package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Attributes short lateral residuals without mistaking sustained geometry for ripple. */
final class UnsupportedRippleEvaluator {
    /**
     * Evaluates one bounded physical support window per profile.
     *
     * @param track corridor identity and direct/interpolated provenance
     * @param tube profile-aligned robust corridor references
     * @param sourcePixelSizePx native source-pixel pitch in sampled-raster pixels
     * @param rippleScaleMeters maximum physical scale of unsupported reversals
     * @param enabled whether configured ripple regularization is active
     * @return immutable profile-aligned support decisions
     */
    List<RippleSupport> evaluate(CorridorTrack track, LongitudinalCorridorTube tube,
        double sourcePixelSizePx, double rippleScaleMeters, boolean enabled) {
        if (!enabled || tube.slices().isEmpty()) {
            return java.util.stream.IntStream.range(0, tube.slices().size())
                .mapToObj(RippleSupport::disabled).toList();
        }
        double sourcePixel = Double.isFinite(sourcePixelSizePx) && sourcePixelSizePx > 0.0
            ? sourcePixelSizePx : 1.0;
        List<RippleSupport> result = new ArrayList<>(tube.slices().size());
        for (CorridorTubeSlice target : tube.slices()) {
            CorridorTrackPoint point = track == null ? null : track.points().get(target.profileIndex());
            if (track != null && (point == null || point.support() != CorridorPointSupport.DIRECT_UNION)) {
                result.add(RippleSupport.noIntervention(target.profileIndex(), 0.0, "non-direct-profile"));
                continue;
            }
            result.add(evaluateWindow(target, contiguousDirectWindow(track, tube,
                target.profileIndex(), rippleScaleMeters), sourcePixel, rippleScaleMeters));
        }
        return List.copyOf(result);
    }

    /** Evaluates synthetic fixtures whose profiles are all directly observed. */
    List<RippleSupport> evaluate(LongitudinalCorridorTube tube, double sourcePixelSizePx,
        double rippleScaleMeters, boolean enabled) {
        return evaluate(null, tube, sourcePixelSizePx, rippleScaleMeters, enabled);
    }

    private List<CorridorTubeSlice> contiguousDirectWindow(CorridorTrack track,
        LongitudinalCorridorTube tube, int targetIndex, double scaleMeters) {
        if (track == null) {
            double targetDistance = tube.at(targetIndex).distanceMeters();
            return tube.slices().stream().filter(slice ->
                Math.abs(slice.distanceMeters() - targetDistance) <= scaleMeters + 1e-9).toList();
        }
        int left = targetIndex;
        int right = targetIndex;
        double targetDistance = tube.at(targetIndex).distanceMeters();
        while (left > 0 && isDirect(track, left - 1)
            && targetDistance - tube.at(left - 1).distanceMeters() <= scaleMeters + 1e-9) {
            left--;
        }
        while (right + 1 < tube.slices().size() && isDirect(track, right + 1)
            && tube.at(right + 1).distanceMeters() - targetDistance <= scaleMeters + 1e-9) {
            right++;
        }
        return List.copyOf(tube.slices().subList(left, right + 1));
    }

    private boolean isDirect(CorridorTrack track, int profileIndex) {
        CorridorTrackPoint point = track.points().get(profileIndex);
        return point != null && point.support() == CorridorPointSupport.DIRECT_UNION;
    }

    private RippleSupport evaluateWindow(CorridorTubeSlice target, List<CorridorTubeSlice> window,
        double sourcePixel, double rippleScaleMeters) {
        double leftSpan = window.isEmpty() ? 0.0
            : target.distanceMeters() - window.get(0).distanceMeters();
        double rightSpan = window.isEmpty() ? 0.0
            : window.get(window.size() - 1).distanceMeters() - target.distanceMeters();
        double directCoverage = clamp((leftSpan + rightSpan)
            / Math.max(1e-9, 2.0 * rippleScaleMeters));
        if (window.size() < 5) {
            return RippleSupport.noIntervention(target.profileIndex(), target.motionSupport(),
                directCoverage, "insufficient-window");
        }
        double span = window.get(window.size() - 1).distanceMeters() - window.get(0).distanceMeters();
        if (span < 0.65 * rippleScaleMeters) {
            return RippleSupport.noIntervention(target.profileIndex(), target.motionSupport(),
                directCoverage, "insufficient-physical-span");
        }
        double minimumSideSpan = 0.325 * rippleScaleMeters;
        if (leftSpan + 1e-9 < minimumSideSpan || rightSpan + 1e-9 < minimumSideSpan) {
            return RippleSupport.noIntervention(target.profileIndex(), target.motionSupport(),
                directCoverage, "boundary-censored-window");
        }
        Trend trend = robustTrend(window, target.distanceMeters(), sourcePixel);
        if (!trend.valid()) {
            return RippleSupport.noIntervention(target.profileIndex(), target.motionSupport(),
                directCoverage, "ill-conditioned-trend");
        }
        List<Double> residualsPx = new ArrayList<>(window.size());
        List<Double> residualsSourcePixels = new ArrayList<>(window.size());
        for (CorridorTubeSlice slice : window) {
            double predicted = trend.centerOffsetPx()
                + trend.slopePxPerMeter() * (slice.distanceMeters() - target.distanceMeters());
            double residual = slice.localCenterOffsetPx() - predicted;
            residualsPx.add(residual);
            residualsSourcePixels.add(residual / sourcePixel);
        }
        double deadband = 0.12 * sourcePixel;
        int previousSign = 0;
        int reversals = 0;
        List<Double> reversalDistances = new ArrayList<>();
        for (int index = 1; index < window.size(); index++) {
            double change = residualsPx.get(index) - residualsPx.get(index - 1);
            int sign = change > deadband ? 1 : change < -deadband ? -1 : 0;
            if (sign != 0) {
                if (previousSign != 0 && sign != previousSign) {
                    reversals++;
                    reversalDistances.add(window.get(index).distanceMeters());
                }
                previousSign = sign;
            }
        }
        double amplitude = robustAmplitude(residualsSourcePixels);
        double authorization = target.scaleConflict() || target.parentMerge() ? 0.0 : trend.authorization();
        if (reversals < 2) {
            return new RippleSupport(target.profileIndex(), clamp(target.motionSupport()),
                clamp(target.motionSupport()), 0.0, amplitude, maximumAbsolute(residualsSourcePixels),
                directCoverage, trend.centerOffsetPx(), trend.slopePxPerMeter(), trend.uncertaintyPx(),
                authorization, 0.0, Double.NaN, reversals,
                target.motionSupport() > 0.0 ? "sustained-motion" : "no-repeated-residual-reversal");
        }
        List<Double> spacings = new ArrayList<>();
        for (int index = 1; index < reversalDistances.size(); index++) {
            spacings.add(reversalDistances.get(index) - reversalDistances.get(index - 1));
        }
        Collections.sort(spacings);
        double medianSpacing = spacings.isEmpty() ? rippleScaleMeters : median(spacings);
        double exposure = clamp((rippleScaleMeters - medianSpacing) / Math.max(1e-9, rippleScaleMeters));
        double unsupported = exposure * smoothStep(0.10, 0.40, amplitude)
            * (1.0 - clamp(target.motionSupport()));
        String reason = authorization <= 0.0 ? "trend-unauthorized-scale-conflict"
            : amplitude <= 0.10 ? "residual-below-amplitude-onset"
            : unsupported > 0.0 ? "unsupported-short-residual" : "supported-motion";
        return new RippleSupport(target.profileIndex(), clamp(target.motionSupport()),
            clamp(target.motionSupport()), exposure, amplitude, maximumAbsolute(residualsSourcePixels),
            directCoverage, trend.centerOffsetPx(), trend.slopePxPerMeter(), trend.uncertaintyPx(),
            authorization, unsupported, medianSpacing, reversals, reason);
    }

    private Trend robustTrend(List<CorridorTubeSlice> window, double targetDistance, double sourcePixel) {
        List<Double> x = window.stream().map(slice -> slice.distanceMeters() - targetDistance).toList();
        List<Double> y = window.stream().map(CorridorTubeSlice::localCenterOffsetPx).toList();
        List<Double> base = window.stream().map(slice -> Math.max(0.05, slice.confidence())
            / square(Math.max(sourcePixel, slice.uncertaintyPx()))).toList();
        AffineFit fit = weightedAffine(x, y, base);
        if (!fit.valid()) return Trend.invalid();
        List<Double> weights = new ArrayList<>(base);
        for (int iteration = 0; iteration < 3; iteration++) {
            List<Double> residuals = residuals(x, y, fit);
            double scale = Math.max(0.05 * sourcePixel, 1.4826 * mad(residuals));
            for (int index = 0; index < weights.size(); index++) {
                double normalized = Math.abs(residuals.get(index)) / Math.max(1e-12, 1.5 * scale);
                weights.set(index, base.get(index) * (normalized <= 1.0 ? 1.0 : 1.0 / normalized));
            }
            fit = weightedAffine(x, y, weights);
            if (!fit.valid()) return Trend.invalid();
        }
        double uncertainty = Math.max(0.05 * sourcePixel, 1.4826 * mad(residuals(x, y, fit)));
        double confidence = window.stream().mapToDouble(CorridorTubeSlice::confidence).average().orElse(0.0);
        double authorization = clamp(confidence)
            * (1.0 - smoothStep(0.50, 1.50, uncertainty / sourcePixel));
        return new Trend(fit.intercept(), fit.slope(), uncertainty, authorization, true);
    }

    private AffineFit weightedAffine(List<Double> x, List<Double> y, List<Double> weights) {
        double total = weights.stream().mapToDouble(Double::doubleValue).sum();
        if (!(total > 1e-12)) return AffineFit.invalid();
        double meanX = 0.0;
        double meanY = 0.0;
        for (int i = 0; i < x.size(); i++) {
            meanX += weights.get(i) * x.get(i);
            meanY += weights.get(i) * y.get(i);
        }
        meanX /= total;
        meanY /= total;
        double covariance = 0.0;
        double variance = 0.0;
        for (int i = 0; i < x.size(); i++) {
            covariance += weights.get(i) * (x.get(i) - meanX) * (y.get(i) - meanY);
            variance += weights.get(i) * square(x.get(i) - meanX);
        }
        if (!(variance > 1e-12)) return AffineFit.invalid();
        double slope = covariance / variance;
        double intercept = meanY - slope * meanX;
        return Double.isFinite(slope) && Double.isFinite(intercept)
            ? new AffineFit(intercept, slope, true) : AffineFit.invalid();
    }

    private List<Double> residuals(List<Double> x, List<Double> y, AffineFit fit) {
        List<Double> result = new ArrayList<>(x.size());
        for (int i = 0; i < x.size(); i++) result.add(y.get(i) - fit.intercept() - fit.slope() * x.get(i));
        return result;
    }

    private double mad(List<Double> values) {
        List<Double> ordered = values.stream().sorted().toList();
        double center = median(ordered);
        return median(ordered.stream().map(value -> Math.abs(value - center)).sorted().toList());
    }

    private double robustAmplitude(List<Double> values) {
        List<Double> absolute = values.stream().map(Math::abs).sorted().toList();
        return absolute.get(Math.max(0, Math.min(absolute.size() - 1,
            (int) Math.ceil(0.80 * absolute.size()) - 1)));
    }

    private double maximumAbsolute(List<Double> values) {
        return values.stream().mapToDouble(Math::abs).max().orElse(0.0);
    }

    private double median(List<Double> sorted) {
        int middle = sorted.size() / 2;
        return (sorted.size() & 1) == 0
            ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0 : sorted.get(middle);
    }

    private double smoothStep(double onset, double full, double value) {
        double x = clamp((value - onset) / Math.max(1e-12, full - onset));
        return x * x * (3.0 - 2.0 * x);
    }

    private double square(double value) { return value * value; }
    private double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }

    /** Immutable physical-window classification and intervention evidence. */
    record RippleSupport(int profileIndex, double support, double supportedTurnWeight,
        double shortScaleExposure, double residualAmplitudeSourcePixels,
        double maximumResidualSourcePixels, double directCoverage, double trendCenterOffsetPx,
        double trendSlopePxPerMeter, double trendUncertaintyPx, double trendAuthorization,
        double unsupportedWeight, double reversalSpacingMeters, int reversalCount, String reason) {
        RippleSupport {
            for (double value : List.of(support, supportedTurnWeight, shortScaleExposure,
                    residualAmplitudeSourcePixels, maximumResidualSourcePixels, directCoverage,
                    trendCenterOffsetPx, trendSlopePxPerMeter, trendUncertaintyPx,
                    trendAuthorization, unsupportedWeight)) {
                if (!Double.isFinite(value)) throw new IllegalArgumentException("Ripple values must be finite");
            }
            reason = Objects.requireNonNull(reason);
            for (double value : List.of(support, supportedTurnWeight, shortScaleExposure,
                    directCoverage, trendAuthorization, unsupportedWeight)) {
                if (value < 0.0 || value > 1.0) {
                    throw new IllegalArgumentException("Ripple weights must be in [0, 1]");
                }
            }
            if (residualAmplitudeSourcePixels < 0.0 || maximumResidualSourcePixels < 0.0
                    || trendUncertaintyPx < 0.0) {
                throw new IllegalArgumentException("Ripple amplitudes and uncertainty must be non-negative");
            }
        }

        static RippleSupport noIntervention(int index, double support, String reason) {
            return noIntervention(index, support, 0.0, reason);
        }

        static RippleSupport noIntervention(int index, double support, double directCoverage, String reason) {
            double bounded = Math.max(0.0, Math.min(1.0, support));
            return new RippleSupport(index, bounded, bounded, 0.0, 0.0, 0.0,
                Math.max(0.0, Math.min(1.0, directCoverage)),
                0.0, 0.0, 0.0, 0.0, 0.0, Double.NaN, 0, reason);
        }

        static RippleSupport disabled(int index) {
            return noIntervention(index, 1.0, "disabled");
        }
    }

    private record AffineFit(double intercept, double slope, boolean valid) {
        static AffineFit invalid() { return new AffineFit(0.0, 0.0, false); }
    }

    private record Trend(double centerOffsetPx, double slopePxPerMeter,
        double uncertaintyPx, double authorization, boolean valid) {
        static Trend invalid() { return new Trend(0.0, 0.0, 0.0, 0.0, false); }
    }
}

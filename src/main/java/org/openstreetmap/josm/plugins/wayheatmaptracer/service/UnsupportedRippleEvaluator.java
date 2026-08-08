package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Evaluates whether short lateral reversals lack sustained longitudinal corridor support. */
final class UnsupportedRippleEvaluator {
    /**
     * Evaluates one physical support window per profile.
     *
     * @param tube profile-aligned robust corridor references
     * @param sourcePixelSizePx native source-pixel pitch in sampled-raster pixels
     * @param rippleScaleMeters maximum physical scale of unsupported reversals
     * @param enabled whether configured ripple regularization is active
     * @return profile-aligned support decisions
     */
    List<RippleSupport> evaluate(
        CorridorTrack track,
        LongitudinalCorridorTube tube,
        double sourcePixelSizePx,
        double rippleScaleMeters,
        boolean enabled
    ) {
        if (!enabled || tube.slices().isEmpty()) {
            return java.util.stream.IntStream.range(0, tube.slices().size())
                .mapToObj(index -> RippleSupport.disabled(index)).toList();
        }
        double sourcePixel = Double.isFinite(sourcePixelSizePx) && sourcePixelSizePx > 0.0
            ? sourcePixelSizePx : 1.0;
        List<RippleSupport> result = new ArrayList<>(tube.slices().size());
        for (CorridorTubeSlice target : tube.slices()) {
            CorridorTrackPoint targetPoint = track == null ? null : track.points().get(target.profileIndex());
            if (track != null && (targetPoint == null
                || targetPoint.support() != CorridorPointSupport.DIRECT_UNION)) {
                result.add(new RippleSupport(target.profileIndex(), 0.0, 0.0, Double.NaN, 0,
                    "non-direct-profile"));
                continue;
            }
            List<CorridorTubeSlice> window = contiguousDirectWindow(
                track, tube, target.profileIndex(), rippleScaleMeters);
            result.add(evaluateWindow(target, window, sourcePixel, rippleScaleMeters));
        }
        return List.copyOf(result);
    }

    /**
     * Evaluates synthetic tube fixtures as directly observed at every profile.
     *
     * @param tube synthetic profile-aligned tube
     * @param sourcePixelSizePx source-pixel pitch in sampled-raster pixels
     * @param rippleScaleMeters physical ripple scale
     * @param enabled whether evaluation is active
     * @return profile-aligned support decisions
     */
    List<RippleSupport> evaluate(
        LongitudinalCorridorTube tube,
        double sourcePixelSizePx,
        double rippleScaleMeters,
        boolean enabled
    ) {
        return evaluate(null, tube, sourcePixelSizePx, rippleScaleMeters, enabled);
    }

    private List<CorridorTubeSlice> contiguousDirectWindow(
        CorridorTrack track,
        LongitudinalCorridorTube tube,
        int targetIndex,
        double scaleMeters
    ) {
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

    private RippleSupport evaluateWindow(
        CorridorTubeSlice target,
        List<CorridorTubeSlice> window,
        double sourcePixel,
        double rippleScaleMeters
    ) {
        if (window.size() < 4) {
            return new RippleSupport(target.profileIndex(), target.motionSupport(), 0.0,
                Double.NaN, 0, "insufficient-window");
        }
        double deadband = 0.12 * sourcePixel;
        int previousSign = 0;
        int reversals = 0;
        List<Double> reversalDistances = new ArrayList<>();
        for (int index = 1; index < window.size(); index++) {
            double change = window.get(index).localCenterOffsetPx()
                - window.get(index - 1).localCenterOffsetPx();
            int sign = change > deadband ? 1 : change < -deadband ? -1 : 0;
            if (sign != 0) {
                if (previousSign != 0 && sign != previousSign) {
                    reversals++;
                    reversalDistances.add(window.get(index).distanceMeters());
                }
                previousSign = sign;
            }
        }
        if (reversals < 2) {
            double support = target.motionSupport();
            return new RippleSupport(target.profileIndex(), support, 0.0, Double.NaN, reversals,
                support > 0.0 ? "sustained-motion" : "no-repeated-reversal");
        }
        List<Double> spacings = new ArrayList<>();
        for (int index = 1; index < reversalDistances.size(); index++) {
            spacings.add(reversalDistances.get(index) - reversalDistances.get(index - 1));
        }
        Collections.sort(spacings);
        double medianSpacing = spacings.isEmpty() ? rippleScaleMeters
            : spacings.get(spacings.size() / 2);
        double shortScaleExposure = clamp((rippleScaleMeters - medianSpacing)
            / Math.max(1e-9, rippleScaleMeters));
        double motionSupport = target.motionSupport();
        double unsupportedWeight = shortScaleExposure * (1.0 - clamp(motionSupport));
        String reason = unsupportedWeight > 0.0 ? "unsupported-short-reversal" : "supported-motion";
        return new RippleSupport(target.profileIndex(), motionSupport, unsupportedWeight,
            medianSpacing, reversals, reason);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Physical-window support result for one profile.
     *
     * @param profileIndex profile index
     * @param support sustained motion/turn support in {@code [0,1]}
     * @param unsupportedWeight additional regularization gate in {@code [0,1]}
     * @param reversalSpacingMeters median physical spacing between reversals, or NaN
     * @param reversalCount sign reversals in the physical window
     * @param reason machine-readable decision
     */
    record RippleSupport(
        int profileIndex,
        double support,
        double unsupportedWeight,
        double reversalSpacingMeters,
        int reversalCount,
        String reason
    ) {
        /**
         * Creates the neutral support result used when ripple evaluation is disabled.
         *
         * @param profileIndex profile index represented by the result
         * @return full support with no ripple penalty
         */
        static RippleSupport disabled(int profileIndex) {
            return new RippleSupport(profileIndex, 1.0, 0.0, Double.NaN, 0, "disabled");
        }
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak;

/**
 * Builds a confidence-weighted longitudinal center reference for one associated corridor identity.
 */
public final class CorridorTubeBuilder {
    private static final double TARGET_HALF_WINDOW_METERS = 5.0;
    private static final int MIN_WINDOW_PROFILES = 5;
    private static final int MAX_WINDOW_PROFILES = 9;
    private static final int HUBER_ITERATIONS = 2;

    /** Creates a stateless tube builder. */
    public CorridorTubeBuilder() {
        // Stateless builder.
    }

    /**
     * Builds one profile-aligned robust corridor tube.
     *
     * @param track associated elementary or explicit parent corridor identity
     * @param profiles extracted fine-level corridor profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param scaleEvidence cross-scale evidence keyed by profile and band id
     * @return robust tube with one slice per profile
     */
    public LongitudinalCorridorTube build(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        double sourcePixelSizePx,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        if (profiles.isEmpty()) {
            return new LongitudinalCorridorTube(List.of());
        }
        double sourcePixel = validSourcePixel(sourcePixelSizePx);
        double[] distanceMeters = cumulativeDistanceMeters(profiles);
        List<Observation> observations = track.points().values().stream()
            .map(point -> observation(point, distanceMeters, scaleEvidence))
            .sorted(Comparator.comparingInt(Observation::profileIndex))
            .toList();
        List<CorridorTubeSlice> slices = new ArrayList<>(profiles.size());
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            CorridorTrackPoint point = track.points().get(profileIndex);
            List<Observation> window = supportWindow(observations, distanceMeters[profileIndex]);
            Regression regression = robustRegression(window, distanceMeters[profileIndex], sourcePixel);
            CorridorBand band = point == null ? null : point.band();
            BandScaleEvidence evidence = band == null ? null : scaleEvidence.get(
                CorridorCenterlineOptimizer.scaleEvidenceKey(profileIndex, band.id()));
            CenterEvidence centers = band == null
                ? CenterEvidence.missing(regression.centerOffsetPx())
                : centers(profiles.get(profileIndex), band);
            double uncertainty = Math.max(sourcePixel * 0.5, regression.residualScalePx());
            if (band != null) {
                uncertainty = Math.max(uncertainty, band.uncertaintyPx());
            }
            slices.add(new CorridorTubeSlice(
                profileIndex,
                distanceMeters[profileIndex],
                regression.centerOffsetPx(),
                regression.tangentOffsetPerMeter(),
                0.0,
                band == null ? Double.NaN : band.coreMinPx(),
                band == null ? Double.NaN : band.coreMaxPx(),
                band == null ? Double.NaN : band.shoulderMinPx(),
                band == null ? Double.NaN : band.shoulderMaxPx(),
                uncertainty,
                regression.confidence(),
                evidence != null && evidence.scaleConflict(),
                evidence != null && evidence.parentMerge(),
                centers.rawCenterPx(),
                centers.lightCenterPx(),
                centers.standardCenterPx(),
                band != null
            ));
        }
        return new LongitudinalCorridorTube(slices);
    }

    private Observation observation(
        CorridorTrackPoint point,
        double[] distanceMeters,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        CorridorBand band = point.band();
        BandScaleEvidence evidence = scaleEvidence.getOrDefault(
            CorridorCenterlineOptimizer.scaleEvidenceKey(point.profileIndex(), band.id()),
            BandScaleEvidence.levelZeroOnly());
        double weight = band.signalExistenceConfidence()
            * (0.25 + 0.75 * band.localizationConfidence())
            * (0.50 + 0.50 * evidence.scalePersistence());
        return new Observation(point.profileIndex(), distanceMeters[point.profileIndex()], band.centerOffsetPx(),
            Math.max(1e-6, weight));
    }

    private List<Observation> supportWindow(List<Observation> observations, double targetDistanceMeters) {
        List<Observation> local = observations.stream()
            .filter(value -> Math.abs(value.distanceMeters() - targetDistanceMeters) <= TARGET_HALF_WINDOW_METERS)
            .sorted(Comparator.comparingDouble(value -> Math.abs(value.distanceMeters() - targetDistanceMeters)))
            .limit(MAX_WINDOW_PROFILES)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (local.size() < Math.min(MIN_WINDOW_PROFILES, observations.size())) {
            for (Observation observation : observations.stream()
                .sorted(Comparator.comparingDouble(value -> Math.abs(value.distanceMeters() - targetDistanceMeters)))
                .toList()) {
                if (!local.contains(observation)) {
                    local.add(observation);
                }
                if (local.size() >= Math.min(MIN_WINDOW_PROFILES, observations.size())) {
                    break;
                }
            }
        }
        return local.stream().sorted(Comparator.comparingDouble(Observation::distanceMeters)).toList();
    }

    private Regression robustRegression(List<Observation> observations, double targetDistance, double sourcePixel) {
        if (observations.isEmpty()) {
            return new Regression(0.0, 0.0, sourcePixel, 0.0);
        }
        double[] weights = observations.stream().mapToDouble(Observation::weight).toArray();
        LinearFit fit = weightedLine(observations, weights, targetDistance);
        double residualScale = sourcePixel * 0.5;
        for (int iteration = 0; iteration < HUBER_ITERATIONS; iteration++) {
            double[] residuals = residuals(observations, fit, targetDistance);
            residualScale = Math.max(sourcePixel * 0.5, 1.5 * weightedMedianAbsolute(residuals, weights));
            for (int index = 0; index < weights.length; index++) {
                double magnitude = Math.abs(residuals[index]);
                double huber = magnitude <= residualScale ? 1.0 : residualScale / Math.max(magnitude, 1e-12);
                weights[index] = observations.get(index).weight() * huber;
            }
            fit = weightedLine(observations, weights, targetDistance);
        }
        double confidence = Math.min(1.0, java.util.Arrays.stream(weights).sum()
            / Math.max(1.0, observations.size()));
        return new Regression(fit.intercept(), fit.slope(), residualScale, confidence);
    }

    private LinearFit weightedLine(List<Observation> observations, double[] weights, double targetDistance) {
        double weightSum = 0.0;
        double xSum = 0.0;
        double ySum = 0.0;
        for (int index = 0; index < observations.size(); index++) {
            double x = observations.get(index).distanceMeters() - targetDistance;
            double weight = weights[index];
            weightSum += weight;
            xSum += weight * x;
            ySum += weight * observations.get(index).centerOffsetPx();
        }
        if (weightSum <= 1e-12) {
            return new LinearFit(observations.get(0).centerOffsetPx(), 0.0);
        }
        double meanX = xSum / weightSum;
        double meanY = ySum / weightSum;
        double covariance = 0.0;
        double variance = 0.0;
        for (int index = 0; index < observations.size(); index++) {
            double x = observations.get(index).distanceMeters() - targetDistance;
            double dx = x - meanX;
            covariance += weights[index] * dx * (observations.get(index).centerOffsetPx() - meanY);
            variance += weights[index] * dx * dx;
        }
        double slope = variance <= 1e-12 ? 0.0 : covariance / variance;
        return new LinearFit(meanY - slope * meanX, slope);
    }

    private double[] residuals(List<Observation> observations, LinearFit fit, double targetDistance) {
        double[] residuals = new double[observations.size()];
        for (int index = 0; index < observations.size(); index++) {
            double x = observations.get(index).distanceMeters() - targetDistance;
            residuals[index] = observations.get(index).centerOffsetPx() - (fit.intercept() + fit.slope() * x);
        }
        return residuals;
    }

    private double weightedMedianAbsolute(double[] values, double[] weights) {
        List<WeightedValue> ordered = new ArrayList<>(values.length);
        for (int index = 0; index < values.length; index++) {
            ordered.add(new WeightedValue(Math.abs(values[index]), weights[index]));
        }
        ordered.sort(Comparator.comparingDouble(WeightedValue::value));
        double total = ordered.stream().mapToDouble(WeightedValue::weight).sum();
        double cumulative = 0.0;
        for (WeightedValue value : ordered) {
            cumulative += value.weight();
            if (cumulative >= total / 2.0) {
                return value.value();
            }
        }
        return 0.0;
    }

    private CenterEvidence centers(CorridorProfile profile, CorridorBand band) {
        CrossSectionPeak peak = profile.source().peaks().stream()
            .min(Comparator.comparingDouble(value -> Math.abs(value.offsetPx() - band.centerOffsetPx())))
            .orElse(null);
        return peak == null
            ? new CenterEvidence(band.centerOffsetPx(), band.centerOffsetPx(), band.centerOffsetPx())
            : new CenterEvidence(peak.rawCenterPx(), peak.lightFilteredCenterPx(), peak.standardFilteredCenterPx());
    }

    private double[] cumulativeDistanceMeters(List<CorridorProfile> profiles) {
        double[] result = new double[profiles.size()];
        if (profiles.isEmpty()) {
            return result;
        }
        double origin = profiles.get(0).source().cumulativeGroundDistanceMeters();
        for (int index = 0; index < profiles.size(); index++) {
            result[index] = profiles.get(index).source().cumulativeGroundDistanceMeters() - origin;
        }
        return result;
    }

    private double validSourcePixel(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    private record Observation(
        int profileIndex,
        double distanceMeters,
        double centerOffsetPx,
        double weight
    ) {
    }

    private record CenterEvidence(double rawCenterPx, double lightCenterPx, double standardCenterPx) {
        private static CenterEvidence missing(double centerOffsetPx) {
            return new CenterEvidence(centerOffsetPx, centerOffsetPx, centerOffsetPx);
        }
    }

    private record LinearFit(double intercept, double slope) {
    }

    private record Regression(
        double centerOffsetPx,
        double tangentOffsetPerMeter,
        double residualScalePx,
        double confidence
    ) {
    }

    private record WeightedValue(double value, double weight) {
    }
}

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
    private static final double LOCAL_HALF_WINDOW_METERS = 5.0;
    private static final int LOCAL_MIN_WINDOW_PROFILES = 5;
    private static final int LOCAL_MAX_WINDOW_PROFILES = 9;
    private static final double STABILITY_HALF_WINDOW_METERS = 12.0;
    private static final int STABILITY_MIN_WINDOW_PROFILES = 9;
    private static final int STABILITY_MAX_WINDOW_PROFILES = 17;
    private static final double WEAK_STABILITY_HALF_WINDOW_METERS = 32.0;
    private static final int WEAK_STABILITY_MIN_WINDOW_PROFILES = 17;
    private static final int WEAK_STABILITY_MAX_WINDOW_PROFILES = 33;
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
            .map(point -> observation(point, profiles.get(point.profileIndex()), distanceMeters, sourcePixel,
                scaleEvidence))
            .sorted(Comparator.comparingInt(Observation::profileIndex))
            .toList();
        List<Observation> directObservations = observations.stream()
            .filter(value -> value.support() == CorridorPointSupport.DIRECT_UNION).toList();
        List<CorridorTubeSlice> slices = new ArrayList<>(profiles.size());
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            CorridorTrackPoint point = track.points().get(profileIndex);
            boolean directlyObserved = point != null && point.support() == CorridorPointSupport.DIRECT_UNION;
            boolean allowMinimumExpansion = !track.parent() || directlyObserved;
            List<Observation> localWindow = supportWindow(observations, distanceMeters[profileIndex],
                LOCAL_HALF_WINDOW_METERS, LOCAL_MIN_WINDOW_PROFILES, LOCAL_MAX_WINDOW_PROFILES,
                allowMinimumExpansion);
            List<Observation> stabilityWindow = supportWindow(observations, distanceMeters[profileIndex],
                STABILITY_HALF_WINDOW_METERS, STABILITY_MIN_WINDOW_PROFILES, STABILITY_MAX_WINDOW_PROFILES,
                allowMinimumExpansion);
            List<Observation> directStabilityWindow = supportWindow(directObservations,
                distanceMeters[profileIndex], STABILITY_HALF_WINDOW_METERS, STABILITY_MIN_WINDOW_PROFILES,
                STABILITY_MAX_WINDOW_PROFILES, allowMinimumExpansion);
            List<Observation> weakStabilityWindow = supportWindow(observations, distanceMeters[profileIndex],
                WEAK_STABILITY_HALF_WINDOW_METERS, WEAK_STABILITY_MIN_WINDOW_PROFILES,
                WEAK_STABILITY_MAX_WINDOW_PROFILES, allowMinimumExpansion);
            Regression local = robustRegression(localWindow, distanceMeters[profileIndex], sourcePixel);
            Regression stability = robustRegression(stabilityWindow, distanceMeters[profileIndex], sourcePixel);
            Regression weakStability = robustRegression(
                weakStabilityWindow, distanceMeters[profileIndex], sourcePixel);
            MotionEvidence motion = motionSupport(directStabilityWindow, sourcePixel);
            double motionSupport = motion.support();
            CorridorBand band = point == null ? null : point.band();
            double prominence = band == null ? 0.0 : Math.max(0.0, band.peakIntensity() - band.noiseFloor());
            double weakSignal = clamp((0.35 - prominence) / 0.30);
            double weakStabilityWeight = weakSignal * (1.0 - motionSupport);
            double stabilityCenter = blend(stability.centerOffsetPx(), weakStability.centerOffsetPx(),
                weakStabilityWeight);
            double stabilityTangent = blend(stability.tangentOffsetPerMeter(),
                weakStability.tangentOffsetPerMeter(), weakStabilityWeight);
            double stabilityResidual = blend(stability.residualScalePx(), weakStability.residualScalePx(),
                weakStabilityWeight);
            double stabilityConfidence = blend(stability.confidence(), weakStability.confidence(),
                weakStabilityWeight);
            double effectiveCenter = blend(stabilityCenter, local.centerOffsetPx(), motionSupport);
            double effectiveTangent = blend(stabilityTangent, local.tangentOffsetPerMeter(),
                motionSupport);
            BandScaleEvidence evidence = band == null ? null : scaleEvidence.get(
                CorridorCenterlineOptimizer.scaleEvidenceKey(profileIndex, band.id()));
            CenterEvidence centers = band == null
                ? CenterEvidence.missing(effectiveCenter)
                : centers(profiles.get(profileIndex), band);
            double uncertainty = Math.max(sourcePixel * 0.5,
                blend(stabilityResidual, local.residualScalePx(), motionSupport));
            if (band != null) {
                uncertainty = Math.max(uncertainty, band.uncertaintyPx());
            }
            slices.add(new CorridorTubeSlice(
                profileIndex,
                distanceMeters[profileIndex],
                effectiveCenter,
                effectiveTangent,
                local.centerOffsetPx(),
                local.tangentOffsetPerMeter(),
                stabilityCenter,
                stabilityTangent,
                stabilityResidual,
                motionSupport,
                motion.reason(),
                0.0,
                band == null ? Double.NaN : band.coreMinPx(),
                band == null ? Double.NaN : band.coreMaxPx(),
                band == null ? Double.NaN : band.shoulderMinPx(),
                band == null ? Double.NaN : band.shoulderMaxPx(),
                uncertainty,
                blend(stabilityConfidence, local.confidence(), motionSupport),
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
        CorridorProfile profile,
        double[] distanceMeters,
        double sourcePixel,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        CorridorBand band = point.band();
        BandScaleEvidence evidence = scaleEvidence.getOrDefault(
            CorridorCenterlineOptimizer.scaleEvidenceKey(point.profileIndex(), band.id()),
            BandScaleEvidence.levelZeroOnly());
        double weight = band.signalExistenceConfidence()
            * (0.25 + 0.75 * band.localizationConfidence())
            * (0.50 + 0.50 * evidence.scalePersistence());
        if (point.support() == CorridorPointSupport.BOUNDED_INTERPOLATION) {
            weight *= 0.45;
        }
        CenterEvidence centers = centers(profile, band);
        List<Double> semanticCenters = new ArrayList<>(band.nestedCentersPx());
        double coreCenter = (band.coreMinPx() + band.coreMaxPx()) / 2.0;
        semanticCenters.add(coreCenter);
        if (!profile.source().peaks().isEmpty()) {
            semanticCenters.add(centers.rawCenterPx());
            semanticCenters.add(centers.lightCenterPx());
            semanticCenters.add(centers.standardCenterPx());
        }
        semanticCenters.sort(Comparator.naturalOrder());
        int middle = semanticCenters.size() / 2;
        double semanticCenter = semanticCenters.size() % 2 == 0
            ? 0.5 * (semanticCenters.get(middle - 1) + semanticCenters.get(middle))
            : semanticCenters.get(middle);
        double semanticSpread = semanticCenters.get(semanticCenters.size() - 1) - semanticCenters.get(0);
        double normalizedSpread = semanticSpread / sourcePixel;
        double channelCenterSpread = Math.max(Math.abs(centers.rawCenterPx() - centers.lightCenterPx()),
            Math.max(Math.abs(centers.rawCenterPx() - centers.standardCenterPx()),
                Math.abs(centers.lightCenterPx() - centers.standardCenterPx())));
        double centerAgreement = Math.exp(-normalizedSpread * normalizedSpread);
        double prominence = Math.max(0.0, band.peakIntensity() - band.noiseFloor());
        double semanticCenterStrength = clamp((prominence - 0.25) / 0.30);
        double centerIdentifiability = band.hasMeasuredCenter() && !band.parentHypothesis()
            ? semanticCenterStrength
                * clamp(0.75 * centerAgreement + 0.25 * band.localizationConfidence()) : 0.0;
        if (evidence.scaleConflict() || evidence.parentMerge()) {
            centerIdentifiability *= 0.25;
        }
        double positionCenter = band.centerOffsetPx()
            + centerIdentifiability * (semanticCenter - band.centerOffsetPx());
        return new Observation(point.profileIndex(), distanceMeters[point.profileIndex()], positionCenter,
            Math.max(1e-6, weight), band.uncertaintyPx(), channelCenterSpread / sourcePixel,
            evidence.scaleConflict(), evidence.parentMerge(), point.support());
    }

    private List<Observation> supportWindow(
        List<Observation> observations,
        double targetDistanceMeters,
        double halfWindowMeters,
        int minimumProfiles,
        int maximumProfiles,
        boolean allowMinimumExpansion
    ) {
        int low = 0;
        int high = observations.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (observations.get(middle).distanceMeters() < targetDistanceMeters) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        int left = low - 1;
        int right = low;
        List<Observation> local = new ArrayList<>(Math.min(maximumProfiles, observations.size()));
        while (local.size() < maximumProfiles) {
            double leftDistance = left >= 0
                ? Math.abs(observations.get(left).distanceMeters() - targetDistanceMeters)
                : Double.POSITIVE_INFINITY;
            double rightDistance = right < observations.size()
                ? Math.abs(observations.get(right).distanceMeters() - targetDistanceMeters)
                : Double.POSITIVE_INFINITY;
            if (Math.min(leftDistance, rightDistance) > halfWindowMeters + 1e-9) {
                break;
            }
            if (leftDistance <= rightDistance) {
                local.add(observations.get(left--));
            } else {
                local.add(observations.get(right++));
            }
        }
        while (allowMinimumExpansion
            && local.size() < Math.min(minimumProfiles, observations.size())) {
            double leftDistance = left >= 0
                ? Math.abs(observations.get(left).distanceMeters() - targetDistanceMeters)
                : Double.POSITIVE_INFINITY;
            double rightDistance = right < observations.size()
                ? Math.abs(observations.get(right).distanceMeters() - targetDistanceMeters)
                : Double.POSITIVE_INFINITY;
            if (!Double.isFinite(Math.min(leftDistance, rightDistance))) {
                break;
            }
            if (leftDistance <= rightDistance) {
                local.add(observations.get(left--));
            } else {
                local.add(observations.get(right++));
            }
        }
        return local.stream().sorted(Comparator.comparingDouble(Observation::distanceMeters)).toList();
    }

    private MotionEvidence motionSupport(List<Observation> observations, double sourcePixel) {
        if (observations.size() < 5) {
            return new MotionEvidence(0.0, "insufficient-direct-profiles");
        }
        double span = observations.get(observations.size() - 1).distanceMeters()
            - observations.get(0).distanceMeters();
        if (span < 8.0) {
            return new MotionEvidence(0.0, "insufficient-span");
        }
        int positive = 0;
        int negative = 0;
        int signChanges = 0;
        int previousSign = 0;
        double deadband = 0.15 * sourcePixel;
        for (int index = 1; index < observations.size(); index++) {
            double motion = observations.get(index).centerOffsetPx()
                - observations.get(index - 1).centerOffsetPx();
            int sign = 0;
            if (motion > deadband) {
                positive++;
                sign = 1;
            } else if (motion < -deadband) {
                negative++;
                sign = -1;
            }
            if (sign != 0) {
                if (previousSign != 0 && sign != previousSign) {
                    signChanges++;
                }
                previousSign = sign;
            }
        }
        int directional = positive + negative;
        if (directional == 0) {
            return new MotionEvidence(0.0, "stationary");
        }
        double coherence = (double) Math.max(positive, negative) / directional;
        double displacement = Math.abs(observations.get(observations.size() - 1).centerOffsetPx()
            - observations.get(0).centerOffsetPx());
        double motionRange = observations.stream().mapToDouble(Observation::centerOffsetPx).max().orElse(0.0)
            - observations.stream().mapToDouble(Observation::centerOffsetPx).min().orElse(0.0);
        double uncertainty = observations.stream().mapToDouble(Observation::uncertaintyPx).average()
            .orElse(sourcePixel);
        double centerAgreement = observations.stream()
            .mapToDouble(value -> clamp(1.0 - value.centerSpreadSourcePx())).average().orElse(0.0);
        double conflictFraction = observations.stream().filter(Observation::scaleConflict).count()
            / (double) observations.size();
        boolean coherentDirection = coherence >= 0.70 && displacement > Math.max(sourcePixel, uncertainty);
        boolean supportedApex = signChanges <= 1 && positive >= 2 && negative >= 2
            && motionRange > Math.max(sourcePixel, uncertainty);
        if (!coherentDirection && !supportedApex) {
            return new MotionEvidence(0.0, signChanges > 1 ? "reversing-noise" : "incoherent-motion");
        }
        double coherenceScore = supportedApex ? 1.0 : clamp((coherence - 0.70) / 0.30);
        double supportedDisplacement = supportedApex ? motionRange : displacement;
        double displacementScore = clamp((supportedDisplacement - Math.max(sourcePixel, uncertainty)) / sourcePixel);
        double support = clamp(coherenceScore * displacementScore * centerAgreement * (1.0 - conflictFraction));
        String reason = supportedApex ? "supported-apex" : "coherent-direction";
        if (centerAgreement < 0.75) {
            reason += "+center-disagreement";
        }
        if (conflictFraction > 0.0) {
            reason += "+scale-conflict";
        }
        return new MotionEvidence(support, reason);
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

    private double blend(double stability, double local, double motionSupport) {
        return stability + clamp(motionSupport) * (local - stability);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record Observation(
        int profileIndex,
        double distanceMeters,
        double centerOffsetPx,
        double weight,
        double uncertaintyPx,
        double centerSpreadSourcePx,
        boolean scaleConflict,
        boolean parentMerge,
        CorridorPointSupport support
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

    private record MotionEvidence(double support, String reason) {
    }
}

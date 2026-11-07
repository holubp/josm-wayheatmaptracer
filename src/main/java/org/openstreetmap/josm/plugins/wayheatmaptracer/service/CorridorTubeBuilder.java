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
    private final MotionPolicy motionPolicy;

    /** Explicit policy identity; historical replay/default construction remains unchanged. */
    public enum MotionPolicy { HISTORICAL, BOUNDED_SECONDARY_REVERSAL, TARGET_OWNED_APEX }

    /** Creates a stateless tube builder. */
    public CorridorTubeBuilder() {
        this(MotionPolicy.HISTORICAL);
    }

    /** Creates an explicitly opted-in motion classifier without changing fit/window parameters. */
    public CorridorTubeBuilder(MotionPolicy motionPolicy) {
        this.motionPolicy = java.util.Objects.requireNonNull(motionPolicy);
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
            MotionEvidence motion = motionSupport(directStabilityWindow, sourcePixel, distanceMeters[profileIndex]);
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
        return motionSupport(observations, sourcePixel, Double.NaN);
    }

    private MotionEvidence motionSupport(List<Observation> observations, double sourcePixel,
            double targetDistanceMeters) {
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
        boolean boundedApex = !coherentDirection && !supportedApex && signChanges > 1
            && motionPolicy == MotionPolicy.BOUNDED_SECONDARY_REVERSAL
            && !boundedApexWitnesses(observations, sourcePixel, Math.max(sourcePixel, uncertainty)).isEmpty();
        List<OwnedTurnWitness> owned = !coherentDirection && !supportedApex && signChanges > 1
            && motionPolicy == MotionPolicy.TARGET_OWNED_APEX
            ? ownedTurnWitnesses(observations, sourcePixel, Math.max(sourcePixel, uncertainty), targetDistanceMeters)
            : List.of();
        boolean ownedApex = !owned.isEmpty();
        boolean authorizedApex = supportedApex || boundedApex || ownedApex;
        if (!coherentDirection && !authorizedApex) {
            return new MotionEvidence(0.0, signChanges > 1 ? "reversing-noise" : "incoherent-motion");
        }
        double coherenceScore = authorizedApex ? 1.0 : clamp((coherence - 0.70) / 0.30);
        double supportedDisplacement = ownedApex
            ? owned.stream().mapToDouble(OwnedTurnWitness::ownedRangePx).max().orElseThrow()
            : authorizedApex ? motionRange : displacement;
        double displacementScore = clamp((supportedDisplacement - Math.max(sourcePixel, uncertainty)) / sourcePixel);
        double support = clamp(coherenceScore * displacementScore * centerAgreement * (1.0 - conflictFraction));
        String reason = ownedApex ? "target-owned-apex" : boundedApex ? "supported-apex-bounded-reversal"
            : supportedApex ? "supported-apex" : "coherent-direction";
        if (centerAgreement < 0.75) {
            reason += "+center-disagreement";
        }
        if (conflictFraction > 0.0) {
            reason += "+scale-conflict";
        }
        return new MotionEvidence(support, reason);
    }

    /** Direct, local turn ownership only; neighboring extrema delimit each completed arm. */
    private List<OwnedTurnWitness> ownedTurnWitnesses(List<Observation> observations,
            double sourcePixel, double threshold, double targetDistanceMeters) {
        if (!Double.isFinite(targetDistanceMeters)) return List.of();
        List<ObservedTurn> turns = new ArrayList<>();
        double deadband = 0.15 * sourcePixel;
        for (int start = 0; start < observations.size();) {
            int end = start;
            while (end + 1 < observations.size()
                    && observations.get(end + 1).profileIndex() == observations.get(end).profileIndex() + 1) end++;
            int previousSign = 0, previousStep = start;
            for (int step = start + 1; step <= end; step++) {
                double delta = observations.get(step).centerOffsetPx() - observations.get(step - 1).centerOffsetPx();
                int sign = delta > deadband ? 1 : delta < -deadband ? -1 : 0;
                if (sign == 0) continue;
                if (previousSign != 0 && sign != previousSign) {
                    double extreme = previousSign * observations.get(previousStep).centerOffsetPx();
                    int first = previousStep, last = previousStep;
                    for (int i = previousStep + 1; i < step; i++) {
                        double value = previousSign * observations.get(i).centerOffsetPx();
                        if (value > extreme) { extreme = value; first = i; last = i; }
                        else if (value == extreme) last = i;
                    }
                    double position = (observations.get(first).distanceMeters()
                        + observations.get(last).distanceMeters()) / 2.0;
                    turns.add(new ObservedTurn(first, last, previousSign, start, end, position));
                }
                previousSign = sign;
                previousStep = step;
            }
            start = end + 1;
        }
        double nearest = turns.stream().mapToDouble(t -> Math.abs(t.distanceMeters() - targetDistanceMeters))
            .min().orElse(Double.POSITIVE_INFINITY);
        List<OwnedTurnWitness> result = new ArrayList<>();
        for (int index = 0; index < turns.size(); index++) {
            ObservedTurn turn = turns.get(index);
            double distance = Math.abs(turn.distanceMeters() - targetDistanceMeters);
            if (distance != nearest || distance > LOCAL_HALF_WINDOW_METERS) continue;
            int left = index > 0 && turns.get(index - 1).segmentStart() == turn.segmentStart()
                ? turns.get(index - 1).last() : turn.segmentStart();
            int right = index + 1 < turns.size() && turns.get(index + 1).segmentStart() == turn.segmentStart()
                ? turns.get(index + 1).first() : turn.segmentEnd();
            if (observations.get(right).distanceMeters() - observations.get(left).distanceMeters() < 8.0) continue;
            double peak = turn.sign() * observations.get(turn.first()).centerOffsetPx();
            double leftExcursion = peak - turn.sign() * observations.get(left).centerOffsetPx();
            double rightExcursion = peak - turn.sign() * observations.get(right).centerOffsetPx();
            int rising = 0, falling = 0;
            double leftViolation = 0, rightViolation = 0, minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
            for (int i = left; i <= right; i++) {
                double value = observations.get(i).centerOffsetPx();
                minimum = Math.min(minimum, value); maximum = Math.max(maximum, value);
                if (i > left && i <= turn.first() && turn.sign() * (value - observations.get(i - 1).centerOffsetPx()) > deadband) rising++;
                if (i > turn.last() && turn.sign() * (observations.get(i - 1).centerOffsetPx() - value) > deadband) falling++;
            }
            for (int i = left; i <= turn.last(); i++) for (int j = i + 1; j <= turn.last(); j++)
                leftViolation = Math.max(leftViolation, turn.sign() * (observations.get(i).centerOffsetPx() - observations.get(j).centerOffsetPx()));
            for (int i = turn.first(); i <= right; i++) for (int j = i + 1; j <= right; j++)
                rightViolation = Math.max(rightViolation, turn.sign() * (observations.get(j).centerOffsetPx() - observations.get(i).centerOffsetPx()));
            if (leftExcursion > threshold && rightExcursion > threshold && rising >= 2 && falling >= 2
                    && leftViolation <= threshold && rightViolation <= threshold) {
                result.add(new OwnedTurnWitness(turn.sign() > 0 ? "peak" : "valley",
                    observations.get(left).profileIndex(), observations.get(turn.first()).profileIndex(),
                    observations.get(turn.last()).profileIndex(), observations.get(right).profileIndex(),
                    turn.distanceMeters(), rising, falling, leftExcursion, rightExcursion,
                    leftViolation, rightViolation, threshold, maximum - minimum));
            }
        }
        return List.copyOf(result);
    }

    private record ObservedTurn(int first, int last, int sign, int segmentStart, int segmentEnd,
        double distanceMeters) { }
    private record OwnedTurnWitness(String kind, int leftProfile, int firstApexProfile, int lastApexProfile,
        int rightProfile, double apexDistanceMeters, int risingSteps, int fallingSteps,
        double leftExcursionPx, double rightExcursionPx, double leftViolationPx, double rightViolationPx,
        double thresholdPx, double ownedRangePx) { }

    /** Boolean authorization witnesses only: no fit or proposed positions are produced. */
    private List<BoundedApexWitness> boundedApexWitnesses(List<Observation> observations,
            double sourcePixel, double threshold) {
        List<BoundedApexWitness> result = new ArrayList<>();
        double deadband = 0.15 * sourcePixel;
        for (double sign : List.of(1.0, -1.0)) {
            for (int apex = 2; apex + 2 < observations.size(); apex++) {
                double peak = sign * observations.get(apex).centerOffsetPx();
                double leftExcursion = peak - sign * observations.get(0).centerOffsetPx();
                double rightExcursion = peak
                    - sign * observations.get(observations.size() - 1).centerOffsetPx();
                if (leftExcursion <= threshold || rightExcursion <= threshold) continue;
                int rising = 0, falling = 0;
                for (int i = 1; i <= apex; i++) {
                    if (sign * (observations.get(i).centerOffsetPx()
                            - observations.get(i - 1).centerOffsetPx()) > deadband) rising++;
                }
                for (int i = apex + 1; i < observations.size(); i++) {
                    if (sign * (observations.get(i - 1).centerOffsetPx()
                            - observations.get(i).centerOffsetPx()) > deadband) falling++;
                }
                if (rising < 2 || falling < 2) continue;
                double leftViolation = 0.0, rightViolation = 0.0;
                for (int i = 0; i <= apex; i++) for (int j = i + 1; j <= apex; j++) {
                    leftViolation = Math.max(leftViolation, sign * (observations.get(i).centerOffsetPx()
                        - observations.get(j).centerOffsetPx()));
                }
                for (int i = apex; i < observations.size(); i++) {
                    for (int j = i + 1; j < observations.size(); j++) {
                        rightViolation = Math.max(rightViolation,
                            sign * (observations.get(j).centerOffsetPx()
                                - observations.get(i).centerOffsetPx()));
                    }
                }
                if (leftViolation <= threshold && rightViolation <= threshold) {
                    result.add(new BoundedApexWitness(sign > 0 ? "peak" : "valley",
                        observations.get(0).profileIndex(), observations.get(apex).profileIndex(),
                        observations.get(observations.size() - 1).profileIndex(), rising, falling,
                        leftExcursion, rightExcursion, leftViolation, rightViolation, threshold));
                }
            }
        }
        return List.copyOf(result);
    }

    private record BoundedApexWitness(String kind, int firstProfile, int apexProfile, int lastProfile,
        int qualifyingRisingSteps, int qualifyingFallingSteps, double leftExcursionPx,
        double rightExcursionPx, double leftViolationPx, double rightViolationPx, double thresholdPx) { }

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

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorQuality;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

/**
 * Selects a stable centerline inside one longitudinal corridor using second-order dynamic programming.
 */
public final class CorridorCenterlineOptimizer {
    private final CorridorOptimizationParameters parameters;

    /**
     * Creates a stateless centerline optimizer.
     */
    public CorridorCenterlineOptimizer() {
        this(CorridorOptimizationParameters.defaults());
    }

    /**
     * Creates an optimizer with explicit documented parameters.
     *
     * @param parameters immutable corridor-aware optimization parameters
     */
    public CorridorCenterlineOptimizer(CorridorOptimizationParameters parameters) {
        this.parameters = java.util.Objects.requireNonNull(parameters);
    }

    /**
     * Optimizes one corridor track without endpoint constraints.
     *
     * @param track selected corridor identity
     * @param profiles profile-aligned corridor evidence
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @return optimized geometry and decomposed costs
     */
    public OptimizationResult optimize(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        double sourcePixelSizePx
    ) {
        return optimize(track, profiles, sourcePixelSizePx, JunctionContext.empty(), Map.of());
    }

    /**
     * Optimizes one corridor track with endpoint and junction constraints.
     *
     * @param track selected corridor identity
     * @param profiles profile-aligned corridor evidence
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param junctionContext endpoint and junction boundary conditions
     * @return optimized geometry and decomposed costs
     */
    public OptimizationResult optimize(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        double sourcePixelSizePx,
        JunctionContext junctionContext
    ) {
        return optimize(track, profiles, sourcePixelSizePx, junctionContext, Map.of());
    }

    /**
     * Optimizes one track with endpoint constraints and cross-scale localization evidence.
     *
     * @param track selected corridor identity
     * @param profiles fine L0 corridor profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param junctionContext endpoint and junction constraints
     * @param scaleEvidence cross-scale evidence keyed by profile index and fine band id
     * @return optimized fine-coordinate geometry
     */
    public OptimizationResult optimize(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        double sourcePixelSizePx,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        LongitudinalCorridorTube tube = new CorridorTubeBuilder().build(
            track, profiles, sourcePixelSizePx, scaleEvidence);
        return optimize(track, profiles, sourcePixelSizePx, junctionContext, scaleEvidence, tube);
    }

    /**
     * Optimizes one track against an already built longitudinal corridor tube.
     *
     * @param track selected corridor identity
     * @param profiles fine L0 corridor profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param junctionContext endpoint and junction constraints
     * @param scaleEvidence cross-scale evidence keyed by profile index and fine band id
     * @param tube profile-aligned robust longitudinal reference for this track
     * @return optimized fine-coordinate geometry
     */
    public OptimizationResult optimize(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        double sourcePixelSizePx,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence,
        LongitudinalCorridorTube tube
    ) {
        if (profiles.isEmpty() || track.points().isEmpty()) {
            return OptimizationResult.empty();
        }
        if (tube.slices().size() != profiles.size()) {
            throw new IllegalArgumentException("Corridor tube must be profile aligned");
        }
        EndpointApproachModel endpointApproaches = new EndpointApproachBuilder().build(
            track, profiles, tube, junctionContext, scaleEvidence);
        double sourcePixel = validSourcePixel(sourcePixelSizePx);
        List<List<Double>> allowed = new ArrayList<>(profiles.size());
        for (int i = 0; i < profiles.size(); i++) {
            allowed.add(allowedOffsets(track, profiles, i, junctionContext, scaleEvidence, tube.at(i),
                endpointApproaches));
        }
        ExactSolution exactSolution = solveExact(track, profiles, allowed, sourcePixel, junctionContext,
            scaleEvidence, tube, endpointApproaches);
        List<Double> optimizedOffsets = exactSolution.offsetsPx();
        List<Point2D.Double> points = new ArrayList<>(profiles.size());
        List<CostRow> costs = new ArrayList<>(profiles.size());
        int inCorridor = 0;
        double accelerationEnergy = 0.0;
        for (int i = 0; i < profiles.size(); i++) {
            double offset = optimizedOffsets.get(i);
            CorridorProfile profile = profiles.get(i);
            points.add(new Point2D.Double(
                profile.source().anchorScreen().x + profile.source().normalScreen().x * offset,
                profile.source().anchorScreen().y + profile.source().normalScreen().y * offset
            ));
            CorridorBand band = bandAt(track, i);
            boolean contained = band == null || (offset >= band.shoulderMinPx() - 1e-9 && offset <= band.shoulderMaxPx() + 1e-9);
            boolean insideCore = band != null && offset >= band.coreMinPx() - 1e-9 && offset <= band.coreMaxPx() + 1e-9;
            if (contained) {
                inCorridor++;
            }
            DataCost dataComponents = dataCostComponents(track, profile, i, offset, sourcePixel, scaleEvidence,
                tube.at(i), heatmapWeight(endpointApproaches, i));
            double data = dataComponents.total();
            double continuity = i == 0 ? 0.0 : continuityWeight(track, i)
                * square((offset - optimizedOffsets.get(i - 1)) / profileSpacing(profiles, i, sourcePixel));
            double acceleration = i < 2 ? 0.0 : accelerationWeight(track, i, scaleEvidence)
                * geometricCurvatureCost(profiles, optimizedOffsets, tube, i, sourcePixel);
            accelerationEnergy += acceleration;
            double endpoint = constraintCost(i, offset, junctionContext, endpointApproaches, sourcePixel);
            double spacing = i == 0 && profiles.size() > 1
                ? profileSpacing(profiles, 1, sourcePixel)
                : (i == 0 ? sourcePixel : profileSpacing(profiles, i, sourcePixel));
            costs.add(new CostRow(i, offset, spacing, data, continuity, acceleration,
                dataComponents.plateauCenterCost(), dataComponents.coarsePriorCost(),
                dataComponents.tubeCenterCost(), endpoint,
                data + continuity + acceleration + endpoint, insideCore, contained));
        }
        double inCorridorFraction = (double) inCorridor / profiles.size();
        double stability = 1.0 / (1.0 + accelerationEnergy / Math.max(1, profiles.size() - 2));
        double totalCost = costs.stream().mapToDouble(CostRow::weightedTotal).sum();
        CorridorQuality quality = new CorridorQualityCalculator().calculate(track, profiles, tube,
            optimizedOffsets, points, sourcePixel, endpointApproaches);
        return new OptimizationResult(optimizedOffsets, points, totalCost, costs, inCorridorFraction, stability,
            quality, endpointApproaches, exactSolution.maximumOffsetStates(), exactSolution.maximumPairStates(),
            exactSolution.transitionEvaluations());
    }

    private ExactSolution solveExact(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        List<List<Double>> allowed,
        double sourcePixel,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence,
        LongitudinalCorridorTube tube,
        EndpointApproachModel endpointApproaches
    ) {
        if (profiles.size() == 1) {
            double best = allowed.get(0).stream().min(Comparator
                .comparingDouble((Double offset) -> profileCost(track, profiles, 0, offset, sourcePixel,
                    junctionContext, scaleEvidence, tube, endpointApproaches))
                .thenComparingDouble(Double::doubleValue)).orElseThrow();
            return new ExactSolution(List.of(best), allowed.get(0).size(), allowed.get(0).size(),
                allowed.get(0).size());
        }

        Map<PairKey, PairState> states = new LinkedHashMap<>();
        long transitionEvaluations = 0L;
        int maximumOffsetStates = allowed.stream().mapToInt(List::size).max().orElse(0);
        double spacing = profileSpacing(profiles, 1, sourcePixel);
        double spacingSourcePixels = spacing / sourcePixel;
        for (double first : allowed.get(0)) {
            double firstCost = profileCost(track, profiles, 0, first, sourcePixel, junctionContext,
                scaleEvidence, tube, endpointApproaches);
            for (double second : allowed.get(1)) {
                transitionEvaluations++;
                Point2D.Double firstPoint = pointFor(profiles.get(0), first);
                Point2D.Double secondPoint = pointFor(profiles.get(1), second);
                double heading = heading(firstPoint, secondPoint);
                double referenceHeading = tubeHeading(profiles, tube, 0, 1);
                double continuity = continuityWeight(track, 1) * square((second - first) / spacing);
                double cost = firstCost + profileCost(track, profiles, 1, second, sourcePixel,
                    junctionContext, scaleEvidence, tube, endpointApproaches) + continuity;
                PairState candidate = new PairState(1, first, second, heading, referenceHeading,
                    spacingSourcePixels, cost, null);
                retainBest(states, new PairKey(first, second), candidate);
            }
        }
        int maximumPairStates = states.size();

        for (int profileIndex = 2; profileIndex < profiles.size(); profileIndex++) {
            Map<PairKey, PairState> next = new LinkedHashMap<>();
            spacing = profileSpacing(profiles, profileIndex, sourcePixel);
            spacingSourcePixels = spacing / sourcePixel;
            for (PairState previous : states.values()) {
                for (double offset : allowed.get(profileIndex)) {
                    transitionEvaluations++;
                    Point2D.Double previousPoint = pointFor(profiles.get(profileIndex - 1),
                        previous.currentOffset());
                    Point2D.Double currentPoint = pointFor(profiles.get(profileIndex), offset);
                    double heading = heading(previousPoint, currentPoint);
                    double referenceHeading = tubeHeading(profiles, tube, profileIndex - 1, profileIndex);
                    double continuity = continuityWeight(track, profileIndex)
                        * square((offset - previous.currentOffset()) / spacing);
                    double acceleration = accelerationWeight(track, profileIndex, scaleEvidence)
                        * geometricCurvatureCost(heading, referenceHeading, previous, spacingSourcePixels);
                    double cost = previous.cost()
                        + profileCost(track, profiles, profileIndex, offset, sourcePixel, junctionContext,
                            scaleEvidence, tube, endpointApproaches)
                        + continuity + acceleration;
                    PairState candidate = new PairState(profileIndex, previous.currentOffset(), offset,
                        heading, referenceHeading, spacingSourcePixels, cost, previous);
                    retainBest(next, new PairKey(previous.currentOffset(), offset), candidate);
                }
            }
            states = next;
            maximumPairStates = Math.max(maximumPairStates, states.size());
        }

        PairState best = states.values().stream().min(pairStateComparator()).orElseThrow();
        Double[] result = new Double[profiles.size()];
        PairState cursor = best;
        while (cursor != null) {
            result[cursor.profileIndex()] = cursor.currentOffset();
            result[cursor.profileIndex() - 1] = cursor.previousOffset();
            cursor = cursor.predecessor();
        }
        return new ExactSolution(List.of(result), maximumOffsetStates, maximumPairStates, transitionEvaluations);
    }

    private double profileCost(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        int profileIndex,
        double offset,
        double sourcePixel,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence,
        LongitudinalCorridorTube tube,
        EndpointApproachModel endpointApproaches
    ) {
        return dataCost(track, profiles.get(profileIndex), profileIndex, offset, sourcePixel, scaleEvidence,
            tube.at(profileIndex), heatmapWeight(endpointApproaches, profileIndex))
            + constraintCost(profileIndex, offset, junctionContext, endpointApproaches, sourcePixel);
    }

    private void retainBest(Map<PairKey, PairState> states, PairKey key, PairState candidate) {
        PairState existing = states.get(key);
        if (existing == null || pairStateComparator().compare(candidate, existing) < 0) {
            states.put(key, candidate);
        }
    }

    private Comparator<PairState> pairStateComparator() {
        return Comparator.comparingDouble(PairState::cost)
            .thenComparingDouble(PairState::currentOffset)
            .thenComparingDouble(PairState::previousOffset);
    }

    private double tubeHeading(
        List<CorridorProfile> profiles,
        LongitudinalCorridorTube tube,
        int fromIndex,
        int toIndex
    ) {
        return heading(pointFor(profiles.get(fromIndex), tube.at(fromIndex).centerOffsetPx()),
            pointFor(profiles.get(toIndex), tube.at(toIndex).centerOffsetPx()));
    }

    private List<Double> allowedOffsets(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        int profileIndex,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence,
        CorridorTubeSlice tubeSlice,
        EndpointApproachModel endpointApproaches
    ) {
        EndpointConstraint constraint = junctionContext.at(profileIndex);
        if (constraint != null && constraint.fixed()) {
            return List.of(0.0);
        }
        CorridorBand band = bandAt(track, profileIndex);
        if (band == null) {
            double gapOffset = interpolatedGapOffset(track, profileIndex);
            return List.of(constraint == null ? gapOffset
                : Math.max(-constraint.maxDisplacementPx(), Math.min(constraint.maxDisplacementPx(), gapOffset)));
        }
        Set<Double> mandatory = new LinkedHashSet<>();
        mandatory.add(band.centerOffsetPx());
        mandatory.add(band.coreMinPx());
        mandatory.add(band.coreMaxPx());
        mandatory.add((band.coreMinPx() + band.coreMaxPx()) / 2.0);
        mandatory.add(band.shoulderMinPx());
        mandatory.add(band.shoulderMaxPx());
        mandatory.add(tubeSlice.centerOffsetPx());
        mandatory.add(tubeSlice.rawCenterPx());
        mandatory.add(tubeSlice.lightCenterPx());
        mandatory.add(tubeSlice.standardCenterPx());
        endpointApproaches.targetsAt(profileIndex).forEach(target -> mandatory.add(target.expectedOffsetPx()));
        BandScaleEvidence evidence = scaleEvidence.get(scaleEvidenceKey(profileIndex, band.id()));
        if (evidence != null && evidence.hasCoarseCenterPrior()) {
            mandatory.add(evidence.coarseCenterPx());
        }
        Set<Double> offsets = new LinkedHashSet<>(mandatory);
        for (IntensitySample sample : profiles.get(profileIndex).source().intensitySamples()) {
            if (sample.insideRaster()
                && sample.offsetPx() >= band.shoulderMinPx()
                && sample.offsetPx() <= band.shoulderMaxPx()) {
                offsets.add(sample.offsetPx());
            }
        }
        if (constraint != null) {
            mandatory.add(0.0);
            mandatory.add(-constraint.maxDisplacementPx());
            mandatory.add(constraint.maxDisplacementPx());
            offsets.addAll(mandatory);
            offsets.removeIf(offset -> Math.abs(offset) > constraint.maxDisplacementPx() + 1e-9);
            mandatory.removeIf(offset -> Math.abs(offset) > constraint.maxDisplacementPx() + 1e-9);
        }
        List<Double> sorted = offsets.stream().sorted().toList();
        if (sorted.size() <= parameters.maxOffsetStates()) {
            return sorted;
        }
        List<Double> reduced = new ArrayList<>(mandatory.stream().sorted().toList());
        if (reduced.size() > parameters.maxOffsetStates()) {
            throw new IllegalStateException("Mandatory corridor states exceed configured state bound");
        }
        int remaining = parameters.maxOffsetStates() - reduced.size();
        for (int i = 0; i < remaining; i++) {
            double position = remaining == 1 ? (sorted.size() - 1.0) / 2.0
                : i * (sorted.size() - 1.0) / (remaining - 1.0);
            reduced.add(sorted.get((int) Math.round(position)));
        }
        return reduced.stream().distinct().toList();
    }

    private double dataCost(
        CorridorTrack track,
        CorridorProfile profile,
        int profileIndex,
        double offset,
        double sourcePixel,
        Map<String, BandScaleEvidence> scaleEvidence,
        CorridorTubeSlice tubeSlice,
        double heatmapWeight
    ) {
        return dataCostComponents(track, profile, profileIndex, offset, sourcePixel, scaleEvidence,
            tubeSlice, heatmapWeight).total();
    }

    private DataCost dataCostComponents(
        CorridorTrack track,
        CorridorProfile profile,
        int profileIndex,
        double offset,
        double sourcePixel,
        Map<String, BandScaleEvidence> scaleEvidence,
        CorridorTubeSlice tubeSlice,
        double heatmapWeight
    ) {
        CorridorBand band = bandAt(track, profileIndex);
        if (band == null) {
            double tubeDistance = Math.abs(offset - tubeSlice.centerOffsetPx())
                / Math.max(sourcePixel, tubeSlice.uncertaintyPx());
            double tubeCost = parameters.tubeCenterWeight() * tubeSlice.confidence() * square(tubeDistance);
            return new DataCost(0.35 + heatmapWeight * tubeCost, 0.0, 0.0,
                heatmapWeight * tubeCost);
        }
        ProfileIntensityInterpolator.InterpolatedIntensity interpolated = ProfileIntensityInterpolator
            .interpolate(profile.source(), offset).orElse(null);
        double scaleIntensity = interpolated == null ? 0.0 : interpolated.scaleIntensity();
        double bandMaximum = profile.source().intensitySamples().stream()
            .filter(IntensitySample::insideRaster)
            .filter(value -> value.offsetPx() >= band.shoulderMinPx() - 1e-9
                && value.offsetPx() <= band.shoulderMaxPx() + 1e-9)
            .mapToDouble(this::scaleIntensity)
            .max().orElse(scaleIntensity);
        double deadband = plateauDeadband(profile, band);
        boolean equivalentPeak = bandMaximum - scaleIntensity <= deadband;
        double normalizedIntensity = band.peakIntensity() <= band.noiseFloor() + 1e-9
            ? 0.0
            : clamp((scaleIntensity - band.noiseFloor()) / (band.peakIntensity() - band.noiseFloor()));
        double intensityCost = (equivalentPeak ? 0.0 : 1.0 - normalizedIntensity)
            * (0.55 + 0.45 * band.signalExistenceConfidence());
        double coreDistance = distanceOutside(offset, band.coreMinPx(), band.coreMaxPx()) / sourcePixel;
        double shoulderDistance = distanceOutside(offset, band.shoulderMinPx(), band.shoulderMaxPx()) / sourcePixel;
        double robustCoreCenter = (band.coreMinPx() + band.coreMaxPx()) / 2.0;
        double centerTarget = equivalentPeak ? robustCoreCenter : band.centerOffsetPx();
        double centerDistance = Math.abs(offset - centerTarget) / Math.max(sourcePixel, band.uncertaintyPx());
        double centerCost = square(centerDistance) * (0.12 + 0.38 * band.localizationConfidence());
        BandScaleEvidence evidence = scaleEvidence.get(scaleEvidenceKey(profileIndex, band.id()));
        double coarsePrior = evidence == null || !evidence.hasCoarseCenterPrior()
            ? 0.0
            : parameters.coarseCenterWeight() * evidence.scalePersistence()
                * (0.35 + 0.65 * (1.0 - band.localizationConfidence()))
                * square((offset - evidence.coarseCenterPx())
                    / Math.max(sourcePixel, Math.min(evidence.coarseUncertaintyPx(), 2.0 * sourcePixel)));
        double tubeDistance = Math.abs(offset - tubeSlice.centerOffsetPx())
            / Math.max(sourcePixel * 0.5, tubeSlice.uncertaintyPx());
        double tubeCost = parameters.tubeCenterWeight() * tubeSlice.confidence()
            * (0.25 + 0.75 * (1.0 - band.localizationConfidence())) * square(tubeDistance);
        if (tubeSlice.scaleConflict() || tubeSlice.parentMerge()) {
            tubeCost *= 0.35;
        }
        double weightedCenterEvidence = heatmapWeight * (intensityCost
            + parameters.coreDistanceWeight() * square(coreDistance) + centerCost + coarsePrior + tubeCost);
        double shoulderCost = parameters.shoulderDistanceWeight() * square(shoulderDistance);
        double total = weightedCenterEvidence + shoulderCost;
        return new DataCost(total, equivalentPeak ? heatmapWeight * centerCost : 0.0,
            heatmapWeight * coarsePrior, heatmapWeight * tubeCost);
    }

    static String scaleEvidenceKey(int profileIndex, String bandId) {
        return profileIndex + "/" + bandId;
    }

    private double scaleIntensity(IntensitySample sample) {
        return 0.30 * sample.nativeIntensity() + 0.30 * sample.lightFilteredIntensity()
            + 0.40 * sample.standardFilteredIntensity();
    }

    private double plateauDeadband(CorridorProfile profile, CorridorBand band) {
        double disagreement = profile.source().intensitySamples().stream()
            .filter(IntensitySample::insideRaster)
            .filter(sample -> sample.offsetPx() >= band.coreMinPx() - 1e-9
                && sample.offsetPx() <= band.coreMaxPx() + 1e-9)
            .mapToDouble(sample -> (Math.abs(sample.nativeIntensity() - sample.lightFilteredIntensity())
                + Math.abs(sample.lightFilteredIntensity() - sample.standardFilteredIntensity())) / 2.0)
            .average().orElse(0.0);
        return Math.max(0.02, Math.min(0.10, 0.02 + 0.5 * disagreement));
    }

    private double continuityWeight(CorridorTrack track, int profileIndex) {
        CorridorBand band = bandAt(track, profileIndex);
        return band == null ? 0.16 : 0.06 + 0.18 * (1.0 - band.localizationConfidence());
    }

    private double accelerationWeight(
        CorridorTrack track,
        int profileIndex,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        CorridorBand current = bandAt(track, profileIndex);
        if (current == null) {
            return 0.42;
        }
        boolean sustainedMotion = sustainedCenterMotion(track, profileIndex, scaleEvidence);
        double base = 0.34 + 0.30 * (1.0 - current.localizationConfidence());
        return sustainedMotion ? base * 0.65 : base;
    }

    private double constraintCost(
        int profileIndex,
        double offset,
        JunctionContext context,
        EndpointApproachModel approaches,
        double sourcePixel
    ) {
        double cost = 0.0;
        for (EndpointConstraint constraint : context.constraints()) {
            if (profileIndex == constraint.profileIndex() && !constraint.fixed()) {
                cost += constraint.priorWeight() * square(offset / Math.max(sourcePixel, constraint.maxDisplacementPx()));
            }
        }
        cost += approaches.targetsAt(profileIndex).stream()
            .mapToDouble(target -> target.positionWeight()
                * square((offset - target.expectedOffsetPx()) / sourcePixel))
            .sum();
        return cost;
    }

    private double heatmapWeight(EndpointApproachModel approaches, int profileIndex) {
        double maximumGuideWeight = approaches.targetsAt(profileIndex).stream()
            .mapToDouble(EndpointApproachModel.GuideTarget::positionWeight).max().orElse(0.0);
        if (maximumGuideWeight <= 0.0) {
            return 1.0;
        }
        double normalizedProximity = Math.sqrt(Math.min(1.0, maximumGuideWeight / 2.5));
        return Math.max(0.15, 1.0 - 0.85 * normalizedProximity);
    }

    private boolean sustainedCenterMotion(
        CorridorTrack track,
        int profileIndex,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        int windowProfiles = 7;
        if (profileIndex < windowProfiles - 1) {
            return false;
        }
        int start = profileIndex - windowProfiles + 1;
        CorridorBand firstBand = bandAt(track, start);
        CorridorBand lastBand = bandAt(track, profileIndex);
        if (firstBand == null || lastBand == null) {
            return false;
        }
        double totalMotion = lastBand.centerOffsetPx() - firstBand.centerOffsetPx();
        double maximumUncertainty = 0.0;
        int coherent = 0;
        int transitions = 0;
        for (int index = start; index <= profileIndex; index++) {
            CorridorBand band = bandAt(track, index);
            if (band == null || band.scaleAgreement() < 0.55) {
                return false;
            }
            BandScaleEvidence evidence = scaleEvidence.get(scaleEvidenceKey(index, band.id()));
            if (evidence != null && evidence.scaleConflict()) {
                return false;
            }
            maximumUncertainty = Math.max(maximumUncertainty, band.uncertaintyPx());
            if (index > start) {
                CorridorBand previous = bandAt(track, index - 1);
                double motion = band.centerOffsetPx() - previous.centerOffsetPx();
                if (Math.abs(motion) <= 1e-9 || Math.signum(motion) == Math.signum(totalMotion)) {
                    coherent++;
                }
                transitions++;
            }
        }
        return transitions > 0 && coherent >= Math.ceil(transitions * 0.70)
            && Math.abs(totalMotion) > maximumUncertainty;
    }

    private double interpolatedGapOffset(CorridorTrack track, int profileIndex) {
        CorridorTrackPoint before = null;
        CorridorTrackPoint after = null;
        for (CorridorTrackPoint point : track.points().values()) {
            if (point.profileIndex() < profileIndex
                && (before == null || point.profileIndex() > before.profileIndex())) {
                before = point;
            }
            if (point.profileIndex() > profileIndex
                && (after == null || point.profileIndex() < after.profileIndex())) {
                after = point;
            }
        }
        if (before != null && after != null) {
            double fraction = (double) (profileIndex - before.profileIndex()) / (after.profileIndex() - before.profileIndex());
            return before.band().centerOffsetPx()
                + fraction * (after.band().centerOffsetPx() - before.band().centerOffsetPx());
        }
        if (before != null) {
            CorridorTrackPoint previous = track.points().get(before.profileIndex() - 1);
            double slope = previous == null ? 0.0 : before.band().centerOffsetPx() - previous.band().centerOffsetPx();
            int gap = profileIndex - before.profileIndex();
            return before.band().centerOffsetPx() + slope * gap * Math.pow(0.55, gap);
        }
        return after == null ? 0.0 : after.band().centerOffsetPx();
    }

    private CorridorBand bandAt(CorridorTrack track, int profileIndex) {
        CorridorTrackPoint point = track.points().get(profileIndex);
        return point == null ? null : point.band();
    }

    private double profileSpacing(List<CorridorProfile> profiles, int index, double sourcePixel) {
        Point2D.Double previous = profiles.get(index - 1).source().anchorScreen();
        Point2D.Double current = profiles.get(index).source().anchorScreen();
        return Math.max(sourcePixel, previous.distance(current));
    }

    private Point2D.Double pointFor(CorridorProfile profile, double offset) {
        return new Point2D.Double(
            profile.source().anchorScreen().x + profile.source().normalScreen().x * offset,
            profile.source().anchorScreen().y + profile.source().normalScreen().y * offset
        );
    }

    private double heading(Point2D.Double from, Point2D.Double to) {
        return Math.atan2(to.y - from.y, to.x - from.x);
    }

    private double geometricCurvatureCost(
        double heading,
        double referenceHeading,
        PairState previous,
        double spacingSourcePixels
    ) {
        double candidateTurn = angleDifference(heading, previous.segmentHeading());
        double referenceTurn = angleDifference(referenceHeading, previous.referenceHeading());
        double meanSpacing = Math.max(1.0, (spacingSourcePixels + previous.spacingSourcePixels()) / 2.0);
        return square(angleDifference(candidateTurn, referenceTurn) / meanSpacing);
    }

    private double geometricCurvatureCost(
        List<CorridorProfile> profiles,
        List<Double> offsets,
        LongitudinalCorridorTube tube,
        int index,
        double sourcePixel
    ) {
        Point2D.Double a = pointFor(profiles.get(index - 2), offsets.get(index - 2));
        Point2D.Double b = pointFor(profiles.get(index - 1), offsets.get(index - 1));
        Point2D.Double c = pointFor(profiles.get(index), offsets.get(index));
        double candidateTurn = angleDifference(heading(b, c), heading(a, b));
        Point2D.Double referenceA = pointFor(profiles.get(index - 2), tube.at(index - 2).centerOffsetPx());
        Point2D.Double referenceB = pointFor(profiles.get(index - 1), tube.at(index - 1).centerOffsetPx());
        Point2D.Double referenceC = pointFor(profiles.get(index), tube.at(index).centerOffsetPx());
        double referenceTurn = angleDifference(heading(referenceB, referenceC), heading(referenceA, referenceB));
        double spacing = (profileSpacing(profiles, index - 1, sourcePixel)
            + profileSpacing(profiles, index, sourcePixel)) / (2.0 * sourcePixel);
        return square(angleDifference(candidateTurn, referenceTurn) / Math.max(1.0, spacing));
    }

    private double angleDifference(double angle, double reference) {
        double difference = angle - reference;
        while (difference > Math.PI) {
            difference -= 2.0 * Math.PI;
        }
        while (difference < -Math.PI) {
            difference += 2.0 * Math.PI;
        }
        return difference;
    }

    private double distanceOutside(double value, double minimum, double maximum) {
        if (value < minimum) {
            return minimum - value;
        }
        if (value > maximum) {
            return value - maximum;
        }
        return 0.0;
    }

    private double validSourcePixel(double sourcePixelSizePx) {
        return Double.isFinite(sourcePixelSizePx) && sourcePixelSizePx > 0.0 ? sourcePixelSizePx : 1.0;
    }

    private double square(double value) {
        return value * value;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Full result of centerline optimization.
     *
     * @param offsetsPx optimized lateral offsets
     * @param screenPoints optimized sampled-raster geometry
     * @param totalCost total dynamic-programming objective
     * @param costs per-profile decomposed objective values
     * @param inCorridorFraction fraction within selected shoulder envelopes
     * @param longitudinalStability inverse normalized lateral-acceleration energy
     * @param quality unweighted physical/source-resolution quality metrics
     * @param endpointApproaches endpoint boundary evidence used by the optimizer
     * @param maximumOffsetStates maximum lateral states retained at any profile
     * @param maximumPairStates maximum exact second-order pair states retained at any profile
     * @param transitionEvaluations number of evaluated exact DP transitions
     */
    public record OptimizationResult(
        List<Double> offsetsPx,
        List<Point2D.Double> screenPoints,
        double totalCost,
        List<CostRow> costs,
        double inCorridorFraction,
        double longitudinalStability,
        CorridorQuality quality,
        EndpointApproachModel endpointApproaches,
        int maximumOffsetStates,
        int maximumPairStates,
        long transitionEvaluations
    ) {
        /** Makes optimizer output collections immutable. */
        public OptimizationResult {
            offsetsPx = List.copyOf(offsetsPx);
            screenPoints = List.copyOf(screenPoints);
            costs = List.copyOf(costs);
            quality = java.util.Objects.requireNonNull(quality);
            endpointApproaches = java.util.Objects.requireNonNull(endpointApproaches);
        }

        static OptimizationResult empty() {
            return new OptimizationResult(List.of(), List.of(), Double.POSITIVE_INFINITY, List.of(), 0.0, 0.0,
                CorridorQuality.empty(), new EndpointApproachModel(List.of()), 0, 0, 0L);
        }
    }

    /**
     * Per-profile optimizer diagnostics.
     *
     * @param profileIndex profile index
     * @param chosenOffsetPx selected lateral offset
     * @param profileSpacingPx longitudinal spacing from the previous profile, or the next spacing at index zero
     * @param dataCost heatmap and corridor-fit cost
     * @param continuityCost first-difference diagnostic
     * @param accelerationCost second-difference diagnostic
     * @param endpointCost endpoint prior cost
     * @param plateauCenterCost robust center cost applied within an intensity plateau
     * @param coarsePriorCost compatible coarse-scale localization prior
     * @param tubeCenterCost confidence-weighted longitudinal tube-center prior
     * @param weightedTotal exact sum of this row's weighted objective terms
     * @param insideCore whether the point is inside its selected high-level core
     * @param insideCorridor whether the point is inside its selected shoulder envelope
     */
    public record CostRow(
        int profileIndex,
        double chosenOffsetPx,
        double profileSpacingPx,
        double dataCost,
        double continuityCost,
        double accelerationCost,
        double plateauCenterCost,
        double coarsePriorCost,
        double tubeCenterCost,
        double endpointCost,
        double weightedTotal,
        boolean insideCore,
        boolean insideCorridor
    ) {
    }

    private record PairKey(double previousOffset, double currentOffset) {
    }

    private record PairState(
        int profileIndex,
        double previousOffset,
        double currentOffset,
        double segmentHeading,
        double referenceHeading,
        double spacingSourcePixels,
        double cost,
        PairState predecessor
    ) {
    }

    private record ExactSolution(
        List<Double> offsetsPx,
        int maximumOffsetStates,
        int maximumPairStates,
        long transitionEvaluations
    ) {
    }

    private record DataCost(
        double total,
        double plateauCenterCost,
        double coarsePriorCost,
        double tubeCenterCost
    ) {
    }
}

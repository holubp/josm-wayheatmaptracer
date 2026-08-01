package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

/**
 * Selects a stable centerline inside one longitudinal corridor using second-order dynamic programming.
 */
public final class CorridorCenterlineOptimizer {
    private static final int MAX_OFFSET_STATES = 21;

    /**
     * Creates a stateless centerline optimizer.
     */
    public CorridorCenterlineOptimizer() {
        // Stateless optimizer.
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
        if (profiles.isEmpty() || track.points().isEmpty()) {
            return OptimizationResult.empty();
        }
        double sourcePixel = validSourcePixel(sourcePixelSizePx);
        List<List<Double>> allowed = new ArrayList<>(profiles.size());
        for (int i = 0; i < profiles.size(); i++) {
            allowed.add(allowedOffsets(track, profiles, i, junctionContext));
        }

        List<State> states = new ArrayList<>();
        for (double offset : allowed.get(0)) {
            double data = dataCost(track, profiles.get(0), 0, offset, sourcePixel, scaleEvidence)
                + constraintCost(track, 0, offset, junctionContext, sourcePixel);
            states.add(new State(offset, 0.0, Double.NaN, Double.NaN, 0.0, data, List.of(offset)));
        }
        for (int profileIndex = 1; profileIndex < profiles.size(); profileIndex++) {
            List<State> next = new ArrayList<>();
            double spacing = profileSpacing(profiles, profileIndex, sourcePixel);
            double spacingSourcePixels = spacing / sourcePixel;
            for (double offset : allowed.get(profileIndex)) {
                for (State previous : states) {
                    double delta = (offset - previous.offset()) / spacing;
                    Point2D.Double currentPoint = pointFor(profiles.get(profileIndex), offset);
                    Point2D.Double previousPoint = pointFor(profiles.get(profileIndex - 1), previous.offset());
                    double heading = heading(previousPoint, currentPoint);
                    double referenceHeading = heading(
                        profiles.get(profileIndex - 1).source().anchorScreen(),
                        profiles.get(profileIndex).source().anchorScreen());
                    double continuity = continuityWeight(track, profileIndex) * square(delta);
                    double acceleration = profileIndex < 2
                        ? 0.0
                        : accelerationWeight(track, profileIndex, scaleEvidence) * geometricCurvatureCost(
                            heading, referenceHeading, previous, spacingSourcePixels);
                    double data = dataCost(track, profiles.get(profileIndex), profileIndex, offset, sourcePixel,
                        scaleEvidence)
                        + constraintCost(track, profileIndex, offset, junctionContext, sourcePixel);
                    double cost = previous.cost() + data + continuity + acceleration;
                    List<Double> offsets = new ArrayList<>(previous.offsets());
                    offsets.add(offset);
                    next.add(new State(offset, delta, heading, referenceHeading, spacingSourcePixels, cost, offsets));
                }
            }
            states = next.stream().sorted(Comparator.comparingDouble(State::cost)).limit(MAX_OFFSET_STATES * MAX_OFFSET_STATES).toList();
        }

        State best = states.stream().min(Comparator.comparingDouble(State::cost)).orElseThrow();
        List<Point2D.Double> points = new ArrayList<>(profiles.size());
        List<CostRow> costs = new ArrayList<>(profiles.size());
        int inCorridor = 0;
        double accelerationEnergy = 0.0;
        for (int i = 0; i < profiles.size(); i++) {
            double offset = best.offsets().get(i);
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
            DataCost dataComponents = dataCostComponents(track, profile, i, offset, sourcePixel, scaleEvidence);
            double data = dataComponents.total();
            double continuity = i == 0 ? 0.0 : continuityWeight(track, i)
                * square((offset - best.offsets().get(i - 1)) / profileSpacing(profiles, i, sourcePixel));
            double acceleration = i < 2 ? 0.0 : accelerationWeight(track, i, scaleEvidence)
                * geometricCurvatureCost(profiles, best.offsets(), i, sourcePixel);
            accelerationEnergy += acceleration;
            double endpoint = constraintCost(track, i, offset, junctionContext, sourcePixel);
            double spacing = i == 0 && profiles.size() > 1
                ? profileSpacing(profiles, 1, sourcePixel)
                : (i == 0 ? sourcePixel : profileSpacing(profiles, i, sourcePixel));
            costs.add(new CostRow(i, offset, spacing, data, continuity, acceleration,
                dataComponents.plateauCenterCost(), dataComponents.coarsePriorCost(), endpoint,
                data + continuity + acceleration + endpoint, insideCore, contained));
        }
        double inCorridorFraction = (double) inCorridor / profiles.size();
        double stability = 1.0 / (1.0 + accelerationEnergy / Math.max(1, profiles.size() - 2));
        return new OptimizationResult(best.offsets(), points, best.cost(), costs, inCorridorFraction, stability);
    }

    private List<Double> allowedOffsets(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        int profileIndex,
        JunctionContext junctionContext
    ) {
        EndpointConstraint constraint = junctionContext.at(profileIndex);
        if (constraint != null && constraint.fixed()) {
            return List.of(0.0);
        }
        CorridorBand band = bandAt(track, profileIndex);
        if (band == null) {
            return List.of(interpolatedGapOffset(track, profileIndex));
        }
        Set<Double> offsets = new LinkedHashSet<>();
        offsets.add(band.centerOffsetPx());
        offsets.add(band.coreMinPx());
        offsets.add(band.coreMaxPx());
        offsets.add((band.coreMinPx() + band.coreMaxPx()) / 2.0);
        offsets.add(band.shoulderMinPx());
        offsets.add(band.shoulderMaxPx());
        for (IntensitySample sample : profiles.get(profileIndex).source().intensitySamples()) {
            if (sample.insideRaster()
                && sample.offsetPx() >= band.shoulderMinPx()
                && sample.offsetPx() <= band.shoulderMaxPx()) {
                offsets.add(sample.offsetPx());
            }
        }
        if (constraint != null) {
            offsets.add(0.0);
            offsets.removeIf(offset -> Math.abs(offset) > constraint.maxDisplacementPx() + 1e-9);
        }
        List<Double> sorted = offsets.stream().sorted().toList();
        if (sorted.size() <= MAX_OFFSET_STATES) {
            return sorted;
        }
        List<Double> reduced = new ArrayList<>(MAX_OFFSET_STATES);
        for (int i = 0; i < MAX_OFFSET_STATES; i++) {
            reduced.add(sorted.get((int) Math.round(i * (sorted.size() - 1.0) / (MAX_OFFSET_STATES - 1.0))));
        }
        return reduced.stream().distinct().toList();
    }

    private double dataCost(
        CorridorTrack track,
        CorridorProfile profile,
        int profileIndex,
        double offset,
        double sourcePixel,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        return dataCostComponents(track, profile, profileIndex, offset, sourcePixel, scaleEvidence).total();
    }

    private DataCost dataCostComponents(
        CorridorTrack track,
        CorridorProfile profile,
        int profileIndex,
        double offset,
        double sourcePixel,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        CorridorBand band = bandAt(track, profileIndex);
        if (band == null) {
            return new DataCost(0.35, 0.0, 0.0);
        }
        IntensitySample sample = nearestSample(profile, offset);
        double scaleIntensity = sample == null ? 0.0
            : (0.30 * sample.nativeIntensity() + 0.30 * sample.lightFilteredIntensity()
                + 0.40 * sample.standardFilteredIntensity());
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
            : 4.0 * evidence.scalePersistence() * (0.35 + 0.65 * (1.0 - band.localizationConfidence()))
                * square((offset - evidence.coarseCenterPx())
                    / Math.max(sourcePixel, Math.min(evidence.coarseUncertaintyPx(), 2.0 * sourcePixel)));
        double total = intensityCost + 0.55 * square(coreDistance) + 4.0 * square(shoulderDistance)
            + centerCost + coarsePrior;
        return new DataCost(total, equivalentPeak ? centerCost : 0.0, coarsePrior);
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
        CorridorTrack track,
        int profileIndex,
        double offset,
        JunctionContext context,
        double sourcePixel
    ) {
        double cost = 0.0;
        for (EndpointConstraint constraint : context.constraints()) {
            int distance = Math.abs(profileIndex - constraint.profileIndex());
            if (distance == 0 && !constraint.fixed()) {
                cost += constraint.priorWeight() * square(offset / Math.max(sourcePixel, constraint.maxDisplacementPx()));
            }
            if (distance > 0 && distance <= constraint.approachWindowProfiles()) {
                int direction = profileIndex < constraint.profileIndex() ? -1 : 1;
                int edgeIndex = constraint.profileIndex() + direction * constraint.approachWindowProfiles();
                CorridorBand edgeBand = nearestBand(track, edgeIndex, direction);
                if (edgeBand == null) {
                    continue;
                }
                double fraction = (double) distance / constraint.approachWindowProfiles();
                double endpointOffset = constraint.fixed() ? 0.0 : clampDisplacement(
                    edgeBand.centerOffsetPx(), constraint.maxDisplacementPx());
                double expected = endpointOffset + fraction * (edgeBand.centerOffsetPx() - endpointOffset);
                double weight = (1.0 - fraction) * (constraint.fixed() ? 2.0 : 0.18);
                cost += weight * square((offset - expected) / sourcePixel);
            }
        }
        return cost;
    }

    private CorridorBand nearestBand(CorridorTrack track, int requestedIndex, int direction) {
        CorridorBand exact = bandAt(track, requestedIndex);
        if (exact != null) {
            return exact;
        }
        return track.points().values().stream()
            .filter(point -> direction < 0 ? point.profileIndex() <= requestedIndex : point.profileIndex() >= requestedIndex)
            .min(Comparator.comparingInt(point -> Math.abs(point.profileIndex() - requestedIndex)))
            .map(CorridorTrackPoint::band)
            .orElse(null);
    }

    private double clampDisplacement(double offset, double maximum) {
        return Math.max(-maximum, Math.min(maximum, offset));
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

    private IntensitySample nearestSample(CorridorProfile profile, double offset) {
        return profile.source().intensitySamples().stream()
            .filter(IntensitySample::insideRaster)
            .min(Comparator.comparingDouble(sample -> Math.abs(sample.offsetPx() - offset)))
            .orElse(null);
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
        State previous,
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
        int index,
        double sourcePixel
    ) {
        Point2D.Double a = pointFor(profiles.get(index - 2), offsets.get(index - 2));
        Point2D.Double b = pointFor(profiles.get(index - 1), offsets.get(index - 1));
        Point2D.Double c = pointFor(profiles.get(index), offsets.get(index));
        double candidateTurn = angleDifference(heading(b, c), heading(a, b));
        Point2D.Double sourceA = profiles.get(index - 2).source().anchorScreen();
        Point2D.Double sourceB = profiles.get(index - 1).source().anchorScreen();
        Point2D.Double sourceC = profiles.get(index).source().anchorScreen();
        double referenceTurn = angleDifference(heading(sourceB, sourceC), heading(sourceA, sourceB));
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
     */
    public record OptimizationResult(
        List<Double> offsetsPx,
        List<Point2D.Double> screenPoints,
        double totalCost,
        List<CostRow> costs,
        double inCorridorFraction,
        double longitudinalStability
    ) {
        /** Makes optimizer output collections immutable. */
        public OptimizationResult {
            offsetsPx = List.copyOf(offsetsPx);
            screenPoints = List.copyOf(screenPoints);
            costs = List.copyOf(costs);
        }

        static OptimizationResult empty() {
            return new OptimizationResult(List.of(), List.of(), Double.POSITIVE_INFINITY, List.of(), 0.0, 0.0);
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
        double endpointCost,
        double weightedTotal,
        boolean insideCore,
        boolean insideCorridor
    ) {
    }

    private record State(
        double offset,
        double delta,
        double segmentHeading,
        double referenceHeading,
        double spacingSourcePixels,
        double cost,
        List<Double> offsets
    ) {
    }

    private record DataCost(double total, double plateauCenterCost, double coarsePriorCost) {
    }
}

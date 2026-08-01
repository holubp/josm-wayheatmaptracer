package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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
        return optimize(track, profiles, sourcePixelSizePx, JunctionContext.empty());
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
            double data = dataCost(track, profiles.get(0), 0, offset, sourcePixel)
                + constraintCost(track, 0, offset, junctionContext, sourcePixel);
            states.add(new State(offset, 0.0, data, List.of(offset)));
        }
        for (int profileIndex = 1; profileIndex < profiles.size(); profileIndex++) {
            List<State> next = new ArrayList<>();
            double spacing = profileSpacing(profiles, profileIndex, sourcePixel);
            for (double offset : allowed.get(profileIndex)) {
                for (State previous : states) {
                    double delta = (offset - previous.offset()) / spacing;
                    double continuity = continuityWeight(track, profileIndex) * square(delta / sourcePixel);
                    double acceleration = profileIndex < 2
                        ? 0.0
                        : accelerationWeight(track, profileIndex) * square((delta - previous.delta()) / sourcePixel);
                    double data = dataCost(track, profiles.get(profileIndex), profileIndex, offset, sourcePixel)
                        + constraintCost(track, profileIndex, offset, junctionContext, sourcePixel);
                    double cost = previous.cost() + data + continuity + acceleration;
                    List<Double> offsets = new ArrayList<>(previous.offsets());
                    offsets.add(offset);
                    next.add(new State(offset, delta, cost, offsets));
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
            double data = dataCost(track, profile, i, offset, sourcePixel);
            double continuity = i == 0 ? 0.0 : square((offset - best.offsets().get(i - 1)) / sourcePixel);
            double acceleration = i < 2 ? 0.0
                : square((offset - 2.0 * best.offsets().get(i - 1) + best.offsets().get(i - 2)) / sourcePixel);
            accelerationEnergy += acceleration;
            double endpoint = constraintCost(track, i, offset, junctionContext, sourcePixel);
            double spacing = i == 0 && profiles.size() > 1
                ? profileSpacing(profiles, 1, sourcePixel)
                : (i == 0 ? sourcePixel : profileSpacing(profiles, i, sourcePixel));
            costs.add(new CostRow(i, offset, spacing, data, continuity, acceleration, endpoint, insideCore, contained));
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
        double sourcePixel
    ) {
        CorridorBand band = bandAt(track, profileIndex);
        if (band == null) {
            return 0.35;
        }
        IntensitySample sample = nearestSample(profile, offset);
        double scaleIntensity = sample == null ? 0.0
            : (0.30 * sample.nativeIntensity() + 0.30 * sample.lightFilteredIntensity()
                + 0.40 * sample.standardFilteredIntensity());
        double normalizedIntensity = band.peakIntensity() <= band.noiseFloor() + 1e-9
            ? 0.0
            : clamp((scaleIntensity - band.noiseFloor()) / (band.peakIntensity() - band.noiseFloor()));
        double intensityCost = (1.0 - normalizedIntensity) * (0.55 + 0.45 * band.signalExistenceConfidence());
        double coreDistance = distanceOutside(offset, band.coreMinPx(), band.coreMaxPx()) / sourcePixel;
        double shoulderDistance = distanceOutside(offset, band.shoulderMinPx(), band.shoulderMaxPx()) / sourcePixel;
        double centerDistance = Math.abs(offset - band.centerOffsetPx()) / Math.max(sourcePixel, band.uncertaintyPx());
        double centerCost = square(centerDistance) * (0.12 + 0.38 * band.localizationConfidence());
        return intensityCost + 0.55 * square(coreDistance) + 4.0 * square(shoulderDistance) + centerCost;
    }

    private double continuityWeight(CorridorTrack track, int profileIndex) {
        CorridorBand band = bandAt(track, profileIndex);
        return band == null ? 0.16 : 0.06 + 0.18 * (1.0 - band.localizationConfidence());
    }

    private double accelerationWeight(CorridorTrack track, int profileIndex) {
        CorridorBand current = bandAt(track, profileIndex);
        if (current == null) {
            return 0.42;
        }
        boolean sustainedMotion = sustainedCenterMotion(track, profileIndex);
        double base = 0.34 + 0.30 * (1.0 - current.localizationConfidence());
        return sustainedMotion ? base * 0.42 : base;
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

    private boolean sustainedCenterMotion(CorridorTrack track, int profileIndex) {
        if (profileIndex < 2) {
            return false;
        }
        CorridorBand a = bandAt(track, profileIndex - 2);
        CorridorBand b = bandAt(track, profileIndex - 1);
        CorridorBand c = bandAt(track, profileIndex);
        if (a == null || b == null || c == null) {
            return false;
        }
        double first = b.centerOffsetPx() - a.centerOffsetPx();
        double second = c.centerOffsetPx() - b.centerOffsetPx();
        return first * second > 0.0
            && Math.min(a.localizationConfidence(), Math.min(b.localizationConfidence(), c.localizationConfidence())) >= 0.35;
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
        double endpointCost,
        boolean insideCore,
        boolean insideCorridor
    ) {
    }

    private record State(double offset, double delta, double cost, List<Double> offsets) {
    }
}

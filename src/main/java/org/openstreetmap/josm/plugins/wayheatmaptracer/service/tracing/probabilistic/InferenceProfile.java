package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;

/** Evaluated immutable profile consumed by the exact pair-state inference graph. */
public final class InferenceProfile {
    private final double chainageMeters;
    private final MetricPoint anchor;
    private final MetricPoint normalUnit;
    private final List<LateralStateCell> cells;
    private final double[] unaryCosts;
    private final Map<String, ImageOrientationSupport> orientationByBranch;
    private final Map<String, Double> orientationReliabilityByBranch;
    private final ObservationOwnership ownership;
    private final boolean entirelyMissing;
    private final double[] structuralGuideCosts;
    private final double[][] componentResponsibilities;

    /**
     * Creates a profile without component responsibilities, primarily for deterministic graph clients.
     *
     * @param chainageMeters factual cumulative ground distance
     * @param anchor source-axis anchor in the metric frame
     * @param normalUnit source-axis unit normal
     * @param cells ordered admitted lateral cells
     * @param unaryCosts normalized mixture costs for the cells
     * @param supportedDirectionsRadians alternative image-supported orientations
     * @param orientationCertainty confidence in the direction set
     * @param ownership strongest factual evidence ownership
     * @param entirelyMissing whether no valid localized image observation exists
     */
    public InferenceProfile(double chainageMeters, MetricPoint anchor, MetricPoint normalUnit,
        List<LateralStateCell> cells, double[] unaryCosts, List<Double> supportedDirectionsRadians,
        double orientationCertainty, ObservationOwnership ownership, boolean entirelyMissing) {
        this(chainageMeters, anchor, normalUnit, cells, unaryCosts,
            uniformOrientation(cells,
                ImageOrientationSupport.legacy(supportedDirectionsRadians, orientationCertainty)),
            uniformReliability(cells, 1.0),
            ownership, entirelyMissing, new double[cells == null ? 0 : cells.size()],
            new double[cells == null ? 0 : cells.size()][0]);
    }

    /** Creates a profile retaining measured image-orientation intervals. */
    public InferenceProfile(double chainageMeters, MetricPoint anchor, MetricPoint normalUnit,
        List<LateralStateCell> cells, double[] unaryCosts, ImageOrientationSupport orientationSupport,
        ObservationOwnership ownership, boolean entirelyMissing) {
        this(chainageMeters, anchor, normalUnit, cells, unaryCosts,
            uniformOrientation(cells, orientationSupport),
            uniformReliability(cells, 1.0),
            ownership, entirelyMissing, new double[cells == null ? 0 : cells.size()],
            new double[cells == null ? 0 : cells.size()][0]);
    }

    /** Retains the point-direction constructor for deterministic graph fixtures. */
    public InferenceProfile(double chainageMeters, MetricPoint anchor, MetricPoint normalUnit,
        List<LateralStateCell> cells, double[] unaryCosts, List<Double> supportedDirectionsRadians,
        double orientationCertainty, ObservationOwnership ownership, boolean entirelyMissing,
        double[][] componentResponsibilities) {
        this(chainageMeters, anchor, normalUnit, cells, unaryCosts,
            uniformOrientation(cells,
                ImageOrientationSupport.legacy(supportedDirectionsRadians, orientationCertainty)),
            uniformReliability(cells, 1.0),
            ownership, entirelyMissing, new double[cells == null ? 0 : cells.size()],
            componentResponsibilities);
    }

    /** Creates a fully evaluated profile with branch-owned image orientation evidence. */
    public InferenceProfile(double chainageMeters, MetricPoint anchor, MetricPoint normalUnit,
        List<LateralStateCell> cells, double[] unaryCosts,
        Map<String, ImageOrientationSupport> orientationByBranch,
        ObservationOwnership ownership, boolean entirelyMissing,
        double[][] componentResponsibilities) {
        this(chainageMeters, anchor, normalUnit, cells, unaryCosts, orientationByBranch,
            uniformReliability(cells, 1.0),
            ownership, entirelyMissing, new double[cells == null ? 0 : cells.size()],
            componentResponsibilities);
    }

    /** Creates a fully evaluated profile with independently weighted branch orientation. */
    public InferenceProfile(double chainageMeters, MetricPoint anchor, MetricPoint normalUnit,
        List<LateralStateCell> cells, double[] unaryCosts,
        Map<String, ImageOrientationSupport> orientationByBranch,
        Map<String, Double> orientationReliabilityByBranch,
        ObservationOwnership ownership, boolean entirelyMissing,
        double[][] componentResponsibilities) {
        this(chainageMeters, anchor, normalUnit, cells, unaryCosts, orientationByBranch,
            orientationReliabilityByBranch, ownership, entirelyMissing,
            new double[cells == null ? 0 : cells.size()], componentResponsibilities);
    }

    private InferenceProfile(double chainageMeters, MetricPoint anchor, MetricPoint normalUnit,
        List<LateralStateCell> cells, double[] unaryCosts,
        Map<String, ImageOrientationSupport> orientationByBranch,
        Map<String, Double> orientationReliabilityByBranch,
        ObservationOwnership ownership, boolean entirelyMissing, double[] structuralGuideCosts,
        double[][] componentResponsibilities) {
        if (!Double.isFinite(chainageMeters) || chainageMeters < 0.0 || anchor == null || normalUnit == null
            || cells == null || cells.isEmpty() || unaryCosts == null || unaryCosts.length != cells.size()
            || structuralGuideCosts == null || structuralGuideCosts.length != cells.size()
            || orientationByBranch == null || orientationByBranch.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null)
            || orientationReliabilityByBranch == null
            || orientationReliabilityByBranch.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null
                    || !unit(entry.getValue()))
            || cells != null && orientationByBranch != null && cells.stream().anyMatch(cell ->
                !cell.branchLabel().equals("unlocalized")
                    && !orientationByBranch.containsKey(cell.branchLabel()))
            || cells != null && orientationReliabilityByBranch != null && cells.stream().anyMatch(cell ->
                !cell.branchLabel().equals("unlocalized")
                    && !orientationReliabilityByBranch.containsKey(cell.branchLabel()))
            || ownership == null
            || componentResponsibilities == null || componentResponsibilities.length != cells.size()) {
            throw new IllegalArgumentException("Inference profile is incomplete");
        }
        double norm = StrictMath.hypot(normalUnit.xMeters(), normalUnit.yMeters());
        if (Math.abs(norm - 1.0) > 1e-9 || Arrays.stream(unaryCosts).anyMatch(value -> !Double.isFinite(value))
            || Arrays.stream(structuralGuideCosts).anyMatch(value -> !Double.isFinite(value) || value < 0.0)) {
            throw new IllegalArgumentException("Inference profile geometry or unary costs are invalid");
        }
        this.chainageMeters = chainageMeters;
        this.anchor = anchor;
        this.normalUnit = normalUnit;
        this.cells = List.copyOf(cells);
        this.unaryCosts = unaryCosts.clone();
        this.orientationByBranch = java.util.Collections.unmodifiableMap(
            new LinkedHashMap<>(orientationByBranch));
        this.orientationReliabilityByBranch = java.util.Collections.unmodifiableMap(
            new LinkedHashMap<>(orientationReliabilityByBranch));
        this.ownership = ownership;
        this.entirelyMissing = entirelyMissing;
        this.structuralGuideCosts = structuralGuideCosts.clone();
        this.componentResponsibilities = deepCopy(componentResponsibilities);
    }

    /** Returns factual chainage in ground metres. */
    public double chainageMeters() {
        return chainageMeters;
    }

    /** Returns the source-axis anchor. */
    public MetricPoint anchor() {
        return anchor;
    }

    /** Returns the source-axis unit normal. */
    public MetricPoint normalUnit() {
        return normalUnit;
    }

    /** Returns immutable admitted cells. */
    public List<LateralStateCell> cells() {
        return cells;
    }

    /** Returns a defensive copy of unary costs. */
    public double[] unaryCosts() {
        return unaryCosts.clone();
    }

    /** Returns one unary cost without copying the vector. */
    public double unaryCost(int stateIndex) {
        return unaryCosts[stateIndex];
    }

    /** Returns alternative supported orientations in radians. */
    public List<Double> supportedDirectionsRadians() {
        return orientationByBranch.values().stream()
            .flatMap(support -> support.supportedDirectionsRadians().stream()).distinct().sorted().toList();
    }

    /** Returns orientation evidence certainty. */
    public double orientationCertainty() {
        return orientationByBranch.values().stream()
            .mapToDouble(ImageOrientationSupport::certainty).max().orElse(0.0);
    }

    /** Returns the first branch support for compatibility-only callers. */
    public ImageOrientationSupport orientationSupport() {
        return orientationByBranch.values().stream().findFirst().orElseGet(() ->
            ImageOrientationSupport.unknown(
                ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT));
    }

    /** Returns orientation evidence owned by the selected lateral state's explicit branch. */
    public ImageOrientationSupport orientationSupport(int stateIndex) {
        return orientationByBranch.getOrDefault(cells.get(stateIndex).branchLabel(),
            ImageOrientationSupport.unknown(
                ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT));
    }

    /** Returns the branch-local multiplier for image-orientation pair energy. */
    public double orientationReliability(int stateIndex) {
        return orientationReliabilityByBranch.getOrDefault(cells.get(stateIndex).branchLabel(), 0.0);
    }

    /** Returns whether any admitted branch exhausted configured orientation work. */
    public boolean orientationResourceLimited() {
        return orientationByBranch.values().stream().anyMatch(support -> support.status()
            == ImageOrientationSupport.Status.RESOURCE_LIMIT);
    }

    /** Returns factual support ownership. */
    public ObservationOwnership ownership() {
        return ownership;
    }

    /** Returns whether this profile contains no localized observation. */
    public boolean entirelyMissing() {
        return entirelyMissing;
    }

    /** Returns the bounded same-image structural prior cost for one state. */
    public double structuralGuideCost(int stateIndex) {
        return structuralGuideCosts[stateIndex];
    }

    /** Returns an otherwise identical profile with explicit structural-prior costs. */
    public InferenceProfile withStructuralGuideCosts(double[] costs) {
        if (costs == null || costs.length != cells.size()) {
            throw new IllegalArgumentException("Structural guide costs must match the state lattice");
        }
        return new InferenceProfile(chainageMeters, anchor, normalUnit, cells, unaryCosts,
            orientationByBranch, orientationReliabilityByBranch, ownership, entirelyMissing, costs,
            componentResponsibilities);
    }

    /** Returns a defensive copy of component responsibilities. */
    public double[][] componentResponsibilities() {
        return deepCopy(componentResponsibilities);
    }

    /** Converts one lateral cell to a metric route point. */
    public MetricPoint point(int stateIndex) {
        double offset = cells.get(stateIndex).offsetMeters();
        return new MetricPoint(anchor.xMeters() + normalUnit.xMeters() * offset,
            anchor.yMeters() + normalUnit.yMeters() * offset);
    }

    private static double[][] deepCopy(double[][] values) {
        double[][] copy = new double[values.length][];
        for (int index = 0; index < values.length; index++) {
            copy[index] = values[index].clone();
        }
        return copy;
    }

    private static Map<String, ImageOrientationSupport> uniformOrientation(
        List<LateralStateCell> cells, ImageOrientationSupport support) {
        if (cells == null || support == null) {
            return Map.of();
        }
        Map<String, ImageOrientationSupport> result = new LinkedHashMap<>();
        cells.forEach(cell -> result.put(cell.branchLabel(), support));
        return result;
    }

    private static Map<String, Double> uniformReliability(
        List<LateralStateCell> cells, double reliability) {
        if (cells == null) {
            return Map.of();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        cells.forEach(cell -> result.put(cell.branchLabel(), reliability));
        return result;
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.Arrays;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;

/** Evaluated immutable profile consumed by the exact pair-state inference graph. */
public final class InferenceProfile {
    private final double chainageMeters;
    private final MetricPoint anchor;
    private final MetricPoint normalUnit;
    private final List<LateralStateCell> cells;
    private final double[] unaryCosts;
    private final List<Double> supportedDirectionsRadians;
    private final double orientationCertainty;
    private final ObservationOwnership ownership;
    private final boolean entirelyMissing;
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
        this(chainageMeters, anchor, normalUnit, cells, unaryCosts, supportedDirectionsRadians,
            orientationCertainty, ownership, entirelyMissing, new double[cells == null ? 0 : cells.size()][0]);
    }

    /** Creates a fully evaluated profile including per-state component responsibilities. */
    public InferenceProfile(double chainageMeters, MetricPoint anchor, MetricPoint normalUnit,
        List<LateralStateCell> cells, double[] unaryCosts, List<Double> supportedDirectionsRadians,
        double orientationCertainty, ObservationOwnership ownership, boolean entirelyMissing,
        double[][] componentResponsibilities) {
        if (!Double.isFinite(chainageMeters) || chainageMeters < 0.0 || anchor == null || normalUnit == null
            || cells == null || cells.isEmpty() || unaryCosts == null || unaryCosts.length != cells.size()
            || supportedDirectionsRadians == null || !Double.isFinite(orientationCertainty)
            || orientationCertainty < 0.0 || orientationCertainty > 1.0 || ownership == null
            || componentResponsibilities == null || componentResponsibilities.length != cells.size()) {
            throw new IllegalArgumentException("Inference profile is incomplete");
        }
        double norm = Math.hypot(normalUnit.xMeters(), normalUnit.yMeters());
        if (Math.abs(norm - 1.0) > 1e-9 || Arrays.stream(unaryCosts).anyMatch(value -> !Double.isFinite(value))) {
            throw new IllegalArgumentException("Inference profile geometry or unary costs are invalid");
        }
        this.chainageMeters = chainageMeters;
        this.anchor = anchor;
        this.normalUnit = normalUnit;
        this.cells = List.copyOf(cells);
        this.unaryCosts = unaryCosts.clone();
        this.supportedDirectionsRadians = List.copyOf(supportedDirectionsRadians);
        this.orientationCertainty = orientationCertainty;
        this.ownership = ownership;
        this.entirelyMissing = entirelyMissing;
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
        return supportedDirectionsRadians;
    }

    /** Returns orientation evidence certainty. */
    public double orientationCertainty() {
        return orientationCertainty;
    }

    /** Returns factual support ownership. */
    public ObservationOwnership ownership() {
        return ownership;
    }

    /** Returns whether this profile contains no localized observation. */
    public boolean entirelyMissing() {
        return entirelyMissing;
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
}

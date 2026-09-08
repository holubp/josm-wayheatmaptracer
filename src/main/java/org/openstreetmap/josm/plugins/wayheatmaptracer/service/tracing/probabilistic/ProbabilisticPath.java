package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.Arrays;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

/** One exact lattice route, never a marginal mean between modes. */
public final class ProbabilisticPath {
    private final int[] stateIndices;
    private final List<MetricPoint> points;
    private final String branchSignature;
    private final double energy;
    private final double logBaseMeasure;
    private final double conditionalPosteriorMass;

    /** Creates an immutable path from one admitted state at every profile. */
    public ProbabilisticPath(int[] stateIndices, List<MetricPoint> points, String branchSignature,
        double energy, double logBaseMeasure, double conditionalPosteriorMass) {
        if (stateIndices == null || points == null || stateIndices.length != points.size()
            || stateIndices.length == 0 || branchSignature == null || branchSignature.isBlank()
            || !Double.isFinite(energy) || !Double.isFinite(logBaseMeasure)
            || !Double.isFinite(conditionalPosteriorMass) || conditionalPosteriorMass < 0.0
            || conditionalPosteriorMass > 1.0) {
            throw new IllegalArgumentException("Probabilistic path is incomplete");
        }
        this.stateIndices = stateIndices.clone();
        this.points = List.copyOf(points);
        this.branchSignature = branchSignature;
        this.energy = energy;
        this.logBaseMeasure = logBaseMeasure;
        this.conditionalPosteriorMass = conditionalPosteriorMass;
    }

    /** Returns a defensive copy of lattice state indices. */
    public int[] stateIndices() {
        return stateIndices.clone();
    }

    /** Returns immutable physical route points. */
    public List<MetricPoint> points() {
        return points;
    }

    /** Returns the ordered modal branch signature. */
    public String branchSignature() {
        return branchSignature;
    }

    /** Returns objective energy without base-measure terms. */
    public double energy() {
        return energy;
    }

    /** Returns the separate logged physical quadrature measure. */
    public double logBaseMeasure() {
        return logBaseMeasure;
    }

    /** Returns path mass conditional on the complete retained graph. */
    public double conditionalPosteriorMass() {
        return conditionalPosteriorMass;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProbabilisticPath path && Arrays.equals(stateIndices, path.stateIndices)
            && points.equals(path.points) && branchSignature.equals(path.branchSignature)
            && Double.doubleToLongBits(energy) == Double.doubleToLongBits(path.energy)
            && Double.doubleToLongBits(logBaseMeasure) == Double.doubleToLongBits(path.logBaseMeasure)
            && Double.doubleToLongBits(conditionalPosteriorMass)
                == Double.doubleToLongBits(path.conditionalPosteriorMass);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(stateIndices) + points.hashCode();
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;

/** Disjoint highest-mass lateral intervals for one profile. */
public record CredibleLateralSet(List<Interval> intervals, double retainedMass) {
    /** One closed lateral interval in metres. */
    public record Interval(double minimumOffsetMeters, double maximumOffsetMeters) {
        /** Validates finite ordered bounds. */
        public Interval {
            if (!Double.isFinite(minimumOffsetMeters) || !Double.isFinite(maximumOffsetMeters)
                || minimumOffsetMeters > maximumOffsetMeters) {
                throw new IllegalArgumentException("Credible interval bounds are invalid");
            }
        }
    }

    /** Copies intervals and validates retained probability mass. */
    public CredibleLateralSet {
        intervals = List.copyOf(intervals);
        if (!Double.isFinite(retainedMass) || retainedMass < 0.0 || retainedMass > 1.0) {
            throw new IllegalArgumentException("Credible-set mass is invalid");
        }
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.Optional;

/** Typed state-construction result retaining mandatory positions on bounded failure. */
public record StateSpaceBuildResult(Status status, Optional<ProbabilisticStateLattice> lattice,
    List<Double> retainedMandatoryOffsets, String explanation) {
    /** State construction completion status. */
    public enum Status { COMPLETE, STATE_LIMIT }

    /** Copies diagnostics and validates status/payload consistency. */
    public StateSpaceBuildResult {
        retainedMandatoryOffsets = List.copyOf(retainedMandatoryOffsets);
        if (status == null || lattice == null || explanation == null
            || status == Status.COMPLETE && lattice.isEmpty()
            || status == Status.STATE_LIMIT && lattice.isPresent()) {
            throw new IllegalArgumentException("State-space result is inconsistent");
        }
    }
}

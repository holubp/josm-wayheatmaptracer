package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;

/** One trace result paired with its actual attempt-budget usage. */
public record TraceEngineRun(TraceHypothesisSet result, TraceWorkUsage usage) {
    /** Rejects missing or contradictory result accounting. */
    public TraceEngineRun {
        if (result == null || usage == null
                || usage.distinctAlternatives() != result.hypotheses().size()) {
            throw new IllegalArgumentException("Trace result usage is incomplete");
        }
    }
}

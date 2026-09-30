package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;

/** One trace result paired with its actual attempt-budget usage and retained-memory peak. */
public record TraceEngineRun(TraceHypothesisSet result, TraceWorkUsage usage,
        long peakAdditionalRetainedBytes) {
    /** Compatibility constructor for engines not yet connected to the attempt-memory seam. */
    public TraceEngineRun(TraceHypothesisSet result, TraceWorkUsage usage) {
        this(result, usage, 0L);
    }

    /** Rejects missing or contradictory result accounting. */
    public TraceEngineRun {
        if (result == null || usage == null
                || usage.distinctAlternatives() != result.hypotheses().size()
                || peakAdditionalRetainedBytes < 0L) {
            throw new IllegalArgumentException("Trace result usage is incomplete");
        }
    }

    /** Returns an otherwise identical run with a factual attempt-memory high-water mark. */
    public TraceEngineRun withPeakAdditionalRetainedBytes(long peakBytes) {
        return new TraceEngineRun(result, usage, peakBytes);
    }
}

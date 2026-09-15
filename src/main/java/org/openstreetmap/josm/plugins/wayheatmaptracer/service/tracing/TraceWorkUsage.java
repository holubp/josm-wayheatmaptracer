package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

/** Actual work charged to one attempt-wide tracing budget. */
public record TraceWorkUsage(long pairVisits, long transitions,
        int rawAlternatives, int distinctAlternatives) {
    /** Validates nonnegative work and ordered alternative counts. */
    public TraceWorkUsage {
        if (pairVisits < 0 || transitions < 0 || rawAlternatives < 0
                || distinctAlternatives < 0 || distinctAlternatives > rawAlternatives) {
            throw new IllegalArgumentException("Trace work usage is invalid");
        }
    }

    /** Returns zero completed work. */
    public static TraceWorkUsage none() {
        return new TraceWorkUsage(0, 0, 0, 0);
    }
}

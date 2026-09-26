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

    /** Adds separately charged engine stages, saturating before primitive overflow. */
    public TraceWorkUsage plus(TraceWorkUsage other) {
        if (other == null) {
            throw new IllegalArgumentException("Trace work to add is required");
        }
        return new TraceWorkUsage(add(pairVisits, other.pairVisits),
                add(transitions, other.transitions), add(rawAlternatives, other.rawAlternatives),
                add(distinctAlternatives, other.distinctAlternatives));
    }

    private static long add(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static int add(int left, int right) {
        return Integer.MAX_VALUE - left < right ? Integer.MAX_VALUE : left + right;
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Physical evidence support lengths on the final inferred curve. */
public record GapSummary(double measuredMeters, double ambiguousMeters, double censoredMeters,
    double absentMeters, double longestInternalGapMeters, double terminalGapMeters) {
    /** Validates finite nonnegative physical lengths. */
    public GapSummary {
        if (!valid(measuredMeters) || !valid(ambiguousMeters) || !valid(censoredMeters)
            || !valid(absentMeters) || !valid(longestInternalGapMeters) || !valid(terminalGapMeters)) {
            throw new IllegalArgumentException("Gap summary lengths are invalid");
        }
    }

    private static boolean valid(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }
}

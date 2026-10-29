package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Hard per-attempt limits shared by modern inference engines. */
public record TraceBudgets(int maximumStatesPerProfile, long maximumPairVisits,
    long maximumTransitions, int maximumRawAlternatives, int maximumDistinctAlternatives) {
    /** Validates positive ordered limits. */
    public TraceBudgets {
        if (maximumStatesPerProfile <= 0 || maximumPairVisits <= 0 || maximumTransitions <= 0
            || maximumRawAlternatives <= 0 || maximumDistinctAlternatives <= 0
            || maximumDistinctAlternatives > maximumRawAlternatives) {
            throw new IllegalArgumentException("Trace budgets must be positive and ordered");
        }
    }

    /** Returns the named opt-in experimental B preview limit, never an ordinary default. */
    public static TraceBudgets experimentalProbabilisticPreview8x4() {
        return new TraceBudgets(96, 8_000_000L, 48_000_000L, 8, 4);
    }

    /** Retains the old source API for explicit frozen/experimental callers. */
    public static TraceBudgets interactiveProbabilisticPreview() {
        return experimentalProbabilisticPreview8x4();
    }

    /** Returns the approved ordinary B 96/8M/128M/32/8 limits. */
    public static TraceBudgets defaults() {
        return new TraceBudgets(96, 8_000_000L, 128_000_000L, 32, 8);
    }
}

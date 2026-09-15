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

    /**
     * Returns explicitly funded attempt-wide limits for full Hybrid A, unguided B, and guided B.
     * Callers must select these limits deliberately; frozen and user-supplied budgets are never upgraded.
     */
    public static TraceBudgets fundedHybrid() {
        return new TraceBudgets(96, 24_000_000L, 384_000_000L, 96, 24);
    }

    /** Returns the initial bounded v0.22.0 limits. */
    public static TraceBudgets defaults() {
        return new TraceBudgets(96, 8_000_000L, 128_000_000L, 32, 8);
    }
}

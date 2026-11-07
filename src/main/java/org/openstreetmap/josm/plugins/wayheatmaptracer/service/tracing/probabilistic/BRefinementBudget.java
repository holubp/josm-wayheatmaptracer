package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Sound one-sided proof for unchanged cached-V4 inference on a nested retained graph. */
public final class BRefinementBudget {
    public enum Decision { UNKNOWN, PROVEN_UNAFFORDABLE }
    private BRefinementBudget() { }
    static Decision assess(CompletedBPassWork first, long remainingScores) {
        if (first == null || remainingScores < 0)
            throw new IllegalArgumentException("Completed work and nonnegative remaining budget required");
        return Math.addExact(first.forwardBackwardScores(), first.kBestHeadScores()) > remainingScores
            ? Decision.PROVEN_UNAFFORDABLE : Decision.UNKNOWN;
    }
}

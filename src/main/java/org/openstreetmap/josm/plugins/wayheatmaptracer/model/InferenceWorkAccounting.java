package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Immutable scored-work witness for one executed probabilistic solver policy. */
public record InferenceWorkAccounting(String executionPolicy, long forwardBackwardScores,
        long kBestScores, long logicalKBestExtensions, long refusedScoreAttempts) {
    public InferenceWorkAccounting {
        if (executionPolicy == null || executionPolicy.isBlank() || forwardBackwardScores < 0L
                || kBestScores < 0L || logicalKBestExtensions < kBestScores
                || refusedScoreAttempts < 0L || refusedScoreAttempts > 1L) {
            throw new IllegalArgumentException("Inference work accounting is inconsistent");
        }
        Math.addExact(Math.addExact(forwardBackwardScores, kBestScores), refusedScoreAttempts);
    }

    /** Computed transition scores; uncomputed refusal is excluded. */
    public long computedScores() { return Math.addExact(forwardBackwardScores, kBestScores); }

    /** Score attempts, including the one refused before computation when work is exhausted. */
    public long attemptedScores() { return Math.addExact(computedScores(), refusedScoreAttempts); }
}

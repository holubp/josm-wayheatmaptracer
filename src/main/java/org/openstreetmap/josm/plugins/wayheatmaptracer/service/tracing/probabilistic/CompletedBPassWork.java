package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Actual scalar work from one usable completed cached-V4 invocation, never a diagnostic estimate. */
record CompletedBPassWork(long forwardBackwardScores, long kBestHeadScores,
        long scoredTransitions, long pairVisits) {
    CompletedBPassWork {
        if (forwardBackwardScores < 0 || kBestHeadScores < 0 || scoredTransitions < 0 || pairVisits < 0)
            throw new IllegalArgumentException("Completed pass counts must be nonnegative");
        try {
            if (Math.addExact(forwardBackwardScores, kBestHeadScores) > scoredTransitions)
                throw new IllegalArgumentException("Completed pass lower bound exceeds actual work");
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Completed pass counts overflow", overflow);
        }
    }
}

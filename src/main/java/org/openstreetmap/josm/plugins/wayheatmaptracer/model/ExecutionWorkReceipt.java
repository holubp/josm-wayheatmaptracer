package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

/** Actual B-stage execution accounting, with independent-engine work kept separate. */
public record ExecutionWorkReceipt(List<InferenceWorkAccounting> bStages, long otherEngineTransitions) {
    public ExecutionWorkReceipt {
        if (bStages == null || bStages.isEmpty() || bStages.size() > 2 || otherEngineTransitions < 0) {
            throw new IllegalArgumentException("Execution work receipt is incomplete");
        }
        bStages = List.copyOf(bStages);
        long total = otherEngineTransitions;
        for (InferenceWorkAccounting stage : bStages) {
            total = Math.addExact(total, Math.addExact(stage.forwardBackwardScores(), stage.kBestScores()));
        }
    }

    /** Number of scored transitions, excluding refused attempts and skipped logical extensions. */
    public long computedTransitions() {
        long total = otherEngineTransitions;
        for (InferenceWorkAccounting stage : bStages) {
            total = Math.addExact(total, Math.addExact(stage.forwardBackwardScores(), stage.kBestScores()));
        }
        return total;
    }

    /** True only when every executed B stage attests the same requested policy. */
    public boolean hasPolicy(String policy) {
        return policy != null && bStages.stream().allMatch(stage -> policy.equals(stage.executionPolicy()));
    }
}

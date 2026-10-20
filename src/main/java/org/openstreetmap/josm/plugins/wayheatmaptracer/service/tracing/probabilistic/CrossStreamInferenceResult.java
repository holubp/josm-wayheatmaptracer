package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CrossStreamWorkAccounting;

/** Separately owned opt-in metrics; the original inference result/layout is unchanged. */
public record CrossStreamInferenceResult(ProbabilisticInferenceResult result,
        CrossStreamWorkAccounting boundWork) {
    public CrossStreamInferenceResult {
        if (result == null || boundWork == null) {
            throw new IllegalArgumentException("Cross-stream result and work are required");
        }
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;

class TileHeatmapSamplerTest {
    @Test
    void stableFixedScaleCapsPrimaryInferenceAndKeepsHighZoomForValidation() {
        ManagedHeatmapConfig config = config(InferenceMode.STABLE_FIXED_SCALE, 15, 13);

        assertEquals(14, TileHeatmapSampler.effectiveInferenceZoom(config));
        assertEquals(15, TileHeatmapSampler.effectiveValidationZoom(config));
    }

    @Test
    void rawHighResolutionPreservesConfiguredInferenceZoom() {
        ManagedHeatmapConfig config = config(InferenceMode.RAW_HIGH_RESOLUTION, 15, 13);

        assertEquals(15, TileHeatmapSampler.effectiveInferenceZoom(config));
        assertEquals(13, TileHeatmapSampler.effectiveValidationZoom(config));
    }

    private ManagedHeatmapConfig config(InferenceMode inferenceMode, int inferenceZoom, int validationZoom) {
        return new ManagedHeatmapConfig(
            "k", "p", "s", "t",
            "all",
            "hot",
            "",
            ".*",
            AlignmentMode.MOVE_EXISTING_NODES,
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            18,
            4,
            3.0,
            inferenceMode,
            inferenceZoom,
            validationZoom,
            28.0,
            6.0,
            0L
        );
    }
}

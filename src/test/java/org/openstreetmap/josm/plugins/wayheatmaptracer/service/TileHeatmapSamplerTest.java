package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;

class TileHeatmapSamplerTest {
    @Test
    void stableFixedScalePreservesConfiguredInferenceAndLowerValidationZoom() {
        ManagedHeatmapConfig config = config(InferenceMode.STABLE_FIXED_SCALE, 15, 13);

        assertEquals(15, TileHeatmapSampler.effectiveInferenceZoom(config));
        assertEquals(13, TileHeatmapSampler.effectiveValidationZoom(config));
    }

    @Test
    void rawHighResolutionPreservesConfiguredInferenceZoom() {
        ManagedHeatmapConfig config = config(InferenceMode.RAW_HIGH_RESOLUTION, 15, 13);

        assertEquals(15, TileHeatmapSampler.effectiveInferenceZoom(config));
        assertEquals(13, TileHeatmapSampler.effectiveValidationZoom(config));
    }

    @Test
    void stableFixedScaleDoesNotDilateDefaultZ15InferenceRaster() {
        assertEquals(0, TileHeatmapSampler.stableInferenceDilationRadius(15));
        assertEquals(1, TileHeatmapSampler.stableInferenceDilationRadius(14));
        assertEquals(2, TileHeatmapSampler.stableInferenceDilationRadius(13));
    }

    @Test
    void sketchLikeSelectionDoesNotAutomaticallyWidenManagedSearch() {
        ManagedHeatmapConfig config = config(InferenceMode.STABLE_FIXED_SCALE, 15, 13);

        assertEquals(28.0, TileHeatmapSampler.effectiveSearchHalfWidthMeters(config, false), 1e-9);
        assertEquals(28.0, TileHeatmapSampler.effectiveSearchHalfWidthMeters(config, true), 1e-9);
    }

    @Test
    void managedTileCacheKeySeparatesColorsAndCacheGenerationsWithoutSecrets() {
        ManagedHeatmapConfig config = config(InferenceMode.STABLE_FIXED_SCALE, 15, 13, 1234L);

        File hot = TileHeatmapSampler.managedTileCacheFile(config, "all", "hot", 15, 123, 456);
        File gray = TileHeatmapSampler.managedTileCacheFile(config, "all", "gray", 15, 123, 456);
        String hotPath = hot.getPath();

        assertTrue(hotPath.contains("cache-1234"));
        assertTrue(hotPath.contains(File.separator + "hot" + File.separator));
        assertTrue(gray.getPath().contains(File.separator + "gray" + File.separator));
        assertFalse(hot.equals(gray));
        assertFalse(hotPath.contains("CloudFront"));
        assertFalse(hotPath.contains("secret"));
        assertFalse(hotPath.contains("policy"));
    }

    @Test
    void aggregatedProfilesRequireEveryBaseColorInSameSamplingFrame() {
        TileHeatmapSampler.SamplingParameters parameters = new TileHeatmapSampler.SamplingParameters(
            15, 50.0, 0.5, 28.0, 6.0, 56, 12);
        TileHeatmapSampler.TileMosaic hotOnly = new TileHeatmapSampler.TileMosaic(
            "hot",
            15,
            0.0,
            0.0,
            new BufferedImage(TileHeatmapSampler.TILE_SIZE, TileHeatmapSampler.TILE_SIZE, BufferedImage.TYPE_INT_ARGB),
            List.of(),
            Map.of(),
            parameters,
            false,
            1.0
        );
        TileHeatmapSampler.TileMosaicSet mosaics = new TileHeatmapSampler.TileMosaicSet(
            InferenceMode.STABLE_FIXED_SCALE,
            15,
            13,
            15,
            13,
            TileHeatmapSampler.TILE_SIZE,
            Map.of("hot@15", hotOnly),
            parameters,
            parameters
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new TileHeatmapSampler().sampleAggregatedProfiles(mosaics, 15, List.of()));
        assertTrue(ex.getMessage().contains("missing [blue, bluered, purple, gray]"));
    }

    private ManagedHeatmapConfig config(InferenceMode inferenceMode, int inferenceZoom, int validationZoom) {
        return config(inferenceMode, inferenceZoom, validationZoom, 0L);
    }

    private ManagedHeatmapConfig config(InferenceMode inferenceMode, int inferenceZoom, int validationZoom, long cacheBuster) {
        return new ManagedHeatmapConfig(
            "CloudFront-Key-Pair-Id", "policy", "secret", "token",
            "all",
            "hot",
            "",
            ".*",
            AlignmentMode.MOVE_EXISTING_NODES,
            false,
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
            IntensitySamplingMode.COLOR_MAPPING,
            cacheBuster
        );
    }
}

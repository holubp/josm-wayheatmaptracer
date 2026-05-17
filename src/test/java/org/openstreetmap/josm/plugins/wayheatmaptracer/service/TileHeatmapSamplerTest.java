package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;

class TileHeatmapSamplerTest {
    @BeforeAll
    static void setProjection() {
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

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

    @Test
    void aggregateVisualizationUsesAllBaseColorsAndHighlightsFusedSignal() {
        TileHeatmapSampler.SamplingParameters parameters = new TileHeatmapSampler.SamplingParameters(
            15, 50.0, 0.5, 28.0, 6.0, 56, 12);
        Map<String, TileHeatmapSampler.TileMosaic> mosaics = Map.of(
            "hot@15", mosaic("hot", 0xFFFFFFFF, parameters),
            "blue@15", mosaic("blue", 0xFF006CFF, parameters),
            "bluered@15", mosaic("bluered", 0xFFFF1028, parameters),
            "purple@15", mosaic("purple", 0xFFE4BBFF, parameters),
            "gray@15", mosaic("gray", 0xFFFF55DD, parameters)
        );
        TileHeatmapSampler.TileMosaicSet set = new TileHeatmapSampler.TileMosaicSet(
            InferenceMode.STABLE_FIXED_SCALE,
            15,
            13,
            15,
            13,
            TileHeatmapSampler.TILE_SIZE,
            mosaics,
            parameters,
            parameters
        );

        TileHeatmapSampler.AggregateVisualization visualization =
            new TileHeatmapSampler().buildAggregatedIntensityVisualization(set, 15);

        assertEquals(List.of("bluered", "purple", "hot", "gray", "blue").size(), visualization.colors().size());
        assertTrue(visualization.colors().containsAll(List.of("hot", "blue", "bluered", "purple", "gray")));
        int center = visualization.image().getRGB(256, 256);
        assertTrue(alpha(center) > alpha(visualization.image().getRGB(0, 0)));
        assertEquals(0x00FFFFFF, center & 0x00FFFFFF);
        assertTrue(visualization.metadataJson().contains("\"palette\":\"white-on-transparent\""));
        assertTrue(visualization.metadataJson().contains("\"aggregatePowerMean\":1.25"));
    }

    @Test
    void aggregateIntensityRewardsCrossColorConsensusMoreThanSingleSourceSignal() {
        Map<String, BufferedImage> consensus = Map.of(
            "hot", pointImage(0xFFFFFFFF),
            "blue", pointImage(0xFF006CFF),
            "bluered", pointImage(0xFFFF1028),
            "purple", pointImage(0xFFE4BBFF),
            "gray", pointImage(0xFFFF55DD)
        );
        Map<String, BufferedImage> singleSource = Map.of(
            "hot", pointImage(0xFFFFFFFF),
            "blue", pointImage(0),
            "bluered", pointImage(0),
            "purple", pointImage(0),
            "gray", pointImage(0)
        );

        double consensusCenter = RenderedHeatmapSampler.aggregatedSourceIntensityAt(consensus, 1, 1);
        double singleCenter = RenderedHeatmapSampler.aggregatedSourceIntensityAt(singleSource, 1, 1);
        double background = RenderedHeatmapSampler.aggregatedSourceIntensityAt(consensus, 0, 0);

        assertTrue(consensusCenter > singleCenter + 0.25);
        assertTrue(consensusCenter > background + 0.50);
    }

    private TileHeatmapSampler.TileMosaic mosaic(
        String color,
        int centerArgb,
        TileHeatmapSampler.SamplingParameters parameters
    ) {
        BufferedImage image = new BufferedImage(TileHeatmapSampler.TILE_SIZE, TileHeatmapSampler.TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 250; y <= 262; y++) {
            for (int x = 250; x <= 262; x++) {
                image.setRGB(x, y, centerArgb);
            }
        }
        return new TileHeatmapSampler.TileMosaic(color, 15, 0.0, 0.0, image, List.of(), Map.of(),
            parameters, false, 1.0);
    }

    private BufferedImage pointImage(int centerArgb) {
        BufferedImage image = new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, centerArgb);
        return image;
    }

    private int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
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

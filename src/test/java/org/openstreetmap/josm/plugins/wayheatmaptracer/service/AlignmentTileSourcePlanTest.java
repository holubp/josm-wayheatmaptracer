package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

class AlignmentTileSourcePlanTest {
    @Test
    void diagnosticVisibilityAndAlternativeMappingsNeverExpandAlignmentSources() {
        assertEquals(List.of("hot"), AlignmentTileSourcePlan.from(config(false, false, false,
            IntensitySamplingMode.COLOR_MAPPING)).orderedColors());
        assertEquals(List.of("hot"), AlignmentTileSourcePlan.from(config(true, false, false,
            IntensitySamplingMode.COLOR_MAPPING)).orderedColors());
        assertEquals(List.of("hot"), AlignmentTileSourcePlan.from(config(true, true, false,
            IntensitySamplingMode.COLOR_MAPPING)).orderedColors());
    }

    @Test
    void explicitAggregateUsesExactlyAllFiveSourcesOnlyForColorMapping() {
        AlignmentTileSourcePlan aggregate = AlignmentTileSourcePlan.from(config(false, false, true,
            IntensitySamplingMode.COLOR_MAPPING));
        assertEquals(List.of("hot", "blue", "bluered", "purple", "gray"), aggregate.orderedColors());
        assertTrue(aggregate.aggregateDetectorRequested());

        AlignmentTileSourcePlan shown = AlignmentTileSourcePlan.from(config(true, false, true,
            IntensitySamplingMode.COLOR_MAPPING));
        assertEquals(aggregate, shown);

        AlignmentTileSourcePlan luminance = AlignmentTileSourcePlan.from(config(true, true, true,
            IntensitySamplingMode.DIRECT_LUMINANCE));
        assertEquals(List.of("hot"), luminance.orderedColors());
        assertFalse(luminance.aggregateDetectorRequested());
    }

    private ManagedHeatmapConfig config(boolean showAggregate, boolean alternatives, boolean aggregate,
        IntensitySamplingMode samplingMode) {
        return new ManagedHeatmapConfig("key", "policy", "signature", "session", "all", "hot", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, TrackerMode.CORRIDOR_AWARE, false, false, alternatives,
            aggregate, showAggregate, false, false, false, false, false, 18, 4, 2.0,
            InferenceMode.STABLE_FIXED_SCALE, 15, 13, 7.0, 1.56, samplingMode, 42L);
    }
}

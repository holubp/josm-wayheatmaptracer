package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ManagedHeatmapConfigTest {
    @Test
    void hasManagedAccessValuesRequiresAllFields() {
        ManagedHeatmapConfig missing = new ManagedHeatmapConfig("", "p", "s", "t", "all", "hot", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, false, false, false, false, false, false, true, false, false, false, 18, 4, 3.0,
            InferenceMode.STABLE_FIXED_SCALE, 15, 13, 28.0, 6.0, IntensitySamplingMode.COLOR_MAPPING, 0L);
        ManagedHeatmapConfig present = new ManagedHeatmapConfig("k", "p", "s", "t", "all", "hot", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, false, false, false, false, false, false, true, false, false, false, 18, 4, 3.0,
            InferenceMode.STABLE_FIXED_SCALE, 15, 13, 28.0, 6.0, IntensitySamplingMode.COLOR_MAPPING, 0L);

        assertFalse(missing.hasManagedAccessValues());
        assertTrue(present.hasManagedAccessValues());
    }

    @Test
    void withAlignmentModeOnlyChangesAlignmentMode() {
        ManagedHeatmapConfig config = new ManagedHeatmapConfig("k", "p", "s", "t", "all", "bluered", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, true, true, true, true, true, false, true, false, false, false, 18, 4, 3.0,
            InferenceMode.STABLE_FIXED_SCALE, 15, 13, 7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 42L);

        ManagedHeatmapConfig changed = config.withAlignmentMode(AlignmentMode.PRECISE_SHAPE);

        assertEquals(AlignmentMode.PRECISE_SHAPE, changed.alignmentMode());
        assertEquals(config.color(), changed.color());
        assertEquals(config.searchHalfWidthMeters(), changed.searchHalfWidthMeters());
        assertEquals(config.multiColorDetection(), changed.multiColorDetection());
        assertEquals(config.aggregateAllColorSchemes(), changed.aggregateAllColorSchemes());
        assertEquals(config.showAggregateIntensityLayer(), changed.showAggregateIntensityLayer());
        assertEquals(config.cacheBuster(), changed.cacheBuster());
    }
}

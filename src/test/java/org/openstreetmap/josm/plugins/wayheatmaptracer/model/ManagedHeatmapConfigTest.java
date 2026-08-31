package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ManagedHeatmapConfigTest {
    @Test
    void hasManagedAccessValuesRequiresAllFields() {
        ManagedHeatmapConfig missing = new ManagedHeatmapConfig("", "p", "s", "t", "all", "hot", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, TrackerMode.LEGACY_V02, false, false, false, false, false, false, true, false, false, false, 18, 4, 3.0,
            InferenceMode.STABLE_FIXED_SCALE, 15, 13, 28.0, 6.0, IntensitySamplingMode.COLOR_MAPPING, 0L);
        ManagedHeatmapConfig present = new ManagedHeatmapConfig("k", "p", "s", "t", "all", "hot", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, TrackerMode.LEGACY_V02, false, false, false, false, false, false, true, false, false, false, 18, 4, 3.0,
            InferenceMode.STABLE_FIXED_SCALE, 15, 13, 28.0, 6.0, IntensitySamplingMode.COLOR_MAPPING, 0L);

        assertFalse(missing.hasManagedAccessValues());
        assertTrue(present.hasManagedAccessValues());
    }

    @Test
    void withAlignmentModeOnlyChangesAlignmentMode() {
        ManagedHeatmapConfig config = new ManagedHeatmapConfig("k", "p", "s", "t", "all", "bluered", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, TrackerMode.CORRIDOR_AWARE, true, true, true, true, true, false, true, false, false, false, 18, 4, 3.0,
            InferenceMode.STABLE_FIXED_SCALE, 15, 13, 7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 42L);

        ManagedHeatmapConfig changed = config.withAlignmentMode(AlignmentMode.PRECISE_SHAPE);

        assertEquals(AlignmentMode.PRECISE_SHAPE, changed.alignmentMode());
        assertEquals(config.color(), changed.color());
        assertEquals(config.searchHalfWidthMeters(), changed.searchHalfWidthMeters());
        assertEquals(config.multiColorDetection(), changed.multiColorDetection());
        assertEquals(config.aggregateAllColorSchemes(), changed.aggregateAllColorSchemes());
        assertEquals(config.showAggregateIntensityLayer(), changed.showAggregateIntensityLayer());
        assertEquals(TrackerMode.CORRIDOR_AWARE, changed.trackerMode());
        assertEquals(config.cacheBuster(), changed.cacheBuster());
    }

    @Test
    void trackerModePreferenceFallsBackToPublicDefault() {
        assertEquals(TrackerMode.CORRIDOR_AWARE, TrackerMode.defaultMode());
        assertEquals(TrackerMode.CORRIDOR_AWARE, TrackerMode.fromPreference("corridor_aware"));
        assertEquals(TrackerMode.CORRIDOR_AWARE, TrackerMode.fromPreference(null));
        assertEquals(TrackerMode.CORRIDOR_AWARE, TrackerMode.fromPreference("future-mode"));
        assertEquals(TrackerMode.LEGACY_V02, TrackerMode.fromPreference("legacy_v02"));
    }

    @Test
    void redactedDiagnosticsUseTheEffectivePublicDefault() {
        ManagedHeatmapConfig config = new ManagedHeatmapConfig("", "", "", "", "all", "hot", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, null, false, false, false, false, false, false, false,
            false, false, false, 18, 4, 3.0, InferenceMode.STABLE_FIXED_SCALE, 15, 13, 7.01,
            1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);

        assertEquals(TrackerMode.CORRIDOR_AWARE, config.trackerMode());
        assertTrue(config.toRedactedJson().contains("\"trackerMode\":\"CORRIDOR_AWARE\""));
    }
}

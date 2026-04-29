package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InferenceModeTest {
    @Test
    void stableFixedScaleIsDefaultForMissingOrUnknownPreferences() {
        assertEquals(InferenceMode.STABLE_FIXED_SCALE, InferenceMode.fromPreference(""));
        assertEquals(InferenceMode.STABLE_FIXED_SCALE, InferenceMode.fromPreference("legacy"));
        assertTrue(InferenceMode.fromPreference(null).stableFixedScale());
    }

    @Test
    void rawHighResolutionCanStillBeSelectedExplicitly() {
        assertEquals(InferenceMode.RAW_HIGH_RESOLUTION, InferenceMode.fromPreference("RAW_HIGH_RESOLUTION"));
    }
}

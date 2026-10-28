package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LegacyFirstTrackerModeTest {
    @Test void freshAndInvalidChoicesUseLegacy() {
        assertEquals(TrackerMode.LEGACY_V02, TrackerMode.defaultMode());
        for (String value : new String[] {null, "", " ", "unknown", "HYBRID"}) {
            assertEquals(TrackerMode.LEGACY_V02, TrackerMode.fromPreference(value));
        }
    }

    @Test void newHybridHasDistinctIdentityAndOldHybridIsNotSelectable() {
        assertEquals("LEGACY_THEN_B", TrackerMode.fromPreference("LEGACY_THEN_B").name());
        assertFalse(Arrays.stream(TrackerMode.selectableValues()).anyMatch(mode -> mode.name().equals("HYBRID")));
    }

    @Test void explicitIndependentEnginesRemainUnchanged() {
        for (String value : new String[] {"LEGACY_V02", "CORRIDOR_AWARE", "PROBABILISTIC", "DIRECTIONAL_IMAGE"}) {
            assertEquals(value, TrackerMode.fromPreference(value).name());
        }
    }
}

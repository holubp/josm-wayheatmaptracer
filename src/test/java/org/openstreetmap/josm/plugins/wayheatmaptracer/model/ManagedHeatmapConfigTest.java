package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ManagedHeatmapConfigTest {
    @Test
    void hasManagedAccessValuesRequiresAllFields() {
        ManagedHeatmapConfig missing = new ManagedHeatmapConfig("", "p", "s", "t", "all", "hot", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, false, false, false, false, false, false, 18, 4, 3.0);
        ManagedHeatmapConfig present = new ManagedHeatmapConfig("k", "p", "s", "t", "all", "hot", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, false, false, false, false, false, false, 18, 4, 3.0);

        assertFalse(missing.hasManagedAccessValues());
        assertTrue(present.hasManagedAccessValues());
    }
}

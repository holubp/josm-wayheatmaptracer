package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.GeometryCleanupSettingsAction;

class WayHeatmapTracerPluginTest {
    @Test
    void geometryCleanupSettingsActionIsAlwaysAvailableAndHasNoShortcut() {
        GeometryCleanupSettingsAction action = new GeometryCleanupSettingsAction();

        assertEquals("Geometry Cleanup Settings...", action.getValue(javax.swing.Action.NAME));
        assertTrue(action.isEnabled());
        action.destroy();
    }
}

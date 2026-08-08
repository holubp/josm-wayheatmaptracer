package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class AlignmentConfigTest {
    @Test
    void compatibilityWrapperDisablesCleanup() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ManagedHeatmapConfig heatmap = PluginPreferences.load();

        AlignmentConfig config = AlignmentConfig.withoutCleanup(heatmap);

        assertEquals(heatmap, config.heatmap());
        assertTrue(config.cleanup().isDisabled());
    }

    @Test
    void rejectsMissingSettingsGroups() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ManagedHeatmapConfig heatmap = PluginPreferences.load();

        assertThrows(IllegalArgumentException.class, () -> new AlignmentConfig(null,
            GeometryCleanupConfig.disabled()));
        assertThrows(IllegalArgumentException.class, () -> new AlignmentConfig(heatmap, null));
    }
}

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

    @Test
    void retryWidthOverrideIsRunScopedAndLeavesPersistedHeatmapUntouched() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ManagedHeatmapConfig heatmap = PluginPreferences.load();
        AlignmentConfig base = new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());

        AlignmentConfig retry = base.withSearchHalfWidthMetersOverride(14.0);

        assertTrue(base.searchHalfWidthMetersOverride().isEmpty());
        assertEquals(7.01, base.effectiveHeatmap().searchHalfWidthMeters(), 1e-9);
        assertEquals(14.0, retry.searchHalfWidthMetersOverride().orElseThrow(), 1e-9);
        assertEquals(14.0, retry.effectiveHeatmap().searchHalfWidthMeters(), 1e-9);
        assertEquals(7.01, heatmap.searchHalfWidthMeters(), 1e-9);
    }

    @Test
    void rejectsInvalidRetryWidthOverride() {
        Config.setPreferencesInstance(new MemoryPreferences());
        AlignmentConfig base = new AlignmentConfig(PluginPreferences.load(), GeometryCleanupConfig.disabled());

        assertThrows(IllegalArgumentException.class, () -> base.withSearchHalfWidthMetersOverride(0.0));
        assertThrows(IllegalArgumentException.class, () -> base.withSearchHalfWidthMetersOverride(Double.NaN));
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class PluginPreferencesTest {
    private static final String PREFIX = "wayheatmaptracer.";

    @BeforeEach
    void resetPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void absentCleanupPreferencesRemainDisabled() {
        GeometryCleanupConfig config = PluginPreferences.loadGeometryCleanup();

        assertTrue(config.isDisabled());
        assertFalse(Config.getPref().getKeySet().contains(PREFIX + "cleanup.schemaVersion"));
    }

    @Test
    void cleanupPreferencesRoundTripWithoutLoss() {
        GeometryCleanupConfig expected = GeometryCleanupPreset.STRONG
            .apply(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE)
            .withRippleStrength(0.72)
            .withLaplacianStrength(0.31)
            .withLaplacianPassCount(5)
            .withSimplificationDeviationMeters(2.75)
            .withMinimumFitRetention(0.93);

        PluginPreferences.saveGeometryCleanup(expected);

        assertEquals(expected, PluginPreferences.loadGeometryCleanup());
        assertEquals(1, Config.getPref().getInt(PREFIX + "cleanup.schemaVersion", 0));
    }

    @Test
    void oldEnabledSimplificationMigratesOnceToReducePointsOnly() {
        Config.getPref().putBoolean(PREFIX + "simplifyEnabled", true);
        Config.getPref().putDouble(PREFIX + "simplifyTolerancePx", 4.5);

        GeometryCleanupConfig migrated = PluginPreferences.loadGeometryCleanup();

        assertEquals(GeometryCleanupMode.REDUCE_POINTS_ONLY, migrated.mode());
        assertEquals(GeometryCleanupPreset.CUSTOM, migrated.preset());
        assertEquals(4.5, migrated.simplificationDeviationMeters());
        assertEquals(1, Config.getPref().getInt(PREFIX + "cleanup.schemaVersion", 0));

        Config.getPref().putDouble(PREFIX + "simplifyTolerancePx", 12.0);
        assertEquals(migrated, PluginPreferences.loadGeometryCleanup(),
            "The schema marker must prevent a second legacy-key migration");
    }

    @Test
    void savingCleanupKeepsLegacyDowngradeKeysSynchronized() {
        GeometryCleanupConfig config = GeometryCleanupPreset.CONSERVATIVE
            .apply(GeometryCleanupMode.REDUCE_POINTS_ONLY)
            .withSimplificationDeviationMeters(1.25);

        PluginPreferences.saveGeometryCleanup(config);

        assertTrue(Config.getPref().getBoolean(PREFIX + "simplifyEnabled", false));
        assertEquals(1.25, Config.getPref().getDouble(PREFIX + "simplifyTolerancePx", -1.0));

        PluginPreferences.saveGeometryCleanup(GeometryCleanupConfig.disabled());
        assertFalse(Config.getPref().getBoolean(PREFIX + "simplifyEnabled", true));
    }

    @Test
    void currentHeatmapConfigCannotPreCleanRawCandidatesAfterMigration() {
        PluginPreferences.saveGeometryCleanup(GeometryCleanupPreset.BALANCED
            .apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertFalse(PluginPreferences.load().simplifyEnabled(),
            "The legacy runtime flag must be disabled once cleanup has its own schema");
        PluginPreferences.save(PluginPreferences.load());
        assertTrue(Config.getPref().getBoolean(PREFIX + "simplifyEnabled", false),
            "Saving heatmap settings must not overwrite the synchronized downgrade key");
    }

    @Test
    void malformedNumericCleanupPreferencesFailExplicitly() {
        Config.getPref().putInt(PREFIX + "cleanup.schemaVersion", 1);
        Config.getPref().put(PREFIX + "cleanup.mode", GeometryCleanupMode.REDUCE_POINTS_ONLY.name());
        Config.getPref().put(PREFIX + "cleanup.preset", GeometryCleanupPreset.CUSTOM.name());
        Config.getPref().putDouble(PREFIX + "cleanup.rippleScaleMeters", Double.NaN);

        assertThrows(IllegalArgumentException.class, PluginPreferences::loadGeometryCleanup);
    }
}

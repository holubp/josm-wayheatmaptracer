package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupChoice;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class GeometryCleanupSettingsTest {
    @BeforeEach
    void resetPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void namedPresetsExposeConfiguredSixTenAndTwentyMetreScales() {
        GeometryCleanupSettingsModel model = new GeometryCleanupSettingsModel(
            GeometryCleanupConfig.disabled());

        model.selectMode(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE);
        model.selectPreset(GeometryCleanupPreset.CONSERVATIVE);
        assertEquals(6.0, model.config().rippleScaleMeters());
        model.selectPreset(GeometryCleanupPreset.BALANCED);
        assertEquals(10.0, model.config().rippleScaleMeters());
        model.selectPreset(GeometryCleanupPreset.STRONG);
        assertEquals(20.0, model.config().rippleScaleMeters());
    }

    @Test
    void selectingNamedChoiceFromOffEnablesConstrainedCleanup() {
        GeometryCleanupSettingsModel model = new GeometryCleanupSettingsModel(
            GeometryCleanupConfig.disabled());

        model.selectChoice(GeometryCleanupChoice.BALANCED);

        assertEquals(GeometryCleanupChoice.BALANCED, model.choice());
        assertEquals(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE, model.config().mode());
        assertTrue(model.config().cleanedAlternativeRequested());
    }

    @Test
    void modeControlsEnableOnlyApplicableSettings() {
        GeometryCleanupSettingsModel model = new GeometryCleanupSettingsModel(
            GeometryCleanupConfig.disabled());

        GeometryCleanupSettingsModel.ControlState disabled = model.controlState();
        assertFalse(disabled.ripple());
        assertFalse(disabled.laplacian());
        assertFalse(disabled.reduction());
        assertFalse(disabled.fitRetention());

        model.selectMode(GeometryCleanupMode.REDUCE_POINTS_ONLY);
        GeometryCleanupSettingsModel.ControlState reduceOnly = model.controlState();
        assertFalse(reduceOnly.ripple());
        assertFalse(reduceOnly.laplacian());
        assertTrue(reduceOnly.reduction());
        assertTrue(reduceOnly.fitRetention());

        model.selectMode(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE);
        GeometryCleanupSettingsModel.ControlState smoothing = model.controlState();
        assertTrue(smoothing.ripple());
        assertTrue(smoothing.laplacian());
        assertTrue(smoothing.reduction());
        assertTrue(smoothing.fitRetention());
    }

    @Test
    void displayLabelsAreReadable() {
        assertEquals("None", GeometryCleanupMode.NONE.toString());
        assertEquals("Reduce points only", GeometryCleanupMode.REDUCE_POINTS_ONLY.toString());
        assertEquals("Constrained smoothing + reduce points",
            GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE.toString());
        assertEquals("Conservative", GeometryCleanupPreset.CONSERVATIVE.toString());
    }

    @Test
    void numericEditsProduceCustomConfiguration() {
        GeometryCleanupSettingsModel model = new GeometryCleanupSettingsModel(
            GeometryCleanupPreset.BALANCED.apply());

        model.setRippleStrength(0.60);

        assertEquals(GeometryCleanupPreset.CUSTOM, model.config().preset());
        assertEquals(0.60, model.config().rippleStrength());
    }

    @Test
    void unsavedModelEditsLeavePersistedConfigurationUntouched() {
        GeometryCleanupConfig persisted = GeometryCleanupPreset.CONSERVATIVE.apply();
        PluginPreferences.saveGeometryCleanup(persisted);
        GeometryCleanupSettingsModel model = new GeometryCleanupSettingsModel(
            PluginPreferences.loadGeometryCleanup());

        model.setSimplificationDeviationMeters(4.0);

        assertEquals(persisted, PluginPreferences.loadGeometryCleanup(),
            "Cancelling the dialog does not save its model state");
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeometryCleanupConfigTest {
    @Test
    void disabledUsesBalancedValuesWithoutRequestingASecondCandidate() {
        GeometryCleanupConfig config = GeometryCleanupConfig.disabled();

        assertEquals(GeometryCleanupMode.NONE, config.mode());
        assertEquals(GeometryCleanupPreset.BALANCED, config.preset());
        assertEquals(10.0, config.rippleScaleMeters());
        assertFalse(config.cleanedAlternativeRequested());
        assertTrue(config.isDisabled());
    }

    @Test
    void presetsUseExactRippleScales() {
        assertEquals(6.0, GeometryCleanupPreset.CONSERVATIVE.apply().rippleScaleMeters());
        assertEquals(10.0, GeometryCleanupPreset.BALANCED.apply().rippleScaleMeters());
        assertEquals(20.0, GeometryCleanupPreset.STRONG.apply().rippleScaleMeters());
    }

    @Test
    void changingPresetDerivedNumericValueSelectsCustom() {
        GeometryCleanupConfig balanced = GeometryCleanupPreset.BALANCED.apply();

        GeometryCleanupConfig changed = balanced.withRippleStrength(0.61);

        assertEquals(GeometryCleanupPreset.CUSTOM, changed.preset());
        assertEquals(0.61, changed.rippleStrength());
        assertEquals(balanced.rippleScaleMeters(), changed.rippleScaleMeters());
    }

    @Test
    void invalidValuesAndInconsistentRequestsAreRejected() {
        GeometryCleanupConfig valid = GeometryCleanupPreset.BALANCED.apply();

        assertThrows(IllegalArgumentException.class, () -> valid.withRippleScaleMeters(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> valid.withLaplacianStrength(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> valid.withLaplacianPassCount(0));
        assertThrows(IllegalArgumentException.class, () -> valid.withSimplificationDeviationMeters(-0.1));
        assertThrows(IllegalArgumentException.class, () -> valid.withMinimumFitRetention(1.1));
        assertThrows(IllegalArgumentException.class, () -> new GeometryCleanupConfig(
            GeometryCleanupMode.NONE, GeometryCleanupPreset.CUSTOM, 10.0, 0.5, 0.2, 2, 1.0, 0.9, true));
    }

    @Test
    void changingModeKeepsCandidateRequestConsistent() {
        GeometryCleanupConfig enabled = GeometryCleanupPreset.BALANCED.apply()
            .withMode(GeometryCleanupMode.REDUCE_POINTS_ONLY);
        GeometryCleanupConfig disabled = enabled.withMode(GeometryCleanupMode.NONE);

        assertTrue(enabled.cleanedAlternativeRequested());
        assertFalse(disabled.cleanedAlternativeRequested());
    }

    @Test
    void preferenceParsingFailsSafely() {
        assertEquals(GeometryCleanupMode.NONE, GeometryCleanupMode.fromPreference("future"));
        assertEquals(GeometryCleanupPreset.BALANCED, GeometryCleanupPreset.fromPreference("future"));
        assertEquals(GeometryCleanupMode.REDUCE_POINTS_ONLY,
            GeometryCleanupMode.fromPreference("reduce_points_only"));
    }

    @Test
    void redactedJsonContainsUnitsButNoCredentialFields() {
        String json = GeometryCleanupPreset.STRONG.apply().toRedactedJson();

        assertTrue(json.contains("\"rippleScaleMeters\":20.0"));
        assertTrue(json.contains("\"cleanedAlternativeRequested\":true"));
        assertFalse(json.toLowerCase().contains("cookie"));
        assertFalse(json.toLowerCase().contains("token"));
        assertFalse(json.toLowerCase().contains("signature"));
    }
}

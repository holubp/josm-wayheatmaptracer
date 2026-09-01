package org.openstreetmap.josm.plugins.wayheatmaptracer.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupChoice;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
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
    void freshPreferencesUseCorridorAwareTrackerButKeepCleanupDisabled() {
        assertEquals(TrackerMode.CORRIDOR_AWARE, PluginPreferences.load().trackerMode());
        assertTrue(PluginPreferences.loadGeometryCleanup().isDisabled());
    }

    @Test
    void explicitLegacyTrackerPreferenceRoundTripsWithoutPromotion() {
        Config.getPref().put(PREFIX + "trackerMode", TrackerMode.LEGACY_V02.name());

        assertEquals(TrackerMode.LEGACY_V02, PluginPreferences.load().trackerMode());

        PluginPreferences.save(PluginPreferences.load());
        assertEquals(TrackerMode.LEGACY_V02.name(),
            Config.getPref().get(PREFIX + "trackerMode", ""));
    }

    @Test
    void unknownTrackerPreferenceUsesPublicDefault() {
        Config.getPref().put(PREFIX + "trackerMode", "removed-future-mode");

        assertEquals(TrackerMode.CORRIDOR_AWARE, PluginPreferences.load().trackerMode());
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
        assertEquals(2, Config.getPref().getInt(PREFIX + "cleanup.schemaVersion", 0));
        assertEquals(GeometryCleanupChoice.CUSTOM.name(),
            Config.getPref().get(PREFIX + "cleanup.choice", ""));
    }

    @Test
    void oldEnabledSimplificationMigratesOnceToReducePointsOnly() {
        Config.getPref().putBoolean(PREFIX + "simplifyEnabled", true);
        Config.getPref().putDouble(PREFIX + "simplifyTolerancePx", 4.5);

        GeometryCleanupConfig migrated = PluginPreferences.loadGeometryCleanup();

        assertEquals(GeometryCleanupMode.REDUCE_POINTS_ONLY, migrated.mode());
        assertEquals(GeometryCleanupPreset.CUSTOM, migrated.preset());
        assertEquals(4.5, migrated.simplificationDeviationMeters());
        assertEquals(2, Config.getPref().getInt(PREFIX + "cleanup.schemaVersion", 0));

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
    void malformedNumericCleanupPreferencesFallBackToOff() {
        Config.getPref().putInt(PREFIX + "cleanup.schemaVersion", 1);
        Config.getPref().put(PREFIX + "cleanup.mode", GeometryCleanupMode.REDUCE_POINTS_ONLY.name());
        Config.getPref().put(PREFIX + "cleanup.preset", GeometryCleanupPreset.CUSTOM.name());
        Config.getPref().putDouble(PREFIX + "cleanup.rippleScaleMeters", Double.NaN);

        assertEquals(GeometryCleanupConfig.disabled(), PluginPreferences.loadGeometryCleanup());
    }

    @Test
    void schemaOneDisabledModeMigratesToOffWithoutActivatingDormantPreset() {
        Config.getPref().putInt(PREFIX + "cleanup.schemaVersion", 1);
        Config.getPref().put(PREFIX + "cleanup.mode", GeometryCleanupMode.NONE.name());
        Config.getPref().put(PREFIX + "cleanup.preset", GeometryCleanupPreset.STRONG.name());

        GeometryCleanupConfig migrated = PluginPreferences.loadGeometryCleanup();

        assertEquals(GeometryCleanupChoice.OFF, migrated.choice());
        assertFalse(migrated.cleanedAlternativeRequested());
        assertEquals(2, Config.getPref().getInt(PREFIX + "cleanup.schemaVersion", 0));
    }

    @Test
    void credentialChangeAdvancesGenerationButUnrelatedSaveDoesNot() {
        ManagedHeatmapConfig initial = withCredentials(PluginPreferences.load(), "key-a", "policy-a",
            "signature-a", "session-a", 41L);
        PluginPreferences.save(initial);
        long firstGeneration = PluginPreferences.load().cacheBuster();
        assertTrue(firstGeneration > 41L);

        PluginPreferences.save(PluginPreferences.load());
        assertEquals(firstGeneration, PluginPreferences.load().cacheBuster());

        PluginPreferences.save(withCredentials(PluginPreferences.load(), "key-b", "policy-a",
            "signature-a", "session-a", firstGeneration));
        long keyGeneration = PluginPreferences.load().cacheBuster();
        assertTrue(keyGeneration > firstGeneration);

        PluginPreferences.save(withCredentials(PluginPreferences.load(), "key-b", "policy-b",
            "signature-a", "session-a", keyGeneration));
        long policyGeneration = PluginPreferences.load().cacheBuster();
        assertTrue(policyGeneration > keyGeneration);

        PluginPreferences.save(withCredentials(PluginPreferences.load(), "key-b", "policy-b",
            "signature-b", "session-a", policyGeneration));
        long signatureGeneration = PluginPreferences.load().cacheBuster();
        assertTrue(signatureGeneration > policyGeneration);

        PluginPreferences.save(withCredentials(PluginPreferences.load(), "key-b", "policy-b",
            "signature-b", "session-b", signatureGeneration));
        assertTrue(PluginPreferences.load().cacheBuster() > signatureGeneration);
    }

    @Test
    void redactedSummaryContainsNoCredentialDerivedCharacters() {
        String sentinel = "SENTINEL-credential-value-9f47";
        ManagedHeatmapConfig config = withCredentials(PluginPreferences.load(), sentinel, sentinel,
            sentinel, sentinel, 1L);

        assertEquals("managedAccessConfigured=true", config.redactedSummary());
        assertFalse(config.redactedSummary().contains("SENTINEL"));
        assertFalse(config.toRedactedJson().contains("SENTINEL"));
        assertFalse(config.toString().contains("SENTINEL"));
    }

    @Test
    void manualCacheBypassAlwaysAdvancesMonotonically() {
        Config.getPref().putLong(PREFIX + "cacheBuster", Long.MAX_VALUE - 2);

        PluginPreferences.bumpManagedTileCacheBuster();

        assertEquals(Long.MAX_VALUE - 1, Config.getPref().getLong(PREFIX + "cacheBuster", 0L));
    }

    private ManagedHeatmapConfig withCredentials(
        ManagedHeatmapConfig base,
        String key,
        String policy,
        String signature,
        String session,
        long generation
    ) {
        return new ManagedHeatmapConfig(key, policy, signature, session, base.activity(), base.color(),
            base.manualLayerName(), base.layerRegex(), base.alignmentMode(), base.trackerMode(), base.verbose(),
            base.debug(), base.multiColorDetection(), base.aggregateAllColorSchemes(),
            base.showAggregateIntensityLayer(), base.candidateRatingEnabled(), base.parallelWayAwareness(),
            base.allowUndownloadedAlignment(), base.adjustJunctionNodes(), base.simplifyEnabled(),
            base.crossSectionHalfWidthPx(), base.crossSectionStepPx(), base.simplifyTolerancePx(),
            base.inferenceMode(), base.inferenceZoom(), base.validationZoom(), base.searchHalfWidthMeters(),
            base.sampleStepMeters(), base.intensitySamplingMode(), generation);
    }
}

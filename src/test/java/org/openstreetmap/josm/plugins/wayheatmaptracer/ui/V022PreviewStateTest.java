package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoverySettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TracingSettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** CP12 state and preference contracts T115-T122. */
class V022PreviewStateTest {
    private static final String PREFIX = "wayheatmaptracer.";

    @BeforeEach
    void resetPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void t115AllFiveEnginesAreSelectableAndMissingPreferenceDefaultsToA() {
        assertEquals(5, TrackerMode.selectableValues().length);
        assertEquals(TrackerMode.CORRIDOR_AWARE, PluginPreferences.loadTracingSettings().engine());
    }

    @Test
    void t116ExistingEngineChoiceRoundTripsWithoutPromotion() {
        TracingSettings settings = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
            TrackerMode.PROBABILISTIC, RecoverySettings.defaults(7.01), false);

        PluginPreferences.saveTracingSettings(settings);

        assertEquals(settings, PluginPreferences.loadTracingSettings());
    }

    @Test
    void t117LegacyAdjustJunctionMigratesOnlyToLegacyBoundedMove() {
        Config.getPref().putBoolean(PREFIX + "adjustJunctionNodes", true);

        TracingSettings settings = PluginPreferences.loadTracingSettings();

        assertEquals(JunctionPolicy.LEGACY_BOUNDED_MOVE, settings.recovery().junctionPolicy());
        assertFalse(settings.recovery().reconstructIncidentWays());
    }

    @Test
    void t118IncidentReconstructionRequiresExplicitReattachment() {
        assertThrows(IllegalArgumentException.class,
            () -> new RecoverySettings(RecoverySettings.CURRENT_SCHEMA_VERSION, false, 7.01, 20.0,
                JunctionPolicy.FIXED, true));
        assertDoesNotThrow(() -> new RecoverySettings(RecoverySettings.CURRENT_SCHEMA_VERSION, false, 7.01,
            7.01, JunctionPolicy.REATTACH, true));
    }

    @Test
    void t119CandidateSwitchClearsExactReviewConfirmation() {
        PreviewReviewState reviewed = applicable("candidate-a").confirm();

        assertFalse(reviewed.withCandidate("candidate-b").confirmed());
    }

    @Test
    void t120PermissionAndSourceChangesInvalidateAllWayReview() {
        PreviewReviewState reviewed = applicable("candidate-a").confirm();
        PreviewReviewState changedPermissions = PreviewReviewState.create("candidate-a", "all-way-plan-hash",
            "permission-hash-2", "source-hash", ValidationReport.Disposition.REVIEW_REQUIRED);
        PreviewReviewState changedSource = PreviewReviewState.create("candidate-a", "all-way-plan-hash",
            "permission-hash", "source-hash-2", ValidationReport.Disposition.REVIEW_REQUIRED);

        assertFalse(reviewed.matches(changedPermissions));
        assertFalse(reviewed.matches(changedSource));
    }

    @Test
    void t121WholePlanHashIncludingIncidentWayOrRelationChangeInvalidatesReview() {
        PreviewReviewState reviewed = applicable("candidate-a").confirm();
        PreviewReviewState incidentChanged = PreviewReviewState.create("candidate-a", "all-way-plan-hash-2",
            "permission-hash", "source-hash", ValidationReport.Disposition.REVIEW_REQUIRED);

        assertFalse(reviewed.matches(incidentChanged));
    }

    @Test
    void t122BlockedPreviewCannotBeConfirmedOrApplied() {
        PreviewReviewState blocked = PreviewReviewState.create("candidate-a", "all-way-plan-hash",
            "permission-hash", "source-hash", ValidationReport.Disposition.HARD_BLOCKED);

        assertThrows(IllegalStateException.class, blocked::confirm);
        assertFalse(blocked.canApply());
        assertTrue(applicable("candidate-a").confirm().canApply());
    }

    private static PreviewReviewState applicable(String candidateId) {
        return PreviewReviewState.create(candidateId, "all-way-plan-hash", "permission-hash", "source-hash",
            ValidationReport.Disposition.REVIEW_REQUIRED);
    }
}

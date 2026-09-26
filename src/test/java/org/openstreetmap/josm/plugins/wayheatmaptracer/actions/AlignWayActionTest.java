package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.ManualJunctionEligibility;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ProductionBundleFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentSourceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoverySettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TracingSettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateAssessment;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateGeometryCleanup;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorCoverage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewSessionController;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedHeatmapLayer;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.IBaseDirectories;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;
/** Verifies action-level candidate selection before the modeless preview opens. */
class AlignWayActionTest {
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void noRouteFromManualJunctionShowsInstructionWhileOrdinaryNoRouteKeepsItsReason(
            @TempDir Path directory)
            throws Exception {
        DataSet dataSet = new DataSet();
        Node start = loadedNode(101, 0.0, -40.0);
        Node junction = loadedNode(102, 0.0, 0.0);
        Node north = loadedNode(103, 40.0, 0.0);
        Node south = loadedNode(104, -40.0, 0.0);
        Way selected = loadedWay(110, start, junction);
        Way receiver = loadedWay(111, south, junction, north);
        for (Node node : List.of(start, junction, north, south)) dataSet.addPrimitive(node);
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        Relation route = new Relation();
        route.setMembers(List.of(new RelationMember("", receiver)));
        route.setOsmId(112, 1);
        route.setModified(false);
        dataSet.addPrimitive(route);
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                List.of(start, junction), Set.of(start, junction));
        int[] pixels = new int[1200 * 1200];
        java.util.Arrays.fill(pixels, 0xff000000);
        LiveBPreviewService.VisibleRaster raster = new LiveBPreviewService.VisibleRaster(
                1200, 1200, pixels, -100.0, -100.0, 100.0, 100.0, 1.0, 1.0,
                OptionalDouble.of(1.0), "visible-test", "EPSG:3857");
        AlignmentConfig config = new AlignmentConfig(configuredCorridor()
                .withTrackerMode(TrackerMode.PROBABILISTIC)
                .withAlignmentMode(AlignmentMode.PRECISE_SHAPE), GeometryCleanupConfig.disabled());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, raster, config, true, permissions));

        assertTrue(AlignWayAction.noPreviewableRouteMessage(captured[0], TrackerMode.PROBABILISTIC)
                .contains("Adjust this junction manually, then run alignment again"));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        assertTrue(computed.pipeline().routes().isEmpty());
        AlignWayAction.recordModernDiagnostics(computed, "blocked", 0, null, false, false,
                "manual-no-route");
        Path diagnostic = directory.resolve("manual-no-route.zip");
        DiagnosticsRegistry.writeLatest(diagnostic.toFile());
        String status = new String(Format15ArchiveReader.read(diagnostic)
                .artifact("attempt-status.json").orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertTrue(status.contains("\"status\":\"blocked\""));
        assertTrue(status.contains("\"manualJunctionReason\":\"PARTICIPATING_RELATION\""));
        RecoveryPermissions fixed = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.FIXED, false);
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, raster, config, true, fixed));
        assertTrue(AlignWayAction.noPreviewableRouteMessage(captured[0], TrackerMode.PROBABILISTIC)
                .contains("no previewable final route"));
        dataSet.removePrimitive(route);
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, raster, config, true, permissions));
        assertTrue(AlignWayAction.noPreviewableRouteMessage(captured[0], TrackerMode.PROBABILISTIC)
                .contains("Adjust this junction manually, then run alignment again."));
        LiveBPreviewService.Computed simpleTNoRoute = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        assertTrue(simpleTNoRoute.pipeline().routes().isEmpty());
        AlignWayAction.recordModernDiagnostics(simpleTNoRoute, "blocked", 0, null,
                false, false, "simple-t-dark-no-route");
        Path simpleDiagnostic = directory.resolve("simple-t-dark-no-route.zip");
        DiagnosticsRegistry.writeLatest(simpleDiagnostic.toFile());
        String simpleStatus = new String(Format15ArchiveReader.read(simpleDiagnostic)
                .artifact("attempt-status.json").orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertTrue(simpleStatus.contains("\"status\":\"blocked\""));
        assertTrue(simpleStatus.contains(
                "\"manualJunctionReason\":\"MISSING_RECEIVER_EVIDENCE\""));
        dataSet.removePrimitive(receiver);
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, raster, config, true, permissions));
        assertTrue(AlignWayAction.noPreviewableRouteMessage(captured[0], TrackerMode.PROBABILISTIC)
                .contains("no previewable final route"));
    }

    @Test
    void incompleteIncidentCaptureExportsTypedBlockedAttempt(@TempDir Path directory)
            throws Exception {
        String failure = "ManualJunctionCaptureException: INCOMPLETE_ARM: incomplete incident way. "
                + "Adjust this junction manually, then run alignment again.";
        var reason = AlignWayAction.manualCaptureReason(failure);
        assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .ManualJunctionEligibility.Reason.INCOMPLETE_ARM, reason);
        assertEquals(null, AlignWayAction.manualCaptureReason(
                "IllegalArgumentException: unrelated source capture failed"));
        AlignWayAction.recordModernUnavailable("blocked", "visible-layer",
                "incomplete-incident", reason);
        Path diagnostic = directory.resolve("incomplete-incident.zip");
        DiagnosticsRegistry.writeLatest(diagnostic.toFile());
        String status = new String(Format15ArchiveReader.read(diagnostic)
                .artifact("attempt-status.json").orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertTrue(status.contains("\"status\":\"blocked\""));
        assertTrue(status.contains("\"manualJunctionReason\":\"INCOMPLETE_ARM\""));
    }

    private static Node loadedNode(long id, double northMeters, double eastMeters) {
        double degrees = 180.0 / Math.PI / 6_378_137.0;
        Node node = new Node(new LatLon(northMeters * degrees, eastMeters * degrees));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static Way loadedWay(long id, Node... nodes) {
        Way way = new Way();
        way.setNodes(List.of(nodes));
        way.setOsmId(id, 1);
        way.setModified(false);
        return way;
    }

    @Test
    void managedRenderedCaptureCannotReachApplyWithoutAnAuthoritativePixelLease(@TempDir Path directory) {
        Config.setPreferencesInstance(new MemoryPreferences());
        Config.setBaseDirectoriesProvider(new IBaseDirectories() {
            @Override public java.io.File getPreferencesDirectory(boolean create) { return directory.toFile(); }
            @Override public java.io.File getUserDataDirectory(boolean create) { return directory.toFile(); }
            @Override public java.io.File getCacheDirectory(boolean create) { return directory.toFile(); }
        });
        ManagedHeatmapLayer layer = new ManagedHeatmapLayer(new ImageryInfo(
            "Test heatmap", "https://example.invalid/{zoom}/{x}/{y}.png", "tms"));
        try {
            LiveBPreviewService.VisibleRaster raster = new LiveBPreviewService.VisibleRaster(12, 12,
                new int[144], 0.0, 0.0, 2.0, 2.0, 1.0, 1.0, OptionalDouble.empty(),
                "test", "EPSG:3857");
            LiveBPreviewService.Captured captured = new LiveBPreviewService.Captured(raster,
                null, null, null, List.of(), List.of(), null, "hot", 1.0, 1.0,
                "settings", "parameters", GeometryCleanupConfig.disabled(),
                AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE, "EPSG:3857");

            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> AlignWayAction.requireSupportedApplySource(captured, layer));

            assertTrue(refusal.getMessage().contains("managed rendered"));
        } finally {
            layer.destroy();
        }
    }
    @Test
    void redoFailureUiUsesOnlyFixedRedactedText() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> shown = new java.util.concurrent.atomic.AtomicReference<>();
        AlignWayAction.redoFailureReporter(shown::set).accept("CloudFront-Signature=private-value");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });

        assertTrue(shown.get().contains("Alignment Redo failed"));
        assertFalse(shown.get().contains("private-value"));
    }
    @Test
    void preCaptureFailureSupersedesPreviousExport(@TempDir Path directory) throws Exception {
        DiagnosticsRegistry.setLastModernBundle(Format15ProductionBundleFactory
            .createUnavailableLive("test", "applied", "visible-layer", "old-attempt"));
        String attemptIdentity = AlignWayAction.beginDiagnosticAttempt();
        AlignWayAction.recordModernUnavailable("failed", "unavailable", attemptIdentity);
        Path exported = directory.resolve("latest.zip");
        DiagnosticsRegistry.writeLatest(exported.toFile());
        String status = new String(Format15ArchiveReader.read(exported)
            .artifact("attempt-status.json").orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertTrue(status.contains("\"status\":\"failed\""));
        assertFalse(status.contains("old-attempt"));
    }

    @Test
    void staleWindowClosingCannotPublishCancellationOverNewerAttempt(@TempDir Path directory)
            throws Exception {
        PreviewSessionController<LiveBPreviewService.Computed> session =
            new PreviewSessionController<>(Runnable::run);
        var first = session.open(() -> { });
        var second = session.open(() -> { });
        AlignWayAction.recordModernUnavailable("started", "visible-layer", "newer-attempt");
        assertFalse(AlignWayAction.closeAndPublishIfCurrent(session, first,
            () -> AlignWayAction.recordModernUnavailable("cancelled", "visible-layer", "old-attempt")));
        Path newest = directory.resolve("newest.zip");
        DiagnosticsRegistry.writeLatest(newest.toFile());
        String status = new String(Format15ArchiveReader.read(newest)
            .artifact("attempt-status.json").orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertTrue(status.contains("\"status\":\"started\""));
        assertTrue(session.isCurrent(second));
        assertTrue(AlignWayAction.closeAndPublishIfCurrent(session, second,
            () -> AlignWayAction.recordModernUnavailable("cancelled", "visible-layer", "newer-attempt")));
        assertFalse(session.isCurrent(second));
        session.close();
    }

    @Test
    void diagnosticBudgetFailureIsResolvedBeforePhysicalApply(@TempDir Path directory)
            throws Exception {
        AtomicInteger applied = new AtomicInteger();
        AlignWayAction.recordModernUnavailable("started", "visible-layer", "budget-attempt");
        assertThrows(IllegalArgumentException.class, () -> AlignWayAction.applyWithPreparedDiagnostics(
            () -> { throw new IllegalArgumentException("diagnostic budget"); },
            applied::incrementAndGet));
        assertEquals(0, applied.get());
        Path exported = directory.resolve("still-started.zip");
        DiagnosticsRegistry.writeLatest(exported.toFile());
        String status = new String(Format15ArchiveReader.read(exported)
            .artifact("attempt-status.json").orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertTrue(status.contains("\"status\":\"started\""));
    }

    @Test
    void modernAttemptStatusDistinguishesBlockedReviewAndResourceLimits() {
        assertEquals("preview-open", AlignWayAction.modernPreviewStatus(
            TraceHypothesisSet.Status.COMPLETE, ValidationReport.Disposition.APPLICABLE));
        assertEquals("review-required", AlignWayAction.modernPreviewStatus(
            TraceHypothesisSet.Status.COMPLETE, ValidationReport.Disposition.REVIEW_REQUIRED));
        assertEquals("blocked", AlignWayAction.modernPreviewStatus(
            TraceHypothesisSet.Status.NO_ROUTE, ValidationReport.Disposition.HARD_BLOCKED));
        assertEquals("resource-limited", AlignWayAction.modernPreviewStatus(
            TraceHypothesisSet.Status.RESOURCE_LIMIT, ValidationReport.Disposition.APPLICABLE));
    }
    @Test
    void manualJunctionAssessmentBlocksApplicableRoutePresentationAndAttemptStatus() {
        var assessment = new ModernSingleWayEditPlanAdapter.Assessment(Optional.empty(),
                ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION,
                "Missing receiver evidence. Adjust this junction manually, then run alignment again.",
                ManualJunctionEligibility.Reason.MISSING_RECEIVER_EVIDENCE);
        ValidationReport.Disposition displayed = AlignWayAction.liveBDisplayedDisposition(
                FinalGeometryEvaluator.Disposition.APPLICABLE, assessment, null);
        assertEquals(ValidationReport.Disposition.HARD_BLOCKED, displayed);
        assertEquals("blocked", AlignWayAction.modernPreviewStatus(
                TraceHypothesisSet.Status.COMPLETE, displayed));
    }
    @Test
    void modernSourceIdentityHashesLayerNamesWithoutExportingSignedValues() {
        String first = AlignWayAction.safeLayerNameIdentity(
            "Heatmap?Signature=private-signature&Policy=private-policy");
        String second = AlignWayAction.safeLayerNameIdentity(
            "Heatmap?Signature=changed-signature&Policy=private-policy");
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertFalse(first.contains("private"));
        assertFalse(first.equals(second));
    }

    @Test
    void previewFailureTextBoundsLongMessagesWithoutDiscardingThePrefix() {
        String suffix = "\u2026\n\nSee the JOSM log for full details.";
        String message = "failure-prefix " + "x".repeat(AlignWayAction.MAXIMUM_PREVIEW_FAILURE_CHARACTERS + 80);

        String displayed = AlignWayAction.previewFailureText(message);

        assertTrue(displayed.startsWith("failure-prefix "));
        assertTrue(displayed.endsWith(suffix));
        assertTrue(displayed.length() <= AlignWayAction.MAXIMUM_PREVIEW_FAILURE_CHARACTERS + suffix.length());
    }

    @Test
    void explicitAVisiblePreviewIsSessionLocalAndLeavesOrdinaryCorridorActionUnchanged() {
        ManagedHeatmapConfig configured = configuredCorridor();
        assertSame(configured, AlignWayAction.effectiveConfig(configured, null, null));
        ManagedHeatmapConfig preview = AlignWayAction.effectiveConfig(configured, null,
                TrackerMode.CORRIDOR_AWARE);
        assertEquals(TrackerMode.CORRIDOR_AWARE, preview.trackerMode());
        assertEquals(AlignmentMode.PRECISE_SHAPE, preview.alignmentMode());
        assertEquals(configured.keyPairId(), preview.keyPairId());
        ManagedHeatmapConfig previewB = AlignWayAction.effectiveConfig(configured, null,
                TrackerMode.PROBABILISTIC);
        assertEquals(TrackerMode.PROBABILISTIC, previewB.trackerMode());
        assertEquals(AlignmentMode.PRECISE_SHAPE, previewB.alignmentMode());
        assertEquals(configured.keyPairId(), previewB.keyPairId());

        AlignmentConfig persisted = new AlignmentConfig(configured, GeometryCleanupConfig.disabled());
        AlignmentConfig effective = new AlignmentConfig(preview, GeometryCleanupConfig.disabled());
        AlignmentConfig effectiveB = new AlignmentConfig(previewB, GeometryCleanupConfig.disabled());
        assertTrue(AlignWayAction.matchesLivePreviewSettings(persisted, effective, persisted,
                null, TrackerMode.CORRIDOR_AWARE));
        assertTrue(AlignWayAction.matchesLivePreviewSettings(persisted, effectiveB, persisted,
                null, TrackerMode.PROBABILISTIC));
        AlignmentConfig changedTracker = new AlignmentConfig(configured.withTrackerMode(
                TrackerMode.PROBABILISTIC), GeometryCleanupConfig.disabled());
        AlignmentConfig changedMode = new AlignmentConfig(configured.withAlignmentMode(
                AlignmentMode.PRECISE_SHAPE), GeometryCleanupConfig.disabled());
        assertFalse(AlignWayAction.matchesLivePreviewSettings(persisted, effective, changedTracker,
                null, TrackerMode.CORRIDOR_AWARE));
        assertFalse(AlignWayAction.matchesLivePreviewSettings(persisted, effective, changedMode,
                null, TrackerMode.CORRIDOR_AWARE));
    }

    @Test
    void explicitHybridPreviewConfigUsesTheSharedReadOnlyContract() {
        ManagedHeatmapConfig configured = configuredCorridor();
        ManagedHeatmapConfig preview = AlignWayAction.effectiveConfig(configured, null,
                TrackerMode.HYBRID);
        assertEquals(TrackerMode.HYBRID, preview.trackerMode());
        assertEquals(AlignmentMode.PRECISE_SHAPE, preview.alignmentMode());
        assertEquals(configured.keyPairId(), preview.keyPairId());
    }

    @Test
    void explicitDirectionalImagePreviewUsesTheSharedReadOnlyContract() {
        ManagedHeatmapConfig configured = configuredCorridor();
        ManagedHeatmapConfig preview = AlignWayAction.effectiveConfig(configured, null,
                TrackerMode.DIRECTIONAL_IMAGE);
        assertEquals(TrackerMode.DIRECTIONAL_IMAGE, preview.trackerMode());
        assertEquals(AlignmentMode.PRECISE_SHAPE, preview.alignmentMode());
        assertEquals(configured.keyPairId(), preview.keyPairId());
    }

    @Test
    void configurationPreflightKeepsBCleanupUnavailableWithoutBlockingACleanup() {
        ManagedHeatmapConfig precise = configuredCorridor().withAlignmentMode(AlignmentMode.PRECISE_SHAPE);
        AlignmentConfig disabled = new AlignmentConfig(precise, GeometryCleanupConfig.disabled());
        AlignmentConfig enabled = new AlignmentConfig(precise, GeometryCleanupPreset.BALANCED
                .apply(GeometryCleanupMode.REDUCE_POINTS_ONLY));
        LiveBPreviewService.VisibleRaster raster = new LiveBPreviewService.VisibleRaster(12, 12,
                new int[144], 0.0, 0.0, 2.0, 2.0, 1.0, 1.0, OptionalDouble.empty(), "test", "EPSG:3857");
        LiveBPreviewService.Captured captured = new LiveBPreviewService.Captured(raster, null, null, null,
                List.of(), List.of(), null, "hot", 1.0, 1.0, "settings", "parameters",
                GeometryCleanupConfig.disabled(), AlignmentMode.PRECISE_SHAPE,
                TrackerMode.PROBABILISTIC, "EPSG:3857");

        assertEquals(AlignWayAction.ModernApplyPreflight.READY,
                AlignWayAction.modernApplyPreflight(captured, disabled));
        assertEquals(AlignWayAction.ModernApplyPreflight.CLEANUP_UNAVAILABLE_FOR_ENGINE,
                AlignWayAction.modernApplyPreflight(captured, enabled));

        LiveBPreviewService.Captured engineA = new LiveBPreviewService.Captured(raster, null, null, null,
                List.of(), List.of(), null, "hot", 1.0, 1.0, "settings", "parameters",
                GeometryCleanupConfig.disabled(), AlignmentMode.PRECISE_SHAPE,
                TrackerMode.CORRIDOR_AWARE, "EPSG:3857");
        assertEquals(AlignWayAction.ModernApplyPreflight.READY,
                AlignWayAction.modernApplyPreflight(engineA, enabled));
    }

    @Test
    void configurationPreflightReturnsTypedSourceAndConfigurationReasons() {
        ManagedHeatmapConfig precise = configuredCorridor()
                .withAlignmentMode(AlignmentMode.PRECISE_SHAPE);
        LiveBPreviewService.VisibleRaster raster = new LiveBPreviewService.VisibleRaster(12, 12,
                new int[144], 0.0, 0.0, 2.0, 2.0, 1.0, 1.0,
                OptionalDouble.empty(), "test", "EPSG:3857");
        LiveBPreviewService.Captured captured = new LiveBPreviewService.Captured(raster, null,
                null, null, List.of(), List.of(), null, "hot", 1.0, 1.0,
                "settings", "parameters", GeometryCleanupConfig.disabled(),
                AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE, "EPSG:3857");
        ManagedHeatmapConfig directIntensity = new ManagedHeatmapConfig(
                precise.keyPairId(), precise.policy(), precise.signature(), precise.sessionToken(),
                precise.activity(), precise.color(), precise.manualLayerName(), precise.layerRegex(),
                precise.alignmentMode(), precise.trackerMode(), precise.verbose(), precise.debug(),
                precise.multiColorDetection(), precise.aggregateAllColorSchemes(),
                precise.showAggregateIntensityLayer(), precise.candidateRatingEnabled(),
                precise.parallelWayAwareness(), precise.allowUndownloadedAlignment(),
                precise.adjustJunctionNodes(), precise.simplifyEnabled(),
                precise.crossSectionHalfWidthPx(), precise.crossSectionStepPx(),
                precise.simplifyTolerancePx(), precise.inferenceMode(), precise.inferenceZoom(),
                precise.validationZoom(), precise.searchHalfWidthMeters(),
                precise.sampleStepMeters(), IntensitySamplingMode.DIRECT_LUMINANCE,
                precise.cacheBuster());

        assertEquals(AlignWayAction.ModernApplyPreflight.SOURCE_LINEAGE_UNAVAILABLE,
                AlignWayAction.modernApplyPreflight(captured,
                        new AlignmentConfig(directIntensity, GeometryCleanupConfig.disabled())));
        LiveBPreviewService.Captured wrongProjection = new LiveBPreviewService.Captured(raster,
                null, null, null, List.of(), List.of(), null, "hot", 1.0, 1.0,
                "settings", "parameters", GeometryCleanupConfig.disabled(),
                AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE, "EPSG:4326");
        assertEquals(AlignWayAction.ModernApplyPreflight.CONFIGURATION_UNSUPPORTED,
                AlignWayAction.modernApplyPreflight(wrongProjection,
                        new AlignmentConfig(precise, GeometryCleanupConfig.disabled())));
    }

    @Test
    void productionPreviewSummaryNamesAuthorityAndApplyState() {
        String summary = AlignWayAction.modernPreviewSummary("Corridor A", "visible layer",
                "REVIEW_REQUIRED", 2, List.of("AMBIGUOUS_BRANCH"), true,
                "review confirmed; Apply available");

        assertTrue(summary.contains("Engine: Corridor A"));
        assertTrue(summary.contains("Source: visible layer"));
        assertTrue(summary.contains("Disposition: REVIEW_REQUIRED"));
        assertTrue(summary.contains("Affected ways: 2"));
        assertTrue(summary.contains("Reasons: AMBIGUOUS_BRANCH"));
        assertTrue(summary.contains("Confirmation: confirmed"));
        assertTrue(summary.contains("Apply: review confirmed; Apply available"));
        assertFalse(summary.toLowerCase(java.util.Locale.ROOT).contains("experimental"));
    }

    @Test
    void typedPreflightStatusesHaveProductionUserMessages() {
        for (AlignWayAction.ModernApplyPreflight preflight
                : AlignWayAction.ModernApplyPreflight.values()) {
            String message = AlignWayAction.modernApplyPreflightMessage(preflight);

            assertFalse(message.isBlank());
            assertFalse(message.contains(preflight.name()), preflight.name());
            assertFalse(message.contains("_"), message);
        }
        assertTrue(AlignWayAction.modernApplyPreflightMessage(
                AlignWayAction.ModernApplyPreflight.CLEANUP_UNAVAILABLE_FOR_ENGINE)
                .contains("Probabilistic B"));
        assertTrue(AlignWayAction.modernApplyPreflightMessage(
                AlignWayAction.ModernApplyPreflight.SOURCE_LINEAGE_UNAVAILABLE)
                .contains("source lineage"));
    }

    @Test
    void persistedModernEnginesUseTheLivePipelineRatherThanLegacyProfileTracking() {
        assertTrue(AlignWayAction.requiresLiveModernPreview(TrackerMode.CORRIDOR_AWARE));
        assertTrue(AlignWayAction.requiresLiveModernPreview(TrackerMode.PROBABILISTIC));
        assertTrue(AlignWayAction.requiresLiveModernPreview(TrackerMode.HYBRID));
        assertTrue(AlignWayAction.requiresLiveModernPreview(TrackerMode.DIRECTIONAL_IMAGE));
        assertFalse(AlignWayAction.requiresLiveModernPreview(TrackerMode.LEGACY_V02));
        assertTrue(AlignWayAction.supportsManagedModernSource(TrackerMode.CORRIDOR_AWARE));
        assertTrue(AlignWayAction.supportsManagedModernSource(TrackerMode.PROBABILISTIC));
        assertFalse(AlignWayAction.supportsManagedModernSource(TrackerMode.HYBRID));
        assertFalse(AlignWayAction.supportsManagedModernSource(TrackerMode.DIRECTIONAL_IMAGE));
    }

    @Test
    void automaticRoutingUsesManagedOnlyWhenTheProductionCaptureSupportsIt() {
        assertOrdinaryRoute(TrackerMode.CORRIDOR_AWARE, AlignmentSourceMode.AUTOMATIC,
                AlignWayAction.OrdinaryPipeline.MODERN_MANAGED);
        assertOrdinaryRoute(TrackerMode.PROBABILISTIC, AlignmentSourceMode.AUTOMATIC,
                AlignWayAction.OrdinaryPipeline.MODERN_MANAGED);
        assertOrdinaryRoute(TrackerMode.HYBRID, AlignmentSourceMode.AUTOMATIC,
                AlignWayAction.OrdinaryPipeline.MODERN_VISIBLE);
        assertOrdinaryRoute(TrackerMode.DIRECTIONAL_IMAGE, AlignmentSourceMode.AUTOMATIC,
                AlignWayAction.OrdinaryPipeline.MODERN_VISIBLE);

        assertThrows(IllegalStateException.class, () -> ordinaryRoute(
                TrackerMode.HYBRID, AlignmentSourceMode.MANAGED_TILES));
        assertThrows(IllegalStateException.class, () -> ordinaryRoute(
                TrackerMode.DIRECTIONAL_IMAGE, AlignmentSourceMode.MANAGED_TILES));
    }

    @Test
    void legacyWithManagedCredentialsKeepsItsCompatibilitySourceResolver() {
        AlignWayAction.OrdinaryRoute route = ordinaryRoute(
                TrackerMode.LEGACY_V02, AlignmentSourceMode.AUTOMATIC);
        AtomicBoolean requiredVisibleUsed = new AtomicBoolean();
        AtomicBoolean optionalVisibleUsed = new AtomicBoolean();

        String selected = AlignWayAction.selectOrdinarySource(route, configuredCorridor(), () -> {
            requiredVisibleUsed.set(true);
            return "required-visible";
        }, () -> {
            optionalVisibleUsed.set(true);
            return null;
        });

        assertEquals(AlignWayAction.OrdinaryPipeline.LEGACY_COMPATIBILITY, route.pipeline());
        assertEquals(null, selected);
        assertFalse(requiredVisibleUsed.get());
        assertTrue(optionalVisibleUsed.get());
    }

    @Test
    void ordinaryPreviewFreshnessIncludesFrozenRecoveryAndJunctionPolicy() {
        ManagedHeatmapConfig heatmap = configuredCorridor();
        AlignmentConfig persisted = new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
        RecoverySettings recovery = new RecoverySettings(RecoverySettings.CURRENT_SCHEMA_VERSION,
                true, 7.01, 14.0, JunctionPolicy.REATTACH, true);
        TracingSettings captured = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                TrackerMode.CORRIDOR_AWARE, recovery, false, AlignmentSourceMode.VISIBLE_LAYER);
        TracingSettings changed = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                TrackerMode.CORRIDOR_AWARE,
                new RecoverySettings(RecoverySettings.CURRENT_SCHEMA_VERSION, true, 7.01, 14.0,
                        JunctionPolicy.LEGACY_BOUNDED_MOVE, false),
                false, AlignmentSourceMode.VISIBLE_LAYER);

        assertTrue(AlignWayAction.matchesLivePreviewSettings(persisted, persisted, persisted,
                captured, captured, null, null));
        assertFalse(AlignWayAction.matchesLivePreviewSettings(persisted, persisted, persisted,
                captured, changed, null, null));
    }

    @Test
    void explicitVisibleSourceRequiresALayerBeforePreviewUiCanOpen() {
        AtomicBoolean ordinaryResolverUsed = new AtomicBoolean();

        assertThrows(NullPointerException.class, () -> ordinaryAction(
                TrackerMode.CORRIDOR_AWARE, AlignmentSourceMode.VISIBLE_LAYER,
                () -> null, () -> {
                    ordinaryResolverUsed.set(true);
                    return "ordinary";
                }));
        assertFalse(ordinaryResolverUsed.get());
        assertEquals("visible", AlignWayAction.selectVisibleSource(true, () -> "visible",
                () -> "ordinary"));
        assertEquals("ordinary", AlignWayAction.selectVisibleSource(false, () -> "visible",
                () -> "ordinary"));
    }

    @Test
    void closedOrSupersededLivePreviewCannotPublishLate() {
        try (PreviewSessionController<String> session =
                new PreviewSessionController<>(Runnable::run)) {
            PreviewSessionController.Owner first = session.open(() -> { });
            assertTrue(session.isCurrent(first));
            Object window = new Object();
            assertTrue(session.isCurrentWindow(first, window, window, true));
            assertTrue(!session.isCurrentWindow(first, window, new Object(), true));
            assertTrue(!session.isCurrentWindow(first, window, window, false));
            session.close(first);
            assertTrue(!session.isCurrent(first));
            PreviewSessionController.Owner second = session.open(() -> { });
            assertTrue(!session.isCurrent(first));
            assertTrue(session.isCurrent(second));
            session.closeAll();
            assertTrue(!session.isCurrent(second));
        }
    }

    @Test
    void staleOwnerCloseCannotAuthorizeSharedPreviewCleanup() {
        try (PreviewSessionController<String> session =
                new PreviewSessionController<>(Runnable::run)) {
            PreviewSessionController.Owner first = session.open(() -> { });
            PreviewSessionController.Owner second = session.open(() -> { });

            assertFalse(session.close(first));
            assertTrue(session.isCurrent(second));
            assertTrue(session.close(second));
            assertFalse(session.isCurrent(second));
        }
    }

    @Test
    void ordinaryRetryIsCappedAtFourteenMeters() {
        assertEquals(14.0, AlignWayAction.ordinaryRetryMaximumMeters(7.01, 80.0), 0.0);
        assertEquals(14.0, AlignWayAction.ordinaryRetryMaximumMeters(10.0, 80.0), 0.0);
        assertEquals(14.0, AlignWayAction.ordinaryRetryMaximumMeters(14.0, 80.0), 0.0);
        assertEquals(20.0, AlignWayAction.ordinaryRetryMaximumMeters(20.0, 80.0), 0.0);
        assertEquals(14.0, AlignWayAction.defaultRetryWidthMeters(7.01, 14.0), 0.0);
    }

    @Test
    void initiallySelectsApplicableCleanedSiblingAheadOfInspectionOnlyRawCandidate() {
        CenterlineCandidate raw = candidate("hot/ridge-1");
        CenterlineCandidate cleaned = candidate("hot/ridge-1#cleaned");
        AlignmentResult result = result(List.of(raw, cleaned), List.of(cleaned));

        assertSame(cleaned, AlignWayAction.initialCandidate(result));
    }

    @Test
    void explicitlyRequestedCleanupPrefersChangedCleanedSiblingWithoutReorderingCandidates() {
        CenterlineCandidate raw = candidate("hot/ridge-1").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE, "hot/ridge-1"));
        CenterlineCandidate cleaned = candidate("hot/ridge-1#cleaned").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.CLEANED, "hot/ridge-1"));
        AlignmentResult result = result(List.of(raw, cleaned), List.of(raw, cleaned));

        assertSame(cleaned, AlignWayAction.initialCandidate(result));
        assertSame(raw, result.candidates().get(0));
    }

    @Test
    void explicitlyRequestedCleanupAlsoPrefersAChangedPartialSibling() {
        CenterlineCandidate raw = candidate("hot/ridge-1").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE, "hot/ridge-1"));
        CenterlineCandidate partial = candidate("hot/ridge-1#cleaned").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED, "hot/ridge-1"));

        assertSame(partial, AlignWayAction.initialCandidate(
            result(List.of(raw, partial), List.of(raw, partial))));
    }

    @Test
    void fallsBackToFirstInspectionCandidateOnlyWhenNoneAreApplicable() {
        CenterlineCandidate first = candidate("hot/ridge-1");
        CenterlineCandidate second = candidate("hot/ridge-2");

        assertSame(first, AlignWayAction.initialCandidate(result(List.of(first, second), List.of())));
    }


    @Test
    void initiallyPrefersReviewRequiredCandidateOverHardBlockedCandidate() {
        CenterlineCandidate blocked = candidate("hot/ridge-blocked");
        CenterlineCandidate reviewable = candidate("hot/ridge-review").withEvidence(
            new CandidateEvidence("hot", 4, 4, 0, 0, 3.2, 0.8, 0.2, 0.9, 0.5, 0.1, List.of())
                .withCorridorCoverage(coverage(true, false, 0, "unresolved-search-edge-censoring")));

        assertSame(reviewable, AlignWayAction.initialCandidate(
            result(List.of(blocked, reviewable), List.of())));
    }
    @Test
    void rejectsAnEmptyCandidateResult() {
        AlignmentResult result = result(List.of(), List.of());

        assertThrows(IllegalStateException.class, () -> AlignWayAction.initialCandidate(result));
    }

    @Test
    void cleanupDetailDescribesTheSelectedCandidateRatherThanTheCandidateList() {
        CenterlineCandidate raw = candidate("hot/ridge-1").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE, "hot/ridge-1"));
        CenterlineCandidate cleaned = candidate("hot/ridge-1#cleaned").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.CLEANED, "hot/ridge-1"));

        String rawDetail = AlignWayAction.cleanupDetail(raw);
        String cleanedDetail = AlignWayAction.cleanupDetail(cleaned);

        assertTrue(rawDetail.contains("cleaned alternative available"));
        assertTrue(rawDetail.contains("before 3"));
        assertTrue(cleanedDetail.contains("fully applied"));
        assertTrue(cleanedDetail.contains("after 2"));
        assertTrue(!rawDetail.equals(cleanedDetail));
    }
    @Test
    void cleanupStatusMakesPartialAndSkippedProcessingVisible() {
        CenterlineCandidate partial = candidate("hot/ridge-1#cleaned").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED, "hot/ridge-1"));
        CenterlineCandidate partialWithoutProtected = candidate("hot/ridge-3#cleaned").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED, "hot/ridge-3")
            .withIntervalSummary(2, 1, 0));
        CenterlineCandidate unchangedAroundProtected = candidate("hot/ridge-4").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.UNCHANGED, "hot/ridge-4")
            .withIntervalSummary(1, 0, 1));
        CenterlineCandidate skipped = candidate("hot/ridge-2").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.SKIPPED, "hot/ridge-2"));

        assertTrue(AlignWayAction.cleanupStatus(partial).contains("partially cleaned in 1 interval"));
        assertTrue(AlignWayAction.cleanupStatus(partial).contains("1 protected neighborhood"));
        assertTrue(AlignWayAction.cleanupStatus(partialWithoutProtected)
            .contains("other eligible geometry stayed unchanged for safety"));
        assertTrue(AlignWayAction.cleanupStatus(unchangedAroundProtected)
            .contains("1 protected neighborhood"));
        assertTrue(AlignWayAction.cleanupStatus(skipped).contains("skipped"));
        assertTrue(partial.displayName().contains("partially cleaned"));
    }


    @Test
    void offersWiderRetryForBridgedOrUnresolvedCorridorCoverageOnly() {
        CenterlineCandidate bridged = candidate("hot/ridge-1").withEvidence(CandidateEvidence.empty()
            .withCorridorCoverage(coverage(true, true, 1, "complete-with-search-edge-bridge")));
        CenterlineCandidate unresolved = candidate("hot/ridge-2").withEvidence(CandidateEvidence.empty()
            .withCorridorCoverage(coverage(true, false, 0, "unresolved-search-edge-censoring")));
        CenterlineCandidate complete = candidate("hot/ridge-3").withEvidence(CandidateEvidence.empty()
            .withCorridorCoverage(coverage(true, true, 0, "complete")));
        CenterlineCandidate genericBridge = candidate("hot/ridge-4").withEvidence(CandidateEvidence.empty()
            .withCorridorCoverage(coverage(true, true, 1, "complete")));

        assertTrue(AlignWayAction.canRetryWithWiderSearch(bridged));
        assertTrue(AlignWayAction.canRetryWithWiderSearch(unresolved));
        assertTrue(!AlignWayAction.canRetryWithWiderSearch(complete));
        assertTrue(!AlignWayAction.canRetryWithWiderSearch(genericBridge));
    }


    @Test
    void candidateCoverageMessagesExplainSearchEdgeStateWithoutRawEnums() {
        CenterlineCandidate bridged = candidate("hot/ridge-1").withEvidence(CandidateEvidence.empty()
            .withCorridorCoverage(coverage(true, true, 2, "complete-with-search-edge-bridge")));
        CenterlineCandidate unresolved = candidate("hot/ridge-2").withEvidence(CandidateEvidence.empty()
            .withCorridorCoverage(coverage(true, false, 0, "unresolved-search-edge-censoring")));
        CandidateAssessment applicable = new CandidateAssessment(
            CandidateAssessment.Disposition.APPLICABLE, List.of());
        CandidateAssessment review = new CandidateAssessment(
            CandidateAssessment.Disposition.REVIEW_REQUIRED,
            List.of(CandidateAssessment.Reason.INCOMPLETE_LONGITUDINAL_CORRIDOR));
        CandidateAssessment blocked = new CandidateAssessment(
            CandidateAssessment.Disposition.HARD_BLOCKED,
            List.of(CandidateAssessment.Reason.STRUCTURAL_SAFETY_FAILURE));

        assertTrue(AlignWayAction.candidateListLabel(bridged, applicable, false)
            .contains("search-edge gaps bridged"));
        assertTrue(AlignWayAction.candidateListLabel(unresolved, review, false)
            .contains("review required"));
        assertTrue(AlignWayAction.candidateListLabel(unresolved, review, true)
            .contains("review confirmed"));
        assertTrue(AlignWayAction.coverageStatus(bridged, 7.0, applicable, false)
            .contains("Search-edge gaps were interpolated"));
        assertTrue(AlignWayAction.coverageStatus(unresolved, 7.0, review, false).contains("7.0 m search boundary"));
        assertTrue(AlignWayAction.coverageStatus(unresolved, 7.0, review, false).contains("Review required"));
        assertTrue(AlignWayAction.canConfirmCandidate(review));
        assertTrue(!AlignWayAction.canConfirmCandidate(blocked));
    }

    private static ManagedHeatmapConfig configuredCorridor() {
        return new ManagedHeatmapConfig("stored-key", "stored-policy", "stored-signature", "stored-session",
            "all", "hot", ".", ".*", AlignmentMode.MOVE_EXISTING_NODES,
            TrackerMode.CORRIDOR_AWARE, false, false, false, false, false, false,
            false, false, false, false, 7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION,
            15, 15, 7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
    }

    private static AlignWayAction.OrdinaryRoute ordinaryRoute(TrackerMode engine,
            AlignmentSourceMode sourceMode) {
        RecoverySettings recovery = RecoverySettings.defaults(7.01);
        TracingSettings settings = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                engine, recovery, false, sourceMode);
        ManagedHeatmapConfig heatmap = configuredCorridor().withTrackerMode(engine);
        return AlignWayAction.resolveOrdinaryRoute(settings,
                new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled()));
    }

    private static void assertOrdinaryRoute(TrackerMode engine, AlignmentSourceMode sourceMode,
            AlignWayAction.OrdinaryPipeline expected) {
        assertEquals(expected, ordinaryRoute(engine, sourceMode).pipeline());
    }

    private static AlignWayAction.OrdinaryActionRouting<String> ordinaryAction(
            TrackerMode engine, AlignmentSourceMode sourceMode,
            java.util.function.Supplier<String> requiredVisible,
            java.util.function.Supplier<String> legacyVisible) {
        RecoverySettings recovery = RecoverySettings.defaults(7.01);
        TracingSettings settings = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                engine, recovery, false, sourceMode);
        ManagedHeatmapConfig heatmap = configuredCorridor().withTrackerMode(engine);
        return AlignWayAction.resolveOrdinaryAction(settings,
                new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled()),
                requiredVisible, legacyVisible);
    }

    private static CenterlineCandidate candidate(String id) {
        return new CenterlineCandidate(id, 0.8, List.of(), List.of());
    }

    private static CandidateGeometryCleanup cleanup(
        CandidateGeometryCleanup.Outcome outcome,
        String parentId
    ) {
        CandidateGeometryCleanup report = new CandidateGeometryCleanup(parentId, outcome, "test", List.of(),
            3, 3, outcome == CandidateGeometryCleanup.Outcome.CLEANED
                || outcome == CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED ? 2 : 3,
            0, 0, 0, 0, 0, 1.0, 1.0, 0.0,
            OptionalDouble.empty(), OptionalDouble.empty());
        return outcome == CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED
            ? report.withIntervalSummary(2, 1, 1) : report;
    }


    private static CorridorCoverage coverage(boolean measured, boolean complete, int bridges, String reason) {
        return new CorridorCoverage(measured, complete, 4, 4, 1.0, 0, 3,
            0.0, 0.0, 0, 0.0, bridges, false, reason);
    }
    private static AlignmentResult result(
        List<CenterlineCandidate> candidates,
        List<CenterlineCandidate> applicable
    ) {
        return new AlignmentResult(null, null, candidates, List.of(), List.of(), List.of(),
            null, null, List.of(), applicable);
    }
}

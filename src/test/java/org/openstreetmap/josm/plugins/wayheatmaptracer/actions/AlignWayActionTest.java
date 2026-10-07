package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import javax.swing.SwingUtilities;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JScrollPane;

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
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
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

    @Test
    void incompleteRelationCaptureReasonExportsTypedBlockedAttempt(@TempDir Path directory)
            throws Exception {
        String failure = "ManualJunctionCaptureException: INCOMPLETE_CLOSURE: "
                + "incomplete relation member. Adjust this junction manually, then run alignment again.";
        var reason = AlignWayAction.manualCaptureReason(failure);
        assertEquals(ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE, reason);
        AlignWayAction.recordModernUnavailable("blocked", "visible-layer",
                "incomplete-relation", reason);
        Path diagnostic = directory.resolve("incomplete-relation.zip");
        DiagnosticsRegistry.writeLatest(diagnostic.toFile());
        String status = new String(Format15ArchiveReader.read(diagnostic)
                .artifact("attempt-status.json").orElseThrow().bytes(), StandardCharsets.UTF_8);
        assertTrue(status.contains("\"status\":\"blocked\""));
        assertTrue(status.contains("\"manualJunctionReason\":\"INCOMPLETE_CLOSURE\""));
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
    void intervalApplyRequiresDirectManagedSourceUntilVisibleRevisionIsProved() {
        LiveBPreviewService.VisibleRaster visible = new LiveBPreviewService.VisibleRaster(
                12, 12, new int[144], 0.0, 0.0, 2.0, 2.0, 1.0, 1.0,
                OptionalDouble.empty(), "visible-test", "EPSG:3857");
        var direct = new ManagedModernPreviewSource.Raster(
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                new boolean[] {true, true, true, true},
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence
                    .SupportedInputRasterTransform.webMercator(15, 0.0, 0.0, 2.0),
                "hot", 15, "managed-test", new org.openstreetmap.josm.plugins.wayheatmaptracer
                    .tile.ManagedTileGeneration(0L));
        LiveBPreviewService.Captured visibleCaptured = new LiveBPreviewService.Captured(
                visible, null, null, null, List.of(), List.of(), null, "hot", 1.0, 1.0,
                "settings", "parameters", GeometryCleanupConfig.disabled(),
                AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE, "EPSG:3857");
        LiveBPreviewService.Captured managedCaptured = new LiveBPreviewService.Captured(
                null, direct, null, null, List.of(), List.of(), null, "hot", 1.0, 1.0,
                "settings", "parameters", GeometryCleanupConfig.disabled(),
                AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE, "EPSG:3857");

        assertFalse(AlignWayAction.intervalApplySourceAvailable(visibleCaptured));
        assertTrue(AlignWayAction.intervalApplySourceAvailable(managedCaptured));
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
    void intervalReceiptUsesOnlyNumericManagedLineageAndHash() {
        String privateIdentity = "https://tiles.invalid/x?Cookie=private-secret";
        var direct = new ManagedModernPreviewSource.Raster(
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                new boolean[] {true, true, true, true},
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence
                    .SupportedInputRasterTransform.webMercator(15, 0.0, 0.0, 2.0),
                "hot", 15, privateIdentity, new org.openstreetmap.josm.plugins.wayheatmaptracer
                    .tile.ManagedTileGeneration(41L));
        var captured = new LiveBPreviewService.Captured(null, direct, null, null,
                List.of(), List.of(), null, "hot", 1.0, 1.0, "settings", "parameters",
                GeometryCleanupConfig.disabled(), AlignmentMode.PRECISE_SHAPE,
                TrackerMode.CORRIDOR_AWARE, "EPSG:3857");

        var receipt = (Format15ProductionBundleFactory.ManagedTileSourceReceipt)
                AlignWayAction.intervalSourceReceipt(captured);

        assertEquals(41L, receipt.generation());
        assertEquals(15, receipt.zoom());
        assertEquals(64, receipt.sourceIdentityHash().length());
        assertFalse(receipt.sourceIdentityHash().contains(privateIdentity));
    }

    @Test
    void intervalAppliedArtifactPublishesOnlyAfterSuccessfulMutation(@TempDir Path directory)
            throws Exception {
        var prepared = new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay
                .format15.Format15Bundle("build", "a".repeat(64), "b".repeat(64),
                    java.util.Map.of("interval-production.json",
                        org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                            .Format15Artifact.text("interval-production.json",
                                "{\"status\":\"APPLIED_AFTER_REVIEW\",\"planIdentity\":\""
                                    + "c".repeat(64) + "\",\"appliedPlanIdentity\":\""
                                    + "c".repeat(64) + "\"}"),
                        "private/interval-composed-preview.json",
                        org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                            .Format15Artifact.text("private/interval-composed-preview.json", "{}"),
                        "private/interval-point-provenance.json",
                        org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                            .Format15Artifact.text("private/interval-point-provenance.json", "{}")));
        AlignWayAction.recordModernUnavailable("started", "managed-tiles", "current");
        assertThrows(IllegalStateException.class, () ->
            AlignWayAction.applyWithPreparedIntervalDiagnostics(() -> prepared,
                "c".repeat(64), () -> { throw new IllegalStateException("command failed"); },
                () -> true));
        Path failed = directory.resolve("failed.zip");
        DiagnosticsRegistry.writeLatest(failed.toFile());
        assertFalse(Format15ArchiveReader.read(failed).artifact("interval-production.json").isPresent());

        AtomicInteger staleApply = new AtomicInteger();
        assertThrows(IllegalStateException.class, () ->
            AlignWayAction.applyWithPreparedIntervalDiagnostics(() -> prepared,
                "c".repeat(64), staleApply::incrementAndGet, () -> false));
        assertEquals(0, staleApply.get());
        assertThrows(IllegalArgumentException.class, () ->
            AlignWayAction.applyWithPreparedIntervalDiagnostics(() -> prepared,
                "d".repeat(64), staleApply::incrementAndGet, () -> true));
        assertEquals(0, staleApply.get());

        AtomicInteger ownerChecks = new AtomicInteger();
        AlignWayAction.applyWithPreparedIntervalDiagnostics(() -> prepared,
            "c".repeat(64), staleApply::incrementAndGet,
            () -> ownerChecks.incrementAndGet() < 3);
        assertEquals(1, staleApply.get());
        Path superseded = directory.resolve("superseded.zip");
        DiagnosticsRegistry.writeLatest(superseded.toFile());
        assertFalse(Format15ArchiveReader.read(superseded)
                .artifact("interval-production.json").isPresent());

        AtomicInteger applied = new AtomicInteger();
        AlignWayAction.applyWithPreparedIntervalDiagnostics(() -> prepared,
            "c".repeat(64), applied::incrementAndGet, () -> true);
        assertEquals(1, applied.get());
        Path success = directory.resolve("applied.zip");
        DiagnosticsRegistry.writeLatest(success.toFile());
        assertTrue(Format15ArchiveReader.read(success).artifact("interval-production.json").isPresent());
    }

    @Test
    void intervalActionArtifactFollowsRouteSwitchAndNeverClaimsFullWayReplay(
            @TempDir Path directory) throws Exception {
        LiveBPreviewService.Computed computed = computedIntervalFixture();
        var state = new AlignWayAction.IntervalPreviewState(computed.intervalBatch());
        assertTrue(computed.intervalBatch().runs().get(0).routes().size() > 1);
        var first = AlignWayAction.createIntervalDiagnostics(computed, state,
                Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW);
        String firstPreview = new String(first.artifact("private/interval-composed-preview.json")
                .bytes(), StandardCharsets.UTF_8);
        state.choose(0, 1);
        var switched = AlignWayAction.createIntervalDiagnostics(computed, state,
                Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW);
        Path file = directory.resolve("switched.zip");
        org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                .Format15BundleWriter.write(switched, file);
        var decoded = Format15ArchiveReader.read(file);
        String index = new String(decoded.artifact("interval-production.json").orElseThrow()
                .bytes(), StandardCharsets.UTF_8);
        String displayed = new String(decoded.artifact("private/interval-composed-preview.json")
                .orElseThrow().bytes(), StandardCharsets.UTF_8);

        assertTrue(index.contains("\"chosenRouteIndex\":1"));
        assertTrue(index.contains("\"revision\":null,\"zoom\":null"));
        assertTrue(index.contains("\"SCALAR_INFERENCE\":false"));
        assertTrue(index.contains("\"FULL_EDIT_PLAN\":false"));
        assertEquals(new String(switched.artifact("private/interval-composed-preview.json")
                .bytes(), StandardCharsets.UTF_8), displayed);
        assertFalse(firstPreview.equals(displayed));
        assertFalse(switched.artifactNames().contains("frozen-input.bin"));
        assertTrue(switched.artifactNames().contains("numerical-policy.json"));
        assertFalse(switched.artifactNames().contains("frozen-edit-plan.bin"));
        assertFalse(index.contains("stored-signature"));

        String planHash = state.assessment().plan().orElseThrow().canonicalHash();
        assertThrows(IllegalStateException.class, () -> AlignWayAction.createIntervalDiagnostics(
                computed, state, Format15ProductionBundleFactory.IntervalArtifactStatus.CONFIRMED));
        state.confirmReview();
        var confirmed = AlignWayAction.createIntervalDiagnostics(computed, state,
                Format15ProductionBundleFactory.IntervalArtifactStatus.CONFIRMED);
        String confirmedIndex = new String(confirmed.artifact("interval-production.json")
                .bytes(), StandardCharsets.UTF_8);
        assertTrue(confirmedIndex.contains("\"reviewedPlanIdentity\":\"" + planHash + "\""));
        var applied = AlignWayAction.createIntervalDiagnostics(computed, state,
                Format15ProductionBundleFactory.IntervalArtifactStatus.APPLIED_AFTER_REVIEW);
        String appliedIndex = new String(applied.artifact("interval-production.json")
                .bytes(), StandardCharsets.UTF_8);
        assertTrue(appliedIndex.contains("\"appliedPlanIdentity\":\"" + planHash + "\""));
        assertTrue(appliedIndex.contains("\"previewSha256\":"));
        var cancelled = AlignWayAction.createIntervalDiagnostics(computed, state,
                Format15ProductionBundleFactory.IntervalArtifactStatus.CANCELLED);
        String cancelledIndex = new String(cancelled.artifact("interval-production.json")
                .bytes(), StandardCharsets.UTF_8);
        assertTrue(cancelledIndex.contains("\"appliedPlanIdentity\":null"));
    }

    @Test
    void intervalPreviewCanSwitchFrozenSourceOwnersAndClearsReview() throws Exception {
        LiveBPreviewService.Computed nativeRun = computedIntervalFixture();
        var nativeBatch = nativeRun.intervalBatch();
        var alternativeOptions = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                .ModernTracePipeline.Options(nativeBatch.options().fieldName(),
                        nativeBatch.options().cleanup(), "selected-mapping-hot-corridor", 2);
        var alternativeBatch = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                .IntervalTraceBatch(nativeBatch.fullRequest(), nativeBatch.evidence(),
                        nativeBatch.network(), nativeBatch.partition(), nativeBatch.runs(),
                        alternativeOptions, nativeBatch.authoritySpecification());
        var alternative = LiveBPreviewService.Computed.partitioned(nativeRun.captured(),
                alternativeBatch, Map.of());
        var choices = nativeRun.withSourceAttempts(List.of(new LiveBPreviewService.DetectorAttempt(
                "hot-corridor", "selected-mapping-hot-corridor",
                LiveBPreviewService.DetectorAttemptStatus.PRODUCED, alternative)));

        var state = new AlignWayAction.IntervalPreviewState(choices);
        assertEquals(2, state.sourceRuns().size());
        assertSame(nativeBatch, state.batch());
        state.confirmReview();
        assertTrue(state.review().confirmed());
        state.chooseSource(1);
        assertSame(alternativeBatch, state.batch());
        assertFalse(state.review().confirmed());
        assertEquals(Map.of(), state.routeChoices());
        var diagnostic = AlignWayAction.createIntervalDiagnostics(choices, state,
                Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW);
        assertTrue(diagnostic.artifactNames().contains("interval-production.json"));
        String sourceOwners = new String(diagnostic.artifact("source-choices.json").bytes(),
                StandardCharsets.UTF_8);
        assertTrue(sourceOwners.contains("\"selectedTier\":\"selected-mapping-hot-corridor\""));
        assertTrue(sourceOwners.contains("\"nativeTier\":\"selected-visible\""));
        assertTrue(sourceOwners.contains("\"evidenceHash\":\""
                + alternative.request().evidenceContentHash() + "\""));
    }

    @Test
    void optionalAggregateFailureHasSafeProminentWarningText() {
        var failure = new org.openstreetmap.josm.plugins.wayheatmaptracer.service
                .ManagedModernPreviewSource.AggregateFailure(15, "bluered",
                        org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus.AUTH_FAILURE);
        String warning = AlignWayAction.aggregateSourceWarning(failure);
        assertTrue(warning.contains("bluered"));
        assertTrue(warning.contains("15"));
        assertTrue(warning.contains("AUTH_FAILURE"));
        assertTrue(warning.contains("selected"));
    }

    private static LiveBPreviewService.Computed computedIntervalFixture() throws Exception {
        DataSet dataSet = new DataSet();
        double[] east = {-19, -14, -8, 0, 8, 14, 19};
        double[] north = {0, 0.2, 0.7, 1.0, 0.5, 0.1, 0};
        java.util.ArrayList<Node> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < east.length; i++) {
            Node node = loadedNode(9100 + i, north[i], east[i]);
            nodes.add(node);
            dataSet.addPrimitive(node);
        }
        Way way = new Way();
        way.setNodes(nodes);
        way.setOsmId(9200, 1);
        way.setModified(false);
        dataSet.addPrimitive(way);
        SelectionContext selection = new SelectionContext(way, 1, 5, nodes.subList(1, 6),
                Set.of(nodes.get(1), nodes.get(5)));
        int width = 600;
        int[] pixels = new int[width * width];
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) {
                double one = (y - 296.0) / 6.0;
                double intensity = 0.02 + 0.80 * Math.exp(-0.5 * one * one / 1.44);
                if (x < width / 2) {
                    double other = (y - 270.0) / 6.0;
                    intensity = Math.max(intensity,
                            0.02 + 0.78 * Math.exp(-0.5 * other * other / 1.44));
                }
                int gray = (int) Math.round(255.0 * intensity);
                pixels[y * width + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        var raster = new LiveBPreviewService.VisibleRaster(width, width, pixels,
                -50.0, -50.0, 50.0, 50.0, 1.0, 1.0, OptionalDouble.of(1.0),
                "visible-test", "EPSG:3857");
        var config = new AlignmentConfig(configuredCorridor()
                .withAlignmentMode(AlignmentMode.PRECISE_SHAPE), GeometryCleanupConfig.disabled());
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(dataSet,
                selection, raster, config, true));
        var selected = (org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay)
                captured[0].network().primitives().get(captured[0].specification().selectedWayKey());
        var keys = selected.nodeKeys();
        var reason = ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED;
        var endpoint1 = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .SelectedWayIntervalPartitioner.BoundaryConstraint(
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                        .SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT,
                    1, keys.get(1), false, false, reason);
        var fixed = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .SelectedWayIntervalPartitioner.BoundaryConstraint(
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                        .SelectedWayIntervalPartitioner.BoundaryKind.FIXED_ISLAND,
                    3, keys.get(3), false, false, reason);
        var endpoint5 = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .SelectedWayIntervalPartitioner.BoundaryConstraint(
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                        .SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT,
                    5, keys.get(5), false, false, reason);
        var island = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .SelectedWayIntervalPartitioner.FixedIsland(
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange(3, 3),
                    List.of(keys.get(3)), Set.of(keys.get(3)), List.of(reason), fixed, fixed,
                    Set.of(), Set.of());
        var left = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .SelectedWayIntervalPartitioner.SlideInterval(
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange(1, 2),
                    keys.subList(1, 3), endpoint1, fixed);
        var right = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .SelectedWayIntervalPartitioner.SlideInterval(
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange(4, 5),
                    keys.subList(4, 6), fixed, endpoint5);
        var partition = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .SelectedWayIntervalPartitioner.Partition(
                    captured[0].specification().selectedWayKey(),
                    captured[0].specification().selectedRange(), List.of(island), List.of(left, right),
                    List.of(), Set.of(), captured[0].network().primitives(),
                    captured[0].network().incomingReferrerWatches(),
                    captured[0].network().datasetIdentity(), captured[0].network().sourceGeneration());
        var batch = service.computePartitioned(captured[0], partition, CancellationProbe.NONE);
        return new LiveBPreviewService.Computed(captured[0], batch.evidence(),
                batch.fullRequest(), null, batch.options(), java.util.Map.of(), batch);
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
    void ordinaryBRouteFreezesSupportedSettingsWithoutChangingRequestedPreferences() {
        ManagedHeatmapConfig saved = configuredCorridor().withTrackerMode(TrackerMode.PROBABILISTIC);
        saved = new ManagedHeatmapConfig(saved.keyPairId(), saved.policy(), saved.signature(),
                saved.sessionToken(), saved.activity(), saved.color(), saved.manualLayerName(),
                saved.layerRegex(), AlignmentMode.PRECISE_SHAPE, saved.trackerMode(),
                saved.verbose(), saved.debug(), saved.multiColorDetection(),
                saved.aggregateAllColorSchemes(), saved.showAggregateIntensityLayer(),
                saved.candidateRatingEnabled(), saved.parallelWayAwareness(),
                saved.allowUndownloadedAlignment(), saved.adjustJunctionNodes(), true,
                saved.crossSectionHalfWidthPx(), saved.crossSectionStepPx(),
                saved.simplifyTolerancePx(), saved.inferenceMode(), saved.inferenceZoom(),
                saved.validationZoom(), saved.searchHalfWidthMeters(), saved.sampleStepMeters(),
                saved.intensitySamplingMode(), saved.cacheBuster());
        for (GeometryCleanupPreset preset : List.of(GeometryCleanupPreset.CONSERVATIVE,
                GeometryCleanupPreset.BALANCED, GeometryCleanupPreset.STRONG,
                GeometryCleanupPreset.CUSTOM)) {
            AlignmentConfig requested = new AlignmentConfig(saved, preset.apply());
            TracingSettings tracing = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                    TrackerMode.PROBABILISTIC, RecoverySettings.defaults(7.01), false,
                    AlignmentSourceMode.MANAGED_TILES);
            AlignWayAction.OrdinaryRoute route = AlignWayAction.resolveOrdinaryRoute(tracing,
                    requested);
            AlignmentConfig effective = route.invocation().config();
            assertFalse(effective.heatmap().simplifyEnabled());
            assertTrue(effective.cleanup().isDisabled());
            assertEquals(preset, effective.cleanup().preset());
            assertTrue(requested.heatmap().simplifyEnabled());
            assertEquals(preset, requested.cleanup().preset());
        }
        ManagedHeatmapConfig savedA = saved.withTrackerMode(TrackerMode.CORRIDOR_AWARE)
                .withAlignmentMode(AlignmentMode.MOVE_EXISTING_NODES);
        AlignmentConfig persistedA = new AlignmentConfig(savedA,
                GeometryCleanupPreset.BALANCED.apply());
        TracingSettings tracingA = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                TrackerMode.CORRIDOR_AWARE, RecoverySettings.defaults(7.01), false,
                AlignmentSourceMode.MANAGED_TILES);
        assertEquals(persistedA.cleanup(), AlignWayAction.resolveOrdinaryRoute(tracingA,
                persistedA).invocation().config().cleanup());
        AlignmentConfig oneShotB = new AlignmentConfig(
                AlignWayAction.effectiveConfig(savedA, null, TrackerMode.PROBABILISTIC),
                persistedA.cleanup()).forEffectiveModernAttempt();
        assertEquals(AlignmentMode.PRECISE_SHAPE, oneShotB.heatmap().alignmentMode());
        assertTrue(oneShotB.cleanup().isDisabled());
        assertFalse(oneShotB.heatmap().simplifyEnabled());
        assertTrue(AlignWayAction.matchesLivePreviewSettings(persistedA, oneShotB,
                persistedA, null, TrackerMode.PROBABILISTIC));
        ManagedHeatmapConfig savedMove = saved.withAlignmentMode(AlignmentMode.MOVE_EXISTING_NODES);
        AlignmentConfig oneShotPrecise = new AlignmentConfig(
                AlignWayAction.effectiveConfig(savedMove, AlignmentMode.PRECISE_SHAPE, null),
                GeometryCleanupPreset.BALANCED.apply()).forEffectiveModernAttempt();
        assertEquals(AlignmentMode.PRECISE_SHAPE, oneShotPrecise.heatmap().alignmentMode());
        assertTrue(oneShotPrecise.cleanup().isDisabled());
        assertTrue(savedMove.simplifyEnabled());
        org.openstreetmap.josm.spi.preferences.IPreferences previousPreferences = Config.getPref();
        try {
            Config.setPreferencesInstance(new MemoryPreferences());
            Config.getPref().putBoolean("wayheatmaptracer.simplifyEnabled", true);
            ManagedHeatmapConfig legacyFresh = PluginPreferences.load()
                    .withTrackerMode(TrackerMode.PROBABILISTIC);
            assertTrue(legacyFresh.simplifyEnabled());
            AlignmentConfig legacyEffective = AlignWayAction.resolveOrdinaryRoute(
                    new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                            TrackerMode.PROBABILISTIC, RecoverySettings.defaults(7.01), false,
                            AlignmentSourceMode.VISIBLE_LAYER),
                    new AlignmentConfig(legacyFresh, GeometryCleanupConfig.disabled()))
                    .invocation().config();
            assertFalse(legacyEffective.heatmap().simplifyEnabled());
            assertTrue(Config.getPref().getBoolean("wayheatmaptracer.simplifyEnabled", false));
            PluginPreferences.save(saved);
            PluginPreferences.saveGeometryCleanup(GeometryCleanupPreset.STRONG.apply());
            ManagedHeatmapConfig loadedHeatmap = PluginPreferences.load();
            GeometryCleanupConfig loadedCleanup = PluginPreferences.loadGeometryCleanup();
            AlignmentConfig loaded = new AlignmentConfig(loadedHeatmap, loadedCleanup);
            AlignWayAction.resolveOrdinaryRoute(new TracingSettings(
                    TracingSettings.CURRENT_SCHEMA_VERSION, TrackerMode.PROBABILISTIC,
                    RecoverySettings.defaults(7.01), false, AlignmentSourceMode.MANAGED_TILES),
                    loaded);
            assertEquals(loadedHeatmap, PluginPreferences.load());
            assertEquals(loadedCleanup, PluginPreferences.loadGeometryCleanup());
            assertTrue(Config.getPref().getBoolean("wayheatmaptracer.simplifyEnabled", false));
        } finally {
            Config.setPreferencesInstance(previousPreferences);
        }
    }

    @Test
    void normalizedBPreviewStillMatchesUnchangedSavedSettings() {
        ManagedHeatmapConfig saved = configuredCorridor().withTrackerMode(TrackerMode.PROBABILISTIC);
        AlignmentConfig persisted = new AlignmentConfig(saved,
                GeometryCleanupPreset.BALANCED.apply());
        AlignmentConfig effective = persisted.forEffectiveModernAttempt();
        assertTrue(AlignWayAction.modernSettingsNotice(persisted, effective)
                .contains("cleanup"));
        TracingSettings tracing = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                TrackerMode.PROBABILISTIC, RecoverySettings.defaults(7.01), false,
                AlignmentSourceMode.MANAGED_TILES);
        assertTrue(AlignWayAction.matchesLivePreviewSettings(persisted, effective, persisted,
                tracing, tracing, null, null));
        AlignmentConfig changed = new AlignmentConfig(saved,
                GeometryCleanupPreset.STRONG.apply());
        assertFalse(AlignWayAction.matchesLivePreviewSettings(persisted, effective, changed,
                tracing, tracing, null, null));
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
                true, true,
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
        var managed = new ManagedModernPreviewSource.Raster(
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                new boolean[] {true, true, true, true},
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence
                    .SupportedInputRasterTransform.webMercator(15, 0.0, 0.0, 2.0),
                "hot", 15, "managed-direct", new org.openstreetmap.josm.plugins.wayheatmaptracer
                    .tile.ManagedTileGeneration(0L));
        LiveBPreviewService.Captured directCaptured = new LiveBPreviewService.Captured(null,
                managed, null, null, List.of(), List.of(), null, "hot", 1.0, 1.0,
                "settings", "parameters", GeometryCleanupConfig.disabled(),
                AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE, "EPSG:3857",
                null, null, IntensitySamplingMode.DIRECT_LUMINANCE);
        assertEquals(AlignWayAction.ModernApplyPreflight.READY,
                AlignWayAction.modernApplyPreflight(directCaptured,
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
    void hundredsOfReasonsKeepButtonsReachable() {
        JTextArea summary = new JTextArea(String.join(", ", java.util.stream.IntStream.range(0, 250)
                .mapToObj(index -> "REASON_" + index + " × 1").toList()));
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        JButton apply = new JButton("Apply");
        JPanel footer = new JPanel();
        footer.add(apply);

        JPanel content = AlignWayAction.modernReviewContent(summary, footer);
        content.setSize(720, 380);
        content.doLayout();
        footer.setSize(content.getWidth(), footer.getPreferredSize().height);
        footer.doLayout();

        assertEquals(2, content.getComponentCount());
        assertTrue(content.getComponent(0) instanceof JScrollPane);
        assertTrue(apply.getY() + apply.getHeight() <= footer.getHeight());
        assertTrue(footer.getY() + footer.getHeight() <= content.getHeight());
    }

    @Test
    void reviewConfirmationEnablesOnlyAvailableSafePlan() {
        PreviewReviewState reviewRequired = PreviewReviewState.create("candidate", "plan",
                "permissions", "source", ValidationReport.Disposition.REVIEW_REQUIRED);

        assertFalse(AlignWayAction.modernApplyEnabled(false, reviewRequired, true, false));
        assertFalse(AlignWayAction.modernApplyEnabled(true, reviewRequired, false, false));
        assertTrue(AlignWayAction.modernApplyEnabled(true, reviewRequired.confirm(), false, false));
        assertFalse(AlignWayAction.modernApplyEnabled(true, reviewRequired.confirm(), false, true));
        assertFalse(AlignWayAction.modernApplyEnabled(false, reviewRequired.confirm(), false, false));
        assertFalse(reviewRequired.confirm().matches(reviewRequired.withCandidate("another-candidate")));
        assertFalse(reviewRequired.confirm().matches(PreviewReviewState.create("candidate",
                "changed-plan", "permissions", "source", ValidationReport.Disposition.REVIEW_REQUIRED)));
    }

    @Test
    void preciseShapeRecoveryIsOneShotAndPreservesSavedMode() {
        AlignmentConfig saved = new AlignmentConfig(configuredCorridor()
                .withAlignmentMode(AlignmentMode.MOVE_EXISTING_NODES), GeometryCleanupConfig.disabled());

        AlignmentConfig retry = AlignWayAction.oneShotPreciseConfig(saved);

        assertEquals(AlignmentMode.MOVE_EXISTING_NODES, saved.heatmap().alignmentMode());
        assertEquals(AlignmentMode.PRECISE_SHAPE, retry.heatmap().alignmentMode());
        assertEquals(saved, new AlignmentConfig(configuredCorridor()
                .withAlignmentMode(AlignmentMode.MOVE_EXISTING_NODES), GeometryCleanupConfig.disabled()));
    }

    @Test
    void noPlanSummaryDoesNotReportAnAffectedWayCount() {
        String summary = AlignWayAction.modernPreviewSummary("Corridor A", "visible layer",
                "REVIEW_REQUIRED", null, List.of("LOCAL_SHAPE_IMAGE_AMBIGUITY (REVIEW)"),
                false, "Apply unavailable: final plan is blocked");

        assertTrue(summary.contains("Affected ways: unavailable"));
        assertTrue(summary.contains("Confirmation: unavailable (no exact plan)"));
        assertTrue(summary.contains("Apply: Apply unavailable: final plan is blocked"));
        assertTrue(summary.contains("LOCAL_SHAPE_IMAGE_AMBIGUITY (REVIEW) × 1"));
    }

    @Test
    void planReasonDisplayGroupsRepeatedCodesInFirstOccurrenceOrder() {
        List<String> reasons = new java.util.ArrayList<>();
        for (int index = 0; index < 93; index++) {
            reasons.add("LOCAL_SHAPE_IMAGE_AMBIGUITY");
        }
        reasons.add("SEARCH_TRUNCATED");

        String summary = AlignWayAction.modernPreviewSummary("Corridor A", "managed tiles",
                "REVIEW_REQUIRED", 1, reasons, false, "Apply unavailable: review required");

        assertTrue(summary.contains("LOCAL_SHAPE_IMAGE_AMBIGUITY × 93"));
        assertTrue(summary.contains("SEARCH_TRUNCATED × 1"));
        assertTrue(summary.indexOf("LOCAL_SHAPE_IMAGE_AMBIGUITY")
                < summary.indexOf("SEARCH_TRUNCATED"));
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

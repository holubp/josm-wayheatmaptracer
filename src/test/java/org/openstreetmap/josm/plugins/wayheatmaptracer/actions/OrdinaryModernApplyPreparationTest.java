package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentSourceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoverySettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TracingSettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewSessionController;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ManagedSourceLockedApplyValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ManagedSourceReceipt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.VisibleSourceLockedApplyValidator;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Public synthetic ordinary action assembly through the real Apply listener's preparation seam. */
class OrdinaryModernApplyPreparationTest {
    @TempDir Path cacheDirectory;

    @BeforeAll static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @BeforeEach void clearUndo() { UndoRedoHandler.getInstance().clean(); }
    @AfterEach void leaveUndoClean() { UndoRedoHandler.getInstance().clean(); }

    @Test
    void ordinaryManagedBRetainsAndMovesItsCapturedInteriorThroughActualApply() throws Exception {
        Fixture fixture = fixture(false, true);
        List<String> original = state(fixture.dataSet());
        try (Attempt attempt = publish(fixture)) {
            var computed = attempt.computed;
            assertFalse(computed.partitioned());
            PrimitiveKey middle = key(fixture.middle());
            assertEquals(Set.of(middle), computed.captured().network().closure().movableExistingNodeKeys());
            assertEquals(Set.of(middle, computed.request().selectedWayKey()),
                    computed.captured().network().closure().editableExistingKeys());
            var route = computed.pipeline().routes().get(0);
            ExistingWayNodeOccurrence occurrence = route.pointIds().stream()
                    .filter(id -> id instanceof ExistingWayNodeOccurrence existing && existing.nodeKey().equals(middle))
                    .map(id -> (ExistingWayNodeOccurrence) id).findFirst().orElseThrow();
            assertNotEquals(computed.evidence().coordinateFrame().toMetric(
                    new GeographicPoint(fixture.middle().lat(), fixture.middle().lon())),
                    route.assignments().get(occurrence), "actual final candidate must move the retained interior");
            var assessment = new ModernSingleWayEditPlanAdapter().assess(computed, 0);
            if (!assessment.applyAvailable()) {
                IllegalStateException refusal = assertThrows(IllegalStateException.class,
                        () -> edt(() -> attempt.prepare(null)));
                assertEquals("Selected occurrence movement/protection authority is incomplete", refusal.getMessage());
            }
            assertTrue(assessment.applyAvailable(), assessment.detail());
            var plan = assessment.plan().orElseThrow();
            PreviewReviewState review = review(plan);
            var prepared = edt(() -> attempt.prepare(review));
            assertEquals(plan, prepared.plan());
            assertEquals(original, state(fixture.dataSet()), "capture/preview/review/preparation are mutation-free");
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
            List<Node> retainedOriginals = List.copyOf(fixture.way().getNodes());
            edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
            assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
            assertTrue(fixture.way().getNodes().containsAll(retainedOriginals));
            assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
            List<String> applied = state(fixture.dataSet());
            edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
            assertEquals(original, state(fixture.dataSet()));
            edt(() -> { UndoRedoHandler.getInstance().redo(); return null; });
            assertEquals(applied, state(fixture.dataSet()));
            assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
        }
    }

    @Test
    void incompatibleSavedBSettingsStillConfirmAndApplyBothGeometryModes() throws Exception {
        for (AlignmentMode mode : List.of(AlignmentMode.PRECISE_SHAPE,
                AlignmentMode.MOVE_EXISTING_NODES)) {
            Fixture fixture = fixture(false, true);
            List<Node> originalNodes = List.copyOf(fixture.way().getNodes());
            List<GeographicPoint> originalGeometry = geometry(fixture.way());
            AlignmentConfig requested = incompatibleBConfig(mode);
            try (Attempt attempt = publish(fixture, TrackerMode.PROBABILISTIC, requested)) {
                assertTrue(requested.heatmap().simplifyEnabled());
                assertFalse(requested.cleanup().isDisabled());
                assertTrue(attempt.computed.captured().cleanup().isDisabled());
                assertEquals(mode, attempt.computed.request().geometryMode());
                var assessment = new ModernSingleWayEditPlanAdapter().assess(attempt.computed, 0);
                assertTrue(assessment.applyAvailable(), mode + ": " + assessment.detail());
                AlignmentEditPlan plan = assessment.plan().orElseThrow();
                PreviewReviewState pending = PreviewReviewState.fromEditPlan("ordinary-route", plan);
                assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED, pending.disposition(),
                        mode + " must exercise the review boundary");
                PreviewReviewState confirmation = pending.confirm();
                assertTrue(confirmation.confirmed());
                var prepared = edt(() -> attempt.prepare(confirmation));
                edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
                assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
                assertNotEquals(originalGeometry, geometry(fixture.way()),
                        "the managed route must apply a genuine move");
                if (mode == AlignmentMode.MOVE_EXISTING_NODES) {
                    assertEquals(originalNodes, fixture.way().getNodes());
                }
                edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
                assertEquals(originalGeometry, geometry(fixture.way()));
            }
            UndoRedoHandler.getInstance().clean();
        }
    }

    @Test
    void preciseShapeRecoveryWithIncompatibleSavedBSettingsRunsOrdinaryAttemptAndApplies() throws Exception {
        Fixture fixture = fixture(false, false);
        AlignmentConfig saved = incompatibleBConfig(AlignmentMode.MOVE_EXISTING_NODES);
        List<GeographicPoint> originalGeometry = geometry(fixture.way());
        LiveBPreviewService.VisibleRaster rendered = offsetVisibleRaster();
        TracingSettings tracing = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                TrackerMode.PROBABILISTIC, RecoverySettings.defaults(7.01), false,
                AlignmentSourceMode.VISIBLE_LAYER);
        var initial = AlignWayAction.resolveOrdinaryAction(tracing, saved,
                () -> "visible", () -> "legacy");
        try (Attempt moveAttempt = publishVisible(fixture, initial, rendered)) {
            var moveAssessment = new ModernSingleWayEditPlanAdapter().assess(moveAttempt.computed, 0);
            assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.PRECISE_SHAPE_REQUIRED,
                    moveAssessment.availability(), moveAssessment.detail());
            assertTrue(moveAssessment.plan().isEmpty());
        }
        var rerun = AlignWayAction.resolvePreciseRerun(
                ModernSingleWayEditPlanAdapter.ApplyAvailability.PRECISE_SHAPE_REQUIRED,
                tracing, saved,
                () -> "visible", () -> "legacy");
        assertEquals(AlignmentMode.MOVE_EXISTING_NODES, saved.heatmap().alignmentMode());
        assertTrue(saved.heatmap().simplifyEnabled());
        assertFalse(saved.cleanup().isDisabled());
        assertEquals(AlignmentMode.PRECISE_SHAPE, rerun.requestedConfig().heatmap().alignmentMode());
        assertTrue(rerun.requestedConfig().heatmap().simplifyEnabled());
        assertFalse(rerun.requestedConfig().cleanup().isDisabled());
        assertEquals(AlignmentMode.PRECISE_SHAPE,
                rerun.route().invocation().config().heatmap().alignmentMode());
        assertFalse(rerun.route().invocation().config().heatmap().simplifyEnabled());
        assertTrue(rerun.route().invocation().config().cleanup().isDisabled());
        try (Attempt preciseAttempt = publishVisible(fixture, rerun, rendered)) {
            assertNotNull(preciseAttempt.computed.settingsResolutionJson());
            assertTrue(preciseAttempt.computed.settingsResolutionJson()
                    .contains("\"requestedCleanupMode\":\"CONSTRAINED_SMOOTH_AND_REDUCE\""));
            assertTrue(preciseAttempt.computed.settingsResolutionJson()
                    .contains("\"effectiveCleanupMode\":\"NONE\""));
            var assessment = new ModernSingleWayEditPlanAdapter().assess(preciseAttempt.computed, 0);
            assertTrue(assessment.applyAvailable(), assessment.detail());
            var plan = assessment.plan().orElseThrow();
            PreviewReviewState pending = PreviewReviewState.fromEditPlan("ordinary-route", plan);
            assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED, pending.disposition());
            var prepared = edt(() -> preciseAttempt.prepare(pending.confirm()));
            edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
            assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
            assertNotEquals(originalGeometry, geometry(fixture.way()));
            edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
            assertEquals(originalGeometry, geometry(fixture.way()));
        }
    }

    @Test
    void noChangeIsCurrentOnlyForTheExactCapturedNetworkAndCreatesNoHistory() throws Exception {
        Fixture fixture = fixture(false, false);
        try (Attempt attempt = publish(fixture, TrackerMode.PROBABILISTIC,
                incompatibleBConfig(AlignmentMode.MOVE_EXISTING_NODES))) {
            var assessment = new ModernSingleWayEditPlanAdapter().assess(attempt.computed, 0);
            assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE,
                    assessment.availability(), assessment.detail());
            assertTrue(assessment.plan().isEmpty());
            var current = edt(() -> NetworkSnapshotCapture.capture(fixture.dataSet(),
                    attempt.computed.captured().specification()));
            AlignWayAction.requireNoChangeCurrent(assessment, attempt.computed, current);
            var watch = assessment.noChangeWatch().orElseThrow();
            var wrongGeneration = new ModernSingleWayEditPlanAdapter.Assessment(
                    java.util.Optional.empty(),
                    ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE,
                    assessment.detail(), null, null,
                    java.util.Optional.of(new ModernSingleWayEditPlanAdapter.NoChangeWatch(
                            watch.selectedWayKey(), watch.selectedRange(), watch.snapshotId(),
                            watch.datasetIdentity(), watch.sourceGeneration() + 1,
                            watch.networkContentHash())));
            assertThrows(IllegalStateException.class,
                    () -> AlignWayAction.requireNoChangeCurrent(wrongGeneration,
                            attempt.computed, current));
            var regeneratedSource = new NetworkSnapshot(current.snapshotId(), current.role(),
                    current.datasetIdentity(), current.sourceGeneration() + 1,
                    current.closure(), current.primitives(), current.incomingReferrerWatches());
            assertThrows(IllegalStateException.class,
                    () -> AlignWayAction.requireNoChangeCurrent(assessment,
                            attempt.computed, regeneratedSource));
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
            edt(() -> { fixture.way().getNode(0).setCoor(new LatLon(0.00001, 0)); return null; });
            var changed = edt(() -> NetworkSnapshotCapture.capture(fixture.dataSet(),
                    attempt.computed.captured().specification()));
            assertThrows(IllegalStateException.class,
                    () -> AlignWayAction.requireNoChangeCurrent(assessment, attempt.computed, changed));
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        }
    }

    @Test
    void ordinarySubrangeAndTwoEndpointControlsKeepEveryOriginalCoordinateAndIdentity() throws Exception {
        for (boolean interior : List.of(true, false)) {
            Fixture fixture = fixture(true, interior);
            List<Node> originals = List.copyOf(fixture.way().getNodes());
            List<GeographicPoint> originalPoints = geometry(fixture.way());
            List<String> before = state(fixture.dataSet());
            try (Attempt attempt = publish(fixture)) {
                var assessment = new ModernSingleWayEditPlanAdapter().assess(attempt.computed, 0);
                assertTrue(assessment.applyAvailable(), assessment.detail());
                var prepared = edt(() -> attempt.prepare(review(assessment.plan().orElseThrow())));
                assertEquals(before, state(fixture.dataSet()));
                edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
                assertEquals(prepared.plan().finalPreviewWays().get(prepared.plan().selectedWayKey()), geometry(fixture.way()));
                assertEquals(originalPoints.get(0), geometry(fixture.way()).get(0));
                assertEquals(originalPoints.get(originalPoints.size() - 1),
                        geometry(fixture.way()).get(fixture.way().getNodesCount() - 1));
                assertTrue(fixture.way().getNodes().containsAll(originals));
                assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
                List<String> applied = state(fixture.dataSet());
                edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
                assertEquals(before, state(fixture.dataSet()));
                edt(() -> { UndoRedoHandler.getInstance().redo(); return null; });
                assertEquals(applied, state(fixture.dataSet()));
                UndoRedoHandler.getInstance().clean();
            }
        }
    }

    @Test
    void otherManagedModernEngineUsesTheSameOrdinaryInteriorApplyBoundary() throws Exception {
        Fixture fixture = fixture(false, true);
        try (Attempt attempt = publish(fixture, TrackerMode.CORRIDOR_AWARE)) {
            var assessment = new ModernSingleWayEditPlanAdapter().assess(attempt.computed, 0);
            assertTrue(assessment.applyAvailable(), assessment.detail());
            var prepared = edt(() -> attempt.prepare(review(assessment.plan().orElseThrow())));
            assertTrue(prepared.plan().before().closure().movableExistingNodeKeys().contains(key(fixture.middle())));
            edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
            assertEquals(prepared.plan().finalPreviewWays().get(prepared.plan().selectedWayKey()), geometry(fixture.way()));
        }
    }

    @Test
    void actualPreparationRejectsUnreviewedStaleWindowManagedOwnerAndNetworkWithoutMutation() throws Exception {
        Fixture fixture = fixture(false, true);
        try (Attempt attempt = publish(fixture)) {
            var plan = new ModernSingleWayEditPlanAdapter().adapt(attempt.computed, 0);
            PreviewReviewState unconfirmed = PreviewReviewState.fromEditPlan("ordinary-route", plan);
            PreviewReviewState current = review(plan);
            refuse(attempt, attempt.computed, null, "reviewed candidate plan is stale");
            assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED, unconfirmed.disposition(),
                    "the actual managed B route must deterministically exercise required confirmation");
            refuse(attempt, attempt.computed, unconfirmed, "reviewed candidate plan is stale");
            refuse(attempt, attempt.computed, current.withCandidate("different-route"), "reviewed candidate plan is stale");
            attempt.activeWindow.set(new Object());
            refuse(attempt, attempt.computed, current, "preview window no longer owns");
            attempt.activeWindow.set(attempt.window);
            attempt.source.updateActiveGeneration(new ManagedTileGeneration(1));
            refuse(attempt, attempt.computed, current, "captured managed source changed");
            attempt.source.updateActiveGeneration(new ManagedTileGeneration(0));
            attempt.currentSource.set(null);
            refuse(attempt, attempt.computed, current, "captured managed source changed");
            attempt.currentSource.set(attempt.source);
            edt(() -> { fixture.middle().setCoor(new LatLon(0.000001, fixture.middle().lon())); return null; });
            refuse(attempt, attempt.computed, current, "network snapshot is stale");
        }
    }

    @Test
    void hostileDetachedAuthorityCannotAuthorizeTaggedSharedOutsideOrExtraEditableNodes() throws Exception {
        Fixture fixture = fixture(true, true);
        try (Attempt attempt = publish(fixture)) {
            var base = attempt.computed.captured().network();
            PrimitiveKey middle = key(fixture.middle());
            DetachedNode original = (DetachedNode) base.primitives().get(middle);
            Map<PrimitiveKey, DetachedPrimitive> tagged = new LinkedHashMap<>(base.primitives());
            tagged.put(middle, new DetachedNode(middle, original.coordinate(), Map.of("highway", "traffic_signals"), false, false));
            assertAuthorityRefused(attempt, network(base, base.closure(), tagged, base.incomingReferrerWatches()));
            Map<PrimitiveKey, Set<PrimitiveKey>> shared = new LinkedHashMap<>(base.incomingReferrerWatches());
            shared.put(middle, Set.of(attempt.computed.request().selectedWayKey(),
                    PrimitiveKey.existing(PrimitiveKey.Type.WAY, 99)));
            assertAuthorityRefused(attempt, network(base, base.closure(), base.primitives(), shared));
            PrimitiveKey outside = key(fixture.way().firstNode());
            Set<PrimitiveKey> movable = new LinkedHashSet<>(base.closure().movableExistingNodeKeys());
            movable.add(outside);
            Set<PrimitiveKey> editable = new LinkedHashSet<>(base.closure().editableExistingKeys()); editable.add(outside);
            Set<PrimitiveKey> protectedKeys = new LinkedHashSet<>(base.closure().protectedExistingNodeKeys()); protectedKeys.remove(outside);
            assertAuthorityRefused(attempt, network(base, closure(base.closure(), editable, movable, protectedKeys),
                    base.primitives(), base.incomingReferrerWatches()));
            // Extra editable outside node grants no movement but still exceeds selected-way edit scope.
            assertAuthorityRefused(attempt, network(base, closure(base.closure(), editable,
                    base.closure().movableExistingNodeKeys(), base.closure().protectedExistingNodeKeys()),
                    base.primitives(), base.incomingReferrerWatches()));
        }
    }

    @Test
    void finalAssignmentsRetainFixedCoordinatesAndEveryOriginalInteriorOccurrence() throws Exception {
        Fixture fixture = fixture(false, true);
        try (Attempt attempt = publish(fixture)) {
            var route = attempt.computed.pipeline().routes().get(0);
            var ids = route.pointIds();
            int middle = java.util.stream.IntStream.range(0, ids.size()).filter(index ->
                    ids.get(index) instanceof ExistingWayNodeOccurrence existing
                            && existing.nodeKey().equals(key(fixture.middle()))).findFirst().orElseThrow();
            MetricPoint boundary = route.assignments().get(ids.get(0));
            var movedBoundary = changedRoute(route, ids, 0, new MetricPoint(boundary.xMeters(), boundary.yMeters() + 1));
            refuse(attempt, withRoute(attempt.computed, movedBoundary), null, "exceeds its movement authority");
            List<FinalRoutePointId> omitted = new ArrayList<>(ids); omitted.remove(middle);
            refuse(attempt, withRoute(attempt.computed, changedRoute(route, omitted, -1, null)), null,
                    "omits a protected or movable existing occurrence");
            List<FinalRoutePointId> reordered = new ArrayList<>(ids);
            java.util.Collections.swap(reordered, 0, middle);
            refuse(attempt, withRoute(attempt.computed, changedRoute(route, reordered, -1, null)), null,
                    "retain both selected boundaries");
            Map<FinalRoutePointId, MetricPoint> missing = new LinkedHashMap<>(route.assignments()); missing.remove(ids.get(middle));
            List<String> before = state(fixture.dataSet());
            assertThrows(IllegalArgumentException.class, () -> new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(),
                    ids, missing, route.sourceOwnership(), route.quality(), route.cleanupStatus(), route.geometryChanged()));
            List<FinalRoutePointId> duplicate = new ArrayList<>(ids); duplicate.set(1, duplicate.get(0));
            assertThrows(IllegalArgumentException.class, () -> new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(),
                    duplicate, route.assignments(), route.sourceOwnership(), route.quality(), route.cleanupStatus(), route.geometryChanged()));
            assertEquals(before, state(fixture.dataSet())); assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        }
    }

    @Test
    void taggedAndExplicitlyFixedInteriorAssignmentsRemainExact() throws Exception {
        for (boolean tagged : List.of(true, false)) {
            Fixture base = fixture(false, true);
            if (tagged) base.middle().put("highway", "traffic_signals");
            Fixture fixture = tagged ? base : new Fixture(base.dataSet(), base.way(),
                    new SelectionContext(base.way(), 0, 2, base.way().getNodes(),
                            Set.copyOf(base.way().getNodes())), base.middle());
            try (Attempt attempt = publish(fixture)) {
                assertTrue(attempt.computed.captured().network().closure().protectedExistingNodeKeys().contains(key(fixture.middle())));
                assertTrue(attempt.computed.captured().network().closure().movableExistingNodeKeys().isEmpty());
                var route = attempt.computed.pipeline().routes().get(0);
                int middle = java.util.stream.IntStream.range(0, route.pointIds().size()).filter(index ->
                        route.pointIds().get(index) instanceof ExistingWayNodeOccurrence existing
                                && existing.nodeKey().equals(key(fixture.middle()))).findFirst().orElseThrow();
                MetricPoint position = route.assignments().get(route.pointIds().get(middle));
                refuse(attempt, withRoute(attempt.computed, changedRoute(route, route.pointIds(), middle,
                        new MetricPoint(position.xMeters(), position.yMeters() + 1))), null,
                        "exceeds its movement authority");
                List<FinalRoutePointId> omitted = new ArrayList<>(route.pointIds()); omitted.remove(middle);
                refuse(attempt, withRoute(attempt.computed, changedRoute(route, omitted, -1, null)), null,
                        "omits a protected or movable existing occurrence");
            }
        }
    }

    @Test
    void fullPreviewCrossingTouchOverlapAndPrefixContinuationStillBlockActualPreparation() throws Exception {
        Fixture fixture = fixture(false, true);
        try (Attempt attempt = publish(fixture)) {
            var route = attempt.computed.pipeline().routes().get(0);
            int middle = java.util.stream.IntStream.range(0, route.pointIds().size()).filter(index ->
                    route.pointIds().get(index) instanceof ExistingWayNodeOccurrence existing
                            && existing.nodeKey().equals(key(fixture.middle()))).findFirst().orElseThrow();
            MetricPoint center = route.hypothesis().points().get(middle);
            for (String defect : List.of("CROSSING", "VERTEX_TOUCH", "COLLINEAR_OVERLAP")) {
                MetricPoint next = route.hypothesis().points().get(middle + 1);
                // A proper crossing is interior to both segments; crossing a
                // final route vertex is correctly classified as a vertex touch.
                MetricPoint crossing = new MetricPoint((center.xMeters() + next.xMeters()) / 2,
                        (center.yMeters() + next.yMeters()) / 2);
                MetricPoint first = defect.equals("CROSSING")
                        ? new MetricPoint(crossing.xMeters(), crossing.yMeters() - 0.4) : center;
                MetricPoint last = defect.equals("COLLINEAR_OVERLAP")
                        ? next : defect.equals("CROSSING")
                                ? new MetricPoint(crossing.xMeters(), crossing.yMeters() + 0.4)
                                : new MetricPoint(center.xMeters(), center.yMeters() + 0.4);
                var frame = attempt.computed.evidence().coordinateFrame();
                Node a = loadedNode(30, frame.toGeographic(first)), b = loadedNode(31, frame.toGeographic(last));
                Way context = new Way(); context.setNodes(List.of(a, b)); context.setOsmId(32, 1); context.setModified(false);
                edt(() -> { fixture.dataSet().addPrimitive(a); fixture.dataSet().addPrimitive(b); fixture.dataSet().addPrimitive(context); return null; });
                // Hostile detached output retains the real raster geometry and quality while
                // the actual current network proves a newly conflicting read-only context.
                NetworkSnapshot current = edt(() -> NetworkSnapshotCapture.capture(fixture.dataSet(),
                        attempt.computed.captured().specification()));
                var changed = withNetwork(attempt.computed, current);
                var assessment = new ModernSingleWayEditPlanAdapter().assess(changed, 0);
                assertFalse(assessment.applyAvailable());
                assertTrue(assessment.plan().orElseThrow().validation().findingCodes().contains("final-topology:" + defect),
                        assessment.detail());
                refuse(attempt, changed, null, "final preview is blocked");
                Set<PrimitiveKey> editable = new LinkedHashSet<>(current.closure().editableExistingKeys());
                editable.add(PrimitiveKey.existing(PrimitiveKey.Type.WAY, 32));
                assertAuthorityRefused(attempt, network(current, closure(current.closure(), editable,
                        current.closure().movableExistingNodeKeys(), current.closure().protectedExistingNodeKeys()),
                        current.primitives(), current.incomingReferrerWatches()));
                edt(() -> { fixture.dataSet().removePrimitive(context); fixture.dataSet().removePrimitive(a);
                    fixture.dataSet().removePrimitive(b); return null; });
            }
        }
        Fixture subrange = fixture(true, true);
        try (Attempt attempt = publish(subrange)) {
            var route = attempt.computed.pipeline().routes().get(0);
            assertTrue(route.pointIds().get(1) instanceof GeneratedCandidatePoint);
            MetricPoint boundary = route.hypothesis().points().get(0);
            var reversed = changedRoute(route, route.pointIds(), 1,
                    new MetricPoint(boundary.xMeters() - 4, boundary.yMeters() + 2));
            var changed = withRoute(attempt.computed, reversed);
            var assessment = new ModernSingleWayEditPlanAdapter().assess(changed, 0);
            assertFalse(assessment.applyAvailable());
            assertTrue(assessment.plan().orElseThrow().validation().findingCodes().contains("final-topology:CONTINUATION"));
            refuse(attempt, changed, null, "final preview is blocked");
        }
    }

    private void refuse(Attempt attempt, LiveBPreviewService.Computed computed, PreviewReviewState review,
            String reason) throws Exception {
        List<String> before = state(attempt.fixture.dataSet());
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> edt(() -> attempt.prepare(computed, review)));
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
        assertEquals(before, state(attempt.fixture.dataSet()));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
    }

    private static void assertAuthorityRefused(Attempt attempt, NetworkSnapshot network) {
        List<String> before = state(attempt.fixture.dataSet());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(withNetwork(attempt.computed, network), 0));
        assertEquals("Selected occurrence movement/protection authority is incomplete", failure.getMessage());
        assertEquals(before, state(attempt.fixture.dataSet()));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
    }

    private static ClosureDescriptor closure(ClosureDescriptor base, Set<PrimitiveKey> editable,
            Set<PrimitiveKey> movable, Set<PrimitiveKey> protectedKeys) {
        return new ClosureDescriptor(base.scope(), base.queryVersion(), base.primitiveKeys(), editable,
                movable, protectedKeys, base.removableExistingNodeKeys(), base.editableWayOccurrences(),
                base.externalPorts(), base.collisionEnvelope(), base.editRegion(), base.mayCreateNodes(),
                base.wayReferrersComplete(), base.relationReferrersComplete(), base.nearbyGeometryComplete());
    }

    private static NetworkSnapshot network(NetworkSnapshot base, ClosureDescriptor closure,
            Map<PrimitiveKey, DetachedPrimitive> primitives, Map<PrimitiveKey, Set<PrimitiveKey>> watches) {
        return new NetworkSnapshot(base.snapshotId(), base.role(), base.datasetIdentity(), base.sourceGeneration(), closure, primitives, watches);
    }

    private static LiveBPreviewService.Computed withNetwork(LiveBPreviewService.Computed base, NetworkSnapshot network) {
        var old = base.captured();
        var captured = new LiveBPreviewService.Captured(old.raster(), old.managedRaster(), old.specification(), network,
                old.sourceGeographic(), old.sourceMetric(), old.outputGrid(), old.palette(), old.searchRadiusMeters(),
                old.sampleStepMeters(), old.settingsHash(), old.parameterHash(), old.cleanup(), old.geometryMode(),
                old.engine(), old.projectionCode(), old.junctionDecision(), old.intervalPartition());
        TraceRequest r = base.request();
        var request = new TraceRequest(r.selectedWayKey(), r.selectedRange(), r.engine(), r.geometryMode(), r.permissions(),
                r.budgets(), r.evidenceSnapshotId(), r.evidenceContentHash(), network.snapshotId(), network.canonicalHash(),
                r.settingsHash(), r.parameterHash(), r.samplerId(), r.configuredSampleStepMeters(), r.profileChainage(),
                r.evidenceResolution(), r.corridorInput());
        return new LiveBPreviewService.Computed(captured, base.evidence(), request, base.pipeline(), base.options(), base.counters());
    }

    private static LiveBPreviewService.Computed withRoute(LiveBPreviewService.Computed base, ModernTracePipeline.Route route) {
        return new LiveBPreviewService.Computed(base.captured(), base.evidence(), base.request(),
                new ModernTracePipeline.Result(base.pipeline().inference(), List.of(route)), base.options(), base.counters());
    }

    private static ModernTracePipeline.Route changedRoute(ModernTracePipeline.Route route,
            List<FinalRoutePointId> ids, int movedIndex, MetricPoint movedPoint) {
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>();
        var support = new ArrayList<org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership>();
        List<MetricPoint> points = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            MetricPoint point = index == movedIndex ? movedPoint : route.assignments().get(ids.get(index));
            points.add(point); assignments.put(ids.get(index), point); support.add(route.sourceOwnership().get(ids.get(index)));
        }
        var old = route.hypothesis();
        var hypothesis = new TraceHypothesis(old.id(), old.branchSignature(), points, support, old.objective(),
                old.posteriorProbability(), old.diagnostics());
        Map<FinalRoutePointId, org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership> ownership = new LinkedHashMap<>();
        for (FinalRoutePointId id : ids) ownership.put(id, route.sourceOwnership().get(id));
        return new ModernTracePipeline.Route(route.rawHypothesis(), hypothesis, ids, assignments, ownership,
                route.quality(), route.cleanupStatus(), true);
    }

    private static PreviewReviewState review(AlignmentEditPlan plan) {
        PreviewReviewState review = PreviewReviewState.fromEditPlan("ordinary-route", plan);
        return review.disposition() == ValidationReport.Disposition.REVIEW_REQUIRED ? review.confirm() : review;
    }

    private Attempt publish(Fixture fixture) throws Exception {
        return publish(fixture, TrackerMode.PROBABILISTIC);
    }

    private Attempt publish(Fixture fixture, TrackerMode engine) throws Exception {
        return publish(fixture, engine, config(engine));
    }

    private Attempt publish(Fixture fixture, TrackerMode engine, AlignmentConfig config) throws Exception {
        var routing = AlignWayAction.resolveOrdinaryAction(new TracingSettings(
                TracingSettings.CURRENT_SCHEMA_VERSION, engine,
                RecoverySettings.defaults(7.01), false, AlignmentSourceMode.MANAGED_TILES),
                config, () -> "visible", () -> "legacy");
        return publish(fixture, routing);
    }

    private Attempt publish(Fixture fixture, AlignWayAction.OrdinaryActionRouting<String> routing)
            throws Exception {
        PreviewSessionController<LiveBPreviewService.Computed> session =
                new PreviewSessionController<>(SwingUtilities::invokeLater);
        PreviewSessionController.Owner owner = session.open(() -> { });
        AtomicReference<LiveBPreviewService.Computed> result = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        edt(() -> {
            new AlignWayAction.OrdinaryModernAttemptAssembly().start(session, owner, routing,
                    fixture.dataSet(), fixture.selection(), "managed-selected-hot-g0",
                    (source, invocation, permissions) -> { throw new AssertionError("managed routing must not capture visible"); },
                    (seed, invocation, cancellation) -> raster(seed.sourceIdentity()), attempt -> {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        result.set(attempt.result()); published.countDown();
                    });
            return null;
        });
        assertTrue(published.await(30, TimeUnit.SECONDS), session.currentAttempt().toString());
        assertNotNull(result.get());
        TileDecoderClassifier classifier = new TileDecoderClassifier();
        TileFetchCoordinator source = new TileFetchCoordinator((request, credentials) -> {
            throw new AssertionError("receipt validation must not acquire tiles");
        }, new ManagedTileCache(cacheDirectory, classifier), classifier, TileReliabilityPolicy.defaults());
        source.updateActiveGeneration(new ManagedTileGeneration(0L));
        return new Attempt(fixture, result.get(), session, owner, source,
                routing.route().invocation().config(), null);
    }

    private Attempt publishVisible(Fixture fixture,
            AlignWayAction.OrdinaryActionRouting<String> routing,
            LiveBPreviewService.VisibleRaster raster) throws Exception {
        PreviewSessionController<LiveBPreviewService.Computed> session =
                new PreviewSessionController<>(SwingUtilities::invokeLater);
        PreviewSessionController.Owner owner = session.open(() -> { });
        AtomicReference<LiveBPreviewService.Computed> result = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        edt(() -> {
            new AlignWayAction.OrdinaryModernAttemptAssembly().start(session, owner, routing,
                    fixture.dataSet(), fixture.selection(), raster.sourceIdentity(),
                    (source, invocation, permissions) -> raster,
                    (seed, invocation, cancellation) -> {
                        throw new AssertionError("visible rerun must not acquire managed tiles");
                    }, attempt -> {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        result.set(attempt.result()); published.countDown();
                    });
            return null;
        });
        assertTrue(published.await(30, TimeUnit.SECONDS), session.currentAttempt().toString());
        TileDecoderClassifier classifier = new TileDecoderClassifier();
        TileFetchCoordinator source = new TileFetchCoordinator((request, credentials) -> {
            throw new AssertionError("visible rerun must not acquire managed tiles");
        }, new ManagedTileCache(cacheDirectory, classifier), classifier, TileReliabilityPolicy.defaults());
        return new Attempt(fixture, result.get(), session, owner, source,
                routing.route().invocation().config(), raster);
    }

    private static final class Attempt implements AutoCloseable {
        final Fixture fixture;
        final LiveBPreviewService.Computed computed;
        final PreviewSessionController<LiveBPreviewService.Computed> session;
        final PreviewSessionController.Owner owner;
        final TileFetchCoordinator source;
        final AtomicReference<TileFetchCoordinator> currentSource;
        final Object window = new Object();
        final AtomicReference<Object> activeWindow = new AtomicReference<>(window);
        final LiveBPreviewService service = new LiveBPreviewService();
        final ManagedSourceReceipt receipt;
        final LiveBPreviewService.VisibleRaster visibleRaster;

        Attempt(Fixture fixture, LiveBPreviewService.Computed computed,
                PreviewSessionController<LiveBPreviewService.Computed> session,
                PreviewSessionController.Owner owner, TileFetchCoordinator source,
                AlignmentConfig config, LiveBPreviewService.VisibleRaster visibleRaster) {
            this.fixture = fixture; this.computed = computed; this.session = session; this.owner = owner;
            this.source = source; currentSource = new AtomicReference<>(source);
            this.visibleRaster = visibleRaster;
            receipt = visibleRaster == null
                    ? new ManagedSourceReceipt(source, computed.captured(), config.heatmap(),
                            currentSource::get, () -> config.heatmap(),
                            () -> ProjectionRegistry.getProjection().toCode()) : null;
        }

        AlignWayAction.PreparedModernApply prepare(PreviewReviewState review) {
            return prepare(computed, review);
        }

        AlignWayAction.PreparedModernApply prepare(LiveBPreviewService.Computed selected, PreviewReviewState review) {
            return AlignWayAction.prepareModernApply(fixture.dataSet(), selected, 0, "ordinary-route", review,
                    () -> {
                        if (!session.isCurrentWindow(owner, window, activeWindow.get(), true)) {
                            throw new IllegalStateException("The preview window no longer owns this attempt");
                        }
                        if (visibleRaster == null) {
                            receipt.requireCurrent();
                            service.requireCurrent(fixture.dataSet(), selected.captured());
                        } else {
                            service.requireCurrent(fixture.dataSet(), selected.captured(), visibleRaster);
                        }
                    }, visibleRaster == null ? source::activeGenerationValue
                            : () -> selected.captured().network().sourceGeneration(),
                    (network, plan) -> visibleRaster == null
                            ? new ManagedSourceLockedApplyValidator(network, service, selected.captured(),
                                    receipt::requireCurrent, failure -> { throw new AssertionError(failure); })
                            : new VisibleSourceLockedApplyValidator(network, service, selected.captured(),
                                    () -> visibleRaster, null, () -> { },
                                    failure -> { throw new AssertionError(failure); }));
        }

        @Override public void close() { session.close(); source.close(); }
    }

    private static ManagedModernPreviewSource.Raster raster(String identity) {
        int size = 256;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            int gray = (int) Math.round(255 * (0.02 + 0.80 * Math.exp(-0.5
                    * (y - 127.0) * (y - 127.0) / 1.44)));
            for (int x = 0; x < size; x++) image.setRGB(x, y,
                    0xff000000 | gray << 16 | gray << 8 | gray);
        }
        boolean[] valid = new boolean[size * size]; Arrays.fill(valid, true);
        double equator = Math.scalb(256.0, 15) / 2.0;
        return new ManagedModernPreviewSource.Raster(image, valid,
                SupportedInputRasterTransform.webMercator(15, equator - size / 4.0,
                        equator - size / 4.0, 2),
                "hot", 15, identity, new ManagedTileGeneration(0L));
    }

    private static LiveBPreviewService.VisibleRaster offsetVisibleRaster() {
        int width = 600;
        int height = 600;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            double distanceMeters = (y - 288.0) / RenderedHeatmapSampler.RASTER_SCALE;
            double intensity = 0.02 + 0.80 * Math.exp(-0.5
                    * distanceMeters * distanceMeters / (1.2 * 1.2));
            int gray = (int) Math.round(255.0 * intensity);
            int pixel = 0xff000000 | gray << 16 | gray << 8 | gray;
            Arrays.fill(argb, y * width, (y + 1) * width, pixel);
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb,
                -50.0, -50.0, 50.0, 50.0, 1.0, 1.0,
                java.util.OptionalDouble.of(1.0), "visible-test", "EPSG:3857");
    }

    private static AlignmentConfig config(TrackerMode engine) {
        return new AlignmentConfig(new ManagedHeatmapConfig("key", "policy", "signature", "session", "all", "hot", "", ".*",
                AlignmentMode.PRECISE_SHAPE, engine, false, false,
                false, false, false, false, false, false, false, false,
                7, 4, 3, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15, 7.01, 1.56,
                IntensitySamplingMode.COLOR_MAPPING, 0L), GeometryCleanupConfig.disabled());
    }

    private static AlignmentConfig incompatibleBConfig(AlignmentMode mode) {
        ManagedHeatmapConfig base = config(TrackerMode.PROBABILISTIC).heatmap();
        return new AlignmentConfig(new ManagedHeatmapConfig(base.keyPairId(), base.policy(),
                base.signature(), base.sessionToken(), base.activity(), base.color(),
                base.manualLayerName(), base.layerRegex(), mode, base.trackerMode(),
                base.verbose(), base.debug(), base.multiColorDetection(),
                base.aggregateAllColorSchemes(), base.showAggregateIntensityLayer(),
                base.candidateRatingEnabled(), base.parallelWayAwareness(),
                base.allowUndownloadedAlignment(), base.adjustJunctionNodes(), true,
                base.crossSectionHalfWidthPx(), base.crossSectionStepPx(),
                base.simplifyTolerancePx(), base.inferenceMode(), base.inferenceZoom(),
                base.validationZoom(), base.searchHalfWidthMeters(), base.sampleStepMeters(),
                base.intensitySamplingMode(), base.cacheBuster()),
                GeometryCleanupPreset.BALANCED.apply());
    }

    private static Fixture fixture(boolean subrange, boolean interior) {
        DataSet dataSet = new DataSet();
        List<Node> nodes = new ArrayList<>();
        if (subrange) nodes.add(node(4, -32));
        nodes.add(node(1, -16));
        Node middle = interior ? node(2, 0) : null;
        if (interior) nodes.add(middle);
        nodes.add(node(3, 16));
        if (subrange) nodes.add(node(5, 32));
        nodes.forEach(dataSet::addPrimitive);
        Way way = new Way(); way.setNodes(nodes); way.setOsmId(10, 1); way.setModified(false); dataSet.addPrimitive(way);
        int first = subrange ? 1 : 0, last = nodes.size() - 1 - (subrange ? 1 : 0);
        return new Fixture(dataSet, way, new SelectionContext(way, first, last,
                nodes.subList(first, last + 1), Set.of(nodes.get(first), nodes.get(last))), middle);
    }

    private static Node node(long id, double metres) {
        Node node = new Node(new LatLon(0, Math.toDegrees(metres / 6_378_137.0)));
        node.setOsmId(id, 1); node.setModified(false); return node;
    }

    private static Node loadedNode(long id, GeographicPoint point) {
        Node node = new Node(new LatLon(point.latitudeDegrees(), point.longitudeDegrees()));
        node.setOsmId(id, 1); node.setModified(false); return node;
    }

    private static PrimitiveKey key(Node node) { return PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId()); }
    private static List<GeographicPoint> geometry(Way way) {
        return way.getNodes().stream().map(n -> new GeographicPoint(n.lat(), n.lon())).toList();
    }
    private static List<String> state(DataSet ds) {
        return ds.allPrimitives().stream().map(p -> p.getPrimitiveId() + "|" + p.getKeys() + "|" + p.isModified()
                + "|" + p.isDeleted() + "|" + (p instanceof Node n ? n.getCoor()
                : p instanceof Way w ? w.getNodes().stream().map(Node::getUniqueId).toList() : "relation"))
                .sorted().toList();
    }
    private static <T> T edt(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(action.get()); } catch (Throwable thrown) { failure.set(thrown); } });
        if (failure.get() instanceof RuntimeException runtime) throw runtime;
        if (failure.get() instanceof Error error) throw error;
        return result.get();
    }
    private record Fixture(DataSet dataSet, Way way, SelectionContext selection, Node middle) { }
}

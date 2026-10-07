package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentSourceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoverySettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TracingSettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewSessionController;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TransportResponse;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ApplyAlignmentEditPlanCommand;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ManagedSourceLockedApplyValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ManagedSourceReceipt;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** The action's ordinary managed assembly, reviewed plan and JOSM host transaction boundary. */
class V022OrdinaryManagedBWorkflowTest {
    @TempDir Path cacheDirectory;

    @BeforeAll static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @BeforeEach void clearHistory() { UndoRedoHandler.getInstance().clean(); }
    @AfterEach void leaveHistoryClean() { UndoRedoHandler.getInstance().clean(); }

    @Test void preciseFullSingle() throws Exception { runChangedCase(AlignmentMode.PRECISE_SHAPE, false, false); }
    @Test void precisePartialSingle() throws Exception { runChangedCase(AlignmentMode.PRECISE_SHAPE, true, false); }
    @Test void moveFullSingle() throws Exception { runChangedCase(AlignmentMode.MOVE_EXISTING_NODES, false, false); }
    @Test void movePartialSingle() throws Exception { runChangedCase(AlignmentMode.MOVE_EXISTING_NODES, true, false); }
    @Test void preciseFullComposed() throws Exception { runChangedCase(AlignmentMode.PRECISE_SHAPE, false, true); }
    @Test void precisePartialComposed() throws Exception { runChangedCase(AlignmentMode.PRECISE_SHAPE, true, true); }
    @Test void moveFullComposed() throws Exception { runChangedCase(AlignmentMode.MOVE_EXISTING_NODES, false, true); }
    @Test void movePartialComposed() throws Exception { runChangedCase(AlignmentMode.MOVE_EXISTING_NODES, true, true); }

    @Test void cancelledOrdinaryManagedInferenceCannotPublishOrTouchHostState() throws Exception {
        Fixture fixture = fixture(false, false);
        List<String> before = state(fixture.dataSet());
        ManagedHeatmapConfig settings = settings(AlignmentMode.PRECISE_SHAPE, false);
        var routing = AlignWayAction.resolveOrdinaryAction(new TracingSettings(
                TracingSettings.CURRENT_SCHEMA_VERSION, TrackerMode.PROBABILISTIC,
                RecoverySettings.defaults(7.01), false, AlignmentSourceMode.MANAGED_TILES),
                new AlignmentConfig(settings, GeometryCleanupConfig.disabled()),
                () -> "visible", () -> "legacy");
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) ->
                new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                        curvedTile(request.address().zoom(), request.address().x(),
                                request.address().y(), false),
                        null, Duration.ZERO, ""),
                new ManagedTileCache(cacheDirectory.resolve("cancel-tiles"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(0L));
            var session = new PreviewSessionController<LiveBPreviewService.Computed>(
                    SwingUtilities::invokeLater);
            var owner = session.open(() -> { });
            CountDownLatch acquired = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch workerExitedAcquisition = new CountDownLatch(1);
            AtomicInteger publications = new AtomicInteger();
            try {
                edt(() -> {
                    new AlignWayAction.OrdinaryModernAttemptAssembly().startWithSources(
                            session, owner, routing, fixture.dataSet(), fixture.selection(),
                            "managed-selected-hot-g0", (source, invocation, permissions) -> {
                                throw new AssertionError("managed ordinary route entered visible capture");
                            }, (seed, invocation, context) -> {
                                try {
                                    var sources = new ManagedModernPreviewSource(coordinator)
                                            .acquireSources(seed.sourceGeographic(),
                                                    invocation.config().heatmap(),
                                                    seed.sourceIdentity(),
                                                    CredentialSnapshot.fromConfig(null), context);
                                    acquired.countDown();
                                    release.await();
                                    context.checkpoint();
                                    return sources;
                                } finally {
                                    workerExitedAcquisition.countDown();
                                }
                            }, coordinator, attempt -> publications.incrementAndGet());
                    return null;
                });
                assertTrue(acquired.await(30, TimeUnit.SECONDS));
                assertEquals(AlignmentJob.State.INFERRING, session.currentAttempt().state());
                assertTrue(edt(() -> session.close(owner)));
                assertEquals(AlignmentJob.State.CANCELLED, session.currentAttempt().state());
                release.countDown();
                assertTrue(workerExitedAcquisition.await(30, TimeUnit.SECONDS));
                edt(() -> null);
                assertEquals(0, publications.get());
                assertEquals(before, state(fixture.dataSet()));
                assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
            } finally {
                release.countDown();
                session.close();
            }
        }
    }

    @Test void confirmedProtectedMemberAndTagEditsInvalidateTheOrdinaryPreview() throws Exception {
        for (String edit : List.of("relation-member", "protected-tag")) {
            UndoRedoHandler.getInstance().clean();
            Fixture fixture = fixture(false, true);
            ManagedHeatmapConfig settings = settings(AlignmentMode.PRECISE_SHAPE, true);
            var routing = AlignWayAction.resolveOrdinaryAction(new TracingSettings(
                    TracingSettings.CURRENT_SCHEMA_VERSION, TrackerMode.PROBABILISTIC,
                    RecoverySettings.defaults(10.0), false, AlignmentSourceMode.MANAGED_TILES),
                    new AlignmentConfig(settings, GeometryCleanupConfig.disabled()),
                    () -> "visible", () -> "legacy");
            TileDecoderClassifier decoder = new TileDecoderClassifier();
            try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) ->
                    new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                            curvedTile(request.address().zoom(), request.address().x(),
                                    request.address().y(), true),
                            null, Duration.ZERO, ""),
                    new ManagedTileCache(cacheDirectory.resolve(edit), decoder), decoder,
                    TileReliabilityPolicy.defaults())) {
                coordinator.updateActiveGeneration(new ManagedTileGeneration(0L));
                LiveBPreviewService.Computed computed = publish(fixture, routing, coordinator);
                var preview = new AlignWayAction.IntervalPreviewState(computed);
                assertTrue(preview.assessment().applyAvailable());
                preview.confirmReview();
                assertTrue(preview.applyAvailable());
                edt(() -> {
                    if ("relation-member".equals(edit)) {
                        Relation relation = fixture.dataSet().getRelations().iterator().next();
                        relation.setMembers(List.of(new RelationMember("other-role",
                                fixture.selected().getNode(8))));
                    } else {
                        fixture.selected().getNode(8).put("note", "changed after confirmation");
                    }
                    return null;
                });
                List<String> edited = state(fixture.dataSet());
                assertThrows(IllegalStateException.class, () -> edt(() -> {
                    new LiveBPreviewService().requireCurrent(fixture.dataSet(), computed.captured());
                    return null;
                }));
                assertEquals(edited, state(fixture.dataSet()), edit);
                assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
            }
        }
    }

    @Test void confirmedManagedSourceGenerationEditRefusesBeforeHistory() throws Exception {
        Fixture fixture = fixture(false, false);
        ManagedHeatmapConfig settings = settings(AlignmentMode.PRECISE_SHAPE, false);
        var routing = AlignWayAction.resolveOrdinaryAction(new TracingSettings(
                TracingSettings.CURRENT_SCHEMA_VERSION, TrackerMode.PROBABILISTIC,
                RecoverySettings.defaults(7.01), false, AlignmentSourceMode.MANAGED_TILES),
                new AlignmentConfig(settings, GeometryCleanupConfig.disabled()),
                () -> "visible", () -> "legacy");
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) ->
                new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                        curvedTile(request.address().zoom(), request.address().x(),
                                request.address().y(), false),
                        null, Duration.ZERO, ""),
                new ManagedTileCache(cacheDirectory.resolve("source-edit"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(0L));
            LiveBPreviewService.Computed computed = publish(fixture, routing, coordinator);
            var assessed = new ModernSingleWayEditPlanAdapter().assess(computed, 0);
            assertTrue(assessed.applyAvailable());
            var plan = assessed.plan().orElseThrow();
            PreviewReviewState review = PreviewReviewState.fromEditPlan("source-edit", plan);
            if (review.disposition() == ValidationReport.Disposition.REVIEW_REQUIRED) review = review.confirm();
            assertTrue(review.canApply());
            List<String> before = state(fixture.dataSet());
            ManagedSourceReceipt receipt = new ManagedSourceReceipt(coordinator, computed.captured(),
                    settings, "selected-visible", () -> coordinator, () -> settings,
                    computed.captured()::projectionCode);
            receipt.requireCurrent();
            coordinator.updateActiveGeneration(new ManagedTileGeneration(1L));
            assertThrows(IllegalStateException.class, receipt::requireCurrent);
            assertEquals(before, state(fixture.dataSet()));
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        }
    }

    private void runChangedCase(AlignmentMode mode, boolean partial, boolean composed) throws Exception {
        Fixture fixture = fixture(partial, composed);
        List<String> original = state(fixture.dataSet());
        List<Long> originalNodeIds = nodeIds(fixture.selected());
        List<LatLon> originalCoordinates = coordinates(fixture.selected());
        Map<Long, String> frozenContext = fixture.dataSet().allPrimitives().stream()
                .filter(primitive -> primitive instanceof Way && primitive != fixture.selected()
                        || primitive instanceof Relation)
                .collect(java.util.stream.Collectors.toMap(OsmPrimitive::getUniqueId,
                        V022OrdinaryManagedBWorkflowTest::primitiveState));
        Map<Long, LatLon> protectedCoordinates = fixture.protectedNodes().stream()
                .collect(java.util.stream.Collectors.toMap(Node::getUniqueId, Node::getCoor));
        ManagedHeatmapConfig settings = settings(mode, composed);
        AlignmentConfig config = new AlignmentConfig(settings, GeometryCleanupConfig.disabled());
        TracingSettings tracing = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                TrackerMode.PROBABILISTIC, RecoverySettings.defaults(settings.searchHalfWidthMeters()),
                false, AlignmentSourceMode.MANAGED_TILES);
        var routing = AlignWayAction.resolveOrdinaryAction(tracing, config,
                () -> "visible", () -> "legacy");
        assertEquals(AlignWayAction.OrdinaryPipeline.MODERN_MANAGED, routing.route().pipeline());
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) ->
                new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                        curvedTile(request.address().zoom(), request.address().x(),
                                request.address().y(), composed),
                        null, Duration.ZERO, ""),
                new ManagedTileCache(cacheDirectory.resolve("tiles"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(0L));
            LiveBPreviewService.Computed computed = publish(fixture, routing, coordinator);
            assertNotNull(computed.captured().sourceOwner());
            assertEquals(Set.of("hot"), computed.captured().sourceRasters().palettes().keySet());

            AlignmentEditPlan plan;
            ApplyAlignmentEditPlanCommand command;
            if (composed) {
                var preview = new AlignWayAction.IntervalPreviewState(computed);
                assertEquals(2, preview.batch().partition().fixedIslands().size(),
                        preview.batch().partition().toString());
                assertEquals(3, preview.batch().runs().size());
                String candidateDetails = preview.batch().runs().stream().map(run ->
                        run.interval().traceRange() + "=" + run.routes().stream()
                                .map(route -> route.hypothesis().id()
                                        + ":" + route.quality().disposition()
                                        + ":direct=" + route.quality().directlySupportedLengthMeters()
                                        + "/" + route.quality().totalLengthMeters()
                                        + ":worst=" + route.quality().worstUnsupportedSpanMeters()
                                        + ":image=" + route.quality().meanImageCenterCost()
                                        + ":owners=" + route.sourceOwnership().values().stream()
                                                .collect(java.util.stream.Collectors.groupingBy(
                                                        value -> value,
                                                        java.util.stream.Collectors.counting()))
                                        + ":" + route.quality().findings()).toList()).toList().toString();
                assertTrue(preview.assessment().plan().isPresent(),
                        preview.assessment().intervals() + "; candidates=" + candidateDetails);
                assertTrue(preview.assessment().intervals().stream().allMatch(interval ->
                                interval.disposition() == FixedIntervalEditPlanComposer.Disposition.CHANGED),
                        "each safe interval must really move: " + preview.assessment().intervals()
                                + "; candidates=" + candidateDetails);
                preview.confirmReview();
                assertTrue(preview.applyAvailable());
                plan = preview.currentPlanForApply();
                ManagedSourceReceipt receipt = new ManagedSourceReceipt(coordinator, computed.captured(),
                        settings, "selected-visible", () -> coordinator, () -> settings,
                        computed.captured()::projectionCode);
                receipt.requireCurrent();
                var bound = edt(() -> NetworkSnapshotCapture.captureBound(fixture.dataSet(),
                        computed.captured().specification()));
                var network = new LiveNetworkSnapshotValidator(bound, plan,
                        coordinator::activeGenerationValue);
                command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), plan,
                        new ManagedSourceLockedApplyValidator(network, new LiveBPreviewService(),
                                computed.captured(), receipt::requireCurrent,
                                failure -> { throw new AssertionError(failure); }),
                        "Apply ordinary managed composed alignment");
            } else {
                var assessment = new ModernSingleWayEditPlanAdapter().assess(computed, 0);
                assertTrue(assessment.applyAvailable(), assessment.detail());
                plan = assessment.plan().orElseThrow();
                PreviewReviewState review = PreviewReviewState.fromEditPlan("ordinary-native", plan);
                if (review.disposition() == ValidationReport.Disposition.REVIEW_REQUIRED) {
                    review = review.confirm();
                }
                PreviewReviewState confirmed = review;
                ManagedSourceReceipt receipt = new ManagedSourceReceipt(coordinator, computed.captured(),
                        settings, "selected-visible", () -> coordinator, () -> settings,
                        computed.captured()::projectionCode);
                var prepared = edt(() -> AlignWayAction.prepareModernApply(fixture.dataSet(),
                        computed, 0, "ordinary-native", confirmed, () -> {
                            receipt.requireCurrent();
                            new LiveBPreviewService().requireCurrent(fixture.dataSet(),
                                    computed.captured());
                        }, coordinator::activeGenerationValue,
                        (network, currentPlan) -> new ManagedSourceLockedApplyValidator(network,
                                new LiveBPreviewService(), computed.captured(), receipt::requireCurrent,
                                failure -> { throw new AssertionError(failure); })));
                command = prepared.command();
                assertEquals(plan.canonicalHash(), prepared.plan().canonicalHash());
            }

            assertNotEquals(originalCoordinates, plan.finalPreviewWays().get(plan.selectedWayKey()),
                    "fixture must require a real geometry change");
            assertEquals(original, state(fixture.dataSet()), "preview cannot mutate the dataset");
            edt(() -> { UndoRedoHandler.getInstance().add(command); return null; });
            assertPreview(plan, fixture.dataSet());
            List<String> applied = state(fixture.dataSet());
            assertNotEquals(original, applied);
            if (mode == AlignmentMode.MOVE_EXISTING_NODES) {
                assertEquals(originalNodeIds, nodeIds(fixture.selected()), "Move keeps every occurrence");
            }
            if (partial) {
                assertEquals(originalCoordinates.get(0), fixture.selected().getNode(0).getCoor());
                assertEquals(originalCoordinates.get(originalCoordinates.size() - 1),
                        fixture.selected().getNode(fixture.selected().getNodesCount() - 1).getCoor());
            }
            for (var entry : protectedCoordinates.entrySet()) {
                Node protectedNode = (Node) fixture.dataSet().getPrimitiveById(entry.getKey(),
                        OsmPrimitiveType.NODE);
                assertEquals(entry.getValue(), protectedNode.getCoor(), "protected node " + entry.getKey());
                assertTrue(fixture.selected().getNodes().contains(protectedNode),
                        "protected occurrence identity " + entry.getKey());
            }
            frozenContext.forEach((id, expected) -> assertEquals(expected,
                    primitiveState(fixture.dataSet().allPrimitives().stream()
                            .filter(primitive -> primitive.getUniqueId() == id).findFirst().orElseThrow()),
                    "frozen context " + id));
            for (int cycle = 0; cycle < 20; cycle++) {
                edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
                assertEquals(original, state(fixture.dataSet()), "Undo cycle " + cycle);
                edt(() -> { UndoRedoHandler.getInstance().redo(); return null; });
                assertEquals(applied, state(fixture.dataSet()), "Redo cycle " + cycle);
                assertPreview(plan, fixture.dataSet());
            }
        }
    }

    private static LiveBPreviewService.Computed publish(Fixture fixture,
            AlignWayAction.OrdinaryActionRouting<String> routing,
            TileFetchCoordinator coordinator) throws Exception {
        var session = new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater);
        var owner = session.open(() -> { });
        AtomicReference<LiveBPreviewService.Computed> result = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        try {
            edt(() -> {
                new AlignWayAction.OrdinaryModernAttemptAssembly().startWithSources(session, owner,
                        routing, fixture.dataSet(), fixture.selection(), "managed-selected-hot-g0",
                        (source, invocation, permissions) -> {
                            throw new AssertionError("managed route entered visible capture");
                        }, (seed, invocation, cancellation) -> new ManagedModernPreviewSource(coordinator)
                                .acquireSources(seed.sourceGeographic(), invocation.config().heatmap(),
                                        seed.sourceIdentity(), CredentialSnapshot.fromConfig(null),
                                        cancellation), coordinator,
                        attempt -> { result.set(attempt.result()); ready.countDown(); });
                return null;
            });
            assertTrue(ready.await(90, TimeUnit.SECONDS), session.currentAttempt().toString());
            assertNotNull(result.get());
            return result.get();
        } finally {
            session.close();
        }
    }

    private static void assertPreview(AlignmentEditPlan plan, DataSet dataSet) {
        for (var entry : plan.finalPreviewWays().entrySet()) {
            Way way = (Way) dataSet.getPrimitiveById(entry.getKey().id(), OsmPrimitiveType.WAY);
            assertNotNull(way, entry.getKey().toString());
            assertEquals(entry.getValue().stream().map(point ->
                    new LatLon(point.latitudeDegrees(), point.longitudeDegrees())).toList(),
                    coordinates(way), entry.getKey().toString());
        }
    }

    private static ManagedHeatmapConfig settings(AlignmentMode mode, boolean composed) {
        return new ManagedHeatmapConfig("key", "policy", "signature", "session", "all", "hot",
                "", ".*", mode, TrackerMode.PROBABILISTIC, false, false,
                false, false, false, false, false, false, false, false,
                7, 4, 3, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15,
                composed ? 10.0 : 7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
    }

    private static Fixture fixture(boolean partial, boolean composed) {
        DataSet dataSet = new DataSet();
        int coreCount = composed ? 31 : 11;
        int interval = composed ? 10 : 8;
        List<Node> nodes = new ArrayList<>();
        for (int index = partial ? -1 : 0; index <= coreCount - 1 + (partial ? 1 : 0); index++) {
            double east = (index - (coreCount - 1) / 2.0) * interval;
            Node node = loadedNode(7000 + index + 1, east, 0);
            nodes.add(node);
            dataSet.addPrimitive(node);
        }
        int shift = partial ? 1 : 0;
        Way selected = new Way();
        selected.setNodes(nodes);
        selected.setOsmId(8000, 1);
        selected.setModified(false);
        dataSet.addPrimitive(selected);
        List<Node> protectedNodes = new ArrayList<>();
        protectedNodes.add(nodes.get(shift));
        protectedNodes.add(nodes.get(shift + coreCount - 1));
        if (composed) {
            for (int index : List.of(4, 12, 18, 26)) {
                Node tagged = nodes.get(shift + index);
                tagged.put("note", "manual junction boundary");
                protectedNodes.add(tagged);
            }
            for (int index : List.of(8, 22)) {
                Node junction = nodes.get(shift + index);
                protectedNodes.add(junction);
                double east = (index - 15) * 10.0;
                Node south = loadedNode(9000 + index, east, -40);
                Node north = loadedNode(9100 + index, east, 40);
                dataSet.addPrimitive(south);
                dataSet.addPrimitive(north);
                Way incident = new Way();
                incident.setNodes(List.of(south, junction, north));
                incident.setOsmId(9200 + index, 1);
                incident.setModified(false);
                dataSet.addPrimitive(incident);
            }
            Relation relation = new Relation();
            relation.setMembers(List.of(new RelationMember("junction", nodes.get(shift + 8))));
            relation.put("type", "route");
            relation.setOsmId(9300, 1);
            relation.setModified(false);
            dataSet.addPrimitive(relation);
        }
        int first = shift;
        int last = shift + coreCount - 1;
        return new Fixture(dataSet, selected, new SelectionContext(selected, first, last,
                nodes.subList(first, last + 1), Set.of(nodes.get(first), nodes.get(last))),
                List.copyOf(protectedNodes));
    }

    private static Node loadedNode(long id, double eastMeters, double northMeters) {
        Node node = new Node(new LatLon(Math.toDegrees(northMeters / 6_378_137.0),
                Math.toDegrees(eastMeters / 6_378_137.0)));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static byte[] curvedTile(int zoom, int tileX, int tileY, boolean composed) {
        try {
            BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
            double pitch = 2.0 * Math.PI * 6_378_137.0 / Math.scalb(512.0, zoom);
            double equator = Math.scalb(512.0, zoom - 1);
            for (int row = 0; row < 512; row++) {
                for (int column = 0; column < 512; column++) {
                    double east = (tileX * 512.0 + column - equator) * pitch;
                    double bend = composed ? composedBend(east)
                            : 4.0 * Math.max(0.0, Math.cos(Math.PI * east / 64.0));
                    double distance = tileY * 512.0 + row - (equator - 0.5 - bend / pitch);
                    double strength = Math.exp(-0.5 * distance * distance / 1.44);
                    int background = 3 + (column * 7 + row * 3) % 29;
                    int gray = (int) Math.round(background + strength * (253 - background));
                    image.setRGB(column, row, 0xff000000 | gray << 16 | gray << 8 | gray);
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Three supported interior bends return exactly to each immutable interval boundary. */
    private static double composedBend(double east) {
        for (double[] interval : new double[][] {
                {-150.0, -110.0}, {-30.0, 30.0}, {110.0, 150.0}}) {
            if (east >= interval[0] && east <= interval[1]) {
                double phase = (east - interval[0]) / (interval[1] - interval[0]);
                return 4.0 * Math.pow(Math.sin(Math.PI * phase), 2);
            }
        }
        return 0.0;
    }

    private static List<Long> nodeIds(Way way) {
        return way.getNodes().stream().map(Node::getUniqueId).toList();
    }

    private static List<LatLon> coordinates(Way way) {
        return way.getNodes().stream().map(Node::getCoor).toList();
    }

    private static List<String> state(DataSet dataSet) {
        return dataSet.allPrimitives().stream().map(V022OrdinaryManagedBWorkflowTest::primitiveState)
                .sorted().toList();
    }

    private static String primitiveState(OsmPrimitive primitive) {
        return primitive.getPrimitiveId() + "|" + primitive.getKeys() + "|"
                + primitive.isModified() + "|" + primitive.isDeleted() + "|"
                + primitiveSpecificState(primitive);
    }

    private static Object primitiveSpecificState(OsmPrimitive primitive) {
        if (primitive instanceof Node node) return node.getCoor();
        if (primitive instanceof Way way) return nodeIds(way);
        if (primitive instanceof Relation relation) return relation.getMembers().stream()
                .map(member -> member.getRole() + ":" + member.getMember().getPrimitiveId()).toList();
        throw new AssertionError(primitive.getClass());
    }

    private static <T> T edt(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { result.set(action.get()); } catch (Throwable thrown) { failure.set(thrown); }
        });
        if (failure.get() instanceof RuntimeException runtime) throw runtime;
        if (failure.get() instanceof Error error) throw error;
        assertFalse(failure.get() != null, String.valueOf(failure.get()));
        return result.get();
    }

    private record Fixture(DataSet dataSet, Way selected, SelectionContext selection,
            List<Node> protectedNodes) { }
}

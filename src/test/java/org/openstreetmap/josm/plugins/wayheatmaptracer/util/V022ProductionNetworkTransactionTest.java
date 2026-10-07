package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.event.DataChangedEvent;
import org.openstreetmap.josm.data.osm.event.DataSetListenerAdapter;
import org.openstreetmap.josm.data.osm.event.NodeMovedEvent;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedHeatmapLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.ManualJunctionEligibility;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.IBaseDirectories;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Host transaction checks using plans generated by live captured network paths. */
class V022ProductionNetworkTransactionTest {
    @TempDir Path temporary;
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @BeforeEach
    void clearHostHistory() {
        UndoRedoHandler.getInstance().clean();
    }

    @AfterEach
    void leaveHostHistoryClean() {
        UndoRedoHandler.getInstance().clean();
    }

    @Test
    void everyMutationPhaseRollsBackRealSimpleTPlan() throws Exception {
        Fixture fixture = fixture();
        V022AtomicApplyTest.LiveState before =
                V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        for (ApplyAlignmentEditPlanCommand.MutationPoint point
                : ApplyAlignmentEditPlanCommand.MutationPoint.values()) {
            ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
                    fixture.dataSet(), fixture.plan(), fixture.validator(),
                    "Inject real network failure", reached -> {
                        if (reached == point) {
                            throw new InjectedFailure(point);
                        }
                    });
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> V022AtomicApplyTest.onEdt(() ->
                            UndoRedoHandler.getInstance().add(command)), point.name());
            assertTrue(failure.getCause() instanceof InjectedFailure, point.name());
            before.assertMatches(fixture.dataSet());
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
            assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
        }
    }

    @Test
    void listenerFailureAfterValidatedApplyRetainsCommittedEditAndHostHistory() throws Exception {
        Fixture fixture = fixture();
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        AtomicBoolean armed = new AtomicBoolean(true);
        AtomicBoolean fired = new AtomicBoolean();
        var listener = new DataSetListenerAdapter(event -> {
            if ((event instanceof NodeMovedEvent || event instanceof DataChangedEvent)
                    && armed.compareAndSet(true, false)) {
                fired.set(true);
                throw new ListenerFailure();
            }
        });
        fixture.dataSet().addDataSetListener(listener);
        try {
            List<ApplyAlignmentEditPlanCommand.NotificationOperation> warnings =
                    new CopyOnWriteArrayList<>();
            var command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), fixture.plan(),
                    fixture.validator(), "Host listener failure on Apply", point -> { }, warnings::add);
            V022AtomicApplyTest.onEdt(() -> {
                UndoRedoHandler.getInstance().add(command);
            });
            V022AtomicApplyTest.onEdt(() -> { });
            assertTrue(fired.get(), "the host listener must throw at update exit");
            assertEquals(List.of(ApplyAlignmentEditPlanCommand.NotificationOperation.APPLY), warnings);
            assertEquals(List.of(command), UndoRedoHandler.getInstance().getUndoCommands());
            assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
            before.assertMatches(fixture.dataSet());
            assertEquals(List.of(command), UndoRedoHandler.getInstance().getRedoCommands());
        } finally {
            fixture.dataSet().removeDataSetListener(listener);
        }
    }

    @Test
    void listenerFailureAfterValidatedRedoRetainsCommittedEditAndUnrelatedHistory() throws Exception {
        Fixture fixture = fixture();
        Node unrelated = new Node(new LatLon(10, 10));
        V022AtomicApplyTest.onEdt(() -> {
            UndoRedoHandler.getInstance().add(new AddCommand(fixture.dataSet(), unrelated));
        });
        var prior = UndoRedoHandler.getInstance().getUndoCommands().get(0);
        List<ApplyAlignmentEditPlanCommand.NotificationOperation> warnings =
                new CopyOnWriteArrayList<>();
        var command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), fixture.plan(),
                fixture.validator(), "Host listener failure on Redo", point -> { }, warnings::add);
        V022AtomicApplyTest.onEdt(() -> {
            UndoRedoHandler.getInstance().add(command);
            UndoRedoHandler.getInstance().undo();
        });
        V022AtomicApplyTest.LiveState beforeRedo =
                V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        AtomicBoolean armed = new AtomicBoolean(true);
        AtomicBoolean fired = new AtomicBoolean();
        var listener = new DataSetListenerAdapter(event -> {
            if ((event instanceof NodeMovedEvent || event instanceof DataChangedEvent)
                    && armed.compareAndSet(true, false)) {
                fired.set(true);
                throw new ListenerFailure();
            }
        });
        fixture.dataSet().addDataSetListener(listener);
        try {
            V022AtomicApplyTest.onEdt(() -> {
                UndoRedoHandler.getInstance().redo();
            });
            V022AtomicApplyTest.onEdt(() -> { });
            assertTrue(fired.get(), "the host listener must throw at Redo update exit");
            assertEquals(List.of(ApplyAlignmentEditPlanCommand.NotificationOperation.REDO), warnings);
            assertEquals(List.of(prior, command), UndoRedoHandler.getInstance().getUndoCommands());
            assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
            beforeRedo.assertMatches(fixture.dataSet());
            assertEquals(List.of(prior), UndoRedoHandler.getInstance().getUndoCommands());
            assertEquals(List.of(command), UndoRedoHandler.getInstance().getRedoCommands());
        } finally {
            fixture.dataSet().removeDataSetListener(listener);
        }
    }

    private static final class ListenerFailure extends RuntimeException { }

    @Test
    void unknownRejectingSourceWrapperIsRefusedBeforeMutationOrInvocation() throws Exception {
        Fixture fixture = fixture();
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        AtomicBoolean wrapperInvoked = new AtomicBoolean();
        LockedApplyValidator unsupported = new LockedApplyValidator() {
            @Override public String datasetIdentity() { return fixture.validator().datasetIdentity(); }
            @Override public void validateLocked(DataSet dataSet, AlignmentEditPlan plan,
                    boolean requireSourceGeneration) {
                fixture.validator().validateLocked(dataSet, plan, requireSourceGeneration);
            }
            @Override public void executeWithPreparedSource(Runnable transaction) {
                wrapperInvoked.set(true);
                transaction.run();
                throw new ListenerFailure();
            }
        };
        var command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), fixture.plan(),
                unsupported, "Unsupported source wrapper");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(command)));
        assertTrue(failure.getMessage().contains("notification boundary contract"));
        assertFalse(wrapperInvoked.get());
        before.assertMatches(fixture.dataSet());
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
    }

    @Test
    void warningReporterFailureCannotRevokeCommittedHostApply() throws Exception {
        Fixture fixture = fixture();
        AtomicBoolean armed = new AtomicBoolean(true);
        var listener = new DataSetListenerAdapter(event -> {
            if (armed.compareAndSet(true, false)) throw new ListenerFailure();
        });
        AtomicInteger warningCalls = new AtomicInteger();
        var command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), fixture.plan(),
                fixture.validator(), "Failing warning reporter", point -> { }, operation -> {
                    warningCalls.incrementAndGet();
                    throw new ListenerFailure();
                });
        fixture.dataSet().addDataSetListener(listener);
        try {
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(command));
            V022AtomicApplyTest.onEdt(() -> { });
            assertEquals(1, warningCalls.get());
            assertEquals(List.of(command), UndoRedoHandler.getInstance().getUndoCommands());
            assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
        } finally {
            fixture.dataSet().removeDataSetListener(listener);
        }
    }

    @Test
    void queuedWriterAfterNotificationFailureIsNeverErasedByASecondRollbackUpdate() throws Exception {
        Fixture fixture = fixture();
        PrimitiveKey movingKey = fixture.plan().writePrimitiveKeys().stream()
                .filter(key -> fixture.plan().before().primitives().get(key) instanceof DetachedNode before
                        && fixture.plan().after().primitives().get(key) instanceof DetachedNode after
                        && !before.coordinate().equals(after.coordinate()))
                .findFirst().orElseThrow();
        Node target = (Node) fixture.dataSet().getPrimitiveById(movingKey.id(), OsmPrimitiveType.NODE);
        LatLon original = target.getCoor();
        LatLon sentinel = new LatLon(original.lat() + 0.0001, original.lon() + 0.0001);
        AtomicBoolean restoredBeforeWriter = new AtomicBoolean();
        AtomicBoolean observedCommittedMove = new AtomicBoolean();
        var observer = new DataSetListenerAdapter(event -> {
            if (event instanceof NodeMovedEvent) {
                if (target.getCoor().equals(original)) restoredBeforeWriter.set(true);
                else if (!target.getCoor().equals(sentinel)) observedCommittedMove.set(true);
            }
        });
        CountDownLatch writerAttempting = new CountDownLatch(1);
        CountDownLatch writerFinished = new CountDownLatch(1);
        AtomicReference<Thread> writer = new AtomicReference<>();
        AtomicBoolean armed = new AtomicBoolean(true);
        var failing = new DataSetListenerAdapter(event -> {
            if (event instanceof NodeMovedEvent && armed.compareAndSet(true, false)) {
                Thread queued = new Thread(() -> {
                    writerAttempting.countDown();
                    fixture.dataSet().update(() -> target.setCoor(sentinel));
                    writerFinished.countDown();
                }, "alignment-notification-queued-writer");
                writer.set(queued);
                queued.start();
                try {
                    assertTrue(writerAttempting.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interruption);
                }
                throw new ListenerFailure();
            }
        });
        fixture.dataSet().addDataSetListener(observer);
        fixture.dataSet().addDataSetListener(failing);
        List<ApplyAlignmentEditPlanCommand.NotificationOperation> warnings =
                new CopyOnWriteArrayList<>();
        var command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), fixture.plan(),
                fixture.validator(), "Queued writer notification boundary", point -> { }, warnings::add);
        try {
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(command));
            assertTrue(writerFinished.await(2, TimeUnit.SECONDS));
            V022AtomicApplyTest.onEdt(() -> { });
            assertTrue(observedCommittedMove.get());
            assertFalse(restoredBeforeWriter.get(), "notification recovery must not restore primitives");
            assertEquals(sentinel, target.getCoor(), "the ordered later writer must survive");
            assertEquals(List.of(command), UndoRedoHandler.getInstance().getUndoCommands());
            assertEquals(List.of(ApplyAlignmentEditPlanCommand.NotificationOperation.APPLY), warnings);
        } finally {
            fixture.dataSet().removeDataSetListener(failing);
            fixture.dataSet().removeDataSetListener(observer);
            if (writer.get() != null) writer.get().join(2000);
        }
    }

    @Test
    void postSealManagedSourceChangeThenListenerFailureCommitsButRefusesLaterRedo()
            throws Exception {
        AtomicLong generation = new AtomicLong(37L);
        ManagedFixture fixture = managedFixture(37L, generation::get);
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        List<ApplyAlignmentEditPlanCommand.NotificationOperation> warnings =
                new CopyOnWriteArrayList<>();
        var command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), fixture.plan(),
                fixture.validator(), "Managed notification source change", point -> { }, warnings::add);
        AtomicBoolean armed = new AtomicBoolean(true);
        var listener = new DataSetListenerAdapter(event -> {
            if (armed.compareAndSet(true, false)) {
                generation.incrementAndGet();
                throw new ListenerFailure();
            }
        });
        fixture.dataSet().addDataSetListener(listener);
        try {
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(command));
            V022AtomicApplyTest.onEdt(() -> { });
            assertEquals(38L, generation.get());
            assertEquals(List.of(command), UndoRedoHandler.getInstance().getUndoCommands());
            assertEquals(List.of(ApplyAlignmentEditPlanCommand.NotificationOperation.APPLY), warnings);
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
            before.assertMatches(fixture.dataSet());
            assertThrows(IllegalStateException.class,
                    () -> V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo()));
            before.assertMatches(fixture.dataSet());
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
            assertEquals(List.of(ApplyAlignmentEditPlanCommand.NotificationOperation.APPLY), warnings);
        } finally {
            fixture.dataSet().removeDataSetListener(listener);
        }
    }

    @Test
    void bodyFailureRemainsRejectedWhenItsRollbackNotificationAlsoThrows() throws Exception {
        Fixture fixture = fixture();
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        List<ApplyAlignmentEditPlanCommand.NotificationOperation> warnings =
                new CopyOnWriteArrayList<>();
        var command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), fixture.plan(),
                fixture.validator(), "Mutation and notification failure", point -> {
                    if (point == ApplyAlignmentEditPlanCommand.MutationPoint.AFTER_MOVE_NODES) {
                        throw new InjectedFailure(point);
                    }
                }, warnings::add);
        AtomicBoolean fired = new AtomicBoolean();
        var listener = new DataSetListenerAdapter(event -> {
            if (fired.compareAndSet(false, true)) {
                throw new ListenerFailure();
            }
        });
        fixture.dataSet().addDataSetListener(listener);
        try {
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> V022AtomicApplyTest.onEdt(() ->
                            UndoRedoHandler.getInstance().add(command)));
            assertTrue(rejected.getCause() instanceof InjectedFailure);
            assertTrue(fired.get(), "rollback notification must exercise the masking path");
            assertEquals(1, rejected.getSuppressed().length);
            assertTrue(rejected.getSuppressed()[0] instanceof ListenerFailure);
            before.assertMatches(fixture.dataSet());
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
            assertTrue(warnings.isEmpty());
        } finally {
            fixture.dataSet().removeDataSetListener(listener);
        }
    }

    @Test
    void hostOuterUndoNotificationFailureLeavesRestoredStateAndRedoOwnership() throws Exception {
        Config.setBaseDirectoriesProvider(new IBaseDirectories() {
            @Override public java.io.File getPreferencesDirectory(boolean create) { return temporary.toFile(); }
            @Override public java.io.File getUserDataDirectory(boolean create) { return temporary.toFile(); }
            @Override public java.io.File getCacheDirectory(boolean create) { return temporary.toFile(); }
        });
        Fixture fixture = fixture();
        OsmDataLayer layer = new OsmDataLayer(fixture.dataSet(), "host outer Undo", null);
        V022AtomicApplyTest.onEdt(() -> MainApplication.getLayerManager().addLayer(layer));
        try {
        Node unrelated = new Node(new LatLon(10, 10));
        V022AtomicApplyTest.onEdt(() ->
                UndoRedoHandler.getInstance().add(new AddCommand(fixture.dataSet(), unrelated)));
        var prior = UndoRedoHandler.getInstance().getUndoCommands().get(0);
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        var command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), fixture.plan(),
                fixture.validator(), "Host outer Undo notification");
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(command));
        AtomicBoolean fired = new AtomicBoolean();
        var listener = new DataSetListenerAdapter(event -> {
            if (fired.compareAndSet(false, true)) {
                throw new ListenerFailure();
            }
        });
        fixture.dataSet().addDataSetListener(listener);
        try {
            assertThrows(ListenerFailure.class,
                    () -> V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo()));
            assertTrue(fired.get());
            before.assertMatches(fixture.dataSet());
            assertEquals(List.of(prior), UndoRedoHandler.getInstance().getUndoCommands());
            assertEquals(List.of(command), UndoRedoHandler.getInstance().getRedoCommands());
        } finally {
            fixture.dataSet().removeDataSetListener(listener);
        }
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo());
        assertEquals(List.of(prior, command), UndoRedoHandler.getInstance().getUndoCommands());
        } finally {
            V022AtomicApplyTest.onEdt(() -> MainApplication.getLayerManager().removeLayer(layer));
        }
    }

    @Test
    void staleRouteMembershipRejectsLockedRelationWayApply() throws Exception {
        Fixture fixture = relationWayFixture();
        List<RelationMember> original = List.copyOf(fixture.route().getMembers());
        V022AtomicApplyTest.onEdt(() -> fixture.route().setMembers(
                List.of(original.get(1), original.get(0))));
        V022AtomicApplyTest.LiveState changed =
                V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(), fixture.validator(),
                "Reject stale route membership");
        assertThrows(IllegalStateException.class, () -> V022AtomicApplyTest.onEdt(() ->
                UndoRedoHandler.getInstance().add(command)));
        changed.assertMatches(fixture.dataSet());
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void relationBoundEndpointTRequiresManualAdjustmentWithoutHostMutation() throws Exception {
        Fixture base = fixture();
        Way selected = base.dataSet().getWays().stream()
                .filter(way -> way.getUniqueId() == 711).findFirst().orElseThrow();
        Way receiver = base.dataSet().getWays().stream()
                .filter(way -> way.getUniqueId() == 712).findFirst().orElseThrow();
        Relation route = new Relation();
        route.setMembers(List.of(new RelationMember("selected", selected),
                new RelationMember("receiver", receiver)));
        route.put("type", "route");
        route.setOsmId(713, 1);
        base.dataSet().addPrimitive(route);
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                selected.getNodes(), Set.of());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(base.dataSet());
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(base.dataSet(),
                selection, raster(), config(), false, permissions));
        assertEquals(ManualJunctionEligibility.Reason.PARTICIPATING_RELATION,
                captured[0].junctionDecision().reason());
        var assessment = new ModernSingleWayEditPlanAdapter().assess(
                service.compute(captured[0], CancellationProbe.NONE), 0);
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION,
                assessment.availability(), assessment.detail());
        assertTrue(assessment.plan().isEmpty());
        before.assertMatches(base.dataSet());
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void changedVisibleRasterRejectsHostRedoWithoutTouchingDatasetOrUnrelatedHistory() throws Exception {
        VisibleSourceEpoch epoch = new VisibleSourceEpoch();
        Fixture fixture = fixture(epoch);
        AtomicReference<LiveBPreviewService.VisibleRaster> current = new AtomicReference<>(raster());
        AtomicReference<String> reported = new AtomicReference<>();
        ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(),
                new VisibleSourceLockedApplyValidator(fixture.validator(), new LiveBPreviewService(),
                        fixture.captured(), current::get, epoch,
                        () -> { }, reported::set), "Apply visible source");
        assertSourceStaleRedoHistory(fixture.dataSet(), alignment, () -> {
            LiveBPreviewService.VisibleRaster original = raster();
            int[] pixels = original.argb();
            pixels[360 * original.width() + 360] = 0xffffffff;
            current.set(new LiveBPreviewService.VisibleRaster(original.width(), original.height(), pixels,
                    original.minimumEast(), original.minimumNorth(), original.maximumEast(),
                    original.maximumNorth(), original.projectionUnitsPerViewPixel(),
                    original.groundMetersPerViewPixel(), original.nativePitchMeters(),
                    original.sourceIdentity(), original.projectionCode()));
        }, reported);
    }

    @Test
    void changedPixelOutsideSampledRouteStillRejectsVisibleHostRedo() throws Exception {
        VisibleSourceEpoch epoch = new VisibleSourceEpoch();
        Fixture fixture = fixture(epoch);
        AtomicReference<LiveBPreviewService.VisibleRaster> current = new AtomicReference<>(raster());
        AtomicReference<String> reported = new AtomicReference<>();
        ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(),
                new VisibleSourceLockedApplyValidator(fixture.validator(), new LiveBPreviewService(),
                        fixture.captured(), current::get, epoch, () -> { }, reported::set),
                "Apply visible source");

        assertSourceStaleRedoHistory(fixture.dataSet(), alignment, () -> {
            LiveBPreviewService.VisibleRaster original = raster();
            int[] pixels = original.argb();
            pixels[0] ^= 0x00010101;
            current.set(new LiveBPreviewService.VisibleRaster(original.width(), original.height(), pixels,
                    original.minimumEast(), original.minimumNorth(), original.maximumEast(),
                    original.maximumNorth(), original.projectionUnitsPerViewPixel(),
                    original.groundMetersPerViewPixel(), original.nativePitchMeters(),
                    original.sourceIdentity(), original.projectionCode()));
        }, reported);
    }

    @Test
    void visibleRepeatCaptureCompletesBeforeTheDatasetWriteLockOnApplyAndRedo() throws Exception {
        VisibleSourceEpoch epoch = new VisibleSourceEpoch();
        Fixture fixture = fixture(epoch);
        AtomicInteger capturesOutsideLock = new AtomicInteger();
        ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(),
                new VisibleSourceLockedApplyValidator(fixture.validator(), new LiveBPreviewService(),
                        fixture.captured(), () -> {
                            if (!readLockBlockedForAnotherThread(fixture.dataSet())) {
                                capturesOutsideLock.incrementAndGet();
                                return raster();
                            }
                            throw new IllegalStateException("Visible capture ran under the dataset write lock");
                        }, epoch, () -> { }, message -> { }), "Apply visible source");

        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(alignment));
        V022AtomicApplyTest.LiveState after = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        for (int cycle = 0; cycle < 20; cycle++) {
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
            before.assertMatches(fixture.dataSet());
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo());
            after.assertMatches(fixture.dataSet());
        }
        assertEquals(21, capturesOutsideLock.get());
    }

    @Test
    void visibleTilePublicationBetweenRenderAndLockedApplyRejectsWithoutMutation() throws Exception {
        VisibleSourceEpoch epoch = new VisibleSourceEpoch();
        Fixture fixture = fixture(epoch);
        VisibleSourceLockedApplyValidator source = new VisibleSourceLockedApplyValidator(
                fixture.validator(), new LiveBPreviewService(), fixture.captured(),
                V022ProductionNetworkTransactionTest::raster, epoch, () -> { }, message -> { });
        LockedApplyValidator racingSource = new LockedApplyValidator() {
            @Override public String datasetIdentity() { return source.datasetIdentity(); }
            @Override public boolean returnsNormallyAfterCompletedTransaction() { return true; }
            @Override public Runnable prepareExecution(DataSet dataSet, boolean redo) {
                Runnable receipt = source.prepareExecution(dataSet, redo);
                epoch.sourceChanged();
                return receipt;
            }
            @Override public void validateLocked(DataSet dataSet, AlignmentEditPlan plan,
                    boolean requireSourceGeneration) {
                source.validateLocked(dataSet, plan, requireSourceGeneration);
            }
        };
        ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(), racingSource, "Apply visible source");
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());

        assertThrows(IllegalStateException.class,
                () -> V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(alignment)));

        before.assertMatches(fixture.dataSet());
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void changedSlideReceiptBeforeRepeatCaptureRejectsIdenticalPixels() throws Exception {
        VisibleSourceEpoch epoch = new VisibleSourceEpoch();
        Fixture fixture = fixture(epoch);
        AtomicInteger ownerChecks = new AtomicInteger();
        ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(),
                new VisibleSourceLockedApplyValidator(fixture.validator(), new LiveBPreviewService(),
                        fixture.captured(), V022ProductionNetworkTransactionTest::raster,
                        epoch, () -> {
                            if (ownerChecks.incrementAndGet() == 1) epoch.sourceChanged();
                        }, message -> { }), "Apply visible source");
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());

        assertThrows(IllegalStateException.class,
                () -> V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(alignment)));

        assertEquals(1, ownerChecks.get());
        before.assertMatches(fixture.dataSet());
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void visiblePublisherCannotChangePixelsDuringTheHostDatasetUpdate() throws Exception {
        VisibleSourceEpoch epoch = new VisibleSourceEpoch();
        Fixture fixture = fixture(epoch);
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger publishedPixel = new AtomicInteger();
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        AtomicReference<Thread> publisher = new AtomicReference<>();
        ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(),
                new VisibleSourceLockedApplyValidator(fixture.validator(), new LiveBPreviewService(),
                        fixture.captured(), V022ProductionNetworkTransactionTest::raster,
                        epoch, () -> {
                            if (checks.incrementAndGet() != 2) return;
                            Thread worker = new Thread(() -> {
                                attempted.countDown();
                                synchronized (epoch) {
                                    publishedPixel.set(1);
                                    epoch.sourceChanged();
                                }
                                published.countDown();
                            }, "visible-test-publisher");
                            publisher.set(worker);
                            worker.start();
                            try {
                                if (!attempted.await(2, TimeUnit.SECONDS)
                                        || published.await(200, TimeUnit.MILLISECONDS)) {
                                    throw new IllegalStateException("Visible pixels published inside dataset update");
                                }
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("Interrupted while testing visible publication");
                            }
                        }, message -> { }), "Apply visible source");

        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(alignment));

        assertTrue(published.await(2, TimeUnit.SECONDS));
        publisher.get().join(2000);
        assertEquals(1, publishedPixel.get());
        assertEquals(List.of(alignment), UndoRedoHandler.getInstance().getUndoCommands());
    }

    @Test
    void genericVisibleSourceKeepsFirstApplyButRefusesUnprovableRedo() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<String> reported = new AtomicReference<>();
        ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(),
                new VisibleSourceLockedApplyValidator(fixture.validator(), new LiveBPreviewService(),
                        fixture.captured(), V022ProductionNetworkTransactionTest::raster,
                        null, () -> { }, reported::set), "Apply generic visible source");

        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(alignment));
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
        V022AtomicApplyTest.LiveState beforeRedo = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());

        assertThrows(VisibleSourceRevisionUnavailableException.class,
                () -> V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo()));

        beforeRedo.assertMatches(fixture.dataSet());
        assertTrue(reported.get() != null && reported.get().contains("visible source"));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void visibleCaptureFailureHasFixedRedactedApplyError() throws Exception {
        Fixture fixture = fixture();
        ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(),
                new VisibleSourceLockedApplyValidator(fixture.validator(), new LiveBPreviewService(),
                        fixture.captured(), () -> {
                            throw new IllegalStateException("signed-url-secret");
                        }, null, () -> { }, message -> { }), "Apply visible source");
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(alignment)));

        assertEquals("The captured visible source changed; run a new alignment.", failure.getMessage());
        before.assertMatches(fixture.dataSet());
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    private static boolean readLockBlockedForAnotherThread(DataSet dataSet) {
        AtomicBoolean acquired = new AtomicBoolean();
        Thread probe = new Thread(() -> {
            java.util.concurrent.locks.Lock readLock = dataSet.getReadLock();
            if (readLock.tryLock()) {
                acquired.set(true);
                readLock.unlock();
            }
        });
        probe.start();
        try {
            probe.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking the dataset lock", exception);
        }
        return !acquired.get();
    }

    @Test
    void changedVisibleIdentityRejectsHostRedoWithoutTouchingDatasetOrUnrelatedHistory() throws Exception {
        VisibleSourceEpoch epoch = new VisibleSourceEpoch();
        Fixture fixture = fixture(epoch);
        AtomicReference<LiveBPreviewService.VisibleRaster> current = new AtomicReference<>(raster());
        AtomicReference<String> reported = new AtomicReference<>();
        ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(),
                new VisibleSourceLockedApplyValidator(fixture.validator(), new LiveBPreviewService(),
                        fixture.captured(), current::get, epoch,
                        () -> { }, reported::set), "Apply visible source");
        assertSourceStaleRedoHistory(fixture.dataSet(), alignment, () -> {
            LiveBPreviewService.VisibleRaster original = raster();
            current.set(new LiveBPreviewService.VisibleRaster(original.width(), original.height(),
                    original.argb(), original.minimumEast(), original.minimumNorth(),
                    original.maximumEast(), original.maximumNorth(),
                    original.projectionUnitsPerViewPixel(), original.groundMetersPerViewPixel(),
                    original.nativePitchMeters(), "replacement-visible-layer", original.projectionCode()));
        }, reported);
    }

    @Test
    void managedDisplayFilterChangeRejectsVisibleHostRedo() throws Exception {
        Config.setBaseDirectoriesProvider(new IBaseDirectories() {
            @Override public java.io.File getPreferencesDirectory(boolean create) { return temporary.toFile(); }
            @Override public java.io.File getUserDataDirectory(boolean create) { return temporary.toFile(); }
            @Override public java.io.File getCacheDirectory(boolean create) { return temporary.toFile(); }
        });
        ManagedHeatmapLayer layer = new ManagedHeatmapLayer(new ImageryInfo(
            "Test heatmap", "https://example.invalid/{zoom}/{x}/{y}.png", "tms"));
        Fixture fixture = fixture(layer.sourceEpoch());
        AtomicReference<String> reported = new AtomicReference<>();
        ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(),
                new VisibleSourceLockedApplyValidator(fixture.validator(), new LiveBPreviewService(),
                        fixture.captured(), V022ProductionNetworkTransactionTest::raster,
                        layer.sourceEpoch(), () -> { }, reported::set), "Apply visible source");

        assertSourceStaleRedoHistory(fixture.dataSet(), alignment, layer::filterChanged, reported);
    }

    @Test
    void changedManagedGenerationRejectsHostRedoWithoutTouchingDatasetOrUnrelatedHistory()
            throws Exception {
        ManagedFixture fixture = managedFixture();
        AtomicReference<ManagedHeatmapConfig> current =
                new AtomicReference<>(fixture.config().heatmap());
        AtomicReference<String> reported = new AtomicReference<>();
        try (TileFetchCoordinator coordinator = managedCoordinator()) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(37L));
            ManagedSourceReceipt receipt = new ManagedSourceReceipt(coordinator, fixture.captured(),
                    fixture.config().heatmap(), () -> coordinator, current::get,
                    () -> fixture.captured().projectionCode());
            ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(),
                new ManagedSourceLockedApplyValidator(fixture.validator(), new LiveBPreviewService(),
                        fixture.captured(), receipt::requireCurrent, reported::set),
                "Apply managed source");
            assertSourceStaleRedoHistory(fixture.dataSet(), alignment,
                    () -> coordinator.updateActiveGeneration(new ManagedTileGeneration(38L)), reported);
        }
    }

    @Test
    void productionManagedOwnerRejectsGenerationChangedAfterAssemblyBeforeFirstApply() throws Exception {
        ManagedHeatmapConfig saved = initializeProductionManagedOwner();
        ManagedFixture fixture = managedFixture(saved.cacheBuster(),
                () -> ManagedTileRuntime.initializedCoordinator().activeGenerationValue());
        try {
            TileFetchCoordinator owner = ManagedTileRuntime.initializedCoordinator();
            ManagedSourceReceipt receipt = ManagedSourceReceipt.forCurrentPlugin(owner,
                    fixture.captured(), fixture.config().heatmap());
            ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                    fixture.dataSet(), fixture.plan(),
                    new ManagedSourceLockedApplyValidator(fixture.validator(),
                            new LiveBPreviewService(), fixture.captured(), receipt::requireCurrent,
                            message -> { }), "Apply production managed source");
            V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
            owner.updateActiveGeneration(new ManagedTileGeneration(saved.cacheBuster() + 1L));

            assertThrows(IllegalStateException.class,
                    () -> V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(alignment)));

            before.assertMatches(fixture.dataSet());
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
            assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
        } finally {
            ManagedTileRuntime.close();
        }
    }

    @Test
    void productionManagedOwnerSupportsTwentyHostCyclesAndRejectsChangedSavedSettingsOnRedo()
            throws Exception {
        ManagedHeatmapConfig saved = initializeProductionManagedOwner();
        ManagedFixture fixture = managedFixture(saved.cacheBuster(),
                () -> ManagedTileRuntime.initializedCoordinator().activeGenerationValue());
        try {
            TileFetchCoordinator owner = ManagedTileRuntime.initializedCoordinator();
            ManagedSourceReceipt receipt = ManagedSourceReceipt.forCurrentPlugin(owner,
                    fixture.captured(), fixture.config().heatmap());
            AtomicReference<String> reported = new AtomicReference<>();
            ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                    fixture.dataSet(), fixture.plan(),
                    new ManagedSourceLockedApplyValidator(fixture.validator(),
                            new LiveBPreviewService(), fixture.captured(), receipt::requireCurrent,
                            reported::set), "Apply production managed source");
            V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(alignment));
            V022AtomicApplyTest.LiveState after = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
            for (int cycle = 0; cycle < 20; cycle++) {
                V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
                before.assertMatches(fixture.dataSet());
                V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo());
                after.assertMatches(fixture.dataSet());
            }
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
            before.assertMatches(fixture.dataSet());
            PluginPreferences.save(managedConfig(saved.cacheBuster(), "blue").heatmap());

            assertThrows(IllegalStateException.class,
                    () -> V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo()));

            before.assertMatches(fixture.dataSet());
            assertTrue(reported.get() != null && reported.get().contains("recompute alignment"));
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        } finally {
            ManagedTileRuntime.close();
        }
    }

    @Test
    void productionManagedOwnerRejectsFirstApplyAndRedoForEverySourceClock() throws Exception {
        for (String changed : List.of("generation", "settings", "projection")) {
            for (boolean redo : List.of(false, true)) {
                ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
                ManagedHeatmapConfig saved = initializeProductionManagedOwner();
                ManagedFixture fixture = managedFixture(saved.cacheBuster(),
                        () -> ManagedTileRuntime.initializedCoordinator().activeGenerationValue());
                AtomicReference<String> shown = new AtomicReference<>();
                try {
                    TileFetchCoordinator owner = ManagedTileRuntime.initializedCoordinator();
                    ManagedSourceReceipt receipt = ManagedSourceReceipt.forCurrentPlugin(owner,
                            fixture.captured(), fixture.config().heatmap());
                    ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                            fixture.dataSet(), fixture.plan(),
                            new ManagedSourceLockedApplyValidator(fixture.validator(),
                                    new LiveBPreviewService(), fixture.captured(),
                                    receipt::requireCurrent,
                                    AlignWayAction.redoFailureReporter(shown::set)),
                            "Apply production managed source");
                    Runnable mutate = switch (changed) {
                        case "generation" -> () -> owner.updateActiveGeneration(
                                new ManagedTileGeneration(saved.cacheBuster() + 1L));
                        case "settings" -> () -> PluginPreferences.save(
                                managedConfig(saved.cacheBuster(), "blue").heatmap());
                        case "projection" -> () -> ProjectionRegistry.setProjection(
                                Projections.getProjectionByCode("EPSG:4326"));
                        default -> throw new AssertionError(changed);
                    };
                    if (redo) {
                        assertSourceStaleRedoHistory(fixture.dataSet(), alignment, mutate);
                        SwingUtilities.invokeAndWait(() -> { });
                        assertTrue(shown.get() != null
                                && shown.get().contains("Alignment Redo failed"), changed);
                        assertTrue(!shown.get().contains("key")
                                && !shown.get().contains("signature"), changed);
                    } else {
                        V022AtomicApplyTest.LiveState before =
                                V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
                        mutate.run();
                        assertThrows(IllegalStateException.class,
                                () -> V022AtomicApplyTest.onEdt(() ->
                                        UndoRedoHandler.getInstance().add(alignment)), changed);
                        before.assertMatches(fixture.dataSet());
                        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty(), changed);
                        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty(), changed);
                    }
                } finally {
                    UndoRedoHandler.getInstance().clean();
                    ManagedTileRuntime.close();
                    ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
                }
            }
        }
    }

    private ManagedHeatmapConfig initializeProductionManagedOwner() {
        Config.setPreferencesInstance(new MemoryPreferences());
        Config.setBaseDirectoriesProvider(new IBaseDirectories() {
            @Override public java.io.File getPreferencesDirectory(boolean create) { return temporary.toFile(); }
            @Override public java.io.File getUserDataDirectory(boolean create) { return temporary.toFile(); }
            @Override public java.io.File getCacheDirectory(boolean create) { return temporary.toFile(); }
        });
        PluginPreferences.save(managedConfig(36L, "hot").heatmap());
        ManagedHeatmapConfig saved = PluginPreferences.load();
        ManagedTileRuntime.initialize(saved);
        return saved;
    }

    @Test
    void managedPlanRetainsTheNonzeroAcquisitionGeneration() throws Exception {
        ManagedFixture fixture = managedFixture();
        assertEquals(37L, fixture.captured().network().sourceGeneration());
        assertEquals(37L, fixture.plan().before().sourceGeneration());
    }

    @Test
    void changedManagedSettingsRejectHostRedoWithoutTouchingDatasetOrUnrelatedHistory()
            throws Exception {
        ManagedFixture fixture = managedFixture();
        AtomicReference<ManagedHeatmapConfig> current =
                new AtomicReference<>(fixture.config().heatmap());
        AtomicReference<String> reported = new AtomicReference<>();
        try (TileFetchCoordinator coordinator = managedCoordinator()) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(37L));
            ManagedSourceReceipt receipt = new ManagedSourceReceipt(coordinator, fixture.captured(),
                    fixture.config().heatmap(), () -> coordinator, current::get,
                    () -> fixture.captured().projectionCode());
            ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(),
                new ManagedSourceLockedApplyValidator(fixture.validator(), new LiveBPreviewService(),
                        fixture.captured(), receipt::requireCurrent, reported::set),
                "Apply managed source");
            assertSourceStaleRedoHistory(fixture.dataSet(), alignment,
                    () -> current.set(managedConfig(37L, "blue").heatmap()), reported);
        }
    }

    @Test
    void changedManagedCoordinatorRejectsHostRedoWithoutTouchingUnrelatedHistory() throws Exception {
        ManagedFixture fixture = managedFixture();
        AtomicReference<String> reported = new AtomicReference<>();
        try (TileFetchCoordinator original = managedCoordinator();
                TileFetchCoordinator replacement = managedCoordinator()) {
            original.updateActiveGeneration(new ManagedTileGeneration(37L));
            replacement.updateActiveGeneration(new ManagedTileGeneration(37L));
            AtomicReference<TileFetchCoordinator> current = new AtomicReference<>(original);
            ManagedSourceReceipt receipt = new ManagedSourceReceipt(original, fixture.captured(),
                    fixture.config().heatmap(), current::get,
                    () -> fixture.config().heatmap(), () -> fixture.captured().projectionCode());
            ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                    fixture.dataSet(), fixture.plan(),
                    new ManagedSourceLockedApplyValidator(fixture.validator(),
                            new LiveBPreviewService(), fixture.captured(), receipt::requireCurrent,
                            reported::set), "Apply managed source");

            assertSourceStaleRedoHistory(fixture.dataSet(), alignment,
                    () -> current.set(replacement), reported);
        }
    }

    @Test
    void changedManagedProjectionRejectsHostRedoWithoutTouchingUnrelatedHistory() throws Exception {
        ManagedFixture fixture = managedFixture();
        AtomicReference<String> currentProjection =
                new AtomicReference<>(fixture.captured().projectionCode());
        AtomicReference<String> reported = new AtomicReference<>();
        try (TileFetchCoordinator coordinator = managedCoordinator()) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(37L));
            ManagedSourceReceipt receipt = new ManagedSourceReceipt(coordinator, fixture.captured(),
                    fixture.config().heatmap(), () -> coordinator,
                    () -> fixture.config().heatmap(), currentProjection::get);
            ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                    fixture.dataSet(), fixture.plan(),
                    new ManagedSourceLockedApplyValidator(fixture.validator(),
                            new LiveBPreviewService(), fixture.captured(), receipt::requireCurrent,
                            reported::set), "Apply managed source");

            assertSourceStaleRedoHistory(fixture.dataSet(), alignment,
                    () -> currentProjection.set("EPSG:4326"), reported);
        }
    }

    @Test
    void managedGenerationChangeAfterMutationRollsBackTheHostCommand() throws Exception {
        ManagedFixture fixture = managedFixture();
        try (TileFetchCoordinator coordinator = managedCoordinator()) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(37L));
            ManagedSourceReceipt receipt = new ManagedSourceReceipt(coordinator, fixture.captured(),
                    fixture.config().heatmap(), () -> coordinator,
                    () -> fixture.config().heatmap(), () -> fixture.captured().projectionCode());
            AtomicInteger checks = new AtomicInteger();
            ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                    fixture.dataSet(), fixture.plan(),
                    new ManagedSourceLockedApplyValidator(fixture.validator(),
                            new LiveBPreviewService(), fixture.captured(), () -> {
                                if (checks.incrementAndGet() == 4) {
                                    coordinator.updateActiveGeneration(new ManagedTileGeneration(38L));
                                }
                                receipt.requireCurrent();
                            }, message -> { }), "Apply managed source");
            V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());

            assertThrows(IllegalStateException.class,
                    () -> V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(alignment)));

            assertEquals(4, checks.get());
            before.assertMatches(fixture.dataSet());
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
            assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
        }
    }

    @Test
    void generationOnlyCompatibilityCommandRejectsStaleHostRedo() throws Exception {
        Fixture fixture = fixture();
        AtomicLong currentGeneration = new AtomicLong(fixture.plan().before().sourceGeneration());
        ApplyAlignmentEditPlanCommand alignment = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(), fixture.plan().before().datasetIdentity(),
                currentGeneration::get, "Apply captured generation");
        assertSourceStaleRedoHistory(fixture.dataSet(), alignment,
                currentGeneration::incrementAndGet);
    }

    private TileFetchCoordinator managedCoordinator() {
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        return new TileFetchCoordinator((request, credentials) -> {
            throw new AssertionError("Source validation must never fetch a tile");
        }, new ManagedTileCache(temporary, decoder), decoder, TileReliabilityPolicy.defaults());
    }

    private static ManagedFixture managedFixture() throws Exception {
        return managedFixture(37L, null);
    }

    private static ManagedFixture managedFixture(long generation, LongSupplier liveGeneration)
            throws Exception {
        DataSet dataSet = new DataSet();
        Node west = node(771, 0, -8);
        Node east = node(772, 0, 8);
        Way selected = way(773, west, east);
        dataSet.addPrimitive(west);
        dataSet.addPrimitive(east);
        dataSet.addPrimitive(selected);
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                List.of(west, east), Set.of(west, east));
        AlignmentConfig config = managedConfig(generation, "hot");
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.ManagedCaptureSeed[] seed =
                new LiveBPreviewService.ManagedCaptureSeed[1];
        SwingUtilities.invokeAndWait(() -> seed[0] = service.captureManagedSeed(
                dataSet, selection, config, "managed-selected-hot-g" + generation));
        BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            int gray = (int) Math.round(255.0 * (0.02 + 0.80 * Math.exp(
                    -0.5 * (y - 300.0) * (y - 300.0) / 1.44)));
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, 0xff000000 | gray << 16 | gray << 8 | gray);
            }
        }
        boolean[] valid = new boolean[image.getWidth() * image.getHeight()];
        java.util.Arrays.fill(valid, true);
        double equator = Math.scalb(256.0, 15) / 2.0;
        double halfWidth = image.getWidth() / 4.0;
        LiveBPreviewService.Captured captured = service.attachManagedRaster(seed[0],
                new ManagedModernPreviewSource.Raster(image, valid,
                        SupportedInputRasterTransform.webMercator(15,
                                equator - halfWidth, equator - halfWidth, 2.0),
                        "hot", 15, "managed-selected-hot-g" + generation,
                        new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(generation)));
        LiveBPreviewService.Computed computed = service.compute(captured, CancellationProbe.NONE);
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        NetworkSnapshotCapture.CapturedSnapshot[] receipt =
                new NetworkSnapshotCapture.CapturedSnapshot[1];
        SwingUtilities.invokeAndWait(() -> receipt[0] = NetworkSnapshotCapture.captureBound(
                dataSet, captured.specification()));
        LiveNetworkSnapshotValidator validator = new LiveNetworkSnapshotValidator(
                receipt[0], plan, liveGeneration == null
                        ? () -> plan.before().sourceGeneration() : liveGeneration);
        return new ManagedFixture(dataSet, config, captured, plan, validator);
    }

    private static AlignmentConfig managedConfig(long generation, String color) {
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig("key", "policy", "signature",
                "session", "all", color, "", ".*", AlignmentMode.PRECISE_SHAPE,
                TrackerMode.CORRIDOR_AWARE, false, false, false, false, false, false,
                false, false, false, false, 7, 4, 3.0,
                InferenceMode.RAW_HIGH_RESOLUTION, 15, 15, 7.01, 1.56,
                IntensitySamplingMode.COLOR_MAPPING, generation);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    private static void assertSourceStaleRedoHistory(DataSet dataSet,
            ApplyAlignmentEditPlanCommand alignment, Runnable changeSource) {
        assertSourceStaleRedoHistory(dataSet, alignment, changeSource, null);
    }

    private static void assertSourceStaleRedoHistory(DataSet dataSet,
            ApplyAlignmentEditPlanCommand alignment, Runnable changeSource,
            AtomicReference<String> reported) {
        Node unrelated = new Node(new LatLon(0.001, 0.001));
        AddCommand unrelatedHistory = new AddCommand(dataSet, unrelated);
        V022AtomicApplyTest.onEdt(() -> {
            UndoRedoHandler.getInstance().add(alignment);
            UndoRedoHandler.getInstance().add(unrelatedHistory);
            UndoRedoHandler.getInstance().undo();
            UndoRedoHandler.getInstance().undo();
        });
        assertEquals(List.of(alignment, unrelatedHistory),
                UndoRedoHandler.getInstance().getRedoCommands());
        changeSource.run();
        V022AtomicApplyTest.LiveState beforeRedo = V022AtomicApplyTest.LiveState.capture(dataSet);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo()));

        assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank());
        if (reported != null) {
            assertTrue(reported.get() != null && reported.get().contains("recompute alignment"));
        }
        beforeRedo.assertMatches(dataSet);
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertEquals(List.of(unrelatedHistory), UndoRedoHandler.getInstance().getRedoCommands());
        assertTrue(unrelated.getDataSet() != dataSet);
    }

    @Test
    void realSimpleTPlanRetainsOneHostEntryThroughTwentyCycles() throws Exception {
        Fixture fixture = fixture();
        assertTrue(!fixture.plan().createdPrimitives().isEmpty(),
                "The real route must exercise plan-local node creation");
        assertTrue(fixture.plan().writePrimitiveKeys().stream().anyMatch(key ->
                key.type() == PrimitiveKey.Type.NODE
                        && fixture.plan().before().primitives().containsKey(key)),
                "The real route must reuse and mutate an existing node identity");
        V022AtomicApplyTest.LiveState before =
                V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), fixture.plan(), fixture.validator(),
                "Apply real simple T");
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(command));
        V022AtomicApplyTest.LiveState after =
                V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        assertEquals(before.nodes().size() + fixture.plan().createdPrimitives().size(),
                after.nodes().size());
        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
        for (int cycle = 0; cycle < 20; cycle++) {
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
            before.assertMatches(fixture.dataSet());
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo());
            after.assertMatches(fixture.dataSet());
            assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
            assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
        }
    }

    @Test
    void explicitCleanupDeletesOnlyCapturedOrdinaryUploadedShapeNode() throws Exception {
        CleanupFixture fixture = cleanupFixture(true);
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(
                new ApplyAlignmentEditPlanCommand(fixture.dataSet(), fixture.plan(),
                        fixture.validator(), "Delete uploaded ordinary shape")));
        assertTrue(fixture.removable().isDeleted());
        assertTrue(!fixture.selected().getNodes().contains(fixture.removable()));
        V022AtomicApplyTest.LiveState after = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
        before.assertMatches(fixture.dataSet());
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo());
        after.assertMatches(fixture.dataSet());
        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
    }

    @Test
    void explicitCleanupPhysicallyRemovesNeverUploadedShapeNode() throws Exception {
        CleanupFixture fixture = cleanupFixture(false);
        assertTrue(fixture.removable().isNew());
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(
                new ApplyAlignmentEditPlanCommand(fixture.dataSet(), fixture.plan(),
                        fixture.validator(), "Delete new ordinary shape")));
        assertTrue(fixture.removable().getDataSet() == null);
        assertTrue(!fixture.selected().getNodes().contains(fixture.removable()));
        V022AtomicApplyTest.LiveState after = V022AtomicApplyTest.LiveState.capture(fixture.dataSet());
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
        before.assertMatches(fixture.dataSet());
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo());
        after.assertMatches(fixture.dataSet());
        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
    }

    @Test
    void cleanupRemovalAuthorityExcludesProtectedAndUnsupportedOccurrences() throws Exception {
        for (String variant : List.of("tagged", "shared", "relation", "fixed", "bent",
                "disabled", "other-engine")) {
            DataSet dataSet = new DataSet();
            Node west = node(741, 0, 0);
            Node middle = node(742, variant.equals("bent") ? 1 : 0, 8);
            Node east = node(743, 0, 16);
            if (variant.equals("tagged")) {
                middle.put("name", "protected");
            }
            Way selected = way(744, west, middle, east);
            for (Node node : List.of(west, middle, east)) {
                dataSet.addPrimitive(node);
            }
            dataSet.addPrimitive(selected);
            if (variant.equals("shared")) {
                Node spur = node(745, 5, 8);
                dataSet.addPrimitive(spur);
                dataSet.addPrimitive(way(746, middle, spur));
            }
            if (variant.equals("relation")) {
                Relation relation = new Relation();
                relation.setMembers(List.of(new RelationMember("stop", middle)));
                relation.put("type", "route");
                relation.setOsmId(747, 1);
                dataSet.addPrimitive(relation);
            }
            SelectionContext selection = new SelectionContext(selected, 0, 2,
                    List.of(west, middle, east), variant.equals("fixed")
                            ? Set.of(middle) : Set.of());
            GeometryCleanupConfig cleanup = variant.equals("disabled")
                    ? GeometryCleanupConfig.disabled()
                    : GeometryCleanupConfig.disabled()
                            .withMode(GeometryCleanupMode.REDUCE_POINTS_ONLY);
            TrackerMode engine = variant.equals("other-engine")
                    ? TrackerMode.PROBABILISTIC : TrackerMode.CORRIDOR_AWARE;
            AlignmentConfig config = new AlignmentConfig(
                    config().heatmap().withTrackerMode(engine), cleanup);
            RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                    JunctionPolicy.REATTACH, true);
            LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
            SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                    dataSet, selection, straightRaster(), config, false, permissions));
            PrimitiveKey middleKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                    middle.getUniqueId());
            assertTrue(captured[0].network().closure().removableExistingNodeKeys().isEmpty(),
                    variant);
            if (List.of("tagged", "shared", "relation", "fixed").contains(variant)) {
                assertTrue(captured[0].network().closure().protectedExistingNodeKeys()
                        .contains(middleKey), variant);
            } else {
                assertTrue(captured[0].network().closure().movableExistingNodeKeys()
                        .contains(middleKey), variant);
            }
        }
    }

    @Test
    void sameReceiverAtTwoSelectedJunctionsRequiresManualAdjustment() throws Exception {
        DataSet dataSet = new DataSet();
        Node left = node(751, 0, -40);
        Node right = node(752, 0, 40);
        Way selected = way(753, left, right);
        Node farLeftSouth = node(754, -50, -42);
        Node leftSouthPort = node(755, -35, -42);
        Node leftSouth = node(756, -10, -42);
        Node leftNorth = node(757, 10, -42);
        Node leftNorthPort = node(758, 35, -42);
        Node topLeft = node(759, 50, -42);
        Node topRight = node(760, 50, 42);
        Node rightNorthPort = node(761, 35, 42);
        Node rightNorth = node(762, 10, 42);
        Node rightSouth = node(763, -10, 42);
        Node rightSouthPort = node(764, -35, 42);
        Node farRightSouth = node(765, -50, 42);
        Way receiver = way(766, farLeftSouth, leftSouthPort, leftSouth, left,
                leftNorth, leftNorthPort, topLeft, topRight, rightNorthPort,
                rightNorth, right, rightSouth, rightSouthPort, farRightSouth);
        for (Node node : List.of(left, right, farLeftSouth, leftSouthPort, leftSouth,
                leftNorth, leftNorthPort, topLeft, topRight, rightNorthPort,
                rightNorth, rightSouth, rightSouthPort, farRightSouth)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        Relation route = new Relation();
        route.setMembers(List.of(new RelationMember("bridge", selected),
                new RelationMember("loop", receiver)));
        route.put("type", "route");
        route.setOsmId(767, 1);
        dataSet.addPrimitive(route);
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                List.of(left, right), Set.of());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(dataSet);
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(
                dataSet, selection, sameReceiverRaster(), config(), false, permissions));
        assertEquals(ManualJunctionEligibility.Reason.MULTIPLE_JUNCTIONS,
                captured[0].junctionDecision().reason());
        PrimitiveKey receiverKey = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 766);
        assertTrue(!captured[0].network().closure().editableWayOccurrences()
                .containsKey(receiverKey));
        var assessment = new ModernSingleWayEditPlanAdapter().assess(
                service.compute(captured[0], CancellationProbe.NONE), 0);
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION,
                assessment.availability(), assessment.detail());
        assertTrue(assessment.plan().isEmpty());
        before.assertMatches(dataSet);
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    private static CleanupFixture cleanupFixture(boolean uploaded) throws Exception {
        DataSet dataSet = new DataSet();
        Node west = node(721, 0, 0);
        Node removable = uploaded ? node(722, 0, 8) : new Node(new LatLon(0, longitude(8)));
        Node east = node(723, 0, 16);
        Way selected = way(724, west, removable, east);
        for (Node node : List.of(west, removable, east)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        SelectionContext selection = new SelectionContext(selected, 0, 2,
                List.of(west, removable, east), Set.of());
        GeometryCleanupConfig cleanup = GeometryCleanupConfig.disabled()
                .withMode(GeometryCleanupMode.REDUCE_POINTS_ONLY);
        AlignmentConfig config = new AlignmentConfig(
                config().heatmap().withTrackerMode(TrackerMode.CORRIDOR_AWARE), cleanup);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, straightRaster(), config, false, permissions));
        PrimitiveKey removedKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                removable.getUniqueId());
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        assertTrue(!computed.pipeline().routes().get(0).pointIds().contains(
                new ExistingWayNodeOccurrence(PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                        selected.getUniqueId()), removedKey, 1)));
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        assertEquals(Set.of(removedKey), plan.removedPrimitives().keySet());
        assertEquals(Set.of(removedKey), captured[0].network().closure()
                .removableExistingNodeKeys());
        NetworkSnapshotCapture.CapturedSnapshot[] receipt =
                new NetworkSnapshotCapture.CapturedSnapshot[1];
        SwingUtilities.invokeAndWait(() -> receipt[0] = NetworkSnapshotCapture.captureBound(
                dataSet, captured[0].specification()));
        LiveNetworkSnapshotValidator validator = new LiveNetworkSnapshotValidator(
                receipt[0], plan, () -> plan.before().sourceGeneration());
        return new CleanupFixture(dataSet, selected, removable, plan, validator);
    }

    private static Fixture fixture() throws Exception {
        return fixture(null);
    }

    private static Fixture fixture(VisibleSourceEpoch epoch) throws Exception {
        DataSet dataSet = new DataSet();
        Node west = node(701, 0, -8);
        Node junction = node(702, 0, 8);
        Node farSouth = node(704, -49, 10);
        Node southPort = node(705, -31, 10);
        Node south = node(706, -8, 10);
        Node middle = node(707, 8, 10);
        Node north = node(708, 18, 10);
        Node northPort = node(709, 31, 10);
        Node farNorth = node(710, 49, 10);
        Way selected = way(711, west, junction);
        Way receiver = way(712, farSouth, southPort, south, junction,
                middle, north, northPort, farNorth);
        for (Node node : List.of(west, junction, farSouth, southPort,
                south, middle, north, northPort, farNorth)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                List.of(west, junction), Set.of());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, raster(epoch), config(), false, permissions));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        NetworkSnapshotCapture.CapturedSnapshot[] receipt =
                new NetworkSnapshotCapture.CapturedSnapshot[1];
        SwingUtilities.invokeAndWait(() -> receipt[0] = NetworkSnapshotCapture.captureBound(
                dataSet, captured[0].specification()));
        LiveNetworkSnapshotValidator validator = new LiveNetworkSnapshotValidator(
                receipt[0], plan, () -> plan.before().sourceGeneration());
        return new Fixture(dataSet, plan, validator, null, captured[0]);
    }

    private static Fixture relationWayFixture() throws Exception {
        DataSet dataSet = new DataSet();
        Node west = node(781, 0, -8);
        Node east = node(782, 0, 8);
        Node otherWest = node(783, 40, -8);
        Node otherEast = node(784, 40, 8);
        Way selected = way(785, west, east);
        Way other = way(786, otherWest, otherEast);
        for (Node node : List.of(west, east, otherWest, otherEast)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(other);
        Relation route = new Relation();
        route.setMembers(List.of(new RelationMember("selected", selected),
                new RelationMember("other", other)));
        route.put("type", "route");
        route.setOsmId(787, 1);
        route.setModified(false);
        dataSet.addPrimitive(route);
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                List.of(west, east), Set.of());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(dataSet,
                selection, straightRaster(), config(), false, permissions));
        LiveBPreviewService.Computed computed = service.compute(captured[0], CancellationProbe.NONE);
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        NetworkSnapshotCapture.CapturedSnapshot[] receipt =
                new NetworkSnapshotCapture.CapturedSnapshot[1];
        SwingUtilities.invokeAndWait(() -> receipt[0] = NetworkSnapshotCapture.captureBound(
                dataSet, captured[0].specification()));
        LiveNetworkSnapshotValidator validator = new LiveNetworkSnapshotValidator(
                receipt[0], plan, () -> plan.before().sourceGeneration());
        return new Fixture(dataSet, plan, validator, route, captured[0]);
    }

    private static LiveBPreviewService.VisibleRaster raster() {
        int size = 720;
        int[] argb = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double selectedDistance = (y - 348.0) / 6.0;
                double receiverDistance = (x - 408.0) / 6.0;
                double selected = Math.exp(-0.5 * selectedDistance * selectedDistance / 1.44);
                double receiver = Math.exp(-0.5 * receiverDistance * receiverDistance / 1.44);
                int gray = (int) Math.round(255.0 * (0.02 + 0.80
                        * Math.max(selected, receiver)));
                argb[y * size + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        return new LiveBPreviewService.VisibleRaster(size, size, argb,
                -60.0, -60.0, 60.0, 60.0, 1.0, 1.0, OptionalDouble.of(1.0),
                "production-interior-transaction", "EPSG:3857");
    }

    private static LiveBPreviewService.VisibleRaster raster(VisibleSourceEpoch epoch) {
        LiveBPreviewService.VisibleRaster source = raster();
        if (epoch == null) {
            return source;
        }
        return new LiveBPreviewService.VisibleRaster(source.width(), source.height(), source.argb(),
                source.minimumEast(), source.minimumNorth(), source.maximumEast(),
                source.maximumNorth(), source.projectionUnitsPerViewPixel(),
                source.groundMetersPerViewPixel(), source.nativePitchMeters(),
                source.sourceIdentity(), source.projectionCode(), epoch.captureStable());
    }

    private static LiveBPreviewService.VisibleRaster straightRaster() {
        int size = 720;
        int[] argb = new int[size * size];
        for (int y = 0; y < size; y++) {
            double distance = (y - size / 2.0) / 6.0;
            int gray = (int) Math.round(255.0 * (0.02 + 0.80
                    * Math.exp(-0.5 * distance * distance / 1.44)));
            java.util.Arrays.fill(argb, y * size, (y + 1) * size,
                    0xff000000 | gray << 16 | gray << 8 | gray);
        }
        return new LiveBPreviewService.VisibleRaster(size, size, argb,
                -60.0, -60.0, 60.0, 60.0, 1.0, 1.0, OptionalDouble.of(1.0),
                "production-cleanup-deletion", "EPSG:3857");
    }

    private static LiveBPreviewService.VisibleRaster sameReceiverRaster() {
        return sameReceiverRaster(true);
    }

    private static LiveBPreviewService.VisibleRaster sameReceiverRaster(boolean rightCorridor) {
        int size = 1440;
        int[] argb = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double selectedDistance = (y - 720.0) / 6.0;
                double leftDistance = (x - 456.0) / 6.0;
                double rightDistance = (x - 984.0) / 6.0;
                double selected = Math.exp(-0.5 * selectedDistance * selectedDistance / 1.44);
                double left = Math.exp(-0.5 * leftDistance * leftDistance / 1.44);
                double right = rightCorridor
                        ? Math.exp(-0.5 * rightDistance * rightDistance / 1.44) : 0.0;
                int gray = (int) Math.round(255.0 * (0.02 + 0.80
                        * Math.max(selected, Math.max(left, right))));
                argb[y * size + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        return new LiveBPreviewService.VisibleRaster(size, size, argb,
                -120.0, -120.0, 120.0, 120.0, 1.0, 1.0, OptionalDouble.of(1.0),
                "production-coupled-same-receiver", "EPSG:3857");
    }

    private static AlignmentConfig config() {
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig("", "policy", "signature",
                "session", "all", "hot", "", ".*", AlignmentMode.PRECISE_SHAPE,
                TrackerMode.PROBABILISTIC, false, false, false, false, false, false,
                false, false, false, false, 7, 4, 3.0,
                InferenceMode.RAW_HIGH_RESOLUTION, 15, 15, 7.01, 1.56,
                IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    private static Node node(long id, double northMeters, double eastMeters) {
        Node node = new Node(new LatLon(Math.toDegrees(northMeters / 6_378_137.0),
                longitude(eastMeters)));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static double longitude(double eastMeters) {
        return Math.toDegrees(eastMeters / 6_378_137.0);
    }

    private static Way way(long id, Node... nodes) {
        Way way = new Way();
        way.setNodes(List.of(nodes));
        way.setOsmId(id, 1);
        way.setModified(false);
        return way;
    }

    private record Fixture(DataSet dataSet, AlignmentEditPlan plan,
            LiveNetworkSnapshotValidator validator, Relation route,
            LiveBPreviewService.Captured captured) { }

    private record ManagedFixture(DataSet dataSet, AlignmentConfig config,
            LiveBPreviewService.Captured captured, AlignmentEditPlan plan,
            LiveNetworkSnapshotValidator validator) { }

    private record CleanupFixture(DataSet dataSet, Way selected, Node removable,
            AlignmentEditPlan plan, LiveNetworkSnapshotValidator validator) { }

    private static final class InjectedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InjectedFailure(ApplyAlignmentEditPlanCommand.MutationPoint point) {
            super("injected at " + point);
        }
    }
}

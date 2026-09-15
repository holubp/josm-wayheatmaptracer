package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.function.LongSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class V022LockedClosureApplyTest {
    private static final long GENERATION = 37L;

    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @BeforeEach
    void clearUndo() {
        UndoRedoHandler.getInstance().clean();
    }

    @AfterEach
    void cleanUndo() {
        UndoRedoHandler.getInstance().clean();
    }

    @Test
    void entrantAddedAfterCommandConstructionRejectsBeforeAnyMutation() throws Exception {
        Fixture fixture = fixture();
        ApplyAlignmentEditPlanCommand command = fixture.command(() -> GENERATION, point -> { });
        addCrossingWay(fixture);
        List<String> before = state(fixture.dataSet);

        assertThrows(IllegalStateException.class,
            () -> onEdt(() -> UndoRedoHandler.getInstance().add(command)));

        assertEquals(before, state(fixture.dataSet));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void fullClosureValidationRunsUnderWriteLockBeforeFirstMutation() throws Exception {
        Fixture fixture = fixture();
        AtomicBoolean writeLockObserved = new AtomicBoolean();
        AtomicBoolean mutationReached = new AtomicBoolean();
        LongSupplier generation = () -> {
            writeLockObserved.set(readLockBlockedForAnotherThread(fixture.dataSet));
            assertFalse(mutationReached.get());
            return GENERATION;
        };
        ApplyAlignmentEditPlanCommand command = fixture.command(generation,
            point -> mutationReached.set(true));

        onEdt(() -> UndoRedoHandler.getInstance().add(command));
        assertTrue(writeLockObserved.get());
        assertTrue(mutationReached.get());
        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());

        onEdt(() -> UndoRedoHandler.getInstance().undo());
        assertEquals(List.of(fixture.first, fixture.last), fixture.way.getNodes());
        onEdt(() -> UndoRedoHandler.getInstance().redo());
        assertEquals(3, fixture.way.getNodesCount());
    }

    @Test
    void wrongCertifiedFrameCannotMissEntrantInReviewedEnvelope() throws Exception {
        Fixture fixture = fixture();
        LocalMetricFrame shifted = LocalMetricFrame.certifiedEquirectangular(
            fixture.frame.toGeographic(new MetricPoint(50.0, 0.0)),
            fixture.frame.distortionCertificate().southWest(),
            fixture.frame.distortionCertificate().northEast());
        NetworkSnapshotCapture.Specification wrong = copySpecification(
            fixture.specification, shifted, fixture.specification.permissions());
        NetworkSnapshotCapture.CapturedSnapshot wrongCapture =
            onEdtValue(() -> NetworkSnapshotCapture.captureBound(fixture.dataSet, wrong));
        addCrossingWay(fixture);
        List<String> before = state(fixture.dataSet);

        assertThrows(IllegalArgumentException.class,
            () -> new LiveNetworkSnapshotValidator(
                wrongCapture, fixture.plan, () -> GENERATION));

        assertEquals(before, state(fixture.dataSet));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void differentPlanSnapshotIdentityCannotBorrowCapturedClosure() throws Exception {
        Fixture fixture = fixture();
        LiveNetworkSnapshotValidator validator = new LiveNetworkSnapshotValidator(
            fixture.captured, fixture.plan, () -> GENERATION);
        AlignmentEditPlan relabeled = copyPlan(fixture.plan,
            copySnapshot(fixture.plan.before(), "different-before"),
            copySnapshot(fixture.plan.after(), "different-after"),
            fixture.plan.selectedRange(), fixture.plan.permissions());
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
            fixture.dataSet, relabeled, validator, "Apply relabeled closure");
        List<String> before = state(fixture.dataSet);

        assertThrows(IllegalStateException.class,
            () -> onEdt(() -> UndoRedoHandler.getInstance().add(command)));

        assertEquals(before, state(fixture.dataSet));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void differentSpecificationPermissionsCannotBorrowReviewedPlan() throws Exception {
        Fixture fixture = fixture();
        NetworkSnapshotCapture.Specification wrong = copySpecification(
            fixture.specification, fixture.frame, RecoveryPermissions.disabled(8.0));
        NetworkSnapshotCapture.CapturedSnapshot wrongCapture =
            onEdtValue(() -> NetworkSnapshotCapture.captureBound(fixture.dataSet, wrong));
        List<String> before = state(fixture.dataSet);

        assertThrows(IllegalArgumentException.class,
            () -> new LiveNetworkSnapshotValidator(
                wrongCapture, fixture.plan, () -> GENERATION));

        assertEquals(before, state(fixture.dataSet));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void differentDatasetObjectCannotReuseMatchingLogicalIdentity() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        LiveNetworkSnapshotValidator validator = new LiveNetworkSnapshotValidator(
            fixture.captured, fixture.plan, () -> GENERATION);
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
            other.dataSet, fixture.plan, validator, "Apply to another dataset");
        List<String> before = state(other.dataSet);

        assertThrows(IllegalStateException.class,
            () -> onEdt(() -> UndoRedoHandler.getInstance().add(command)));

        assertEquals(before, state(other.dataSet));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void changedInitialSourceGenerationRejectsBeforeMutation() throws Exception {
        Fixture fixture = fixture();
        ApplyAlignmentEditPlanCommand command = fixture.command(
            () -> GENERATION + 1, point -> { });
        List<String> before = state(fixture.dataSet);

        assertThrows(IllegalStateException.class,
            () -> onEdt(() -> UndoRedoHandler.getInstance().add(command)));

        assertEquals(before, state(fixture.dataSet));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void selectedRangeAndAuthorityMustMatchCapturedPlan() throws Exception {
        Fixture fixture = fixture();
        OccurrenceRange firstOnly = new OccurrenceRange(0, 0);
        NetworkSnapshotCapture.Specification wrong = new NetworkSnapshotCapture.Specification(
            fixture.specification.snapshotId(), fixture.specification.datasetIdentity(),
            fixture.specification.sourceGeneration(), fixture.specification.selectedWayKey(),
            firstOnly, fixture.frame, fixture.specification.collisionEnvelope(),
            fixture.specification.editRegion(),
            Map.of(fixture.specification.selectedWayKey(), List.of(firstOnly)),
            fixture.specification.editableExistingKeys(),
            fixture.specification.movableExistingNodeKeys(),
            fixture.specification.removableExistingNodeKeys(),
            fixture.specification.explicitlyProtectedNodeKeys(),
            false, fixture.specification.permissions());
        NetworkSnapshotCapture.CapturedSnapshot wrongCapture =
            onEdtValue(() -> NetworkSnapshotCapture.captureBound(fixture.dataSet, wrong));
        List<String> before = state(fixture.dataSet);

        assertThrows(IllegalArgumentException.class,
            () -> new LiveNetworkSnapshotValidator(
                wrongCapture, fixture.plan, () -> GENERATION));

        assertEquals(before, state(fixture.dataSet));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void changedReferrerRejectsWithExactDatasetAndHistoryUntouched() throws Exception {
        Fixture fixture = fixture();
        ApplyAlignmentEditPlanCommand command = fixture.command(() -> GENERATION, point -> { });
        Relation relation = new Relation();
        relation.setMembers(List.of(new RelationMember("", fixture.first)));
        fixture.dataSet.addPrimitive(relation);
        List<String> before = state(fixture.dataSet);

        assertThrows(IllegalStateException.class,
            () -> onEdt(() -> UndoRedoHandler.getInstance().add(command)));

        assertEquals(before, state(fixture.dataSet));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
    }

    @Test
    void changedKnownCoordinateRejectsWithExactDatasetAndHistoryUntouched() throws Exception {
        Fixture fixture = fixture();
        ApplyAlignmentEditPlanCommand command = fixture.command(() -> GENERATION, point -> { });
        fixture.first.setCoor(new LatLon(fixture.first.lat() + 0.00001, fixture.first.lon()));
        List<String> before = state(fixture.dataSet);

        assertThrows(IllegalStateException.class,
            () -> onEdt(() -> UndoRedoHandler.getInstance().add(command)));

        assertEquals(before, state(fixture.dataSet));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
    }

    private static Fixture fixture() throws Exception {
        DataSet dataSet = new DataSet();
        GeographicPoint origin = new GeographicPoint(42.0, 19.0);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        Node first = loadedNode(1, frame.toGeographic(new MetricPoint(-8.0, 0.0)));
        Node last = loadedNode(2, frame.toGeographic(new MetricPoint(8.0, 0.0)));
        Way way = new Way();
        way.setNodes(List.of(first, last));
        way.setOsmId(10, 1);
        way.setModified(false);
        dataSet.addPrimitive(first);
        dataSet.addPrimitive(last);
        dataSet.addPrimitive(way);

        PrimitiveKey wayKey = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 10);
        PrimitiveKey firstKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey lastKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        OccurrenceRange range = new OccurrenceRange(0, 1);
        MetricRegion region = MetricRegion.rectangle(-20.0, -20.0, 20.0, 20.0);
        NetworkSnapshotCapture.Specification specification =
            new NetworkSnapshotCapture.Specification("locked-before", "locked-dataset",
                GENERATION, wayKey, range, frame, region, region,
                Map.of(wayKey, List.of(range)), Set.of(wayKey), Set.of(), Set.of(),
                Set.of(firstKey, lastKey), true, RecoveryPermissions.disabled(7.01));
        NetworkSnapshotCapture.CapturedSnapshot captured =
            onEdtValue(() -> NetworkSnapshotCapture.captureBound(dataSet, specification));
        NetworkSnapshot before = captured.snapshot();

        PrimitiveKey planned = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 1);
        GeographicPoint midpoint = frame.toGeographic(new MetricPoint(0.0, 2.0));
        Map<PrimitiveKey, DetachedPrimitive> afterValues =
            new LinkedHashMap<>(before.primitives());
        afterValues.put(planned, new DetachedNode(planned, midpoint, Map.of(), false, true));
        DetachedWay oldWay = (DetachedWay) before.primitives().get(wayKey);
        afterValues.put(wayKey, new DetachedWay(wayKey,
            List.of(firstKey, planned, lastKey), oldWay.tags(), false, true));
        NetworkSnapshot after = new NetworkSnapshot("locked-after", SnapshotRole.PROPOSED_AFTER,
            before.datasetIdentity(), before.sourceGeneration(), before.closure(), afterValues,
            V022SnapshotFixtures.closedWorldReferrerWatches(afterValues));
        AlignmentEditPlan plan = new AlignmentEditPlan(wayKey, range, before, after, frame,
            RecoveryPermissions.disabled(7.01), "settings", "evidence", "parameters",
            "locked-route", Map.of(wayKey, List.of(
                ((DetachedNode) before.primitives().get(firstKey)).coordinate(), midpoint,
                ((DetachedNode) before.primitives().get(lastKey)).coordinate())),
            new ValidationReport(ValidationReport.Disposition.APPLICABLE, List.of()));
        return new Fixture(dataSet, first, last, way, frame, specification, captured,
            before, plan);
    }

    private static NetworkSnapshotCapture.Specification copySpecification(
            NetworkSnapshotCapture.Specification source, LocalMetricFrame frame,
            RecoveryPermissions permissions) {
        return new NetworkSnapshotCapture.Specification(source.snapshotId(),
            source.datasetIdentity(), source.sourceGeneration(), source.selectedWayKey(),
            source.selectedRange(), frame, source.collisionEnvelope(), source.editRegion(),
            source.editableWayOccurrences(), source.editableExistingKeys(),
            source.movableExistingNodeKeys(), source.removableExistingNodeKeys(),
            source.explicitlyProtectedNodeKeys(), source.mayCreateNodes(), permissions);
    }

    private static NetworkSnapshot copySnapshot(NetworkSnapshot source, String snapshotId) {
        return new NetworkSnapshot(snapshotId, source.role(), source.datasetIdentity(),
            source.sourceGeneration(), source.closure(), source.primitives(),
            source.incomingReferrerWatches());
    }

    private static AlignmentEditPlan copyPlan(AlignmentEditPlan source,
            NetworkSnapshot before, NetworkSnapshot after, OccurrenceRange selectedRange,
            RecoveryPermissions permissions) {
        return new AlignmentEditPlan(source.selectedWayKey(), selectedRange, before, after,
            source.metricFrame(), permissions, source.settingsHash(), source.evidenceHash(),
            source.parameterHash(), source.routeIdentity(), source.finalPreviewWays(),
            source.validation());
    }

    private static void addCrossingWay(Fixture fixture) {
        Node first = new Node(latLon(fixture.frame.toGeographic(new MetricPoint(0.0, -30.0))));
        Node last = new Node(latLon(fixture.frame.toGeographic(new MetricPoint(0.0, 30.0))));
        Way crossing = new Way();
        crossing.setNodes(List.of(first, last));
        fixture.dataSet.addPrimitive(first);
        fixture.dataSet.addPrimitive(last);
        fixture.dataSet.addPrimitive(crossing);
    }

    private static boolean readLockBlockedForAnotherThread(DataSet dataSet) {
        AtomicBoolean acquired = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            Lock readLock = dataSet.getReadLock();
            if (readLock.tryLock()) {
                acquired.set(true);
                readLock.unlock();
            }
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while probing dataset lock", exception);
        }
        return !acquired.get();
    }

    private static Node loadedNode(long id, GeographicPoint point) {
        Node node = new Node(latLon(point));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static LatLon latLon(GeographicPoint point) {
        return new LatLon(point.latitudeDegrees(), point.longitudeDegrees());
    }

    private static List<String> state(DataSet dataSet) {
        return dataSet.allPrimitives().stream().map(primitive -> primitive.getType() + ":"
            + primitive.getUniqueId() + ":" + primitive.isModified() + ":" + primitive.isDeleted()
            + ":" + primitive.getKeys()
            + (primitive instanceof Node node ? ":" + node.lat() + ":" + node.lon()
                : primitive instanceof Way way ? ":" + way.getNodeIds() : ""))
            .sorted().toList();
    }

    private static <T> T onEdtValue(java.util.concurrent.Callable<T> operation)
            throws Exception {
        Object[] value = new Object[1];
        onEdt(() -> {
            try {
                value[0] = operation.call();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        @SuppressWarnings("unchecked")
        T result = (T) value[0];
        return result;
    }

    private static void onEdt(Runnable operation) throws Exception {
        try {
            SwingUtilities.invokeAndWait(operation);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw exception;
        }
    }

    private record Fixture(DataSet dataSet, Node first, Node last, Way way,
            LocalMetricFrame frame, NetworkSnapshotCapture.Specification specification,
            NetworkSnapshotCapture.CapturedSnapshot captured, NetworkSnapshot before,
            AlignmentEditPlan plan) {
        ApplyAlignmentEditPlanCommand command(LongSupplier generation,
                ApplyAlignmentEditPlanCommand.MutationProbe mutationProbe) {
            LiveNetworkSnapshotValidator validator = new LiveNetworkSnapshotValidator(
                captured, plan, generation);
            return new ApplyAlignmentEditPlanCommand(dataSet, plan, validator,
                "Apply locked closure", mutationProbe);
        }
    }
}

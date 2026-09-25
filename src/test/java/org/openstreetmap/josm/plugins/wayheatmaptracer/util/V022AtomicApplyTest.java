package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelationMember;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Transaction regressions T107-T114 for the v0.22 all-way apply boundary. */
class V022AtomicApplyTest {
    private static final String DATASET_ID = "atomic-fixture";
    private static final long SOURCE_GENERATION = 37L;

    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @BeforeEach
    void clearUndoStack() {
        UndoRedoHandler.getInstance().clean();
    }

    @AfterEach
    void leaveUndoStackClean() {
        UndoRedoHandler.getInstance().clean();
    }

    @ParameterizedTest(name = "T107 rollback at {0}")
    @EnumSource(ApplyAlignmentEditPlanCommand.MutationPoint.class)
    void t107EveryMutationCheckpointFailureRollsBackAtomically(
        ApplyAlignmentEditPlanCommand.MutationPoint failurePoint
    ) {
        Fixture fixture = Fixture.create(false);
        LiveState before = LiveState.capture(fixture);
        ApplyAlignmentEditPlanCommand command = fixture.command(point -> {
            if (point == failurePoint) {
                throw new InjectedFailure(failurePoint);
            }
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> onEdt(() -> UndoRedoHandler.getInstance().add(command)));

        assertTrue(failure.getCause() instanceof InjectedFailure);
        before.assertMatches(fixture);
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void g602FailureAfterPhysicalTransientDeletionRollsBack() {
        Fixture fixture = Fixture.createWithNeverUploadedSecondNode();
        LiveState before = LiveState.capture(fixture);
        Node transientNode = fixture.dataSet.getNodes().stream()
            .filter(Node::isNew).findFirst().orElseThrow();
        ApplyAlignmentEditPlanCommand command = fixture.command(point -> {
            if (point == ApplyAlignmentEditPlanCommand.MutationPoint.AFTER_DELETE_NODES) {
                throw new InjectedFailure(point);
            }
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> onEdt(() -> UndoRedoHandler.getInstance().add(command)));

        assertTrue(failure.getCause() instanceof InjectedFailure);
        before.assertMatches(fixture);
        assertSame(fixture.dataSet, transientNode.getDataSet());
        assertFalse(transientNode.isDeleted());
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void g603FailureRestoresUnchangedExternalBoundaryReferrer() {
        Fixture fixture = Fixture.createWithExternalBoundaryReferrer();
        LiveState before = LiveState.capture(fixture);
        Way external = (Way) fixture.dataSet.getPrimitiveById(30L,
            org.openstreetmap.josm.data.osm.OsmPrimitiveType.WAY);
        ApplyAlignmentEditPlanCommand command = fixture.command(point -> {
            if (point == ApplyAlignmentEditPlanCommand.MutationPoint.AFTER_MOVE_NODES) {
                throw new InjectedFailure(point);
            }
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> onEdt(() -> UndoRedoHandler.getInstance().add(command)));

        assertTrue(failure.getCause() instanceof InjectedFailure);
        before.assertMatches(fixture);
        assertEquals(List.of(9L, 1L), external.getNodeIds());
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void g601InterruptedOffEdtApplyCannotQueueALateMutation() throws InterruptedException {
        Fixture fixture = Fixture.create(false);
        LiveState before = LiveState.capture(fixture);
        ApplyAlignmentEditPlanCommand command = fixture.command(point -> { });
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                releaseEdt.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtBlocked.await(5, TimeUnit.SECONDS));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                command.executeCommand();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "interrupted-off-edt-apply");

        try {
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(worker.isAlive(), "Interrupted Apply caller did not return");
            assertTrue(failure.get() instanceof IllegalStateException,
                "Off-EDT Apply must fail before dispatch");
        } finally {
            releaseEdt.countDown();
        }

        onEdt(() -> { });
        before.assertMatches(fixture);
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    static void onEdt(ThrowingRunnable operation) {
        if (SwingUtilities.isEventDispatchThread()) {
            operation.run();
            return;
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    operation.run();
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for EDT", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("EDT invocation failed", exception.getCause());
        }
        Throwable throwable = failure.get();
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        if (throwable != null) {
            throw new IllegalStateException("EDT operation failed", throwable);
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run();
    }

    private static final class InjectedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InjectedFailure(ApplyAlignmentEditPlanCommand.MutationPoint point) {
            super("injected at " + point);
        }
    }

    record LiveState(
        Map<Long, NodeState> nodes,
        Map<Long, WayState> ways,
        Map<Long, RelationState> relations,
        boolean dataSetModified
    ) {
        static LiveState capture(Fixture fixture) {
            return capture(fixture.dataSet);
        }

        static LiveState capture(DataSet dataSet) {
            Map<Long, NodeState> nodes = new LinkedHashMap<>();
            dataSet.getNodes().forEach(node -> nodes.put(node.getUniqueId(), NodeState.capture(node)));
            Map<Long, WayState> ways = new LinkedHashMap<>();
            dataSet.getWays().forEach(way -> ways.put(way.getUniqueId(), WayState.capture(way)));
            Map<Long, RelationState> relations = new LinkedHashMap<>();
            dataSet.getRelations().forEach(relation ->
                relations.put(relation.getUniqueId(), RelationState.capture(relation)));
            return new LiveState(Map.copyOf(nodes), Map.copyOf(ways), Map.copyOf(relations),
                dataSet.isModified());
        }

        void assertMatches(Fixture fixture) {
            assertMatches(fixture.dataSet);
        }

        void assertMatches(DataSet dataSet) {
            LiveState actual = capture(dataSet);
            assertEquals(this, actual);
            nodes.forEach((id, expected) -> assertSame(expected.identity(), actual.nodes.get(id).identity()));
            ways.forEach((id, expected) -> assertSame(expected.identity(), actual.ways.get(id).identity()));
            relations.forEach((id, expected) ->
                assertSame(expected.identity(), actual.relations.get(id).identity()));
        }
    }

    private record NodeState(Node identity, double latitude, double longitude, Map<String, String> tags,
                             boolean modified, boolean deleted, Set<Long> referrers) {
        static NodeState capture(Node node) {
            return new NodeState(node, node.lat(), node.lon(), Map.copyOf(node.getKeys()), node.isModified(),
                node.isDeleted(), referrerIds(node));
        }
    }

    private record WayState(Way identity, List<Long> nodeIds, Map<String, String> tags,
                            boolean modified, boolean deleted, Set<Long> referrers) {
        static WayState capture(Way way) {
            return new WayState(way, way.getNodes().stream().map(Node::getUniqueId).toList(),
                Map.copyOf(way.getKeys()), way.isModified(), way.isDeleted(), referrerIds(way));
        }
    }

    private record RelationState(Relation identity, List<MemberState> members, Map<String, String> tags,
                                 boolean modified, boolean deleted, Set<Long> referrers) {
        static RelationState capture(Relation relation) {
            return new RelationState(relation, relation.getMembers().stream().map(MemberState::capture).toList(),
                Map.copyOf(relation.getKeys()), relation.isModified(), relation.isDeleted(), referrerIds(relation));
        }
    }

    private record MemberState(String role, long uniqueId, String type) {
        static MemberState capture(RelationMember member) {
            return new MemberState(member.getRole(), member.getUniqueId(), member.getType().name());
        }
    }

    private static Set<Long> referrerIds(OsmPrimitive primitive) {
        Set<Long> result = new LinkedHashSet<>();
        primitive.getReferrers().forEach(referrer -> result.add(referrer.getUniqueId()));
        return Set.copyOf(result);
    }

    static final class Fixture {
        final DataSet dataSet;
        final AlignmentEditPlan plan;

        private Fixture(DataSet dataSet, AlignmentEditPlan plan) {
            this.dataSet = dataSet;
            this.plan = plan;
        }

        static Fixture create(boolean initiallyDirty) {
            return create(initiallyDirty, true);
        }

        static Fixture createWithNeverUploadedSecondNode() {
            return create(false, false, false);
        }

        static Fixture createWithExternalBoundaryReferrer() {
            return create(false, true, true);
        }

        private static Fixture create(boolean initiallyDirty, boolean secondNodeUploaded) {
            return create(initiallyDirty, secondNodeUploaded, false);
        }

        private static Fixture create(boolean initiallyDirty, boolean secondNodeUploaded,
            boolean externalBoundaryReferrer) {
            DataSet dataSet = new DataSet();
            Node n1 = loadedNode(1, 42.0000, 19.0000);
            Node n2 = secondNodeUploaded ? loadedNode(2, 42.0000, 19.0001)
                : new Node(new LatLon(42.0000, 19.0001));
            Node n3 = loadedNode(3, 42.0000, 19.0002);
            Node n4 = loadedNode(4, 42.0000, 19.0003);
            Node n5 = loadedNode(5, 42.0001, 19.0002);
            Node n7 = loadedNode(7, 42.0002, 19.0000);
            Node n8 = loadedNode(8, 42.0002, 19.0001);
            List<Node> nodes = List.of(n1, n2, n3, n4, n5, n7, n8);
            nodes.forEach(dataSet::addPrimitive);

            Way selected = loadedWay(10, List.of(n1, n2, n3, n4));
            Way incident = loadedWay(11, List.of(n5, n3));
            Way independent = loadedWay(12, List.of(n7, n8));
            dataSet.addPrimitive(selected);
            dataSet.addPrimitive(incident);
            dataSet.addPrimitive(independent);

            Relation restriction = new Relation();
            restriction.setMembers(List.of(new RelationMember("from", selected),
                new RelationMember("via", n3), new RelationMember("to", incident)));
            restriction.put("type", "restriction");
            restriction.put("restriction", "no_right_turn");
            restriction.setOsmId(20, 1);
            restriction.setModified(false);
            dataSet.addPrimitive(restriction);

            Way external = null;
            if (externalBoundaryReferrer) {
                Node externalNode = loadedNode(9, 42.0003, 19.0000);
                dataSet.addPrimitive(externalNode);
                external = loadedWay(30, List.of(externalNode, n1));
                dataSet.addPrimitive(external);
            }

            if (initiallyDirty) {
                Node unrelatedNew = new Node(new LatLon(42.001, 19.001));
                unrelatedNew.put("note", "pre-existing local edit");
                dataSet.addPrimitive(unrelatedNew);
                n5.setModified(true);
            }

            return new Fixture(dataSet, createPlan(dataSet, selected, incident, independent, restriction,
                external == null ? null : key(external), n1, n2, n3, n4, n5, n7, n8));
        }

        ApplyAlignmentEditPlanCommand command(ApplyAlignmentEditPlanCommand.MutationProbe probe) {
            return new ApplyAlignmentEditPlanCommand(dataSet, plan, DATASET_ID,
                () -> SOURCE_GENERATION, "Apply heatmap network alignment", probe);
        }

        private static AlignmentEditPlan createPlan(DataSet dataSet, Way selected, Way incident,
            Way independent, Relation restriction, PrimitiveKey externalBoundaryReferrer, Node... nodes) {
            Map<PrimitiveKey, DetachedPrimitive> beforeValues = new LinkedHashMap<>();
            for (Node node : nodes) {
                beforeValues.put(key(node), detached(node));
            }
            beforeValues.put(key(selected), detached(selected));
            beforeValues.put(key(incident), detached(incident));
            beforeValues.put(key(independent), detached(independent));
            beforeValues.put(key(restriction), detached(restriction));

            PrimitiveKey n1 = key(nodes[0]);
            PrimitiveKey n2 = key(nodes[1]);
            PrimitiveKey n3 = key(nodes[2]);
            PrimitiveKey n4 = key(nodes[3]);
            PrimitiveKey n5 = key(nodes[4]);
            PrimitiveKey n7 = key(nodes[5]);
            PrimitiveKey n8 = key(nodes[6]);
            PrimitiveKey selectedKey = key(selected);
            PrimitiveKey incidentKey = key(incident);
            PrimitiveKey independentKey = key(independent);
            PrimitiveKey relationKey = key(restriction);
            PrimitiveKey replacement = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 1);

            Set<PrimitiveKey> capturedKeys = Set.copyOf(beforeValues.keySet());
            Set<PrimitiveKey> editable = Set.of(n2, n3, n8, selectedKey, incidentKey,
                independentKey, relationKey);
            ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
                "atomic-fixture-v1", capturedKeys, editable, Set.of(n8), Set.of(n1, n4, n5, n7),
                Set.of(n2, n3), Map.of(
                    selectedKey, List.of(new OccurrenceRange(1, 2)),
                    incidentKey, List.of(new OccurrenceRange(1, 1)),
                    independentKey, List.of(new OccurrenceRange(1, 1))),
                List.of(), MetricRegion.rectangle(-100.0, -100.0, 100.0, 100.0),
                MetricRegion.rectangle(-100.0, -100.0, 100.0, 100.0),
                true, true, true, true);

            Map<PrimitiveKey, Set<PrimitiveKey>> beforeWatches = new LinkedHashMap<>(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures
                    .closedWorldReferrerWatches(beforeValues));
            if (externalBoundaryReferrer != null) {
                beforeWatches.put(n1, Set.of(selectedKey, externalBoundaryReferrer));
            }
            NetworkSnapshot before = new NetworkSnapshot("atomic-before", SnapshotRole.CAPTURED_BEFORE,
                DATASET_ID, SOURCE_GENERATION, closure, beforeValues, beforeWatches);
            Map<PrimitiveKey, DetachedPrimitive> afterValues = new LinkedHashMap<>(beforeValues);
            afterValues.remove(n2);
            afterValues.remove(n3);
            GeographicPoint replacementPoint = new GeographicPoint(42.00001, 19.0002);
            afterValues.put(replacement, new DetachedNode(replacement, replacementPoint, Map.of(), false, true));
            afterValues.put(n8, new DetachedNode(n8, new GeographicPoint(42.00021, 19.0001),
                Map.of(), false, true));
            afterValues.put(selectedKey, new DetachedWay(selectedKey, List.of(n1, replacement, n4),
                Map.copyOf(selected.getKeys()), false, true));
            afterValues.put(incidentKey, new DetachedWay(incidentKey, List.of(n5, replacement),
                Map.copyOf(incident.getKeys()), false, true));
            afterValues.put(relationKey, new DetachedRelation(relationKey, List.of(
                new DetachedRelationMember(selectedKey, "from"),
                new DetachedRelationMember(replacement, "via"),
                new DetachedRelationMember(incidentKey, "to")),
                Map.copyOf(restriction.getKeys()), false, true));
            Map<PrimitiveKey, Set<PrimitiveKey>> afterWatches = new LinkedHashMap<>(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures
                    .closedWorldReferrerWatches(afterValues));
            if (externalBoundaryReferrer != null) {
                afterWatches.put(n1, Set.of(selectedKey, externalBoundaryReferrer));
            }
            NetworkSnapshot after = new NetworkSnapshot("atomic-after", SnapshotRole.PROPOSED_AFTER,
                DATASET_ID, SOURCE_GENERATION, closure, afterValues, afterWatches);

            GeographicPoint origin = new GeographicPoint(42.0001, 19.0001);
            LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
                new GeographicPoint(41.999, 18.999), new GeographicPoint(42.002, 19.002));
            Map<PrimitiveKey, List<GeographicPoint>> preview = new LinkedHashMap<>();
            preview.put(selectedKey, List.of(coordinate(nodes[0]), replacementPoint, coordinate(nodes[3])));
            preview.put(incidentKey, List.of(coordinate(nodes[4]), replacementPoint));
            preview.put(independentKey, List.of(coordinate(nodes[5]),
                ((DetachedNode) afterValues.get(n8)).coordinate()));
            return new AlignmentEditPlan(selectedKey, new OccurrenceRange(0, 3), before, after, frame,
                new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.REATTACH, true),
                "settings", "evidence", "parameters", "atomic-route", preview,
                new ValidationReport(ValidationReport.Disposition.APPLICABLE, List.of()));
        }

        private static Node loadedNode(long id, double latitude, double longitude) {
            Node node = new Node(new LatLon(latitude, longitude));
            node.setOsmId(id, 1);
            node.setModified(false);
            return node;
        }

        private static Way loadedWay(long id, List<Node> nodes) {
            Way way = new Way();
            way.setNodes(nodes);
            way.setOsmId(id, 1);
            way.setModified(false);
            return way;
        }

        private static PrimitiveKey key(OsmPrimitive primitive) {
            PrimitiveKey.Type type = primitive instanceof Node ? PrimitiveKey.Type.NODE
                : primitive instanceof Way ? PrimitiveKey.Type.WAY : PrimitiveKey.Type.RELATION;
            return PrimitiveKey.existing(type, primitive.getUniqueId());
        }

        private static DetachedPrimitive detached(OsmPrimitive primitive) {
            PrimitiveKey key = key(primitive);
            if (primitive instanceof Node node) {
                return new DetachedNode(key, coordinate(node), Map.copyOf(node.getKeys()),
                    node.isDeleted(), node.isModified());
            }
            if (primitive instanceof Way way) {
                return new DetachedWay(key, way.getNodes().stream().map(Fixture::key).toList(),
                    Map.copyOf(way.getKeys()), way.isDeleted(), way.isModified());
            }
            Relation relation = (Relation) primitive;
            return new DetachedRelation(key, relation.getMembers().stream()
                .map(member -> new DetachedRelationMember(key(member.getMember()), member.getRole())).toList(),
                Map.copyOf(relation.getKeys()), relation.isDeleted(), relation.isModified());
        }

        private static GeographicPoint coordinate(Node node) {
            return new GeographicPoint(node.lat(), node.lon());
        }
    }
}

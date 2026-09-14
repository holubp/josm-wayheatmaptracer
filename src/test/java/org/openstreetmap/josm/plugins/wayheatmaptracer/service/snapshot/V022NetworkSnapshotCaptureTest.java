package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelationMember;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ExternalPort;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class V022NetworkSnapshotCaptureTest {
    private static final LocalMetricFrame FRAME = LocalMetricFrame.certifiedEquirectangular(
        new GeographicPoint(0, 0), new GeographicPoint(-0.01, -0.01),
        new GeographicPoint(0.01, 0.01));
    private static final MetricRegion COLLISION = MetricRegion.rectangle(-10, -10, 10, 10);
    private static final MetricRegion EDIT = MetricRegion.rectangle(-500, -500, 500, 500);

    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void captureRejectsOffEdtBeforeReadingLiveData() {
        Chain chain = chain();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> NetworkSnapshotCapture.capture(chain.dataSet(), chain.specification("off-edt")));

        assertTrue(failure.getMessage().contains("EDT"));
    }

    @Test
    void boundedChainTerminatesAtExternalIdentityWatchAndHashIsStable() {
        Chain chain = chain();

        NetworkSnapshot first = onEdt(() -> NetworkSnapshotCapture.capture(
            chain.dataSet(), chain.specification("first")));
        NetworkSnapshot second = onEdt(() -> NetworkSnapshotCapture.capture(
            chain.dataSet(), chain.specification("second")));

        assertEquals(Set.of(node(1), node(2), node(3), way(10), way(11)),
            first.primitives().keySet());
        assertEquals(Set.of(way(11), way(12)), first.incomingReferrerWatches().get(node(3)));
        assertFalse(first.primitives().containsKey(way(12)));
        assertEquals(List.of(node(2), node(3)),
            ((DetachedWay) first.primitives().get(way(11))).nodeKeys());
        assertEquals(first.canonicalHash(), second.canonicalHash());
    }

    @Test
    void captureIsDetachedImmutableAndLeavesDatasetUntouched() throws InterruptedException {
        Chain chain = chain();
        List<String> beforeState = liveState(chain.dataSet());
        List<Long> beforeNodes = chain.bc().getNodeIds();
        double beforeLongitude = chain.c().lon();
        boolean beforeModified = chain.c().isModified();

        NetworkSnapshot snapshot = onEdt(() -> NetworkSnapshotCapture.capture(
            chain.dataSet(), chain.specification("detached")));

        assertEquals(beforeNodes, chain.bc().getNodeIds());
        assertEquals(beforeLongitude, chain.c().lon());
        assertEquals(beforeModified, chain.c().isModified());
        assertEquals(beforeState, liveState(chain.dataSet()));
        chain.c().setCoor(new LatLon(0.0002, 0.0002));
        chain.c().put("changed", "later");
        DetachedNode captured = (DetachedNode) snapshot.primitives().get(node(3));
        assertEquals(new GeographicPoint(0, 0.001), captured.coordinate());
        assertTrue(captured.tags().isEmpty());
        AtomicReference<Throwable> offThreadFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                snapshot.canonicalHash();
            } catch (Throwable throwable) {
                offThreadFailure.set(throwable);
            }
        }, "detached-network-consumer");
        worker.start();
        worker.join(5_000);
        assertFalse(worker.isAlive());
        assertNull(offThreadFailure.get());
    }

    @Test
    void decisionRelevantNodeCapturesFullOrderedRelationPayload() {
        DataSet dataSet = new DataSet();
        Node a = loadedNode(1, 0, -0.0001);
        Node b = loadedNode(2, 0, 0);
        Node c = loadedNode(3, 0, 0.0001);
        add(dataSet, a, b, c);
        Way selected = loadedWay(10, List.of(a, b, c));
        dataSet.addPrimitive(selected);
        Relation route = new Relation();
        route.setMembers(List.of(new RelationMember("forward", selected),
            new RelationMember("stop", b)));
        route.put("type", "route");
        route.setOsmId(20, 1);
        route.setModified(false);
        dataSet.addPrimitive(route);
        NetworkSnapshotCapture.Specification specification = specification("relation", selected,
            new OccurrenceRange(0, 2), Map.of(way(10), List.of(new OccurrenceRange(0, 2))),
            Set.of(way(10), node(2)), Set.of(node(2)), Set.of(), Set.of(), false,
            new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.REATTACH, true));

        NetworkSnapshot snapshot = onEdt(() -> NetworkSnapshotCapture.capture(dataSet, specification));

        DetachedRelation captured = (DetachedRelation) snapshot.primitives().get(relation(20));
        assertEquals(List.of(new DetachedRelationMember(way(10), "forward"),
            new DetachedRelationMember(node(2), "stop")), captured.members());
        assertEquals(Set.of(way(10), relation(20)), snapshot.incomingReferrerWatches().get(node(2)));
    }

    @Test
    void spatialQueryIncludesOutsideEndpointCrossingExcludesRemoteAndBuildsExactPorts() {
        DataSet dataSet = new DataSet();
        Node outsideBefore = loadedNode(1, 0, -0.002);
        Node first = loadedNode(2, 0, -0.00005);
        Node last = loadedNode(3, 0, 0.00005);
        Node outsideAfter = loadedNode(4, 0, 0.002);
        Node crossA = loadedNode(5, 0, -0.001);
        Node crossB = loadedNode(6, 0, 0.001);
        Node remoteA = loadedNode(7, 0.005, 0.005);
        Node remoteB = loadedNode(8, 0.006, 0.006);
        add(dataSet, outsideBefore, first, last, outsideAfter, crossA, crossB, remoteA, remoteB);
        Way selected = loadedWay(10, List.of(outsideBefore, first, last, outsideAfter));
        Way crossing = loadedWay(20, List.of(crossA, crossB));
        Way remote = loadedWay(30, List.of(remoteA, remoteB));
        add(dataSet, selected, crossing, remote);
        NetworkSnapshotCapture.Specification specification = specification("spatial", selected,
            new OccurrenceRange(1, 2), Map.of(way(10), List.of(new OccurrenceRange(1, 2))),
            Set.of(way(10)), Set.of(), Set.of(), Set.of(node(2), node(3)), false,
            RecoveryPermissions.disabled(7.01));

        NetworkSnapshot snapshot = onEdt(() -> NetworkSnapshotCapture.capture(dataSet, specification));

        assertTrue(snapshot.primitives().containsKey(way(20)));
        assertFalse(snapshot.primitives().containsKey(way(30)));
        assertEquals(List.of(
            new ExternalPort(way(10), node(2), node(1), 1, ExternalPort.Side.BEFORE,
                new GeographicPoint(0, -0.002)),
            new ExternalPort(way(10), node(3), node(4), 2, ExternalPort.Side.AFTER,
                new GeographicPoint(0, 0.002))), snapshot.closure().externalPorts());
        assertFalse(snapshot.closure().editableExistingKeys().contains(way(20)));
    }

    @Test
    void repeatedSelectionTaggedRemovalAndIncompleteNecessaryMemberFailClosed() {
        DataSet repeatedData = new DataSet();
        Node a = loadedNode(1, 0, 0);
        Node b = loadedNode(2, 0, 0.0001);
        add(repeatedData, a, b);
        Way repeated = loadedWay(10, List.of(a, b, a));
        repeatedData.addPrimitive(repeated);
        NetworkSnapshotCapture.Specification repeatedSpec = specification("repeated", repeated,
            new OccurrenceRange(0, 1), Map.of(way(10), List.of(new OccurrenceRange(0, 1))),
            Set.of(way(10)), Set.of(), Set.of(), Set.of(), false,
            RecoveryPermissions.disabled(7.01));
        assertThrows(IllegalStateException.class,
            () -> onEdt(() -> NetworkSnapshotCapture.capture(repeatedData, repeatedSpec)));

        Chain chain = chain();
        chain.b().put("name", "protected");
        NetworkSnapshotCapture.Specification removal = specification("tagged-removal", chain.ab(),
            new OccurrenceRange(0, 1), Map.of(way(10), List.of(new OccurrenceRange(0, 1))),
            Set.of(way(10), node(2)), Set.of(), Set.of(node(2)), Set.of(), false,
            new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.REATTACH, true));
        assertThrows(IllegalStateException.class,
            () -> onEdt(() -> NetworkSnapshotCapture.capture(chain.dataSet(), removal)));

        Node incomplete = new Node(99);
        chain.dataSet().addPrimitive(incomplete);
        Relation relation = new Relation();
        relation.setMembers(List.of(new RelationMember("missing", incomplete),
            new RelationMember("via", chain.b())));
        relation.setOsmId(40, 1);
        relation.setModified(false);
        chain.dataSet().addPrimitive(relation);
        NetworkSnapshotCapture.Specification movable = specification("incomplete", chain.ab(),
            new OccurrenceRange(0, 1), Map.of(way(10), List.of(new OccurrenceRange(0, 1))),
            Set.of(way(10), node(2)), Set.of(node(2)), Set.of(), Set.of(), false,
            new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.REATTACH, true));
        assertThrows(IllegalStateException.class,
            () -> onEdt(() -> NetworkSnapshotCapture.capture(chain.dataSet(), movable)));
    }

    @Test
    void taggedMovementRequiresUnsupportedFeatureDecisionWhileUntaggedMovementRemainsAllowed() {
        DataSet dataSet = new DataSet();
        Node a = loadedNode(1, 0, -0.0001);
        Node b = loadedNode(2, 0, 0);
        Node c = loadedNode(3, 0, 0.0001);
        add(dataSet, a, b, c);
        Way selected = loadedWay(10, List.of(a, b, c));
        dataSet.addPrimitive(selected);
        NetworkSnapshotCapture.Specification specification = specification("tagged-movement", selected,
            new OccurrenceRange(0, 2), Map.of(way(10), List.of(new OccurrenceRange(0, 2))),
            Set.of(way(10), node(2)), Set.of(node(2)), Set.of(), Set.of(node(1), node(3)), false,
            RecoveryPermissions.disabled(7.01));
        b.put("highway", "traffic_signals");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> onEdt(() -> NetworkSnapshotCapture.capture(dataSet, specification)));

        assertTrue(failure.getMessage().contains("tagged"));
        b.remove("highway");
        assertDoesNotThrow(() -> onEdt(() -> NetworkSnapshotCapture.capture(dataSet, specification)));
    }

    @Test
    void frozenReceivingWayNeedsReattachmentButIncidentShapeMovementNeedsReconstruction() {
        DataSet dataSet = new DataSet();
        Node a = loadedNode(1, 0, -0.0001);
        Node b = loadedNode(2, 0, 0);
        Node c = loadedNode(3, 0, 0.0001);
        Node shape = loadedNode(4, 0.0001, 0);
        Node d = loadedNode(5, 0.0002, 0);
        add(dataSet, a, b, c, shape, d);
        Way selected = loadedWay(10, List.of(a, b, c));
        Way receiving = loadedWay(11, List.of(b, shape, d));
        add(dataSet, selected, receiving);
        Map<PrimitiveKey, List<OccurrenceRange>> occurrences = Map.of(
            way(10), List.of(new OccurrenceRange(0, 2)),
            way(11), List.of(new OccurrenceRange(0, 2)));
        RecoveryPermissions reattachOnly = new RecoveryPermissions(false, 7.01, 7.01,
            JunctionPolicy.REATTACH, false);
        NetworkSnapshotCapture.Specification frozenReceiver = specification("frozen-receiver", selected,
            new OccurrenceRange(0, 2), occurrences, Set.of(way(10), way(11), node(2)),
            Set.of(node(2)), Set.of(), Set.of(node(1), node(3), node(4), node(5)), false,
            reattachOnly);

        NetworkSnapshot snapshot = assertDoesNotThrow(
            () -> onEdt(() -> NetworkSnapshotCapture.capture(dataSet, frozenReceiver)));

        assertEquals(occurrences, snapshot.closure().editableWayOccurrences());
        NetworkSnapshotCapture.Specification movingShape = specification("moving-shape", selected,
            new OccurrenceRange(0, 2), occurrences, Set.of(way(10), way(11), node(4)),
            Set.of(node(4)), Set.of(), Set.of(node(1), node(2), node(3), node(5)), false,
            reattachOnly);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> onEdt(() -> NetworkSnapshotCapture.capture(dataSet, movingShape)));
        assertTrue(failure.getMessage().contains("reconstruction"));
    }

    @Test
    void completeDeepNecessaryRelationPayloadIsMaterializedWithoutRecursiveTraversal() {
        DataSet smallData = new DataSet();
        Node smallA = loadedNode(1, 0, 0);
        Node smallB = loadedNode(2, 0, 0.00001);
        add(smallData, smallA, smallB);
        Way smallSelected = loadedWay(10, List.of(smallA, smallB));
        smallData.addPrimitive(smallSelected);
        addNestedRelations(smallData, smallSelected, smallA, 3, 100);
        NetworkSnapshot small = assertDoesNotThrow(() -> onEdt(() -> NetworkSnapshotCapture.capture(
            smallData, specification("small-relations", smallSelected, new OccurrenceRange(0, 1),
                Map.of(way(10), List.of(new OccurrenceRange(0, 1))), Set.of(way(10)), Set.of(),
                Set.of(), Set.of(node(1), node(2)), false, RecoveryPermissions.disabled(7.01)))));
        assertTrue(small.primitives().containsKey(relation(102)));

        DataSet deepData = new DataSet();
        Node deepA = loadedNode(1, 0, 0);
        Node deepB = loadedNode(2, 0, 0.00001);
        add(deepData, deepA, deepB);
        Way deepSelected = loadedWay(10, List.of(deepA, deepB));
        deepData.addPrimitive(deepSelected);
        addNestedRelations(deepData, deepSelected, deepA, 20_000, 1_000);
        NetworkSnapshot deep = assertDoesNotThrow(() -> onEdt(() -> NetworkSnapshotCapture.capture(
            deepData, specification("deep-relations", deepSelected, new OccurrenceRange(0, 1),
                Map.of(way(10), List.of(new OccurrenceRange(0, 1))), Set.of(way(10)), Set.of(),
                Set.of(), Set.of(node(1), node(2)), false, RecoveryPermissions.disabled(7.01)))));
        assertTrue(deep.primitives().containsKey(relation(20_999)));
    }

    @Test
    void explicitPrimitiveBudgetRejectsBeforePublication() {
        Chain chain = chain();
        NetworkSnapshotCapture.Limits tiny = new NetworkSnapshotCapture.Limits(2, 100,
            100, 100, 100, 100);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> onEdt(() -> NetworkSnapshotCapture.capture(
                chain.dataSet(), chain.specification("budget"), tiny)));

        assertTrue(failure.getMessage().contains("primitive budget"));

        NetworkSnapshotCapture.Limits tinyPayload = new NetworkSnapshotCapture.Limits(100, 100,
            100, 1, 100, 100);
        IllegalStateException payloadFailure = assertThrows(IllegalStateException.class,
            () -> onEdt(() -> NetworkSnapshotCapture.capture(
                chain.dataSet(), chain.specification("payload-budget"), tinyPayload)));
        assertTrue(payloadFailure.getMessage().contains("materialized reference budget"));
    }

    private static Chain chain() {
        DataSet dataSet = new DataSet();
        Node a = loadedNode(1, 0, 0);
        Node b = loadedNode(2, 0, 0.00001);
        Node c = loadedNode(3, 0, 0.001);
        Node d = loadedNode(4, 0, 0.002);
        Node e = loadedNode(5, 0, 0.003);
        add(dataSet, a, b, c, d, e);
        Way ab = loadedWay(10, List.of(a, b));
        Way bc = loadedWay(11, List.of(b, c));
        Way cd = loadedWay(12, List.of(c, d));
        Way de = loadedWay(13, List.of(d, e));
        add(dataSet, ab, bc, cd, de);
        return new Chain(dataSet, a, b, c, ab, bc);
    }

    private static void addNestedRelations(DataSet dataSet, Way selected, Node leaf,
        int depth, long firstId) {
        Relation child = null;
        for (int offset = depth - 1; offset >= 0; offset--) {
            Relation relation = new Relation();
            if (child == null) {
                relation.setMembers(List.of(new RelationMember("leaf", leaf)));
            } else if (offset == 0) {
                relation.setMembers(List.of(new RelationMember("selected", selected),
                    new RelationMember("child", child)));
            } else {
                relation.setMembers(List.of(new RelationMember("child", child)));
            }
            relation.setOsmId(firstId + offset, 1);
            relation.setModified(false);
            dataSet.addPrimitive(relation);
            child = relation;
        }
    }

    private static NetworkSnapshotCapture.Specification specification(String id, Way selected,
        OccurrenceRange range, Map<PrimitiveKey, List<OccurrenceRange>> occurrences,
        Set<PrimitiveKey> editable, Set<PrimitiveKey> movable, Set<PrimitiveKey> removable,
        Set<PrimitiveKey> protectedNodes, boolean mayCreateNodes, RecoveryPermissions permissions) {
        return new NetworkSnapshotCapture.Specification(id, "dataset", 7, key(selected), range,
            FRAME, COLLISION, EDIT, occurrences, editable, movable, removable,
            protectedNodes, mayCreateNodes, permissions);
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

    private static void add(DataSet dataSet, org.openstreetmap.josm.data.osm.OsmPrimitive... primitives) {
        for (org.openstreetmap.josm.data.osm.OsmPrimitive primitive : primitives) {
            dataSet.addPrimitive(primitive);
        }
    }

    private static PrimitiveKey key(org.openstreetmap.josm.data.osm.OsmPrimitive primitive) {
        PrimitiveKey.Type type = primitive instanceof Node ? PrimitiveKey.Type.NODE
            : primitive instanceof Way ? PrimitiveKey.Type.WAY : PrimitiveKey.Type.RELATION;
        return PrimitiveKey.existing(type, primitive.getUniqueId());
    }

    private static PrimitiveKey node(long id) {
        return PrimitiveKey.existing(PrimitiveKey.Type.NODE, id);
    }

    private static PrimitiveKey way(long id) {
        return PrimitiveKey.existing(PrimitiveKey.Type.WAY, id);
    }

    private static PrimitiveKey relation(long id) {
        return PrimitiveKey.existing(PrimitiveKey.Type.RELATION, id);
    }

    private static List<String> liveState(DataSet dataSet) {
        return dataSet.allPrimitives().stream().sorted((left, right) -> key(left).compareTo(key(right)))
            .map(primitive -> {
                String payload = primitive instanceof Node node
                    ? Double.toHexString(node.lat()) + "," + Double.toHexString(node.lon())
                    : primitive instanceof Way way ? way.getNodeIds().toString()
                        : ((Relation) primitive).getMembers().stream()
                            .map(member -> member.getRole() + ":" + key(member.getMember())).toList().toString();
                return key(primitive) + "|" + payload + "|" + primitive.getKeys()
                    + "|" + primitive.isModified() + "|" + primitive.isDeleted()
                    + "|" + primitive.getReferrers().stream().map(V022NetworkSnapshotCaptureTest::key)
                        .sorted().toList();
            }).toList();
    }

    private static <T> T onEdt(ThrowingSupplier<T> operation) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(operation.get());
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException(exception.getCause());
        }
        if (failure.get() instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure.get() != null) {
            throw new IllegalStateException(failure.get());
        }
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }

    private record Chain(DataSet dataSet, Node a, Node b, Node c, Way ab, Way bc) {
        NetworkSnapshotCapture.Specification specification(String id) {
            return V022NetworkSnapshotCaptureTest.specification(id, ab, new OccurrenceRange(0, 1),
                Map.of(way(10), List.of(new OccurrenceRange(0, 1))), Set.of(way(10)),
                Set.of(), Set.of(), Set.of(), false, RecoveryPermissions.disabled(7.01));
        }
    }
}

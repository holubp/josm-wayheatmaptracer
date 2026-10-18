package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SharedNodeDisconnectionIntent;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class V022SharedNodeDisconnectionEligibilityTest {
    private static final OccurrenceRange RANGE = new OccurrenceRange(0, 3);

    @org.junit.jupiter.api.BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void multipleIndependentSharedOccurrencesAreEligible() {
        NetworkSnapshot source = source(false, false, false, false, false);
        var result = evaluate(source, Map.of(1, node(2), 2, node(3)));

        assertTrue(result.decisions().get(1).eligible());
        assertTrue(result.decisions().get(2).eligible());
        assertEquals(2, result.decisions().size());
    }

    @Test
    void taggedSharedNodeRequiresManualHandling() {
        var result = evaluate(source(true, false, false, false, false), Map.of(1, node(2)));
        assertFalse(result.decisions().get(1).eligible());
        assertEquals(SharedNodeDisconnectionEligibility.Reason.TAGGED_NODE,
                result.decisions().get(1).reason());
    }

    @Test
    void nodeAndParticipatingWayRelationsRequireManualHandling() {
        var nodeRelation = evaluate(source(false, true, false, false, false), Map.of(1, node(2)));
        assertEquals(SharedNodeDisconnectionEligibility.Reason.NODE_RELATION_MEMBERSHIP,
                nodeRelation.decisions().get(1).reason());

        var wayRelation = evaluate(source(false, false, true, false, false), Map.of(1, node(2)));
        assertEquals(SharedNodeDisconnectionEligibility.Reason.PARTICIPATING_WAY_RELATION,
                wayRelation.decisions().get(1).reason());
    }

    @Test
    void repeatedNodeAndOutOfRangeOccurrencesRequireManualHandling() {
        NetworkSnapshot repeated = source(false, false, false, true, false);
        var repeatedResult = evaluate(repeated, Map.of(1, node(2)));
        assertEquals(SharedNodeDisconnectionEligibility.Reason.REPEATED_OCCURRENCE,
                repeatedResult.decisions().get(1).reason());

        NetworkSnapshot ordinary = source(false, false, false, false, false);
        var outOfRange = evaluate(ordinary, Map.of(4, node(4)));
        assertEquals(SharedNodeDisconnectionEligibility.Reason.OUTSIDE_SELECTED_RANGE,
                outOfRange.decisions().get(4).reason());
    }

    @Test
    void mismatchedOriginalOccurrenceAndSourceIdentityFailClosed() {
        NetworkSnapshot source = source(false, false, false, false, false);
        SharedNodeDisconnectionIntent wrongNode = intent(source, Map.of(1, node(1)));
        assertEquals(SharedNodeDisconnectionEligibility.Reason.OCCURRENCE_IDENTITY_MISMATCH,
                SharedNodeDisconnectionEligibility.evaluate(source, wrongNode).decisions().get(1).reason());

        SharedNodeDisconnectionIntent stale = new SharedNodeDisconnectionIntent(way(10), RANGE,
                Map.of(1, node(2)), "stale-network");
        assertEquals(SharedNodeDisconnectionEligibility.Reason.SOURCE_MISMATCH,
                SharedNodeDisconnectionEligibility.evaluate(source, stale).decisions().get(1).reason());
    }

    @Test
    void unknownReferrerRequiresManualHandlingButCompleteMultiwayNodeCanDisconnect() {
        NetworkSnapshot complete = source(false, false, false, false, false);
        Map<PrimitiveKey, Set<PrimitiveKey>> incompleteWatches = new LinkedHashMap<>(
                complete.incomingReferrerWatches());
        incompleteWatches.put(node(2), Set.of(way(10), way(11), way(99)));
        NetworkSnapshot incomplete = new NetworkSnapshot(complete.snapshotId(), complete.role(),
                complete.datasetIdentity(), complete.sourceGeneration(), complete.closure(),
                complete.primitives(), incompleteWatches);
        assertEquals(SharedNodeDisconnectionEligibility.Reason.INCOMPLETE_SOURCE,
                evaluate(incomplete, Map.of(1, node(2))).decisions().get(1).reason());

        NetworkSnapshot ambiguous = source(false, false, false, false, true);
        assertEquals(SharedNodeDisconnectionEligibility.Reason.ELIGIBLE,
                evaluate(ambiguous, Map.of(1, node(2))).decisions().get(1).reason());
        NetworkSnapshot relation = source(false, false, true, false, true);
        assertEquals(SharedNodeDisconnectionEligibility.Reason.PARTICIPATING_WAY_RELATION,
                evaluate(relation, Map.of(1, node(2))).decisions().get(1).reason());
    }

    @Test
    void legacyPermissionConstructorDefaultsToEmptyIntent() {
        assertTrue(RecoveryPermissions.disabled(7.0).sharedNodeDisconnectionIntent().isEmpty());
    }

    @Test
    void explicitIntentIsPartOfPermissionIdentity() {
        NetworkSnapshot source = source(false, false, false, false, false);
        Map<Integer, PrimitiveKey> ordered = new LinkedHashMap<>();
        ordered.put(1, node(2));
        ordered.put(2, node(3));
        Map<Integer, PrimitiveKey> reversed = new LinkedHashMap<>();
        reversed.put(2, node(3));
        reversed.put(1, node(2));
        SharedNodeDisconnectionIntent intent = intent(source, ordered);
        SharedNodeDisconnectionIntent sameIntent = intent(source, reversed);
        RecoveryPermissions without = RecoveryPermissions.disabled(7.0);
        RecoveryPermissions with = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.FIXED, false, intent);
        assertNotEquals(without, with);
        assertEquals(intent, with.sharedNodeDisconnectionIntent());
        assertEquals(intent.canonicalHash(), sameIntent.canonicalHash());
        assertEquals(with.canonicalIdentity(), new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.FIXED, false, sameIntent).canonicalIdentity());
    }

    @Test
    void captureBindsIntentToOriginalFactualSnapshotAndProtectsOriginalNode() throws Exception {
        CaptureFixture fixture = captureFixture();
        var original = onEdt(() -> NetworkSnapshotCapture.capture(fixture.data(), fixture.specification(
                RecoveryPermissions.disabled(7.0))));
        SharedNodeDisconnectionIntent intent = new SharedNodeDisconnectionIntent(way(30),
                fixture.range(), Map.of(1, node(31)), original.canonicalHash());

        var bound = onEdt(() -> NetworkSnapshotCapture.capture(fixture.data(),
                fixture.specification(new RecoveryPermissions(false, 7.0, 7.0,
                        JunctionPolicy.FIXED, false, intent))));

        assertEquals(original.canonicalHash(), bound.canonicalHash());
        assertTrue(bound.closure().protectedExistingNodeKeys().contains(node(31)));
        assertTrue(fixture.shared().getReferrers().contains(fixture.other()));
        assertFalse(fixture.shared().isModified());
    }

    @Test
    void captureRejectsSourceHashAndOccurrenceIdentityThatDoNotMatchCurrentData() throws Exception {
        CaptureFixture fixture = captureFixture();
        SharedNodeDisconnectionIntent stale = new SharedNodeDisconnectionIntent(way(30), fixture.range(),
                Map.of(1, node(31)), "caller-supplied-unverified-source");
        assertThrows(IllegalStateException.class, () -> onEdt(() -> NetworkSnapshotCapture.capture(
                fixture.data(), fixture.specification(new RecoveryPermissions(false, 7.0, 7.0,
                        JunctionPolicy.FIXED, false, stale)))));

        var original = onEdt(() -> NetworkSnapshotCapture.capture(fixture.data(), fixture.specification(
                RecoveryPermissions.disabled(7.0))));
        SharedNodeDisconnectionIntent wrongNode = new SharedNodeDisconnectionIntent(way(30), fixture.range(),
                Map.of(1, node(32)), original.canonicalHash());
        assertThrows(IllegalStateException.class, () -> onEdt(() -> NetworkSnapshotCapture.capture(
                fixture.data(), fixture.specification(new RecoveryPermissions(false, 7.0, 7.0,
                        JunctionPolicy.FIXED, false, wrongNode)))));
    }

    private static SharedNodeDisconnectionEligibility.Result evaluate(NetworkSnapshot source,
            Map<Integer, PrimitiveKey> requested) {
        return SharedNodeDisconnectionEligibility.evaluate(source, intent(source, requested));
    }

    private static SharedNodeDisconnectionIntent intent(NetworkSnapshot source,
            Map<Integer, PrimitiveKey> requested) {
        return new SharedNodeDisconnectionIntent(way(10), RANGE, requested, source.canonicalHash());
    }

    private static NetworkSnapshot source(boolean taggedNode, boolean nodeRelation,
            boolean wayRelation, boolean repeatedSelectedOccurrence, boolean extraWay) {
        PrimitiveKey selected = way(10);
        PrimitiveKey receiverA = way(11);
        PrimitiveKey receiverB = way(12);
        List<PrimitiveKey> selectedNodes = repeatedSelectedOccurrence
                ? List.of(node(1), node(2), node(3), node(2))
                : List.of(node(1), node(2), node(3), node(4));
        Map<PrimitiveKey, DetachedPrimitive> primitives = new LinkedHashMap<>();
        for (int id = 1; id <= 4; id++) {
            Map<String, String> tags = taggedNode && id == 2 ? Map.of("barrier", "gate") : Map.of();
            primitives.put(node(id), new DetachedNode(node(id), new GeographicPoint(0, id * 0.0001), tags,
                    false, false));
        }
        primitives.put(selected, new DetachedWay(selected, selectedNodes,
                Map.of("highway", "path"), false, false));
        primitives.put(receiverA, new DetachedWay(receiverA, List.of(node(5), node(2)),
                Map.of("highway", "path"), false, false));
        primitives.put(receiverB, new DetachedWay(receiverB, List.of(node(6), node(3)),
                Map.of("highway", "path"), false, false));
        primitives.put(node(5), new DetachedNode(node(5), new GeographicPoint(0.0001, 0), Map.of(), false, false));
        primitives.put(node(6), new DetachedNode(node(6), new GeographicPoint(0.0002, 0), Map.of(), false, false));
        if (extraWay) {
            primitives.put(way(13), new DetachedWay(way(13), List.of(node(7), node(2)),
                    Map.of("highway", "path"), false, false));
            primitives.put(node(7), new DetachedNode(node(7), new GeographicPoint(0.0003, 0), Map.of(), false, false));
        }
        if (nodeRelation || wayRelation) {
            PrimitiveKey relation = relation(20);
            PrimitiveKey member = nodeRelation ? node(2) : extraWay ? way(13) : receiverA;
            primitives.put(relation, new DetachedRelation(relation,
                    List.of(new DetachedRelationMember(member, "")), Map.of("type", "route"), false, false));
        }
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = new LinkedHashMap<>();
        primitives.keySet().forEach(key -> watches.put(key, new java.util.LinkedHashSet<>()));
        primitives.values().forEach(primitive -> {
            if (primitive instanceof DetachedWay way) {
                way.nodeKeys().forEach(key -> watches.get(key).add(way.key()));
            } else if (primitive instanceof DetachedRelation relation) {
                relation.members().forEach(member -> watches.get(member.memberKey()).add(relation.key()));
            }
        });
        Set<PrimitiveKey> keys = Set.copyOf(primitives.keySet());
        Map<PrimitiveKey, List<OccurrenceRange>> ranges = Map.of(selected, List.of(RANGE));
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT, "test-v1",
                keys, Set.of(selected), Set.of(), Set.of(node(2), node(3)), Set.of(), ranges, List.of(),
                MetricRegion.rectangle(-10, -10, 10, 10), MetricRegion.rectangle(-10, -10, 10, 10),
                true, true, true, true);
        return new NetworkSnapshot("snapshot", SnapshotRole.CAPTURED_BEFORE, "dataset", 1,
                closure, primitives, watches);
    }

    private static CaptureFixture captureFixture() {
        DataSet data = new DataSet();
        Node first = liveNode(30, 0, -0.0002);
        Node shared = liveNode(31, 0, 0);
        Node selectedEnd = liveNode(32, 0, 0.0002);
        Node otherEnd = liveNode(33, 0.0002, 0);
        for (Node node : List.of(first, shared, selectedEnd, otherEnd)) data.addPrimitive(node);
        Way selected = liveWay(30, List.of(first, shared));
        Way other = liveWay(31, List.of(shared, otherEnd));
        selected.put("highway", "path");
        other.put("highway", "path");
        data.addPrimitive(selected);
        data.addPrimitive(other);
        return new CaptureFixture(data, selected, other, shared, new OccurrenceRange(0, 1));
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            if (failure.get() instanceof Exception exception) throw exception;
            throw new IllegalStateException(failure.get());
        }
        return result.get();
    }

    private static Node liveNode(long id, double latitude, double longitude) {
        Node node = new Node(new LatLon(latitude, longitude));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static Way liveWay(long id, List<Node> nodes) {
        Way way = new Way();
        way.setNodes(nodes);
        way.setOsmId(id, 1);
        way.setModified(false);
        return way;
    }

    private record CaptureFixture(DataSet data, Way selected, Way other, Node shared,
            OccurrenceRange range) {
        NetworkSnapshotCapture.Specification specification(RecoveryPermissions permissions) {
            LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(
                    new GeographicPoint(0, 0), new GeographicPoint(-0.01, -0.01),
                    new GeographicPoint(0.01, 0.01));
            return new NetworkSnapshotCapture.Specification("disconnection-capture", "dataset", 1,
                    way(30), range, frame, MetricRegion.rectangle(-100, -100, 100, 100),
                    MetricRegion.rectangle(-100, -100, 100, 100),
                    Map.of(way(30), List.of(range)), Set.of(way(30)), Set.of(), Set.of(), Set.of(),
                    true, permissions);
        }
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
}

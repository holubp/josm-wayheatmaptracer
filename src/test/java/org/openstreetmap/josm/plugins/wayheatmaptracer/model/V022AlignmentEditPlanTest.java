package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class V022AlignmentEditPlanTest {
    private static final PrimitiveKey NODE_1 = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
    private static final PrimitiveKey NODE_2 = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
    private static final PrimitiveKey WAY_1 = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 1);
    private static final PrimitiveKey RELATION_1 = PrimitiveKey.existing(PrimitiveKey.Type.RELATION, 1);
    private static final PrimitiveKey PLANNED_NODE = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 1);
    private static final PrimitiveKey OUTSIDE_NODE = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 99);
    private static final Set<PrimitiveKey> CAPTURED_KEYS = Set.of(NODE_1, NODE_2, WAY_1, RELATION_1);

    @Test
    void t099BuildingPlanFromDetachedSnapshotsHasNoMutationSideEffect() {
        NetworkSnapshot before = beforeSnapshot();
        NetworkSnapshot after = afterSnapshot();
        String beforeHash = before.canonicalHash();

        AlignmentEditPlan plan = plan(before, after, expectedPreview());

        assertEquals(beforeHash, before.canonicalHash());
        assertNotEquals(before.canonicalHash(), after.canonicalHash());
        assertNotEquals(before.canonicalHash(), plan.canonicalHash());
        assertThrows(UnsupportedOperationException.class,
            () -> ((DetachedWay) plan.after().primitives().get(WAY_1)).nodeKeys().add(NODE_2));
    }

    @Test
    void t100TypedIdentityAndStructuralDiffCoverCreateDeleteWayRelationAndPort() {
        AlignmentEditPlan plan = plan(beforeSnapshot(), afterSnapshot(), expectedPreview());

        assertEquals(Set.of(PLANNED_NODE), plan.createdPrimitives().keySet());
        assertEquals(Set.of(NODE_2), plan.removedPrimitives().keySet());
        assertEquals(Set.of(NODE_2, PLANNED_NODE, WAY_1), plan.writePrimitiveKeys());
        assertEquals(Set.of(WAY_1), plan.affectedWayKeys());
        assertFalse(NODE_1.equals(WAY_1));
        assertFalse(WAY_1.equals(RELATION_1));
        assertEquals(beforeSnapshot().closure().externalPorts(), afterSnapshot().closure().externalPorts());
    }

    @Test
    void t101UnrelatedExistingPrimitiveCannotChangeThroughBroadClosureReadAccess() {
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(afterSnapshot().primitives());
        values.put(NODE_1, new DetachedNode(NODE_1, new GeographicPoint(42.00001, 19.0), Map.of(), false, true));
        NetworkSnapshot after = snapshot("unauthorized-node", values, closure(Set.of(WAY_1, NODE_2),
            Set.of(NODE_2), MetricRegion.rectangle(-20, -20, 20, 20), standardPorts()));

        assertThrows(IllegalArgumentException.class,
            () -> plan(beforeSnapshot(), after, expectedPreview()));
    }

    @Test
    void t102ReviewedPreviewMustExactlyEqualAfterWayNodeOrderAndCoordinates() {
        Map<PrimitiveKey, List<GeographicPoint>> wrong = Map.of(WAY_1,
            List.of(new GeographicPoint(42.0, 19.0), new GeographicPoint(42.00005, 19.00005)));

        assertThrows(IllegalArgumentException.class,
            () -> plan(beforeSnapshot(), afterSnapshot(), wrong));
    }

    @Test
    void t103BeforeAndAfterClosurePortsAndGenerationMustMatch() {
        NetworkSnapshot after = new NetworkSnapshot("changed-generation", SnapshotRole.PROPOSED_AFTER, "dataset-fixture", 2L,
            afterSnapshot().closure(), afterSnapshot().primitives());

        assertThrows(IllegalArgumentException.class,
            () -> plan(beforeSnapshot(), after, expectedPreview()));
    }

    @Test
    void t104CreatedAndMovedGeometryMustRemainInsideCertifiedEditRegion() {
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(afterSnapshot().primitives());
        values.put(PLANNED_NODE, new DetachedNode(PLANNED_NODE,
            new GeographicPoint(42.0005, 19.0005), Map.of(), false, true));
        NetworkSnapshot after = snapshot("outside-region", values, standardClosure());

        assertThrows(IllegalArgumentException.class,
            () -> plan(beforeSnapshot(), after, Map.of(WAY_1, List.of(
                new GeographicPoint(42.0, 19.0), new GeographicPoint(42.0005, 19.0005)))));
    }

    @Test
    void t105RelationTagsRolesAndOrderCannotBeSilentlyRewritten() {
        ClosureDescriptor relationEditable = closure(Set.of(WAY_1, NODE_2, RELATION_1), Set.of(NODE_2),
            MetricRegion.rectangle(-20, -20, 20, 20), standardPorts());
        NetworkSnapshot before = snapshot("before-relation", beforePrimitives(), relationEditable);
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(afterPrimitives());
        values.put(RELATION_1, new DetachedRelation(RELATION_1,
            List.of(new DetachedRelationMember(WAY_1, "backward")), Map.of("type", "route"), false, true));
        NetworkSnapshot after = snapshot("after-relation", values, relationEditable);

        assertThrows(IllegalArgumentException.class, () -> plan(before, after, expectedPreview()));
    }

    @Test
    void t106NodeDeletionRequiresExplicitSafeRemovalAuthority() {
        ClosureDescriptor noRemoval = closure(Set.of(WAY_1, NODE_2), Set.of(),
            MetricRegion.rectangle(-20, -20, 20, 20), standardPorts());
        NetworkSnapshot before = snapshot("before-no-removal", beforePrimitives(), noRemoval);

        assertThrows(IllegalArgumentException.class,
            () -> snapshot("after-no-removal", afterPrimitives(), noRemoval));
        assertEquals(SnapshotRole.CAPTURED_BEFORE, before.role());
    }

    private static AlignmentEditPlan plan(NetworkSnapshot before, NetworkSnapshot after,
        Map<PrimitiveKey, List<GeographicPoint>> preview) {
        GeographicPoint origin = new GeographicPoint(42.0, 19.0);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        return new AlignmentEditPlan(WAY_1, new OccurrenceRange(0, 1), before, after, frame,
            new RecoveryPermissions(true, 7.01, 20.0, JunctionPolicy.REATTACH, true),
            "settings-hash", "evidence-hash", "parameter-hash", "route-a", preview,
            new ValidationReport(ValidationReport.Disposition.REVIEW_REQUIRED,
                List.of("network-review-required")));
    }

    private static Map<PrimitiveKey, List<GeographicPoint>> expectedPreview() {
        return Map.of(WAY_1, List.of(new GeographicPoint(42.0, 19.0),
            new GeographicPoint(42.0001, 19.0001)));
    }

    private static NetworkSnapshot beforeSnapshot() {
        return snapshot("before", beforePrimitives(), standardClosure());
    }

    private static NetworkSnapshot afterSnapshot() {
        return snapshot("after", afterPrimitives(), standardClosure());
    }

    private static Map<PrimitiveKey, DetachedPrimitive> beforePrimitives() {
        Map<PrimitiveKey, DetachedPrimitive> primitives = new LinkedHashMap<>();
        primitives.put(NODE_1, new DetachedNode(NODE_1, new GeographicPoint(42.0, 19.0), Map.of(), false, false));
        primitives.put(NODE_2, new DetachedNode(NODE_2, new GeographicPoint(42.0001, 19.0001),
            Map.of(), false, false));
        primitives.put(WAY_1, new DetachedWay(WAY_1, List.of(NODE_1, NODE_2),
            Map.of("highway", "path"), false, false));
        primitives.put(RELATION_1, new DetachedRelation(RELATION_1,
            List.of(new DetachedRelationMember(WAY_1, "forward")), Map.of("type", "route"), false, false));
        return primitives;
    }

    private static Map<PrimitiveKey, DetachedPrimitive> afterPrimitives() {
        Map<PrimitiveKey, DetachedPrimitive> primitives = new LinkedHashMap<>();
        primitives.put(NODE_1, new DetachedNode(NODE_1, new GeographicPoint(42.0, 19.0), Map.of(), false, false));
        primitives.put(PLANNED_NODE, new DetachedNode(PLANNED_NODE,
            new GeographicPoint(42.0001, 19.0001), Map.of(), false, true));
        primitives.put(WAY_1, new DetachedWay(WAY_1, List.of(NODE_1, PLANNED_NODE),
            Map.of("highway", "path"), false, true));
        primitives.put(RELATION_1, new DetachedRelation(RELATION_1,
            List.of(new DetachedRelationMember(WAY_1, "forward")), Map.of("type", "route"), false, false));
        return primitives;
    }

    private static NetworkSnapshot snapshot(String id, Map<PrimitiveKey, DetachedPrimitive> primitives,
        ClosureDescriptor closure) {
        return new NetworkSnapshot(id, id.startsWith("before") ? SnapshotRole.CAPTURED_BEFORE : SnapshotRole.PROPOSED_AFTER,
            "dataset-fixture", 1L, closure, primitives);
    }

    private static ClosureDescriptor standardClosure() {
        return closure(Set.of(WAY_1, NODE_2), Set.of(NODE_2),
            MetricRegion.rectangle(-20, -20, 20, 20), standardPorts());
    }

    private static ClosureDescriptor closure(Set<PrimitiveKey> editable, Set<PrimitiveKey> removable,
        MetricRegion editRegion, List<ExternalPort> ports) {
        return new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
            "closure-query-v1", CAPTURED_KEYS, editable, Set.of(), Set.of(NODE_1), removable,
            Map.of(WAY_1, List.of(new OccurrenceRange(0, 1))), ports,
            MetricRegion.rectangle(-30, -30, 30, 30), editRegion,
            true, true, true, true);
    }

    private static List<ExternalPort> standardPorts() {
        return List.of(new ExternalPort(WAY_1, NODE_1, OUTSIDE_NODE, 0, ExternalPort.Side.BEFORE,
            new GeographicPoint(41.9999, 18.9999)));
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class V022ReferrerWatchEditPlanTest {
    private static final PrimitiveKey A = node(1);
    private static final PrimitiveKey B = node(2);
    private static final PrimitiveKey C = node(3);
    private static final PrimitiveKey D = node(4);
    private static final PrimitiveKey AB = way(10);
    private static final PrimitiveKey BC = way(11);
    private static final PrimitiveKey CD = way(12);
    private static final PrimitiveKey ROUTE = relation(20);
    private static final PrimitiveKey EXTERNAL_ROUTE = relation(21);
    private static final PrimitiveKey PLANNED = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 1);
    private static final GeographicPoint MOVED_C = new GeographicPoint(0.0, 0.0021);

    @Test
    void movingNodeWithIdentityOnlyIncidentWayIsBlocked() {
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = fivePayloadChain();
        Map<PrimitiveKey, DetachedPrimitive> afterValues = movedC(beforeValues);
        Map<PrimitiveKey, Set<PrimitiveKey>> beforeWatches = mutableWatches(beforeValues);
        beforeWatches.get(C).add(CD);
        Map<PrimitiveKey, Set<PrimitiveKey>> afterWatches = mutableWatches(afterValues);
        afterWatches.get(C).add(CD);
        ClosureDescriptor closure = closure(beforeValues.keySet(), Set.of(C, BC), Set.of(C), Set.of(),
            Map.of(BC, List.of(new OccurrenceRange(0, 1))), false);

        NetworkSnapshot before = snapshot("before-identity-way", SnapshotRole.CAPTURED_BEFORE,
            closure, beforeValues, beforeWatches);
        NetworkSnapshot after = snapshot("after-identity-way", SnapshotRole.PROPOSED_AFTER,
            closure, afterValues, afterWatches);

        assertThrows(IllegalArgumentException.class,
            () -> plan(before, after, Map.of(BC, coordinates(after, BC))));
    }

    @Test
    void movingNodeWithIdentityOnlyRelationOnAffectedWayIsBlocked() {
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = completeIncidentValues(false);
        Map<PrimitiveKey, DetachedPrimitive> afterValues = movedC(beforeValues);
        Map<PrimitiveKey, Set<PrimitiveKey>> beforeWatches = mutableWatches(beforeValues);
        beforeWatches.get(BC).add(EXTERNAL_ROUTE);
        Map<PrimitiveKey, Set<PrimitiveKey>> afterWatches = mutableWatches(afterValues);
        afterWatches.get(BC).add(EXTERNAL_ROUTE);
        ClosureDescriptor closure = completeClosure(beforeValues.keySet());

        NetworkSnapshot before = snapshot("before-identity-relation", SnapshotRole.CAPTURED_BEFORE,
            closure, beforeValues, beforeWatches);
        NetworkSnapshot after = snapshot("after-identity-relation", SnapshotRole.PROPOSED_AFTER,
            closure, afterValues, afterWatches);

        assertThrows(IllegalArgumentException.class, () -> plan(before, after,
            Map.of(BC, coordinates(after, BC), CD, coordinates(after, CD))));
    }

    @Test
    void fixedPolicyCannotDropRetainedNodeWithIdentityOnlyIncidentWay() {
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = incidenceValues(List.of(A, B, C));
        Map<PrimitiveKey, DetachedPrimitive> afterValues = incidenceValues(List.of(A, C));
        Map<PrimitiveKey, Set<PrimitiveKey>> beforeWatches = mutableWatches(beforeValues);
        beforeWatches.get(B).add(CD);
        Map<PrimitiveKey, Set<PrimitiveKey>> afterWatches = mutableWatches(afterValues);
        afterWatches.get(B).add(CD);
        ClosureDescriptor closure = closure(beforeValues.keySet(), Set.of(B, BC), Set.of(B), Set.of(),
            Map.of(BC, List.of(new OccurrenceRange(0, 2))), false);
        NetworkSnapshot before = snapshot("before-drop-incidence", SnapshotRole.CAPTURED_BEFORE,
            closure, beforeValues, beforeWatches);
        NetworkSnapshot after = snapshot("after-drop-incidence", SnapshotRole.PROPOSED_AFTER,
            closure, afterValues, afterWatches);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> fixedPlan(before, after, new OccurrenceRange(0, 2),
                Map.of(BC, coordinates(after, BC))));
        assertTrue(failure.getMessage().contains("identity-only"), failure::getMessage);
    }

    @Test
    void fixedPolicyCannotReorderRetainedNodeWithIdentityOnlyIncidentWay() {
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = incidenceValues(List.of(A, B, C, D));
        Map<PrimitiveKey, DetachedPrimitive> afterValues = incidenceValues(List.of(A, C, B, D));
        Map<PrimitiveKey, Set<PrimitiveKey>> beforeWatches = mutableWatches(beforeValues);
        beforeWatches.get(B).add(CD);
        Map<PrimitiveKey, Set<PrimitiveKey>> afterWatches = mutableWatches(afterValues);
        afterWatches.get(B).add(CD);
        ClosureDescriptor closure = closure(beforeValues.keySet(), Set.of(B, C, BC), Set.of(B, C), Set.of(),
            Map.of(BC, List.of(new OccurrenceRange(0, 3))), false);
        NetworkSnapshot before = snapshot("before-reorder-incidence", SnapshotRole.CAPTURED_BEFORE,
            closure, beforeValues, beforeWatches);
        NetworkSnapshot after = snapshot("after-reorder-incidence", SnapshotRole.PROPOSED_AFTER,
            closure, afterValues, afterWatches);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> fixedPlan(before, after, new OccurrenceRange(0, 3),
                Map.of(BC, coordinates(after, BC))));
        assertTrue(failure.getMessage().contains("identity-only"), failure::getMessage);
    }

    @Test
    void firstFixedBoundaryRequiresExactOutwardNeighborPayload() {
        assertOutwardBoundaryPayload(false);
    }

    @Test
    void lastFixedBoundaryRequiresExactOutwardNeighborPayload() {
        assertOutwardBoundaryPayload(true);
    }

    private static void assertOutwardBoundaryPayload(boolean reversed) {
        assertDoesNotThrow(() -> outwardNeighborFixture(false, reversed));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> outwardNeighborFixture(true, reversed));
        assertTrue(failure.getMessage().contains("identity-only"), failure::getMessage);
    }

    private static AlignmentEditPlan outwardNeighborFixture(boolean moveOutward, boolean reversed) {
        PrimitiveKey e = node(5);
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = new LinkedHashMap<>();
        for (PrimitiveKey key : List.of(A, B, C, D, e)) {
            beforeValues.put(key, detachedNode(key, key.id() * 0.00001));
        }
        List<PrimitiveKey> oldNodes = reversed ? List.of(D, C, B, A) : List.of(A, B, C, D);
        List<PrimitiveKey> newNodes = reversed ? List.of(D, e, B, A) : List.of(A, B, e, D);
        beforeValues.put(BC, new DetachedWay(BC, oldNodes, Map.of(), false, false));
        Map<PrimitiveKey, DetachedPrimitive> afterValues = new LinkedHashMap<>(beforeValues);
        afterValues.put(BC, new DetachedWay(BC, newNodes, Map.of(), false, true));
        if (moveOutward) {
            afterValues.put(A, new DetachedNode(A,
                new GeographicPoint(0.000005, 0.00001), Map.of(), false, true));
        }
        // CD is identity-only; materializing it would invalidate this regression's premise.
        Map<PrimitiveKey, Set<PrimitiveKey>> beforeWatches = mutableWatches(beforeValues);
        Map<PrimitiveKey, Set<PrimitiveKey>> afterWatches = mutableWatches(afterValues);
        beforeWatches.get(B).add(CD);
        afterWatches.get(B).add(CD);
        assertTrue(!beforeValues.containsKey(CD) && !afterValues.containsKey(CD));
        ClosureDescriptor closure = closure(beforeValues.keySet(), Set.of(A, C, e, BC),
            Set.of(A, C, e), Set.of(), Map.of(BC, List.of(new OccurrenceRange(0, 3))), false);
        NetworkSnapshot before = snapshot("before-outward-neighbor", SnapshotRole.CAPTURED_BEFORE,
            closure, beforeValues, beforeWatches);
        NetworkSnapshot after = snapshot("after-outward-neighbor", SnapshotRole.PROPOSED_AFTER,
            closure, afterValues, afterWatches);
        return fixedPlan(before, after, reversed ? new OccurrenceRange(0, 2) : new OccurrenceRange(1, 3),
            Map.of(BC, coordinates(after, BC)));
    }

    @Test
    void rawIndexShiftWithSameDirectedNeighborsDoesNotRequireExternalPayload() {
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = incidenceValues(List.of(A, B, C, D));
        Map<PrimitiveKey, DetachedPrimitive> afterValues = new LinkedHashMap<>(beforeValues);
        afterValues.put(PLANNED, detachedNode(PLANNED, -0.001));
        afterValues.put(BC, new DetachedWay(BC, List.of(PLANNED, A, B, C, D),
            Map.of(), false, true));
        Map<PrimitiveKey, Set<PrimitiveKey>> beforeWatches = mutableWatches(beforeValues);
        beforeWatches.get(B).add(CD);
        Map<PrimitiveKey, Set<PrimitiveKey>> afterWatches = mutableWatches(afterValues);
        afterWatches.get(B).add(CD);
        ClosureDescriptor closure = closure(beforeValues.keySet(), Set.of(A, BC), Set.of(A), Set.of(),
            Map.of(BC, List.of(new OccurrenceRange(0, 0))), true);
        NetworkSnapshot before = snapshot("before-index-shift", SnapshotRole.CAPTURED_BEFORE,
            closure, beforeValues, beforeWatches);
        NetworkSnapshot after = snapshot("after-index-shift", SnapshotRole.PROPOSED_AFTER,
            closure, afterValues, afterWatches);

        assertDoesNotThrow(() -> fixedPlan(before, after, new OccurrenceRange(0, 3),
            Map.of(BC, coordinates(after, BC))));
    }

    @Test
    void completeIncidentWayAndRelationPayloadsPermitMoveAndPreserveOrderRoles() {
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = completeIncidentValues(true);
        Map<PrimitiveKey, DetachedPrimitive> afterValues = movedC(beforeValues);
        ClosureDescriptor closure = completeClosure(beforeValues.keySet());
        NetworkSnapshot before = snapshot("before-complete", SnapshotRole.CAPTURED_BEFORE,
            closure, beforeValues, V022SnapshotFixtures.closedWorldReferrerWatches(beforeValues));
        NetworkSnapshot after = snapshot("after-complete", SnapshotRole.PROPOSED_AFTER,
            closure, afterValues, V022SnapshotFixtures.closedWorldReferrerWatches(afterValues));

        assertDoesNotThrow(() -> plan(before, after,
            Map.of(BC, coordinates(after, BC), CD, coordinates(after, CD))));
    }

    @Test
    void unchangedExternalRefOnReadOnlyNodeMustBePreservedExactly() {
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = completeIncidentValues(true);
        Map<PrimitiveKey, DetachedPrimitive> afterValues = movedC(beforeValues);
        Map<PrimitiveKey, Set<PrimitiveKey>> beforeWatches = mutableWatches(beforeValues);
        beforeWatches.get(A).add(EXTERNAL_ROUTE);
        Map<PrimitiveKey, Set<PrimitiveKey>> preserved = mutableWatches(afterValues);
        preserved.get(A).add(EXTERNAL_ROUTE);
        Map<PrimitiveKey, Set<PrimitiveKey>> dropped = mutableWatches(afterValues);
        ClosureDescriptor closure = completeClosure(beforeValues.keySet());
        NetworkSnapshot before = snapshot("before-read-only-external", SnapshotRole.CAPTURED_BEFORE,
            closure, beforeValues, beforeWatches);
        NetworkSnapshot goodAfter = snapshot("after-read-only-external", SnapshotRole.PROPOSED_AFTER,
            closure, afterValues, preserved);
        NetworkSnapshot badAfter = snapshot("after-dropped-external", SnapshotRole.PROPOSED_AFTER,
            closure, afterValues, dropped);

        assertDoesNotThrow(() -> plan(before, goodAfter,
            Map.of(BC, coordinates(goodAfter, BC), CD, coordinates(goodAfter, CD))));
        assertThrows(IllegalArgumentException.class, () -> plan(before, badAfter,
            Map.of(BC, coordinates(badAfter, BC), CD, coordinates(badAfter, CD))));
    }

    @Test
    void removedNodeCannotDiscardIdentityOnlyExternalReferrer() {
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = fivePayloadChain();
        Map<PrimitiveKey, Set<PrimitiveKey>> beforeWatches = mutableWatches(beforeValues);
        beforeWatches.get(C).add(CD);
        Map<PrimitiveKey, DetachedPrimitive> afterValues = new LinkedHashMap<>(beforeValues);
        afterValues.remove(C);
        afterValues.put(PLANNED, detachedNode(PLANNED, 0.002));
        afterValues.put(BC, new DetachedWay(BC, List.of(B, PLANNED), Map.of(), false, true));
        ClosureDescriptor closure = closure(beforeValues.keySet(), Set.of(C, BC), Set.of(), Set.of(C),
            Map.of(BC, List.of(new OccurrenceRange(0, 1))), true);
        NetworkSnapshot before = snapshot("before-delete-external", SnapshotRole.CAPTURED_BEFORE,
            closure, beforeValues, beforeWatches);
        NetworkSnapshot after = snapshot("after-delete-external", SnapshotRole.PROPOSED_AFTER,
            closure, afterValues, V022SnapshotFixtures.closedWorldReferrerWatches(afterValues));

        assertThrows(IllegalArgumentException.class,
            () -> plan(before, after, Map.of(BC, coordinates(after, BC))));
    }

    @Test
    void newNodeCannotAcquireAnUnmaterializedExternalBaseline() {
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = fivePayloadChain();
        Map<PrimitiveKey, DetachedPrimitive> afterValues = new LinkedHashMap<>(beforeValues);
        afterValues.put(PLANNED, detachedNode(PLANNED, 0.0015));
        Map<PrimitiveKey, Set<PrimitiveKey>> afterWatches = mutableWatches(afterValues);
        afterWatches.get(PLANNED).add(CD);
        ClosureDescriptor closure = closure(beforeValues.keySet(), Set.of(), Set.of(), Set.of(),
            Map.of(), true);
        NetworkSnapshot before = snapshot("before-new-external", SnapshotRole.CAPTURED_BEFORE,
            closure, beforeValues, V022SnapshotFixtures.closedWorldReferrerWatches(beforeValues));
        NetworkSnapshot after = snapshot("after-new-external", SnapshotRole.PROPOSED_AFTER,
            closure, afterValues, afterWatches);

        assertThrows(IllegalArgumentException.class, () -> plan(before, after, Map.of()));
    }

    private static AlignmentEditPlan plan(NetworkSnapshot before, NetworkSnapshot after,
        Map<PrimitiveKey, List<GeographicPoint>> preview) {
        return plan(before, after, new OccurrenceRange(0, 1), JunctionPolicy.REATTACH, preview);
    }

    private static AlignmentEditPlan fixedPlan(NetworkSnapshot before, NetworkSnapshot after,
        OccurrenceRange selectedRange, Map<PrimitiveKey, List<GeographicPoint>> preview) {
        return plan(before, after, selectedRange, JunctionPolicy.FIXED, preview);
    }

    private static AlignmentEditPlan plan(NetworkSnapshot before, NetworkSnapshot after,
        OccurrenceRange selectedRange, JunctionPolicy junctionPolicy,
        Map<PrimitiveKey, List<GeographicPoint>> preview) {
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(
            new GeographicPoint(0.0, 0.0), new GeographicPoint(-0.01, -0.01),
            new GeographicPoint(0.01, 0.01));
        return new AlignmentEditPlan(BC, selectedRange, before, after, frame,
            new RecoveryPermissions(false, 7.01, 7.01, junctionPolicy,
                junctionPolicy == JunctionPolicy.REATTACH),
            "settings", "evidence", "parameters", "route", preview,
            new ValidationReport(ValidationReport.Disposition.APPLICABLE, List.of()));
    }

    private static NetworkSnapshot snapshot(String id, SnapshotRole role, ClosureDescriptor closure,
        Map<PrimitiveKey, DetachedPrimitive> values,
        Map<PrimitiveKey, Set<PrimitiveKey>> watches) {
        return new NetworkSnapshot(id, role, "dataset", 1, closure, values, watches);
    }

    private static ClosureDescriptor completeClosure(Set<PrimitiveKey> keys) {
        return closure(keys, Set.of(C, BC, CD), Set.of(C), Set.of(), Map.of(
            BC, List.of(new OccurrenceRange(0, 1)),
            CD, List.of(new OccurrenceRange(0, 1))), false);
    }

    private static ClosureDescriptor closure(Set<PrimitiveKey> keys, Set<PrimitiveKey> editable,
        Set<PrimitiveKey> movable, Set<PrimitiveKey> removable,
        Map<PrimitiveKey, List<OccurrenceRange>> occurrences, boolean mayCreateNodes) {
        Set<PrimitiveKey> protectedNodes = keys.stream()
            .filter(key -> key.type() == PrimitiveKey.Type.NODE && !movable.contains(key)
                && !removable.contains(key))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT, "watch-plan-v1", keys,
            editable, movable, protectedNodes, removable, occurrences, List.of(),
            MetricRegion.rectangle(-500, -500, 500, 500),
            MetricRegion.rectangle(-500, -500, 500, 500),
            mayCreateNodes, true, true, true);
    }

    private static Map<PrimitiveKey, DetachedPrimitive> fivePayloadChain() {
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        values.put(A, detachedNode(A, 0.0));
        values.put(B, detachedNode(B, 0.001));
        values.put(C, detachedNode(C, 0.002));
        values.put(AB, new DetachedWay(AB, List.of(A, B), Map.of(), false, false));
        values.put(BC, new DetachedWay(BC, List.of(B, C), Map.of(), false, false));
        return values;
    }

    private static Map<PrimitiveKey, DetachedPrimitive> incidenceValues(List<PrimitiveKey> wayNodes) {
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        values.put(A, detachedNode(A, 0.0));
        values.put(B, detachedNode(B, 0.001));
        values.put(C, detachedNode(C, 0.002));
        if (wayNodes.contains(D)) {
            values.put(D, detachedNode(D, 0.003));
        }
        values.put(BC, new DetachedWay(BC, wayNodes, Map.of(), false,
            !wayNodes.equals(List.of(A, B, C)) && !wayNodes.equals(List.of(A, B, C, D))));
        return values;
    }

    private static Map<PrimitiveKey, DetachedPrimitive> completeIncidentValues(boolean includeRelation) {
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(fivePayloadChain());
        values.put(D, detachedNode(D, 0.003));
        values.put(CD, new DetachedWay(CD, List.of(C, D), Map.of(), false, false));
        if (includeRelation) {
            values.put(ROUTE, new DetachedRelation(ROUTE,
                List.of(new DetachedRelationMember(BC, "forward"),
                    new DetachedRelationMember(CD, "backward")),
                Map.of("type", "route"), false, false));
        }
        return values;
    }

    private static Map<PrimitiveKey, DetachedPrimitive> movedC(
        Map<PrimitiveKey, DetachedPrimitive> before) {
        Map<PrimitiveKey, DetachedPrimitive> result = new LinkedHashMap<>(before);
        result.put(C, new DetachedNode(C, MOVED_C, Map.of(), false, true));
        return result;
    }

    private static List<GeographicPoint> coordinates(NetworkSnapshot snapshot, PrimitiveKey wayKey) {
        DetachedWay way = (DetachedWay) snapshot.primitives().get(wayKey);
        return way.nodeKeys().stream()
            .map(key -> ((DetachedNode) snapshot.primitives().get(key)).coordinate()).toList();
    }

    private static Map<PrimitiveKey, Set<PrimitiveKey>> mutableWatches(
        Map<PrimitiveKey, DetachedPrimitive> values) {
        Map<PrimitiveKey, Set<PrimitiveKey>> result = new LinkedHashMap<>();
        V022SnapshotFixtures.closedWorldReferrerWatches(values)
            .forEach((key, refs) -> result.put(key, new LinkedHashSet<>(refs)));
        return result;
    }

    private static DetachedNode detachedNode(PrimitiveKey key, double longitude) {
        return new DetachedNode(key, new GeographicPoint(0.0, longitude), Map.of(), false, false);
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

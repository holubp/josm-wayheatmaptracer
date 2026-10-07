package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Adversarial movement-authority tests for exact selected occurrences. */
class V022EditAuthorityAdversarialTest {
    private static final PrimitiveKey FIRST = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
    private static final PrimitiveKey SECOND = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
    private static final PrimitiveKey WAY = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 3);
    private static final PrimitiveKey SOUTH = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 4);
    private static final PrimitiveKey MIDDLE = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 5);
    private static final PrimitiveKey NORTH = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 6);
    private static final PrimitiveKey RECEIVER = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 7);

    @Test
    void fixedPolicyRejectsOtherwiseAuthorizedSelectedEndpointMovement() {
        Fixture fixture = fixture();
        assertThrows(IllegalArgumentException.class, () -> plan(fixture,
            RecoveryPermissions.disabled(7.01)));
    }

    @Test
    void explicitReattachmentPolicyRejectsNonsharedSelectedEndpointMovement() {
        Fixture fixture = fixture();
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
            JunctionPolicy.REATTACH, false);
        assertThrows(IllegalArgumentException.class, () -> plan(fixture, permissions));
    }

    @Test
    void protectedAndMovableNodeAuthorityCannotOverlap() {
        Set<PrimitiveKey> keys = Set.of(FIRST, SECOND, WAY);
        assertThrows(IllegalArgumentException.class, () -> new ClosureDescriptor(
            ClosureDescriptor.Scope.EDIT_COMPONENT, "authority-v1", keys, Set.of(WAY, SECOND),
            Set.of(SECOND), Set.of(SECOND), Set.of(),
            Map.of(WAY, List.of(new OccurrenceRange(0, 1))), List.of(),
            MetricRegion.rectangle(-20, -20, 20, 20), MetricRegion.rectangle(-10, -10, 10, 10),
            false, true, true, true));
    }

    @Test
    void onlyReattachMayReinsertAnAuthorizedMovableJunctionAtANewReceiverEdge() {
        Fixture fixture = receiverReorderFixture();
        RecoveryPermissions reattach = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, false);
        assertDoesNotThrow(() -> receiverReorderPlan(fixture, reattach));
        RecoveryPermissions legacyMove = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.LEGACY_BOUNDED_MOVE, false);
        assertThrows(IllegalArgumentException.class,
                () -> receiverReorderPlan(fixture, legacyMove));
        Fixture overbound = receiverReorderFixture(new GeographicPoint(0.0003, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> receiverReorderPlan(overbound, reattach));
    }

    @Test
    void detachedPlanRejectsMovementOfUnselectedWayContinuation() {
        PrimitiveKey prefix = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 10);
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 11);
        PrimitiveKey second = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 12);
        PrimitiveKey suffix = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 13);
        PrimitiveKey wayKey = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 14);
        GeographicPoint prefixPoint = new GeographicPoint(42.0, 19.0);
        GeographicPoint firstPoint = new GeographicPoint(42.0001, 19.0);
        GeographicPoint secondPoint = new GeographicPoint(42.0002, 19.0);
        GeographicPoint suffixPoint = new GeographicPoint(42.0003, 19.0);
        List<PrimitiveKey> nodeKeys = List.of(prefix, first, second, suffix);
        Set<PrimitiveKey> primitiveKeys = Set.of(prefix, first, second, suffix, wayKey);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
                "partial-selection-authority-v1", primitiveKeys, primitiveKeys, Set.of(prefix),
                Set.of(first, second, suffix), Set.of(),
                Map.of(wayKey, List.of(new OccurrenceRange(1, 2))), List.of(),
                MetricRegion.rectangle(-100, -100, 100, 100),
                MetricRegion.rectangle(-50, -50, 50, 50), false, true, true, true);
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = Map.of(
                prefix, new DetachedNode(prefix, prefixPoint, Map.of(), false, false),
                first, new DetachedNode(first, firstPoint, Map.of(), false, false),
                second, new DetachedNode(second, secondPoint, Map.of(), false, false),
                suffix, new DetachedNode(suffix, suffixPoint, Map.of(), false, false),
                wayKey, new DetachedWay(wayKey, nodeKeys, Map.of("highway", "path"), false, false));
        GeographicPoint maliciousContinuation = new GeographicPoint(42.00001, 19.00001);
        Map<PrimitiveKey, DetachedPrimitive> afterValues = new java.util.LinkedHashMap<>(beforeValues);
        afterValues.put(prefix, new DetachedNode(prefix, maliciousContinuation, Map.of(), false, true));
        NetworkSnapshot before = new NetworkSnapshot("partial-before", SnapshotRole.CAPTURED_BEFORE,
                "dataset", 1, closure, beforeValues,
                V022SnapshotFixtures.closedWorldReferrerWatches(beforeValues));
        NetworkSnapshot after = new NetworkSnapshot("partial-after", SnapshotRole.PROPOSED_AFTER,
                "dataset", 1, closure, afterValues,
                V022SnapshotFixtures.closedWorldReferrerWatches(afterValues));
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(
                new GeographicPoint(42.0, 19.0), new GeographicPoint(41.99, 18.99),
                new GeographicPoint(42.01, 19.01));
        Map<PrimitiveKey, List<GeographicPoint>> preview = Map.of(wayKey, List.of(
                maliciousContinuation, firstPoint, secondPoint, suffixPoint));

        assertThrows(IllegalArgumentException.class, () -> new AlignmentEditPlan(wayKey,
                new OccurrenceRange(1, 2), before, after, frame,
                new RecoveryPermissions(false, 7.01, 7.01,
                        JunctionPolicy.LEGACY_BOUNDED_MOVE, false),
                "settings", "evidence", "parameters", "malicious-subrange-route", preview,
                new ValidationReport(ValidationReport.Disposition.APPLICABLE, List.of())));
    }

    private static AlignmentEditPlan plan(Fixture fixture, RecoveryPermissions permissions) {
        GeographicPoint origin = new GeographicPoint(42.0, 19.0);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        return new AlignmentEditPlan(WAY, new OccurrenceRange(0, 1), fixture.before(), fixture.after(),
            frame, permissions, "settings", "evidence", "parameters", "route",
            Map.of(WAY, List.of(origin, new GeographicPoint(42.00011, 19.0001))),
            new ValidationReport(ValidationReport.Disposition.APPLICABLE, List.of()));
    }

    private static Fixture fixture() {
        GeographicPoint first = new GeographicPoint(42.0, 19.0);
        GeographicPoint second = new GeographicPoint(42.0001, 19.0001);
        Set<PrimitiveKey> keys = Set.of(FIRST, SECOND, WAY);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
            "authority-v1", keys, Set.of(WAY, SECOND), Set.of(SECOND), Set.of(FIRST), Set.of(),
            Map.of(WAY, List.of(new OccurrenceRange(0, 1))), List.of(),
            MetricRegion.rectangle(-30, -30, 30, 30), MetricRegion.rectangle(-20, -20, 20, 20),
            false, true, true, true);
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = Map.of(
            FIRST, new DetachedNode(FIRST, first, Map.of(), false, false),
            SECOND, new DetachedNode(SECOND, second, Map.of(), false, false),
            WAY, new DetachedWay(WAY, List.of(FIRST, SECOND), Map.of("highway", "path"), false, false));
        Map<PrimitiveKey, DetachedPrimitive> afterValues = Map.of(
            FIRST, beforeValues.get(FIRST),
            SECOND, new DetachedNode(SECOND, new GeographicPoint(42.00011, 19.0001),
                Map.of(), false, true),
            WAY, beforeValues.get(WAY));
        return new Fixture(
            new NetworkSnapshot("before", SnapshotRole.CAPTURED_BEFORE, "dataset", 1, closure, beforeValues,
                V022SnapshotFixtures.closedWorldReferrerWatches(beforeValues)),
            new NetworkSnapshot("after", SnapshotRole.PROPOSED_AFTER, "dataset", 1, closure, afterValues,
                V022SnapshotFixtures.closedWorldReferrerWatches(afterValues)));
    }

    private static Fixture receiverReorderFixture() {
        return receiverReorderFixture(new GeographicPoint(0.0001, 0.0));
    }

    private static Fixture receiverReorderFixture(GeographicPoint moved) {
        GeographicPoint first = new GeographicPoint(0.0, -0.0002);
        GeographicPoint junction = new GeographicPoint(0.0, 0.0);
        GeographicPoint south = new GeographicPoint(-0.0002, 0.0);
        GeographicPoint middle = new GeographicPoint(0.00005, 0.0);
        GeographicPoint north = new GeographicPoint(0.0002, 0.0);
        Set<PrimitiveKey> keys = Set.of(FIRST, SECOND, SOUTH, MIDDLE, NORTH, WAY, RECEIVER);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
                "reattach-authority-v1", keys, Set.of(WAY, RECEIVER, SECOND), Set.of(SECOND),
                Set.of(FIRST, SOUTH, MIDDLE, NORTH), Set.of(), Map.of(
                        WAY, List.of(new OccurrenceRange(0, 1)),
                        RECEIVER, List.of(new OccurrenceRange(0, 3))), List.of(),
                MetricRegion.rectangle(-100, -100, 100, 100),
                MetricRegion.rectangle(-100, -100, 100, 100), false, true, true, true);
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = Map.of(
                FIRST, new DetachedNode(FIRST, first, Map.of(), false, false),
                SECOND, new DetachedNode(SECOND, junction, Map.of(), false, false),
                SOUTH, new DetachedNode(SOUTH, south, Map.of(), false, false),
                MIDDLE, new DetachedNode(MIDDLE, middle, Map.of(), false, false),
                NORTH, new DetachedNode(NORTH, north, Map.of(), false, false),
                WAY, new DetachedWay(WAY, List.of(FIRST, SECOND), Map.of(), false, false),
                RECEIVER, new DetachedWay(RECEIVER, List.of(SOUTH, SECOND, MIDDLE, NORTH),
                        Map.of(), false, false));
        Map<PrimitiveKey, DetachedPrimitive> afterValues = Map.of(
                FIRST, beforeValues.get(FIRST),
                SECOND, new DetachedNode(SECOND, moved, Map.of(), false, true),
                SOUTH, beforeValues.get(SOUTH),
                MIDDLE, beforeValues.get(MIDDLE),
                NORTH, beforeValues.get(NORTH),
                WAY, beforeValues.get(WAY),
                RECEIVER, new DetachedWay(RECEIVER, List.of(SOUTH, MIDDLE, SECOND, NORTH),
                        Map.of(), false, true));
        return new Fixture(
                new NetworkSnapshot("receiver-before", SnapshotRole.CAPTURED_BEFORE, "dataset", 1,
                        closure, beforeValues, V022SnapshotFixtures.closedWorldReferrerWatches(beforeValues)),
                new NetworkSnapshot("receiver-after", SnapshotRole.PROPOSED_AFTER, "dataset", 1,
                        closure, afterValues, V022SnapshotFixtures.closedWorldReferrerWatches(afterValues)));
    }

    private static AlignmentEditPlan receiverReorderPlan(Fixture fixture,
            RecoveryPermissions permissions) {
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(
                new GeographicPoint(0.0, 0.0), new GeographicPoint(-0.01, -0.01),
                new GeographicPoint(0.01, 0.01));
        DetachedNode first = (DetachedNode) fixture.after().primitives().get(FIRST);
        DetachedNode junction = (DetachedNode) fixture.after().primitives().get(SECOND);
        DetachedNode south = (DetachedNode) fixture.after().primitives().get(SOUTH);
        DetachedNode middle = (DetachedNode) fixture.after().primitives().get(MIDDLE);
        DetachedNode north = (DetachedNode) fixture.after().primitives().get(NORTH);
        return new AlignmentEditPlan(WAY, new OccurrenceRange(0, 1), fixture.before(), fixture.after(),
                frame, permissions, "settings", "evidence", "parameters", "route",
                Map.of(WAY, List.of(first.coordinate(), junction.coordinate()),
                        RECEIVER, List.of(south.coordinate(), middle.coordinate(),
                                junction.coordinate(), north.coordinate())),
                new ValidationReport(ValidationReport.Disposition.REVIEW_REQUIRED,
                        List.of("network-review-required")));
    }

    private record Fixture(NetworkSnapshot before, NetworkSnapshot after) { }
}

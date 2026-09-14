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

    @Test
    void fixedPolicyRejectsOtherwiseAuthorizedSelectedEndpointMovement() {
        Fixture fixture = fixture();
        assertThrows(IllegalArgumentException.class, () -> plan(fixture,
            RecoveryPermissions.disabled(7.01)));
    }

    @Test
    void explicitReattachmentPolicyAdmitsAuthorizedSelectedEndpointMovement() {
        Fixture fixture = fixture();
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
            JunctionPolicy.REATTACH, false);
        assertDoesNotThrow(() -> plan(fixture, permissions));
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

    private record Fixture(NetworkSnapshot before, NetworkSnapshot after) { }
}

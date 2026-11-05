package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

class LegacyProfileNormalPolicyTest {
    @Test
    void policyIdsAreStableAndUnknownIdsFailClosed() {
        assertEquals("legacy-immediate-v1", LegacyProfileNormalPolicy.IMMEDIATE_V1.id());
        assertEquals("legacy-protected-secant3-v1", LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1.id());
        assertEquals(LegacyProfileNormalPolicy.IMMEDIATE_V1,
            LegacyProfileNormalPolicy.fromId("legacy-immediate-v1"));
        assertEquals(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1,
            LegacyProfileNormalPolicy.fromId("legacy-protected-secant3-v1"));
        assertThrows(IllegalArgumentException.class, () -> LegacyProfileNormalPolicy.fromId("future-policy"));
        assertThrows(IllegalArgumentException.class, () -> LegacyProfileNormalPolicy.fromId(null));
    }

    @Test
    void exactFixedStationsMapToOneDenseRowAndAlwaysRetainEndpoints() {
        var plan = LegacyProfileNormalFences.plan(List.of(point(0), point(5), point(10)), 5, List.of(0, 1, 2));

        assertTrue(plan.eligible());
        assertEquals("eligible", plan.reason());
        assertEquals(Set.of(0, 2, 4), plan.profileBoundaries());
    }

    @Test
    void offGridFixedStationsFenceBothBracketingDenseRows() {
        var plan = LegacyProfileNormalFences.plan(List.of(point(0), point(4), point(10)), 5, List.of(0, 1, 2));

        assertTrue(plan.eligible());
        assertEquals(Set.of(0, 1, 2, 4), plan.profileBoundaries());
    }

    @Test
    void ulpNearGridBoundaryIsNotSnappedToNearestRow() {
        double justAfterFive = Math.nextUp(5.0);
        var plan = LegacyProfileNormalFences.plan(
            List.of(point(0), point(justAfterFive), point(10)), 5, List.of(0, 1, 2));

        assertTrue(plan.eligible());
        assertEquals(Set.of(0, 2, 3, 4), plan.profileBoundaries());
    }

    @Test
    void coincidentProtectedStationsRemainRepresentedWithoutInventingRows() {
        List<EastNorth> source = List.of(point(0), point(5), point(5), point(10));
        var plan = LegacyProfileNormalFences.plan(source, 5, List.of(0, 1, 2, 3));

        assertTrue(plan.eligible());
        assertEquals(Set.of(0, 2, 4), plan.profileBoundaries());
    }

    @Test
    void malformedOrUnprovableMappingsSelectImmediateFallback() {
        assertFallback("invalid-profile-count", line(0, 10), 1, List.of(0, 1));
        assertFallback("invalid-authority", line(0, 10), 5, List.of(1, 2));
        assertFallback("invalid-authority", line(0, 10), 5, List.of(0, 2, 1, 10));
        assertFallback("invalid-authority", line(0, 10), 5, List.of(0, 11, 10));
        assertFallback("degenerate-geometry", List.of(point(1), point(1)), 5, List.of(0, 1));
        assertFallback("non-finite-geometry", List.of(point(0), new EastNorth(Double.NaN, 2)), 5, List.of(0, 1));
    }

    private static void assertFallback(String reason, List<EastNorth> source, int profiles, List<Integer> fixed) {
        var plan = LegacyProfileNormalFences.plan(source, profiles, fixed);
        assertFalse(plan.eligible());
        assertEquals(reason, plan.reason());
        assertTrue(plan.profileBoundaries().isEmpty());
    }

    private static List<EastNorth> line(double start, double end) {
        return List.of(point(start), point(end));
    }

    private static EastNorth point(double east) {
        return new EastNorth(east, 0);
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Representability controls: these characterize sampling, not a claimed tracing repair. */
class PhysicalPathContinuityTest {
    @Test
    void legacyRetainsSupportedBendOppositeToSourceDirection() {
        var candidates = new RidgeTracker().track(PhysicalGeometryFixture.supportedOppositeArcProfiles(),
                PhysicalGeometryFixture.NATIVE_PITCH);
        assertTrue(!candidates.isEmpty());
        assertOppositeArc(candidates.get(0).screenPoints());
    }

    @Test
    void corridorARetainsSupportedBendOppositeToSourceDirection() {
        var candidates = new CorridorAwareTracker().track(PhysicalGeometryFixture.supportedOppositeArcProfiles(),
                PhysicalGeometryFixture.NATIVE_PITCH);
        assertTrue(!candidates.isEmpty());
        assertOppositeArc(candidates.get(0).screenPoints());
    }

    private static void assertOppositeArc(java.util.List<java.awt.geom.Point2D.Double> points) {
        double radius = 4 * PhysicalGeometryFixture.NATIVE_PITCH;
        for (var point : points) {
            assertEquals(radius, Math.hypot(point.x - 64, point.y - 64), PhysicalGeometryFixture.NATIVE_PITCH,
                    "The path must stay on the independently painted arc, not its dark shortcut");
        }
        assertTrue(points.stream().anyMatch(p -> p.x < 64 - radius + PhysicalGeometryFixture.NATIVE_PITCH),
                "A valid 150-degree bend must retain its apex even with a reversed source-normal chart");
    }

    @Test
    void regularNormalChartCanTraverseIndependentStraightRidgeOnce() {
        var profiles = PhysicalGeometryFixture.straightRidgeThroughParabola(12.0);
        double previous = Double.NEGATIVE_INFINITY;
        for (var profile : profiles) {
            var point = PhysicalGeometryFixture.strongestObservedPoint(profile);
            assertEquals(64.0, point.y, 0.5, "Observed image ridge must remain straight");
            assertTrue(point.x > previous, "Regular source-normal chart must preserve ridge order");
            previous = point.x;
        }
    }

    @Test
    void foldedNormalChartForcesReversalEvenThoughImageRidgeIsStraight() {
        var profiles = PhysicalGeometryFixture.straightRidgeThroughParabola(4.0);
        var before = PhysicalGeometryFixture.strongestObservedPoint(profiles.get(4)); // j=-4
        var middle = PhysicalGeometryFixture.strongestObservedPoint(profiles.get(8));
        var after = PhysicalGeometryFixture.strongestObservedPoint(profiles.get(12)); // j=4
        assertEquals(64.0, before.y, 0.5);
        assertEquals(64.0, middle.y, 0.5);
        assertEquals(64.0, after.y, 0.5);
        assertTrue(before.x > middle.x + 3.0 && middle.x > after.x + 3.0,
                "True peaks reverse by over 6m; no score can create an absent ordered state sequence");
        assertTrue(PhysicalGeometryFixture.strongestObservedPoint(profiles.get(0)).x < before.x);
        assertTrue(PhysicalGeometryFixture.strongestObservedPoint(profiles.get(16)).x > after.x);
    }
}

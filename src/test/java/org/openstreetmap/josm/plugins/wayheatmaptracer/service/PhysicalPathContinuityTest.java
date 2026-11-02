package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Representability controls: these characterize sampling, not a claimed tracing repair. */
class PhysicalPathContinuityTest {
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

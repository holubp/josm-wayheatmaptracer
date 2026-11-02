package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;

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

    @Test
    void legacyParallelStrandsDoNotGainDarkConnectorAtStrongOrWeakSignal() {
        for (double amplitude : List.of(0.95, 0.35)) {
            PhysicalGeometryFixture.ParallelFrame frame = PhysicalGeometryFixture.parallelFrame(amplitude);
            var profiles = frame.profileSet().levelZeroProfiles();
            var candidates = new RidgeTracker().track(profiles, frame.sourcePixelPitch());

            assertTrue(!candidates.isEmpty(), "Legacy amplitude=" + amplitude);
            assertParallelStrand(candidates.get(0), frame, "Legacy amplitude=" + amplitude);
            assertEquals(0xff000000, frame.raster().getRGB(108, 80),
                "The independently measured midpoint between the ring strands must be dark");
        }
    }

    @Test
    void corridorAwareParallelStrandsDoNotGainDarkConnectorAtStrongOrWeakSignal() {
        for (double amplitude : List.of(0.95, 0.35)) {
            PhysicalGeometryFixture.ParallelFrame frame = PhysicalGeometryFixture.parallelFrame(amplitude);
            var result = new CorridorAwareTracker().trackDetailed(
                frame.profileSet(), frame.sourcePixelPitch(), JunctionContext.empty());

            assertTrue(!result.candidates().isEmpty(), "Corridor Aware amplitude=" + amplitude);
            assertParallelStrand(result.candidates().get(0), frame, "Corridor Aware amplitude=" + amplitude);
            assertEquals(0xff000000, frame.raster().getRGB(108, 80),
                "The independently measured midpoint between the ring strands must be dark");
        }
    }

    @Test
    void legacyCollinearInsertionPreservesSupportedCurvedImageApex() {
        PhysicalGeometryFixture.CollinearInsertionFrame frame = PhysicalGeometryFixture.collinearInsertionFrame();
        assertEquivalentResampledSource(frame);

        var original = new RidgeTracker().track(frame.originalProfiles().levelZeroProfiles(), frame.sourcePixelPitch());
        var inserted = new RidgeTracker().track(frame.insertedProfiles().levelZeroProfiles(), frame.sourcePixelPitch());

        assertTrue(!original.isEmpty() && !inserted.isEmpty());
        assertSameImageCurveAndApex(original.get(0), inserted.get(0), frame.sourcePixelPitch());
    }

    @Test
    void corridorAwareCollinearInsertionPreservesSupportedCurvedImageApex() {
        PhysicalGeometryFixture.CollinearInsertionFrame frame = PhysicalGeometryFixture.collinearInsertionFrame();
        assertEquivalentResampledSource(frame);

        var original = new CorridorAwareTracker().trackDetailed(
            frame.originalProfiles(), frame.sourcePixelPitch(), JunctionContext.empty()).candidates();
        var inserted = new CorridorAwareTracker().trackDetailed(
            frame.insertedProfiles(), frame.sourcePixelPitch(), JunctionContext.empty()).candidates();

        assertTrue(!original.isEmpty() && !inserted.isEmpty());
        assertSameImageCurveAndApex(original.get(0), inserted.get(0), frame.sourcePixelPitch());
    }

    private static void assertParallelStrand(
        CenterlineCandidate candidate,
        PhysicalGeometryFixture.ParallelFrame frame,
        String context
    ) {
        List<Point2D.Double> points = candidate.screenPoints();
        var profiles = frame.profileSet().levelZeroProfiles();
        assertEquals(profiles.size(), points.size(), context);
        int selectedStrand = -1;
        for (int i = 0; i < points.size(); i++) {
            double innerDistance = PhysicalGeometryFixture.distanceToParallelStrand(points.get(i), i, 0, frame);
            double outerDistance = PhysicalGeometryFixture.distanceToParallelStrand(points.get(i), i, 1, frame);
            assertTrue(Math.min(innerDistance, outerDistance) <= frame.sourcePixelPitch(),
                context + ", profile=" + i + ", inner error=" + innerDistance + ", outer error=" + outerDistance);
            int thisStrand = innerDistance <= outerDistance ? 0 : 1;
            if (selectedStrand < 0) {
                selectedStrand = thisStrand;
            } else {
                assertEquals(selectedStrand, thisStrand,
                    context + ", candidate switched between the two separated image strands at profile=" + i);
            }
        }
    }

    private static void assertEquivalentResampledSource(PhysicalGeometryFixture.CollinearInsertionFrame frame) {
        assertEquals(frame.originalResampledSource().size(), frame.insertedResampledSource().size());
        assertEquals(frame.originalProfiles().levelZeroProfiles().size(), frame.insertedProfiles().levelZeroProfiles().size());
        for (int i = 0; i < frame.originalResampledSource().size(); i++) {
            assertEquals(0.0, frame.originalResampledSource().get(i).distance(frame.insertedResampledSource().get(i)), 1e-9,
                "PolylineMath.resampleBySpacing must erase only the collinear source-node insertion at sample=" + i);
        }
    }

    private static void assertSameImageCurveAndApex(
        CenterlineCandidate original,
        CenterlineCandidate inserted,
        double sourcePixelPitch
    ) {
        assertEquals(original.screenPoints().size(), inserted.screenPoints().size());
        for (int i = 0; i < original.screenPoints().size(); i++) {
            assertTrue(original.screenPoints().get(i).distance(inserted.screenPoints().get(i)) <= sourcePixelPitch,
                "Collinear insertion changed physical output by more than one source pixel at sample=" + i);
            assertTrue(distanceToIndependentCurve(original.screenPoints().get(i)) <= sourcePixelPitch,
                "Original source sampling left the independently painted curve at sample=" + i);
            assertTrue(distanceToIndependentCurve(inserted.screenPoints().get(i)) <= sourcePixelPitch,
                "Inserted source sampling left the independently painted curve at sample=" + i);
        }
        assertApex(original, sourcePixelPitch);
        assertApex(inserted, sourcePixelPitch);
    }

    private static void assertApex(CenterlineCandidate candidate, double sourcePixelPitch) {
        Point2D.Double apex = candidate.screenPoints().stream()
            .filter(point -> point.x >= 60.0 && point.x <= 68.0)
            .min(java.util.Comparator.comparingDouble(point -> Math.abs(point.x - 64.0)))
            .orElseThrow(() -> new AssertionError("Candidate omitted the independently sampled apex interval"));
        assertTrue(distanceToIndependentCurve(apex) <= sourcePixelPitch,
            "Candidate must remain on the independently painted apex within one source pixel");
        double sourceY = 50.0 + 0.005 * (apex.x - 64.0) * (apex.x - 64.0);
        assertTrue(Math.abs(apex.y - sourceY) >= 3.0,
            "Candidate must retain the painted bend away from the source-axis shortcut");
    }

    private static double distanceToIndependentCurve(Point2D point) {
        double best = Double.POSITIVE_INFINITY;
        for (int step = 0; step <= 880; step++) {
            double x = 20.0 + step * 0.1;
            double y = PhysicalGeometryFixture.independentCurvedRidgeY(x);
            best = Math.min(best, point.distance(x, y));
        }
        return best;
    }
}

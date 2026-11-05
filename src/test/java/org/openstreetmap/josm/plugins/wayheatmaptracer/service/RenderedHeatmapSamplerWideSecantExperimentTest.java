package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class RenderedHeatmapSamplerWideSecantExperimentTest {
    @Test
    void radiusThreeNormalsKeepEveryStraightRidgeHitOrdered() {
        var raster = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);
        var anchors = gaussianFrameAnchors();
        var sampler = new RenderedHeatmapSampler();
        List<CrossSectionProfile> ordinary = sampler.sampleProfilesOnAnchors(raster, anchors,
                16, 1, "hot", 1.0, 1.0, IntensitySamplingMode.DIRECT_VALUE,
                (RenderedHeatmapSampler.RasterCoordinateProjector) null, CancellationProbe.NONE);
        List<CrossSectionProfile> profiles = sampleWide(sampler, raster, anchors,
                Set.of(0, anchors.size() - 1), CancellationProbe.NONE);

        List<Point2D.Double> intersections = horizontalIntersections(profiles, 40.0, 16.0);
        List<Point2D.Double> ordinaryIntersections = horizontalIntersections(ordinary, 40.0, 16.0);
        assertTrue(ordinaryIntersections.get(6).x > ordinaryIntersections.get(7).x,
                "The old immediate-normal sampler must retain the geometric reversal this experiment repairs");
        for (int i = 1; i < intersections.size(); i++) {
            assertTrue(intersections.get(i - 1).x < intersections.get(i).x,
                    "The radius-3 frame must keep the y=8 target ordered; reversal at profile " + i
                    + ": " + intersections.get(i - 1).x + " then " + intersections.get(i).x);
        }
        assertEquals(ordinary.size(), profiles.size());
        for (int i = 0; i < profiles.size(); i++) {
            assertEquals(anchors.get(i), profiles.get(i).samplingAnchor());
            assertEquals(ordinary.get(i).intensitySamples().size(), profiles.get(i).intensitySamples().size());
            assertEquals(-16.0, profiles.get(i).intensitySamples().get(0).offsetPx());
            assertEquals(16.0, profiles.get(i).intensitySamples()
                    .get(profiles.get(i).intensitySamples().size() - 1).offsetPx());
            if (i < 3 || i >= profiles.size() - 3) {
                assertNormalEquals(ordinary.get(i), profiles.get(i), "first/last three rows remain immediate");
            }
        }
    }

    @Test
    void straightNonuniformRasterSamplingMatchesEveryOrdinaryProfileValue() {
        var raster = new BufferedImage(100, 96, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < raster.getHeight(); y++) {
            int gray = Math.min(255, y * 3);
            int pixel = 0xff000000 | gray << 16 | gray << 8 | gray;
            for (int x = 0; x < raster.getWidth(); x++) raster.setRGB(x, y, pixel);
        }
        var anchors = straightAnchors(13);
        var immutableInput = List.copyOf(anchors);
        RenderedHeatmapSampler.RasterCoordinateProjector projector =
                (x, y) -> new EastNorth(2.0 * x + 5.0, 3.0 * y - 7.0);
        var sampler = new RenderedHeatmapSampler();
        var ordinary = sampler.sampleProfilesOnAnchors(raster, anchors, 16, 2, "hot", 1.0, 1.0,
                IntensitySamplingMode.DIRECT_VALUE, projector, CancellationProbe.NONE);
        var named = sampleWide(sampler, raster, anchors, Set.of(0, anchors.size() - 1), CancellationProbe.NONE,
                projector, 16, 2);

        assertEquals(immutableInput, anchors, "Sampling must not mutate caller anchors");
        assertEquals(ordinary.size(), named.size());
        for (int i = 0; i < ordinary.size(); i++) {
            var expected = ordinary.get(i);
            var actual = named.get(i);
            assertEquals(expected.samplingAnchor(), actual.samplingAnchor());
            assertNormalEquals(expected, actual, "a straight frame has identical immediate and radius-three normals");
            assertEquals(expected.peaks(), actual.peaks());
            assertEquals(expected.anchorWithinRaster(), actual.anchorWithinRaster());
            assertEquals(expected.intensitySamples(), actual.intensitySamples());
            assertEquals(expected.projectedLateralTransform(), actual.projectedLateralTransform());
            assertEquals(17, actual.intensitySamples().size());
        }
        assertTrue(ordinary.get(6).intensitySamples().stream()
                .map(RenderedHeatmapSampler.IntensitySample::nativeIntensity).distinct().count() > 1,
                "The parity fixture must carry nonuniform painted evidence");
    }

    @Test
    void protectedInteriorFenceKeepsImmediateNormalsAcrossItsInterval() {
        var anchors = gaussianFrameAnchors();
        var raster = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);
        var sampler = new RenderedHeatmapSampler();
        var ordinary = sampler.sampleProfilesOnAnchors(raster, anchors, 16, 1, "hot", 1.0, 1.0,
                IntensitySamplingMode.DIRECT_VALUE, (RenderedHeatmapSampler.RasterCoordinateProjector) null,
                CancellationProbe.NONE);
        var wide = sampleWide(sampler, raster, anchors, Set.of(0, anchors.size() - 1), CancellationProbe.NONE);
        var fenced = sampleWide(sampler, raster, anchors, Set.of(0, 9, anchors.size() - 1), CancellationProbe.NONE);

        assertNormalEquals(ordinary.get(7), fenced.get(7), "row 7 secant [4,10] crosses fence 9");
        assertNormalEquals(wide.get(4), fenced.get(4), "row 4 secant [1,7] stays inside one interval");
        assertNormalEquals(ordinary.get(9), fenced.get(9), "the protected boundary row keeps its immediate normal");
    }

    @Test
    void invalidBoundaryAuthorityFailsBeforeSampling() {
        var anchors = gaussianFrameAnchors();
        var sampler = new RenderedHeatmapSampler();
        var probeCalls = new AtomicInteger();
        CancellationProbe probe = () -> {
            probeCalls.incrementAndGet();
            return false;
        };
        var raster = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);

        assertThrows(IllegalArgumentException.class,
                () -> sampleWide(sampler, raster, anchors, null, probe));
        assertThrows(IllegalArgumentException.class,
                () -> sampleWide(sampler, raster, anchors, Set.of(0), probe));
        assertThrows(IllegalArgumentException.class,
                () -> sampleWide(sampler, raster, anchors, Set.of(0, anchors.size()), probe));
        @SuppressWarnings({"rawtypes", "unchecked"})
        Set<Integer> malformed = (Set) new HashSet<>(Set.of(0, anchors.size() - 1, "not-an-index"));
        assertThrows(IllegalArgumentException.class,
                () -> sampleWide(sampler, raster, anchors, malformed, probe));
        assertEquals(0, probeCalls.get(), "Malformed interval authority must fail before the sampling loop");
    }

    @Test
    void degenerateWideChordFallsBackToTheImmediateNormal() {
        var anchors = degenerateChordAnchors();
        var raster = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);
        var sampler = new RenderedHeatmapSampler();
        var ordinary = sampler.sampleProfilesOnAnchors(raster, anchors, 4, 1, "hot", 1.0, 1.0,
                IntensitySamplingMode.DIRECT_VALUE, (RenderedHeatmapSampler.RasterCoordinateProjector) null,
                CancellationProbe.NONE);
        var wide = sampleWide(sampler, raster, anchors, Set.of(0, anchors.size() - 1), CancellationProbe.NONE);
        assertNormalEquals(ordinary.get(3), wide.get(3), "radius-three chord is finite but has zero length");
    }

    @Test
    void cancellationStillStopsNamedSamplingAtProfileBoundaries() {
        var anchors = gaussianFrameAnchors();
        var calls = new AtomicInteger();
        CancellationProbe probe = () -> calls.incrementAndGet() >= 3;
        assertThrows(CancellationException.class, () -> sampleWide(new RenderedHeatmapSampler(),
                new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB), anchors,
                Set.of(0, anchors.size() - 1), probe));
        assertEquals(3, calls.get());
    }

    @Test
    void wideSecantsRetainTheSupportedOppositeArc() {
        var anchors = circleAnchors(40.0, -75.0, 75.0, 5.0, 50.0, 50.0);
        var sampler = new RenderedHeatmapSampler();
        var profiles = sampleWide(sampler, new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB), anchors,
                Set.of(0, anchors.size() - 1), CancellationProbe.NONE);
        List<Point2D.Double> hits = circleIntersections(profiles, 50.0, 50.0, 34.0, 16.0);
        double previousAngle = Double.NEGATIVE_INFINITY;
        for (Point2D.Double hit : hits) {
            double angle = StrictMath.toDegrees(StrictMath.atan2(hit.y - 50.0, hit.x - 50.0));
            assertTrue(angle > previousAngle, "The radius-three frame must preserve the ordered opposite 150-degree arc");
            previousAngle = angle;
        }
    }

    private static List<ProfileSamplingAnchor> gaussianFrameAnchors() {
        var anchors = new ArrayList<ProfileSamplingAnchor>(19);
        for (int i = -9; i <= 9; i++) {
            double y = StrictMath.exp(-0.5 * i * i);
            anchors.add(new ProfileSamplingAnchor(new EastNorth(i, y), i + 32.0, y + 32.0, i + 9.0));
        }
        return List.copyOf(anchors);
    }

    private static List<ProfileSamplingAnchor> straightAnchors(int count) {
        var anchors = new ArrayList<ProfileSamplingAnchor>(count);
        for (int i = 0; i < count; i++) {
            double x = 20.0 + 3.0 * i;
            anchors.add(new ProfileSamplingAnchor(new EastNorth(100.0 + 3.0 * i, 200.0), x, 40.0, 3.0 * i));
        }
        return List.copyOf(anchors);
    }

    private static List<ProfileSamplingAnchor> degenerateChordAnchors() {
        double[][] xy = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {2, 1}, {1, 0}, {0, 0}};
        var anchors = new ArrayList<ProfileSamplingAnchor>(xy.length);
        for (int i = 0; i < xy.length; i++) {
            anchors.add(new ProfileSamplingAnchor(new EastNorth(xy[i][0], xy[i][1]),
                    xy[i][0] + 32.0, xy[i][1] + 32.0, i));
        }
        return List.copyOf(anchors);
    }

    private static List<ProfileSamplingAnchor> circleAnchors(double sourceRadius, double firstDegrees,
            double lastDegrees, double stepDegrees, double centerX, double centerY) {
        int count = (int) Math.round((lastDegrees - firstDegrees) / stepDegrees) + 1;
        var anchors = new ArrayList<ProfileSamplingAnchor>(count);
        double chainage = 0.0;
        Point2D.Double previous = null;
        for (int i = 0; i < count; i++) {
            double angle = StrictMath.toRadians(firstDegrees + i * stepDegrees);
            double x = centerX + sourceRadius * StrictMath.cos(angle);
            double y = centerY + sourceRadius * StrictMath.sin(angle);
            if (previous != null) chainage += previous.distance(x, y);
            anchors.add(new ProfileSamplingAnchor(new EastNorth(x, y), x, y, chainage));
            previous = new Point2D.Double(x, y);
        }
        return List.copyOf(anchors);
    }

    private static List<CrossSectionProfile> sampleWide(RenderedHeatmapSampler sampler, BufferedImage raster,
            List<ProfileSamplingAnchor> anchors, Set<Integer> boundaries, CancellationProbe cancellation) {
        return sampleWide(sampler, raster, anchors, boundaries, cancellation, null, 16, 1);
    }

    private static List<CrossSectionProfile> sampleWide(RenderedHeatmapSampler sampler, BufferedImage raster,
            List<ProfileSamplingAnchor> anchors, Set<Integer> boundaries, CancellationProbe cancellation,
            RenderedHeatmapSampler.RasterCoordinateProjector projector, int halfWidth, int step) {
        return sampler.sampleProfilesOnAnchorsWithWideSecants(raster, anchors, halfWidth, step, "hot", 1.0, 1.0,
                IntensitySamplingMode.DIRECT_VALUE,
                projector, cancellation, boundaries);
    }

    private static void assertNormalEquals(CrossSectionProfile expected, CrossSectionProfile actual, String message) {
        assertEquals(Double.doubleToRawLongBits(expected.normalScreen().x),
                Double.doubleToRawLongBits(actual.normalScreen().x), message + " x");
        assertEquals(Double.doubleToRawLongBits(expected.normalScreen().y),
                Double.doubleToRawLongBits(actual.normalScreen().y), message + " y");
    }

    private static List<Point2D.Double> horizontalIntersections(List<CrossSectionProfile> profiles,
            double y, double maximumDistance) {
        var intersections = new ArrayList<Point2D.Double>(profiles.size());
        for (CrossSectionProfile profile : profiles) {
            var anchor = profile.anchorScreen();
            var normal = profile.normalScreen();
            assertTrue(Math.abs(normal.y) > 1e-12, "Every analytic normal must intersect the horizontal target");
            double distance = (y - anchor.y) / normal.y;
            assertTrue(Double.isFinite(distance) && Math.abs(distance) <= maximumDistance,
                    "Target hit must remain inside the existing search radius: " + distance);
            intersections.add(new Point2D.Double(anchor.x + distance * normal.x, y));
        }
        return List.copyOf(intersections);
    }

    private static List<Point2D.Double> circleIntersections(List<CrossSectionProfile> profiles,
            double centerX, double centerY, double radius, double maximumDistance) {
        var hits = new ArrayList<Point2D.Double>(profiles.size());
        for (CrossSectionProfile profile : profiles) {
            var point = profile.anchorScreen();
            var normal = profile.normalScreen();
            double x = point.x - centerX;
            double y = point.y - centerY;
            double projection = x * normal.x + y * normal.y;
            double constant = x * x + y * y - radius * radius;
            double discriminant = projection * projection - constant;
            assertTrue(discriminant >= 0.0, "The circle target remains reachable on every normal");
            double root = StrictMath.sqrt(discriminant);
            double first = -projection - root;
            double second = -projection + root;
            double distance = Math.abs(first) <= Math.abs(second) ? first : second;
            assertTrue(Math.abs(distance) <= maximumDistance, "The target remains inside the existing search radius");
            hits.add(new Point2D.Double(point.x + distance * normal.x, point.y + distance * normal.y));
        }
        return List.copyOf(hits);
    }
}

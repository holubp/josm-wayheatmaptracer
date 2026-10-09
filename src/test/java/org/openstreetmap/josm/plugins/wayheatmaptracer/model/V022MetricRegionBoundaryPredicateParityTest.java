package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Independent historical predicate oracle for changing evaluation order only. */
class V022MetricRegionBoundaryPredicateParityTest {
    @Test
    void finiteOverflowSignedZeroAndSubnormalCasesKeepHistoricalResults() throws Exception {
        Method predicate = MetricRegion.class.getDeclaredMethod("onSegment",
                MetricPoint.class, MetricPoint.class, MetricPoint.class);
        predicate.setAccessible(true);
        double maximum = Double.MAX_VALUE;
        MetricPoint left = new MetricPoint(-maximum, 0), right = new MetricPoint(maximum, 0);
        // Subtraction and hypot overflow: Infinity <= Infinity remains historically true.
        assertEdgeParity(predicate, left, right, new MetricPoint(0, 1e-10), true);
        assertEdgeParity(predicate, left, right, new MetricPoint(0, 1), false);
        assertEdgeParity(predicate, left, right, new MetricPoint(0, 0), false);
        // Finite differences with overflowing hypot and products, including Infinity - Infinity.
        MetricPoint lower = new MetricPoint(-maximum * 0.5, -maximum * 0.5);
        MetricPoint upper = new MetricPoint(maximum * 0.5, maximum * 0.5);
        assertEdgeParity(predicate, lower, upper, lower, true);
        assertEdgeParity(predicate, lower, upper, new MetricPoint(0, 0), false);
        assertEdgeParity(predicate, new MetricPoint(-maximum, -maximum),
                new MetricPoint(maximum, maximum), new MetricPoint(0, 0), false);
        assertEdgeParity(predicate, new MetricPoint(0, 0), new MetricPoint(maximum, maximum),
                new MetricPoint(maximum, 0), true);
        assertEdgeParity(predicate, new MetricPoint(0, 0), new MetricPoint(maximum * 0.5, maximum),
                new MetricPoint(maximum, 0), false);
        assertEdgeParity(predicate, new MetricPoint(-0.0, 0.0), new MetricPoint(0.0, -0.0),
                new MetricPoint(-0.0, -0.0), true);
        assertEdgeParity(predicate, new MetricPoint(0, 0),
                new MetricPoint(Double.MIN_VALUE, Double.MIN_VALUE), new MetricPoint(0, 0), true);
    }

    @Test
    void edgeBoundsAndExtremeFiniteCoordinatesKeepHistoricalResults() throws Exception {
        Method predicate = MetricRegion.class.getDeclaredMethod("onSegment",
                MetricPoint.class, MetricPoint.class, MetricPoint.class);
        predicate.setAccessible(true);
        int index = 0;
        for (double scale : new double[] {Double.MIN_VALUE, 1e-12, 1.0, 1e100, 1e300}) {
            MetricPoint a = new MetricPoint(-scale, -scale);
            for (MetricPoint b : List.of(new MetricPoint(scale, -scale),
                    new MetricPoint(-scale, scale), new MetricPoint(scale, scale), a)) {
                for (MetricPoint point : List.of(a, b, new MetricPoint(0, 0),
                        new MetricPoint(Math.nextDown(Math.min(a.xMeters(), b.xMeters()) - 1e-9), 0),
                        new MetricPoint(Math.nextUp(Math.max(a.xMeters(), b.xMeters()) + 1e-9), 0),
                        new MetricPoint(0, Math.nextDown(Math.min(a.yMeters(), b.yMeters()) - 1e-9)),
                        new MetricPoint(0, Math.nextUp(Math.max(a.yMeters(), b.yMeters()) + 1e-9)))) {
                    assertEquals(historicalOnSegment(a, b, point), predicate.invoke(null, a, b, point),
                            "finite boundary case " + index++);
                }
            }
        }
        Random random = new Random(0x022_20261009L);
        for (int sample = 0; sample < 10_000; sample++) {
            MetricPoint a = finitePoint(random), b = finitePoint(random);
            MetricPoint point = sample % 3 == 0 ? a : sample % 3 == 1
                    ? new MetricPoint(a.xMeters() * 0.5 + b.xMeters() * 0.5,
                            a.yMeters() * 0.5 + b.yMeters() * 0.5)
                    : finitePoint(random);
            assertEquals(historicalOnSegment(a, b, point), predicate.invoke(null, a, b, point),
                    "finite edge sample " + sample);
        }
    }

    @Test
    void concaveAndMultiplePolygonContainmentKeepsHistoricalResults() {
        List<MetricRegion> regions = List.of(MetricRegion.rectangle(-3, -2, 3, 2),
                new MetricRegion(List.of(List.of(new MetricPoint(-3, -3), new MetricPoint(3, -3),
                        new MetricPoint(3, -1), new MetricPoint(-1, -1),
                        new MetricPoint(-1, 3), new MetricPoint(-3, 3)))),
                new MetricRegion(List.of(
                        List.of(new MetricPoint(-5, -2), new MetricPoint(-2, 0), new MetricPoint(-5, 2)),
                        List.of(new MetricPoint(1, -2), new MetricPoint(5, -2),
                                new MetricPoint(5, 2), new MetricPoint(1, 2)))));
        Random random = new Random(0x022_20261010L);
        for (MetricRegion region : regions) {
            assertEquals(false, region.contains(null));
            for (List<MetricPoint> polygon : region.polygons()) {
                for (MetricPoint vertex : polygon) {
                    for (double offset : new double[] {-1e-9, Math.nextUp(-1e-9), -0.0, 0.0,
                            Math.nextDown(1e-9), 1e-9}) {
                        assertContainmentParity(region,
                                new MetricPoint(vertex.xMeters() + offset, vertex.yMeters()));
                        assertContainmentParity(region,
                                new MetricPoint(vertex.xMeters(), vertex.yMeters() + offset));
                    }
                }
            }
            for (int sample = 0; sample < 5_000; sample++) {
                assertContainmentParity(region, new MetricPoint(random.nextDouble() * 16 - 8,
                        random.nextDouble() * 16 - 8));
            }
        }
    }

    private static MetricPoint finitePoint(Random random) {
        return new MetricPoint(Math.scalb(random.nextDouble() * 2 - 1, random.nextInt(2046) - 1022),
                Math.scalb(random.nextDouble() * 2 - 1, random.nextInt(2046) - 1022));
    }

    private static void assertEdgeParity(Method predicate, MetricPoint a, MetricPoint b,
            MetricPoint point, boolean expected) throws Exception {
        assertEquals(expected, historicalOnSegment(a, b, point), "independent historical result");
        assertEquals(expected, predicate.invoke(null, a, b, point), "reordered predicate result");
    }

    private static void assertContainmentParity(MetricRegion region, MetricPoint point) {
        assertEquals(region.polygons().stream().anyMatch(polygon -> historicalContains(polygon, point)),
                region.contains(point));
    }

    private static boolean historicalContains(List<MetricPoint> polygon, MetricPoint point) {
        boolean inside = false;
        for (int current = 0, previous = polygon.size() - 1; current < polygon.size(); previous = current++) {
            MetricPoint a = polygon.get(previous), b = polygon.get(current);
            if (historicalOnSegment(a, b, point)) return true;
            boolean crosses = (a.yMeters() > point.yMeters()) != (b.yMeters() > point.yMeters())
                    && point.xMeters() < (b.xMeters() - a.xMeters()) * (point.yMeters() - a.yMeters())
                    / (b.yMeters() - a.yMeters()) + a.xMeters();
            if (crosses) inside = !inside;
        }
        return inside;
    }

    private static boolean historicalOnSegment(MetricPoint a, MetricPoint b, MetricPoint point) {
        double cross = (b.xMeters() - a.xMeters()) * (point.yMeters() - a.yMeters())
                - (b.yMeters() - a.yMeters()) * (point.xMeters() - a.xMeters());
        double scale = Math.max(1.0, StrictMath.hypot(a.xMeters() - b.xMeters(), a.yMeters() - b.yMeters()));
        return Math.abs(cross) <= 1e-9 * scale
                && point.xMeters() >= Math.min(a.xMeters(), b.xMeters()) - 1e-9
                && point.xMeters() <= Math.max(a.xMeters(), b.xMeters()) + 1e-9
                && point.yMeters() >= Math.min(a.yMeters(), b.yMeters()) - 1e-9
                && point.yMeters() <= Math.max(a.yMeters(), b.yMeters()) + 1e-9;
    }
}

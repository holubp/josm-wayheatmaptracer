package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class V022MetricRegionIntersectionTest {
    @Test
    void segmentInteriorIntersectionDoesNotRequireContainedEndpoints() {
        MetricRegion region = MetricRegion.rectangle(-1.0, -1.0, 1.0, 1.0);

        assertTrue(region.intersectsSegment(new MetricPoint(-2.0, 0.0), new MetricPoint(2.0, 0.0)));
        assertFalse(region.intersectsSegment(new MetricPoint(-2.0, 2.0), new MetricPoint(2.0, 2.0)));
    }

    @Test
    void boundaryTouchAndCollinearOverlapCountAsIntersections() {
        MetricRegion region = MetricRegion.rectangle(-1.0, -1.0, 1.0, 1.0);

        assertTrue(region.intersectsSegment(new MetricPoint(-2.0, 2.0), new MetricPoint(-1.0, 1.0)));
        assertTrue(region.intersectsSegment(new MetricPoint(-2.0, 1.0), new MetricPoint(2.0, 1.0)));
    }

    @Test
    void nonRectangularUnionTestsEachExactPolygonAndPreservesGaps() {
        MetricRegion region = new MetricRegion(List.of(
            List.of(new MetricPoint(-4.0, -2.0), new MetricPoint(-1.0, 0.0),
                new MetricPoint(-4.0, 2.0)),
            List.of(new MetricPoint(1.0, -1.0), new MetricPoint(4.0, -1.0),
                new MetricPoint(4.0, 1.0), new MetricPoint(1.0, 1.0))));

        assertTrue(region.intersectsSegment(new MetricPoint(-5.0, 0.0), new MetricPoint(-2.0, 0.0)));
        assertTrue(region.intersectsSegment(new MetricPoint(0.0, 0.0), new MetricPoint(2.0, 0.0)));
        assertFalse(region.intersectsSegment(new MetricPoint(-0.8, -0.5), new MetricPoint(0.8, 0.5)));
    }
}

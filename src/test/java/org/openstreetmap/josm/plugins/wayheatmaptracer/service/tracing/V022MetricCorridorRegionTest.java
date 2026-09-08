package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;

/** Physical-bound regressions for modern-engine decision regions. */
class V022MetricCorridorRegionTest {
    @Test
    void diagonalAndTurningSegmentsAreCoveredWithoutExpandingToTheirBoundingBox() {
        MetricRegion region = MetricCorridorRegion.aroundPolyline(List.of(
                new MetricPoint(0, 0), new MetricPoint(10, 10), new MetricPoint(20, 0)), 3.0);

        assertTrue(region.containsSegment(new MetricPoint(0, 0), new MetricPoint(10, 10)));
        assertTrue(region.contains(new MetricPoint(10, 12)));
        assertFalse(region.contains(new MetricPoint(0, 10)));
        assertFalse(region.contains(new MetricPoint(10, -8)));
    }
}

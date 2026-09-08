package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;

/** Builds a bounded union of segment-aligned metric rectangles around a route proposal. */
public final class MetricCorridorRegion {
    private MetricCorridorRegion() {
    }

    /**
     * Creates a conservative decision region extending {@code radiusMeters} from each source segment.
     * Square end caps intentionally preserve exact segment containment without pretending to provide
     * positional evidence outside the acquired raster.
     */
    public static MetricRegion aroundPolyline(List<MetricPoint> points, double radiusMeters) {
        if (points == null || points.size() < 2 || !Double.isFinite(radiusMeters) || radiusMeters <= 0.0) {
            throw new IllegalArgumentException("A nondegenerate polyline and positive metric radius are required");
        }
        List<List<MetricPoint>> polygons = new ArrayList<>();
        for (int index = 1; index < points.size(); index++) {
            MetricPoint start = points.get(index - 1);
            MetricPoint end = points.get(index);
            double dx = end.xMeters() - start.xMeters();
            double dy = end.yMeters() - start.yMeters();
            double length = Math.hypot(dx, dy);
            if (length <= 1.0e-9) {
                continue;
            }
            double alongX = dx / length * radiusMeters;
            double alongY = dy / length * radiusMeters;
            double normalX = -dy / length * radiusMeters;
            double normalY = dx / length * radiusMeters;
            polygons.add(List.of(
                    point(start, -alongX + normalX, -alongY + normalY),
                    point(end, alongX + normalX, alongY + normalY),
                    point(end, alongX - normalX, alongY - normalY),
                    point(start, -alongX - normalX, -alongY - normalY)));
        }
        if (polygons.isEmpty()) {
            MetricPoint point = points.get(0);
            return MetricRegion.rectangle(point.xMeters() - radiusMeters,
                    point.yMeters() - radiusMeters, point.xMeters() + radiusMeters,
                    point.yMeters() + radiusMeters);
        }
        return new MetricRegion(polygons);
    }

    private static MetricPoint point(MetricPoint base, double dx, double dy) {
        return new MetricPoint(base.xMeters() + dx, base.yMeters() + dy);
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;

/** Experiment-only closed-rectangle predicate; never a source capability. */
final class BExactRectangleContainment {
    private BExactRectangleContainment() { }

    static boolean contains(MetricRegion rectangle, MetricRegion operand) {
        if (rectangle == null || operand == null)
            throw new IllegalArgumentException("Rectangle and operand are required");
        return containsVertices(rectangle.polygons(), operand.polygons());
    }

    static boolean containsVertices(List<List<MetricPoint>> rectangle,
            List<List<MetricPoint>> operand) {
        if (rectangle == null || rectangle.size() != 1 || rectangle.get(0) == null
                || rectangle.get(0).size() != 4 || operand == null || operand.isEmpty())
            throw new IllegalArgumentException("One four-corner rectangle and polygon operands are required");
        List<MetricPoint> corners = rectangle.get(0);
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (MetricPoint point : corners) {
            requireFinite(point);
            minX = Math.min(minX, point.xMeters()); maxX = Math.max(maxX, point.xMeters());
            minY = Math.min(minY, point.yMeters()); maxY = Math.max(maxY, point.yMeters());
        }
        if (!(minX < maxX) || !(minY < maxY))
            throw new IllegalArgumentException("Rectangle must have positive width and height");
        int seen = 0;
        for (int index = 0; index < 4; index++) {
            MetricPoint point = corners.get(index), next = corners.get((index + 1) % 4);
            double x = point.xMeters(), y = point.yMeters();
            if ((x != minX && x != maxX) || (y != minY && y != maxY)
                    || (x == next.xMeters()) == (y == next.yMeters()))
                throw new IllegalArgumentException("Rectangle corners must be cyclic and axis aligned");
            int corner = (x == maxX ? 1 : 0) | (y == maxY ? 2 : 0);
            if ((seen & (1 << corner)) != 0)
                throw new IllegalArgumentException("Rectangle corners must be distinct");
            seen |= 1 << corner;
        }
        for (List<MetricPoint> polygon : operand) {
            if (polygon == null || polygon.size() < 3)
                throw new IllegalArgumentException("Operand polygons need at least three vertices");
            for (MetricPoint point : polygon) {
                requireFinite(point);
                if (point.xMeters() < minX || point.xMeters() > maxX
                        || point.yMeters() < minY || point.yMeters() > maxY) return false;
            }
        }
        return true;
    }

    private static void requireFinite(MetricPoint point) {
        if (point == null || !Double.isFinite(point.xMeters()) || !Double.isFinite(point.yMeters()))
            throw new IllegalArgumentException("Finite original vertices are required");
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image;

import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;

/** Geometric safety predicates shared by image route search and later final validation. */
public final class DirectionalImageGeometry {
    private static final double EPSILON = 1e-9;

    private DirectionalImageGeometry() {
    }

    /** Proves that a complete candidate segment remains inside the admitted decision polygon. */
    public static boolean segmentAuthorized(MetricRegion region, MetricPoint start, MetricPoint end) {
        return region != null && region.containsSegment(start, end);
    }

    /** Detects proper crossings, endpoint touches, and collinear overlaps between nonadjacent edges. */
    public static boolean hasSelfIntersection(List<MetricPoint> points) {
        for (int first = 0; first + 1 < points.size(); first++) {
            for (int second = first + 2; second + 1 < points.size(); second++) {
                if (intersects(points.get(first), points.get(first + 1), points.get(second), points.get(second + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean intersects(MetricPoint a, MetricPoint b, MetricPoint c, MetricPoint d) {
        double abC = cross(a, b, c);
        double abD = cross(a, b, d);
        double cdA = cross(c, d, a);
        double cdB = cross(c, d, b);
        if ((abC > EPSILON && abD < -EPSILON || abC < -EPSILON && abD > EPSILON)
            && (cdA > EPSILON && cdB < -EPSILON || cdA < -EPSILON && cdB > EPSILON)) {
            return true;
        }
        return Math.abs(abC) <= EPSILON && on(a, b, c) || Math.abs(abD) <= EPSILON && on(a, b, d)
            || Math.abs(cdA) <= EPSILON && on(c, d, a) || Math.abs(cdB) <= EPSILON && on(c, d, b);
    }

    private static double cross(MetricPoint a, MetricPoint b, MetricPoint c) {
        return (b.xMeters() - a.xMeters()) * (c.yMeters() - a.yMeters())
            - (b.yMeters() - a.yMeters()) * (c.xMeters() - a.xMeters());
    }

    private static boolean on(MetricPoint a, MetricPoint b, MetricPoint point) {
        return point.xMeters() >= Math.min(a.xMeters(), b.xMeters()) - EPSILON
            && point.xMeters() <= Math.max(a.xMeters(), b.xMeters()) + EPSILON
            && point.yMeters() >= Math.min(a.yMeters(), b.yMeters()) - EPSILON
            && point.yMeters() <= Math.max(a.yMeters(), b.yMeters()) + EPSILON;
    }
}

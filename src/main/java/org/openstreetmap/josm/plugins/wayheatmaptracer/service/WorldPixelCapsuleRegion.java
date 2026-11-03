package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Exact-radius source segment rectangles with circular turn and endpoint caps in world pixels. */
final class WorldPixelCapsuleRegion {
    private record Interval(double first, double last) { }
    private final List<RasterPoint> source;
    private final double radius;

    WorldPixelCapsuleRegion(List<RasterPoint> source, double radius) {
        if (source == null || source.size() < 2 || !Double.isFinite(radius) || radius <= 0.0) {
            throw new IllegalArgumentException("Original source search needs finite positive radius");
        }
        this.source = List.copyOf(source);
        this.radius = radius;
        for (int i = 1; i < source.size(); i++) {
            if (source.get(i).equals(source.get(i - 1))) {
                throw new IllegalArgumentException("Original source has unresolved zero-length segment");
            }
        }
    }

    boolean contains(RasterPoint point) {
        for (int i = 1; i < source.size(); i++) {
            RasterPoint a = source.get(i - 1), b = source.get(i);
            double dx = b.x() - a.x(), dy = b.y() - a.y();
            double t = Math.max(0.0, Math.min(1.0,
                    ((point.x() - a.x()) * dx + (point.y() - a.y()) * dy)
                    / (dx * dx + dy * dy)));
            if (Math.hypot(point.x() - (a.x() + t * dx),
                    point.y() - (a.y() + t * dy)) <= radius) return true;
        }
        return false;
    }

    boolean containsSegment(RasterPoint start, RasterPoint end, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner scratchOwner) {
        budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, 1);
        if (start.x() == end.x() && start.y() == end.y()) {
            budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, source.size() - 1L);
            return contains(start);
        }
        long capacity = Math.multiplyExact((long) source.size() - 1L, 3L);
        long bytes = Math.addExact(256L, Math.multiplyExact(capacity, 48L));
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        try (var scratch = scratchOwner.reserve(bytes)) {
            List<Interval> intervals = new ArrayList<>(Math.toIntExact(capacity));
            double qx = start.x(), qy = start.y();
            double dx = end.x() - qx, dy = end.y() - qy;
            for (int i = 1; i < source.size(); i++) {
                budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, 3);
                RasterPoint a = source.get(i - 1), b = source.get(i);
                appendDisk(intervals, qx, qy, dx, dy, a.x(), a.y());
                appendDisk(intervals, qx, qy, dx, dy, b.x(), b.y());
                appendStrip(intervals, qx, qy, dx, dy, a, b);
            }
            if (intervals.isEmpty()) return false;
            int n = intervals.size();
            budget.charge(EmissionWorkBudget.Operation.REGION_QUERY,
                    Math.multiplyExact((long) n, 1L + (32 - Integer.numberOfLeadingZeros(n))));
            intervals.sort(Comparator.comparingDouble(Interval::first));
            double reached = 0.0;
            for (Interval interval : intervals) {
                budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, 1);
                if (interval.first() > reached) return false;
                reached = Math.max(reached, interval.last());
                if (reached >= 1.0) return true;
            }
            return false;
        }
    }

    boolean withinRectangle(double minX, double minY, double maxX, double maxY) {
        for (RasterPoint point : source) {
            if (point.x() - radius < minX || point.x() + radius > maxX
                    || point.y() - radius < minY || point.y() + radius > maxY) return false;
        }
        return true;
    }

    private void appendDisk(List<Interval> intervals, double qx, double qy,
            double dx, double dy, double cx, double cy) {
        double vx = qx - cx, vy = qy - cy;
        double a = dx * dx + dy * dy;
        double b = 2.0 * (vx * dx + vy * dy);
        double c = vx * vx + vy * vy - radius * radius;
        double discriminant = b * b - 4.0 * a * c;
        if (discriminant < 0.0) return;
        double root = Math.sqrt(discriminant);
        append(intervals, (-b - root) / (2.0 * a), (-b + root) / (2.0 * a));
    }

    private void appendStrip(List<Interval> intervals, double qx, double qy,
            double dx, double dy, RasterPoint a, RasterPoint b) {
        double sx = b.x() - a.x(), sy = b.y() - a.y();
        double length = Math.hypot(sx, sy);
        double ux = sx / length, uy = sy / length, nx = -uy, ny = ux;
        double ax = qx - a.x(), ay = qy - a.y();
        Interval clipped = clip(new Interval(0.0, 1.0), ax * ux + ay * uy,
                dx * ux + dy * uy, 0.0, length);
        if (clipped != null) clipped = clip(clipped, ax * nx + ay * ny,
                dx * nx + dy * ny, -radius, radius);
        if (clipped != null) intervals.add(clipped);
    }

    private static Interval clip(Interval interval, double initial, double slope,
            double lower, double upper) {
        if (slope == 0.0) return initial >= lower && initial <= upper ? interval : null;
        double first = (lower - initial) / slope;
        double last = (upper - initial) / slope;
        if (first > last) { double swap = first; first = last; last = swap; }
        double clippedFirst = Math.max(interval.first(), first);
        double clippedLast = Math.min(interval.last(), last);
        return clippedFirst <= clippedLast ? new Interval(clippedFirst, clippedLast) : null;
    }

    private static void append(List<Interval> intervals, double first, double last) {
        double start = Math.max(0.0, first), end = Math.min(1.0, last);
        if (start <= end) intervals.add(new Interval(start, end));
    }
}

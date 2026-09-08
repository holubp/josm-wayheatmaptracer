package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.awt.geom.Area;
import java.awt.geom.Path2D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable union of non-self-intersecting closed metric polygons without holes. */
public final class MetricRegion {
    private static final double EPSILON = 1e-10;
    private final List<List<MetricPoint>> polygons;

    /** Creates a union from nondegenerate simple polygons with at least three vertices each. */
    public MetricRegion(List<List<MetricPoint>> polygons) {
        if (polygons == null || polygons.isEmpty()) {
            throw new IllegalArgumentException("A metric region requires at least one polygon");
        }
        List<List<MetricPoint>> copy = new ArrayList<>(polygons.size());
        for (List<MetricPoint> source : polygons) {
            if (source == null || source.size() < 3 || source.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("A metric polygon needs at least three finite vertices");
            }
            List<MetricPoint> polygon = List.copyOf(source);
            validateSimplePolygon(polygon);
            copy.add(polygon);
        }
        this.polygons = List.copyOf(copy);
    }

    /** Creates an axis-aligned closed rectangle with positive area. */
    public static MetricRegion rectangle(double minX, double minY, double maxX, double maxY) {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(maxX)
            || !Double.isFinite(maxY) || minX >= maxX || minY >= maxY) {
            throw new IllegalArgumentException("Rectangle bounds must be finite and strictly ordered");
        }
        return new MetricRegion(List.of(List.of(new MetricPoint(minX, minY), new MetricPoint(maxX, minY),
            new MetricPoint(maxX, maxY), new MetricPoint(minX, maxY))));
    }

    /** Returns the deeply immutable polygon union. */
    public List<List<MetricPoint>> polygons() {
        return polygons;
    }

    /** Returns whether any polygon contains the point, including its boundary. */
    public boolean contains(MetricPoint point) {
        return point != null && polygons.stream().anyMatch(polygon -> contains(polygon, point));
    }

    /** Proves a whole segment lies inside the polygon union without discrete-step gaps. */
    public boolean containsSegment(MetricPoint start, MetricPoint end) {
        if (!contains(start) || !contains(end)) {
            return false;
        }
        List<Double> events = new ArrayList<>();
        events.add(0.0);
        events.add(1.0);
        for (List<MetricPoint> polygon : polygons) {
            for (int index = 0; index < polygon.size(); index++) {
                addIntersectionParameters(start, end, polygon.get(index),
                    polygon.get((index + 1) % polygon.size()), events);
            }
        }
        Collections.sort(events);
        for (int index = 1; index < events.size(); index++) {
            double left = events.get(index - 1);
            double right = events.get(index);
            if (right - left > EPSILON && !contains(interpolate(start, end, (left + right) / 2.0))) {
                return false;
            }
        }
        return true;
    }

    /** Returns whether every boundary and interior of another hole-free polygon union is contained. */
    public boolean containsRegion(MetricRegion other) {
        if (other == null) {
            return false;
        }
        Area remainder = other.toArea();
        remainder.subtract(toArea());
        return remainder.isEmpty();
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof MetricRegion other && polygons.equals(other.polygons);
    }

    @Override
    public int hashCode() {
        return polygons.hashCode();
    }

    private static void validateSimplePolygon(List<MetricPoint> polygon) {
        double twiceArea = 0.0;
        for (int index = 0; index < polygon.size(); index++) {
            MetricPoint current = polygon.get(index);
            MetricPoint next = polygon.get((index + 1) % polygon.size());
            if (current.distanceTo(next) <= EPSILON) {
                throw new IllegalArgumentException("Metric polygon contains a zero-length edge");
            }
            twiceArea += current.xMeters() * next.yMeters() - next.xMeters() * current.yMeters();
        }
        if (Math.abs(twiceArea) <= EPSILON) {
            throw new IllegalArgumentException("Metric polygon must have positive area");
        }
        int size = polygon.size();
        for (int first = 0; first < size; first++) {
            MetricPoint a = polygon.get(first);
            MetricPoint b = polygon.get((first + 1) % size);
            for (int second = first + 1; second < size; second++) {
                if (second == first || second == (first + 1) % size
                    || first == (second + 1) % size) {
                    continue;
                }
                MetricPoint c = polygon.get(second);
                MetricPoint d = polygon.get((second + 1) % size);
                if (segmentsIntersect(a, b, c, d)) {
                    throw new IllegalArgumentException("Metric polygon must not self-intersect");
                }
            }
        }
    }

    private static boolean segmentsIntersect(MetricPoint a, MetricPoint b, MetricPoint c, MetricPoint d) {
        double abC = orientation(a, b, c);
        double abD = orientation(a, b, d);
        double cdA = orientation(c, d, a);
        double cdB = orientation(c, d, b);
        if ((abC > EPSILON && abD < -EPSILON || abC < -EPSILON && abD > EPSILON)
            && (cdA > EPSILON && cdB < -EPSILON || cdA < -EPSILON && cdB > EPSILON)) {
            return true;
        }
        return Math.abs(abC) <= EPSILON && onSegment(a, b, c)
            || Math.abs(abD) <= EPSILON && onSegment(a, b, d)
            || Math.abs(cdA) <= EPSILON && onSegment(c, d, a)
            || Math.abs(cdB) <= EPSILON && onSegment(c, d, b);
    }

    private static double orientation(MetricPoint a, MetricPoint b, MetricPoint c) {
        return cross(b.xMeters() - a.xMeters(), b.yMeters() - a.yMeters(),
            c.xMeters() - a.xMeters(), c.yMeters() - a.yMeters());
    }


    private Area toArea() {
        Area result = new Area();
        for (List<MetricPoint> polygon : polygons) {
            Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
            path.moveTo(polygon.get(0).xMeters(), polygon.get(0).yMeters());
            for (int index = 1; index < polygon.size(); index++) {
                path.lineTo(polygon.get(index).xMeters(), polygon.get(index).yMeters());
            }
            path.closePath();
            result.add(new Area(path));
        }
        return result;
    }

    private static MetricPoint interpolate(MetricPoint start, MetricPoint end, double fraction) {
        return new MetricPoint(start.xMeters() + fraction * (end.xMeters() - start.xMeters()),
            start.yMeters() + fraction * (end.yMeters() - start.yMeters()));
    }

    private static void addIntersectionParameters(MetricPoint p, MetricPoint q, MetricPoint a,
        MetricPoint b, List<Double> events) {
        double rx = q.xMeters() - p.xMeters();
        double ry = q.yMeters() - p.yMeters();
        double sx = b.xMeters() - a.xMeters();
        double sy = b.yMeters() - a.yMeters();
        double denominator = cross(rx, ry, sx, sy);
        double apx = a.xMeters() - p.xMeters();
        double apy = a.yMeters() - p.yMeters();
        if (Math.abs(denominator) <= EPSILON) {
            if (Math.abs(cross(apx, apy, rx, ry)) <= EPSILON) {
                double lengthSquared = rx * rx + ry * ry;
                if (lengthSquared > EPSILON) {
                    addEvent(events, (apx * rx + apy * ry) / lengthSquared);
                    addEvent(events, ((b.xMeters() - p.xMeters()) * rx
                        + (b.yMeters() - p.yMeters()) * ry) / lengthSquared);
                }
            }
            return;
        }
        double t = cross(apx, apy, sx, sy) / denominator;
        double u = cross(apx, apy, rx, ry) / denominator;
        if (t >= -EPSILON && t <= 1.0 + EPSILON && u >= -EPSILON && u <= 1.0 + EPSILON) {
            addEvent(events, t);
        }
    }

    private static void addEvent(List<Double> events, double value) {
        if (value >= -EPSILON && value <= 1.0 + EPSILON) {
            double bounded = Math.max(0.0, Math.min(1.0, value));
            if (events.stream().noneMatch(existing -> Math.abs(existing - bounded) <= EPSILON)) {
                events.add(bounded);
            }
        }
    }

    private static double cross(double ax, double ay, double bx, double by) {
        return ax * by - ay * bx;
    }

    private static boolean contains(List<MetricPoint> polygon, MetricPoint point) {
        boolean inside = false;
        for (int current = 0, previous = polygon.size() - 1; current < polygon.size(); previous = current++) {
            MetricPoint a = polygon.get(previous);
            MetricPoint b = polygon.get(current);
            if (onSegment(a, b, point)) {
                return true;
            }
            boolean crosses = (a.yMeters() > point.yMeters()) != (b.yMeters() > point.yMeters())
                && point.xMeters() < (b.xMeters() - a.xMeters()) * (point.yMeters() - a.yMeters())
                    / (b.yMeters() - a.yMeters()) + a.xMeters();
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean onSegment(MetricPoint a, MetricPoint b, MetricPoint point) {
        double cross = orientation(a, b, point);
        double scale = Math.max(1.0, a.distanceTo(b));
        return Math.abs(cross) <= 1e-9 * scale
            && point.xMeters() >= Math.min(a.xMeters(), b.xMeters()) - 1e-9
            && point.xMeters() <= Math.max(a.xMeters(), b.xMeters()) + 1e-9
            && point.yMeters() >= Math.min(a.yMeters(), b.yMeters()) - 1e-9
            && point.yMeters() <= Math.max(a.yMeters(), b.yMeters()) + 1e-9;
    }
}

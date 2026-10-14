package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.awt.geom.Area;
import java.awt.geom.Path2D;

import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.LongConsumer;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Immutable union of non-self-intersecting closed metric polygons without holes. */
public final class MetricRegion {
    private static final double EPSILON = 1e-10;
    private final List<List<MetricPoint>> polygons;

    /** Immutable bounds borrowed only while their creating attempt owner remains alive. */
    public static final class YMembershipIndex {
        private final MetricRegion region;
        private final double[] lower, upper;
        private YMembershipIndex(MetricRegion region, double[] lower, double[] upper) {
            this.region = region; this.lower = lower; this.upper = upper;
        }
    }

    /** Prepares owned point bounds; segment edge events always remain complete. */
    public YMembershipIndex prepareYMembership(CancellationProbe cancellation,
            LongConsumer admission, AttemptMemoryLedger.Owner owner) {
        if (cancellation == null || admission == null)
            throw new IllegalArgumentException("Membership work controls are required");
        cancellation.checkpoint();
        admission.accept(1L);
        AttemptMemoryLedger.Owner inventory = owner == null ? null : owner.child("region-y-membership");
        boolean complete = false;
        try {
            cancellation.checkpoint();
            admission.accept(Math.addExact(Math.multiplyExact(2L, polygons.size()), 1L));
            if (inventory != null) inventory.reserve(Math.addExact(AttemptMemoryLedger.objectBytes(24),
                    Math.multiplyExact(2L, AttemptMemoryLedger.arrayBytes(polygons.size(), Double.BYTES))));
            double[] lower = new double[polygons.size()];
            double[] upper = new double[polygons.size()];
            for (int index = 0; index < polygons.size(); index++) {
                cancellation.checkpoint(); admission.accept(1L);
                List<MetricPoint> polygon = polygons.get(index);
                double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
                for (int vertex = 0; vertex < polygon.size(); vertex++) {
                    cancellation.checkpoint(); admission.accept(1L);
                    double y = polygon.get(vertex).yMeters();
                    minimum = Math.min(minimum, y); maximum = Math.max(maximum, y);
                }
                // Exactly the original onSegment Y bounds, including conservative overflow.
                lower[index] = minimum - 1e-9;
                upper[index] = maximum + 1e-9;
            }
            YMembershipIndex result = new YMembershipIndex(this, lower, upper);
            complete = true;
            return result;
        } finally {
            if (!complete && inventory != null) inventory.close();
        }
    }

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
        return containsSegment(start, end, CancellationProbe.NONE, ignored -> { }, null);
    }

    /**
     * Proves segment authority with owned scratch and cooperatively bounded geometry work.
     * The size-based sorting charge is an nlogn proxy, not a measured comparison count.
     */
    public boolean containsSegment(MetricPoint start, MetricPoint end,
            CancellationProbe cancellation, LongConsumer admission, AttemptMemoryLedger.Owner owner) {
        return containsSegment(start, end, cancellation, admission, owner, false, null);
    }

    /**
     * Proves the same segment authority with explicitly owned event storage grown only
     * for distinct observed events. Intended for modern raster interval evidence.
     */
    public boolean containsSegmentWithBoundedEvents(MetricPoint start, MetricPoint end,
            CancellationProbe cancellation, LongConsumer admission, AttemptMemoryLedger.Owner owner) {
        return containsSegment(start, end, cancellation, admission, owner, true, null);
    }

    /** Uses owned bounds for membership only; foreign indices retain the original predicate. */
    public boolean containsSegmentWithBoundedEvents(MetricPoint start, MetricPoint end,
            CancellationProbe cancellation, LongConsumer admission, AttemptMemoryLedger.Owner owner,
            YMembershipIndex membershipIndex) {
        return containsSegment(start, end, cancellation, admission, owner, true, membershipIndex);
    }

    private boolean containsSegment(MetricPoint start, MetricPoint end,
            CancellationProbe cancellation, LongConsumer admission, AttemptMemoryLedger.Owner owner,
            boolean boundedEvents, YMembershipIndex membershipIndex) {
        if (cancellation == null || admission == null) {
            throw new IllegalArgumentException("Segment authority work controls are required");
        }
        cancellation.checkpoint();
        long edges = 0L;
        for (List<MetricPoint> polygon : polygons) {
            cancellation.checkpoint();
            admission.accept(1L);
            edges = Math.addExact(edges, polygon.size());
        }
        long eventBound = Math.addExact(2L, Math.multiplyExact(2L, edges));
        if (eventBound > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Segment authority inventory is too large");
        }
        AttemptMemoryLedger.Owner scratch = owner == null ? null : owner.child("segment-authority");
        try {
        // Bounded storage also needs one live deduplication iterator (three ints and
        // its list reference), independently of the separately reserved sort scratch.
        long bytes = boundedEvents
                ? AttemptMemoryLedger.objectBytes(56) + AttemptMemoryLedger.objectBytes(16)
                    + AttemptMemoryLedger.objectBytes(24)
                : Math.addExact(AttemptMemoryLedger.arrayBytes(eventBound, 2L * Long.BYTES),
                Math.addExact(AttemptMemoryLedger.objectBytes(24),
                    Math.multiplyExact(eventBound, 2L * AttemptMemoryLedger.objectBytes(16))));
        if (scratch != null) scratch.reserve(bytes);
        if (!contains(start, cancellation, admission, membershipIndex)
                || !contains(end, cancellation, admission, membershipIndex)) {
            return false;
        }
        if (boundedEvents) admission.accept(1L); // Facade allocation, before construction.
        else admission.accept(eventBound);
        List<Double> events = boundedEvents
                ? new BoundedEventList((int) eventBound, cancellation, admission, scratch)
                : new ArrayList<>((int) eventBound);
        appendEvent(events, 0.0);
        appendEvent(events, 1.0);
        for (List<MetricPoint> polygon : polygons) {
            for (int index = 0; index < polygon.size(); index++) {
                cancellation.checkpoint();
                admission.accept(1L);
                addIntersectionParameters(start, end, polygon.get(index),
                    polygon.get((index + 1) % polygon.size()), events, cancellation, admission);
            }
        }
        cancellation.checkpoint();
        admission.accept(Math.multiplyExact((long) events.size(),
                64L - Long.numberOfLeadingZeros(events.size())));
        Collections.sort(events);
        cancellation.checkpoint();
        for (int index = 1; index < events.size(); index++) {
            cancellation.checkpoint();
            admission.accept(1L);
            double left = events.get(index - 1);
            double right = events.get(index);
            if (right - left > EPSILON && !contains(interpolate(start, end, (left + right) / 2.0),
                    cancellation, admission, membershipIndex)) {
                return false;
            }
        }
        return true;
        } finally {
            if (scratch != null) scratch.close();
        }
    }

    /** Keeps ArrayList's exact sort while preventing every implicit backing-array resize. */
    private static final class BoundedEventList extends AbstractList<Double> implements RandomAccess {
        private final int bound;
        private final CancellationProbe cancellation;
        private final LongConsumer admission;
        private final AttemptMemoryLedger.Owner owner;
        private ArrayList<Double> values;
        private AttemptMemoryLedger.Reservation storage;
        private int capacity;

        BoundedEventList(int bound, CancellationProbe cancellation, LongConsumer admission,
                AttemptMemoryLedger.Owner owner) {
            this.bound = bound; this.cancellation = cancellation;
            this.admission = admission; this.owner = owner;
            capacity = 2;
            cancellation.checkpoint();
            admission.accept(capacity + 1L); // Backing slots and the ArrayList object.
            storage = reserveStorage(capacity);
            values = new ArrayList<>(capacity);
        }

        private AttemptMemoryLedger.Reservation reserveStorage(int slots) {
            return owner == null ? null : owner.reserve(Math.addExact(
                    AttemptMemoryLedger.objectBytes(24), AttemptMemoryLedger.referenceArrayBytes(slots)));
        }

        void append(double value) {
            cancellation.checkpoint();
            if (values.size() == capacity) grow();
            admission.accept(1L); // A new boxed event and its occupied slot.
            if (owner != null) owner.reserve(AttemptMemoryLedger.objectBytes(Double.BYTES));
            values.add(Double.valueOf(value));
            modCount++;
        }

        private void grow() {
            if (capacity >= bound) throw new IllegalArgumentException("Segment event inventory exceeded");
            int next = Math.toIntExact(Math.min((long) bound, Math.multiplyExact(2L, capacity)));
            cancellation.checkpoint();
            admission.accept(next + 1L);
            var nextStorage = reserveStorage(next); // Old and new storage coexist through copying.
            ArrayList<Double> replacement = new ArrayList<>(next);
            for (int index = 0; index < values.size(); index++) {
                cancellation.checkpoint();
                admission.accept(2L); // Read old reference, then write it into new storage.
                replacement.add(values.get(index));
            }
            values = replacement;
            capacity = next;
            if (storage != null) storage.close();
            storage = nextStorage;
        }

        @Override public Double get(int index) { return values.get(index); }
        @Override public int size() { return values.size(); }
        @Override public void sort(Comparator<? super Double> comparator) {
            cancellation.checkpoint();
            // Retain the existing conservative opaque-sort inventory, now sized by
            // actual events. Boxed values and live backing storage are owned separately.
            long bytes = Math.addExact(AttemptMemoryLedger.arrayBytes(size(), 2L * Long.BYTES),
                    Math.addExact(AttemptMemoryLedger.objectBytes(24),
                        Math.multiplyExact((long) size(), 2L * AttemptMemoryLedger.objectBytes(16))));
            try (var sortScratch = owner == null ? null : owner.reserve(bytes)) {
                values.sort(comparator);
            }
            cancellation.checkpoint();
        }
    }

    private static void appendEvent(List<Double> events, double value) {
        if (events instanceof BoundedEventList bounded) bounded.append(value);
        else events.add(value);
    }

    private boolean contains(MetricPoint point, CancellationProbe cancellation, LongConsumer admission) {
        if (point == null) return false;
        for (List<MetricPoint> polygon : polygons) {
            if (contains(polygon, point, cancellation, admission)) return true;
        }
        return false;
    }

    private boolean contains(MetricPoint point, CancellationProbe cancellation, LongConsumer admission,
            YMembershipIndex index) {
        if (index == null || point == null) return contains(point, cancellation, admission);
        cancellation.checkpoint(); admission.accept(1L);
        if (index.region != this) return contains(point, cancellation, admission);
        for (int polygonIndex = 0; polygonIndex < polygons.size(); polygonIndex++) {
            cancellation.checkpoint();
            admission.accept(2L); // Both comparisons precede even an early outside result.
            if (point.yMeters() < index.lower[polygonIndex]
                    || point.yMeters() > index.upper[polygonIndex]) continue;
            if (contains(polygons.get(polygonIndex), point, cancellation, admission)) return true;
        }
        return false;
    }

    /** Returns whether a segment touches or crosses any polygon in the union. */
    public boolean intersectsSegment(MetricPoint start, MetricPoint end) {
        if (start == null || end == null) {
            return false;
        }
        if (contains(start) || contains(end)) {
            return true;
        }
        for (List<MetricPoint> polygon : polygons) {
            for (int index = 0; index < polygon.size(); index++) {
                if (segmentsIntersect(start, end, polygon.get(index),
                    polygon.get((index + 1) % polygon.size()))) {
                    return true;
                }
            }
        }
        return false;
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
        MetricPoint b, List<Double> events, CancellationProbe cancellation, LongConsumer admission) {
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
                    addEvent(events, (apx * rx + apy * ry) / lengthSquared, cancellation, admission);
                    addEvent(events, ((b.xMeters() - p.xMeters()) * rx
                        + (b.yMeters() - p.yMeters()) * ry) / lengthSquared, cancellation, admission);
                }
            }
            return;
        }
        double t = cross(apx, apy, sx, sy) / denominator;
        double u = cross(apx, apy, rx, ry) / denominator;
        if (t >= -EPSILON && t <= 1.0 + EPSILON && u >= -EPSILON && u <= 1.0 + EPSILON) {
            addEvent(events, t, cancellation, admission);
        }
    }

    private static void addEvent(List<Double> events, double value,
            CancellationProbe cancellation, LongConsumer admission) {
        if (value >= -EPSILON && value <= 1.0 + EPSILON) {
            double bounded = Math.max(0.0, Math.min(1.0, value));
            for (double existing : events) {
                cancellation.checkpoint();
                admission.accept(1L);
                if (Math.abs(existing - bounded) <= EPSILON) return;
            }
            appendEvent(events, bounded);
        }
    }

    private static double cross(double ax, double ay, double bx, double by) {
        return ax * by - ay * bx;
    }

    private static boolean contains(List<MetricPoint> polygon, MetricPoint point) {
        return contains(polygon, point, CancellationProbe.NONE, ignored -> { });
    }

    private static boolean contains(List<MetricPoint> polygon, MetricPoint point,
            CancellationProbe cancellation, LongConsumer admission) {
        boolean inside = false;
        for (int current = 0, previous = polygon.size() - 1; current < polygon.size(); previous = current++) {
            cancellation.checkpoint();
            admission.accept(1L);
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
        // These same bounds are necessary for a hit; reject before computing the edge length.
        if (!(point.xMeters() >= Math.min(a.xMeters(), b.xMeters()) - 1e-9
            && point.xMeters() <= Math.max(a.xMeters(), b.xMeters()) + 1e-9
            && point.yMeters() >= Math.min(a.yMeters(), b.yMeters()) - 1e-9
            && point.yMeters() <= Math.max(a.yMeters(), b.yMeters()) + 1e-9)) {
            return false;
        }
        double cross = orientation(a, b, point);
        double scale = Math.max(1.0, a.distanceTo(b));
        return Math.abs(cross) <= 1e-9 * scale;
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * Checks immutable Legacy final previews without imposing Corridor A assignment or context policies.
 * Unchanged inherited contacts require two uniquely matching directed original segment occurrences.
 * Changed, ambiguous, subdivided or simplified contact segments receive no inheritance certificate.
 */
public final class LegacyFinalPreviewSafety {
    private LegacyFinalPreviewSafety() { }

    /**
     * Measures nonadjacent final-preview contacts once, for cached candidate assessment.
     *
     * @param source immutable pre-command selected source polyline
     * @param preview stored final geometry after reconstruction and permitted simplification
     * @return immutable blocking warnings; empty only when all contacts are absent or certified inherited
     */
    public static List<String> warnings(List<EastNorth> source, List<EastNorth> preview) {
        if (!available(source) || !available(preview)) {
            return List.of("Legacy final-preview contact evidence unavailable");
        }
        List<Segment> original = segments(source);
        List<Segment> proposed = segments(preview);
        boolean originalClosed = closed(source);
        boolean hasInheritedContact = hasContact(original, originalClosed);
        boolean closureOwned = originalClosed && closed(preview);
        int[] ownership = new int[proposed.size()];
        for (int index = 0; index < proposed.size(); index++) {
            ownership[index] = uniqueOriginalSegment(proposed.get(index), original);
        }
        Set<String> warnings = new LinkedHashSet<>();
        for (int first = 0; first < proposed.size(); first++) {
            for (int second = first + 2; second < proposed.size(); second++) {
                Kind kind = intersection(proposed.get(first), proposed.get(second));
                if (!reportable(kind, first, second, proposed.size(), closureOwned)) { continue; }
                int ownerFirst = Math.min(ownership[first], ownership[second]);
                int ownerSecond = Math.max(ownership[first], ownership[second]);
                if (ownerFirst >= 0 && ownerSecond >= ownerFirst + 2
                    && reportable(kind, ownerFirst, ownerSecond, original.size(), originalClosed)
                    && intersection(original.get(ownerFirst), original.get(ownerSecond)) == kind) {
                    continue;
                }
                String status = hasInheritedContact ? "inheritance unproven: " : "new ";
                warnings.add(status + kind.description + " in Legacy final preview");
            }
        }
        return List.copyOf(warnings);
    }

    /**
     * Rechecks exactly the geometry displayed and supplied to the command, after source freshness validation.
     *
     * @param source immutable pre-command selected source polyline
     * @param stored candidate-owned final preview
     * @param applied displayed geometry that the command will consume
     * @throws IllegalStateException when final geometry differs, is unavailable, or contains an uncertified contact
     */
    public static void requireSafe(List<EastNorth> source, List<EastNorth> stored, List<EastNorth> applied) {
        if (!available(stored) || !available(applied) || !samePolyline(stored, applied)) {
            throw new IllegalStateException("The displayed Legacy preview differs from its stored final geometry. Run the slide again.");
        }
        List<String> warnings = warnings(source, stored);
        if (!warnings.isEmpty()) {
            throw new IllegalStateException(String.join("; ", warnings));
        }
    }

    private static boolean available(List<EastNorth> geometry) {
        return geometry != null && geometry.size() >= 2 && geometry.stream().allMatch(point -> point != null
            && Double.isFinite(point.east()) && Double.isFinite(point.north()));
    }

    private static boolean samePolyline(List<EastNorth> first, List<EastNorth> second) {
        if (first.size() != second.size()) { return false; }
        for (int index = 0; index < first.size(); index++) {
            if (!samePoint(first.get(index), second.get(index))) { return false; }
        }
        return true;
    }

    private static boolean samePoint(EastNorth first, EastNorth second) {
        return first.east() == second.east() && first.north() == second.north();
    }

    private static boolean closed(List<EastNorth> geometry) {
        return samePoint(geometry.get(0), geometry.get(geometry.size() - 1));
    }

    private static List<Segment> segments(List<EastNorth> geometry) {
        List<ExactPoint> points = geometry.stream().map(ExactPoint::new).toList();
        List<Segment> result = new ArrayList<>();
        for (int index = 0; index + 1 < points.size(); index++) {
            result.add(new Segment(points.get(index), points.get(index + 1)));
        }
        return result;
    }

    private static int uniqueOriginalSegment(Segment proposed, List<Segment> original) {
        int match = -1;
        for (int index = 0; index < original.size(); index++) {
            Segment before = original.get(index);
            if (proposed.start().same(before.start()) && proposed.end().same(before.end())) {
                if (match >= 0) { return -1; }
                match = index;
            }
        }
        return match;
    }

    private static boolean hasContact(List<Segment> geometry, boolean closureOwned) {
        for (int first = 0; first < geometry.size(); first++) {
            for (int second = first + 2; second < geometry.size(); second++) {
                Kind kind = intersection(geometry.get(first), geometry.get(second));
                if (reportable(kind, first, second, geometry.size(), closureOwned)) { return true; }
            }
        }
        return false;
    }

    private static boolean reportable(Kind kind, int first, int second, int size, boolean closureOwned) {
        // Only the common closure endpoint is exempt, never an overlapping closing edge.
        return kind != Kind.NONE && !(kind == Kind.TOUCH && closureOwned && first == 0 && second == size - 1);
    }

    private static Kind intersection(Segment first, Segment second) {
        ExactPoint a = first.start();
        ExactPoint b = first.end();
        ExactPoint c = second.start();
        ExactPoint d = second.end();
        if (Math.max(a.x(), b.x()) < Math.min(c.x(), d.x())
            || Math.max(c.x(), d.x()) < Math.min(a.x(), b.x())
            || Math.max(a.y(), b.y()) < Math.min(c.y(), d.y())
            || Math.max(c.y(), d.y()) < Math.min(a.y(), b.y())) {
            return Kind.NONE;
        }
        int abc = orientation(a, b, c);
        int abd = orientation(a, b, d);
        int cda = orientation(c, d, a);
        int cdb = orientation(c, d, b);
        if (abc * abd < 0 && cda * cdb < 0) { return Kind.PROPER; }
        if (abc == 0 && abd == 0 && cda == 0 && cdb == 0) {
            boolean useEast = a.x() != b.x() || c.x() != d.x();
            double lo = Math.max(Math.min(useEast ? a.x() : a.y(), useEast ? b.x() : b.y()),
                Math.min(useEast ? c.x() : c.y(), useEast ? d.x() : d.y()));
            double hi = Math.min(Math.max(useEast ? a.x() : a.y(), useEast ? b.x() : b.y()),
                Math.max(useEast ? c.x() : c.y(), useEast ? d.x() : d.y()));
            return hi > lo ? Kind.OVERLAP : Kind.TOUCH;
        }
        return abc == 0 && within(c, a, b) || abd == 0 && within(d, a, b)
            || cda == 0 && within(a, c, d) || cdb == 0 && within(b, c, d) ? Kind.TOUCH : Kind.NONE;
    }

    private static int orientation(ExactPoint a, ExactPoint b, ExactPoint c) {
        return b.east().subtract(a.east()).multiply(c.north().subtract(a.north()))
            .subtract(b.north().subtract(a.north()).multiply(c.east().subtract(a.east()))).signum();
    }

    private static boolean within(ExactPoint point, ExactPoint start, ExactPoint end) {
        return point.x() >= Math.min(start.x(), end.x()) && point.x() <= Math.max(start.x(), end.x())
            && point.y() >= Math.min(start.y(), end.y()) && point.y() <= Math.max(start.y(), end.y());
    }

    private record ExactPoint(double x, double y, BigDecimal east, BigDecimal north) {
        ExactPoint(EastNorth point) {
            this(point.east(), point.north(), new BigDecimal(point.east()), new BigDecimal(point.north()));
        }
        boolean same(ExactPoint other) { return x == other.x && y == other.y; }
    }
    private record Segment(ExactPoint start, ExactPoint end) { }
    private enum Kind {
        NONE("none"), PROPER("self-intersection"), TOUCH("nonadjacent touch"), OVERLAP("collinear overlap");
        private final String description;
        Kind(String description) { this.description = description; }
    }
}

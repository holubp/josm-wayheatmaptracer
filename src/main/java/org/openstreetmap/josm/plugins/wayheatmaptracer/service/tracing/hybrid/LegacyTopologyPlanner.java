package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

/** Detached numerical portion of Legacy precise-shape existing-node assignment. */
public final class LegacyTopologyPlanner {
    private static final double LOCAL_SEARCH_FRACTION = 0.08;
    // The existing Legacy distance constant is in captured projection units, not ground metres.
    private static final double LOCAL_SEARCH_PROJECTED_UNITS = 35.0;

    private LegacyTopologyPlanner() { }

    /** Caller-captured policy facts for one original occurrence, in source-index order. */
    public record Occurrence(int sourceIndex, boolean fixed, boolean topologyAnchor) {
        public Occurrence {
            if (sourceIndex < 0) {
                throw new IllegalArgumentException("Source occurrence index must be nonnegative");
            }
        }
    }

    /** One factual occurrence assignment; coincident targets do not merge occurrence identities. */
    public record Assignment(int sourceIndex, ProjectedPoint source, ProjectedPoint target,
            double sourceFraction, double previewFraction, boolean fixed) {
        public Assignment {
            if (sourceIndex < 0 || source == null || target == null
                    || !Double.isFinite(sourceFraction) || !Double.isFinite(previewFraction)) {
                throw new IllegalArgumentException("Assignment values must be finite and non-null");
            }
        }
    }

    /**
     * Assigns fixed and topology-relevant occurrences using the unchanged Legacy near-fraction rule.
     * All coordinates and the local distance window use the captured projection's units.
     * Facts must cover every source occurrence exactly once in source order; this helper grants
     * no fixed-node or topology policy of its own.
     */
    public static List<Assignment> preciseAssignments(List<ProjectedPoint> sourcePolyline,
            List<ProjectedPoint> previewPolyline, List<Occurrence> occurrences) {
        requirePolyline(sourcePolyline, "Source");
        requirePolyline(previewPolyline, "Preview");
        requireOccurrences(sourcePolyline, occurrences);
        List<Double> sourceFractions = fractionsForSegment(sourcePolyline);
        List<Double> previewFractions = fractionsForSegment(previewPolyline);
        double previewLength = length(previewPolyline);
        double window = Math.max(LOCAL_SEARCH_FRACTION,
                LOCAL_SEARCH_PROJECTED_UNITS / Math.max(1.0, previewLength));
        List<Assignment> assignments = new ArrayList<>();
        for (Occurrence occurrence : occurrences) {
            int index = occurrence.sourceIndex();
            double sourceFraction = sourceFractions.get(index);
            if (occurrence.fixed()) {
                ClosestPoint projection = closestPointNearFraction(previewPolyline, previewFractions,
                        sourcePolyline.get(index), sourceFraction, 1.0);
                if (distance(projection.point(), sourcePolyline.get(index)) > 1e-7) {
                    throw new IllegalStateException("Fixed selected node is missing from final preview geometry");
                }
                assignments.add(new Assignment(index, sourcePolyline.get(index), sourcePolyline.get(index),
                        sourceFraction, projection.fraction(), true));
            } else if (occurrence.topologyAnchor()) {
                ClosestPoint projection = closestPointNearFraction(previewPolyline, previewFractions,
                        sourcePolyline.get(index), sourceFraction, window);
                assignments.add(new Assignment(index, sourcePolyline.get(index), projection.point(),
                        sourceFraction, projection.fraction(), false));
            }
        }
        assignments.sort(Comparator.comparingDouble(Assignment::sourceFraction));
        requireMonotonic(assignments);
        return List.copyOf(assignments);
    }

    /**
     * Inserts the actual Legacy assignment targets and clips geometry to the assigned endpoints.
     * Only adjacent coincident geometry is deduplicated; assignments remain separate occurrences.
     */
    public static List<ProjectedPoint> constrain(List<ProjectedPoint> sourcePolyline,
            List<ProjectedPoint> previewPolyline, List<Occurrence> occurrences) {
        List<Assignment> assignments = preciseAssignments(sourcePolyline, previewPolyline, occurrences);
        Assignment first = assignments.stream().filter(item -> item.sourceIndex() == 0).findFirst()
                .orElseThrow(() -> new IllegalStateException("Selected start node has no preview assignment"));
        Assignment last = assignments.stream()
                .filter(item -> item.sourceIndex() == sourcePolyline.size() - 1).findFirst()
                .orElseThrow(() -> new IllegalStateException("Selected end node has no preview assignment"));
        if (last.previewFraction() + 1e-9 < first.previewFraction()) {
            throw new IllegalStateException("Proposed endpoint assignments reverse the selected segment");
        }
        List<Double> fractions = fractionsForSegment(previewPolyline);
        List<ProjectedPoint> result = new ArrayList<>();
        int assignmentIndex = 0;
        while (assignmentIndex < assignments.size()
                && assignments.get(assignmentIndex).previewFraction() < first.previewFraction() - 1e-9) {
            assignmentIndex++;
        }
        for (int index = 0; index < previewPolyline.size(); index++) {
            double fraction = fractions.get(index);
            if (fraction < first.previewFraction() - 1e-9 || fraction > last.previewFraction() + 1e-9) {
                continue;
            }
            while (assignmentIndex < assignments.size()
                    && assignments.get(assignmentIndex).previewFraction() <= fraction + 1e-9
                    && assignments.get(assignmentIndex).previewFraction() <= last.previewFraction() + 1e-9) {
                appendDistinct(result, assignments.get(assignmentIndex).target());
                assignmentIndex++;
            }
            appendDistinct(result, previewPolyline.get(index));
        }
        while (assignmentIndex < assignments.size()
                && assignments.get(assignmentIndex).previewFraction() <= last.previewFraction() + 1e-9) {
            appendDistinct(result, assignments.get(assignmentIndex).target());
            assignmentIndex++;
        }
        appendDistinct(result, last.target());
        if (result.size() < 2) {
            throw new IllegalStateException("Proposed endpoint assignments collapse the preview geometry");
        }
        return List.copyOf(result);
    }

    private static void requireOccurrences(List<ProjectedPoint> source, List<Occurrence> occurrences) {
        if (occurrences == null || occurrences.size() != source.size()) {
            throw new IllegalArgumentException("Facts must cover the complete source occurrence sequence");
        }
        for (int index = 0; index < occurrences.size(); index++) {
            if (occurrences.get(index) == null || occurrences.get(index).sourceIndex() != index) {
                throw new IllegalArgumentException("Occurrence indices must be unique and in source order");
            }
        }
    }

    private static void requirePolyline(List<ProjectedPoint> polyline, String label) {
        if (polyline == null || polyline.size() < 2) {
            throw new IllegalArgumentException(label + " geometry must contain at least two points");
        }
        for (ProjectedPoint point : polyline) {
            if (point == null || !Double.isFinite(point.east()) || !Double.isFinite(point.north())) {
                throw new IllegalArgumentException(label + " geometry contains a non-finite point");
            }
        }
    }

    private static void requireMonotonic(List<Assignment> assignments) {
        double previous = Double.NEGATIVE_INFINITY;
        for (Assignment assignment : assignments) {
            if (assignment.previewFraction() + 1e-9 < previous) {
                throw new IllegalStateException("Existing-node targets are not monotonic in preview geometry");
            }
            previous = assignment.previewFraction();
        }
    }

    private static void appendDistinct(List<ProjectedPoint> points, ProjectedPoint point) {
        if (points.isEmpty() || distance(points.get(points.size() - 1), point) > 1e-9) {
            points.add(point);
        }
    }

    // These operations retain PolylineMath's Legacy arithmetic and tie order without JOSM values.
    private static List<Double> fractionsForSegment(List<ProjectedPoint> polyline) {
        List<Double> fractions = new ArrayList<>(polyline.size());
        double total = 0.0;
        double[] cumulative = new double[polyline.size()];
        cumulative[0] = 0.0;
        for (int index = 1; index < polyline.size(); index++) {
            total += distance(polyline.get(index - 1), polyline.get(index));
            cumulative[index] = total;
        }
        if (total == 0.0) {
            for (int index = 0; index < polyline.size(); index++) {
                fractions.add(index == polyline.size() - 1 ? 1.0 : 0.0);
            }
            return fractions;
        }
        for (double value : cumulative) {
            fractions.add(value / total);
        }
        return fractions;
    }

    private static double length(List<ProjectedPoint> polyline) {
        double total = 0.0;
        for (int index = 1; index < polyline.size(); index++) {
            total += distance(polyline.get(index - 1), polyline.get(index));
        }
        return total;
    }

    private static ClosestPoint closestPointNearFraction(List<ProjectedPoint> polyline,
            List<Double> fractions, ProjectedPoint source, double sourceFraction, double window) {
        double minFraction = Math.max(0.0, sourceFraction - window);
        double maxFraction = Math.min(1.0, sourceFraction + window);
        ClosestPoint best = null;
        for (int index = 1; index < polyline.size(); index++) {
            double leftFraction = fractions.get(index - 1);
            double rightFraction = fractions.get(index);
            if (rightFraction < minFraction || leftFraction > maxFraction) {
                continue;
            }
            ClosestPoint projected = projectToSegment(polyline.get(index - 1), polyline.get(index),
                    leftFraction, rightFraction, source);
            if (projected.fraction() < minFraction || projected.fraction() > maxFraction) {
                continue;
            }
            if (best == null || projected.distance() < best.distance()) {
                best = projected;
            }
        }
        if (best != null) {
            return best;
        }
        ProjectedPoint fallback = interpolateAtFraction(polyline, fractions, sourceFraction);
        return new ClosestPoint(fallback, sourceFraction, distance(fallback, source));
    }

    private static ClosestPoint projectToSegment(ProjectedPoint start, ProjectedPoint end,
            double leftFraction, double rightFraction, ProjectedPoint source) {
        double dx = end.east() - start.east();
        double dy = end.north() - start.north();
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0.0 ? 0.0
                : ((source.east() - start.east()) * dx + (source.north() - start.north()) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        ProjectedPoint point = new ProjectedPoint(start.east() + dx * t, start.north() + dy * t);
        double fraction = leftFraction + (rightFraction - leftFraction) * t;
        return new ClosestPoint(point, fraction, distance(point, source));
    }

    private static ProjectedPoint interpolateAtFraction(List<ProjectedPoint> polyline,
            List<Double> fractions, double targetFraction) {
        if (targetFraction <= 0.0) {
            return polyline.get(0);
        }
        if (targetFraction >= 1.0) {
            return polyline.get(polyline.size() - 1);
        }
        for (int index = 1; index < polyline.size(); index++) {
            double left = fractions.get(index - 1);
            double right = fractions.get(index);
            if (targetFraction <= right) {
                double span = right - left;
                double t = span == 0.0 ? 0.0 : (targetFraction - left) / span;
                ProjectedPoint start = polyline.get(index - 1);
                ProjectedPoint end = polyline.get(index);
                return new ProjectedPoint(start.east() + (end.east() - start.east()) * t,
                        start.north() + (end.north() - start.north()) * t);
            }
        }
        return polyline.get(polyline.size() - 1);
    }

    private static double distance(ProjectedPoint first, ProjectedPoint second) {
        double dx = first.east() - second.east();
        double dy = first.north() - second.north();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private record ClosestPoint(ProjectedPoint point, double fraction, double distance) { }
}

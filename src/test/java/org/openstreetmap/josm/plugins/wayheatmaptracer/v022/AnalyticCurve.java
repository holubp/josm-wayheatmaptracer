package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import java.util.ArrayList;
import java.util.List;

/** Analytic physical centerline used only to create independent test evidence. */
public interface AnalyticCurve {
    /** Returns a point for a normalized curve parameter in [0, 1]. */
    V022Point pointAtFraction(double fraction);

    /** Returns the approximate physical length, accurate enough for fixture sampling. */
    double lengthMeters();

    /** Returns a non-negative distance to the curve. */
    default double distanceTo(V022Point point) {
        double best = Double.POSITIVE_INFINITY;
        V022Point previous = pointAtFraction(0.0);
        for (int index = 1; index <= 2_000; index++) {
            V022Point next = pointAtFraction(index / 2_000.0);
            best = Math.min(best, distanceToSegment(point, previous, next));
            previous = next;
        }
        return best;
    }

    /** Samples the curve at no greater than {@code maximumStepMeters}. */
    default List<V022Point> sample(double maximumStepMeters) {
        int count = Math.max(1, (int) Math.ceil(lengthMeters() / maximumStepMeters));
        List<V022Point> points = new ArrayList<>(count + 1);
        for (int index = 0; index <= count; index++) {
            points.add(pointAtFraction(index / (double) count));
        }
        return List.copyOf(points);
    }

    /** Exact point-to-segment distance used by the independent fixture and oracle code. */
    static double distanceToSegment(V022Point point, V022Point start, V022Point end) {
        double dx = end.x() - start.x();
        double dy = end.y() - start.y();
        double denominator = dx * dx + dy * dy;
        if (denominator == 0.0) {
            return point.distanceTo(start);
        }
        double fraction = ((point.x() - start.x()) * dx + (point.y() - start.y()) * dy) / denominator;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        return point.distanceTo(start.interpolate(end, fraction));
    }
}

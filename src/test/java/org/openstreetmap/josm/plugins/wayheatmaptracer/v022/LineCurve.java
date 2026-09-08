package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

/** Straight analytic fixture branch. */
public record LineCurve(V022Point start, V022Point end) implements AnalyticCurve {
    @Override
    public V022Point pointAtFraction(double fraction) {
        return start.interpolate(end, fraction);
    }

    @Override
    public double lengthMeters() {
        return start.distanceTo(end);
    }

    @Override
    public double distanceTo(V022Point point) {
        return AnalyticCurve.distanceToSegment(point, start, end);
    }
}

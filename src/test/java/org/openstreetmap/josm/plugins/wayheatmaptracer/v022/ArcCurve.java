package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

/** Circular analytic fixture branch. Angles are radians. */
public record ArcCurve(V022Point center, double radiusMeters, double startRadians, double endRadians)
        implements AnalyticCurve {
    @Override
    public V022Point pointAtFraction(double fraction) {
        double angle = startRadians + (endRadians - startRadians) * Math.max(0.0, Math.min(1.0, fraction));
        return new V022Point(center.x() + radiusMeters * Math.cos(angle), center.y() + radiusMeters * Math.sin(angle));
    }

    @Override
    public double lengthMeters() {
        return Math.abs(endRadians - startRadians) * radiusMeters;
    }

    @Override
    public double distanceTo(V022Point point) {
        double angle = Math.atan2(point.y() - center.y(), point.x() - center.x());
        double low = Math.min(startRadians, endRadians);
        double high = Math.max(startRadians, endRadians);
        while (angle < low) {
            angle += 2.0 * Math.PI;
        }
        while (angle > high) {
            angle -= 2.0 * Math.PI;
        }
        if (angle < low || angle > high) {
            return Math.min(point.distanceTo(pointAtFraction(0.0)), point.distanceTo(pointAtFraction(1.0)));
        }
        return Math.abs(point.distanceTo(center) - radiusMeters);
    }
}

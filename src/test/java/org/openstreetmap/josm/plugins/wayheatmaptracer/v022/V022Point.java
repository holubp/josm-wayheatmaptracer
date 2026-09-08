package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

/** Immutable Cartesian point expressed in synthetic-scene ground metres. */
public record V022Point(double x, double y) {
    /** Returns the Euclidean distance to {@code other}. */
    public double distanceTo(V022Point other) {
        return Math.hypot(x - other.x, y - other.y);
    }

    /** Returns a translated copy. */
    public V022Point plus(double dx, double dy) {
        return new V022Point(x + dx, y + dy);
    }

    /** Returns the linear interpolation at {@code fraction}. */
    public V022Point interpolate(V022Point other, double fraction) {
        return new V022Point(x + (other.x - x) * fraction, y + (other.y - y) * fraction);
    }
}

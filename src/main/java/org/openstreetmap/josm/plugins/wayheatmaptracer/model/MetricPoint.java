package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** A point in a versioned slide-local metric coordinate frame. */
public record MetricPoint(double xMeters, double yMeters) {
    /** Rejects non-finite coordinates. */
    public MetricPoint {
        if (!Double.isFinite(xMeters) || !Double.isFinite(yMeters)) {
            throw new IllegalArgumentException("Metric coordinates must be finite");
        }
    }

    /** Returns Euclidean distance to another local point in metres. */
    public double distanceTo(MetricPoint other) {
        return StrictMath.hypot(xMeters - other.xMeters, yMeters - other.yMeters);
    }
}

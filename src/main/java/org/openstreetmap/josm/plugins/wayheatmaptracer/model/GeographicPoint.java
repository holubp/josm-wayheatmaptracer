package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Detached normalized latitude/longitude coordinate used as edit-plan authority. */
public record GeographicPoint(double latitudeDegrees, double longitudeDegrees) {
    /** Validates geographic ranges. */
    public GeographicPoint {
        if (!Double.isFinite(latitudeDegrees) || !Double.isFinite(longitudeDegrees)
            || latitudeDegrees < -90.0 || latitudeDegrees > 90.0
            || longitudeDegrees < -180.0 || longitudeDegrees > 180.0) {
            throw new IllegalArgumentException("Geographic coordinate is outside its valid range");
        }
    }
}

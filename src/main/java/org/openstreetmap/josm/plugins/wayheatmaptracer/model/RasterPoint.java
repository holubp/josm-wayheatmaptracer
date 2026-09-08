package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Continuous raster coordinate whose integer values address pixel centers. */
public record RasterPoint(double x, double y) {
    /** Rejects non-finite coordinates. */
    public RasterPoint {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Raster coordinates must be finite");
        }
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * Slide-time affine transform from a lateral sampled-raster offset to projected coordinates.
 *
 * @param zeroOffset projected coordinate at lateral offset zero
 * @param eastPerRasterPixel projected east-coordinate delta for one positive lateral raster pixel
 * @param northPerRasterPixel projected north-coordinate delta for one positive lateral raster pixel
 */
public record ProjectedLateralTransform(
    EastNorth zeroOffset,
    double eastPerRasterPixel,
    double northPerRasterPixel
) {
    /** Validates finite slide-time transform values. */
    public ProjectedLateralTransform {
        if (zeroOffset == null || !Double.isFinite(zeroOffset.east()) || !Double.isFinite(zeroOffset.north())
            || !Double.isFinite(eastPerRasterPixel) || !Double.isFinite(northPerRasterPixel)
            || Math.hypot(eastPerRasterPixel, northPerRasterPixel) <= 0.0) {
            throw new IllegalArgumentException("Projected lateral transform must be finite and non-degenerate");
        }
    }

    /**
     * Projects one lateral sampled-raster offset using the retained slide-time transform.
     *
     * @param offsetPx lateral offset in sampled-raster pixels
     * @return projected coordinate at the requested offset
     */
    public EastNorth atOffset(double offsetPx) {
        if (!Double.isFinite(offsetPx)) {
            throw new IllegalArgumentException("Lateral offset must be finite");
        }
        return new EastNorth(zeroOffset.east() + offsetPx * eastPerRasterPixel,
            zeroOffset.north() + offsetPx * northPerRasterPixel);
    }
}

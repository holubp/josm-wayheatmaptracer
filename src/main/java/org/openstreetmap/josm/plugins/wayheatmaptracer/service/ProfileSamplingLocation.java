package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;

/**
 * Shared sampling-anchor contract for detached and legacy sampling traces.
 */
public interface ProfileSamplingLocation {
    /**
     * Returns the sampling coordinate in raster pixel-center space.
     *
     * @return raster coordinate in pixel-center units
     */
    RasterPoint rasterPoint();

    /**
     * Returns cumulative chainage from the first profile in metres.
     *
     * @return cumulative ground distance in metres
     */
    double cumulativeGroundDistanceMeters();

    /**
     * Returns the legacy sampled raster anchor shape used by corridor modules.
     *
     * @return sampled raster coordinate in pixels
     */
    default Point2D.Double anchorScreen() {
        RasterPoint rasterPoint = rasterPoint();
        return new Point2D.Double(rasterPoint.x(), rasterPoint.y());
    }
}

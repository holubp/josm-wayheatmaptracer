package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.BilinearSegmentIntegral;

/** Read-only scalar values with an explicit mapping to the producer's base raster. */
public interface NativeScalarGridView extends BilinearSegmentIntegral.ReadOnlyScalarGrid {
    RasterPoint toGrid(RasterPoint baseRasterPoint);
    RasterPoint toBaseRaster(RasterPoint gridPoint);
}

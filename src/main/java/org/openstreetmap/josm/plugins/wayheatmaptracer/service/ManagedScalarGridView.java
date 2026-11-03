package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.BilinearSegmentIntegral;

/** Read-only numeric grid in full managed-image pixel-center coordinates (origin zero). */
public final class ManagedScalarGridView implements BilinearSegmentIntegral.ReadOnlyScalarGrid {
    private final ManagedScalarGrid grid;
    ManagedScalarGridView(ManagedScalarGrid grid) { this.grid = grid; }
    @Override public int width() { return grid.width(); }
    @Override public int height() { return grid.height(); }
    @Override public boolean supportsCell(int x, int y) {
        return grid.supportsCell(x, y);
    }
    @Override public double nodeValue(int x, int y) { return grid.nodeValue(x, y); }
    public RasterPoint toGrid(RasterPoint point) { return Objects.requireNonNull(point); }
    public RasterPoint toBaseRaster(RasterPoint point) { return Objects.requireNonNull(point); }
}

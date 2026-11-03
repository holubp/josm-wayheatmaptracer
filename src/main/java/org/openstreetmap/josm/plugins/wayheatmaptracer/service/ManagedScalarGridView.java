package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;

/** Read-only native-image nodes mapped from the producer's virtual profile coordinates. */
public final class ManagedScalarGridView implements NativeScalarGridView {
    private final ManagedScalarGrid grid;
    private final double profileCoordinateScale;
    ManagedScalarGridView(ManagedScalarGrid grid, double profileCoordinateScale) {
        this.grid = grid;
        this.profileCoordinateScale = profileCoordinateScale;
    }
    @Override public int width() { return grid.width(); }
    @Override public int height() { return grid.height(); }
    @Override public boolean supportsCell(int x, int y) {
        return grid.supportsCell(x, y);
    }
    @Override public double nodeValue(int x, int y) { return grid.nodeValue(x, y); }
    public RasterPoint toGrid(RasterPoint point) {
        Objects.requireNonNull(point);
        return new RasterPoint(point.x() / profileCoordinateScale,
                point.y() / profileCoordinateScale);
    }
    public RasterPoint toBaseRaster(RasterPoint point) {
        Objects.requireNonNull(point);
        return new RasterPoint(point.x() * profileCoordinateScale,
                point.y() * profileCoordinateScale);
    }
    /** Producer virtual-profile raster pixels per one native source-image pixel. */
    public double profileCoordinateScale() { return profileCoordinateScale; }
}

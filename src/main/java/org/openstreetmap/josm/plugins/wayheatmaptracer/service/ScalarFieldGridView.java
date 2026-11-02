package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.BilinearSegmentIntegral;

/** Read-only, grid-coordinate view of an existing scalar intensity/evidence field. */
public final class ScalarFieldGridView implements BilinearSegmentIntegral.ReadOnlyScalarGrid {
    private final ScalarIntensityField intensity;
    private final ScalarEvidenceField evidence;
    private final int originX;
    private final int originY;
    private final int reduction;

    private ScalarFieldGridView(ScalarIntensityField intensity, ScalarEvidenceField evidence,
            int originX, int originY, int reduction) {
        this.intensity = intensity;
        this.evidence = evidence;
        this.originX = originX;
        this.originY = originY;
        this.reduction = reduction;
    }

    /** Wraps an existing cropped or reduced L0 intensity field without copying it. */
    public static ScalarFieldGridView fromIntensity(ScalarIntensityField field) {
        if (field == null) throw new IllegalArgumentException("Scalar intensity field is required");
        return new ScalarFieldGridView(field, null, field.originX(), field.originY(), field.reduction());
    }

    /** Wraps an existing immutable scalar evidence field without copying it. */
    public static ScalarFieldGridView fromEvidence(ScalarEvidenceField field) {
        if (field == null) throw new IllegalArgumentException("Scalar evidence field is required");
        return new ScalarFieldGridView(null, field, 0, 0, 1);
    }

    @Override public int width() { return intensity != null ? intensity.width() : evidence.width(); }
    @Override public int height() { return intensity != null ? intensity.height() : evidence.height(); }

    @Override public boolean supportsCell(int x, int y) {
        return intensity != null
            ? intensity.supportsInterpolationCell(x, y)
            : evidence.supportsInterpolationCell(x, y);
    }

    @Override public double nodeValue(int x, int y) {
        checkNodeIndex(x, y);
        if (intensity != null) {
            return intensity.isValid(x, y) ? intensity.value(x, y) : Double.NaN;
        }
        return evidence.sample(x, y).orElse(Double.NaN);
    }

    /** Maps an L0/base-raster pixel-center point into this field's grid coordinates. */
    public RasterPoint toGrid(RasterPoint baseRasterPoint) {
        if (baseRasterPoint == null) throw new IllegalArgumentException("Base raster point is required");
        return new RasterPoint((baseRasterPoint.x() - originX) / reduction,
            (baseRasterPoint.y() - originY) / reduction);
    }

    /** Maps a grid-coordinate point into L0/base-raster pixel-center coordinates. */
    public RasterPoint toBaseRaster(RasterPoint gridPoint) {
        if (gridPoint == null) throw new IllegalArgumentException("Grid point is required");
        return new RasterPoint(originX + reduction * gridPoint.x(),
            originY + reduction * gridPoint.y());
    }

    private void checkNodeIndex(int x, int y) {
        if (x < 0 || y < 0 || x >= width() || y >= height()) {
            throw new IndexOutOfBoundsException("Scalar grid node is outside the field");
        }
    }

    public int originX() { return originX; }
    public int originY() { return originY; }
    public int reduction() { return reduction; }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Versioned affine transform between raster pixel centers and local metric coordinates.
 *
 * <p>Managed mosaics start at a native source-pixel boundary and therefore use the established
 * {@code +0.5} center phase before dividing by virtual oversampling. Visible captures start at the
 * first rendered pixel center and use no managed half-pixel correction.</p>
 */
public record RasterMetricTransform(
    String transformId,
    OriginKind originKind,
    MetricPoint origin,
    double xAxisEastMetersPerSourcePixel,
    double xAxisNorthMetersPerSourcePixel,
    double yAxisEastMetersPerSourcePixel,
    double yAxisNorthMetersPerSourcePixel,
    double rasterPixelsPerSourcePixel
) {
    /** Meaning of the retained origin coordinate. */
    public enum OriginKind { MANAGED_SOURCE_PIXEL_BOUNDARY, VISIBLE_FIRST_PIXEL_CENTER }

    /** Validates a finite invertible transform. */
    public RasterMetricTransform {
        double determinant = xAxisEastMetersPerSourcePixel * yAxisNorthMetersPerSourcePixel
            - xAxisNorthMetersPerSourcePixel * yAxisEastMetersPerSourcePixel;
        if (transformId == null || transformId.isBlank() || originKind == null || origin == null
            || !Double.isFinite(determinant) || Math.abs(determinant) < 1e-12
            || !Double.isFinite(rasterPixelsPerSourcePixel) || rasterPixelsPerSourcePixel <= 0.0) {
            throw new IllegalArgumentException("Raster transform must be finite and invertible");
        }
    }

    /** Creates an axis-aligned managed-mosaic transform. */
    public static RasterMetricTransform managed(MetricPoint nativeBoundaryOrigin,
        double sourcePitchMeters, double rasterPixelsPerSourcePixel) {
        return new RasterMetricTransform("managed-pixel-boundary-v1",
            OriginKind.MANAGED_SOURCE_PIXEL_BOUNDARY, nativeBoundaryOrigin,
            sourcePitchMeters, 0.0, 0.0, -sourcePitchMeters, rasterPixelsPerSourcePixel);
    }

    /** Creates an axis-aligned visible-capture transform. */
    public static RasterMetricTransform visible(MetricPoint firstRenderedCenter,
        double rasterPitchMeters) {
        return new RasterMetricTransform("visible-first-center-v1", OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
            firstRenderedCenter, rasterPitchMeters, 0.0, 0.0, -rasterPitchMeters, 1.0);
    }

    /** Converts a continuous raster pixel-center coordinate into metric space. */
    public MetricPoint pixelCenterToMetric(double rasterX, double rasterY) {
        double phase = originKind == OriginKind.MANAGED_SOURCE_PIXEL_BOUNDARY ? 0.5 : 0.0;
        double sourceX = rasterX / rasterPixelsPerSourcePixel + phase;
        double sourceY = rasterY / rasterPixelsPerSourcePixel + phase;
        return new MetricPoint(origin.xMeters() + sourceX * xAxisEastMetersPerSourcePixel
                + sourceY * yAxisEastMetersPerSourcePixel,
            origin.yMeters() + sourceX * xAxisNorthMetersPerSourcePixel
                + sourceY * yAxisNorthMetersPerSourcePixel);
    }

    /** Converts a metric coordinate into continuous raster pixel-center coordinates. */
    public RasterPoint metricToPixelCenter(MetricPoint point) {
        double dx = point.xMeters() - origin.xMeters();
        double dy = point.yMeters() - origin.yMeters();
        double determinant = xAxisEastMetersPerSourcePixel * yAxisNorthMetersPerSourcePixel
            - xAxisNorthMetersPerSourcePixel * yAxisEastMetersPerSourcePixel;
        double sourceX = (dx * yAxisNorthMetersPerSourcePixel - dy * yAxisEastMetersPerSourcePixel)
            / determinant;
        double sourceY = (dy * xAxisEastMetersPerSourcePixel - dx * xAxisNorthMetersPerSourcePixel)
            / determinant;
        double phase = originKind == OriginKind.MANAGED_SOURCE_PIXEL_BOUNDARY ? 0.5 : 0.0;
        return new RasterPoint((sourceX - phase) * rasterPixelsPerSourcePixel,
            (sourceY - phase) * rasterPixelsPerSourcePixel);
    }
}

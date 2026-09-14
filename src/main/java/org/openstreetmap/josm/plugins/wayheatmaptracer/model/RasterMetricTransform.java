package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Versioned affine transform between raster pixel centers and local metric coordinates.
 *
 * <p>Managed mosaics start at a native source-pixel boundary and therefore use the established
 * {@code +0.5} center phase before dividing by virtual oversampling. Visible captures start at the
 * first rendered pixel center. Exact metric grids use neutral raster-pixel axes and no source phase.</p>
 */
public record RasterMetricTransform(
    String transformId,
    OriginKind originKind,
    AxisUnit axisUnit,
    MetricPoint origin,
    double xAxisEastMetersPerSourcePixel,
    double xAxisNorthMetersPerSourcePixel,
    double yAxisEastMetersPerSourcePixel,
    double yAxisNorthMetersPerSourcePixel,
    double rasterPixelsPerSourcePixel,
    RasterTransformCertificate accuracyCertificate
) {
    /** Meaning of the retained origin coordinate. */
    public enum OriginKind {
        MANAGED_SOURCE_PIXEL_BOUNDARY, VISIBLE_FIRST_PIXEL_CENTER, METRIC_FIRST_PIXEL_CENTER
    }

    /** Unit represented by each stored affine axis vector. */
    public enum AxisUnit { SOURCE_PIXEL, RASTER_PIXEL }

    /** Creates a directly specified source-pixel affine transform. */
    public RasterMetricTransform(String transformId, OriginKind originKind, MetricPoint origin,
            double xAxisEastMetersPerSourcePixel, double xAxisNorthMetersPerSourcePixel,
            double yAxisEastMetersPerSourcePixel, double yAxisNorthMetersPerSourcePixel,
            double rasterPixelsPerSourcePixel) {
        this(transformId, originKind, AxisUnit.SOURCE_PIXEL, origin,
            xAxisEastMetersPerSourcePixel, xAxisNorthMetersPerSourcePixel,
            yAxisEastMetersPerSourcePixel, yAxisNorthMetersPerSourcePixel,
            rasterPixelsPerSourcePixel, RasterTransformCertificate.declaredAffine());
    }

    /** Creates a certified source-pixel affine transform. */
    public RasterMetricTransform(String transformId, OriginKind originKind, MetricPoint origin,
            double xAxisEastMetersPerSourcePixel, double xAxisNorthMetersPerSourcePixel,
            double yAxisEastMetersPerSourcePixel, double yAxisNorthMetersPerSourcePixel,
            double rasterPixelsPerSourcePixel, RasterTransformCertificate accuracyCertificate) {
        this(transformId, originKind, AxisUnit.SOURCE_PIXEL, origin,
            xAxisEastMetersPerSourcePixel, xAxisNorthMetersPerSourcePixel,
            yAxisEastMetersPerSourcePixel, yAxisNorthMetersPerSourcePixel,
            rasterPixelsPerSourcePixel, accuracyCertificate);
    }

    /** Validates a finite invertible transform and a retained accuracy certificate. */
    public RasterMetricTransform {
        double determinant = xAxisEastMetersPerSourcePixel * yAxisNorthMetersPerSourcePixel
            - xAxisNorthMetersPerSourcePixel * yAxisEastMetersPerSourcePixel;
        if (transformId == null || transformId.isBlank() || originKind == null || axisUnit == null
            || origin == null || !Double.isFinite(determinant) || Math.abs(determinant) < 1e-12
            || !Double.isFinite(rasterPixelsPerSourcePixel) || rasterPixelsPerSourcePixel <= 0.0
            || accuracyCertificate == null
            || originKind == OriginKind.METRIC_FIRST_PIXEL_CENTER
                && (axisUnit != AxisUnit.RASTER_PIXEL
                    || Double.doubleToLongBits(rasterPixelsPerSourcePixel)
                        != Double.doubleToLongBits(1.0))) {
            throw new IllegalArgumentException("Raster transform must be finite and invertible");
        }
    }

    /** Creates an axis-aligned managed-mosaic transform. */
    public static RasterMetricTransform managed(MetricPoint nativeBoundaryOrigin,
        double sourcePitchMeters, double rasterPixelsPerSourcePixel) {
        return new RasterMetricTransform("managed-pixel-boundary-v1",
            OriginKind.MANAGED_SOURCE_PIXEL_BOUNDARY, AxisUnit.SOURCE_PIXEL, nativeBoundaryOrigin,
            sourcePitchMeters, 0.0, 0.0, -sourcePitchMeters, rasterPixelsPerSourcePixel,
            RasterTransformCertificate.declaredAffine());
    }

    /** Creates an axis-aligned visible-capture transform. */
    public static RasterMetricTransform visible(MetricPoint firstRenderedCenter,
        double rasterPitchMeters) {
        return new RasterMetricTransform("visible-first-center-v1",
            OriginKind.VISIBLE_FIRST_PIXEL_CENTER, AxisUnit.SOURCE_PIXEL, firstRenderedCenter,
            rasterPitchMeters, 0.0, 0.0, -rasterPitchMeters, 1.0,
            RasterTransformCertificate.declaredAffine());
    }

    /** Creates an exact neutral output transform whose stored axes are metric metres per raster pixel. */
    public static RasterMetricTransform metricGrid(MetricPoint firstCenter,
            double xAxisEastMetersPerRasterPixel, double xAxisNorthMetersPerRasterPixel,
            double yAxisEastMetersPerRasterPixel, double yAxisNorthMetersPerRasterPixel) {
        return new RasterMetricTransform("metric-first-center-v1",
                OriginKind.METRIC_FIRST_PIXEL_CENTER, AxisUnit.RASTER_PIXEL, firstCenter,
                xAxisEastMetersPerRasterPixel, xAxisNorthMetersPerRasterPixel,
                yAxisEastMetersPerRasterPixel, yAxisNorthMetersPerRasterPixel, 1.0,
                RasterTransformCertificate.exactMetricGrid());
    }

    /** Converts a continuous raster pixel-center coordinate into metric space. */
    public MetricPoint pixelCenterToMetric(double rasterX, double rasterY) {
        double phase = originKind == OriginKind.MANAGED_SOURCE_PIXEL_BOUNDARY ? 0.5 : 0.0;
        double scale = axisUnit == AxisUnit.SOURCE_PIXEL ? rasterPixelsPerSourcePixel : 1.0;
        double axisX = rasterX / scale + phase;
        double axisY = rasterY / scale + phase;
        return new MetricPoint(origin.xMeters() + axisX * xAxisEastMetersPerSourcePixel
                + axisY * yAxisEastMetersPerSourcePixel,
            origin.yMeters() + axisX * xAxisNorthMetersPerSourcePixel
                + axisY * yAxisNorthMetersPerSourcePixel);
    }

    /** Converts a metric coordinate into continuous raster pixel-center coordinates. */
    public RasterPoint metricToPixelCenter(MetricPoint point) {
        double dx = point.xMeters() - origin.xMeters();
        double dy = point.yMeters() - origin.yMeters();
        double determinant = xAxisEastMetersPerSourcePixel * yAxisNorthMetersPerSourcePixel
            - xAxisNorthMetersPerSourcePixel * yAxisEastMetersPerSourcePixel;
        double axisX = (dx * yAxisNorthMetersPerSourcePixel - dy * yAxisEastMetersPerSourcePixel)
            / determinant;
        double axisY = (dy * xAxisEastMetersPerSourcePixel - dx * xAxisNorthMetersPerSourcePixel)
            / determinant;
        double phase = originKind == OriginKind.MANAGED_SOURCE_PIXEL_BOUNDARY ? 0.5 : 0.0;
        double scale = axisUnit == AxisUnit.SOURCE_PIXEL ? rasterPixelsPerSourcePixel : 1.0;
        return new RasterPoint((axisX - phase) * scale, (axisY - phase) * scale);
    }
}

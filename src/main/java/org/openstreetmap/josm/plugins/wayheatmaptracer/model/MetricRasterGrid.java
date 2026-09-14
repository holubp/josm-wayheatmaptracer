package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

/**
 * Exact finite raster grid constructed in a certified slide-local metric frame.
 * Integer raster coordinates address output pixel centers.
 */
public record MetricRasterGrid(
    LocalMetricFrame coordinateFrame,
    MetricPoint firstPixelCenter,
    double xAxisEastUnit,
    double xAxisNorthUnit,
    double yAxisEastUnit,
    double yAxisNorthUnit,
    double pitchMeters,
    int width,
    int height
) {
    private static final double AXIS_TOLERANCE = 1e-12;

    /** Validates finite orthonormal raster axes, physical pitch, and dimensions without allocating pixels. */
    public MetricRasterGrid {
        double xNorm = Math.hypot(xAxisEastUnit, xAxisNorthUnit);
        double yNorm = Math.hypot(yAxisEastUnit, yAxisNorthUnit);
        double dot = xAxisEastUnit * yAxisEastUnit + xAxisNorthUnit * yAxisNorthUnit;
        double determinant = xAxisEastUnit * yAxisNorthUnit - xAxisNorthUnit * yAxisEastUnit;
        if (coordinateFrame == null || firstPixelCenter == null
                || !Double.isFinite(xNorm) || !Double.isFinite(yNorm) || !Double.isFinite(dot)
                || Math.abs(xNorm - 1.0) > AXIS_TOLERANCE
                || Math.abs(yNorm - 1.0) > AXIS_TOLERANCE || Math.abs(dot) > AXIS_TOLERANCE
                || Math.abs(Math.abs(determinant) - 1.0) > AXIS_TOLERANCE
                || !Double.isFinite(pitchMeters) || pitchMeters <= 0.0
                || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Metric raster grid is inconsistent");
        }
    }

    /** Returns the exact metric location of a continuous raster-center coordinate. */
    public MetricPoint pixelCenterToMetric(double rasterX, double rasterY) {
        if (!Double.isFinite(rasterX) || !Double.isFinite(rasterY)) {
            throw new IllegalArgumentException("Raster coordinate must be finite");
        }
        return new MetricPoint(
                firstPixelCenter.xMeters() + rasterX * (pitchMeters * xAxisEastUnit)
                    + rasterY * (pitchMeters * yAxisEastUnit),
                firstPixelCenter.yMeters() + rasterX * (pitchMeters * xAxisNorthUnit)
                    + rasterY * (pitchMeters * yAxisNorthUnit));
    }

    /** Returns the exact neutral raster-to-metric transform represented by this grid. */
    public RasterMetricTransform transform() {
        return RasterMetricTransform.metricGrid(firstPixelCenter,
                pitchMeters * xAxisEastUnit, pitchMeters * xAxisNorthUnit,
                pitchMeters * yAxisEastUnit, pitchMeters * yAxisNorthUnit);
    }

    /** Returns the closed pixel-boundary footprint of the finite output grid. */
    public MetricRegion footprint() {
        RasterMetricTransform exactTransform = transform();
        return new MetricRegion(List.of(List.of(
                exactTransform.pixelCenterToMetric(-0.5, -0.5),
                exactTransform.pixelCenterToMetric(width - 0.5, -0.5),
                exactTransform.pixelCenterToMetric(width - 0.5, height - 0.5),
                exactTransform.pixelCenterToMetric(-0.5, height - 0.5))));
    }
}

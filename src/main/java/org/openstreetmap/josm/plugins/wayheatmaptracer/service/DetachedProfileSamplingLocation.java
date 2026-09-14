package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;

/**
 * Immutable detached sampling location with one certified geographic, metric, and raster association.
 *
 * <p>The factory is the normal construction path: it derives the metric and raster coordinates from the
 * supplied transforms. The full constructor remains useful for deserialization and validates every retained
 * coordinate against those transforms before accepting the value.</p>
 *
 * @param geographicPoint authoritative WGS84 coordinate
 * @param metricPoint coordinate in the supplied local metric frame
 * @param rasterPoint continuous raster pixel-center coordinate
 * @param coordinateFrame certified geographic-to-metric frame
 * @param rasterTransform explicit metric-to-raster transform
 * @param cumulativeGroundDistanceMeters factual cumulative ground chainage
 */
public record DetachedProfileSamplingLocation(
    GeographicPoint geographicPoint,
    MetricPoint metricPoint,
    RasterPoint rasterPoint,
    LocalMetricFrame coordinateFrame,
    RasterMetricTransform rasterTransform,
    double cumulativeGroundDistanceMeters
) implements ProfileSamplingLocation {
    private static final double COORDINATE_TOLERANCE = 1.0e-9;

    /** Validates the detached coordinate association and physical chainage. */
    public DetachedProfileSamplingLocation {
        Objects.requireNonNull(geographicPoint, "geographicPoint");
        Objects.requireNonNull(metricPoint, "metricPoint");
        Objects.requireNonNull(rasterPoint, "rasterPoint");
        Objects.requireNonNull(coordinateFrame, "coordinateFrame");
        Objects.requireNonNull(rasterTransform, "rasterTransform");
        if (!Double.isFinite(cumulativeGroundDistanceMeters) || cumulativeGroundDistanceMeters < 0.0) {
            throw new IllegalArgumentException("Cumulative ground distance must be finite and non-negative");
        }
        requireClose("geographic-to-metric", coordinateFrame.toMetric(geographicPoint), metricPoint);
        requireClose("metric-to-raster", rasterTransform.metricToPixelCenter(metricPoint), rasterPoint);
    }

    /** Derives the typed metric and raster coordinates from one geographic point and the supplied transforms. */
    public static DetachedProfileSamplingLocation at(
        GeographicPoint geographicPoint,
        LocalMetricFrame coordinateFrame,
        RasterMetricTransform rasterTransform,
        double cumulativeGroundDistanceMeters
    ) {
        Objects.requireNonNull(geographicPoint, "geographicPoint");
        Objects.requireNonNull(coordinateFrame, "coordinateFrame");
        Objects.requireNonNull(rasterTransform, "rasterTransform");
        MetricPoint metricPoint = coordinateFrame.toMetric(geographicPoint);
        RasterPoint rasterPoint = rasterTransform.metricToPixelCenter(metricPoint);
        return new DetachedProfileSamplingLocation(geographicPoint, metricPoint, rasterPoint,
            coordinateFrame, rasterTransform, cumulativeGroundDistanceMeters);
    }

    /** Returns whether two typed points agree within a scale-aware numerical tolerance. */
    private static void requireClose(String relationship, MetricPoint expected, MetricPoint actual) {
        if (!close(expected.xMeters(), actual.xMeters()) || !close(expected.yMeters(), actual.yMeters())) {
            throw new IllegalArgumentException("Detached sampling " + relationship + " coordinate mismatch");
        }
    }

    /** Returns whether two typed raster coordinates agree within a scale-aware numerical tolerance. */
    private static void requireClose(String relationship, RasterPoint expected, RasterPoint actual) {
        if (!close(expected.x(), actual.x()) || !close(expected.y(), actual.y())) {
            throw new IllegalArgumentException("Detached sampling " + relationship + " coordinate mismatch");
        }
    }

    private static boolean close(double expected, double actual) {
        double scale = Math.max(1.0, Math.max(Math.abs(expected), Math.abs(actual)));
        return Math.abs(expected - actual) <= COORDINATE_TOLERANCE * scale;
    }
}

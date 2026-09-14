package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;

/**
 * Closed set of exact producer-to-input-raster transforms with analytic bounds over complete
 * metric interpolation cells. Implementations are used synchronously during capture and are never
 * retained in an evidence snapshot.
 */
public sealed interface SupportedInputRasterTransform
        permits SupportedInputRasterTransform.LocalMetricAffine,
                SupportedInputRasterTransform.WebMercator,
                SupportedInputRasterTransform.VisibleWebMercator {
    double WEB_MERCATOR_MAX_LATITUDE = 85.0511287798066;
    double WEB_MERCATOR_RADIUS_METERS = 6_378_137.0;

    /** Closed source-raster coordinate bounds of a mapped metric interpolation cell. */
    record RasterBounds(double minimumX, double minimumY, double maximumX, double maximumY) {
        /** Rejects non-finite or inverted analytic bounds. */
        public RasterBounds {
            if (!Double.isFinite(minimumX) || !Double.isFinite(minimumY)
                    || !Double.isFinite(maximumX) || !Double.isFinite(maximumY)
                    || minimumX > maximumX || minimumY > maximumY) {
                throw new IllegalArgumentException("Source-raster bounds are inconsistent");
            }
        }
    }

    /** Exact mapping of one geographic coordinate to an input-raster pixel-center coordinate. */
    RasterPoint toRasterCenter(GeographicPoint geographic);

    /**
     * Returns conservative analytic extrema over the complete convex metric cell.
     * The four points must be supplied in boundary order.
     */
    RasterBounds boundsForMetricCell(LocalMetricFrame frame, List<MetricPoint> corners);

    /** Stable provenance identity for the analytic mapping and bound method. */
    String kindId();

    /** Stable exact-parameter identity retained as inert snapshot provenance. */
    String parameterIdentity();

    /** Returns whether this exact mapping is admitted for the stated producer type. */
    boolean supports(EvidenceFieldLineage.AcquisitionKind acquisitionKind);

    /** Creates an exact affine transform for synthetic metric-raster evidence. */
    static SupportedInputRasterTransform localMetricAffine(LocalMetricFrame frame,
            RasterPoint rasterCenterAtMetricOrigin, double rasterXPerEastMeter,
            double rasterXPerNorthMeter, double rasterYPerEastMeter,
            double rasterYPerNorthMeter) {
        return new LocalMetricAffine(frame, rasterCenterAtMetricOrigin,
                rasterXPerEastMeter, rasterXPerNorthMeter,
                rasterYPerEastMeter, rasterYPerNorthMeter);
    }

    /**
     * Creates the standard slippy-map Web Mercator transform for an input raster whose origin is a
     * world-pixel boundary. Input raster center zero is one half input pixel inside that boundary.
     */
    static SupportedInputRasterTransform webMercator(int zoom,
            double inputWorldPixelBoundaryX, double inputWorldPixelBoundaryY,
            double rasterPixelsPerWorldPixel) {
        return new WebMercator(zoom, inputWorldPixelBoundaryX,
                inputWorldPixelBoundaryY, rasterPixelsPerWorldPixel);
    }

    /**
     * Creates the known visible-render transform from captured Web Mercator projection bounds.
     * The rendered raster uses the slide-time projection formula directly, without managed phase.
     */
    static SupportedInputRasterTransform visibleWebMercator(double minimumProjectedEast,
            double maximumProjectedNorth, double projectionUnitsPerViewPixel,
            double rasterPixelsPerViewPixel) {
        return new VisibleWebMercator(minimumProjectedEast, maximumProjectedNorth,
                projectionUnitsPerViewPixel, rasterPixelsPerViewPixel);
    }

    /** Exact affine mapping in one specific certified local metric frame. */
    record LocalMetricAffine(LocalMetricFrame coordinateFrame,
            RasterPoint rasterCenterAtMetricOrigin, double rasterXPerEastMeter,
            double rasterXPerNorthMeter, double rasterYPerEastMeter,
            double rasterYPerNorthMeter) implements SupportedInputRasterTransform {
        /** Validates a finite invertible constructed affine mapping. */
        public LocalMetricAffine {
            double determinant = rasterXPerEastMeter * rasterYPerNorthMeter
                    - rasterXPerNorthMeter * rasterYPerEastMeter;
            if (coordinateFrame == null || rasterCenterAtMetricOrigin == null
                    || !Double.isFinite(determinant) || Math.abs(determinant) < 1e-18
                    || !finite(rasterXPerEastMeter, rasterXPerNorthMeter,
                            rasterYPerEastMeter, rasterYPerNorthMeter)) {
                throw new IllegalArgumentException("Local metric affine transform must be finite and invertible");
            }
        }

        @Override
        public RasterPoint toRasterCenter(GeographicPoint geographic) {
            return map(coordinateFrame.toMetric(geographic));
        }

        @Override
        public RasterBounds boundsForMetricCell(LocalMetricFrame frame, List<MetricPoint> corners) {
            if (!coordinateFrame.equals(frame)) {
                throw new IllegalArgumentException("Affine input transform uses a different metric frame");
            }
            return bounds(corners.stream().map(this::map).toList());
        }

        @Override
        public String kindId() {
            return "constructed-local-metric-affine-v1";
        }

        @Override
        public String parameterIdentity() {
            return kindId() + "|" + coordinateFrame.projectionId()
                    + "|" + hex(coordinateFrame.origin().latitudeDegrees())
                    + "|" + hex(coordinateFrame.origin().longitudeDegrees())
                    + "|" + hex(rasterCenterAtMetricOrigin.x())
                    + "|" + hex(rasterCenterAtMetricOrigin.y())
                    + "|" + hex(rasterXPerEastMeter) + "|" + hex(rasterXPerNorthMeter)
                    + "|" + hex(rasterYPerEastMeter) + "|" + hex(rasterYPerNorthMeter);
        }

        @Override
        public boolean supports(EvidenceFieldLineage.AcquisitionKind acquisitionKind) {
            return acquisitionKind == EvidenceFieldLineage.AcquisitionKind.SYNTHETIC;
        }

        private RasterPoint map(MetricPoint metric) {
            return new RasterPoint(
                    rasterCenterAtMetricOrigin.x() + rasterXPerEastMeter * metric.xMeters()
                        + rasterXPerNorthMeter * metric.yMeters(),
                    rasterCenterAtMetricOrigin.y() + rasterYPerEastMeter * metric.xMeters()
                        + rasterYPerNorthMeter * metric.yMeters());
        }
    }

    /** Exact standard Web Mercator mapping with monotone analytic latitude/longitude bounds. */
    record WebMercator(int zoom, double inputWorldPixelBoundaryX,
            double inputWorldPixelBoundaryY,
            double rasterPixelsPerWorldPixel) implements SupportedInputRasterTransform {
        /** Validates bounded zoom and a finite source-raster world origin. */
        public WebMercator {
            if (zoom < 0 || zoom > 30 || !Double.isFinite(inputWorldPixelBoundaryX)
                    || !Double.isFinite(inputWorldPixelBoundaryY)
                    || !Double.isFinite(rasterPixelsPerWorldPixel)
                    || rasterPixelsPerWorldPixel <= 0.0) {
                throw new IllegalArgumentException("Web Mercator input transform is inconsistent");
            }
        }

        @Override
        public RasterPoint toRasterCenter(GeographicPoint geographic) {
            requireMercatorLatitude(geographic);
            double worldSize = Math.scalb(256.0, zoom);
            double longitudeWorld = (geographic.longitudeDegrees() + 180.0) / 360.0 * worldSize;
            double latitudeRadians = Math.toRadians(geographic.latitudeDegrees());
            double latitudeWorld = (1.0
                    - Math.log(Math.tan(latitudeRadians) + 1.0 / Math.cos(latitudeRadians))
                        / Math.PI) * 0.5 * worldSize;
            return new RasterPoint(
                    (longitudeWorld - inputWorldPixelBoundaryX)
                        * rasterPixelsPerWorldPixel - 0.5,
                    (latitudeWorld - inputWorldPixelBoundaryY)
                        * rasterPixelsPerWorldPixel - 0.5);
        }

        @Override
        public RasterBounds boundsForMetricCell(LocalMetricFrame frame, List<MetricPoint> corners) {
            List<GeographicPoint> geographic = validatedCorners(frame, corners);
            rejectAntimeridianWrap(geographic);
            return bounds(geographic.stream().map(this::toRasterCenter).toList());
        }

        @Override
        public String kindId() {
            return "web-mercator-world-pixel-boundary-v1";
        }

        @Override
        public String parameterIdentity() {
            return kindId() + "|" + zoom + "|" + hex(inputWorldPixelBoundaryX)
                    + "|" + hex(inputWorldPixelBoundaryY)
                    + "|" + hex(rasterPixelsPerWorldPixel);
        }

        @Override
        public boolean supports(EvidenceFieldLineage.AcquisitionKind acquisitionKind) {
            return acquisitionKind == EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE;
        }
    }

    /** Exact visible-render mapping for explicitly captured Web Mercator projection bounds. */
    record VisibleWebMercator(double minimumProjectedEast, double maximumProjectedNorth,
            double projectionUnitsPerViewPixel,
            double rasterPixelsPerViewPixel) implements SupportedInputRasterTransform {
        /** Validates the complete slide-time rendered sampling frame. */
        public VisibleWebMercator {
            if (!finite(minimumProjectedEast, maximumProjectedNorth,
                    projectionUnitsPerViewPixel, rasterPixelsPerViewPixel)
                    || projectionUnitsPerViewPixel <= 0.0 || rasterPixelsPerViewPixel <= 0.0) {
                throw new IllegalArgumentException(
                        "Visible Web Mercator capture transform is inconsistent");
            }
        }

        @Override
        public RasterPoint toRasterCenter(GeographicPoint geographic) {
            requireMercatorLatitude(geographic);
            double longitudeRadians = Math.toRadians(geographic.longitudeDegrees());
            double latitudeRadians = Math.toRadians(geographic.latitudeDegrees());
            double projectedEast = WEB_MERCATOR_RADIUS_METERS * longitudeRadians;
            double projectedNorth = WEB_MERCATOR_RADIUS_METERS
                    * Math.log(Math.tan(Math.PI / 4.0 + latitudeRadians / 2.0));
            return new RasterPoint(
                    (projectedEast - minimumProjectedEast)
                        / projectionUnitsPerViewPixel * rasterPixelsPerViewPixel,
                    (maximumProjectedNorth - projectedNorth)
                        / projectionUnitsPerViewPixel * rasterPixelsPerViewPixel);
        }

        @Override
        public RasterBounds boundsForMetricCell(LocalMetricFrame frame, List<MetricPoint> corners) {
            List<GeographicPoint> geographic = validatedCorners(frame, corners);
            rejectAntimeridianWrap(geographic);
            return bounds(geographic.stream().map(this::toRasterCenter).toList());
        }

        @Override
        public String kindId() {
            return "visible-web-mercator-projection-bounds-v1";
        }

        @Override
        public String parameterIdentity() {
            return kindId() + "|" + hex(minimumProjectedEast)
                    + "|" + hex(maximumProjectedNorth)
                    + "|" + hex(projectionUnitsPerViewPixel)
                    + "|" + hex(rasterPixelsPerViewPixel);
        }

        @Override
        public boolean supports(EvidenceFieldLineage.AcquisitionKind acquisitionKind) {
            return acquisitionKind == EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER;
        }
    }

    private static List<GeographicPoint> validatedCorners(
            LocalMetricFrame frame, List<MetricPoint> corners) {
        if (frame == null || corners == null || corners.size() != 4
                || corners.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Analytic cell bounds require four metric corners");
        }
        List<GeographicPoint> result = corners.stream().map(frame::toGeographic).toList();
        result.forEach(SupportedInputRasterTransform::requireMercatorLatitude);
        return result;
    }

    private static void rejectAntimeridianWrap(List<GeographicPoint> geographic) {
        double minimumLongitude = geographic.stream()
                .mapToDouble(GeographicPoint::longitudeDegrees).min().orElseThrow();
        double maximumLongitude = geographic.stream()
                .mapToDouble(GeographicPoint::longitudeDegrees).max().orElseThrow();
        if (maximumLongitude - minimumLongitude > 180.0) {
            throw new IllegalArgumentException(
                    "Web Mercator analytic support does not admit antimeridian-wrapping cells");
        }
    }

    private static void requireMercatorLatitude(GeographicPoint geographic) {
        if (geographic == null
                || Math.abs(geographic.latitudeDegrees()) >= WEB_MERCATOR_MAX_LATITUDE) {
            throw new IllegalArgumentException(
                    "Web Mercator analytic support requires a non-polar geographic domain");
        }
    }

    private static RasterBounds bounds(List<RasterPoint> points) {
        if (points == null || points.size() != 4) {
            throw new IllegalArgumentException("Analytic cell bounds require four mapped corners");
        }
        return new RasterBounds(
                points.stream().mapToDouble(RasterPoint::x).min().orElseThrow(),
                points.stream().mapToDouble(RasterPoint::y).min().orElseThrow(),
                points.stream().mapToDouble(RasterPoint::x).max().orElseThrow(),
                points.stream().mapToDouble(RasterPoint::y).max().orElseThrow());
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static String hex(double value) {
        return Double.toHexString(value);
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
import java.util.function.UnaryOperator;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.MetricCorridorRegion;

/** Captures a live raster into a detached scalar evidence snapshot before worker inference starts. */
public final class RasterEvidenceCapture {
    /** One scalar field mapping and its immutable provenance. */
    public record FieldSpec(String name, IntToDoubleFunction argbMapping,
            EvidenceFieldLineage lineage, UnaryOperator<ScalarEvidenceField> postMappingOperation) {
        /** Validates a named scalar-before-filter capture operation. */
        public FieldSpec {
            if (name == null || name.isBlank() || argbMapping == null || lineage == null
                    || postMappingOperation == null) {
                throw new IllegalArgumentException("Raster evidence field specification is incomplete");
            }
        }

        /** Creates an unfiltered field specification. */
        public static FieldSpec direct(String name, IntToDoubleFunction mapping,
                EvidenceFieldLineage lineage) {
            return new FieldSpec(name, mapping, lineage, UnaryOperator.identity());
        }
    }

    /**
     * Converts the acquired image into immutable scalar fields in a certified local metric frame.
     * The supplied raster-to-geographic function is used only during capture and is never retained.
     */
    public EvidenceSnapshot capture(String snapshotId, BufferedImage raster,
            List<GeographicPoint> sourcePolyline, java.util.function.Function<RasterPoint, GeographicPoint> rasterToGeographic,
            EvidenceResolution resolution, double decisionRadiusMeters, String sourceIdentity,
            EvidenceFieldLineage.AcquisitionKind acquisitionKind, List<FieldSpec> fieldSpecs) {
        if (snapshotId == null || snapshotId.isBlank() || raster == null || raster.getWidth() < 2
                || raster.getHeight() < 2 || sourcePolyline == null || sourcePolyline.size() < 2
                || rasterToGeographic == null || resolution == null || !Double.isFinite(decisionRadiusMeters)
                || decisionRadiusMeters <= 0.0 || sourceIdentity == null || sourceIdentity.isBlank()
                || acquisitionKind == null || fieldSpecs == null || fieldSpecs.isEmpty()) {
            throw new IllegalArgumentException("Raster evidence capture is incomplete");
        }
        GeographicPoint origin = sourcePolyline.get(0);
        List<GeographicPoint> domain = new ArrayList<>(sourcePolyline);
        domain.add(rasterToGeographic.apply(new RasterPoint(-0.5, -0.5)));
        domain.add(rasterToGeographic.apply(new RasterPoint(raster.getWidth() - 0.5, -0.5)));
        domain.add(rasterToGeographic.apply(new RasterPoint(-0.5, raster.getHeight() - 0.5)));
        domain.add(rasterToGeographic.apply(new RasterPoint(raster.getWidth() - 0.5,
                raster.getHeight() - 0.5)));
        LocalMetricFrame frame = frame(origin, domain);
        RasterMetricTransform transform = transform(frame, rasterToGeographic);
        MetricRegion footprint = footprint(transform, raster.getWidth(), raster.getHeight());
        List<MetricPoint> metricSource = sourcePolyline.stream().map(frame::toMetric).toList();
        MetricRegion decision = MetricCorridorRegion.aroundPolyline(metricSource, decisionRadiusMeters);
        if (!footprint.containsRegion(decision)) {
            throw new IllegalArgumentException("Acquired raster does not cover the complete modern decision corridor");
        }
        int[] argb = raster.getRGB(0, 0, raster.getWidth(), raster.getHeight(), null, 0,
                raster.getWidth());
        boolean[] valid = new boolean[argb.length];
        java.util.Arrays.fill(valid, true);
        Map<String, ScalarEvidenceField> fields = new LinkedHashMap<>();
        for (FieldSpec spec : fieldSpecs) {
            if (spec.lineage().acquisitionKind() != acquisitionKind) {
                throw new IllegalArgumentException("Field lineage does not match the capture source");
            }
            ScalarEvidenceField mapped = ScalarEvidenceFactory.mapArgb(raster.getWidth(),
                    raster.getHeight(), argb, valid, spec.argbMapping(), spec.lineage());
            ScalarEvidenceField result = spec.postMappingOperation().apply(mapped);
            if (result == null || result.width() != raster.getWidth()
                    || result.height() != raster.getHeight()) {
                throw new IllegalArgumentException("Post-mapping scalar operation changed the raster frame");
            }
            if (fields.put(spec.name(), result) != null) {
                throw new IllegalArgumentException("Duplicate scalar evidence field name");
            }
        }
        return new EvidenceSnapshot(snapshotId, frame, transform, resolution, decision, footprint,
                fields, sourceIdentity);
    }

    private static RasterMetricTransform transform(LocalMetricFrame frame,
            java.util.function.Function<RasterPoint, GeographicPoint> rasterToGeographic) {
        MetricPoint origin = frame.toMetric(rasterToGeographic.apply(new RasterPoint(0, 0)));
        MetricPoint x = frame.toMetric(rasterToGeographic.apply(new RasterPoint(1, 0)));
        MetricPoint y = frame.toMetric(rasterToGeographic.apply(new RasterPoint(0, 1)));
        return new RasterMetricTransform("captured-raster-center-v1",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, origin,
                x.xMeters() - origin.xMeters(), x.yMeters() - origin.yMeters(),
                y.xMeters() - origin.xMeters(), y.yMeters() - origin.yMeters(), 1.0);
    }

    private static MetricRegion footprint(RasterMetricTransform transform, int width, int height) {
        return new MetricRegion(List.of(List.of(
                transform.pixelCenterToMetric(-0.5, -0.5),
                transform.pixelCenterToMetric(width - 0.5, -0.5),
                transform.pixelCenterToMetric(width - 0.5, height - 0.5),
                transform.pixelCenterToMetric(-0.5, height - 0.5))));
    }

    private static LocalMetricFrame frame(GeographicPoint origin, List<GeographicPoint> domain) {
        double minLatitude = domain.stream().mapToDouble(GeographicPoint::latitudeDegrees).min().orElseThrow();
        double maxLatitude = domain.stream().mapToDouble(GeographicPoint::latitudeDegrees).max().orElseThrow();
        double minLongitudeDelta = domain.stream().mapToDouble(point -> longitudeDelta(
                point.longitudeDegrees() - origin.longitudeDegrees())).min().orElseThrow();
        double maxLongitudeDelta = domain.stream().mapToDouble(point -> longitudeDelta(
                point.longitudeDegrees() - origin.longitudeDegrees())).max().orElseThrow();
        GeographicPoint southWest = new GeographicPoint(minLatitude,
                normalizeLongitude(origin.longitudeDegrees() + minLongitudeDelta));
        GeographicPoint northEast = new GeographicPoint(maxLatitude,
                normalizeLongitude(origin.longitudeDegrees() + maxLongitudeDelta));
        return LocalMetricFrame.certifiedEquirectangular(origin, southWest, northEast);
    }

    private static double longitudeDelta(double value) {
        double result = value % 360.0;
        if (result > 180.0) {
            result -= 360.0;
        } else if (result <= -180.0) {
            result += 360.0;
        }
        return result;
    }

    private static double normalizeLongitude(double value) {
        double result = longitudeDelta(value);
        return result == -180.0 ? 180.0 : result;
    }
}

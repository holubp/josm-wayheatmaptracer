package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Complete immutable scalar evidence owned by one slide attempt. */
public record EvidenceSnapshot(
    String snapshotId,
    LocalMetricFrame coordinateFrame,
    RasterMetricTransform transform,
    EvidenceResolution resolution,
    MetricRegion decisionRegion,
    MetricRegion evidenceRegion,
    Map<String, ScalarEvidenceField> fields,
    RasterResamplingProvenance resampling,
    String sourceIdentity
) {
    /** Preserves direct-grid construction semantics for existing snapshot producers. */
    public EvidenceSnapshot(String snapshotId, LocalMetricFrame coordinateFrame,
            RasterMetricTransform transform, EvidenceResolution resolution,
            MetricRegion decisionRegion, MetricRegion evidenceRegion,
            Map<String, ScalarEvidenceField> fields, String sourceIdentity) {
        this(snapshotId, coordinateFrame, transform, resolution, decisionRegion, evidenceRegion,
                fields, directProvenance(fields), sourceIdentity);
    }

    /** Copies containers and proves region, raster, resolution, and resampling ownership. */
    public EvidenceSnapshot {
        if (snapshotId == null || snapshotId.isBlank() || sourceIdentity == null || sourceIdentity.isBlank()) {
            throw new IllegalArgumentException("Evidence snapshot identity is required");
        }
        coordinateFrame = Objects.requireNonNull(coordinateFrame, "coordinateFrame");
        transform = Objects.requireNonNull(transform, "transform");
        resolution = Objects.requireNonNull(resolution, "resolution");
        decisionRegion = Objects.requireNonNull(decisionRegion, "decisionRegion");
        evidenceRegion = Objects.requireNonNull(evidenceRegion, "evidenceRegion");
        resampling = Objects.requireNonNull(resampling, "resampling");
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("Evidence snapshot requires at least one scalar field");
        }
        fields = Map.copyOf(new LinkedHashMap<>(fields));
        int width = fields.values().iterator().next().width();
        int height = fields.values().iterator().next().height();
        if (fields.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
            || entry.getValue() == null || entry.getValue().width() != width || entry.getValue().height() != height)) {
            throw new IllegalArgumentException("Evidence fields must share one named raster frame");
        }
        if (resampling.outputWidth() != width || resampling.outputHeight() != height) {
            throw new IllegalArgumentException("Resampling provenance does not match the evidence raster");
        }
        MetricPoint topLeft = transform.pixelCenterToMetric(-0.5, -0.5);
        MetricPoint topRight = transform.pixelCenterToMetric(width - 0.5, -0.5);
        MetricPoint bottomRight = transform.pixelCenterToMetric(width - 0.5, height - 0.5);
        MetricPoint bottomLeft = transform.pixelCenterToMetric(-0.5, height - 0.5);
        MetricRegion rasterFootprint = new MetricRegion(java.util.List.of(
            java.util.List.of(topLeft, topRight, bottomRight, bottomLeft)));
        if (!evidenceRegion.containsRegion(decisionRegion) || !rasterFootprint.containsRegion(evidenceRegion)) {
            throw new IllegalArgumentException("Decision, halo, and raster regions are inconsistent");
        }
        org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier.verify(
            java.util.List.of(coordinateFrame, transform, resolution, decisionRegion, evidenceRegion,
                    fields, resampling));
    }

    /** Returns a content-bound hash over coordinates, masks, scalar values, resolution, and source lineage. */
    public String canonicalHash() {
        CanonicalEncoder encoder = new CanonicalEncoder().field("evidence-snapshot-v3")
            .field(sourceIdentity).field(coordinateFrame.projectionId())
            .field(Double.toHexString(coordinateFrame.origin().latitudeDegrees()))
            .field(Double.toHexString(coordinateFrame.origin().longitudeDegrees()))
            .field(coordinateFrame.distortionCertificate().method())
            .field(Double.toHexString(coordinateFrame.distortionCertificate().maximumRelativeDistanceError()))
            .field(transform.transformId()).field(transform.originKind().name()).field(transform.axisUnit().name())
            .field(Double.toHexString(transform.origin().xMeters()))
            .field(Double.toHexString(transform.origin().yMeters()))
            .field(Double.toHexString(transform.xAxisEastMetersPerSourcePixel()))
            .field(Double.toHexString(transform.xAxisNorthMetersPerSourcePixel()))
            .field(Double.toHexString(transform.yAxisEastMetersPerSourcePixel()))
            .field(Double.toHexString(transform.yAxisNorthMetersPerSourcePixel()))
            .field(Double.toHexString(transform.rasterPixelsPerSourcePixel()))
            .field(transform.accuracyCertificate().method())
            .field(Double.toHexString(transform.accuracyCertificate().maximumErrorMeters()))
            .field(Double.toHexString(transform.accuracyCertificate().toleranceMeters()))
            .field(transform.accuracyCertificate().verificationPointCount())
            .field(resolution.kind().name())
            .field(resolution.nativePitchMeters().isPresent())
            .field(resolution.nativePitchMeters().isPresent()
                ? Double.toHexString(resolution.nativePitchMeters().getAsDouble()) : "unknown")
            .field(Double.toHexString(resolution.renderedPitchMeters()))
            .field(resolution.resampledPitchMeters().isPresent())
            .field(resolution.resampledPitchMeters().isPresent()
                ? Double.toHexString(resolution.resampledPitchMeters().getAsDouble()) : "not-resampled")
            .field(resampling.method()).field(resampling.sourceTransformKind())
            .field(resampling.sourceTransformIdentity())
            .field(resampling.inputCoordinateConvention())
            .field(resampling.scalarOrder()).field(resampling.validityRule())
            .field(resampling.inputWidth()).field(resampling.inputHeight())
            .field(resampling.outputWidth()).field(resampling.outputHeight());
        resolution.spatialPitchSamples().forEach(sample -> encoder.field("pitch")
            .field(Double.toHexString(sample.chainageMeters()))
            .field(sample.nativePitchMeters().isPresent())
            .field(sample.nativePitchMeters().isPresent()
                ? Double.toHexString(sample.nativePitchMeters().getAsDouble()) : "unknown")
            .field(Double.toHexString(sample.renderedPitchMeters())));
        encodeRegion(encoder.field("decision"), decisionRegion);
        encodeRegion(encoder.field("evidence"), evidenceRegion);
        fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ScalarEvidenceField field = entry.getValue();
            EvidenceFieldLineage lineage = field.lineage();
            encoder.field("field").field(entry.getKey()).field(field.width()).field(field.height())
                .field(lineage.acquisitionKind().name()).field(lineage.derivationKind().name())
                .field(lineage.sourcePalette()).field(lineage.correlationGroup().name())
                .field(lineage.completeAggregate());
            lineage.scalarOperations().forEach(operation -> encoder.field("operation").field(operation));
            double[] values = field.copiedValues();
            boolean[] valid = field.copiedValidity();
            for (int index = 0; index < values.length; index++) {
                encoder.field(valid[index]).field(Double.toHexString(values[index]));
            }
            boolean[] interpolationValid = field.copiedInterpolationValidity();
            for (boolean completeCellSupport : interpolationValid) {
                encoder.field("cell-support").field(completeCellSupport);
            }
        });
        return encoder.sha256();
    }

    private static RasterResamplingProvenance directProvenance(
            Map<String, ScalarEvidenceField> fields) {
        if (fields == null || fields.isEmpty() || fields.values().iterator().next() == null) {
            throw new IllegalArgumentException("Evidence snapshot requires at least one scalar field");
        }
        ScalarEvidenceField field = fields.values().iterator().next();
        return RasterResamplingProvenance.direct(field.width(), field.height());
    }

    private static void encodeRegion(CanonicalEncoder encoder, MetricRegion region) {
        region.polygons().forEach(polygon -> {
            encoder.field("polygon").field(polygon.size());
            polygon.forEach(point -> encoder.field(Double.toHexString(point.xMeters()))
                .field(Double.toHexString(point.yMeters())));
        });
    }

    /** Returns closed correlation groups, not palette count, as independent evidence groups. */
    public Set<EvidenceCorrelationGroup> independentEvidenceGroups() {
        return fields.values().stream().map(field -> field.lineage().correlationGroup())
            .collect(Collectors.toUnmodifiableSet());
    }

    /** Returns whether a point may be used as route position rather than filter halo only. */
    public boolean routePositionAuthorized(MetricPoint point) {
        return decisionRegion.contains(point);
    }

    /** Returns whether a complete candidate segment stays inside the decision region. */
    public boolean routeSegmentAuthorized(MetricPoint start, MetricPoint end) {
        return decisionRegion.containsSegment(start, end);
    }
}

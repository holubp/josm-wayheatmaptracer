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
    String sourceIdentity
) {
    /** Copies containers and proves region and common-raster ownership. */
    public EvidenceSnapshot {
        if (snapshotId == null || snapshotId.isBlank() || sourceIdentity == null || sourceIdentity.isBlank()) {
            throw new IllegalArgumentException("Evidence snapshot identity is required");
        }
        coordinateFrame = Objects.requireNonNull(coordinateFrame, "coordinateFrame");
        transform = Objects.requireNonNull(transform, "transform");
        resolution = Objects.requireNonNull(resolution, "resolution");
        decisionRegion = Objects.requireNonNull(decisionRegion, "decisionRegion");
        evidenceRegion = Objects.requireNonNull(evidenceRegion, "evidenceRegion");
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
            java.util.List.of(coordinateFrame, transform, resolution, decisionRegion, evidenceRegion, fields));
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

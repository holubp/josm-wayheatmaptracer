package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Map;

/**
 * Read-only scalar evidence access shared by native and experimental samplers.
 * Implementing this interface certifies neither capture origin nor validation,
 * ownership, source identity, or authorization to issue an experimental input.
 */
public interface EvidenceReadView {
    String snapshotId();
    LocalMetricFrame coordinateFrame();
    RasterMetricTransform transform();
    EvidenceResolution resolution();
    MetricRegion decisionRegion();
    MetricRegion evidenceRegion();
    Map<String, ScalarEvidenceField> fields();
    RasterResamplingProvenance resampling();
    String sourceIdentity();
    boolean routePositionAuthorized(MetricPoint point);
    boolean routeSegmentAuthorized(MetricPoint start, MetricPoint end);
}

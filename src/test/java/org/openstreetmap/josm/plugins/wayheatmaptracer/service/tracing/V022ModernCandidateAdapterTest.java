package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterResamplingProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;

/** Existing-preview adaptation checks for detached modern hypotheses. */
class V022ModernCandidateAdapterTest {
    @Test
    void projectionOffsetsAndUniformBrightnessRemainExplicitButUnlocalized() {
        EvidenceSnapshot evidence = evidence();
        TraceHypothesis route = new TraceHypothesis("b-0", "main",
                List.of(new MetricPoint(1, 1), new MetricPoint(5, 2), new MetricPoint(9, 1)),
                List.of(ObservationOwnership.DIRECT_TWO_SIDED, ObservationOwnership.INFERRED_GAP,
                        ObservationOwnership.DIRECT_TWO_SIDED), 2.5, 0.6,
                Map.of("longitudinalPersistence", 0.8));
        TraceHypothesisSet set = new TraceHypothesisSet(TrackerMode.PROBABILISTIC, List.of(route),
                TraceHypothesisSet.Status.COMPLETE, false, 3, 2, "complete");

        var candidate = new ModernCandidateAdapter().adapt(set, evidence, "hot",
                List.of(new MetricPoint(1, 1), new MetricPoint(9, 1)), point -> {
                    MetricPoint metric = evidence.coordinateFrame().toMetric(point);
                    return new EastNorth(metric.xMeters(), metric.yMeters());
                }).get(0);

        assertEquals(3, candidate.eastNorthPoints().size());
        assertEquals(1.0, candidate.offsetsPx().get(1), 1.0e-9);
        assertFalse(candidate.evidence().hasSignal());
        assertEquals(0, candidate.evidence().supportedProfiles());
        assertEquals("modern-incomplete-evidence", candidate.evidence().corridorCoverage().reason());
    }

    @Test
    void resampledOutputPitchDefinesCandidateRasterOffsets() {
        EvidenceSnapshot evidence = resampledEvidence();
        TraceHypothesis route = new TraceHypothesis("b-resampled", "main",
                List.of(new MetricPoint(0.2, 0.2), new MetricPoint(1.0, 0.4),
                        new MetricPoint(1.8, 0.2)),
                List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED),
                1.0, 0.8, Map.of());
        TraceHypothesisSet set = new TraceHypothesisSet(TrackerMode.PROBABILISTIC, List.of(route),
                TraceHypothesisSet.Status.COMPLETE, false, 3, 3, "complete");

        var candidate = new ModernCandidateAdapter().adapt(set, evidence, "hot",
                List.of(new MetricPoint(0.2, 0.2), new MetricPoint(1.8, 0.2)), point -> {
                    MetricPoint metric = evidence.coordinateFrame().toMetric(point);
                    return new EastNorth(metric.xMeters(), metric.yMeters());
                }).get(0);

        assertEquals(2.4, evidence.resolution().effectivePitchMeters(), 0.0);
        assertEquals(0.4, evidence.resolution().renderedPitchMeters(), 0.0);
        assertEquals(0.2, evidence.resolution().resampledPitchMeters().orElseThrow(), 0.0);
        assertEquals(1.0, candidate.offsetsPx().get(1), 1.0e-9);
    }

    private static EvidenceSnapshot resampledEvidence() {
        EvidenceSnapshot direct = evidence();
        return new EvidenceSnapshot("resampled", direct.coordinateFrame(),
                RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 0.2, 0, 0, 0.2),
                EvidenceResolution.nativeSource(2.4, 0.4).resampledTo(0.2),
                MetricRegion.rectangle(0, 0, 2.2, 2.2),
                MetricRegion.rectangle(-0.1, -0.1, 2.3, 2.3), direct.fields(),
                RasterResamplingProvenance.exactInverseBilinear(
                        "constructed-local-metric-affine-v1", "test-affine-v1",
                        12, 12, 12, 12), "source");
    }

    @Test
    void freshRelativeEvidenceReplacesStaleNoSignalButNotTopologyOnlyOwnership() {
        EvidenceSnapshot evidence = evidence(true);
        List<MetricPoint> points = List.of(new MetricPoint(1, 5), new MetricPoint(5, 5),
                new MetricPoint(9, 5));
        TraceHypothesis stale = new TraceHypothesis("faint", "main", points,
                List.of(ObservationOwnership.FIXED_TOPOLOGY_ONLY,
                        ObservationOwnership.NO_SIGNAL_VALID_RASTER,
                        ObservationOwnership.FIXED_TOPOLOGY_ONLY), 1.0, 0.0, Map.of());
        TraceHypothesis topologyOnly = new TraceHypothesis("topology", "main", points,
                List.of(ObservationOwnership.FIXED_TOPOLOGY_ONLY,
                        ObservationOwnership.FIXED_TOPOLOGY_ONLY,
                        ObservationOwnership.FIXED_TOPOLOGY_ONLY), 1.0, 0.0, Map.of());
        ModernCandidateAdapter adapter = new ModernCandidateAdapter();

        var refreshed = adapter.adapt(new TraceHypothesisSet(TrackerMode.DIRECTIONAL_IMAGE,
                List.of(stale), TraceHypothesisSet.Status.COMPLETE, false, 1, 1, "faint"),
                evidence, "hot", List.of(points.get(0), points.get(2)),
                point -> project(evidence, point)).get(0);
        var protectedCandidate = adapter.adapt(new TraceHypothesisSet(TrackerMode.DIRECTIONAL_IMAGE,
                List.of(topologyOnly), TraceHypothesisSet.Status.COMPLETE, false, 1, 1, "topology"),
                evidence, "hot", List.of(points.get(0), points.get(2)),
                point -> project(evidence, point)).get(0);

        assertEquals(1, refreshed.evidence().supportedProfiles());
        assertTrue(refreshed.evidence().hasSignal());
        assertEquals(0, protectedCandidate.evidence().supportedProfiles());
    }

    @Test
    void topologyOnlyDuplicatesAreRetainedAndUndefinedMeasurementTangentsFailClosed() {
        EvidenceSnapshot evidence = evidence(true);
        MetricPoint first = new MetricPoint(1, 5);
        MetricPoint second = new MetricPoint(9, 5);
        ModernCandidateAdapter adapter = new ModernCandidateAdapter();
        for (List<MetricPoint> points : List.of(
                List.of(first, first, second), List.of(first, second, first))) {
            TraceHypothesis topologyOnly = new TraceHypothesis("topology-duplicates", "main",
                    points, java.util.Collections.nCopies(points.size(),
                            ObservationOwnership.FIXED_TOPOLOGY_ONLY),
                    1.0, 0.0, Map.of());
            var candidate = assertDoesNotThrow(() -> adapter.adapt(
                    new TraceHypothesisSet(TrackerMode.DIRECTIONAL_IMAGE,
                            List.of(topologyOnly), TraceHypothesisSet.Status.COMPLETE,
                            false, 1, 1, "topology"),
                    evidence, "hot", List.of(first, second),
                    point -> project(evidence, point))).get(0);
            assertEquals(points.size(), candidate.screenPoints().size());
            assertEquals(0, candidate.evidence().supportedProfiles());
        }

        List<MetricPoint> reversing = List.of(first, second, first);
        TraceHypothesis undefinedMeasurement = new TraceHypothesis("undefined-tangent", "main",
                reversing, List.of(ObservationOwnership.FIXED_TOPOLOGY_ONLY,
                        ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.FIXED_TOPOLOGY_ONLY),
                1.0, 0.0, Map.of());
        var candidate = assertDoesNotThrow(() -> adapter.adapt(
                new TraceHypothesisSet(TrackerMode.DIRECTIONAL_IMAGE,
                        List.of(undefinedMeasurement), TraceHypothesisSet.Status.COMPLETE,
                        false, 1, 1, "undefined"),
                evidence, "hot", List.of(first, second),
                point -> project(evidence, point))).get(0);
        assertEquals(3, candidate.screenPoints().size());
        assertEquals(0, candidate.evidence().supportedProfiles());
    }

    @Test
    void finalRouteProjectionPreservesFinalGeometryAndCompleteExistingAssignments() {
        EvidenceSnapshot evidence = evidence(true);
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 10);
        PrimitiveKey firstNode = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey lastNode = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        List<MetricPoint> rawPoints = List.of(new MetricPoint(1, 1),
            new MetricPoint(5, 1), new MetricPoint(9, 1));
        List<MetricPoint> finalPoints = List.of(new MetricPoint(1, 1),
            new MetricPoint(5, 5), new MetricPoint(9, 1));
        TraceHypothesis raw = new TraceHypothesis("route-final", "main", rawPoints,
            java.util.Collections.nCopies(3, ObservationOwnership.DIRECT_TWO_SIDED),
            2.0, 0.8, Map.of());
        TraceHypothesis finalized = new TraceHypothesis("route-final", "main", finalPoints,
            java.util.Collections.nCopies(3, ObservationOwnership.DIRECT_TWO_SIDED),
            2.0, 0.8, Map.of());
        List<FinalRoutePointId> ids = List.of(
            new ExistingWayNodeOccurrence(way, firstNode, 0),
            new GeneratedCandidatePoint("route-final", 1),
            new ExistingWayNodeOccurrence(way, lastNode, 1));
        Map<FinalRoutePointId, MetricPoint> assignments = Map.of(
            ids.get(0), finalPoints.get(0), ids.get(1), finalPoints.get(1),
            ids.get(2), finalPoints.get(2));
        Map<FinalRoutePointId, ObservationOwnership> ownership = Map.of(
            ids.get(0), ObservationOwnership.FIXED_TOPOLOGY_ONLY,
            ids.get(1), ObservationOwnership.DIRECT_TWO_SIDED,
            ids.get(2), ObservationOwnership.FIXED_TOPOLOGY_ONLY);
        FinalGeometryEvaluator.Result quality = new FinalGeometryEvaluator.Result(
            "route-final", FinalGeometryEvaluator.Disposition.APPLICABLE, List.of(),
            10.0, 10.0, 0.0, 0.1, 0.0);
        ModernTracePipeline.Route route = new ModernTracePipeline.Route(raw, finalized,
            ids, assignments, ownership, quality,
            ImageSupportedLocalCleanup.Status.UNCHANGED, true);

        var candidate = new ModernCandidateAdapter().adaptRoutes(List.of(route),
            TrackerMode.PROBABILISTIC, evidence, "hot",
            List.of(rawPoints.get(0), rawPoints.get(2)), point -> project(evidence, point)).get(0);

        assertEquals(finalPoints.stream().map(point -> project(evidence,
            evidence.coordinateFrame().toGeographic(point))).toList(),
            candidate.finalPreviewPoints());
        assertEquals(Map.of(1L, candidate.finalPreviewPoints().get(0),
            2L, candidate.finalPreviewPoints().get(2)), candidate.proposedNodePositions());
    }

    private static EastNorth project(EvidenceSnapshot evidence, GeographicPoint point) {
        MetricPoint metric = evidence.coordinateFrame().toMetric(point);
        return new EastNorth(metric.xMeters(), metric.yMeters());
    }

    private static EvidenceSnapshot evidence() {
        return evidence(false);
    }

    private static EvidenceSnapshot evidence(boolean faintRidge) {
        GeographicPoint origin = new GeographicPoint(42, 19);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
                new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        int size = 12;
        double[] values = new double[size * size];
        boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(values, faintRidge ? 1.0e-4 : 0.8);
        java.util.Arrays.fill(valid, true);
        if (faintRidge) {
            for (int y = 4; y <= 6; y++) {
                for (int x = 0; x < size; x++) {
                    values[y * size + x] = 8.0e-4;
                }
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(size, size, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "hot",
                        EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        MetricRegion region = MetricRegion.rectangle(0, 0, 11, 11);
        return new EvidenceSnapshot("evidence", frame,
                new RasterMetricTransform("positive-y",
                        RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                        new MetricPoint(0, 0), 1, 0, 0, 1, 1),
                EvidenceResolution.nativeSource(1, 1),
                MetricRegion.rectangle(0.5, 0.5, 10.5, 10.5), region,
                Map.of("hot", field), "source");
    }
}

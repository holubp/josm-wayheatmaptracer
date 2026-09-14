package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterResamplingProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

/** Existing-preview adaptation checks for detached modern hypotheses. */
class V022ModernCandidateAdapterTest {
    @Test
    void projectionOffsetsAndIncompleteEvidenceRemainExplicit() {
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
        assertTrue(candidate.evidence().hasSignal());
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

    private static EvidenceSnapshot evidence() {
        GeographicPoint origin = new GeographicPoint(42, 19);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
                new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        int size = 12;
        double[] values = new double[size * size];
        boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(values, 0.8);
        java.util.Arrays.fill(valid, true);
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

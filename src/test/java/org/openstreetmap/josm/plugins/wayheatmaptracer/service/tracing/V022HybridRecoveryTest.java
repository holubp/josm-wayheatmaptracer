package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

/** T033-T038: independent A proposals and unguided B orchestration. */
class V022HybridRecoveryTest {
    @Test
    void T033_unguidedBIsRetainedAlongsideA() {
        Fixture fixture = fixture();
        TraceHypothesisSet result = engine(route(TrackerMode.CORRIDOR_AWARE, "a", 4.0),
                route(TrackerMode.PROBABILISTIC, "b", 2.0)).trace(fixture.request(),
                fixture.evidence(), fixture.network());

        assertEquals(2, result.hypotheses().size());
        assertTrue(result.hypotheses().stream().anyMatch(route -> route.id().startsWith("hybrid-b-")));
        assertEquals(TraceHypothesisSet.Status.AMBIGUOUS, result.status());
    }

    @Test
    void T034_aProposalIsMarkedStructuralAndDoesNotAddPosteriorCertainty() {
        Fixture fixture = fixture();
        TraceHypothesisSet result = engine(route(TrackerMode.CORRIDOR_AWARE, "a", 1.0),
                route(TrackerMode.PROBABILISTIC, "b", 2.0)).trace(fixture.request(),
                fixture.evidence(), fixture.network());

        TraceHypothesis proposal = result.hypotheses().get(0);
        assertEquals(1.0, proposal.diagnostics().get("structuralPriorOnly"));
        assertEquals(0.0, proposal.posteriorProbability());
        assertEquals(0.7, proposal.diagnostics().get("sourcePosteriorProbability"));
    }

    @Test
    void T035_falseApexCannotCreateAnUnobservedGuidedRoute() {
        Fixture fixture = fixture();
        TraceHypothesisSet result = engine(route(TrackerMode.CORRIDOR_AWARE, "false-apex", 0.1),
                noRoute(TrackerMode.PROBABILISTIC)).trace(fixture.request(), fixture.evidence(), fixture.network());

        assertEquals(1, result.hypotheses().size());
        assertFalse(result.hypotheses().stream().anyMatch(route -> route.id().contains("guided")));
    }

    @Test
    void T036_bWorksWhenAIsEmpty() {
        Fixture fixture = fixture();
        TraceHypothesisSet result = engine(noRoute(TrackerMode.CORRIDOR_AWARE),
                route(TrackerMode.PROBABILISTIC, "b", 2.0)).trace(fixture.request(),
                fixture.evidence(), fixture.network());

        assertEquals(TraceHypothesisSet.Status.COMPLETE, result.status());
        assertEquals("hybrid-b-b", result.hypotheses().get(0).id());
    }

    @Test
    void T037_sameImageEvidenceIsNotDoubleCounted() {
        Fixture fixture = fixture();
        TraceHypothesisSet result = engine(route(TrackerMode.CORRIDOR_AWARE, "a", 1.0),
                route(TrackerMode.PROBABILISTIC, "b", 2.0)).trace(fixture.request(),
                fixture.evidence(), fixture.network());

        assertEquals(1, fixture.evidence().independentEvidenceGroups().size());
        assertTrue(result.hypotheses().stream().allMatch(route ->
                route.diagnostics().get("independentImageObservationCount") == 1.0));
    }

    @Test
    void T038_strongerLaterBEvidenceRemainsAvailableToCommonRanking() {
        Fixture fixture = fixture();
        TraceHypothesisSet result = engine(route(TrackerMode.CORRIDOR_AWARE, "a", 0.1),
                route(TrackerMode.PROBABILISTIC, "strong-b", 8.0)).trace(fixture.request(),
                fixture.evidence(), fixture.network());

        assertTrue(result.hypotheses().stream().anyMatch(route ->
                route.branchSignature().equals("b:strong-b")));
        assertTrue(result.explanation().contains("common final ranking"));
    }

    private static HybridTraceEngine engine(TraceHypothesisSet a, TraceHypothesisSet b) {
        return new HybridTraceEngine(stub(TrackerMode.CORRIDOR_AWARE, a),
                stub(TrackerMode.PROBABILISTIC, b));
    }

    private static TraceEngine stub(TrackerMode expected, TraceHypothesisSet result) {
        return (request, evidence, network, cancellation) -> {
            assertEquals(expected, request.engine());
            return result;
        };
    }

    private static TraceHypothesisSet route(TrackerMode mode, String id, double objective) {
        TraceHypothesis hypothesis = new TraceHypothesis(id, id,
                List.of(new MetricPoint(0, 0), new MetricPoint(4, 0)),
                List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED), objective, 0.7, Map.of());
        return new TraceHypothesisSet(mode, List.of(hypothesis), TraceHypothesisSet.Status.COMPLETE,
                false, 2, 1, "complete");
    }

    private static TraceHypothesisSet noRoute(TrackerMode mode) {
        return new TraceHypothesisSet(mode, List.of(), TraceHypothesisSet.Status.NO_ROUTE,
                false, 0, 0, "no route");
    }

    private static Fixture fixture() {
        GeographicPoint origin = new GeographicPoint(42, 19);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
                new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey last = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 3);
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        values.put(first, new DetachedNode(first, origin, Map.of(), false, false));
        values.put(last, new DetachedNode(last, frame.toGeographic(new MetricPoint(4, 0)),
                Map.of(), false, false));
        values.put(way, new DetachedWay(way, List.of(first, last), Map.of("highway", "path"),
                false, false));
        MetricRegion region = MetricRegion.rectangle(-5, -5, 6, 6);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
                "hybrid-test-v1", values.keySet(), Set.of(way), Set.of(), Set.of(first, last), Set.of(),
                Map.of(way, List.of(new OccurrenceRange(0, 1))), List.of(), region, region,
                false, true, true, true);
        NetworkSnapshot network = new NetworkSnapshot("network", SnapshotRole.CAPTURED_BEFORE,
                "dataset", 1, closure, values,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures
                    .closedWorldReferrerWatches(values));
        int size = 12;
        double[] intensity = new double[size * size];
        boolean[] valid = new boolean[intensity.length];
        java.util.Arrays.fill(intensity, 0.8);
        java.util.Arrays.fill(valid, true);
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        ScalarEvidenceField field = new ScalarEvidenceField(size, size, intensity, valid, lineage);
        EvidenceResolution resolution = EvidenceResolution.nativeSource(1, 1);
        EvidenceSnapshot evidence = new EvidenceSnapshot("evidence", frame,
                new RasterMetricTransform("hybrid-test-raster",
                        RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                        new MetricPoint(-5, -5), 1, 0, 0, 1, 1), resolution,
                MetricRegion.rectangle(-4, -4, 5, 5), region, Map.of("synthetic", field), "source");
        ProfileChainage chainage = new ProfileChainage(List.of(0.0, 4.0), 4.0);
        TraceRequest request = new TraceRequest(way, new OccurrenceRange(0, 1), TrackerMode.HYBRID,
                AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(4), TraceBudgets.defaults(),
                evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
                "settings", "parameters", "sampler", 4, chainage, resolution);
        return new Fixture(request, evidence, network);
    }

    private record Fixture(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network) { }
}

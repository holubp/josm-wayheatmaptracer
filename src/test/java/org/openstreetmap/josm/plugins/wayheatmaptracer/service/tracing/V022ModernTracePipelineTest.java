package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image.DirectionalImageTraceEngine;

/** Common detached post-processing regressions for all modern tracing engines. */
class V022ModernTracePipelineTest {
    @Test
    void ranksCompleteCenterSupportedRouteAheadOfOffCorridorRoute() {
        Fixture fixture = fixture(TrackerMode.CORRIDOR_AWARE);
        TraceEngine a = (request, evidence, network, cancellation) -> routes(request.engine(),
                route("off", 3.0), route("center", 0.0));

        ModernTracePipeline.Result result = new ModernTracePipeline(a).run(fixture.request,
                fixture.evidence, fixture.network,
                options(GeometryCleanupConfig.disabled()), CancellationProbe.NONE);

        assertEquals(List.of("center", "off"), result.routes().stream()
                .map(route -> route.hypothesis().id()).toList());
        assertEquals(TraceHypothesisSet.Status.AMBIGUOUS, result.inference().status());
        assertTrue(result.routes().get(0).quality().directlySupportedLengthMeters() >
                result.routes().get(1).quality().directlySupportedLengthMeters());
    }

    @Test
    void reduceOnlyProducesASeparateFinalGeometryWithoutChangingInference() {
        Fixture fixture = fixture(TrackerMode.CORRIDOR_AWARE);
        TraceHypothesis dense = new TraceHypothesis("dense", "center",
                List.of(new MetricPoint(0, 0), new MetricPoint(2, 0), new MetricPoint(4, 0)),
                List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED), 1.0, 0.8, Map.of());
        TraceEngine a = (request, evidence, network, cancellation) -> routes(request.engine(), dense);
        GeometryCleanupConfig reduce = GeometryCleanupConfig.disabled()
                .withMode(GeometryCleanupMode.REDUCE_POINTS_ONLY);

        ModernTracePipeline.Result result = new ModernTracePipeline(a).run(fixture.request,
                fixture.evidence, fixture.network, options(reduce), CancellationProbe.NONE);

        assertEquals(3, result.inference().hypotheses().get(0).points().size());
        assertEquals(2, result.routes().get(0).hypothesis().points().size());
        assertEquals(ImageSupportedLocalCleanup.Status.CLEANED,
                result.routes().get(0).cleanupStatus());
        assertTrue(result.routes().get(0).geometryChanged());
    }

    @Test
    void legacyModeCannotBypassItsCompatibilityPipeline() {
        Fixture fixture = fixture(TrackerMode.LEGACY_V02);
        TraceEngine unused = (request, evidence, network, cancellation) -> routes(request.engine(), route("x", 0));

        assertThrows(IllegalArgumentException.class, () -> new ModernTracePipeline(unused).run(
                fixture.request, fixture.evidence, fixture.network,
                options(GeometryCleanupConfig.disabled()), CancellationProbe.NONE));
    }

    @Test
    void noRouteRemainsTruthfullyEmpty() {
        Fixture fixture = fixture(TrackerMode.CORRIDOR_AWARE);
        TraceEngine a = (request, evidence, network, cancellation) -> new TraceHypothesisSet(
                request.engine(), List.of(), TraceHypothesisSet.Status.NO_ROUTE,
                false, 0, 0, "no route");

        ModernTracePipeline.Result result = new ModernTracePipeline(a).run(fixture.request,
                fixture.evidence, fixture.network,
                options(GeometryCleanupConfig.disabled()), CancellationProbe.NONE);

        assertTrue(result.routes().isEmpty());
        assertFalse(result.inference().alternativesTruncated());
    }

    @Test
    void directionalEngineCarriesFaintRelativeSupportIntoCandidateAdaptation() {
        Fixture fixture = fixture(TrackerMode.DIRECTIONAL_IMAGE, 1.0e-4, 8.0e-4);
        TraceHypothesisSet inference = new DirectionalImageTraceEngine("native").trace(fixture.request,
                fixture.evidence, fixture.network, CancellationProbe.NONE);

        assertFalse(inference.hypotheses().isEmpty());
        TraceHypothesis route = inference.hypotheses().get(0);
        assertEquals(ObservationOwnership.FIXED_TOPOLOGY_ONLY, route.support().get(0));
        assertEquals(ObservationOwnership.FIXED_TOPOLOGY_ONLY,
                route.support().get(route.support().size() - 1));
        assertTrue(route.support().subList(1, route.support().size() - 1).stream()
                .anyMatch(ownership -> ownership == ObservationOwnership.DIRECT_TWO_SIDED));

        var candidate = new ModernCandidateAdapter().adapt(inference, fixture.evidence, "native",
                List.of(new MetricPoint(0, 0), new MetricPoint(4, 0)), point -> {
                    MetricPoint metric = fixture.evidence.coordinateFrame().toMetric(point);
                    return new EastNorth(metric.xMeters(), metric.yMeters());
                }).get(0);
        assertTrue(candidate.evidence().supportedProfiles() > 0);
        assertTrue(candidate.evidence().hasSignal());
    }

    private static ModernTracePipeline.Options options(GeometryCleanupConfig cleanup) {
        return new ModernTracePipeline.Options("native", cleanup, Set.of(), "native", 0);
    }

    private static TraceHypothesis route(String id, double y) {
        return new TraceHypothesis(id, id,
                List.of(new MetricPoint(0, y), new MetricPoint(4, y)),
                List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED), 1.0, 0.4, Map.of());
    }

    private static TraceHypothesisSet routes(TrackerMode mode, TraceHypothesis... routes) {
        return new TraceHypothesisSet(mode, List.of(routes),
                routes.length == 1 ? TraceHypothesisSet.Status.COMPLETE
                    : TraceHypothesisSet.Status.AMBIGUOUS,
                false, Math.max(1, routes.length), 1, "fixture routes");
    }

    private static Fixture fixture(TrackerMode mode) {
        return fixture(mode, 0.1, 1.0);
    }

    private static Fixture fixture(TrackerMode mode, double background, double ridge) {
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
        MetricRegion region = MetricRegion.rectangle(-5, -5, 9, 9);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
                "pipeline-test-v1", values.keySet(), Set.of(way), Set.of(), Set.of(first, last), Set.of(),
                Map.of(way, List.of(new OccurrenceRange(0, 1))), List.of(), region, region,
                false, true, true, true);
        NetworkSnapshot network = new NetworkSnapshot("network", SnapshotRole.CAPTURED_BEFORE,
                "dataset", 1, closure, values,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures
                    .closedWorldReferrerWatches(values));
        int size = 15;
        double[] intensity = new double[size * size];
        boolean[] valid = new boolean[intensity.length];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                intensity[y * size + x] = y >= 4 && y <= 6 ? ridge : background;
                valid[y * size + x] = true;
            }
        }
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        ScalarEvidenceField field = new ScalarEvidenceField(size, size, intensity, valid, lineage);
        EvidenceResolution resolution = EvidenceResolution.nativeSource(1, 1);
        EvidenceSnapshot evidence = new EvidenceSnapshot("evidence", frame,
                new RasterMetricTransform("pipeline-test-raster",
                        RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                        new MetricPoint(-5, -5), 1, 0, 0, 1, 1), resolution,
                MetricRegion.rectangle(-4, -4, 8, 8), region, Map.of("native", field), "source");
        TraceRequest request = new TraceRequest(way, new OccurrenceRange(0, 1), mode,
                AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(4), TraceBudgets.defaults(),
                evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
                "settings", "parameters", "sampler", 4,
                new ProfileChainage(List.of(0.0, 4.0), 4.0), resolution);
        return new Fixture(request, evidence, network);
    }

    private record Fixture(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network) {
    }
}

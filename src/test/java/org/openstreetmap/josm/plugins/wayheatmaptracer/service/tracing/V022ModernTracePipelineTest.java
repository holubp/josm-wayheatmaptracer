package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
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
    void cancellationContinuesThroughCommonCleanupAndCannotReturnSuccess() {
        Fixture fixture = fixture(TrackerMode.CORRIDOR_AWARE);
        TraceEngine engine = (request, evidence, network, cancellation) ->
                routes(request.engine(), route("cancel-cleanup", 0.7));
        GeometryCleanupConfig enabled = GeometryCleanupConfig.disabled()
                .withMode(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE);
        AtomicInteger checkpoints = new AtomicInteger();

        assertThrows(CancellationException.class, () -> new ModernTracePipeline(engine).run(
                fixture.request, fixture.evidence, fixture.network, options(enabled),
                () -> checkpoints.incrementAndGet() >= 2));
        assertEquals(2, checkpoints.get());
    }

    @Test
    void ranksCompleteCenterSupportedRouteAheadOfOffCorridorRoute() {
        Fixture fixture = fixture(TrackerMode.CORRIDOR_AWARE);
        TraceEngine a = (request, evidence, network, cancellation) -> routes(request.engine(),
                route("off", 2.0), route("center", 0.0));

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
    void interiorProtectedOccurrenceSurvivesReductionWithTopologyOnlyOwnership() {
        Fixture fixture = fixture(TrackerMode.CORRIDOR_AWARE, 0.1, 1.0, true);
        TraceHypothesis dense = new TraceHypothesis("protected", "center",
                List.of(new MetricPoint(0, 0), new MetricPoint(1, 0), new MetricPoint(2, 0),
                        new MetricPoint(3, 0), new MetricPoint(4, 0)),
                List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED), 1.0, 0.8, Map.of());
        TraceEngine a = (request, evidence, network, cancellation) -> routes(request.engine(), dense);
        GeometryCleanupConfig reduce = GeometryCleanupConfig.disabled()
                .withMode(GeometryCleanupMode.REDUCE_POINTS_ONLY);

        ModernTracePipeline.Result result = new ModernTracePipeline(a).run(fixture.request,
                fixture.evidence, fixture.network, options(reduce), CancellationProbe.NONE);

        ModernTracePipeline.Route finalRoute = result.routes().get(0);
        ExistingWayNodeOccurrence protectedId = new ExistingWayNodeOccurrence(
                way(3), node(4), 1);
        int protectedIndex = finalRoute.pointIds().indexOf(protectedId);
        assertTrue(protectedIndex >= 0);
        assertEquals(finalRoute.assignments().get(protectedId),
                finalRoute.hypothesis().points().get(protectedIndex));
        assertEquals(ObservationOwnership.FIXED_TOPOLOGY_ONLY,
                finalRoute.hypothesis().support().get(protectedIndex));
        assertEquals(dense, finalRoute.rawHypothesis());
        assertEquals(3, finalRoute.existingAssignments().size());
        assertThrows(UnsupportedOperationException.class,
                () -> finalRoute.pointIds().add(protectedId));
        assertThrows(UnsupportedOperationException.class,
                () -> finalRoute.assignments().put(protectedId, new MetricPoint(9, 9)));
    }

    @Test
    void equalCountFinalGeometryResamplesUnsupportedPointsInsteadOfRetainingDirectOwnership() {
        Fixture fixture = fixture(TrackerMode.CORRIDOR_AWARE);
        TraceHypothesis stale = new TraceHypothesis("stale", "remote-branch",
                List.of(new MetricPoint(0, 0), new MetricPoint(2, 2), new MetricPoint(4, 0)),
                List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED), 1.0, 0.8, Map.of());
        TraceEngine a = (request, evidence, network, cancellation) -> routes(request.engine(), stale);

        ModernTracePipeline.Result result = new ModernTracePipeline(a).run(fixture.request,
                fixture.evidence, fixture.network, options(GeometryCleanupConfig.disabled()),
                CancellationProbe.NONE);

        ModernTracePipeline.Route finalRoute = result.routes().get(0);
        assertTrue(finalRoute.hypothesis().support().stream()
                .noneMatch(ownership -> ownership == ObservationOwnership.DIRECT_TWO_SIDED));
        GeneratedCandidatePoint movedSample = new GeneratedCandidatePoint("stale", 1);
        assertEquals(ObservationOwnership.DIRECT_TWO_SIDED,
                finalRoute.sourceOwnership().get(movedSample));
    }

    @Test
    void reducedGeometryResamplesInsteadOfBorrowingNearestOldBranchOwnership() {
        Fixture fixture = fixture(TrackerMode.CORRIDOR_AWARE, 0.1, 1.0, false, true);
        TraceHypothesis stale = new TraceHypothesis("reduced", "center",
                List.of(new MetricPoint(0, 0), new MetricPoint(1, 0),
                        new MetricPoint(2, 0.5), new MetricPoint(3, 0), new MetricPoint(4, 0)),
                List.of(ObservationOwnership.NO_SIGNAL_VALID_RASTER,
                        ObservationOwnership.NO_SIGNAL_VALID_RASTER,
                        ObservationOwnership.NO_SIGNAL_VALID_RASTER,
                        ObservationOwnership.NO_SIGNAL_VALID_RASTER,
                        ObservationOwnership.NO_SIGNAL_VALID_RASTER), 1.0, 0.8, Map.of());
        TraceEngine a = (request, evidence, network, cancellation) -> routes(request.engine(), stale);
        GeometryCleanupConfig reduce = GeometryCleanupConfig.disabled()
                .withMode(GeometryCleanupMode.REDUCE_POINTS_ONLY);

        ModernTracePipeline.Result result = new ModernTracePipeline(a).run(fixture.request,
                fixture.evidence, fixture.network, options(reduce), CancellationProbe.NONE);

        assertTrue(result.routes().get(0).hypothesis().points().size() < stale.points().size());
        assertTrue(result.routes().get(0).hypothesis().support().stream()
                .anyMatch(ownership -> ownership == ObservationOwnership.DIRECT_TWO_SIDED));
        ExistingWayNodeOccurrence movableId = new ExistingWayNodeOccurrence(way(3), node(-4), 1);
        assertTrue(result.routes().get(0).existingAssignments().get(movableId)
                .distanceTo(new MetricPoint(2, 0.5)) < 1.0e-6);
        assertEquals(3, result.routes().get(0).existingAssignments().size());
        assertEquals(ObservationOwnership.NO_SIGNAL_VALID_RASTER,
                result.routes().get(0).sourceOwnership().get(movableId));
        assertEquals(stale, result.routes().get(0).rawHypothesis());
        assertEquals("center", result.routes().get(0).hypothesis().branchSignature());
    }

    @Test
    void repeatedSelectedNodeIdentityFailsClosedEvenOutsideTheSelectedRange() {
        Fixture fixture = fixture(TrackerMode.CORRIDOR_AWARE, 0.1, 1.0, true);
        PrimitiveKey first = node(1);
        PrimitiveKey middle = node(4);
        PrimitiveKey selectedWay = way(3);
        Map<PrimitiveKey, DetachedPrimitive> repeatedValues = new LinkedHashMap<>(
                fixture.network.primitives());
        repeatedValues.put(selectedWay, new DetachedWay(selectedWay,
                List.of(first, middle, first), Map.of("highway", "path"), false, false));
        MetricRegion region = fixture.network.closure().editRegion();
        ClosureDescriptor repeatedClosure = new ClosureDescriptor(
                ClosureDescriptor.Scope.SELECTION_SAFETY, "pipeline-test-v1",
                repeatedValues.keySet(), Set.of(selectedWay), Set.of(),
                Set.of(first, middle, node(2)), Set.of(),
                Map.of(selectedWay, List.of(new OccurrenceRange(0, 1))), List.of(),
                region, region, false, true, true, true);
        NetworkSnapshot repeatedNetwork = new NetworkSnapshot("repeated",
                SnapshotRole.CAPTURED_BEFORE, "dataset", 1, repeatedClosure, repeatedValues,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures
                    .closedWorldReferrerWatches(repeatedValues));
        TraceRequest repeatedRequest = new TraceRequest(selectedWay, new OccurrenceRange(0, 1),
                TrackerMode.CORRIDOR_AWARE, AlignmentMode.PRECISE_SHAPE,
                RecoveryPermissions.disabled(4), TraceBudgets.defaults(),
                fixture.evidence.snapshotId(), fixture.evidence.canonicalHash(),
                repeatedNetwork.snapshotId(), repeatedNetwork.canonicalHash(),
                "settings", "parameters", "sampler", 4,
                new ProfileChainage(List.of(0.0, 4.0), 4.0),
                fixture.evidence.resolution());
        TraceEngine a = (request, evidence, network, cancellation) -> routes(request.engine(),
                new TraceHypothesis("repeated-route", "center",
                        List.of(new MetricPoint(0, 0), new MetricPoint(2, 0)),
                        List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                                ObservationOwnership.DIRECT_TWO_SIDED),
                        1.0, 0.8, Map.of()));

        assertThrows(IllegalArgumentException.class, () -> new ModernTracePipeline(a).run(
                repeatedRequest, fixture.evidence, repeatedNetwork,
                options(GeometryCleanupConfig.disabled()), CancellationProbe.NONE));
    }

    @Test
    void ambiguousCandidateOccurrenceMappingFreezesOnlyThatExistingAssignment() {
        Fixture fixture = fixture(TrackerMode.CORRIDOR_AWARE, 0.1, 1.0, false, true);
        TraceHypothesis repeatedPosition = new TraceHypothesis("ambiguous-position", "center",
                List.of(new MetricPoint(0, 0), new MetricPoint(1, 0),
                        new MetricPoint(2, 0.5), new MetricPoint(2, 0.5),
                        new MetricPoint(3, 0), new MetricPoint(4, 0)),
                List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED), 1.0, 0.8, Map.of());
        TraceEngine a = (request, evidence, network, cancellation) ->
                routes(request.engine(), repeatedPosition);

        ModernTracePipeline.Route route = new ModernTracePipeline(a).run(fixture.request,
                fixture.evidence, fixture.network, options(GeometryCleanupConfig.disabled()),
                CancellationProbe.NONE).routes().get(0);

        ExistingWayNodeOccurrence middle = new ExistingWayNodeOccurrence(way(3), node(-4), 1);
        MetricPoint captured = fixture.evidence.coordinateFrame().toMetric(
                ((DetachedNode) fixture.network.primitives().get(node(-4))).coordinate());
        assertEquals(captured, route.existingAssignments().get(middle));
        assertTrue(route.hypothesis().points().contains(new MetricPoint(1, 0)));
        assertTrue(route.hypothesis().points().contains(new MetricPoint(3, 0)));
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
        return new ModernTracePipeline.Options("native", cleanup, "native", 0);
    }

    private static TraceHypothesis route(String id, double y) {
        return new TraceHypothesis(id, id,
                List.of(new MetricPoint(0, 0), new MetricPoint(2, y), new MetricPoint(4, 0)),
                List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                        ObservationOwnership.DIRECT_TWO_SIDED,
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
        return fixture(mode, background, ridge, false, false);
    }

    private static Fixture fixture(TrackerMode mode, double background, double ridge,
            boolean interiorProtected) {
        return fixture(mode, background, ridge, interiorProtected, false);
    }

    private static Fixture fixture(TrackerMode mode, double background, double ridge,
            boolean interiorProtected, boolean interiorMovable) {
        GeographicPoint origin = new GeographicPoint(42, 19);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
                new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey last = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 3);
        PrimitiveKey middle = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                interiorMovable ? -4 : 4);
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        values.put(first, new DetachedNode(first, origin, Map.of(), false, false));
        if (interiorProtected || interiorMovable) {
            values.put(middle, new DetachedNode(middle,
                    frame.toGeographic(new MetricPoint(2, 0)), interiorProtected ? Map.of("barrier", "gate") : Map.of(), false, false));
        }
        values.put(last, new DetachedNode(last, frame.toGeographic(new MetricPoint(4, 0)),
                Map.of(), false, false));
        List<PrimitiveKey> wayNodes = interiorProtected || interiorMovable
                ? List.of(first, middle, last) : List.of(first, last);
        values.put(way, new DetachedWay(way, wayNodes, Map.of("highway", "path"), false, false));
        MetricRegion region = MetricRegion.rectangle(-5, -5, 9, 9);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
                "pipeline-test-v1", values.keySet(),
                interiorMovable ? Set.of(way, middle) : Set.of(way),
                interiorMovable ? Set.of(middle) : Set.of(),
                interiorProtected ? Set.of(first, middle, last) : Set.of(first, last), Set.of(),
                Map.of(way, List.of(new OccurrenceRange(0,
                        interiorProtected || interiorMovable ? 2 : 1))),
                List.of(), region, region,
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
        OccurrenceRange selectedRange = new OccurrenceRange(0,
                interiorProtected || interiorMovable ? 2 : 1);
        TraceRequest request = new TraceRequest(way, selectedRange, mode,
                AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(4), TraceBudgets.defaults(),
                evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
                "settings", "parameters", "sampler", 4,
                new ProfileChainage(interiorProtected || interiorMovable
                        ? List.of(0.0, 2.0, 4.0) : List.of(0.0, 4.0), 4.0), resolution);
        return new Fixture(request, evidence, network);
    }

    private static PrimitiveKey node(long id) {
        return PrimitiveKey.existing(PrimitiveKey.Type.NODE, id);
    }

    private static PrimitiveKey way(long id) {
        return PrimitiveKey.existing(PrimitiveKey.Type.WAY, id);
    }


    @Test
    void independentProbeRemovalOnlyIdentityMayNotMoveWhenRetained() {
        Fixture base = fixture(TrackerMode.CORRIDOR_AWARE, 0.1, 1.0, false, true);
        var old = base.network.closure();
        var closure = new ClosureDescriptor(old.scope(), old.queryVersion(), old.primitiveKeys(),
                old.editableExistingKeys(), Set.of(), old.protectedExistingNodeKeys(), Set.of(node(-4)),
                old.editableWayOccurrences(), old.externalPorts(), old.collisionEnvelope(), old.editRegion(),
                old.mayCreateNodes(), true, true, true);
        Fixture test = replaceNetwork(base, closure, base.network.primitives(), 2);
        TraceHypothesis proposed = route("removal-only", 0.5);
        TraceEngine a = (request, evidence, network, cancellation) -> routes(request.engine(), proposed);
        ModernTracePipeline.Route result = new ModernTracePipeline(a).run(test.request, test.evidence,
                test.network, options(GeometryCleanupConfig.disabled()), CancellationProbe.NONE).routes().get(0);
        var id = new ExistingWayNodeOccurrence(way(3), node(-4), 1);
        MetricPoint captured = test.evidence.coordinateFrame().toMetric(
                ((DetachedNode) test.network.primitives().get(node(-4))).coordinate());
        assertEquals(captured, result.existingAssignments().get(id),
                "Retained removal-only identity has no permission to change coordinates");
    }

    @Test
    void independentProbeDistinctCoincidentOccurrencesDoNotBecomeNonadjacentTouch() {
        Fixture base = fixture(TrackerMode.CORRIDOR_AWARE, 0.1, 1.0, true);
        var values = new LinkedHashMap<>(base.network.primitives());
        var middle = (DetachedNode) values.get(node(4));
        values.put(node(5), new DetachedNode(node(5), middle.coordinate(), Map.of(), false, false));
        values.put(way(3), new DetachedWay(way(3), List.of(node(1), node(4), node(5), node(2)),
                Map.of("highway", "path"), false, false));
        var old = base.network.closure();
        var closure = new ClosureDescriptor(old.scope(), old.queryVersion(), values.keySet(),
                Set.of(way(3)), Set.of(), Set.of(node(1), node(2), node(4), node(5)), Set.of(),
                Map.of(way(3), List.of(new OccurrenceRange(0, 3))), List.of(), old.collisionEnvelope(),
                old.editRegion(), false, true, true, true);
        Fixture test = replaceNetwork(base, closure, values, 3);
        TraceEngine a = (request, evidence, network, cancellation) -> routes(request.engine(), route("coincident", 0));
        ModernTracePipeline.Route result = new ModernTracePipeline(a).run(test.request, test.evidence,
                test.network, options(GeometryCleanupConfig.disabled()), CancellationProbe.NONE).routes().get(0);
        assertEquals(4, result.existingAssignments().size());
        assertFalse(result.quality().has(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.
                FinalGeometryEvaluator.FindingCode.NONADJACENT_TOUCH),
                "Distinct coincident adjacent occurrences are a valid zero-length connector: " + result.quality());
    }

    private static Fixture replaceNetwork(Fixture base, ClosureDescriptor closure,
            Map<PrimitiveKey, DetachedPrimitive> values, int lastIndex) {
        var network = new NetworkSnapshot("probe-network", SnapshotRole.CAPTURED_BEFORE, "dataset", 1,
                closure, values, org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures
                    .closedWorldReferrerWatches(values));
        var original = base.request;
        var request = new TraceRequest(way(3), new OccurrenceRange(0, lastIndex), original.engine(),
                AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(4), TraceBudgets.defaults(),
                base.evidence.snapshotId(), base.evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
                "settings", "parameters", "sampler", 4, original.profileChainage(), base.evidence.resolution());
        return new Fixture(request, base.evidence, network);
    }


    @Test
    void independentProbeRemovalOnlyIdentityCanBeSafelyRemoved() {
        Fixture base = fixture(TrackerMode.CORRIDOR_AWARE, 0.1, 1.0, false, true);
        var old = base.network.closure();
        var closure = new ClosureDescriptor(old.scope(), old.queryVersion(), old.primitiveKeys(),
                old.editableExistingKeys(), Set.of(), old.protectedExistingNodeKeys(), Set.of(node(-4)),
                old.editableWayOccurrences(), old.externalPorts(), old.collisionEnvelope(), old.editRegion(),
                old.mayCreateNodes(), true, true, true);
        Fixture test = replaceNetwork(base, closure, base.network.primitives(), 2);
        TraceEngine a = (request, evidence, network, cancellation) -> routes(request.engine(), route("remove", 0));
        ModernTracePipeline.Route result = new ModernTracePipeline(a).run(test.request, test.evidence,
                test.network, options(GeometryCleanupConfig.disabled().withMode(GeometryCleanupMode.REDUCE_POINTS_ONLY)),
                CancellationProbe.NONE).routes().get(0);
        assertEquals(2, result.hypothesis().points().size());
        assertFalse(result.existingAssignments().containsKey(new ExistingWayNodeOccurrence(way(3), node(-4), 1)));
    }

    @Test
    void independentProbeCoreCensoredFinalSupportIsNotDirectAmbiguous() {
        Fixture base = fixture(TrackerMode.CORRIDOR_AWARE);
        var e = base.evidence;
        var clipped = new EvidenceSnapshot("clipped-probe", e.coordinateFrame(), e.transform(), e.resolution(),
                MetricRegion.rectangle(-4, 0, 8, 8), e.evidenceRegion(), e.fields(), e.sourceIdentity());
        var r = base.request;
        var request = new TraceRequest(r.selectedWayKey(), r.selectedRange(), r.engine(),
                AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(4), TraceBudgets.defaults(),
                clipped.snapshotId(), clipped.canonicalHash(), base.network.snapshotId(), base.network.canonicalHash(),
                "settings", "parameters", "sampler", 4, r.profileChainage(), clipped.resolution());
        TraceEngine a = (req, evidence, network, cancellation) -> routes(req.engine(), route("core-cut", 0));
        ModernTracePipeline.Route result = new ModernTracePipeline(a).run(request, clipped, base.network,
                options(GeometryCleanupConfig.disabled()), CancellationProbe.NONE).routes().get(0);
        assertEquals(ObservationOwnership.CORE_CENSORED, result.hypothesis().support().get(1));
    }

    private record Fixture(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network) {
    }
}

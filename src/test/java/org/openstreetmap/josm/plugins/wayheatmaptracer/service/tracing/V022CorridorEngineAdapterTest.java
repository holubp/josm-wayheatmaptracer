package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorTraceInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorAwareTracker;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedScalarProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.EndpointConstraint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.JunctionContext;

/** Production-boundary regressions for corridor-aware Engine A. */
class V022CorridorEngineAdapterTest {
    private static final String FIELD = "native";

    @Test
    void productionAdapterMatchesDirectSamplerAndTrackerOnFrozenIrregularAnchors() {
        Fixture fixture = fixture(true);
        TraceHypothesisSet actual = new CorridorEngineAdapter(FIELD).trace(
            fixture.request(), fixture.evidence(), fixture.network());
        var input = fixture.request().corridorInput().orElseThrow();
        var profiles = new DetachedScalarProfileSampler().sample(fixture.evidence(), FIELD,
            input.profileLocations(), fixture.request().permissions().ordinaryRadiusMeters(),
            input.lateralStepMeters(), CancellationProbe.NONE);
        double sourcePixels = fixture.evidence().resolution().effectivePitchMeters()
            / fixture.evidence().resolution().outputRasterPitchMeters();
        var fixedPorts = new JunctionContext(List.of(
            new EndpointConstraint(0, 1, true, false, 0.0, 0.0, 0),
            new EndpointConstraint(input.profileLocations().size() - 1, 2,
                true, false, 0.0, 0.0, 0)));
        var reference = new CorridorAwareTracker().trackDetailed(profiles, sourcePixels,
            fixedPorts, FIELD, GeometryCleanupConfig.disabled(),
            fixture.evidence().resolution().outputRasterPitchMeters());
        var expected = reference.candidates().stream().filter(candidate -> candidate.evidence().hasSignal()).toList();

        assertFalse(expected.isEmpty());
        assertEquals(expected.stream().map(candidate -> candidate.id()).toList(),
            actual.hypotheses().stream().map(TraceHypothesis::branchSignature).toList());
        assertEquals(expected.get(0).screenPoints().stream().map(point -> fixture.evidence().transform()
            .pixelCenterToMetric(point.x, point.y)).toList(), actual.hypotheses().get(0).points());
        assertEquals(50.0, actual.hypotheses().get(0).points().get(0).yMeters(), 1.0e-9);
        assertEquals(50.0, actual.hypotheses().get(0).points()
            .get(actual.hypotheses().get(0).points().size() - 1).yMeters(), 1.0e-9);
        assertTrue(actual.hypotheses().get(0).points().subList(1,
            actual.hypotheses().get(0).points().size() - 1).stream()
            .allMatch(point -> Math.abs(point.yMeters() - 53.0) < 1.0));
        assertEquals(ObservationOwnership.FIXED_TOPOLOGY_ONLY,
            actual.hypotheses().get(0).support().get(0));
        assertEquals(ObservationOwnership.FIXED_TOPOLOGY_ONLY, actual.hypotheses().get(0).support()
            .get(actual.hypotheses().get(0).support().size() - 1));
        assertTrue(actual.hypotheses().get(0).support().subList(1,
            actual.hypotheses().get(0).support().size() - 1).stream()
            .allMatch(value -> value == ObservationOwnership.DIRECT_TWO_SIDED));
        assertTrue(actual.hypotheses().get(0).posteriorProbability().isEmpty());
        assertTrue(actual.evaluatedStates() > 0);
        assertTrue(actual.evaluatedTransitions() > 0);
    }

    @Test
    void missingInputChangedSnapshotAndChangedFrozenEndpointFailClosed() {
        Fixture fixture = fixture(true);
        TraceRequest missing = withoutInput(fixture.request());
        assertThrows(IllegalArgumentException.class, () -> new CorridorEngineAdapter(FIELD).trace(
            missing, fixture.evidence(), fixture.network()));
        EvidenceSnapshot changed = new EvidenceSnapshot("changed", fixture.evidence().coordinateFrame(),
            fixture.evidence().transform(), fixture.evidence().resolution(), fixture.evidence().decisionRegion(),
            fixture.evidence().evidenceRegion(), fixture.evidence().fields(), fixture.evidence().sourceIdentity());
        assertThrows(IllegalArgumentException.class, () -> new CorridorEngineAdapter(FIELD).trace(
            fixture.request(), changed, fixture.network()));
        var locations = fixture.request().corridorInput().orElseThrow().profileLocations();
        var shifted = new java.util.ArrayList<>(locations);
        shifted.set(shifted.size() - 1, DetachedProfileSamplingLocation.at(
            fixture.evidence().coordinateFrame().toGeographic(new MetricPoint(139.0, 50.0)),
            fixture.evidence().coordinateFrame(), fixture.evidence().transform(), 120.0));
        TraceRequest badEndpoint = withInput(fixture.request(), new CorridorTraceInput(shifted, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new CorridorEngineAdapter(FIELD).trace(
            badEndpoint, fixture.evidence(), fixture.network()));
    }

    @Test
    void noSignalAndCancellationPublishNoRoutes() {
        Fixture noSignal = fixture(false);
        TraceHypothesisSet empty = new CorridorEngineAdapter(FIELD).trace(
            noSignal.request(), noSignal.evidence(), noSignal.network());
        assertEquals(TraceHypothesisSet.Status.NO_ROUTE, empty.status());
        assertTrue(empty.hypotheses().isEmpty());
        TraceHypothesisSet cancelled = new CorridorEngineAdapter(FIELD).trace(
            noSignal.request(), noSignal.evidence(), noSignal.network(), () -> true);
        assertEquals(TraceHypothesisSet.Status.CANCELLED, cancelled.status());
        assertTrue(cancelled.hypotheses().isEmpty());
    }

    @Test
    void solverBudgetThatCannotAdmitTheProductionStateSpaceFailsBeforePublishingRoutes() {
        Fixture fixture = fixture(true);
        TraceRequest constrained = withBudgets(fixture.request(), new TraceBudgets(3, 1, 1, 1, 1));
        TraceHypothesisSet result = new CorridorEngineAdapter(FIELD).trace(
            constrained, fixture.evidence(), fixture.network());
        assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, result.status());
        assertTrue(result.alternativesTruncated());
        assertTrue(result.hypotheses().isEmpty());
        assertEquals(0, result.evaluatedStates());
        assertEquals(0, result.evaluatedTransitions());
    }

    @Test
    void unavailablePosteriorRemainsDistinctFromMeasuredZeroAndMassChecksIgnoreOnlyUnavailable() {
        List<MetricPoint> points = List.of(new MetricPoint(0, 0), new MetricPoint(1, 0));
        List<ObservationOwnership> support = List.of(
            ObservationOwnership.DIRECT_TWO_SIDED, ObservationOwnership.DIRECT_TWO_SIDED);
        TraceHypothesis unavailable = new TraceHypothesis("u", "u", points, support, 1.0,
            OptionalDouble.empty(), Map.of());
        TraceHypothesis zero = new TraceHypothesis("z", "z", points, support, 2.0, 0.0, Map.of());
        assertTrue(unavailable.posteriorProbability().isEmpty());
        assertEquals(0.0, zero.posteriorProbability().orElseThrow());
        assertEquals(2, new TraceHypothesisSet(TrackerMode.HYBRID, List.of(unavailable, zero),
            TraceHypothesisSet.Status.AMBIGUOUS, false, 2, 1, "mixed availability")
            .hypotheses().size());
        assertEquals(0, new TraceHypothesisSet(TrackerMode.DIRECTIONAL_IMAGE, List.of(unavailable),
            TraceHypothesisSet.Status.COMPLETE, false, 0, 0, "route with unavailable counters")
            .evaluatedStates());
        TraceHypothesis tooMuch = new TraceHypothesis("m", "m", points, support, 3.0, 0.6, Map.of());
        TraceHypothesis alsoTooMuch = new TraceHypothesis("n", "n", points, support, 4.0, 0.6, Map.of());
        assertThrows(IllegalArgumentException.class, () -> new TraceHypothesisSet(TrackerMode.PROBABILISTIC,
            List.of(tooMuch, alsoTooMuch), TraceHypothesisSet.Status.AMBIGUOUS,
            false, 2, 1, "invalid mass"));
    }

    private static Fixture fixture(boolean ridge) {
        int width = 161;
        int height = 101;
        GeographicPoint origin = new GeographicPoint(50, 14);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(49.99, 13.99), new GeographicPoint(50.01, 14.02));
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
            new MetricPoint(0, 0), 1, 0, 0, 1);
        double[] values = new double[width * height];
        Arrays.fill(values, 0.02);
        if (ridge) {
            for (int y = 52; y <= 54; y++) {
                Arrays.fill(values, y * width, (y + 1) * width, y == 53 ? 1.0 : 0.6);
            }
        }
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, FIELD,
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        EvidenceResolution resolution = EvidenceResolution.nativeSource(2.0, 1.0).resampledTo(1.0);
        MetricRegion region = MetricRegion.rectangle(1, 1, 159, 99);
        EvidenceSnapshot evidence = new EvidenceSnapshot("evidence", frame, transform, resolution,
            region, MetricRegion.rectangle(-0.5, -0.5, 160.5, 100.5), Map.of(FIELD, field), "source");
        List<Double> xs = List.of(20.0, 35.0, 58.0, 90.0, 115.0, 140.0);
        List<Double> chainage = List.of(0.0, 15.0, 38.0, 70.0, 95.0, 120.0);
        List<DetachedProfileSamplingLocation> locations = new java.util.ArrayList<>();
        for (int index = 0; index < xs.size(); index++) {
            locations.add(DetachedProfileSamplingLocation.at(
                frame.toGeographic(new MetricPoint(xs.get(index), 50.0)), frame, transform,
                chainage.get(index)));
        }
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey last = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 3);
        Map<PrimitiveKey, DetachedPrimitive> primitives = new LinkedHashMap<>();
        primitives.put(first, new DetachedNode(first, locations.get(0).geographicPoint(), Map.of(), false, false));
        primitives.put(last, new DetachedNode(last, locations.get(locations.size() - 1).geographicPoint(),
            Map.of(), false, false));
        primitives.put(way, new DetachedWay(way, List.of(first, last), Map.of("highway", "path"), false, false));
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
            "corridor-adapter-v1", primitives.keySet(), Set.of(way), Set.of(), Set.of(first, last), Set.of(),
            Map.of(way, List.of(new OccurrenceRange(0, 1))), List.of(), region, region,
            false, true, true, true);
        NetworkSnapshot network = new NetworkSnapshot("network", SnapshotRole.CAPTURED_BEFORE,
            "dataset", 1, closure, primitives,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures
                .closedWorldReferrerWatches(primitives));
        TraceRequest request = new TraceRequest(way, new OccurrenceRange(0, 1), TrackerMode.CORRIDOR_AWARE,
            AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(8.0), TraceBudgets.defaults(),
            evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
            "settings", "parameters", "sampler", 20.0,
            new ProfileChainage(chainage, 20.0), resolution,
            Optional.of(new CorridorTraceInput(locations, 1.0)));
        return new Fixture(request, evidence, network);
    }

    private static TraceRequest withoutInput(TraceRequest request) {
        return new TraceRequest(request.selectedWayKey(), request.selectedRange(), request.engine(),
            request.geometryMode(), request.permissions(), request.budgets(), request.evidenceSnapshotId(),
            request.evidenceContentHash(), request.networkSnapshotId(), request.networkContentHash(),
            request.settingsHash(), request.parameterHash(), request.samplerId(),
            request.configuredSampleStepMeters(), request.profileChainage(), request.evidenceResolution());
    }

    private static TraceRequest withInput(TraceRequest request, CorridorTraceInput input) {
        return new TraceRequest(request.selectedWayKey(), request.selectedRange(), request.engine(),
            request.geometryMode(), request.permissions(), request.budgets(), request.evidenceSnapshotId(),
            request.evidenceContentHash(), request.networkSnapshotId(), request.networkContentHash(),
            request.settingsHash(), request.parameterHash(), request.samplerId(),
            request.configuredSampleStepMeters(), request.profileChainage(), request.evidenceResolution(),
            Optional.of(input));
    }

    private static TraceRequest withBudgets(TraceRequest request, TraceBudgets budgets) {
        return new TraceRequest(request.selectedWayKey(), request.selectedRange(), request.engine(),
            request.geometryMode(), request.permissions(), budgets, request.evidenceSnapshotId(),
            request.evidenceContentHash(), request.networkSnapshotId(), request.networkContentHash(),
            request.settingsHash(), request.parameterHash(), request.samplerId(),
            request.configuredSampleStepMeters(), request.profileChainage(), request.evidenceResolution(),
            request.corridorInput());
    }

    private record Fixture(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network) { }
}

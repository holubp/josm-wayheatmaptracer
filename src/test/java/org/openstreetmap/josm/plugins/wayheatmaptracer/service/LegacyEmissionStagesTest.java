package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Stage ownership on a retained source with a real GAP row and exact fixed event. */
class LegacyEmissionStagesTest {
    private static final int WIDTH = 96, HEIGHT = 96;
    private static final String FIELD = "stage-ridge";

    @Test
    void anchoredTargetAndFixedBoundaryKeepOriginalGapAndExactOccurrence() {
        var fixture = fixture();
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(
                 fixture.evidence(), FIELD, fixture.input(), fixture.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                fixture.evidence(), FIELD, fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var event = new LegacyEmissionBinding.ProtectedOccurrence("fixed-3-4", 3, 4, 28,
                new MetricPoint(52, 64.5), true);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                List.of(0, 1, 2, 3, 4, 5, 6), 1, List.of(event));
            assertEquals(NativeConnectorEvaluator.SourceClass.GAP,
                binding.observations().get(3).sourceClass());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,
                    retained, binding);
                 var result = new RidgeTracker().trackEmission(input, CancellationProbe.NONE)) {
                assertEquals(7, result.originalObservations().size());
                assertEquals(NativeConnectorEvaluator.SourceClass.GAP,
                    result.originalObservations().get(3).sourceClass());
                assertFalse(result.intervalStages().isEmpty(), "Actual reliable anchors must trigger stage two");
                int gapVertex = result.mapping().emittedVertexForObservation(3).orElseThrow();
                assertEquals(EmissionResult.StateKind.INTERVAL_TARGET,
                    result.inferenceVertices().get(gapVertex).kind());
                int boundaryVertex = gapVertex + 1;
                var boundary = result.inferenceVertices().get(boundaryVertex);
                assertEquals(new EmissionMapping.BoundaryVertex("fixed-3-4"), boundary.owner());
                assertEquals(new MetricPoint(52, 64.5), boundary.position());
                assertTrue(boundary.nativeStateId().isEmpty());
                assertTrue(boundary.offsetRasterPixels().isEmpty());
                assertTrue(result.mapping().emittedVertexForObservation(0).isPresent());
                assertTrue(result.mapping().emittedVertexForObservation(6).isPresent());
                assertTrue(result.mapping().emittedVertexForObservation(4).isPresent(),
                    "The right protected bracketing row must be emitted");
                int emitted = 0, consumed = 0, boundaryCount = 0;
                for (int row = 0; row < result.originalObservations().size(); row++) {
                    var ownerRecord = result.mapping().observationOwners().get(row);
                    if (ownerRecord instanceof EmissionMapping.Emitted link) {
                        emitted++;
                        assertEquals(new EmissionMapping.ObservationVertex(row),
                            result.mapping().vertexOwners().get(link.vertexIndex()));
                    } else if (ownerRecord instanceof EmissionMapping.Consumed link) {
                        consumed++;
                        var arc = result.inferenceArcs().get(link.arcIndex());
                        assertEquals(EmissionResult.ArcKind.OPTIONAL_ADMITTED, arc.kind());
                        assertEquals(NativeConnectorEvaluator.Status.ADMITTED,
                            arc.optionalReceipt().orElseThrow().status());
                        var left = assertInstanceOf(EmissionMapping.ObservationVertex.class,
                            result.mapping().vertexOwners().get(link.arcIndex()));
                        var right = assertInstanceOf(EmissionMapping.ObservationVertex.class,
                            result.mapping().vertexOwners().get(link.arcIndex() + 1));
                        assertTrue(left.observationIndex() < row && row < right.observationIndex());
                        assertTrue(right.observationIndex() - left.observationIndex() - 1 <= 16);
                        assertTrue(arc.sourceSpanMeters() <= 20);
                    } else fail("Every original observation needs an exact owner");
                }
                for (var ownerRecord : result.mapping().vertexOwners())
                    if (ownerRecord instanceof EmissionMapping.BoundaryVertex) boundaryCount++;
                assertEquals(1, boundaryCount);
                assertEquals(7, emitted + consumed);
                assertEquals(emitted + boundaryCount, result.inferenceVertices().size());
                assertEquals(result.inferenceVertices().size() - 1, result.inferenceArcs().size());
                var rowFive = assertInstanceOf(EmissionMapping.Consumed.class,
                    result.mapping().observationOwners().get(5));
                assertEquals(new EmissionMapping.ObservationVertex(4),
                    result.mapping().vertexOwners().get(rowFive.arcIndex()));
                assertEquals(new EmissionMapping.ObservationVertex(6),
                    result.mapping().vertexOwners().get(rowFive.arcIndex() + 1));
                assertEquals(EmissionResult.ArcKind.OPTIONAL_ADMITTED,
                    result.inferenceArcs().get(rowFive.arcIndex()).kind());
                assertEquals(16, result.inferenceArcs().get(rowFive.arcIndex()).sourceSpanMeters(), 1e-12);
                assertEquals(4, result.inferenceArcs().get(boundaryVertex - 1).sourceSpanMeters(), 1e-12);
                assertEquals(4, result.inferenceArcs().get(boundaryVertex).sourceSpanMeters(), 1e-12);
                assertEquals(result.stitched().total(), result.inferenceArcs().stream()
                    .mapToDouble(arc -> arc.q() + arc.c() + arc.t() + arc.k()).sum()
                    + 0.28 * Math.abs(result.inferenceVertices().get(0).offsetRasterPixels().orElseThrow()
                        - 0), 1e-7);
                assertEquals(48, result.sourceIntervalMassMeters(), 1e-12);
                assertEquals(result.inferenceArcs().stream().filter(arc -> arc.meanNativeIntensity().isPresent())
                    .mapToDouble(EmissionResult.Arc::lengthMeters).sum(),
                    result.measuredPhysicalLengthMeters(), 1e-10);
                assertEquals(result.postSmoothing().total(), result.postSmoothingArcs().stream()
                    .mapToDouble(arc -> arc.q() + arc.c() + arc.t() + arc.k()).sum()
                    + 0.28 * Math.abs(result.inferenceVertices().get(0).offsetRasterPixels().orElseThrow()),
                    1e-7);
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void kernelWorkRefusalIsTerminalForTheRetainedAttempt() {
        var fixture = fixture();
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(
                 fixture.evidence(), FIELD, fixture.input(), fixture.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                fixture.evidence(), FIELD, fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                List.of(0, 1, 2, 3, 4, 5, 6), 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,
                    retained, binding)) {
                long remaining = budget.snapshot().workLimit() - budget.snapshot().workUsed();
                assertTrue(remaining > 200);
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, remaining - 200);
                var first = assertThrows(EmissionWorkBudget.LimitExceededException.class,
                    () -> new RidgeTracker().trackEmission(input, CancellationProbe.NONE));
                assertEquals(EmissionWorkBudget.LimitKind.TOTAL_WORK, first.kind());
                assertSame(first, assertThrows(EmissionWorkBudget.LimitExceededException.class,
                    () -> new RidgeTracker().trackEmission(input, CancellationProbe.NONE)));
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void kernelPairRefusalIsTerminalForTheRetainedAttempt() {
        var fixture = fixture();
        var ledger = AttemptMemoryLedger.production();
        var limits = new TraceBudgets(96, 3, 48_000_000, 8, 4);
        var budget = new EmissionWorkBudget(limits, CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(
                 fixture.evidence(), FIELD, fixture.input(), fixture.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                fixture.evidence(), FIELD, fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                List.of(0, 1, 2, 3, 4, 5, 6), 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,
                    retained, binding)) {
                var first = assertThrows(EmissionWorkBudget.LimitExceededException.class,
                    () -> new RidgeTracker().trackEmission(input, CancellationProbe.NONE));
                assertEquals(EmissionWorkBudget.LimitKind.PAIR_VISITS, first.kind());
                assertEquals(3, budget.snapshot().pairVisits());
                assertSame(first, assertThrows(EmissionWorkBudget.LimitExceededException.class,
                    () -> new RidgeTracker().trackEmission(input, CancellationProbe.NONE)));
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void kernelMemoryRefusalReleasesItsPartialOwnerAndReplaysTerminalFailure() {
        var fixture = fixture();
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(
                 fixture.evidence(), FIELD, fixture.input(), fixture.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                fixture.evidence(), FIELD, fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                List.of(0, 1, 2, 3, 4, 5, 6), 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,
                    retained, binding)) {
                long before = ledger.currentBytes();
                long remaining = ledger.limitBytes() - before;
                assertTrue(remaining > 1);
                AttemptMemoryLedger.ResourceLimitException first;
                try (var pressure = root.reserve(remaining - 1)) {
                    first = assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                        () -> new RidgeTracker().trackEmission(input, CancellationProbe.NONE));
                }
                assertEquals(before, ledger.currentBytes(),
                    "The failed kernel must release every partial execution owner");
                assertSame(first, assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> new RidgeTracker().trackEmission(input, CancellationProbe.NONE)));
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void cancellationDuringKernelExpansionIsTerminalAndReleasesItsOwner() {
        var fixture = fixture();
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(
                 fixture.evidence(), FIELD, fixture.input(), fixture.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                fixture.evidence(), FIELD, fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                List.of(0, 1, 2, 3, 4, 5, 6), 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,
                    retained, binding)) {
                long before = ledger.currentBytes();
                var checks = new AtomicInteger();
                CancellationProbe probe = () -> checks.incrementAndGet() > 30;
                var first = assertThrows(CancellationException.class,
                    () -> new RidgeTracker().trackEmission(input, probe));
                assertTrue(checks.get() > 30, "Cancellation must interrupt active kernel work");
                assertEquals(before, ledger.currentBytes(),
                    "Cancelled kernel work must release its partial execution owner");
                assertSame(first, assertThrows(CancellationException.class,
                    () -> new RidgeTracker().trackEmission(input, CancellationProbe.NONE)));
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void consecutiveZeroPhysicalBoundaryArcsTransportTheLastPositiveDirection() {
        var source = fixture();
        var frame = source.evidence().coordinateFrame();
        var transform = source.evidence().transform();
        var locations = new ArrayList<DetachedProfileSamplingLocation>();
        double[] x = {24, 32, 32, 40};
        for (int row = 0; row < x.length; row++) {
            var point = new MetricPoint(x[row], 64.22);
            locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(point), frame,
                transform, 8.0 * row));
        }
        var trace = new CorridorTraceInput(locations, 1);
        var chainage = new ProfileChainage(locations.stream()
            .map(DetachedProfileSamplingLocation::cumulativeGroundDistanceMeters).toList(), 24);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(
                 source.evidence(), FIELD, trace, chainage, 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                source.evidence(), FIELD, trace.profileLocations(), 25, 1, 1, budget, permit);
            var left = retained.profiles().levelZeroProfiles().get(1);
            var right = retained.profiles().levelZeroProfiles().get(2);
            assertEquals(left.peaks().get(0).offsetPx(), right.peaks().get(0).offsetPx(), 0);
            var peak = left.peaks().get(0);
            var eventRaster = new RasterPoint(left.anchorScreen().x + left.normalScreen().x * peak.offsetPx(),
                left.anchorScreen().y + left.normalScreen().y * peak.offsetPx());
            var exactEvent = transform.pixelCenterToMetric(eventRaster.x(), eventRaster.y());
            var event = new LegacyEmissionBinding.ProtectedOccurrence("coincident-fixed", 1, 2,
                12, exactEvent, true);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                List.of(0, 1, 2, 3), 1, List.of(event));
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,
                    retained, binding);
                 var result = new RidgeTracker().trackEmission(input, CancellationProbe.NONE)) {
                assertEquals(5, result.inferenceVertices().size());
                assertEquals(4, result.inferenceArcs().size());
                assertEquals(new EmissionMapping.BoundaryVertex("coincident-fixed"),
                    result.inferenceVertices().get(2).owner());
                assertEquals(0, result.inferenceArcs().get(1).lengthMeters(), 0);
                assertEquals(0, result.inferenceArcs().get(2).lengthMeters(), 0);
                assertTrue(result.inferenceArcs().get(0).ordinaryPositive().isPresent());
                var firstCursor = result.inferenceArcs().get(1).ordinaryZeroCursor().orElseThrow();
                var secondCursor = result.inferenceArcs().get(2).ordinaryZeroCursor().orElseThrow();
                assertSame(firstCursor.ordinaryOrigin().orElseThrow(),
                    secondCursor.ordinaryOrigin().orElseThrow());
                assertTrue(result.inferenceArcs().get(3).ordinaryPositive().isPresent());
                assertEquals(0, result.inferenceArcs().get(3).k(), 1e-12,
                    "Two zero legs must preserve the original straight positive heading");
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    private record Fixture(EvidenceSnapshot evidence, CorridorTraceInput input,
            ProfileChainage chainage) { }

    private static Fixture fixture() {
        double[] values = new double[WIDTH * HEIGHT];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < HEIGHT; y++) {
            double ridge = Math.exp(-0.5 * Math.pow((y - 64.5) / 3.0, 2));
            for (int x = 0; x < WIDTH; x++)
                values[y * WIDTH + x] = x == 48 ? 0 : (float) Math.rint(255 * ridge) / 255.0f;
        }
        var geographicOrigin = new GeographicPoint(0, 0);
        var frame = LocalMetricFrame.certifiedEquirectangular(geographicOrigin,
            new GeographicPoint(-0.01, -0.01), new GeographicPoint(0.01, 0.01));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1);
        var field = new ScalarEvidenceField(WIDTH, HEIGHT, values, valid,
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, FIELD,
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        var evidence = new EvidenceSnapshot("legacy-stages", frame, transform,
            EvidenceResolution.nativeSource(1, 1), MetricRegion.rectangle(0, 0, WIDTH - 1, HEIGHT - 1),
            MetricRegion.rectangle(-0.5, -0.5, WIDTH - 0.5, HEIGHT - 0.5), Map.of(FIELD, field),
            "stages-test");
        var locations = new ArrayList<DetachedProfileSamplingLocation>();
        for (int row = 0; row < 7; row++) {
            var point = new MetricPoint(24 + 8.0 * row, 64.22);
            locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(point), frame,
                transform, 8.0 * row));
        }
        return new Fixture(evidence, new CorridorTraceInput(locations, 1),
            new ProfileChainage(locations.stream()
                .map(DetachedProfileSamplingLocation::cumulativeGroundDistanceMeters).toList(), 48));
    }
}

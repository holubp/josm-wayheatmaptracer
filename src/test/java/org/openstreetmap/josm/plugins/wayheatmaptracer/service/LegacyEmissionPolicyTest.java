package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Explicit existing evaluator policies must not silently alter the default Legacy entry. */
class LegacyEmissionPolicyTest {
    private static final String DEFAULT_MODEL = "LEGACY_OPTIONAL_NATIVE_INTEGRAL_V2";
    private record ArcValue(EmissionResult.ArcKind kind, double span, double length,
            double q, double c, double t, double k, Optional<Double> mean,
            Optional<NativeConnectorEvaluator.QueryPolicy> query, Optional<String> parameterVersion) { }
    private record Output(List<EmissionResult.Vertex> raw, List<EmissionResult.Vertex> smoothed,
            List<ArcValue> rawArcs, List<ArcValue> smoothedArcs,
            EmissionResult.Costs first, List<EmissionResult.Costs> intervals,
            EmissionResult.Costs stitched, EmissionResult.Costs post,
            EmissionResult.SeedReference seed, EmissionResult.SourceReference source,
            EmissionWorkBudget.Snapshot work, long peak) { }
    @FunctionalInterface private interface Run { void accept(SampledEmissionInput input); }

    @Test void defaultAndExplicitDenseEagerEntriesHaveExactlyTheSameResultAndWork() {
        var outputs = new ArrayList<Output>();
        withInput(input -> {
            try (var result = new RidgeTracker().trackEmission(input, CancellationProbe.NONE)) {
                outputs.add(output(result));
            }
        });
        withInput(input -> {
            try (var result = new RidgeTracker().trackEmission(input, CancellationProbe.NONE,
                    NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                    NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1)) {
                outputs.add(output(result));
            }
        });
        assertEquals(outputs.get(0), outputs.get(1));
        assertEquals(DEFAULT_MODEL, outputs.get(0).source().modelPolicyId());
    }

    @Test void explicitLocalStopPolicyOwnsSelectedReceiptAndResultProvenance() {
        withInput(input -> {
            try (var result = new RidgeTracker().trackEmission(input, CancellationProbe.NONE,
                    NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1,
                    NativeConnectorEvaluator.ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
                var receipts = result.inferenceArcs().stream().flatMap(arc -> arc.optionalReceipt().stream()).toList();
                assertFalse(receipts.isEmpty(), "The curved guide on a straight bright ridge must select an optional arc");
                for (var receipt : receipts) {
                    assertEquals(NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1, receipt.queryPolicy());
                    assertTrue(receipt.parameterVersion().endsWith("/ZERO_GUARDED_LOCAL_V1/STOP_ON_FINAL_MODE_FAILURE_V1"));
                }
                assertEquals(DEFAULT_MODEL + "/query=ZERO_GUARDED_LOCAL_V1/execution=STOP_ON_FINAL_MODE_FAILURE_V1",
                    result.sourceReference().modelPolicyId());
                assertEquals(input.context().policyId(), result.sourceReference().policyId(),
                    "Selecting an existing evaluator policy does not rename the v2 objective");
            }
        });
    }

    @Test void incompatibleStopPolicyRefusesBeforeChargingTrackerOrOpeningKernelOwners() {
        withInput(input -> {
            var before = input.workBudget().snapshot();
            assertThrows(IllegalArgumentException.class, () -> new RidgeTracker().trackEmission(input,
                CancellationProbe.NONE, NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                NativeConnectorEvaluator.ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1));
            assertEquals(before, input.workBudget().snapshot());
            assertThrows(NullPointerException.class, () -> new RidgeTracker().trackEmission(input,
                CancellationProbe.NONE, null, NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1));
            assertEquals(before, input.workBudget().snapshot());
        });
    }

    private static Output output(EmissionResult result) {
        return new Output(result.inferenceVertices(), result.smoothedVertices(), arcs(result.inferenceArcs()),
            arcs(result.postSmoothingArcs()), result.firstStage(), result.intervalStages(), result.stitched(),
            result.postSmoothing(), result.chosenSeed(), result.sourceReference(), result.work(), result.peakRetainedBytes());
    }
    private static List<ArcValue> arcs(List<EmissionResult.Arc> arcs) {
        return arcs.stream().map(a -> new ArcValue(a.kind(), a.sourceSpanMeters(), a.lengthMeters(), a.q(), a.c(), a.t(), a.k(),
            a.meanNativeIntensity(), a.optionalReceipt().map(NativeConnectorEvaluator.Receipt::queryPolicy),
            a.optionalReceipt().map(NativeConnectorEvaluator.Receipt::parameterVersion))).toList();
    }
    private static void withInput(Run run) {
        int width = 96, height = 96;
        var values = new double[width * height]; var valid = new boolean[values.length]; Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            values[y * width + x] = Math.exp(-.5 * Math.pow((y - 64.5) / 3, 2));
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
            new GeographicPoint(-.01, -.01), new GeographicPoint(.01, .01));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1);
        var field = new ScalarEvidenceField(width, height, values, valid,
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "ridge",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        var evidence = new EvidenceSnapshot("policy-ridge", frame, transform, EvidenceResolution.nativeSource(1, 1),
            MetricRegion.rectangle(0, 0, 95, 95), MetricRegion.rectangle(-.5, -.5, 95.5, 95.5), Map.of("ridge", field), "policy-ridge");
        var anchors = List.of(new MetricPoint(24, 64.22), new MetricPoint(32, 62.22), new MetricPoint(40, 64.22));
        var sourceS = ProfileChainage.measured(anchors, 8);
        var locations = new ArrayList<DetachedProfileSamplingLocation>();
        for (int row = 0; row < anchors.size(); row++) locations.add(DetachedProfileSamplingLocation.at(
            frame.toGeographic(anchors.get(row)), frame, transform, sourceS.cumulativeGroundMeters().get(row)));
        var trace = new CorridorTraceInput(locations, 1);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence, "ridge", trace, sourceS, 25, budget, owner)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence, "ridge", trace.profileLocations(), 25, 1, 1, budget, permit);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained, List.of(0, 1, 2), 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding)) { run.accept(input); }
        }
        assertEquals(0, ledger.currentBytes());
    }
}

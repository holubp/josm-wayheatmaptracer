package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Integration control for the pre-existing policy overload and the named affinity entry. */
class LegacyAffinityIntegrationRoutingTest {
    @Test void defaultAndExplicitDenseV2RetainExactOutputWorkAndIndependentPolicyIdentity() {
        var implicit = run(null, null);
        var explicit = run(QueryPolicy.DENSE_EIGHTH_NATIVE_V1, ExecutionPolicy.EAGER_ALL_PROBES_V1);
        assertEquals(implicit, explicit);
        assertEquals("LEGACY_OPTIONAL_NATIVE_INTEGRAL_V2", implicit.policy());
        var preflight = run(QueryPolicy.ZERO_GUARDED_LOCAL_V1, ExecutionPolicy.EXACT_SLAB_PREFLIGHT_V1);
        assertEquals("LEGACY_OPTIONAL_NATIVE_INTEGRAL_V2/query=ZERO_GUARDED_LOCAL_V1/execution=EXACT_SLAB_PREFLIGHT_V1",
            preflight.policy());
        assertEquals(implicit.vertices(), preflight.vertices());
        assertEquals(implicit.finalVertices(), preflight.finalVertices());
        assertEquals(implicit.stitched(), preflight.stitched());
        assertEquals(implicit.post(), preflight.post());
    }

    private record Snapshot(String policy, List<EmissionResult.Vertex> vertices,
        List<EmissionResult.Vertex> finalVertices, EmissionResult.Costs stitched,
        EmissionResult.Costs post, EmissionWorkBudget.Snapshot work,
        List<EmissionResult.NativeStateTable> states) { }

    private static Snapshot run(QueryPolicy query, ExecutionPolicy execution) {
        var fixture = LegacyAffinityContractProbeTest.fixture(.60, false, false);
        String field = fixture.evidence().fields().keySet().iterator().next();
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        Snapshot snapshot;
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(), field,
                 fixture.input(), fixture.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(), field,
                fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                java.util.stream.IntStream.range(0, 11).boxed().toList(), 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding);
                 var result = query == null ? new RidgeTracker().trackEmission(input, CancellationProbe.NONE)
                     : new RidgeTracker().trackEmission(input, CancellationProbe.NONE, query, execution)) {
                assertTrue(result.affinity().isEmpty());
                snapshot = new Snapshot(result.sourceReference().modelPolicyId(), result.inferenceVertices(),
                    result.smoothedVertices(), result.stitched(), result.postSmoothing(), result.work(),
                    result.nativeStateTables());
            }
        }
        assertEquals(0, ledger.currentBytes());
        return snapshot;
    }
}

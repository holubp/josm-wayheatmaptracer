package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Old v2/native state behavior remains independently callable when managed proof is unavailable. */
class ManagedAffinityKernelRoutingTest {
    record Snapshot(List<EmissionResult.Vertex> raw,List<EmissionResult.Vertex> actualFinal,
        EmissionResult.Costs stitched,EmissionResult.Costs post,List<EmissionResult.NativeStateTable> states){}
    @Test void explicitManagedEntryKeepsUnsupportedAffineNativeFallbackAndSeparatePolicyIdentity(){
        assertEquals(run(false),run(true));
    }
    private Snapshot run(boolean managed){
        var fixture=LegacyAffinityContractProbeTest.fixture(.60,false,false);
        String field=fixture.evidence().fields().keySet().iterator().next();
        var ledger=AttemptMemoryLedger.production();var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        Snapshot snapshot;
        try(var owner=ledger.rootOwner();var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(),field,fixture.input(),fixture.chainage(),25,budget,owner)){
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(),field,fixture.input().profileLocations(),25,1,1,budget,permit);
            var binding=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,java.util.stream.IntStream.range(0,11).boxed().toList(),1,List.of());
            try(var input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding)){
                var kernel=new LegacyEmissionKernel();
                LegacyEmissionKernel.Smoother smoother=(profiles,offsets,strengths)->offsets;
                EmissionResult value;
                if(managed){
                    try{value=(EmissionResult)kernel.getClass().getMethod("solveManagedChartAffinity",SampledEmissionInput.class,List.class,List.class,List.class,LegacyEmissionKernel.Smoother.class,CancellationProbe.class)
                        .invoke(kernel,input,retained.profiles().levelZeroProfiles(),List.of(-4.0,4.0),List.of(),smoother,CancellationProbe.NONE);}
                    catch(ReflectiveOperationException missing){throw new AssertionError("Explicit managed local-connector entry is required",missing);}
                }else value=kernel.solve(input,retained.profiles().levelZeroProfiles(),List.of(-4.0,4.0),List.of(),smoother,CancellationProbe.NONE);
                try(var result=value){
                    assertTrue(result.affinity().isEmpty(),"Managed proof cannot manufacture affine inventory/slab evidence");
                    Optional<?> diagnostics;
                    try{diagnostics=(Optional<?>)result.getClass().getMethod("managedAffinity").invoke(result);}
                    catch(ReflectiveOperationException missing){throw new AssertionError("Managed diagnostics must be additive and explicit",missing);}
                    assertEquals(managed,diagnostics.isPresent());
                    if(managed){
                        try{
                            assertEquals(ManagedOrdinaryAffinity.POLICY_ID,diagnostics.orElseThrow().getClass().getMethod("policyId").invoke(diagnostics.orElseThrow()));
                        }catch(ReflectiveOperationException malformed){throw new AssertionError(malformed);}
                        assertEquals("LEGACY_OPTIONAL_MANAGED_LOCAL_CONNECTOR_V1",result.sourceReference().modelPolicyId());
                    }else assertEquals("LEGACY_OPTIONAL_NATIVE_INTEGRAL_V2",result.sourceReference().modelPolicyId());
                    snapshot=new Snapshot(result.inferenceVertices(),result.smoothedVertices(),result.stitched(),result.postSmoothing(),result.nativeStateTables());
                }
            }
        }
        assertEquals(0,ledger.currentBytes());return snapshot;
    }
}

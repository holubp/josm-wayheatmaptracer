package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class ManagedAEndpointExecutionTest {
    @TempDir Path cache;
    static final NativeConnectorEvaluator.QueryPolicy QUERY=NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1;
    static final NativeConnectorEvaluator.ExecutionPolicy EXECUTION=NativeConnectorEvaluator.ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1;
    @ParameterizedTest @EnumSource(NativeAEmissionSolver.ExecutionChoice.class)
    void everyNativeExecutionChoiceUsesTheSameCapturedEndpointGraphAndOwnedScalarFacts(NativeAEmissionSolver.ExecutionChoice choice)throws Exception {
        var helper=new ManagedAEndpointProducerTest();helper.cache=cache;
        helper.withCapture(false,true,.25,CancellationProbe.NONE,f->{
            try(var permit=ManagedAEndpointProducerTest.bind(f);var input=ManagedAEndpointProducerTest.produce(f,permit)){
                List<List<NativeAEmissionSolver.EmittedOccurrence>> expected;List<Double> costs;
                try(var baseline=new CorridorAwareTracker().trackManagedAEndpointEmission(input,QUERY,EXECUTION)){
                    expected=baseline.results().stream().map(NativeAEmissionSolver.Result::emitted).toList();
                    costs=baseline.results().stream().map(NativeAEmissionSolver.Result::cost).toList();
                }
                boolean scalar=choice==NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_REFINEMENT_V1
                    ||choice==NativeAEmissionSolver.ExecutionChoice.POSITIVE_ARC_SCALAR_PREFIX_DOMINANCE_V1;
                try(var census=scalar?NativeAExecutionCensus.open(input):null;
                    var actual=invoke(input,choice,census)){
                    assertEquals(expected,actual.results().stream().map(NativeAEmissionSolver.Result::emitted).toList());
                    assertEquals(costs,actual.results().stream().map(NativeAEmissionSolver.Result::cost).toList());
                    for(var result:actual.results()){
                        assertTrue(result.numericalPolicyId().contains(ManagedAEndpointAuthority.POLICY_ID));
                        assertEquals(2,result.managedFixedEndpoints().size());
                    }
                    if(scalar){assertTrue(census.scalarStarts()>0);assertNull(census.failure());}
                }
            }
        });
    }
    static CorridorAwareTracker.OptionalEmissionTracking invoke(SampledEmissionInput input,NativeAEmissionSolver.ExecutionChoice choice,NativeAExecutionCensus census)throws Exception{
        try{return (CorridorAwareTracker.OptionalEmissionTracking)CorridorAwareTracker.class.getMethod("trackManagedAEndpointEmission",SampledEmissionInput.class,
            NativeConnectorEvaluator.QueryPolicy.class,NativeConnectorEvaluator.ExecutionPolicy.class,NativeAEmissionSolver.ExecutionChoice.class,NativeAExecutionCensus.class)
            .invoke(new CorridorAwareTracker(),input,QUERY,EXECUTION,choice,census);
        }catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException failure)throw failure;throw e;}
    }
}

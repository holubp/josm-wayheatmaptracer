package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.LegacyEmissionProgress.Counter;

class NativeLocalNoisePrecheckTest {
    /** Compatibility goldens recorded at6d6 before any production edit, not a new quality oracle. */
    @Test void defaultAndOldLocalStopKeepExact6d6Baseline() throws Exception {
        for(boolean local:new boolean[]{false,true})try(var f=positive(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
            var e=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),local?QueryPolicy.ZERO_GUARDED_LOCAL_V1:QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
                local?ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1:ExecutionPolicy.EAGER_ALL_PROBES_V1)) {
            var r=e.evaluate(f.states().get(0),f.states().get(1));
            String canonical=List.of(r.status(),r.reason(),r.queryPolicy(),r.parameterVersion(),r.from(),r.to(),r.sourceSpanMeters(),r.emittedLengthMeters(),
                r.minimumNativePitchMeters(),r.scalar(),r.scalarEnvelopeComplete(),r.measuredNativeContinuationComplete(),r.probes(),r.plannedProbeCount(),r.firstFailingSlabIndex()).toString();
            String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(local?"fc7f3b5c19b822b125a038696484630a3ac4c0dc600d0393d9dfc3145737da3d":"62c85a389488864a5ca2e4a5219166ae743a2654e8a79639190ee3ab3c940a1e",digest);
            assertEquals(local?451253:413550,f.input().workBudget().workUsed());assertEquals(1,f.input().workBudget().pairVisits());
            long[] operations=local?new long[]{52829,33022,154251,153709,51194,6247,0,1,0,0,0,0}:new long[]{49673,31410,145015,136325,45618,5508,0,1,0,0,0,0};
            for(var op:EmissionWorkBudget.Operation.values())assertEquals(operations[op.ordinal()],f.input().workBudget().operationUnits(op),op.name());
            assertEquals(local?904946:933946,f.ledger().peakBytes());
        }
    }
    static ExecutionPolicy optimized() {
        try{return ExecutionPolicy.valueOf("INITIAL_LOCAL_NOISE_PRECHECK_V1");}
        catch(IllegalArgumentException missing){throw new AssertionError("Explicit sampled-noise opt-in execution identity is missing",missing);}
    }
    @Test void positiveBackgroundSkipsOnlyLocalExtractionAndKeepsSettledDenseEvidenceExact() {
        try(var f=positive(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
            var offProgress=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
            var onProgress=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,optimized());
            var off=NativeConnectorEvaluator.forLegacyProgress(f.input(),new Domain(f.states(),List.of()),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1,offProgress);
            var on=NativeConnectorEvaluator.forLegacyProgress(f.input(),new Domain(f.states(),List.of()),QueryPolicy.ZERO_GUARDED_LOCAL_V1,optimized(),onProgress)) {
            var beforeOff=f.input().workBudget().snapshot();
            var expected=off.evaluate(f.states().get(0),f.states().get(1));offProgress.seal(null);
            var beforeOn=f.input().workBudget().snapshot();
            var actual=on.evaluate(f.states().get(0),f.states().get(1));onProgress.seal(null);
            var afterOn=f.input().workBudget().snapshot();
            assertEquals(Status.ADMITTED,expected.status());assertEquals(expected.status(),actual.status());assertEquals(expected.reason(),actual.reason());
            assertEquals(expected.scalar(),actual.scalar());assertEquals(expected.sourceWeightedLoss(),actual.sourceWeightedLoss(),0);
            assertEquals(expected.plannedProbeCount(),actual.plannedProbeCount());assertEquals(expected.probes().size(),actual.probes().size());
            for(int i=0;i<actual.probes().size();i++) {
                var a=expected.probes().get(i);var b=actual.probes().get(i);
                assertEquals(ProfileBackend.DENSE_FALLBACK,b.profileQuery().backend());assertEquals(FallbackCause.NOISE_FLOOR_NONZERO,b.profileQuery().fallbackCause());
                assertEquals(a.parameter(),b.parameter(),0);assertEquals(a.rasterPosition(),b.rasterPosition());assertEquals(a.metricPosition(),b.metricPosition());
                assertEquals(a.metricNormal(),b.metricNormal());assertEquals(a.nativePitchMeters(),b.nativePitchMeters(),0);
                assertEquals(a.samples(),b.samples());assertEquals(a.extraction(),b.extraction());assertEquals(a.selectedMode(),b.selectedMode());assertEquals(a.threshold(),b.threshold());
                assertEquals(a.profileQuery(),b.profileQuery());
            }
            long probes=actual.probes().size();
            assertEquals(2*probes,offProgress.view().count(Counter.EXTRACTION_STARTED));
            assertEquals(probes,onProgress.view().count(Counter.EXTRACTION_STARTED),"Actual LOCAL extraction must be skipped, dense extraction must run");
            assertEquals(probes,onProgress.view().count(Counter.EXTRACTION_COMPLETED));
            assertEquals(actual.probes().stream().mapToLong(p->p.extraction().result().baseline().modes().size()).sum(),onProgress.view().count(Counter.MODES_RETURNED));
            assertEquals(actual.probes().stream().mapToLong(p->p.extraction().acceptedReceipts().size()).sum(),onProgress.view().count(Counter.ACCEPTED_THRESHOLDS));
            assertEquals(probes,onProgress.view().count(Counter.INITIAL_LOCAL_NOISE_SKIPS));assertTrue(onProgress.view().hasInitialLocalNoiseProof());
            assertEquals(17,onProgress.view().lastNoiseSampleCount());assertEquals(17,onProgress.view().lastNoiseValidCount());
            assertEquals(0,onProgress.view().lastNoiseZeroCount());assertEquals(3,onProgress.view().lastNoiseRank());
            assertEquals(offProgress.view().count(Counter.SAMPLE_COMPLETED),onProgress.view().count(Counter.SAMPLE_COMPLETED));
            assertTrue(actual.parameterVersion().endsWith("/INITIAL_LOCAL_NOISE_PRECHECK_V1"));
            for(var op:List.of(EmissionWorkBudget.Operation.CELL_QUERY,EmissionWorkBudget.Operation.NODE_READ,EmissionWorkBudget.Operation.REGION_QUERY))
                assertEquals(beforeOn.operationUnits().get(op)-beforeOff.operationUnits().get(op),afterOn.operationUnits().get(op)-beforeOn.operationUnits().get(op),op.name());
            var mode=EmissionWorkBudget.Operation.MODE_EXTRACTION;
            long oldMode=beforeOn.operationUnits().get(mode)-beforeOff.operationUnits().get(mode),newMode=afterOn.operationUnits().get(mode)-beforeOn.operationUnits().get(mode);
            assertTrue(newMode<oldMode);
            System.out.println("synthetic positive-background probes="+probes+" samples="+onProgress.view().count(Counter.SAMPLE_COMPLETED)+" MODE_EXTRACTION off="+oldMode+" on="+newMode);
        }
    }

    @Test void newOptionKeepsIssuedSamplesOwnedAndRequiresTheExistingLocalQuery() {
        try(var f=positive(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE))) {
            long inputBytes=f.ledger().currentBytes();
            for(var query:QueryPolicy.values())if(query!=QueryPolicy.ZERO_GUARDED_LOCAL_V1)
                assertThrows(IllegalArgumentException.class,()->new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),query,optimized()));
            try(var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),QueryPolicy.ZERO_GUARDED_LOCAL_V1,optimized())) {
                long setup=f.ledger().currentBytes();var actual=evaluator.evaluate(f.states().get(0),f.states().get(1));
                assertEquals(Status.ADMITTED,actual.status());long count=actual.probes().stream().mapToLong(p->p.samples().size()).sum();
                assertTrue(f.ledger().currentBytes()-setup>=17L*count,"all issued dense sample payload remains charged");
                var identities=new IdentityHashMap<Object,Boolean>();
                for(var p:actual.probes())for(var s:p.samples())assertNull(identities.put(s,Boolean.TRUE));
            }
            assertEquals(inputBytes,f.ledger().currentBytes(),"discarded proof/local samples and issued dense payload all close");
        }
    }
    static NativeConnectorEvaluatorTest.Straight positive(EmissionWorkBudget work) {
        // Freeze the exact6d6 synthetic scalar bits so compatibility hashes do not depend
        // on another host's permitted Math.exp rounding. These are the same sampled values.
        double[] intensity={0x1.0p0,0x1.5ed5bfd2611a2p-1,0x1.3baaaa7e633ecp-2,0x1.abcd0d05449cep-3,
            0x1.9a264da01a01p-3,0x1.999b29bf0ff88p-3,0x1.99999b3c3d0e1p-3,0x1.9999999a3ab9dp-3,0x1.9999999999b07p-3};
        return NativeConnectorEvaluatorTest.scene((x,y)->{int d=(int)Math.abs(y-32);return d<intensity.length?intensity[d]:.2;},(x,y)->true,
            MetricRegion.rectangle(0,0,63,63),SourceClass.GAP,2,20,RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),work,.5,null,8);
    }
}

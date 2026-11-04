package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.LegacyEmissionProgress.Counter;

class LocalNoiseControlParityTest {
    static final QueryPolicy QUERY=QueryPolicy.ZERO_GUARDED_LOCAL_V1;
    static final ExecutionPolicy OFF=ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1;
    static final ExecutionPolicy ON=ExecutionPolicy.INITIAL_LOCAL_NOISE_PRECHECK_V1;
    static EmissionWorkBudget work(){return new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);}

    @Test void zeroFloorAllZeroParallelAndInvalidSupportKeepActualOldPath() {
        for(int kind=0;kind<4;kind++) {
            final int fixture=kind;
            try(var f=NativeConnectorEvaluatorTest.scene((x,y)->switch(fixture) {
                    case 0 -> Math.abs(y-32)<=1?1:0;
                    case 1 -> 0;
                    case 2 -> Math.abs(y-32)<=1||Math.abs(y-37)<=1?1:0;
                    default -> .2+.8*Math.exp(-.5*Math.pow(y-32,2));
                },(x,y)->fixture!=3||y<35,MetricRegion.rectangle(0,0,63,63),SourceClass.GAP,
                    2,20,RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),work(),.5,null,8);
                var a=LegacyEmissionProgress.open(f.input(),QUERY,OFF);
                var b=LegacyEmissionProgress.open(f.input(),QUERY,ON);
                var old=NativeConnectorEvaluator.forLegacyProgress(f.input(),new Domain(f.states(),List.of()),QUERY,OFF,a);
                var fresh=NativeConnectorEvaluator.forLegacyProgress(f.input(),new Domain(f.states(),List.of()),QUERY,ON,b)) {
                var expected=old.evaluate(f.states().get(0),f.states().get(1));
                var actual=fresh.evaluate(f.states().get(0),f.states().get(1));a.seal(null);b.seal(null);
                assertReceipt(expected,actual);
                assertEquals(a.view().count(Counter.SAMPLE_COMPLETED),b.view().count(Counter.SAMPLE_COMPLETED));
                if(fixture<3) {
                    assertEquals(0,b.view().count(Counter.INITIAL_LOCAL_NOISE_SKIPS));
                    assertFalse(b.view().hasInitialLocalNoiseProof());
                    assertThrows(IllegalStateException.class,b.view()::lastNoiseRank);
                    assertEquals(a.view().count(Counter.EXTRACTION_STARTED),b.view().count(Counter.EXTRACTION_STARTED));
                } else {
                    assertTrue(b.view().count(Counter.INITIAL_LOCAL_NOISE_SKIPS)>0);
                    assertTrue(actual.probes().stream().anyMatch(p->p.samples().stream().anyMatch(s->!s.valid())));
                }
            }
        }
    }

    @Test void ninetyNineExpansionsRemainCompleteAndUnskipped() {
        var resolution=new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,OptionalDouble.of(2),.2,
            List.of(new EvidenceResolution.PitchSample(0,OptionalDouble.of(2),.2)),OptionalDouble.of(.2));
        try(var oldFixture=LegacyProgressPolicyTest.fractional(work(),resolution);
            var f=LegacyProgressPolicyTest.fractional(work(),resolution);
            var a=LegacyEmissionProgress.open(oldFixture.input(),QUERY,OFF);
            var b=LegacyEmissionProgress.open(f.input(),QUERY,ON);
            var old=NativeConnectorEvaluator.forLegacyProgress(oldFixture.input(),new Domain(oldFixture.states(),List.of()),QUERY,OFF,a);
            var fresh=NativeConnectorEvaluator.forLegacyProgress(f.input(),new Domain(f.states(),List.of()),QUERY,ON,b)) {
            var expected=old.evaluate(oldFixture.states().get(0),oldFixture.states().get(1));
            var actual=fresh.evaluate(f.states().get(0),f.states().get(1));a.seal(null);b.seal(null);
            assertReceipt(expected,actual);assertEquals(Status.ADMITTED,actual.status());
            assertEquals(100L*actual.probes().size(),b.view().count(Counter.LOCAL_WINDOWS));
            assertEquals(0,b.view().count(Counter.INITIAL_LOCAL_NOISE_SKIPS));
            assertEquals(a.view().count(Counter.EXTRACTION_COMPLETED),b.view().count(Counter.EXTRACTION_COMPLETED));
            for(var p:actual.probes()){assertEquals(99,p.profileQuery().expansionCount());assertEquals(1203,p.samples().size());}
            long scanned=1005L*actual.probes().size(); // initial j=-502..502 only
            assertEquals(scanned+actual.probes().size(),b.view().productionUnits(EmissionWorkBudget.Operation.PREPARATION)-a.view().productionUnits(EmissionWorkBudget.Operation.PREPARATION));
            assertEquals(a.view().productionUnits(EmissionWorkBudget.Operation.MODE_EXTRACTION),b.view().productionUnits(EmissionWorkBudget.Operation.MODE_EXTRACTION));
            assertEquals(a.view().productionUnits(EmissionWorkBudget.Operation.ALLOCATION),b.view().productionUnits(EmissionWorkBudget.Operation.ALLOCATION));
        }
    }

    @Test void fallbackKeepsBinary64TwentyNineToThirtyIntervalRoundTrip() {
        double step=Math.nextUp(50.0/29),pitch=step*8;
        var resolution=new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,OptionalDouble.of(pitch),1,
            List.of(new EvidenceResolution.PitchSample(0,OptionalDouble.of(pitch),1)),OptionalDouble.of(1));
        try(var f=NativeConnectorEvaluatorTest.scene((x,y)->.2+.8*Math.exp(-.5*Math.pow((y-32)/3,2)),(x,y)->true,
                MetricRegion.rectangle(0,0,63,63),SourceClass.GAP,2,20,RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),
                work(),step,resolution,25);
            var old=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),QUERY,OFF);
            var fresh=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),QUERY,ON);
            var dense=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()))) {
            var a=old.evaluate(f.states().get(0),f.states().get(1));var b=fresh.evaluate(f.states().get(0),f.states().get(1));
            assertReceipt(a,b);assertFalse(b.probes().isEmpty());
            assertEquals(29,(int)Math.ceil(50/step));assertEquals(30,(int)Math.ceil(50/(50.0/29)));
            assertEquals(31,b.probes().get(0).samples().size());
            assertEquals(ProfileBackend.DENSE_FALLBACK,b.probes().get(0).profileQuery().backend());
            assertEquals(FallbackCause.INITIAL_WINDOW_TOO_WIDE,b.probes().get(0).profileQuery().fallbackCause());
            assertEquals(30,dense.evaluate(f.states().get(0),f.states().get(1)).probes().get(0).samples().size());
        }
    }

    @Test void allStagesCostsGeometryOwnersSeedsAndTiesRemainExact() {
        try(var a=LegacyProgressPolicyTest.stageInput(work());var b=LegacyProgressPolicyTest.stageInput(work());
            var p=LegacyEmissionProgress.open(b.input(),QUERY,ON);
            var old=new RidgeTracker().trackEmission(a.input(),CancellationProbe.NONE,QUERY,OFF);
            var fresh=new RidgeTracker().trackEmission(b.input(),CancellationProbe.NONE,QUERY,ON,p)) {
            assertEquals(old.inferenceVertices(),fresh.inferenceVertices());assertEquals(old.smoothedVertices(),fresh.smoothedVertices());
            assertEquals(old.firstStage(),fresh.firstStage());assertEquals(old.intervalStages(),fresh.intervalStages());
            assertEquals(old.stitched(),fresh.stitched());assertEquals(old.postSmoothing(),fresh.postSmoothing());
            assertEquals(old.mapping().observationOwners(),fresh.mapping().observationOwners());
            assertEquals(old.chosenSeed(),fresh.chosenSeed());assertEquals(old.eligibleAnchors(),fresh.eligibleAnchors());
            var x=old;var y=fresh;
            assertEquals(x.selectedAnchors(),y.selectedAnchors());assertEquals(x.nativeStateTables(),y.nativeStateTables());
            assertEquals(x.originReferences(),y.originReferences());assertEquals(x.generatedStateReferences(),y.generatedStateReferences());
            assertEquals(x.firstStageVertices(),y.firstStageVertices());assertArcs(x.firstStageArcs(),y.firstStageArcs());
            assertArcs(old.inferenceArcs(),fresh.inferenceArcs());assertArcs(old.postSmoothingArcs(),fresh.postSmoothingArcs());
            assertEquals(x.intervalStageReferences().size(),y.intervalStageReferences().size());
            for(int i=0;i<x.intervalStageReferences().size();i++) {
                var left=x.intervalStageReferences().get(i);var right=y.intervalStageReferences().get(i);
                assertEquals(left.ordinal(),right.ordinal());assertEquals(left.leftAnchor(),right.leftAnchor());assertEquals(left.rightAnchor(),right.rightAnchor());
                assertEquals(left.vertices(),right.vertices());assertEquals(left.costs(),right.costs());assertArcs(left.arcs(),right.arcs());
            }
            assertTrue(fresh.sourceReference().modelPolicyId().contains(ON.name()));assertEquals(LegacyEmissionProgress.Termination.SUCCESS,p.view().termination());
        }
    }

    @Test void augmentedObserverFixedLayoutFitsItsExistingPrepaidBound() throws Exception {
        try(var f=NativeLocalNoisePrecheckTest.positive(work())) {
            long before=f.ledger().currentBytes();
            try(var p=LegacyEmissionProgress.open(f.input(),QUERY,ON)) {
                assertEquals(LegacyEmissionProgress.STORAGE_BYTES,f.ledger().currentBytes()-before);
                long fields=0,arrays=0;
                for(var field:LegacyEmissionProgress.class.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())) {
                    fields+=8; // conservative slot for every primitive/reference, even a boolean
                    if(field.getType().isArray()){field.setAccessible(true);Object array=field.get(p);
                        arrays+=AttemptMemoryLedger.arrayBytes(Array.getLength(array),field.getType().componentType()==int.class?4:8);}
                }
                long bound=AttemptMemoryLedger.objectBytes(fields)+arrays+AttemptMemoryLedger.objectBytes(8);
                // Also reserve room for the separately reviewed future borrowed input-binding reference.
                assertTrue(bound+8<=LegacyEmissionProgress.STORAGE_BYTES,()->"fixed progress layout="+bound);
                System.out.println("fixed observer conservative layout="+bound+" prepaid="+LegacyEmissionProgress.STORAGE_BYTES+" spare="+(LegacyEmissionProgress.STORAGE_BYTES-bound));
                p.seal(null);long used=f.input().workBudget().workUsed();long bytes=f.ledger().currentBytes();
                p.view().hasInitialLocalNoiseProof();assertEquals(used,f.input().workBudget().workUsed());assertEquals(bytes,f.ledger().currentBytes());
            }
            assertEquals(before,f.ledger().currentBytes());
        }
    }

    static void assertReceipt(Receipt a,Receipt b) {
        assertEquals(a.status(),b.status());assertEquals(a.reason(),b.reason());assertEquals(a.from().row(),b.from().row());assertEquals(a.to().row(),b.to().row());
        assertEquals(a.sourceSpanMeters(),b.sourceSpanMeters(),0);assertEquals(a.emittedLengthMeters(),b.emittedLengthMeters(),0);
        assertEquals(a.minimumNativePitchMeters(),b.minimumNativePitchMeters(),0);assertEquals(a.scalar(),b.scalar());
        assertEquals(a.scalarEnvelopeComplete(),b.scalarEnvelopeComplete());assertEquals(a.measuredNativeContinuationComplete(),b.measuredNativeContinuationComplete());
        assertEquals(a.plannedProbeCount(),b.plannedProbeCount());assertEquals(a.firstFailingSlabIndex(),b.firstFailingSlabIndex());assertEquals(a.probes(),b.probes());
    }
    static void assertArcs(List<EmissionResult.Arc> a,List<EmissionResult.Arc> b) {
        assertEquals(a.size(),b.size());for(int i=0;i<a.size();i++) {
            var x=a.get(i);var y=b.get(i);assertEquals(x.index(),y.index());assertEquals(x.kind(),y.kind());
            assertEquals(x.sourceSpanMeters(),y.sourceSpanMeters(),0);assertEquals(x.lengthMeters(),y.lengthMeters(),0);
            assertEquals(x.q(),y.q(),0);assertEquals(x.c(),y.c(),0);assertEquals(x.t(),y.t(),0);assertEquals(x.k(),y.k(),0);assertEquals(x.meanNativeIntensity(),y.meanNativeIntensity());
            assertEquals(x.optionalReceipt().isPresent(),y.optionalReceipt().isPresent());
            if(x.optionalReceipt().isPresent())assertReceipt(x.optionalReceipt().orElseThrow(),y.optionalReceipt().orElseThrow());
        }
    }
}

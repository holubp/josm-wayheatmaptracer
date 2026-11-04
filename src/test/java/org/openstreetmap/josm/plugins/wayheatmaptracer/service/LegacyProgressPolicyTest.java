package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.LegacyEmissionProgress.*;

class LegacyProgressPolicyTest {
    private static final QueryPolicy QUERY=QueryPolicy.ZERO_GUARDED_LOCAL_V1;
    private static final ExecutionPolicy EXEC=ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1;

    @Test void actualNestedWindowsHaveIndependentlyEnumeratedRepeatsAndExactAdjustedWork() {
        var resolution=new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,OptionalDouble.of(2),.2,
            List.of(new EvidenceResolution.PitchSample(0,OptionalDouble.of(2),.2)),OptionalDouble.of(.2));
        var oldWork=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        var newWork=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var a=fractional(oldWork,resolution);var b=fractional(newWork,resolution);
            var p=LegacyEmissionProgress.open(b.input(),QUERY,EXEC);
            var old=new NativeConnectorEvaluator(a.input(),new Domain(a.states(),List.of()),QUERY,EXEC);
            var fresh=NativeConnectorEvaluator.forLegacyProgress(b.input(),new Domain(b.states(),List.of()),QUERY,EXEC,p)) {
            var expected=old.evaluate(a.states().get(0),a.states().get(1));
            var actual=fresh.evaluate(b.states().get(0),b.states().get(1));p.seal(null);var v=p.view();
            assertEquals(Status.ADMITTED,actual.status());assertEquals(expected.status(),actual.status());
            assertEquals(expected.sourceWeightedLoss(),actual.sourceWeightedLoss(),0);assertEquals(expected.scalar(),actual.scalar());
            assertEquals(expected.probes().size(),actual.probes().size());
            for(int i=0;i<actual.probes().size();i++) {
                var x=expected.probes().get(i);var y=actual.probes().get(i);
                assertEquals(ProfileBackend.LOCAL,y.profileQuery().backend());assertEquals(99,y.profileQuery().expansionCount());
                assertEquals(x.samples(),y.samples());assertEquals(x.selectedMode(),y.selectedMode());
                assertEquals(x.threshold().shoulderThreshold(),y.threshold().shoulderThreshold(),0);
                assertEquals(x.profileQuery(),y.profileQuery());
            }
            long profiles=actual.probes().size();
            // j=-502..502, then one new index at each end through j=-601..601.
            long requests=100L*(502+601+1)*profiles,unique=1203L*profiles;
            assertEquals(100*profiles,v.count(Counter.LOCAL_WINDOWS));
            assertEquals(requests,v.count(Counter.SAMPLE_COMPLETED));assertEquals(unique,v.count(Counter.NEW_COMPLETED));
            assertEquals(requests-unique,v.count(Counter.REPEAT_COMPLETED));
            assertEquals(v.count(Counter.EXTRACTION_STARTED),v.count(Counter.EXTRACTION_COMPLETED));
            assertTrue(v.count(Counter.REPEAT_CELL_WORK)>0);assertTrue(v.count(Counter.REPEAT_NODE_WORK)>0);
            for(var op:EmissionWorkBudget.Operation.values())assertEquals(oldWork.operationUnits(op),v.productionUnits(op),op.name());
            assertTrue(v.diagnosticUnits(EmissionWorkBudget.Operation.PREPARATION)>0);
            assertEquals(1,v.count(Counter.EVALUATION_COMPLETED));assertFalse(v.evaluationInFlight());
        }
    }

    @Test void productionStagesCostsOwnersTiesAndAdjustedWorkRemainExact() {
        try(var a=stageInput(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
            var b=stageInput(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
            var p=LegacyEmissionProgress.open(b.input(),QUERY,EXEC);
            var old=new RidgeTracker().trackEmission(a.input(),CancellationProbe.NONE,QUERY,EXEC);
            var fresh=new RidgeTracker().trackEmission(b.input(),CancellationProbe.NONE,QUERY,EXEC,p)) {
            assertEquals(old.inferenceVertices(),fresh.inferenceVertices());assertEquals(old.smoothedVertices(),fresh.smoothedVertices());
            assertEquals(old.firstStage(),fresh.firstStage());assertEquals(old.intervalStages(),fresh.intervalStages());
            assertEquals(old.stitched(),fresh.stitched());assertEquals(old.postSmoothing(),fresh.postSmoothing());
            assertEquals(old.mapping().observationOwners(),fresh.mapping().observationOwners());
            assertEquals(old.chosenSeed(),fresh.chosenSeed());assertEquals(old.eligibleAnchors(),fresh.eligibleAnchors());
            assertEquals(old.inferenceArcs().stream().map(x->x.optionalReceipt().map(Receipt::reason)).toList(),
                fresh.inferenceArcs().stream().map(x->x.optionalReceipt().map(Receipt::reason)).toList());
            var v=p.view();assertEquals(Termination.SUCCESS,v.termination());
            assertEquals(v.count(Counter.SEEDS_STARTED),v.count(Counter.SEEDS_COMPLETED));
            assertEquals(v.seedsPlanned(),v.count(Counter.SEEDS_COMPLETED));assertTrue(v.firstStageCompleted());
            assertTrue(v.count(Counter.POSITIVE_LOOKUP)>0);assertTrue(v.count(Counter.POSITIVE_HIT)>0);
            assertEquals(v.count(Counter.EVALUATION_STARTED),v.count(Counter.EVALUATION_COMPLETED));
            assertTrue(v.prefixLength()>0);assertTrue(v.milestoneOverflow()>0);
            for(var op:EmissionWorkBudget.Operation.values())assertEquals(old.work().operationUnits().get(op),v.productionUnits(op),op.name());
        }
    }

    @Test void intervalAndStitchCancellationRetainOnlyTheirActualEnteredPhase() {
        for(boolean stitch:new boolean[]{false,true}) {
            var armed=new AtomicBoolean();var seen=new AtomicBoolean();
            LegacyEmissionProgress[] current=new LegacyEmissionProgress[1];
            var work=new EmissionWorkBudget(TraceBudgets.defaults(),()->armed.get()&&!seen.get()&&current[0]!=null
                &&current[0].currentPhase()==(stitch?Phase.STITCH:Phase.INTERVAL)&&current[0].currentFromOrdinal()>=0&&seen.compareAndSet(false,true));
            try(var f=stageInput(work);var p=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)) {
                current[0]=p;
                armed.set(true);
                var failure=assertThrows(CancellationException.class,()->new RidgeTracker().trackEmission(f.input(),CancellationProbe.NONE,QUERY,EXEC,p));
                assertTrue(seen.get());assertEquals(stitch?Phase.STITCH:Phase.INTERVAL,p.view().phase());
                assertTrue(p.view().firstStageCompleted());
                assertTrue(p.view().stage()>0);assertTrue(p.view().intervalRight()>p.view().intervalLeft());
                assertSame(failure,assertThrows(CancellationException.class,f.input()::requireOpenAndUnchanged));
            }
        }
    }
    @Test void completedEvaluationIsNotReportedAsCacheInsertionWhenNextAdmissionFails() {
        var armed=new AtomicBoolean();var seen=new AtomicBoolean();LegacyEmissionProgress[] current=new LegacyEmissionProgress[1];
        var work=new EmissionWorkBudget(TraceBudgets.defaults(),()->armed.get()&&current[0]!=null
            &&current[0].currentCount(Counter.EVALUATION_COMPLETED)>0&&seen.compareAndSet(false,true));
        try(var f=stageInput(work);var p=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)) {
            current[0]=p;armed.set(true);
            assertThrows(CancellationException.class,()->new RidgeTracker().trackEmission(f.input(),CancellationProbe.NONE,QUERY,EXEC,p));
            assertTrue(seen.get());assertEquals(1,p.view().count(Counter.EVALUATION_COMPLETED));assertFalse(p.view().evaluationInFlight());
            assertFalse(p.view().firstStageCompleted());
            assertEquals(1,p.view().count(Counter.POSITIVE_MISS));assertEquals(0,p.view().count(Counter.POSITIVE_INSERT));
        }
    }
    @Test void actualUnavailableKernelEntryHasNoInventedFailureQuantities() {
        try(var f=stageInput(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE),true);
            var p=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)) {
            var failure=assertThrows(LegacyEmissionKernel.UnavailableException.class,()->new RidgeTracker().trackEmission(f.input(),CancellationProbe.NONE,QUERY,EXEC,p));
            assertEquals(Termination.UNAVAILABLE,p.view().termination());assertEquals(failure.reason(),p.view().unavailableReason());
            assertFalse(p.view().hasFailureRequested());assertFalse(p.view().hasFailureUsed());assertFalse(p.view().hasFailureLimit());assertFalse(p.view().hasFailureOriginalOrdinal());
            assertThrows(IllegalStateException.class,()->p.view().failureUsed());
        }
    }
    private static NativeConnectorEvaluatorTest.Straight fractional(EmissionWorkBudget work,EvidenceResolution resolution) {
        return NativeConnectorEvaluatorTest.scene((x,y)->{double u=Math.abs((y-32)*.2);return u==0?1:u<=.2?.5:0;},
            (x,y)->true,MetricRegion.rectangle(0,0,12.6,12.6),SourceClass.OBSERVED,2,.2,
            RasterMetricTransform.metricGrid(new MetricPoint(0,0),.2,0,0,.2),work,.01,resolution,6.2);
    }
    private record StageInput(SampledEmissionInput input,AttemptMemoryLedger.Owner owner,AttemptMemoryLedger ledger) implements AutoCloseable {
        @Override public void close(){input.close();owner.close();assertEquals(0,ledger.currentBytes());}
    }
    private static StageInput stageInput(EmissionWorkBudget work) {
        return stageInput(work,false);
    }
    private static StageInput stageInput(EmissionWorkBudget work,boolean movableBoundary) {
        var f=LegacyEmissionStagesTest.fixture();var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        try(var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(f.evidence(),"stage-ridge",f.input(),f.chainage(),25,work,owner)) {
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(f.evidence(),"stage-ridge",f.input().profileLocations(),25,1,1,work,permit);
            var event=new LegacyEmissionBinding.ProtectedOccurrence("fixed-3-4",3,4,28,new MetricPoint(52,64.5),!movableBoundary);
            var binding=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,List.of(0,1,2,3,4,5,6),1,List.of(event));
            return new StageInput(OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding),owner,ledger);
        }catch(RuntimeException failure){owner.close();throw failure;}
    }
}

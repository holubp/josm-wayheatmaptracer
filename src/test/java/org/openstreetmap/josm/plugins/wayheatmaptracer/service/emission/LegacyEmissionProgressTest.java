package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.LegacyEmissionProgress.*;

class LegacyEmissionProgressTest {
    @Test void exactNestedCensusSeparatesPartialSamplingAndExtraction() {
        try(var f=fixture();var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
            p.localBegin();
            for(int half:new int[]{2,3}) {
                p.windowBegin(ProfileBackend.LOCAL,half,half-2);
                p.windowIdentity(true,1,2,3,4,0,1,.25,8,1);
                for(int j=-half;j<=half;j++){p.sampleBegin(j);p.sampleComplete();}
                p.samplingComplete();p.extractionBegin();p.extractionComplete(1);p.windowComplete();
            }
            p.windowBegin(ProfileBackend.LOCAL,4,2);p.windowIdentity(true,1,2,3,4,0,1,.25,8,1);
            p.sampleBegin(-4);p.sampleComplete();p.sampleBegin(-3);p.sampleComplete();p.sampleBegin(-2);
            var failure=new CancellationException("synthetic");
            var before=f.input().workBudget().snapshot();long bytes=f.ledger().currentBytes();
            p.seal(failure);var v=p.view();
            assertEquals(before,f.input().workBudget().snapshot());assertEquals(bytes,f.ledger().currentBytes());
            assertSame(v,p.view());assertEquals(Termination.CANCELLED,v.termination());
            assertEquals(15,v.count(Counter.SAMPLE_STARTED));assertEquals(14,v.count(Counter.SAMPLE_COMPLETED));
            assertEquals(7,v.count(Counter.REPEAT_STARTED));assertEquals(6,v.count(Counter.REPEAT_COMPLETED));
            assertEquals(2,v.count(Counter.SAMPLING_COMPLETED));assertEquals(2,v.count(Counter.EXTRACTION_COMPLETED));
            assertTrue(v.sampleInFlight());assertFalse(v.samplingFinished());assertFalse(v.extractionInFlight());
        }
    }

    @Test void oneUlpIdentityChangeIsIneligibleNotARepeat() {
        try(var f=fixture();var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
            p.localBegin();p.windowBegin(ProfileBackend.LOCAL,1,0);p.windowIdentity(true,1,2,3,4,0,1,.25,8,1);
            for(int j=-1;j<=1;j++){p.sampleBegin(j);p.sampleComplete();}p.samplingComplete();
            p.windowBegin(ProfileBackend.LOCAL,2,1);p.windowIdentity(true,1,2,3,4,0,1,Math.nextUp(.25),8,1);
            for(int j=-2;j<=2;j++){p.sampleBegin(j);p.sampleComplete();}p.samplingComplete();p.seal(null);
            assertEquals(0,p.view().count(Counter.REPEAT_STARTED));assertEquals(1,p.view().count(Counter.CENSUS_INELIGIBLE));
            assertEquals(16,p.view().censusIneligibilityMask());assertEquals(5,p.view().count(Counter.CENSUS_EXCLUDED_COMPLETED));
        }
    }

    @Test void sourcePointCenterAndSignedZeroNormalDifferencesAreIneligible() {
        for(int changed=0;changed<4;changed++)try(var f=fixture();var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
            p.localBegin();p.windowBegin(ProfileBackend.LOCAL,1,0);p.windowIdentity(true,1,2,3,4,0,1,.25,8,1);
            for(int j=-1;j<=1;j++){p.sampleBegin(j);p.sampleComplete();}p.samplingComplete();
            p.windowBegin(ProfileBackend.LOCAL,2,1);p.windowIdentity(changed!=0,changed==1?Math.nextUp(1.0):1,2,
                changed==2?Math.nextUp(3.0):3,4,changed==3?-0.0:0.0,1,.25,8,1);
            for(int j=-2;j<=2;j++){p.sampleBegin(j);p.sampleComplete();}p.samplingComplete();p.seal(null);
            assertEquals(1<<changed,p.view().censusIneligibilityMask());assertEquals(0,p.view().count(Counter.REPEAT_COMPLETED));
            assertEquals(5,p.view().count(Counter.CENSUS_EXCLUDED_COMPLETED));
        }
    }

    @Test void actualReturnedPreflightAndEarlySpanCannotInventCurrentModeWork() {
        try(var f=fixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE),true);
            var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.EXACT_SLAB_PREFLIGHT_V1);
            var e=NativeConnectorEvaluator.forLegacyProgress(f.input(),new Domain(f.states(),List.of()),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.EXACT_SLAB_PREFLIGHT_V1,p)) {
            assertEquals(Reason.EXACT_ZERO_SLAB_NECESSARY_FAILURE,e.evaluate(f.states().get(0),f.states().get(1)).reason());
            assertEquals(Reason.SPAN,e.evaluate(f.states().get(1),f.states().get(0)).reason());p.seal(null);
            assertEquals(1,p.view().count(Counter.EXACT_ZERO_PROOFS));assertEquals(1,p.view().count(Counter.PREFLIGHT_RECEIPTS));
            assertEquals(2,p.view().count(Counter.EVALUATION_COMPLETED));assertEquals(0,p.view().count(Counter.EXTRACTION_STARTED));
            assertFalse(p.view().samplingFinished());assertFalse(p.view().windowInFlight());assertFalse(p.view().evaluationInFlight());
        }
    }

    @Test void fixedSeedSlotsOverflowAndSealDoesNotChargeAfterStickyFailure() {
        try(var f=fixture();var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
            for(int i=0;i<SEED_SLOTS+3;i++){p.seedBegin(i);p.phase(Phase.BASE,0,-1,-1);p.seedComplete();}
            var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->f.input().workBudget().charge(EmissionWorkBudget.Operation.PREPARATION,Long.MAX_VALUE));
            long used=f.input().workBudget().workUsed();p.seal(failure);
            assertEquals(used,f.input().workBudget().workUsed());assertEquals(3,p.view().seedOverflow());
            assertEquals(SEED_SLOTS+3,p.view().count(Counter.SEEDS_COMPLETED));
            assertEquals(3,p.view().seedStageCount(SEED_SLOTS,Phase.BASE,Counter.SEEDS_COMPLETED));
            assertSame(failure,assertThrows(EmissionWorkBudget.LimitExceededException.class,()->f.input().workBudget().charge(EmissionWorkBudget.Operation.PREPARATION,1)));
        }
    }
    @Test void stageOrdinalHistoryIsFixedAndOverflowRemainsCounted() {
        try(var f=fixture();var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
            long bytes=f.ledger().currentBytes();
            for(int i=0;i<STAGE_SLOTS+3;i++){p.phase(Phase.INTERVAL,i,0,1);p.phaseComplete();}
            p.seal(null);assertEquals(bytes,f.ledger().currentBytes());assertEquals(3,p.view().stageOverflow());
            assertEquals(3,p.view().stageCount(STAGE_SLOTS,Counter.PHASE_COMPLETED));
            assertEquals(STAGE_SLOTS-1,p.view().stageOrdinal(STAGE_SLOTS-1));
        }
    }

    @Test void bufferInitializationFailureHasNoReceiptAndKeepsOriginalStickyException() {
        try(var f=fixture()) {
            long before=f.ledger().currentBytes();
            AttemptMemoryLedger.ResourceLimitException failure;
            try(var held=f.owner().reserve(f.ledger().limitBytes()-before)) {
                failure=assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->LegacyEmissionProgress.open(f.input(),
                    QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1));
            }
            assertEquals(before,f.ledger().currentBytes());
            assertSame(failure,assertThrows(AttemptMemoryLedger.ResourceLimitException.class,f.input()::requireOpenAndUnchanged));
        }
    }

    @Test void prefixAndHistogramsSaturateExplicitlyWithoutAllocatingHistory() {
        try(var f=fixture();var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
            var nativeEvaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=nativeEvaluator.evaluate(f.states().get(0),f.states().get(1));long retained=f.ledger().currentBytes();
            for(int i=0;i<PREFIX_CAPACITY+5;i++){p.evaluationBegin(0,1,0,0,false,0,0,0);p.evaluationComplete(receipt);}
            p.localBegin();p.windowBegin(ProfileBackend.LOCAL,100,50);p.seal(null);
            assertEquals(retained,f.ledger().currentBytes());assertEquals(PREFIX_CAPACITY,p.view().prefixLength());assertEquals(5,p.view().prefixOverflow());
            assertEquals(PREFIX_CAPACITY+5,p.view().count(Counter.EVALUATION_COMPLETED));
            assertTrue(p.view().prefixWorkWord(0,0)<p.view().prefixWorkWord(PREFIX_CAPACITY-1,0));
            assertEquals(0,p.view().prefixWorkWord(0,0)-p.view().prefixWorkWord(0,1)
                -(p.view().prefixWorkWord(PREFIX_CAPACITY-1,0)-p.view().prefixWorkWord(PREFIX_CAPACITY-1,1)),
                "Only diagnostic hooks occur between these actual-receipt bookkeeping checkpoints");
            assertEquals(1,p.view().histogramOverflow());assertEquals(1,p.view().expansionBucket(15));assertEquals(1,p.view().halfIntervalBucket(31));
            assertThrows(IndexOutOfBoundsException.class,()->p.view().prefixWord(PREFIX_CAPACITY,0));
        }
    }

    @Test void stricterCaller32MCeilingAndExhaustedBudgetStillPermitPrepaidSeal() {
        var limits=new TraceBudgets(96,8_000_000,32_000_000,32,8);var work=new EmissionWorkBudget(limits,CancellationProbe.NONE);
        try(var f=fixture(work);var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1)) {
            work.charge(EmissionWorkBudget.Operation.PREPARATION,work.workLimit()-work.workUsed());
            var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->work.charge(EmissionWorkBudget.Operation.PREPARATION,1));
            p.seal(failure);assertEquals(32_000_000,p.view().workLimit());assertEquals(32_000_000,p.view().workUsed());
            assertEquals(8_000_000,p.view().pairLimit());assertEquals(AttemptMemoryLedger.MAX_BYTES,p.view().memoryLimit());
            assertEquals(Termination.TOTAL_WORK,p.view().termination());assertEquals(1,p.view().failureRequested());assertEquals(0,p.view().count(Counter.EVALUATION_STARTED));
        }
    }

    @Test void sampleWorkPairAndCancellationFailuresRemainIncompleteAndSticky() {
        for(int kind=0;kind<3;kind++) {
            final int target=kind;var armed=new java.util.concurrent.atomic.AtomicBoolean();var injected=new java.util.concurrent.atomic.AtomicBoolean();
            LegacyEmissionProgress[] progress=new LegacyEmissionProgress[1];EmissionWorkBudget[] holder=new EmissionWorkBudget[1];
            var work=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
                if(!armed.get()||progress[0]==null||!progress[0].sampleInFlight()||!injected.compareAndSet(false,true))return false;
                if(target==2)return true;
                holder[0].charge(target==0?EmissionWorkBudget.Operation.PREPARATION:EmissionWorkBudget.Operation.PAIR_LOOKUP,
                    target==0?holder[0].workLimit()+1:holder[0].pairLimit()+1);return false;
            });holder[0]=work;
            try(var f=fixture(work);var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
                var e=evaluator(f,p)) {
                progress[0]=p;armed.set(true);
                var failure=assertThrows(RuntimeException.class,()->e.evaluate(f.states().get(0),f.states().get(1)));p.seal(failure);var v=p.view();
                assertTrue(injected.get());assertTrue(v.sampleInFlight());assertFalse(v.samplingFinished());
                assertEquals(1,v.count(Counter.SAMPLE_STARTED));assertEquals(0,v.count(Counter.SAMPLE_COMPLETED));
                assertEquals(0,v.count(Counter.EVALUATION_COMPLETED));assertTrue(v.evaluationInFlight());
                assertEquals(target==0?Termination.TOTAL_WORK:target==1?Termination.PAIR_VISITS:Termination.CANCELLED,v.termination());
                assertSame(failure,assertThrows(RuntimeException.class,()->e.evaluate(f.states().get(0),f.states().get(1))));
            }
        }
    }

    @Test void extractionFailureReportsCompleteSamplingButNoCompletedExtraction() {
        var armed=new java.util.concurrent.atomic.AtomicBoolean();LegacyEmissionProgress[] current=new LegacyEmissionProgress[1];
        var work=new EmissionWorkBudget(TraceBudgets.defaults(),()->armed.get()&&current[0]!=null&&current[0].extractionInFlight());
        try(var f=fixture(work);var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);var e=evaluator(f,p)) {
            current[0]=p;armed.set(true);var failure=assertThrows(CancellationException.class,()->e.evaluate(f.states().get(0),f.states().get(1)));
            p.seal(failure);var v=p.view();assertTrue(v.samplingFinished());assertTrue(v.extractionInFlight());
            assertEquals(v.count(Counter.SAMPLE_STARTED),v.count(Counter.SAMPLE_COMPLETED));assertEquals(1,v.count(Counter.SAMPLING_COMPLETED));
            assertEquals(1,v.count(Counter.EXTRACTION_STARTED));assertEquals(0,v.count(Counter.EXTRACTION_COMPLETED));
        }
    }

    @Test void extractorMemoryWrapperKeepsOriginalExceptionAndActualMemoryCauseFields() {
        var armed=new java.util.concurrent.atomic.AtomicBoolean();var injected=new java.util.concurrent.atomic.AtomicBoolean();
        LegacyEmissionProgress[] current=new LegacyEmissionProgress[1];Fixture[] holder=new Fixture[1];
        var work=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
            if(armed.get()&&current[0]!=null&&current[0].extractionInFlight()&&injected.compareAndSet(false,true))holder[0].owner().reserve(Long.MAX_VALUE);
            return false;
        });
        try(var f=fixture(work);var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);var e=evaluator(f,p)) {
            holder[0]=f;current[0]=p;armed.set(true);var failure=assertThrows(RuntimeException.class,()->e.evaluate(f.states().get(0),f.states().get(1)));
            p.seal(failure);assertTrue(injected.get());assertEquals(Termination.MEMORY,p.view().termination());assertEquals(Long.MAX_VALUE,p.view().failureRequested());
            assertEquals(0,p.view().count(Counter.EXTRACTION_COMPLETED));assertTrue(p.view().samplingFinished());
            assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));
        }
    }

    @Test void memoryFailureAfterReturnedQueryDoesNotUndoItsCompletion() {
        var armed=new java.util.concurrent.atomic.AtomicBoolean();var injected=new java.util.concurrent.atomic.AtomicBoolean();
        LegacyEmissionProgress[] current=new LegacyEmissionProgress[1];Fixture[] holder=new Fixture[1];
        var work=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
            var p=current[0];
            if(armed.get()&&p!=null&&!p.sampleInFlight()&&!p.samplingFinished()&&p.currentCount(Counter.SAMPLE_COMPLETED)>0&&injected.compareAndSet(false,true))
                holder[0].owner().reserve(Long.MAX_VALUE);
            return false;
        });
        try(var f=fixture(work);var p=LegacyEmissionProgress.open(f.input(),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);var e=evaluator(f,p)) {
            holder[0]=f;current[0]=p;armed.set(true);
            var failure=assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->e.evaluate(f.states().get(0),f.states().get(1)));p.seal(failure);
            assertTrue(injected.get());assertEquals(Termination.MEMORY,p.view().termination());
            assertFalse(p.view().sampleInFlight());assertEquals(1,p.view().count(Counter.SAMPLE_COMPLETED));
            assertEquals(0,p.view().count(Counter.SAMPLING_COMPLETED));assertEquals(0,p.view().count(Counter.EXTRACTION_STARTED));
            assertSame(failure,assertThrows(AttemptMemoryLedger.ResourceLimitException.class,f.input()::requireOpenAndUnchanged));
        }
    }
    private static NativeConnectorEvaluator evaluator(Fixture f,LegacyEmissionProgress p) {
        return NativeConnectorEvaluator.forLegacyProgress(f.input(),new Domain(f.states(),List.of()),QueryPolicy.ZERO_GUARDED_LOCAL_V1,ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1,p);
    }

    static record Fixture(SampledEmissionInput input,AttemptMemoryLedger.Owner owner,AttemptMemoryLedger ledger,List<RowState> states) implements AutoCloseable {
        @Override public void close(){input.close();owner.close();assertEquals(0,ledger.currentBytes());}
    }
    static Fixture fixture(){return fixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));}
    static Fixture fixture(EmissionWorkBudget work) {
        return fixture(work,false);
    }
    static Fixture fixture(EmissionWorkBudget work,boolean zero) {
        int size=48;double[] values=new double[size*size];boolean[] valid=new boolean[values.length];Arrays.fill(valid,true);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)values[y*size+x]=zero?0:Math.exp(-.5*Math.pow((y-24)/2,2));
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-.01,-.01),new GeographicPoint(.01,.01));
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var field=new ScalarEvidenceField(size,size,values,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"ridge",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var region=MetricRegion.rectangle(0,0,47,47);var evidence=new EvidenceSnapshot("progress",frame,transform,EvidenceResolution.nativeSource(1,1),region,region,Map.of("ridge",field),"progress");
        var locations=new ArrayList<DetachedProfileSamplingLocation>();
        for(int x:new int[]{12,20}){var m=new MetricPoint(x,24);locations.add(new DetachedProfileSamplingLocation(frame.toGeographic(m),m,new RasterPoint(x,24),frame,transform,x-12));}
        var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        try {return new Fixture(new DetachedScalarProfileSampler().sampleDeclaredSyntheticForEmission(evidence,"ridge",new CorridorTraceInput(locations,1),
            new ProfileChainage(List.of(0.0,8.0),8),8,work,owner),owner,ledger,
            List.of(new RowState(0,0,0,SourceClass.GAP,null),new RowState(1,0,0,SourceClass.GAP,null)));}catch(RuntimeException e){owner.close();throw e;}
    }
}

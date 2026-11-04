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

class LocalNoiseFailureTest {
    enum Boundary { LOCAL_SAMPLE, PROOF_SCAN, PROOF_ALLOCATION, LOCAL_EXTRACTOR, DENSE_SAMPLE, DENSE_EXTRACTOR }
    enum Refusal { WORK, PAIRS, CANCEL, MEMORY }

    @Test void failuresAtActualSampleProofExtractorAndFallbackAreStickyWithoutPartialWinner() {
        for(var boundary:Boundary.values())for(var refusal:Refusal.values())refuse(boundary,refusal);
    }

    private static void refuse(Boundary boundary,Refusal refusal) {
        var armed=new AtomicBoolean();var fired=new AtomicBoolean();var proofCharges=new AtomicInteger();
        LegacyEmissionProgress[] progress={null};EmissionWorkBudget[] budget={null};
        NativeConnectorEvaluatorTest.Straight[] scene={null};AttemptMemoryLedger.Reservation[] held={null};
        CancellationProbe cancel=()->{
            if(!armed.get()||fired.get()||progress[0]==null)return false;
            var p=progress[0];
            boolean candidate=switch(boundary) {
                case LOCAL_SAMPLE -> p.currentCount(Counter.SAMPLE_STARTED)>0;
                case PROOF_SCAN,PROOF_ALLOCATION -> p.currentCount(Counter.SAMPLING_COMPLETED)>0&&p.currentCount(Counter.EXTRACTION_STARTED)==0;
                case LOCAL_EXTRACTOR -> p.currentCount(Counter.EXTRACTION_STARTED)>0;
                case DENSE_SAMPLE -> p.currentCount(Counter.DENSE_FALLBACK)>0&&p.currentCount(Counter.WINDOW_STARTED)>1&&p.currentCount(Counter.SAMPLE_STARTED)>17;
                case DENSE_EXTRACTOR -> p.currentCount(Counter.INITIAL_LOCAL_NOISE_SKIPS)>0&&p.currentCount(Counter.EXTRACTION_STARTED)>0;
            };
            if(!candidate)return false;
            String target=switch(boundary){case PROOF_SCAN,PROOF_ALLOCATION -> "sampledNoiseProof";
                case LOCAL_EXTRACTOR,DENSE_EXTRACTOR -> "robustNoiseFloor";default -> "strictValue";};
            if(!StackWalker.getInstance().walk(s->s.anyMatch(f->f.getMethodName().equals(target))))return false;
            if(boundary==Boundary.PROOF_ALLOCATION&&proofCharges.incrementAndGet()!=19)return false; // 17 samples, rank, allocation
            fired.set(true);
            switch(refusal) {
                case CANCEL -> {return true;}
                case WORK -> budget[0].charge(EmissionWorkBudget.Operation.PREPARATION,budget[0].workLimit()+1);
                case PAIRS -> budget[0].charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,budget[0].pairLimit()+1);
                case MEMORY -> {
                    if(boundary==Boundary.PROOF_ALLOCATION) {
                        // Allow the allocation charge, then refuse the actual 32-byte owned proof reservation.
                        var f=scene[0];held[0]=f.owner().reserve(f.ledger().limitBytes()-f.ledger().currentBytes()-31);
                    } else scene[0].owner().reserve(Long.MAX_VALUE);
                }
            }
            return false;
        };
        budget[0]=new EmissionWorkBudget(TraceBudgets.defaults(),cancel);
        var f=boundary==Boundary.LOCAL_EXTRACTOR
            ?NativeConnectorEvaluatorTest.scene((x,y)->Math.abs(y-32)<=1?1:0,(x,y)->true,MetricRegion.rectangle(0,0,63,63),SourceClass.GAP,
                2,20,RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget[0],.5,null,8)
            :NativeLocalNoisePrecheckTest.positive(budget[0]);
        scene[0]=f;
        try(f;var p=LegacyEmissionProgress.open(f.input(),LocalNoiseControlParityTest.QUERY,LocalNoiseControlParityTest.ON)) {
            progress[0]=p;
            long baseline=f.ledger().currentBytes();
            try(var evaluator=NativeConnectorEvaluator.forLegacyProgress(f.input(),new Domain(f.states(),List.of()),LocalNoiseControlParityTest.QUERY,LocalNoiseControlParityTest.ON,p)) {
                armed.set(true);
                var failure=assertThrows(RuntimeException.class,()->evaluator.evaluate(f.states().get(0),f.states().get(1)),boundary+" "+refusal);
                assertTrue(fired.get(),boundary+" "+refusal);
                assertSame(failure,assertThrows(RuntimeException.class,()->evaluator.evaluate(f.states().get(0),f.states().get(1))));
                assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));
                if(held[0]!=null){held[0].close();held[0]=null;}
                long bytes=f.ledger().currentBytes(),work=budget[0].workUsed();p.seal(failure);
                assertEquals(bytes,f.ledger().currentBytes());assertEquals(work,budget[0].workUsed());
                var v=p.view();assertEquals(0,v.count(Counter.EVALUATION_COMPLETED));assertTrue(v.evaluationInFlight());
                assertEquals(0,v.count(Counter.PROFILE_COMPLETED));
                assertEquals(0,v.count(Counter.EXTRACTION_COMPLETED));
                if(boundary==Boundary.PROOF_SCAN||boundary==Boundary.PROOF_ALLOCATION) {
                    assertTrue(v.samplingFinished());assertFalse(v.extractionInFlight());assertEquals(17,v.count(Counter.SAMPLE_COMPLETED));
                    assertEquals(0,v.count(Counter.INITIAL_LOCAL_NOISE_SKIPS));assertEquals(0,v.count(Counter.DENSE_FALLBACK));
                }
                assertEquals(switch(refusal){case WORK->Termination.TOTAL_WORK;case PAIRS->Termination.PAIR_VISITS;case CANCEL->Termination.CANCELLED;case MEMORY->Termination.MEMORY;},v.termination());
                if(refusal==Refusal.WORK||refusal==Refusal.PAIRS)assertInstanceOf(EmissionWorkBudget.LimitExceededException.class,failure);
                if(refusal==Refusal.CANCEL)assertInstanceOf(CancellationException.class,failure);
                assertEquals(refusal!=Refusal.CANCEL,v.hasFailureRequested());
            }
            assertEquals(baseline,f.ledger().currentBytes(),"local/dense scratch must close on "+boundary+" "+refusal);
        } finally {if(held[0]!=null)held[0].close();}
        assertEquals(0,f.ledger().currentBytes());
    }

    @Test void staleNativeProfilesAreRejectedBeforeAnyProofOrFallback() {
        try(var f=NativeLocalNoisePrecheckTest.positive(LocalNoiseControlParityTest.work());
            var p=LegacyEmissionProgress.open(f.input(),LocalNoiseControlParityTest.QUERY,LocalNoiseControlParityTest.ON);
            var e=NativeConnectorEvaluator.forLegacyProgress(f.input(),new Domain(f.states(),List.of()),LocalNoiseControlParityTest.QUERY,LocalNoiseControlParityTest.ON,p)) {
            var profile=f.input().profiles().levelZeroProfiles().get(0);
            profile.normalScreen().x=.25;
            var failure=assertThrows(IllegalStateException.class,()->e.evaluate(f.states().get(0),f.states().get(1)));
            p.seal(failure);assertEquals(0,p.view().count(Counter.INITIAL_LOCAL_NOISE_SKIPS));
            assertEquals(0,p.view().count(Counter.SAMPLE_STARTED));assertEquals(0,p.view().count(Counter.DENSE_FALLBACK));
        }
    }
}

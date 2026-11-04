package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class ManagedGroupedForwarderOwnershipTest {
    @ParameterizedTest @EnumSource(LegacyGroupedForwarderTest.Refusal.class)
    void revokedPreparationObserverKeepsItsExactFieldsAndOriginalRefusal(LegacyGroupedForwarderTest.Refusal refusal)throws Exception{
        for(boolean sealed:List.of(false,true))try(var f=LegacyGroupedForwarderTest.fixture(false);
                var p=LegacyEmissionProgress.open(f.input(),LegacyGroupedForwarderTest.QUERY,LegacyGroupedForwarderTest.EXEC)){
            var fired=new AtomicBoolean();var frozen=new AtomicReference<LegacyProofProgressOwnershipTest.Snapshot>();var budget=f.input().workBudget();
            CancellationProbe callback=()->{
                if(fired.get()||!StackWalker.getInstance().walk(s->s.anyMatch(frame->frame.getMethodName().equals("suppressLongitudinalNoise"))))return false;
                fired.set(true);if(sealed)p.seal(null);else p.close();
                try{frozen.set(LegacyProofProgressOwnershipTest.snapshot(p,f,f,budget));}catch(Exception failure){throw new AssertionError(failure);}
                switch(refusal){
                    case CANCEL -> {return true;}
                    case WORK -> budget.charge(EmissionWorkBudget.Operation.PREPARATION,budget.workLimit()+1);
                    case PAIRS -> budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,budget.pairLimit()+1);
                    case MEMORY -> f.owner().reserve(Long.MAX_VALUE);
                }return false;
            };
            var primary=assertThrows(RuntimeException.class,()->forward(f.input(),callback,p));assertTrue(fired.get());
            assertSame(primary,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));
            LegacyProofProgressOwnershipTest.assertUnchanged(frozen.get(),LegacyProofProgressOwnershipTest.snapshot(p,f,f,budget));
            if(sealed)assertEquals(LegacyEmissionProgress.Termination.SUCCESS,p.view().termination());else assertThrows(IllegalStateException.class,p::view);
            assertEquals(LegacyEmissionProgress.Phase.NOT_ENTERED,p.currentPhase());assertEquals(0,p.currentCount(LegacyEmissionProgress.Counter.WITNESS_ATTEMPTED));
        }
    }
    @Test void foreignSameBudgetObserverRefusesBeforePreparationWithoutAnyMutation()throws Exception{
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var a=LegacyCompetitiveProofTest.fixture(false,budget);var b=LegacyCompetitiveProofTest.fixture(false,budget);
            var p=LegacyEmissionProgress.open(a.input(),LegacyGroupedForwarderTest.QUERY,LegacyGroupedForwarderTest.EXEC)){
            var frozen=LegacyProofProgressOwnershipTest.snapshot(p,a,b,budget);
            assertThrows(IllegalArgumentException.class,()->forward(b.input(),CancellationProbe.NONE,p));
            LegacyProofProgressOwnershipTest.assertUnchanged(frozen,LegacyProofProgressOwnershipTest.snapshot(p,a,b,budget));
            try(var result=forward(a.input(),CancellationProbe.NONE,p)){assertTrue(result.emission().sourceReference().modelPolicyId().startsWith("LEGACY_GROUPED_MANAGED_LOCAL_CONNECTOR_V1/"));}
        }
    }
    @Test void nonidentityOriginalRowsKeepNativeStagesAndFactualSourceS()throws Exception{
        try(var a=LegacyGroupedForwarderTest.fixture(true);var b=LegacyGroupedForwarderTest.fixture(true);
                var expected=LegacyGroupedForwarderTest.forward(a.input(),CancellationProbe.NONE,null);
                var actual=forward(b.input(),CancellationProbe.NONE,null)){
            LegacyGroupedAllStageTest.assertSameDecisions(expected,actual);
            assertEquals(List.of(1,2,3),actual.emission().originalObservations().stream().map(EmissionResult.OriginalObservation::retainedContextIndex).toList());
            for(var row:actual.emission().originalObservations())assertEquals(b.input().context().profileChainage().sourceOriginGroundMeters()
                +b.input().context().sourceChainageMeters(row.retainedContextIndex()),row.sourceChainageMeters(),0);
        }
    }
    static LegacyEmissionKernel.GroupedResult forward(SampledEmissionInput input,CancellationProbe cancellation,LegacyEmissionProgress progress){
        return new RidgeTracker().trackManagedGroupedEmission(input,cancellation,LegacyGroupedForwarderTest.QUERY,LegacyGroupedForwarderTest.EXEC,
            LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CUTOFF_V1,progress);
    }
}

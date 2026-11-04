package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.LegacyEmissionProgress.*;

class LegacyGroupedFailureTest {
    enum Boundary { WITNESS, FRONT, FRONT_DELETION, BOUND, INTERVAL_ENTRY, STITCH, PUBLICATION }
    enum Refusal { WORK, PAIRS, CANCEL, MEMORY }
    @Test void everyOwnedStagePreservesPrimaryRefusalAndBalancesTheEntireAttempt() throws Exception {
        for(var boundary:Boundary.values())for(var refusal:Refusal.values())refuse(boundary,refusal);
    }
    static void refuse(Boundary boundary,Refusal refusal) throws Exception {
        var armed=new AtomicBoolean();var fired=new AtomicBoolean();
        LegacyEmissionProgress[] progress={null};LegacyCompetitiveProofTest.Fixture[] fixture={null};EmissionWorkBudget[] current={null};
        current[0]=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
            if(!armed.get()||fired.get())return false;
            var p=progress[0];if(p.currentCount(Counter.WITNESS_ATTEMPTED)==0)return false;
            // Gate by existing primitive facts before inspecting a stack; never walk every dense scalar read.
            if(boundary==Boundary.STITCH&&p.currentPhase()!=Phase.STITCH
                    ||boundary==Boundary.INTERVAL_ENTRY&&p.currentPhase()!=Phase.ANCHOR_ELIGIBILITY
                    ||boundary==Boundary.PUBLICATION&&p.currentCount(Counter.SEEDS_COMPLETED)==0
                    ||boundary==Boundary.FRONT_DELETION&&p.currentFromOrdinal()!=2
                    ||boundary==Boundary.BOUND&&p.currentCount(Counter.COMPONENT_BOUND_PREPARATIONS)==0)return false;
            boolean match=StackWalker.getInstance().walk(s->{
                var names=s.map(StackWalker.StackFrame::getMethodName).toList();
                return switch(boundary) {
                    case WITNESS->names.contains("groupedWitness")&&names.contains("ordinary");
                    case FRONT->names.contains("insertExact");
                    case FRONT_DELETION->names.contains("insertExact")&&names.contains("releaseUnretained");
                    case BOUND->names.contains("deferOptional")&&p.currentCount(Counter.COMPONENT_BOUND_PREPARATIONS)>0;
                    case INTERVAL_ENTRY->names.contains("phase")&&p.currentPhase()==Phase.ANCHOR_ELIGIBILITY;
                    case STITCH->names.contains("advance")&&names.contains("solveBody")&&!names.contains("solveGroupedStage")&&!names.contains("groupedWitness");
                    case PUBLICATION->names.contains("solveGrouped")&&!names.contains("solveBody")&&p.currentCount(Counter.SEEDS_COMPLETED)>0;
                };
            });
            if(!match)return false;fired.set(true);
            switch(refusal) {
                case CANCEL->{return true;}
                case WORK->current[0].charge(EmissionWorkBudget.Operation.PREPARATION,current[0].workLimit()+1);
                case PAIRS->current[0].charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,current[0].pairLimit()+1);
                case MEMORY->fixture[0].owner().reserve(Long.MAX_VALUE);
            }
            return false;
        });
        boolean staged=boundary==Boundary.INTERVAL_ENTRY||boundary==Boundary.STITCH;
        try(var f=staged?LegacyGroupedAllStageTest.stageFixture(current[0]):boundary==Boundary.FRONT_DELETION
                ?LegacyGroupedAllStageTest.gapFourFixture(current[0]):LegacyCompetitiveProofTest.fixture(false,current[0]);
            var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            fixture[0]=f;progress[0]=p;var anchors=new ArrayList<LegacyEmissionKernel.AnchorProposal>();
            if(staged)for(int row:List.of(0,2,4,6))anchors.add(new LegacyEmissionKernel.AnchorProposal(row,f.profiles().get(row).peaks().get(0)));
            long baseline=f.ledger().currentBytes();armed.set(true);
            var failure=assertThrows(RuntimeException.class,()->LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),List.of(0.0),anchors,(a,b,c)->b,"EXACT_Q0_CUTOFF_V1",p),boundary+" "+refusal);
            assertTrue(fired.get(),boundary+" "+refusal);assertEquals(baseline,f.ledger().currentBytes(),boundary+" "+refusal);
            assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));
            assertSame(failure,assertThrows(RuntimeException.class,()->LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),List.of(0.0),anchors,(a,b,c)->b,"EXACT_Q0_CUTOFF_V1",null)));
            assertEquals(switch(refusal){case WORK->Termination.TOTAL_WORK;case PAIRS->Termination.PAIR_VISITS;case CANCEL->Termination.CANCELLED;case MEMORY->Termination.MEMORY;},p.view().termination());
            if(boundary==Boundary.INTERVAL_ENTRY||boundary==Boundary.WITNESS)assertFalse(p.view().hasWitnessUpper());
            if(boundary==Boundary.BOUND)assertEquals(0,p.view().count(Counter.STRICT_CERTIFICATE_DEFERRALS));
            long work=current[0].workUsed();p.seal(failure);assertEquals(work,current[0].workUsed());assertEquals(baseline,f.ledger().currentBytes());
        }
    }

    @Test void foreignSameBudgetInputCannotConsumeTheNewEntryObserverAndOwnerCanStillFinish() throws Exception {
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var a=LegacyCompetitiveProofTest.fixture(false,budget);var b=LegacyCompetitiveProofTest.fixture(false,budget);
            var p=LegacyEmissionProgress.open(a.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            var before=LegacyProofProgressOwnershipTest.snapshot(p,a,b,budget);
            for(int repeat=0;repeat<3;repeat++) {
                assertThrows(IllegalArgumentException.class,()->LegacyGroupedAllStageTest.solve(b.input(),b.profiles(),List.of(0.0),List.of(),(x,y,z)->y,"EXACT_Q0_CUTOFF_V1",p));
                LegacyProofProgressOwnershipTest.assertUnchanged(before,LegacyProofProgressOwnershipTest.snapshot(p,a,b,budget));
            }
            var result=LegacyGroupedAllStageTest.solve(a.input(),a.profiles(),List.of(0.0),List.of(),(x,y,z)->y,"EXACT_Q0_CUTOFF_V1",p);
            a.input().close();assertFalse(result.emission().inferenceVertices().isEmpty());assertFalse(result.stages().isEmpty());
            result.close();result.close();assertThrows(IllegalStateException.class,result::stages);assertThrows(IllegalStateException.class,result::emission);
            assertEquals(Termination.SUCCESS,p.view().termination());
        }
    }

    @Test void sourceMutationAtDeferralOrFinalPublicationNeverPublishesAResultOrFalseCount() throws Exception {
        for(boolean atPublication:List.of(false,true)) {
            var armed=new AtomicBoolean();var fired=new AtomicBoolean();LegacyEmissionProgress[] progress={null};LegacyCompetitiveProofTest.Fixture[] fixture={null};
            var budget=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
                if(!armed.get()||fired.get()||progress[0].currentCount(Counter.WITNESS_ATTEMPTED)==0)return false;
                if(atPublication&&progress[0].currentCount(Counter.SEEDS_COMPLETED)==0
                        ||!atPublication&&progress[0].currentCount(Counter.COMPONENT_BOUND_PREPARATIONS)==0)return false;
                boolean match=StackWalker.getInstance().walk(s->{var names=s.map(StackWalker.StackFrame::getMethodName).toList();
                    return atPublication?names.contains("solveGrouped")&&!names.contains("solveBody")&&progress[0].currentCount(Counter.SEEDS_COMPLETED)>0
                        :names.contains("deferOptional")&&names.contains("pay")&&progress[0].currentCount(Counter.COMPONENT_BOUND_PREPARATIONS)>0;});
                if(match){fired.set(true);fixture[0].profiles().get(0).normalScreen().x=.25;}return false;
            });
            try(var f=LegacyCompetitiveProofTest.fixture(false,budget);var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
                fixture[0]=f;progress[0]=p;long baseline=f.ledger().currentBytes();armed.set(true);
                assertThrows(IllegalStateException.class,()->LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),List.of(0.0),List.of(),(a,b,c)->b,"EXACT_Q0_CUTOFF_V1",p));
                assertTrue(fired.get());assertEquals(baseline,f.ledger().currentBytes());
                if(!atPublication)assertEquals(0,p.view().count(Counter.STRICT_CERTIFICATE_DEFERRALS));
            }
        }
    }

    @Test void nextSeedEntryClearsPreviousUpperBeforeItsFirstFailingCharge() throws Exception {
        var armed=new AtomicBoolean();var fired=new AtomicBoolean();LegacyEmissionProgress[] progress={null};EmissionWorkBudget[] budget={null};
        budget[0]=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
            if(!armed.get()||fired.get()||progress[0].currentCount(Counter.SEEDS_COMPLETED)!=1)return false;
            boolean match=StackWalker.getInstance().walk(s->s.anyMatch(frame->frame.getMethodName().equals("seedBegin")));
            if(match){fired.set(true);budget[0].charge(EmissionWorkBudget.Operation.PREPARATION,budget[0].workLimit()+1);}return false;
        });
        try(var f=LegacyCompetitiveProofTest.fixture(false,budget[0]);var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            progress[0]=p;armed.set(true);long baseline=f.ledger().currentBytes();
            var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),List.of(0.0,.25),List.of(),(a,b,c)->b,"EXACT_Q0_CUTOFF_V1",p));
            assertTrue(fired.get());assertEquals(1,p.view().count(Counter.WITNESS_CERTIFIED));assertFalse(p.view().hasWitnessUpper());
            assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));assertEquals(baseline,f.ledger().currentBytes());
        }
    }

    @Test void realWitnessCarryAdmissionsRespectTheSameCallerFamilyCeiling() throws Exception {
        var limits=TraceBudgets.defaults();
        var budget=new EmissionWorkBudget(new TraceBudgets(1,limits.maximumPairVisits(),limits.maximumTransitions(),limits.maximumRawAlternatives(),limits.maximumDistinctAlternatives()),CancellationProbe.NONE);
        try(var f=LegacyGroupedAllStageTest.stageFixture(budget);var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            long baseline=f.ledger().currentBytes();
            var failure=assertThrows(LegacyEmissionDomain.StateFamilyLimitException.class,()->LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),List.of(0.0),List.of(),(a,b,c)->b,"EXACT_Q0_CUTOFF_V1",p));
            assertEquals(3,failure.originalRow());assertEquals(2,failure.familySize());assertEquals(1,failure.limit());
            assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));assertEquals(baseline,f.ledger().currentBytes());
            assertEquals(Termination.STATE_FAMILY,p.view().termination());
        }
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.LegacyEmissionProgress.*;

class LegacyCompetitiveFailureTest {
    @Test void sourceChangedAtFinalPrepaidDeferralHookCannotPublishAFalseDeferral() {
        var armed=new AtomicBoolean();var seen=new AtomicBoolean();
        LegacyEmissionProgress[] progress={null};LegacyCompetitiveProofTest.Fixture[] fixture={null};
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
            if(armed.get()&&!seen.get()&&progress[0].currentCount(Counter.COMPONENT_BOUND_PREPARATIONS)>0
                    &&StackWalker.getInstance().walk(s->{var names=s.map(StackWalker.StackFrame::getMethodName).toList();
                        return names.contains("deferOptional")&&names.contains("pay");})) {
                seen.set(true);fixture[0].profiles().get(0).normalScreen().x=.25;
            }
            return false;
        });
        try(var f=LegacyCompetitiveProofTest.fixture(false,budget);
            var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            fixture[0]=f;progress[0]=p;armed.set(true);long baseline=f.ledger().currentBytes();
            assertThrows(IllegalStateException.class,()->LegacyCompetitiveProofTest.solve(f,"EXACT_CUTOFF_PROOF_V1",p));
            assertTrue(seen.get());assertEquals(0,p.view().count(Counter.STRICT_CERTIFICATE_DEFERRALS));
            assertEquals(baseline,f.ledger().currentBytes());assertFalse(p.view().firstStageCompleted());
        }
    }

    enum Boundary { WITNESS, FRONT, BOUND, PUBLICATION }
    enum Refusal { WORK, PAIRS, CANCEL, MEMORY }
    @Test void allOwnedPhasesPreservePrimaryTerminalRefusalAndZeroCleanup() {
        for(var boundary:Boundary.values())for(var refusal:Refusal.values())refuse(boundary,refusal);
    }
    static void refuse(Boundary boundary,Refusal refusal) {
        var armed=new AtomicBoolean();var fired=new AtomicBoolean();
        LegacyEmissionProgress[] progress={null};LegacyCompetitiveProofTest.Fixture[] fixture={null};EmissionWorkBudget[] current={null};
        current[0]=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
            if(!armed.get()||fired.get())return false;
            var p=progress[0];boolean candidate=boundary==Boundary.WITNESS?p.currentCount(Counter.WITNESS_ATTEMPTED)>0&&p.currentCount(Counter.WITNESS_CERTIFIED)==0
                :p.currentCount(Counter.WITNESS_CERTIFIED)>0;
            if(!candidate)return false;
            String target=switch(boundary){case WITNESS->"ordinary";case FRONT->"insertExact";case BOUND->"deferOptional";case PUBLICATION->"flatten";};
            boolean match=StackWalker.getInstance().walk(s->{var names=s.map(StackWalker.StackFrame::getMethodName).toList();
                return names.contains(target)&&(boundary!=Boundary.PUBLICATION||!names.contains("solveExactObservedBase"));});
            if(!match||boundary==Boundary.BOUND&&p.currentCount(Counter.COMPONENT_BOUND_PREPARATIONS)==0)return false;
            fired.set(true);
            switch(refusal) {
                case CANCEL->{return true;}
                case WORK->current[0].charge(EmissionWorkBudget.Operation.PREPARATION,current[0].workLimit()+1);
                case PAIRS->current[0].charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,current[0].pairLimit()+1);
                case MEMORY->fixture[0].owner().reserve(Long.MAX_VALUE);
            }
            return false;
        });
        try(var f=LegacyCompetitiveProofTest.fixture(false,current[0]);
            var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            fixture[0]=f;progress[0]=p;long baseline=f.ledger().currentBytes();armed.set(true);
            var failure=assertThrows(RuntimeException.class,()->LegacyCompetitiveProofTest.solve(f,"EXACT_CUTOFF_PROOF_V1",p),boundary+" "+refusal);
            assertTrue(fired.get(),boundary+" "+refusal);
            assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));
            assertSame(failure,assertThrows(RuntimeException.class,()->LegacyCompetitiveProofTest.solve(f,"EXACT_CUTOFF_PROOF_V1",null)));
            assertFalse(p.view().firstStageCompleted());assertEquals(0,p.view().count(Counter.SEEDS_COMPLETED));
            assertEquals(baseline,f.ledger().currentBytes(),boundary+" "+refusal);
            assertEquals(switch(refusal){case WORK->Termination.TOTAL_WORK;case PAIRS->Termination.PAIR_VISITS;case CANCEL->Termination.CANCELLED;case MEMORY->Termination.MEMORY;},p.view().termination());
            if(boundary==Boundary.WITNESS){assertFalse(p.view().hasWitnessUpper());assertEquals(0,p.view().count(Counter.EVALUATION_STARTED));}
            if(boundary==Boundary.BOUND)assertEquals(0,p.view().count(Counter.STRICT_CERTIFICATE_DEFERRALS));
            long used=current[0].workUsed(),bytes=f.ledger().currentBytes();p.seal(failure);
            assertEquals(used,current[0].workUsed());assertEquals(bytes,f.ledger().currentBytes());
        }
    }
}

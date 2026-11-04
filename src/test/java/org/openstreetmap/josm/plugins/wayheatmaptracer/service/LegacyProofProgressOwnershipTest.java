package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class LegacyProofProgressOwnershipTest {
    static final String MODE="EXACT_CUTOFF_PROOF_V1";

    @Test void foreignSameBudgetInputCannotMutateSealOrConsumeOwnersObserver() throws Exception {
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var a=LegacyCompetitiveProofTest.fixture(false,budget);var b=LegacyCompetitiveProofTest.fixture(false,budget);
            var progress=LegacyEmissionProgress.open(a.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            var initial=snapshot(progress,a,b,budget);
            for(int repeat=0;repeat<3;repeat++) {
                assertThrows(IllegalArgumentException.class,()->{
                    try(var unexpected=LegacyCompetitiveProofTest.solve(b,MODE,progress)){ /* Must not enter or publish B. */ }
                });
                assertUnchanged(initial,snapshot(progress,a,b,budget));
                assertThrows(IllegalStateException.class,progress::view,"Foreign rejection must not make a terminal view available");
            }
            try(var owned=LegacyCompetitiveProofTest.solve(a,MODE,progress)) {
                assertTrue(owned.witnessCosts().isPresent());assertTrue(progress.view().firstStageCompleted());
                assertEquals(LegacyEmissionProgress.Termination.SUCCESS,progress.view().termination());
                assertNull(field(progress,"boundInput"),"Sealed diagnostics must release the live Input binding");
            }
            var sealed=snapshot(progress,a,b,budget);
            for(var input:List.of(a,b)) {
                assertThrows(IllegalArgumentException.class,()->LegacyCompetitiveProofTest.solve(input,MODE,progress));
                assertUnchanged(sealed,snapshot(progress,a,b,budget));
            }
        }
    }

    @Test void realOwnedBeginFailureSealsItsPrimaryAndLaterRejectionsStayNonmutating() throws Exception {
        var armed=new AtomicBoolean();var budget=new EmissionWorkBudget(TraceBudgets.defaults(),armed::get);
        try(var a=LegacyCompetitiveProofTest.fixture(false,budget);var b=LegacyCompetitiveProofTest.fixture(false,budget);
            var progress=LegacyEmissionProgress.open(a.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            armed.set(true);
            var failure=assertThrows(CancellationException.class,()->LegacyCompetitiveProofTest.solve(a,MODE,progress));
            assertEquals(LegacyEmissionProgress.Termination.CANCELLED,progress.view().termination());
            assertFalse(progress.view().hasCompetitiveExecution());assertFalse(progress.view().hasWitnessUpper());
            assertFalse(progress.view().hasFailureRequested());assertFalse(progress.view().hasFailureUsed());assertFalse(progress.view().hasFailureLimit());
            assertSame(failure,assertThrows(CancellationException.class,a.input()::requireOpenAndUnchanged));
            assertNull(field(progress,"boundInput"));var terminal=snapshot(progress,a,b,budget);
            for(var input:List.of(b,a)) {
                assertThrows(IllegalArgumentException.class,()->LegacyCompetitiveProofTest.solve(input,MODE,progress));
                assertUnchanged(terminal,snapshot(progress,a,b,budget));
            }
            assertSame(failure,assertThrows(CancellationException.class,()->LegacyCompetitiveProofTest.solve(a,MODE,null)));
        }
    }

    @Test void closedObserverRejectionDoesNotInventATerminalReceipt() throws Exception {
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var a=LegacyCompetitiveProofTest.fixture(false,budget);var b=LegacyCompetitiveProofTest.fixture(false,budget);
            var progress=LegacyEmissionProgress.open(a.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            progress.close();assertNull(field(progress,"boundInput"));var closed=snapshot(progress,a,b,budget);
            for(var input:List.of(b,a)) {
                assertThrows(IllegalArgumentException.class,()->LegacyCompetitiveProofTest.solve(input,MODE,progress));
                assertUnchanged(closed,snapshot(progress,a,b,budget));assertThrows(IllegalStateException.class,progress::view);
            }
        }
    }

    record Snapshot(Map<String,Object> facts,EmissionWorkBudget.Snapshot work,long aCurrent,long aPeak,long bCurrent,long bPeak) { }
    static Snapshot snapshot(LegacyEmissionProgress progress,LegacyCompetitiveProofTest.Fixture a,
            LegacyCompetitiveProofTest.Fixture b,EmissionWorkBudget budget) throws Exception {
        var facts=new LinkedHashMap<String,Object>();
        for(var f:LegacyEmissionProgress.class.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())) {
            f.setAccessible(true);Object value=f.get(progress);
            if(value instanceof long[] array)value=array.clone();else if(value instanceof int[] array)value=array.clone();
            facts.put(f.getName(),value);
        }
        return new Snapshot(facts,budget.snapshot(),a.ledger().currentBytes(),a.ledger().peakBytes(),b.ledger().currentBytes(),b.ledger().peakBytes());
    }
    static void assertUnchanged(Snapshot before,Snapshot after) {
        assertEquals(before.work(),after.work());assertEquals(before.aCurrent(),after.aCurrent());assertEquals(before.aPeak(),after.aPeak());
        assertEquals(before.bCurrent(),after.bCurrent());assertEquals(before.bPeak(),after.bPeak());
        assertEquals(before.facts().keySet(),after.facts().keySet());
        before.facts().forEach((name,value)->{
            Object fresh=after.facts().get(name);
            if(value instanceof long[] array)assertArrayEquals(array,(long[])fresh,name);
            else if(value instanceof int[] array)assertArrayEquals(array,(int[])fresh,name);
            else assertEquals(value,fresh,name);
        });
    }
    static Object field(Object object,String name) throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
}

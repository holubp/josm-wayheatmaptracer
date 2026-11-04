package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class NativeAExecutionCensusTest {
    @Test void aForeignSameBudgetInputCannotMutateTheCarrierAndItsRealOwnerCanStillExecute() throws Exception {
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var owner=new NativeAEmissionSolverTest.Fixture(3,budget);var foreign=new NativeAEmissionSolverTest.Fixture(3,budget);
                var census=NativeAExecutionCensus.open(owner.input)) {
            var before=facts(census);var work=budget.snapshot();
            long ownerBytes=owner.ledger.currentBytes(),ownerPeak=owner.ledger.peakBytes();
            long foreignBytes=foreign.ledger.currentBytes(),foreignPeak=foreign.ledger.peakBytes();
            assertThrows(IllegalArgumentException.class,()->track(foreign.input,census));
            assertEquals(before,facts(census));assertEquals(work,budget.snapshot());
            assertEquals(ownerBytes,owner.ledger.currentBytes());assertEquals(ownerPeak,owner.ledger.peakBytes());
            assertEquals(foreignBytes,foreign.ledger.currentBytes());assertEquals(foreignPeak,foreign.ledger.peakBytes());
            assertSame(owner.input,liveInput(census));
            try(var result=track(owner.input,census)) {
                assertFalse(result.results().isEmpty());assertTrue(census.finished());assertNull(census.failure());
                assertNull(liveInput(census));
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"active","successful","failed","closed"})
    void repeatedAndClosedRequestsCannotModifyTheExistingOwnersFrozenFacts(String state) throws Exception {
        try(var fixture=new NativeAEmissionSolverTest.Fixture(3);var census=NativeAExecutionCensus.open(fixture.input)) {
            if(state.equals("active"))census.begin(fixture.input);
            if(state.equals("successful"))try(var result=track(fixture.input,census)){assertFalse(result.results().isEmpty());}
            if(state.equals("failed")){census.begin(fixture.input);census.fail(new IllegalStateException("original owned failure"));}
            if(state.equals("closed"))census.close();
            var before=facts(census);var work=fixture.input.workBudget().snapshot();
            long current=fixture.ledger.currentBytes(),peak=fixture.ledger.peakBytes();
            assertThrows(IllegalArgumentException.class,()->track(fixture.input,census));
            assertEquals(before,facts(census));assertEquals(work,fixture.input.workBudget().snapshot());
            assertEquals(current,fixture.ledger.currentBytes());assertEquals(peak,fixture.ledger.peakBytes());
            if(state.equals("active")){assertDoesNotThrow(()->census.requireActive(fixture.input));assertSame(fixture.input,liveInput(census));}
        }
    }

    @ParameterizedTest @ValueSource(strings={"finish","fail","early-close"})
    void exactInputGuardLivesOnlyUntilTerminalOrCloseAndEveryExportGetterIsSourceFree(String terminal) throws Exception {
        var cancelled=new AtomicBoolean();
        try(var fixture=new NativeAEmissionSolverTest.Fixture(3,new EmissionWorkBudget(TraceBudgets.defaults(),cancelled::get));
                var census=NativeAExecutionCensus.open(fixture.input)) {
            assertSame(fixture.input,liveInput(census));census.begin(fixture.input);
            census.beginEngine();assertDoesNotThrow(()->census.requireActive(fixture.input));
            census.frontier(3,4,true,2,1);census.beginEngine();
            assertSame(fixture.input,liveInput(census));assertDoesNotThrow(()->census.requireActive(fixture.input));
            var failure=new IllegalStateException("owned failure");cancelled.set(true);
            if(terminal.equals("finish"))census.finish();
            else if(terminal.equals("fail"))census.fail(failure);
            else census.close();
            assertNull(liveInput(census));
            assertThrows(IllegalArgumentException.class,()->census.requireActive(fixture.input));
            if(terminal.equals("fail"))assertSame(failure,census.failure());
            var exported=facts(census);assertEquals(exported,facts(census));
            fixture.input.close();fixture.owner.close();census.close();
            assertNull(liveInput(census));assertEquals(0,census.currentBytes());
            assertDoesNotThrow(()->facts(census));
        }
    }

    @ParameterizedTest @ValueSource(strings={"cancel","work"})
    void theRealOwnersBeginFailureIsStillSealedWithTheOriginalStickyFailure(String kind) throws Exception {
        var cancelled=new AtomicBoolean();var budget=new EmissionWorkBudget(TraceBudgets.defaults(),cancelled::get);
        try(var fixture=new NativeAEmissionSolverTest.Fixture(3,budget);var census=NativeAExecutionCensus.open(fixture.input)) {
            if(kind.equals("cancel"))cancelled.set(true);
            else budget.charge(EmissionWorkBudget.Operation.PREPARATION,budget.snapshot().workLimit()-budget.snapshot().workUsed());
            long before=fixture.ledger.currentBytes();
            var failure=assertThrows(RuntimeException.class,()->track(fixture.input,census));
            if(kind.equals("cancel"))assertInstanceOf(java.util.concurrent.CancellationException.class,failure);
            else assertInstanceOf(EmissionWorkBudget.LimitExceededException.class,failure);
            assertSame(failure,census.failure());assertTrue(census.finished());assertNull(liveInput(census));
            assertSame(failure,assertThrows(RuntimeException.class,fixture.input::requireRetainedSourceUnchanged));
            assertEquals(budget.snapshot().workUsed(),census.workUsed());assertEquals(budget.snapshot().pairVisits(),census.pairVisits());
            assertEquals(before,fixture.ledger.currentBytes());assertDoesNotThrow(()->facts(census));
        }
    }

    private static CorridorAwareTracker.OptionalEmissionTracking track(SampledEmissionInput input,NativeAExecutionCensus census) {
        return new CorridorAwareTracker().trackOptionalEmissionWithScalarRefinement(input,null,JunctionContext.empty(),List.of(),
            GeometryCleanupConfig.disabled(),QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EAGER_ALL_PROBES_V1,census);
    }
    private static Object liveInput(NativeAExecutionCensus census) throws Exception {
        var field=NativeAExecutionCensus.class.getDeclaredField("input");field.setAccessible(true);return field.get(census);
    }
    private static Map<String,Object> facts(NativeAExecutionCensus census) throws Exception {
        var facts=new TreeMap<String,Object>();
        for(var method:NativeAExecutionCensus.class.getDeclaredMethods())
            if(java.lang.reflect.Modifier.isPublic(method.getModifiers())&&method.getParameterCount()==0&&method.getReturnType()!=void.class)
                facts.put(method.getName(),method.invoke(census));
        return facts;
    }
}

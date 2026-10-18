package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/** Original-case metadata with a wholly dummy evaluator: never opens a scene. */
class V022BProspectiveScheduleTest {
    private static final String PREFIX="sparse-crossing-noisy-strands-vs-persistent-parallel/train/";
    private static final List<String> FIRST=List.of(PREFIX+"v0/right",PREFIX+"v4/right");

    @Test void fixedPrefixRunsBeforeOldRemainderButAll32ScoresKeepOriginalOrder() throws Exception {
        var f=fixture(32);
        for(var parameter:f.parameters)f.geometryFailure.put(parameter,FIRST.get(1));
        var result=run(f);
        assertTrue(result.shortlist().isEmpty());
        assertEquals(32,result.scores().size());
        assertEquals(f.cases.stream().map(d->"score:"+d.key().id()).toList(),f.events.subList(0,f.cases.size()));
        for(var parameter:f.parameters) {
            assertEquals(FIRST,f.visitedDefinitions.get(parameter).stream().map(d->d.key().id()).toList());
            assertEquals(f.cases.size()-2,result.evidence().get(parameter).skippedGeometryScenes());
            assertTrue(result.evidence().get(parameter).skippedRequiredPairs()>0);
        }
    }

    @Test void missingDuplicateOrModifiedPrefixRefusesBeforeEvenDummyScoring() {
        for(int fault=0;fault<3;fault++) {
            var f=fixture(6);var changed=new ArrayList<>(f.cases);
            var first=changed.stream().filter(d->d.key().id().equals(FIRST.get(0))).findFirst().orElseThrow();
            if(fault==0)changed.remove(first);
            if(fault==1)changed.add(first);
            if(fault==2) {changed.remove(first);changed.add(new BIdentificationCatalog.CaseDefinition(first.key(),
                    first.contrastId(),first.side(),first.variantId(),first.phase(),first.sourcePitchMeters()+0.01,
                    first.oversampling(),first.reversed(),first.equation(),first.capabilities()));}
            f.cases=changed;
            assertThrows(IllegalArgumentException.class,()->run(f));
            assertTrue(f.events.isEmpty(),"bad full permutation must fail before scoring/acquisition");
        }
    }

    @Test void prefixPassStillRequiresEveryRemainingCaseAndCoupledControlBeforeTopFour() throws Exception {
        var f=fixture(6);var result=run(f);
        assertEquals(f.parameters.subList(0,4),result.shortlist());
        for(var p:result.shortlist()) {
            var visited=f.visitedDefinitions.get(p);
            assertEquals(FIRST,visited.subList(0,2).stream().map(d->d.key().id()).toList());
            assertEquals(112,visited.size());assertEquals(new HashSet<>(f.cases),new HashSet<>(visited));
            assertEquals(0,result.evidence().get(p).skippedGeometryScenes());
            assertEquals(0,result.evidence().get(p).skippedRequiredPairs());
        }
        assertEquals(result.shortlist(),f.coupledCalls);
        for(var p:f.parameters.subList(4,6))assertEquals(BObservationIdentificationTool.TrainState.UNTESTED,result.evidence().get(p).state());
    }

    @Test void laterGeometryOrCoupledFailureCannotUseItsPassingPrefixAsQualification() throws Exception {
        var f=fixture(6);
        String last=BObservationIdentificationTool.trainGeometryOrder(f.cases,false,false).stream()
                .filter(d->!FIRST.contains(d.key().id())).reduce((a,b)->b).orElseThrow().key().id();
        f.geometryFailure.put(f.parameters.get(0),last);f.coupledFailure.add(f.parameters.get(1));
        var result=run(f);assertEquals(f.parameters.subList(2,6),result.shortlist());
        assertEquals(112,f.geometryCount(f.parameters.get(0)));assertFalse(f.coupledCalls.contains(f.parameters.get(0)));
        assertTrue(f.coupledCalls.contains(f.parameters.get(1)));
    }

    @Test void earlyOrdinaryFailureLeavesLaterErrorsUnknownAndFewerThanFourExhaustsEligibleRanks() throws Exception {
        for(int successes:new int[]{0,2}) {
            var f=fixture(6);
            for(var p:f.parameters.subList(successes,6))f.geometryFailure.put(p,FIRST.get(0));
            var result=run(f);assertEquals(f.parameters.subList(0,successes),result.shortlist());
            for(var p:f.parameters)assertNotEquals(BObservationIdentificationTool.TrainState.UNTESTED,result.evidence().get(p).state());
        }
        var f=fixture(6);int[] laterError={0};
        for(var p:f.parameters)f.geometryFailure.put(p,FIRST.get(0));
        f.onGeometry=()->{if(f.events.get(f.events.size()-1).endsWith(FIRST.get(1))) {laterError[0]++;throw new IllegalStateException("late error");}};
        var result=run(f);assertTrue(result.shortlist().isEmpty());assertEquals(0,laterError[0]);
        assertEquals(6,f.totalGeometry());
        assertTrue(result.evidence().values().stream().allMatch(e->e.skippedGeometryScenes()==111));
    }

    @Test void scoreTiesCheapFailuresAndNonfiniteScoresRetainTheSameParameterOrder() throws Exception {
        var f=fixture(8);for(var p:f.parameters)f.loss.put(p,0.0);
        f.cheapFailure.add(f.parameters.get(0));f.unavailableCheap.add(f.parameters.get(1));
        f.loss.put(f.parameters.get(2),Double.POSITIVE_INFINITY);
        var result=run(f);assertEquals(f.parameters.subList(3,7),result.shortlist());
        for(var p:f.parameters.subList(0,3))assertEquals(0,f.geometryCount(p));
    }

    @Test void encounteredExceptionalFailuresAndMissingCoupledCacheAbortGlobally() {
        for(String cause:List.of("MEMORY","NUMERIC_FAILURE","SOURCE_BINDING","TRANSITION_BUDGET","CACHE_HASH")) {
            var f=fixture(6);var error=new BObservationIdentificationTool.RunLimitException(cause);
            f.encounteredFailure.put(f.parameters.get(0),error);
            assertSame(error,assertThrows(BObservationIdentificationTool.RunLimitException.class,()->run(f)));
            assertEquals(List.of(f.parameters.get(0)),f.geometryOrder.stream().distinct().toList());assertEquals(0,f.liveReconstructions);
        }
        var f=fixture(6);var missing=new IllegalStateException("missing required coupled cache");
        f.coupledEncountered.put(f.parameters.get(0),missing);
        assertSame(missing,assertThrows(IllegalStateException.class,()->run(f)));
        assertEquals(List.of(f.parameters.get(0)),f.geometryOrder.stream().distinct().toList());
    }

    @Test void staleInputsAndMissingInitialBindingsRefuseBeforeGeometry() {
        for(String field:List.of("source","renderer","catalog","recipe","native","frame","capability","facts","lattice")) {
            var f=fixture(6);f.bindingMismatch=field;
            assertThrows(IllegalArgumentException.class,()->run(f));assertEquals(0,f.totalGeometry());assertEquals(0,f.liveReconstructions);
        }
        var f=fixture(6);f.missingBinding=true;
        assertThrows(IllegalArgumentException.class,()->run(f));assertEquals(0,f.totalGeometry());
    }

    @Test void cancellationAndTimeoutAfterScoringCannotResetTheOriginalClockOrAdvanceRanks() {
        var cancelled=fixture(6);boolean[] stop={false};cancelled.cancellation=()->stop[0];cancelled.afterAllScores=()->stop[0]=true;
        assertThrows(CancellationException.class,()->run(cancelled));assertEquals(0,cancelled.totalGeometry());
        var f=fixture(6);var clock=new AtomicLong(0);
        var controls=new BObservationIdentificationTool.Controls(null,67_108_864L,100,clock::get,CancellationProbe.NONE,()->{});
        f.cancellation=BObservationIdentificationTool.guarded(controls,0);clock.set(25);
        f.afterAllScores=()->clock.set(100);
        assertEquals("TIME",assertThrows(BObservationIdentificationTool.RunLimitException.class,()->run(f)).reason);
        assertEquals(0,f.totalGeometry());
    }

    private static V022BIdentificationRunnerTest.TrainFixture fixture(int parameters) {
        var f=new V022BIdentificationRunnerTest.TrainFixture(parameters);
        f.cases=BIdentificationCatalog.trainDiagnosticCatalog().definitions().stream()
                .filter(d->d.key().split()==BIdentificationCatalog.Split.TRAIN).toList();
        return f;
    }
    private static BObservationIdentificationTool.TrainPass run(V022BIdentificationRunnerTest.TrainFixture f) throws Exception {
        var evaluator=new BObservationIdentificationTool.TrainEvaluator() {
            public BObservationIdentificationTool.TrainSceneScore score(BIdentificationCatalog.CaseDefinition d,List<BIdentifiedObservationKernel.Parameters> p) {
                return f.score(d,p);
            }
            public BObservationIdentificationTool.TrainReconstruction reconstruct(BIdentificationCatalog.CaseDefinition d,
                    BIdentifiedObservationKernel.Parameters p,BObservationIdentificationTool.TrainInputBinding binding) throws Exception {
                return f.reconstruct(d,p,binding);
            }
            public BIdentificationAcceptance.Checks coupled(BIdentifiedObservationKernel.Parameters p,List<BObservationIdentificationTool.RequiredPair> required) {
                assertFalse(required.isEmpty());f.events.add("coupled:"+p);f.coupledCalls.add(p);
                if(f.coupledEncountered.containsKey(p))throw f.coupledEncountered.get(p);
                return V022BIdentificationRunnerTest.TrainFixture.checks(!f.coupledFailure.contains(p));
            }
        };
        return BObservationIdentificationTool.trainPassProspective(f.cases,f.parameters,evaluator,f.cancellation,true);
    }
}

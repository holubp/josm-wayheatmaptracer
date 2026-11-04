package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import java.util.concurrent.atomic.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.LegacyEmissionProgress.*;

class LegacyCeilingCensusTest {
    static final String CENSUS="EXACT_Q0_CEILING_CENSUS_V1";

    @Test void actualRasterAllStagesKeepTheExhaustiveWinnerAndQ0Decisions() throws Exception {
        for(int kind=0;kind<3;kind++) {
            var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
            try(var f=kind==0?LegacyGroupedAllStageTest.stageFixture(budget):kind==1?LegacyGroupedAllStageTest.zeroFixture(budget):LegacyGroupedAllStageTest.darkFixture(budget)) {
                var anchors=new ArrayList<LegacyEmissionKernel.AnchorProposal>();
                if(kind==0)for(int row:List.of(0,2,4,6))anchors.add(new LegacyEmissionKernel.AnchorProposal(row,f.profiles().get(row).peaks().get(0)));
                if(kind==1)for(int row=0;row<4;row++)anchors.add(new LegacyEmissionKernel.AnchorProposal(row,f.profiles().get(row).peaks().get(0)));
                var seeds=List.of(0.0,.25);var expected=LegacyGroupedExhaustiveOracle.enumerate(f,seeds,anchors);
                try(var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC);
                    var baseline=LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),seeds,anchors,(a,b,c)->b,"EXACT_Q0_CUTOFF_V1",null);
                    var actual=LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),seeds,anchors,(a,b,c)->b,CENSUS,p)) {
                    LegacyGroupedAllStageTest.assertOracle(expected,actual);
                    LegacyGroupedAllStageTest.assertSameDecisions(baseline,actual);
                    assertEquals(CENSUS,p.view().groupedExecution().name());
                    assertTrue(p.view().hasCeilingCensus());
                    assertFalse(p.view().hasCeilingStage(),"No active stage is fabricated at final publication");
                    assertFalse(p.view().ceilingCounterOverflow());
                    assertEquals(p.view().count(Counter.COMPONENT_BOUND_PREPARATIONS),p.view().ceilingTotalCount(CeilingCounter.CANDIDATES));
                }
            }
        }
    }

    record Paid(Map<EmissionWorkBudget.Operation,Long> production,Map<Counter,Long> events,Map<EmissionWorkBudget.Operation,Long> tax,
            long candidates,long witnesses,Map<EmissionWorkBudget.Operation,Long> ticketWork,boolean census) { }
    static Paid paid(String policy) throws Exception {
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var f=LegacyCompetitiveProofTest.fixture(false,budget)) {
            var ticketWork=LegacyCeilingTicketFixture.publicationWork(f.input(),f.owner());
            try(var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC);
            var result=LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),List.of(0.0,.25),List.of(),(a,b,c)->b,policy,p)) {
            var v=p.view();var production=new EnumMap<EmissionWorkBudget.Operation,Long>(EmissionWorkBudget.Operation.class);
            var tax=new EnumMap<EmissionWorkBudget.Operation,Long>(EmissionWorkBudget.Operation.class);
            for(var op:EmissionWorkBudget.Operation.values()){production.put(op,v.productionUnits(op));tax.put(op,v.diagnosticUnits(op));}
            var events=new EnumMap<Counter,Long>(Counter.class);for(var counter:Counter.values())events.put(counter,v.count(counter));
            assertEquals(budget.snapshot(),result.work());
            return new Paid(production,events,tax,v.count(Counter.COMPONENT_BOUND_PREPARATIONS),v.count(Counter.WITNESS_CERTIFIED),ticketWork,v.hasCeilingCensus());
            }
        }
    }
    @Test void onlyPrepaidDiagnosticTaxChangesWhileNativeWorkAndEventOrderStayExact() throws Exception {
        var off=paid("EXACT_Q0_CUTOFF_V1");var on=paid(CENSUS);
        assertEquals(off.production(),on.production());assertEquals(off.events(),on.events());
        assertFalse(off.census());assertTrue(on.census());
        // Existing independently prepared tickets supply the complete check's actual per-operation price.
        assertEquals(off.ticketWork(),on.ticketWork());long checks=1+on.witnesses()+on.candidates();
        for(var op:EmissionWorkBudget.Operation.values()) {
            long own=op==EmissionWorkBudget.Operation.PREPARATION?64+64*on.witnesses()+128*on.candidates():op==EmissionWorkBudget.Operation.ALLOCATION?1:0;
            assertEquals(own+checks*on.ticketWork().get(op),on.tax().get(op)-off.tax().get(op),op.name());
        }
        assertTrue(on.candidates()>0);assertTrue(on.events().get(Counter.STRICT_CERTIFICATE_DEFERRALS)>0);
    }

    @Test void nullObserverAddsNoWorkOrOwnershipToTheExistingQ0Route() throws Exception {
        try(var a=LegacyCompetitiveProofTest.fixture(false,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
            var b=LegacyCompetitiveProofTest.fixture(false,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
            var old=LegacyGroupedAllStageTest.solve(a.input(),a.profiles(),List.of(0.0,.25),List.of(),(x,y,z)->y,"EXACT_Q0_CUTOFF_V1",null);
            var census=LegacyGroupedAllStageTest.solve(b.input(),b.profiles(),List.of(0.0,.25),List.of(),(x,y,z)->y,CENSUS,null)) {
            LegacyGroupedAllStageTest.assertSameDecisions(old,census);
            assertEquals(old.work(),census.work());assertEquals(old.peakRetainedBytes(),census.peakRetainedBytes());
            assertTrue(census.emission().sourceReference().modelPolicyId().contains(CENSUS));
        }
    }

    @Test void foreignSameBudgetAndTerminalObserversCannotBeConsumedByTheCensusRoute() throws Exception {
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var a=LegacyCompetitiveProofTest.fixture(false,budget);var b=LegacyCompetitiveProofTest.fixture(false,budget);
            var p=LegacyEmissionProgress.open(a.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            var before=LegacyProofProgressOwnershipTest.snapshot(p,a,b,budget);
            for(int repeat=0;repeat<2;repeat++) {
                assertThrows(IllegalArgumentException.class,()->LegacyGroupedAllStageTest.solve(b.input(),b.profiles(),List.of(0.0),List.of(),(x,y,z)->y,CENSUS,p));
                LegacyProofProgressOwnershipTest.assertUnchanged(before,LegacyProofProgressOwnershipTest.snapshot(p,a,b,budget));
            }
            try(var result=LegacyGroupedAllStageTest.solve(a.input(),a.profiles(),List.of(0.0),List.of(),(x,y,z)->y,CENSUS,p)) {
                assertFalse(result.emission().inferenceVertices().isEmpty());assertTrue(p.view().hasCeilingCensus());
                var terminal=budget.snapshot();long bytes=a.ledger().currentBytes();
                assertThrows(IllegalArgumentException.class,()->LegacyGroupedAllStageTest.solve(a.input(),a.profiles(),List.of(0.0),List.of(),(x,y,z)->y,CENSUS,p));
                assertEquals(terminal,budget.snapshot());assertEquals(bytes,a.ledger().currentBytes());
            }
        }
    }

    @Test void paymentRefusalAndSourceMutationCannotPublishTheFirstCeilingObservation() throws Exception {
        for(int fault=0;fault<4;fault++) {
            int kind=fault;var armed=new AtomicBoolean();var fired=new AtomicBoolean();
            LegacyEmissionProgress[] progress={null};LegacyCompetitiveProofTest.Fixture[] fixture={null};EmissionWorkBudget[] current={null};
            current[0]=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
                if(!armed.get()||fired.get()||progress[0].currentCount(Counter.COMPONENT_BOUND_PREPARATIONS)==0)return false;
                boolean match=StackWalker.getInstance().walk(s->s.anyMatch(f->f.getMethodName().equals("prepareCeilingObservation")));
                if(!match)return false;fired.set(true);
                if(kind==0)current[0].charge(EmissionWorkBudget.Operation.PREPARATION,current[0].workLimit()+1);
                if(kind==1)fixture[0].owner().reserve(Long.MAX_VALUE);
                if(kind==2)return true;
                fixture[0].profiles().get(0).normalScreen().x=.25;return false;
            });
            try(var f=LegacyCompetitiveProofTest.fixture(false,current[0]);var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
                fixture[0]=f;progress[0]=p;
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.EmissionResult.Costs actualWitness;
                try(var baseline=LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),List.of(0.0),List.of(),(a,b,c)->b,"EXACT_Q0_CUTOFF_V1",null)) {
                    actualWitness=baseline.stages().get(0).ordinaryWitness().orElseThrow();
                }
                long bytes=f.ledger().currentBytes();armed.set(true);
                var failure=assertThrows(RuntimeException.class,()->LegacyGroupedAllStageTest.solve(f.input(),f.profiles(),List.of(0.0),List.of(),(a,b,c)->b,CENSUS,p));
                assertTrue(fired.get());assertEquals(bytes,f.ledger().currentBytes());
                assertEquals(0,p.view().ceilingTotalCount(CeilingCounter.CANDIDATES));
                assertTrue(p.view().hasCeilingWitness());
                assertEquals(List.of(actualWitness.q(),actualWitness.c(),actualWitness.t(),actualWitness.k()),
                    java.util.stream.IntStream.range(0,4).mapToObj(p.view()::ceilingWitnessComponent).toList());
                assertEquals(actualWitness.total(),p.view().ceilingWitnessUpper());
                if(kind<3)assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));
                else assertThrows(IllegalStateException.class,f.input()::requireOpenAndUnchanged);
                long work=current[0].workUsed();p.seal(failure);assertEquals(work,current[0].workUsed());
            }
        }
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.ref.Reference;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class NativeMeasuredProfileTerminalTest {
    enum Entry {
        PROJECT(0,64), PROJECT_RATIO(64,4), BINDING(0,1), ANCHORED(0,2);
        final long allowedAfterFresh,refusedCharge;
        Entry(long allowedAfterFresh,long refusedCharge){this.allowedAfterFresh=allowedAfterFresh;this.refusedCharge=refusedCharge;}
        void run(Prepared p){switch(this){
            case PROJECT,PROJECT_RATIO -> p.family.project(new MetricPoint(52,64));
            case BINDING -> p.binding.validate();
            case ANCHORED -> p.session.metricAnchoredQuery(p.state.evaluatorState().row(),p.point,new MetricPoint(0,1));
        }}
    }
    /** Arms only when the measured freshness prefix and any declared allowance have completed. */
    static final class Gate implements CancellationProbe {
        EmissionWorkBudget budget;long at=Long.MAX_VALUE;boolean armed;
        @Override public boolean cancelled(){return armed&&budget.snapshot().workUsed()>=at;}
    }
    record Prepared(SampledEmissionInput input,EmissionWorkBudget budget,AttemptMemoryLedger ledger,
        NativeMeasuredProfileReceipt.Session session,NativeMeasuredProfileReceipt.Profile profile,
        NativeMeasuredProfileReceipt.Family family,NativeMeasuredProfileReceipt.NativePointBinding binding,
        LegacyEmissionDomain domain,LegacyEmissionDomain.State state,RasterPoint point,long withoutSession,Gate gate) { }
    @FunctionalInterface interface Check {void run(Prepared p);}

    @ParameterizedTest @EnumSource(Entry.class)
    void postFreshWorkFailureImmediatelyClosesPayloadAndRevokesGetters(Entry entry){
        withPrepared(p->exercise(p,entry,false));
    }
    @ParameterizedTest @EnumSource(Entry.class)
    void postFreshCancellationImmediatelyClosesPayloadAndRevokesGetters(Entry entry){
        withPrepared(p->exercise(p,entry,true));
    }
    @ParameterizedTest @EnumSource(value=Entry.class,names={"PROJECT","BINDING","ANCHORED"})
    void nonterminalFreshSourceRefusalPreservesImmutableFactsAndCallerCleanup(Entry entry){
        withPrepared(p->{
            var facts=p.profile.probe();long retained=p.ledger.currentBytes();
            p.input.profiles().levelZeroProfiles().get(0).normalScreen().x+=.125;
            assertThrows(IllegalStateException.class,()->entry.run(p));
            assertSame(facts,p.profile.probe());assertEquals(retained,p.ledger.currentBytes());
        });
    }
    @Test void postFreshTerminalFailureDoesNotCloseAnotherAttempt(){
        withPrepared(foreign->{
            var facts=foreign.profile.probe();long retained=foreign.ledger.currentBytes();
            withPrepared(primary->exercise(primary,Entry.PROJECT,false));
            assertSame(facts,foreign.profile.probe());assertEquals(retained,foreign.ledger.currentBytes());
            foreign.profile.validate();
        });
    }
    private static void exercise(Prepared p,Entry entry,boolean cancel){
        long before=p.budget.snapshot().workUsed();p.input.requireOpenAndUnchanged();
        long freshCost=p.budget.snapshot().workUsed()-before;assertTrue(freshCost>0);
        long expectedUsed;
        if(cancel){
            expectedUsed=p.budget.snapshot().workUsed()+freshCost+entry.allowedAfterFresh;
            p.gate.at=expectedUsed;p.gate.armed=true;
        }else{
            long burn=p.budget.snapshot().workLimit()-p.budget.snapshot().workUsed()-freshCost-entry.allowedAfterFresh;
            assertTrue(burn>=0);p.budget.charge(EmissionWorkBudget.Operation.PREPARATION,burn);
            expectedUsed=p.budget.snapshot().workLimit();
        }
        RuntimeException failure;
        if(cancel)failure=assertThrows(CancellationException.class,()->entry.run(p));
        else{
            var limit=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->entry.run(p));
            assertEquals(entry.refusedCharge,limit.requestedUnits());
            assertEquals(EmissionWorkBudget.LimitKind.TOTAL_WORK,limit.kind());failure=limit;
        }
        p.gate.armed=false;
        assertEquals(expectedUsed,p.budget.snapshot().workUsed(),"Failure must occur after successful freshness and the declared inner allowance");
        // All observations below precede any retry, close or later numerical freshness check.
        assertAll(
            ()->assertEquals(p.withoutSession,p.ledger.currentBytes(),"Issuing session AND evaluator payload must be released immediately"),
            ()->assertSame(failure,assertThrows(RuntimeException.class,p.profile::probe)),
            ()->assertSame(failure,assertThrows(RuntimeException.class,p.family::exactDeterminant)),
            ()->assertSame(failure,assertThrows(RuntimeException.class,p.binding::state)));
        var stopped=p.budget.snapshot();
        assertSame(failure,assertThrows(RuntimeException.class,()->entry.run(p)));
        assertEquals(stopped,p.budget.snapshot(),"Sticky failure cannot charge or retry work");
    }
    private static void withPrepared(Check check){
        var f=LegacyAffinityContractProbeTest.fixture(.6,false,false);
        var ledger=AttemptMemoryLedger.production();var gate=new Gate();
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),gate);gate.budget=budget;
        String field=f.evidence().fields().keySet().iterator().next();
        try(var owner=ledger.rootOwner();var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(f.evidence(),field,f.input(),f.chainage(),25,budget,owner)){
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(f.evidence(),field,f.input().profileLocations(),25,1,1,budget,permit);
            var mapping=java.util.stream.IntStream.range(0,f.input().profileLocations().size()).boxed().toList();
            var binding=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,mapping,1,List.of());
            try(var input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding);
                var stateOwner=input.openExecutionOwner("m1-post-fresh-control-domain")){
                var domain=new LegacyEmissionDomain(binding,input.profiles().levelZeroProfiles(),List.of(-4.0,4.0),budget,stateOwner);
                long baseline=ledger.currentBytes();
                try(var session=NativeConnectorEvaluator.openAffinityProfiles(input)){
                    var state=domain.observedAt(5).get(0);var point=domain.rasterPoint(state);
                    var profile=session.metricAnchoredQuery(state.evaluatorState().row(),point,new MetricPoint(0,1));
                    var nativeBinding=session.bindNativeState(profile,domain,state);
                    var family=session.freezeFamily(input.context().metricAt(point),new MetricPoint(3,0),new MetricPoint(0,2),0,1);
                    try{check.run(new Prepared(input,budget,ledger,session,profile,family,nativeBinding,domain,state,point,baseline,gate));}
                    finally{Reference.reachabilityFence(domain);}
                }
            }
        }
        assertEquals(0,ledger.currentBytes());
    }
}

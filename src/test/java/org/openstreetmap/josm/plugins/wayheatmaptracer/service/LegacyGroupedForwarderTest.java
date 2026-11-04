package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;

class LegacyGroupedForwarderTest {
    static final NativeConnectorEvaluator.QueryPolicy QUERY=NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1;
    static final NativeConnectorEvaluator.ExecutionPolicy EXEC=NativeConnectorEvaluator.ExecutionPolicy.INITIAL_LOCAL_NOISE_PRECHECK_V1;
    static final LegacyEmissionKernel.GroupedExecution GROUPED=LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CUTOFF_V1;

    @Test void invalidExplicitPoliciesRefuseBeforeChargingPreparation() throws Exception {
        try(var f=fixture(false)) {
            var before=f.input().workBudget().snapshot();long bytes=f.ledger().currentBytes();
            assertThrows(IllegalArgumentException.class,()->new RidgeTracker().trackGroupedEmission(f.input(),CancellationProbe.NONE,
                NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,EXEC,GROUPED,null));
            assertEquals(before,f.input().workBudget().snapshot());assertEquals(bytes,f.ledger().currentBytes());
            assertThrows(NullPointerException.class,()->new RidgeTracker().trackGroupedEmission(f.input(),CancellationProbe.NONE,QUERY,EXEC,null,null));
            assertEquals(before,f.input().workBudget().snapshot());assertEquals(bytes,f.ledger().currentBytes());
        }
    }

    enum Refusal { WORK, PAIRS, CANCEL, MEMORY }
    @Test void observerClosedDuringPreparationIsNotResealedByTheForwarder() throws Exception {
        try(var f=fixture(false);var p=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)) {
            var closed=new AtomicBoolean();var bytes=new AtomicLong();CancellationProbe probe=()->{
                if(!closed.get()&&StackWalker.getInstance().walk(s->s.anyMatch(frame->frame.getMethodName().equals("suppressLongitudinalNoise")))) {
                    closed.set(true);p.close();bytes.set(f.ledger().currentBytes());
                }return false;
            };
            assertThrows(IllegalArgumentException.class,()->forward(f.input(),probe,p));assertTrue(closed.get());
            assertThrows(IllegalStateException.class,p::view,"Kernel admission must leave the now-closed, unsealed foreign-lifetime observer untouched");
            assertEquals(LegacyEmissionProgress.Phase.NOT_ENTERED,p.currentPhase());assertEquals(bytes.get(),f.ledger().currentBytes());
            f.input().requireOpenAndUnchanged();
        }
    }
    @Test void preparationRefusalsSealOnlyUnenteredFactsAndPreserveThePrimary() throws Exception {
        for(var refusal:Refusal.values())try(var f=fixture(false);var p=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)) {
            var fired=new AtomicBoolean();var budget=f.input().workBudget();long bytes=f.ledger().currentBytes();
            CancellationProbe probe=()->{
                if(fired.get()||!StackWalker.getInstance().walk(s->s.anyMatch(frame->frame.getMethodName().equals("suppressLongitudinalNoise"))))return false;
                fired.set(true);switch(refusal) {
                    case CANCEL->{return true;}
                    case WORK->budget.charge(EmissionWorkBudget.Operation.PREPARATION,budget.workLimit()+1);
                    case PAIRS->budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,budget.pairLimit()+1);
                    case MEMORY->f.owner().reserve(Long.MAX_VALUE);
                }return false;
            };
            var primary=assertThrows(RuntimeException.class,()->forward(f.input(),probe,p));assertTrue(fired.get());
            assertEquals(switch(refusal){case WORK->EmissionWorkBudget.LimitExceededException.class;case PAIRS->EmissionWorkBudget.LimitExceededException.class;case CANCEL->CancellationException.class;case MEMORY->AttemptMemoryLedger.ResourceLimitException.class;},primary.getClass());
            assertSame(primary,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));
            assertSame(primary,assertThrows(RuntimeException.class,()->forward(f.input(),CancellationProbe.NONE,null)));
            assertEquals(bytes,f.ledger().currentBytes());assertEquals(LegacyEmissionProgress.Phase.NOT_ENTERED,p.view().phase());
            assertEquals(switch(refusal){case WORK->LegacyEmissionProgress.Termination.TOTAL_WORK;case PAIRS->LegacyEmissionProgress.Termination.PAIR_VISITS;case CANCEL->LegacyEmissionProgress.Termination.CANCELLED;case MEMORY->LegacyEmissionProgress.Termination.MEMORY;},p.view().termination());
            assertNoStage(p);assertFalse(p.view().hasGroupedExecution());
            if(refusal==Refusal.CANCEL){assertFalse(p.view().hasFailureRequested());assertFalse(p.view().hasFailureUsed());assertFalse(p.view().hasFailureLimit());}
            else {assertTrue(p.view().hasFailureRequested());assertTrue(p.view().hasFailureUsed());assertTrue(p.view().hasFailureLimit());}
        }
    }

    @Test void foreignObserverIsRejectedBeforePreparationAndItsOwnerCanStillUseIt() throws Exception {
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var a=LegacyCompetitiveProofTest.fixture(false,budget);var b=LegacyCompetitiveProofTest.fixture(false,budget);var p=LegacyEmissionProgress.open(a.input(),QUERY,EXEC)) {
            var initial=LegacyProofProgressOwnershipTest.snapshot(p,a,b,budget);
            for(int repeat=0;repeat<3;repeat++) {
                assertThrows(IllegalArgumentException.class,()->forward(b.input(),CancellationProbe.NONE,p));
                LegacyProofProgressOwnershipTest.assertUnchanged(initial,LegacyProofProgressOwnershipTest.snapshot(p,a,b,budget));
            }
            var owned=forward(a.input(),CancellationProbe.NONE,p);var vertices=owned.emission().inferenceVertices();
            var terminal=LegacyProofProgressOwnershipTest.snapshot(p,a,b,budget);
            for(var input:List.of(a,b)) {
                assertThrows(IllegalArgumentException.class,()->forward(input.input(),CancellationProbe.NONE,p));
                LegacyProofProgressOwnershipTest.assertUnchanged(terminal,LegacyProofProgressOwnershipTest.snapshot(p,a,b,budget));
            }
            a.input().close();assertEquals(vertices,owned.emission().inferenceVertices());assertFalse(owned.stages().isEmpty());
            owned.close();owned.close();assertThrows(IllegalStateException.class,owned::emission);assertThrows(IllegalStateException.class,owned::stages);
        }
    }

    @Test void staleSourceAtEntryOrDuringPreparationNeverCompletesAStage() throws Exception {
        for(boolean during:List.of(false,true))try(var f=fixture(false);var p=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)) {
            var changed=new AtomicBoolean();long bytes=f.ledger().currentBytes();
            if(!during){f.profiles().get(0).normalScreen().x=.25;changed.set(true);}
            CancellationProbe probe=()->{
                if(during&&!changed.get()&&StackWalker.getInstance().walk(s->s.anyMatch(frame->frame.getMethodName().equals("suppressLongitudinalNoise")))) {
                    changed.set(true);f.profiles().get(0).normalScreen().x=.25;
                }return false;
            };
            assertThrows(IllegalStateException.class,()->forward(f.input(),probe,p));assertTrue(changed.get());assertEquals(bytes,f.ledger().currentBytes());
            assertNoStage(p);assertEquals(LegacyEmissionProgress.Termination.OTHER,p.view().termination());
            assertEquals(during?LegacyEmissionProgress.Phase.DOMAIN:LegacyEmissionProgress.Phase.NOT_ENTERED,p.view().phase());
            assertEquals(during,p.view().hasGroupedExecution(),"A preparation mutation is refused by the actual kernel's entry guard, before any stage");
        }
    }

    @Test void successfulHandoffHasNoPostPublicationWorkOrCancellationCallback() throws Exception {
        try(var f=fixture(false);var p=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)) {
            var late=new AtomicBoolean();CancellationProbe probe=()->{
                try{p.view();late.set(true);}catch(IllegalStateException notSealed){ /* actual kernel still owns publication */ }return false;
            };
            try(var result=forward(f.input(),probe,p)) {
                assertFalse(late.get());assertEquals(result.work(),f.input().workBudget().snapshot());
                assertEquals(LegacyEmissionProgress.Termination.SUCCESS,p.view().termination());
            }
        }
    }
    static void assertNoStage(LegacyEmissionProgress p) {
        assertFalse(p.view().firstStageCompleted());assertFalse(p.view().hasWitnessUpper());
        assertEquals(0,p.view().count(LegacyEmissionProgress.Counter.SEEDS_STARTED));assertEquals(0,p.view().count(LegacyEmissionProgress.Counter.WITNESS_ATTEMPTED));
        assertEquals(0,p.view().count(LegacyEmissionProgress.Counter.EVALUATION_STARTED));
    }

    @Test void actualProductionPreparationMatchesDirectGroupedKernelAndFactualSourceRows() throws Exception {
        for(boolean nonidentity:List.of(false,true)) {
            try(var a=fixture(nonidentity);var b=fixture(nonidentity);
                var pa=LegacyEmissionProgress.open(a.input(),QUERY,EXEC);var pb=LegacyEmissionProgress.open(b.input(),QUERY,EXEC);
                var expected=direct(a.input(),CancellationProbe.NONE,pa);var actual=forward(b.input(),CancellationProbe.NONE,pb)) {
                LegacyGroupedAllStageTest.assertSameDecisions(expected,actual);
                var x=expected.emission();var y=actual.emission();assertEquals(expected.execution(),actual.execution());
                assertEquals(x.sourceReference(),y.sourceReference());assertEquals(x.nativeStateTables(),y.nativeStateTables());
                assertEquals(x.generatedStateReferences(),y.generatedStateReferences());assertEquals(x.originReferences(),y.originReferences());
                assertEquals(x.originalObservations(),y.originalObservations());
                LocalNoiseControlParityTest.assertArcs(x.firstStageArcs(),y.firstStageArcs());LocalNoiseControlParityTest.assertArcs(x.inferenceArcs(),y.inferenceArcs());
                LocalNoiseControlParityTest.assertArcs(x.postSmoothingArcs(),y.postSmoothingArcs());
                assertEquals(expected.work(),actual.work());assertEquals(expected.peakRetainedBytes(),actual.peakRetainedBytes());
                assertEquals(actual.work(),b.input().workBudget().snapshot(),"No work or callback follows the actual kernel publication guard");
                for(var counter:LegacyEmissionProgress.Counter.values())assertEquals(pa.view().count(counter),pb.view().count(counter),counter.name());
                for(var row:y.originalObservations())assertEquals(b.input().context().profileChainage().sourceOriginGroundMeters()
                    +b.input().context().sourceChainageMeters(row.retainedContextIndex()),row.sourceChainageMeters(),0);
                assertTrue(y.sourceReference().modelPolicyId().contains(GROUPED.name()));assertTrue(y.sourceReference().modelPolicyId().contains(EXEC.name()));
                if(nonidentity)assertEquals(List.of(1,2,3),y.originalObservations().stream().map(EmissionResult.OriginalObservation::retainedContextIndex).toList());
                else assertFalse(y.intervalStageReferences().isEmpty());
            }
        }
    }

    static LegacyCompetitiveProofTest.Fixture fixture(boolean nonidentity) throws Exception {
        return nonidentity?LegacyCompetitiveZeroDirectionTest.fixture():LegacyGroupedAllStageTest.stageFixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
    }
    static LegacyEmissionKernel.GroupedResult forward(SampledEmissionInput input,CancellationProbe cancellation,LegacyEmissionProgress progress) throws Exception {
        var m=RidgeTracker.class.getMethod("trackGroupedEmission",SampledEmissionInput.class,CancellationProbe.class,
            NativeConnectorEvaluator.QueryPolicy.class,NativeConnectorEvaluator.ExecutionPolicy.class,LegacyEmissionKernel.GroupedExecution.class,LegacyEmissionProgress.class);
        try{return (LegacyEmissionKernel.GroupedResult)m.invoke(new RidgeTracker(),input,cancellation,QUERY,EXEC,GROUPED,progress);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException primary)throw primary;throw failure;}
    }
    @SuppressWarnings("unchecked")
    static LegacyEmissionKernel.GroupedResult direct(SampledEmissionInput input,CancellationProbe cancellation,LegacyEmissionProgress progress) throws Exception {
        // Call the ACTUAL private production preparation functions; no approximate suppression/seed/anchor/smoother oracle.
        var tracker=new RidgeTracker();input.requireOpenAndUnchanged();var binding=input.legacyBinding().orElseThrow();var budget=input.workBudget();
        budget.charge(EmissionWorkBudget.Operation.PREPARATION,binding.observations().size());
        var original=binding.observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList();
        var suppressed=(List<CrossSectionProfile>)call(tracker,"suppressLongitudinalNoise",call(tracker,"cancellableProfiles",original,cancellation));
        budget.charge(EmissionWorkBudget.Operation.PREPARATION,suppressed.size());
        var seeds=(List<Double>)call(tracker,"collectSeedOffsets",suppressed);budget.charge(EmissionWorkBudget.Operation.PREPARATION,seeds.size());
        double normalization=binding.trackerNormalizationRasterPx();var proposals=new ArrayList<LegacyEmissionKernel.AnchorProposal>();
        for(Object anchor:(List<?>)call(tracker,"reliableAnchors",suppressed,normalization,call(tracker,"profileEdgeLimit",suppressed)))
            proposals.add(new LegacyEmissionKernel.AnchorProposal((int)LegacyGroupedExhaustiveOracle.access(anchor,"index"),
                (RenderedHeatmapSampler.CrossSectionPeak)LegacyGroupedExhaustiveOracle.access(anchor,"peak")));
        budget.charge(EmissionWorkBudget.Operation.PREPARATION,proposals.size());
        LegacyEmissionKernel.Smoother smoother=(p,o,i)->{
            try{return (List<Double>)call(tracker,"smoothOffsets",p,o,i,normalization,cancellation,budget);}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new AssertionError(failure);}
        };
        return new LegacyEmissionKernel().solveGrouped(input,suppressed,seeds,proposals,smoother,cancellation,QUERY,EXEC,GROUPED,progress);
    }
    static Object call(Object target,String name,Object...args) throws Exception {
        for(var method:RidgeTracker.class.getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length) {
            method.setAccessible(true);try{return method.invoke(target,args);}catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException primary)throw primary;throw failure;}
        }throw new NoSuchMethodException(name);
    }
}

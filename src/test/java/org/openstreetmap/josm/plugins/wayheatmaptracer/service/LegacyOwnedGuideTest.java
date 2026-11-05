package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.SampledEmissionInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class LegacyOwnedGuideTest {
    @Test void guideHasAnExplicitOwnedPreparationEntry() {
        assertDoesNotThrow(()->RidgeTracker.class.getMethod("prepareOwnedOrdinaryGuide",SampledEmissionInput.class,CancellationProbe.class));
    }
    @Test void witnessComparisonHasAnExplicitBaseOnlyProductionEntry() {
        assertDoesNotThrow(()->RidgeTracker.class.getMethod("trackOwnedGuideWitnesses",SampledEmissionInput.class,CancellationProbe.class,
            NativeConnectorEvaluator.QueryPolicy.class,NativeConnectorEvaluator.ExecutionPolicy.class,LegacyEmissionProgress.class));
    }
    @Test void baseSiblingMatchesNativeScoreOffsetsIntensitiesAndRetainsExactOwners()throws Exception {
        try(var f=LegacyCompetitiveProofTest.fixture(false,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
            var provider=new RidgeTracker().prepareOwnedOrdinaryGuide(f.input(),CancellationProbe.NONE)) {
            var tracker=new RidgeTracker();var nativeMethod=RidgeTracker.class.getDeclaredMethod("bestPathForSeed",List.class,double.class,double.class);nativeMethod.setAccessible(true);
            for(int id=0;id<provider.seeds().size();id++)try(var trace=provider.base(f.input(),id,provider.seeds().get(id))) {
                Object expected=nativeMethod.invoke(tracker,provider.profiles(),provider.seeds().get(id),provider.normalization());
                assertEquals(value(expected,"score"),trace.score());assertEquals(value(expected,"offsets"),trace.offsets());assertEquals(value(expected,"intensities"),trace.intensities());
                assertEquals(provider.profiles().size(),trace.steps().size());
                for(var step:trace.steps())if(step.kind()==RidgeTracker.GuideKind.OBSERVED)
                    assertTrue(provider.profiles().get(step.originalRow()).peaks().stream().anyMatch(p->p==step.peak()));
                assertTrue(f.ledger().currentBytes()<f.ledger().limitBytes());
            }
            try(var foreign=LegacyCompetitiveProofTest.fixture(false,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE))) {
                var before=f.input().workBudget().snapshot();assertThrows(IllegalArgumentException.class,()->provider.base(foreign.input(),0,provider.seeds().get(0)));assertEquals(before,f.input().workBudget().snapshot());
            }
        }
    }
    static Object value(Object o,String method)throws Exception {var m=o.getClass().getDeclaredMethod(method);m.setAccessible(true);return m.invoke(o);}

    @Test void anchoredSiblingUsesExactNativeAnchorsAndTypedGapInstructions()throws Exception {
        try(var f=LegacyGroupedAllStageTest.stageFixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
            var provider=new RidgeTracker().prepareOwnedOrdinaryGuide(f.input(),CancellationProbe.NONE)) {
            assertTrue(provider.anchors().size()>=2);var left=provider.anchors().get(0);var right=provider.anchors().get(provider.anchors().size()-1);
            var anchor=RidgeTracker.GuideAnchor.class.getDeclaredField("nativeAnchor");anchor.setAccessible(true);
            var method=Arrays.stream(RidgeTracker.class.getDeclaredMethods()).filter(m->m.getName().equals("solveAnchoredInterval")).findFirst().orElseThrow();method.setAccessible(true);
            for(var endpoint:List.of(left,right))try(var trace=provider.interval(f.input(),left,endpoint)) {
                Object expected=method.invoke(new RidgeTracker(),provider.profiles(),anchor.get(left),anchor.get(endpoint),provider.normalization(),
                    trackerCall("profileEdgeLimit",provider.profiles()));
                assertEquals(value(expected,"scoreBonus"),trace.score());assertEquals(value(expected,"offsets"),trace.offsets());assertEquals(value(expected,"intensities"),trace.intensities());
                assertSame(endpoint.peak(),trace.steps().get(trace.steps().size()-1).peak());
                if(endpoint==right)assertTrue(trace.steps().stream().anyMatch(s->s.kind()==RidgeTracker.GuideKind.INTERVAL_TARGET));
            }
            try(var base=provider.base(f.input(),0,provider.seeds().get(0))){assertTrue(base.steps().stream().anyMatch(s->s.kind()==RidgeTracker.GuideKind.CARRY_PREVIOUS));}
            var components=left.peak().getClass().getRecordComponents();var types=new Class<?>[components.length];var values=new Object[components.length];
            for(int i=0;i<components.length;i++){types[i]=components[i].getType();values[i]=components[i].getAccessor().invoke(left.peak());}
            var equalPeak=(RenderedHeatmapSampler.CrossSectionPeak)left.peak().getClass().getDeclaredConstructor(types).newInstance(values);
            assertEquals(left.peak(),equalPeak);assertNotSame(left.peak(),equalPeak);
            assertTrue(provider.anchor(left.row(),equalPeak).isEmpty(),"Even a structurally equal peak cannot recover an owner");
        }
    }
    static Object trackerCall(String name,Object...args)throws Exception {
        for(var m:RidgeTracker.class.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(new RidgeTracker(),args);}
        throw new NoSuchMethodException(name);
    }

    @ParameterizedTest @ValueSource(ints={0,1,2})
    void fullGuidePreservesExhaustiveSeedsProtectedIntervalsZeroCarriesAndSmoothing(int fixture)throws Exception {
        try(var f=fixture==0?LegacyGroupedAllStageTest.stageFixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE)):
                fixture==1?LegacyGroupedAllStageTest.zeroFixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE)):
                    LegacyGroupedAllStageTest.darkFixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
            var provider=new RidgeTracker().prepareOwnedOrdinaryGuide(f.input(),CancellationProbe.NONE);
            var progress=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            var seeds=provider.seeds();var proposals=provider.proposals();
            var expected=LegacyGroupedExhaustiveOracle.enumerate(f,seeds,proposals);var calls=new java.util.concurrent.atomic.AtomicInteger();
            try(var result=new LegacyEmissionKernel().solveOwnedGuideManagedGrouped(f.input(),provider,(p,o,i)->{calls.incrementAndGet();return o;},CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,progress)) {
                var grouped=result.grouped().orElseThrow();LegacyGroupedAllStageTest.assertOracle(expected,grouped);
                assertEquals(calls.get(),grouped.emission().managedAffinity().orElseThrow().smoothingOutcomes().size());if(fixture!=1)assertTrue(calls.get()>0);
                assertEquals(grouped.stages().size(),result.comparisons().size());assertEquals(f.input().workBudget().snapshot(),result.work());
                assertTrue(result.modelIdentity().contains("scope=FULL_GROUPED"));assertTrue(result.modelIdentity().contains("EXACT_Q0_OWNED_NATIVE_GUIDE_V1"));
                assertEquals(LegacyEmissionProgress.Termination.SUCCESS,progress.view().termination());
                for(int i=0;i<grouped.stages().size();i++) {
                    var c=result.comparisons().get(i);var stage=grouped.stages().get(i);
                    assertEquals(stage.seedId(),c.seedId());assertEquals(stage.ordinal(),c.ordinal());
                    assertEquals(c.choice()==LegacyEmissionKernel.GuideChoice.GUIDED?c.guided():c.greedy(),stage.ordinaryWitness());
                    if(c.greedy().equals(c.guided())&&c.greedy().isPresent())assertEquals(LegacyEmissionKernel.GuideChoice.GREEDY,c.choice());
                }
                assertThrows(IllegalArgumentException.class,()->provider.base(f.input(),0,seeds.get(0)));
            }
        }
    }

    @Test void uniqueNativeR4HasHonestEqualWitnesses()throws Exception {
        var source=PhysicalGeometryFixture.r4SyntheticFrame(4.0);var crop=LegacyEmissionR4Test.class.getDeclaredMethod("crop",PhysicalGeometryFixture.R4SyntheticFrame.class);
        crop.setAccessible(true);var cropped=crop.invoke(null,source);var evidence=(EvidenceSnapshot)value(cropped,"evidence");var trace=(CorridorTraceInput)value(cropped,"input");var chainage=(ProfileChainage)value(cropped,"chainage");
        var ledger=AttemptMemoryLedger.production();var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var owner=ledger.rootOwner();var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence,"synthetic",trace,chainage,25,budget,owner)) {
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence,"synthetic",trace.profileLocations(),25,1,1,budget,permit);
            var truth=new OptionalEmissionContextFactory.DeclaredSyntheticRasterTruth(source.fullScalarGrid(),18,17,1,0,List.of());
            var binding=OptionalEmissionContextFactory.createDeclaredSyntheticR4Binding(permit,retained,source.profiles().subList(1,18),java.util.stream.IntStream.range(1,18).boxed().toList(),1,truth);
            try(var input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding);
                var result=new RidgeTracker().trackOwnedGuideWitnesses(input,CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,null)) {
                assertEquals(3,result.comparisons().size());assertTrue(result.grouped().isEmpty());
                for(var c:result.comparisons()){assertTrue(c.greedy().isPresent());assertEquals(c.greedy(),c.guided());assertEquals(c.greedySteps(),c.guidedSteps());assertEquals(LegacyEmissionKernel.GuideChoice.GREEDY,c.choice());}
            }
        }assertEquals(0,ledger.currentBytes());
    }

    @Test void allZeroPhysicalReplayReportsNoUpperWithoutInventingAWinner()throws Exception {
        try(var f=LegacyCompetitiveZeroDirectionTest.fixture(true);
            var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC);
            var result=new RidgeTracker().trackOwnedGuideWitnesses(f.input(),CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,p)) {
            assertTrue(result.grouped().isEmpty());assertFalse(result.comparisons().isEmpty());
            for(var c:result.comparisons()) {
                assertTrue(c.greedy().isEmpty());assertTrue(c.guided().isEmpty());assertEquals(LegacyEmissionKernel.GuideChoice.NONE,c.choice());
                assertEquals(LegacyEmissionKernel.GuideStatus.NO_POSITIVE_FINITE_VECTOR,c.greedyStatus());assertEquals(c.greedyStatus(),c.guidedStatus());
            }
            assertEquals(0,p.view().count(LegacyEmissionProgress.Counter.WITNESS_CERTIFIED));assertFalse(p.view().firstStageCompleted());
        }
    }

    @Test void foreignProviderObserverAndFinishedLifetimesRefuseBeforeWork()throws Exception {
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var a=LegacyCompetitiveProofTest.fixture(false,budget);var b=LegacyCompetitiveProofTest.fixture(false,budget);
            var provider=new RidgeTracker().prepareOwnedOrdinaryGuide(a.input(),CancellationProbe.NONE);
            var p=LegacyEmissionProgress.open(a.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            var before=LegacyProofProgressOwnershipTest.snapshot(p,a,b,budget);
            assertThrows(IllegalArgumentException.class,()->new LegacyEmissionKernel().evaluateOwnedOrdinaryWitnesses(b.input(),provider,CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,p));
            assertThrows(IllegalArgumentException.class,()->new RidgeTracker().trackOwnedGuideWitnesses(b.input(),CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,p));
            LegacyProofProgressOwnershipTest.assertUnchanged(before,LegacyProofProgressOwnershipTest.snapshot(p,a,b,budget));
            var result=new LegacyEmissionKernel().evaluateOwnedOrdinaryWitnesses(a.input(),provider,CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,p);
            a.input().close();assertFalse(result.comparisons().isEmpty());result.close();result.close();assertThrows(IllegalStateException.class,result::comparisons);
        }
    }

    @ParameterizedTest @ValueSource(strings={"prepareOwnedOrdinaryGuide","propose","guideAppend","replayOwnedOrdinary","prepareManagedFinalPublication"})
    void chargedPhaseRefusalsAreTerminalWithoutFallbackAndCloseEverything(String phase)throws Exception {
        var work=new java.util.concurrent.atomic.AtomicReference<EmissionWorkBudget>();var fired=new java.util.concurrent.atomic.AtomicBoolean();
        var primary=new java.util.concurrent.CancellationException("synthetic owned guide refusal");
        CancellationProbe probe=()->{if(work.get()!=null&&!fired.get()&&onStack(phase)){fired.set(true);throw primary;}return false;};
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),probe);
        try(var f=LegacyCompetitiveProofTest.fixture(false,budget);var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            long bytes=f.ledger().currentBytes();work.set(budget);
            assertSame(primary,assertThrows(RuntimeException.class,()->new RidgeTracker().trackOwnedGuideWitnesses(f.input(),CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,p)));
            assertTrue(fired.get());assertSame(primary,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));assertEquals(bytes,f.ledger().currentBytes());
            assertEquals(LegacyEmissionProgress.Termination.CANCELLED,p.view().termination());assertFalse(p.view().firstStageCompleted());
        }
    }
    static boolean onStack(String method){return Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(s->s.getMethodName().equals(method));}

    @ParameterizedTest @ValueSource(strings={"work","pairs","memory"})
    void realGuidePrepaymentLimitsStayStickyAndReleaseTheGuide(String kind)throws Exception {
        var action=new java.util.concurrent.atomic.AtomicReference<Runnable>();var fired=new java.util.concurrent.atomic.AtomicBoolean();
        CancellationProbe callback=()->{if(action.get()!=null&&!fired.get()&&onStack("guideAppend")){fired.set(true);action.get().run();}return false;};
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),callback);
        try(var f=LegacyCompetitiveProofTest.fixture(false,budget);var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            long bytes=f.ledger().currentBytes();action.set(()->{switch(kind){
                case "work"->budget.charge(EmissionWorkBudget.Operation.PREPARATION,budget.workLimit()-budget.workUsed()+1);
                case "pairs"->budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,budget.pairLimit()-budget.pairVisits()+1);
                case "memory"->f.owner().reserve(Long.MAX_VALUE);
            }});
            var failure=assertThrows(RuntimeException.class,()->new RidgeTracker().trackOwnedGuideWitnesses(f.input(),CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,p));
            assertTrue(fired.get());assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));assertEquals(bytes,f.ledger().currentBytes());
            assertEquals(kind.equals("memory")?AttemptMemoryLedger.ResourceLimitException.class:EmissionWorkBudget.LimitExceededException.class,failure.getClass());
            assertEquals(0,p.view().count(LegacyEmissionProgress.Counter.WITNESS_CERTIFIED));assertFalse(p.view().firstStageCompleted());
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void revokedObserverInPreparationIsNotPublishedEvenWhenCancellationFollows(boolean seal)throws Exception {
        try(var f=LegacyCompetitiveProofTest.fixture(false,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
            var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            var frozen=new java.util.concurrent.atomic.AtomicReference<LegacyProofProgressOwnershipTest.Snapshot>();var fired=new java.util.concurrent.atomic.AtomicBoolean();
            CancellationProbe callback=()->{
                if(!fired.get()&&onStack("suppressLongitudinalNoise")){
                    fired.set(true);if(seal)p.seal(null);else p.close();
                    try{frozen.set(LegacyProofProgressOwnershipTest.snapshot(p,f,f,f.input().workBudget()));}catch(Exception failure){throw new AssertionError(failure);}return true;
                }return false;
            };
            var failure=assertThrows(java.util.concurrent.CancellationException.class,()->new RidgeTracker().trackOwnedGuideWitnesses(f.input(),callback,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,p));
            assertTrue(fired.get());assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));
            var after=LegacyProofProgressOwnershipTest.snapshot(p,f,f,f.input().workBudget());
            frozen.get().facts().forEach((name,value)->assertTrue(Objects.deepEquals(value,after.facts().get(name)),name));
            assertEquals(frozen.get().work(),after.work());assertEquals(frozen.get().aPeak(),after.aPeak());
            assertTrue(after.aCurrent()<frozen.get().aCurrent(),"Failed preparation releases its guide scratch after observer revocation");
            if(seal)assertEquals(LegacyEmissionProgress.Termination.SUCCESS,p.view().termination());else assertThrows(IllegalStateException.class,p::view);
        }
    }

    @Test void staleOriginalRowAfterLaterGuidePaymentCannotPublishWitness()throws Exception {
        var normal=new java.util.concurrent.atomic.AtomicReference<java.awt.geom.Point2D.Double>();var fired=new java.util.concurrent.atomic.AtomicBoolean();
        CancellationProbe probe=()->{if(normal.get()!=null&&!fired.get()&&onStack("guideAppend")){fired.set(true);normal.get().x=Math.nextUp(normal.get().x);}return false;};
        try(var f=LegacyCompetitiveProofTest.fixture(false,new EmissionWorkBudget(TraceBudgets.defaults(),probe));var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            normal.set(f.profiles().get(0).normalScreen());long bytes=f.ledger().currentBytes();
            assertThrows(IllegalStateException.class,()->new RidgeTracker().trackOwnedGuideWitnesses(f.input(),CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,p));
            assertTrue(fired.get());assertEquals(bytes,f.ledger().currentBytes());assertEquals(0,p.view().count(LegacyEmissionProgress.Counter.WITNESS_CERTIFIED));assertFalse(p.view().firstStageCompleted());
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void observerRevokedByWitnessTaxPaymentNeverReceivesTheAdmittedTaxOrUpper(boolean seal)throws Exception {
        var observer=new java.util.concurrent.atomic.AtomicReference<LegacyEmissionProgress>();var fired=new java.util.concurrent.atomic.AtomicBoolean();
        var action=new java.util.concurrent.atomic.AtomicReference<Runnable>();
        CancellationProbe callback=()->{if(observer.get()!=null&&!fired.get()&&onStack("prepareOwnedGuideWitness")){fired.set(true);action.get().run();}return false;};
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),callback);
        try(var f=LegacyCompetitiveProofTest.fixture(false,budget);var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            var frozen=new java.util.concurrent.atomic.AtomicReference<LegacyProofProgressOwnershipTest.Snapshot>();observer.set(p);long baseline=f.ledger().currentBytes();
            action.set(()->{if(seal)p.seal(null);else p.close();try{frozen.set(LegacyProofProgressOwnershipTest.snapshot(p,f,f,budget));}catch(Exception failure){throw new AssertionError(failure);}});
            assertThrows(IllegalArgumentException.class,()->new RidgeTracker().trackOwnedGuideWitnesses(f.input(),CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,p));
            assertTrue(fired.get());var after=LegacyProofProgressOwnershipTest.snapshot(p,f,f,budget);
            frozen.get().facts().forEach((name,value)->assertTrue(Objects.deepEquals(value,after.facts().get(name)),name));
            assertEquals(frozen.get().work().workUsed()+8,after.work().workUsed(),"The shared ledger retains the admitted8 units; a revoked diagnostic buffer cannot publish them");
            assertEquals(baseline-(seal?0:LegacyEmissionProgress.STORAGE_BYTES),f.ledger().currentBytes());f.input().requireOpenAndUnchanged();
            if(seal){assertFalse(p.view().hasWitnessUpper());assertEquals(0,p.view().count(LegacyEmissionProgress.Counter.WITNESS_CERTIFIED));}
            else assertThrows(IllegalStateException.class,p::view);
        }
    }

    @Test void witnessOnlyCertifiesWholeReplaysAndDoesNotRunThePrimaryOptimizer()throws Exception {
        try(var f=analytic(.55,false);var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC);
            var result=new RidgeTracker().trackOwnedGuideWitnesses(f.input(),CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,p)) {
            assertEquals(LegacyEmissionKernel.GuideScope.BASE_WITNESS_ONLY,result.scope());assertTrue(result.grouped().isEmpty());
            assertTrue(result.modelIdentity().contains("guide="+RidgeTracker.OwnedOrdinaryGuide.POLICY_ID));
            assertFalse(p.view().firstStageCompleted());assertEquals(0,p.view().count(LegacyEmissionProgress.Counter.EVALUATION_STARTED));
            assertTrue(result.comparisons().stream().allMatch(s->s.ordinal()==0));assertEquals(result.comparisons().size(),p.view().seedsPlanned());
            assertEquals(f.input().workBudget().snapshot(),result.work());
            assertTrue(result.comparisons().stream().anyMatch(s->s.choice()==LegacyEmissionKernel.GuideChoice.GUIDED&&s.guided().orElseThrow().total()<s.greedy().orElseThrow().total()),result.comparisons().toString());
            for(var comparison:result.comparisons()) {
                assertIndependentOrdinaryCosts(f,comparison);
                if(comparison.greedy().isPresent()&&comparison.guided().isPresent())assertEquals(
                    comparison.guided().get().total()<comparison.greedy().get().total()?LegacyEmissionKernel.GuideChoice.GUIDED:LegacyEmissionKernel.GuideChoice.GREEDY,comparison.choice());
            }
            System.out.println("owned-guide-positive "+result.comparisons().stream().map(c->List.of(c.seedId(),c.greedy(),c.guided(),c.choice())).toList());
        }
    }
    @Test void unaryProposalWithWorsePhysicalTurnCanKeepTheWholeGreedyVector()throws Exception {
        // Strong isolated sampled cross-sections favor the native proposal, while its
        // bent connecting legs cross weak source pixels. Native score cannot certify Q/K.
        try(var f=analytic(.55,true)) {
            try(var provider=new RidgeTracker().prepareOwnedOrdinaryGuide(f.input(),CancellationProbe.NONE)) {
                var nativeMethod=RidgeTracker.class.getDeclaredMethod("bestPathForSeed",List.class,double.class,double.class);nativeMethod.setAccessible(true);
                for(int id=0;id<provider.seeds().size();id++)try(var trace=provider.base(f.input(),id,provider.seeds().get(id))) {
                    var expected=nativeMethod.invoke(new RidgeTracker(),provider.profiles(),provider.seeds().get(id),provider.normalization());
                    assertEquals(value(expected,"score"),trace.score());assertEquals(value(expected,"offsets"),trace.offsets());
                }
                assertEquals(f.profiles().stream().map(p->p.peaks().size()).toList(),provider.profiles().stream().map(p->p.peaks().size()).toList(),"The known parallel branches must survive native suppression");
            }
            try(var result=new RidgeTracker().trackOwnedGuideWitnesses(f.input(),CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,null)) {
            assertTrue(f.profiles().get(1).peaks().size()>=2,f.profiles().toString());
            for(var comparison:result.comparisons())assertIndependentOrdinaryCosts(f,comparison);
            assertTrue(result.comparisons().stream().anyMatch(s->s.greedy().isPresent()&&s.guided().isPresent()
                &&s.guided().get().k()>s.greedy().get().k()&&s.guided().get().total()>s.greedy().get().total()
                &&s.choice()==LegacyEmissionKernel.GuideChoice.GREEDY),()->result.comparisons()+" native="+f.profiles().stream().map(p->p.peaks().stream().map(k->List.of(k.offsetPx(),k.intensity(),k.supportWidthPx(),k.nativeFilteredAgreement(),k.scaleAgreement())).toList()).toList());
            System.out.println("owned-guide-worse-QK "+result.comparisons().stream().map(c->List.of(c.seedId(),c.greedy(),c.guided(),c.choice())).toList());
            }
        }
    }
    static void assertIndependentOrdinaryCosts(LegacyCompetitiveProofTest.Fixture f,LegacyEmissionKernel.GuideStageReference comparison)throws Exception {
        @SuppressWarnings("unchecked") var seeds=(List<Double>)trackerCall("collectSeedOffsets",f.profiles());double seed=seeds.get(comparison.seedId());
        for(boolean guided:List.of(false,true)) {
            var references=guided?comparison.guidedSteps():comparison.greedySteps();var actual=guided?comparison.guided():comparison.greedy();
            if(actual.isEmpty())continue;
            var path=new ArrayList<NativeConnectorEvaluator.RowState>();
            for(var step:references) {
                assertEquals(RidgeTracker.GuideKind.OBSERVED,step.kind());var row=f.input().legacyBinding().orElseThrow().observations().get(step.originalRow());
                var owner=row.producerPeaks().stream().filter(p->p.originalPeakId()==step.nativeStateId()).findFirst().orElseThrow();
                path.add(new NativeConnectorEvaluator.RowState(row.retainedObservationIndex(),step.nativeStateId(),owner.peak().offsetPx(),NativeConnectorEvaluator.SourceClass.OBSERVED,owner.measurementPeakOwner()));
            }
            assertEquals(LegacyCompetitiveProofTest.cost(f,path,null,seed).costs(),actual.orElseThrow(),"Independent native-pitch ordinary raster integral and physical-turn components");
        }
    }
    static LegacyCompetitiveProofTest.Fixture analytic(double weak,boolean zigzag) {
        int w=96,h=128;double[] scalar=new double[w*h];boolean[] valid=new boolean[scalar.length];Arrays.fill(valid,true);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double center=zigzag?6+Math.max(0,1-Math.abs((x-32)/8.0)):6;
            double distance=Math.min(Math.abs(x-24),Math.min(Math.abs(x-32),Math.abs(x-40)));
            double amplitude=zigzag?.95*StrictMath.exp(-.5*Math.pow(distance/.5,2)):.95;
            scalar[y*w+x]=Math.max(weak*StrictMath.exp(-.5*Math.pow((y-64.5)/.8,2)),amplitude*StrictMath.exp(-.5*Math.pow((y-64.5-center)/.8,2)));
        }
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-.01,-.01),new GeographicPoint(.01,.01));
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var field=new ScalarEvidenceField(w,h,scalar,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"guide",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var evidence=new EvidenceSnapshot("owned-guide-analytic",frame,transform,EvidenceResolution.nativeSource(1,1),MetricRegion.rectangle(0,0,w-1,h-1),MetricRegion.rectangle(-.5,-.5,w-.5,h-.5),Map.of("guide",field),"guide");
        double spacing=8;
        int rows=3;
        var locations=new ArrayList<DetachedProfileSamplingLocation>();for(int i=0;i<rows;i++)locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(new MetricPoint(24+spacing*i,64.5)),frame,transform,spacing*i));
        var source=new CorridorTraceInput(locations,1);var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        var chainages=java.util.stream.IntStream.range(0,rows).mapToObj(i->spacing*i).toList();
        try(var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence,"guide",source,new ProfileChainage(chainages,spacing*(rows-1)),25,budget,owner)) {
            double pitch=DetachedScalarProfileSampler.emissionSourcePitchRasterPixels(evidence,source,0,budget);
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence,"guide",source.profileLocations(),25,1,pitch,budget,permit);
            var binding=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,java.util.stream.IntStream.range(0,rows).boxed().toList(),1,List.of());
            var input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding);
            return new LegacyCompetitiveProofTest.Fixture(input,binding.observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList(),ledger,owner);
        }catch(RuntimeException failure){owner.close();throw failure;}
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyGroupedExhaustiveOracle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RidgeTracker;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class ManagedGroupedCompositionTest {
    @TempDir Path cache;
    static final NativeConnectorEvaluator.QueryPolicy QUERY=NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1;
    static final NativeConnectorEvaluator.ExecutionPolicy EXEC=NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1;
    @ParameterizedTest @ValueSource(strings={"unpin","profile","work","pair","cancel","memory"})
    void outerPublicationPrepaymentRefusalsKeepPrimaryAndReleaseAllOwnedPayload(String kind)throws Exception{
        var fixture=new ManagedOrdinaryAffinityTest();fixture.cache=cache;
        var work=new java.util.concurrent.atomic.AtomicReference<EmissionWorkBudget>();var pressure=new java.util.concurrent.atomic.AtomicReference<AttemptMemoryLedger.Owner>();
        var nativeNormal=new java.util.concurrent.atomic.AtomicReference<java.awt.geom.Point2D.Double>();
        var lastBackpointer=new java.util.concurrent.atomic.AtomicLong();var outer=new java.util.concurrent.atomic.AtomicBoolean();var fired=new java.util.concurrent.atomic.AtomicBoolean();
        var primary=new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        CancellationProbe callback=()->{
            var budget=work.get();if(budget==null||fired.get())return false;
            long bp=budget.operationUnits(EmissionWorkBudget.Operation.BACKPOINTER_VISIT);
            if(!outer.get()&&lastBackpointer.getAndSet(bp)==bp)return false;
            var stack=Arrays.asList(Thread.currentThread().getStackTrace());
            if(stack.stream().anyMatch(s->s.getClassName().equals(LegacyEmissionKernel.class.getName())&&s.getMethodName().equals("result"))
                    &&stack.stream().anyMatch(s->s.getClassName().equals(LegacyEmissionKernel.class.getName())&&s.getMethodName().equals("unpinChain"))){
                outer.set(true);if(kind.equals("unpin")){fired.set(true);nativeNormal.get().x=Math.nextUp(nativeNormal.get().x);return false;}
            }
            if(stack.stream().anyMatch(s->s.getMethodName().equals("solveManagedGroupedChartAffinity"))&&stack.stream().noneMatch(s->s.getMethodName().equals("solveBody")))outer.set(true);
            if(outer.get()&&stack.stream().anyMatch(s->s.getMethodName().equals("prepareManagedFinalPublication"))){
                fired.set(true);
                try{switch(kind){
                    case "profile" -> nativeNormal.get().x=Math.nextUp(nativeNormal.get().x);
                    case "work" -> budget.charge(EmissionWorkBudget.Operation.PREPARATION,budget.workLimit()-budget.workUsed()+1);
                    case "pair" -> budget.charge(EmissionWorkBudget.Operation.TRANSITION,budget.pairLimit()-budget.pairVisits()+1);
                    case "cancel" -> throw new java.util.concurrent.CancellationException("synthetic grouped publication refusal");
                    case "memory" -> pressure.get().reserve(AttemptMemoryLedger.MAX_BYTES-pressure.get().currentBytes()-64);
                }}catch(RuntimeException failure){primary.set(failure);throw failure;}
            }return false;
        };
        fixture.managed(new ManagedOrdinaryAffinityTest.Spec(.60,true,false,false,callback,12,true),f->{
            work.set(f.input().workBudget());nativeNormal.set(f.input().profiles().levelZeroProfiles().get(0).normalScreen());
            try(var progress=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)){
                long baseline=f.ledger().currentBytes();
                try(var owner=f.input().openExecutionOwner("synthetic-outer-publication-pressure")){
                    pressure.set(owner);var profiles=f.input().legacyBinding().orElseThrow().observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList();
                    var failure=assertThrows(RuntimeException.class,()->solve(f.input(),profiles,profiles.get(0).peaks().stream().map(p->p.offsetPx()).toList(),List.of(),(p,o,s)->o,CancellationProbe.NONE,
                        LegacyEmissionKernel.GroupedExecution.EXACT_VERSIONS_V1,progress));
                    assertTrue(fired.get(),"Refusal must occur at the declared final-unpin or outer publication admission boundary");
                    if(primary.get()!=null)assertSame(primary.get(),failure);
                    if(kind.equals("profile")||kind.equals("unpin")){assertInstanceOf(IllegalStateException.class,failure);assertEquals(LegacyEmissionProgress.Termination.OTHER,progress.view().termination());}
                    else{assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));assertNotEquals(LegacyEmissionProgress.Termination.SUCCESS,progress.view().termination());}
                }assertEquals(baseline,f.ledger().currentBytes());
            }
        });
    }
    @Test void actualManagedProductionForwarderMatchesExactPrivatePreparationAndHasNoLateCallback()throws Exception{
        var fixture=new ManagedOrdinaryAffinityTest();fixture.cache=cache;
        fixture.managed(new ManagedOrdinaryAffinityTest.Spec(.60,true,false,false,CancellationProbe.NONE,12,true),f->{
            long baseline=f.ledger().currentBytes();List<EmissionResult.Vertex> vertices;List<EmissionResult.Costs> intervals;
            EmissionResult.Costs first,stitched,post;Map<EmissionWorkBudget.Operation,Long> directWork;long peak;
            try(var progress=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)){
                var before=f.input().workBudget().snapshot();
                try(var expected=directProduction(f.input(),progress)){
                    var e=expected.emission();vertices=e.smoothedVertices();intervals=e.intervalStages();first=e.firstStage();stitched=e.stitched();post=e.postSmoothing();
                    directWork=delta(before,expected.work());peak=expected.peakRetainedBytes();
                }
            }assertEquals(baseline,f.ledger().currentBytes());
            try(var progress=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)){
                var late=new java.util.concurrent.atomic.AtomicBoolean();CancellationProbe probe=()->{
                    try{progress.view();late.set(true);}catch(IllegalStateException notSealed){}return false;
                };
                var before=f.input().workBudget().snapshot();
                try(var actual=new RidgeTracker().trackManagedGroupedEmission(f.input(),probe,QUERY,EXEC,LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CUTOFF_V1,progress)){
                    var a=actual.emission();assertEquals(vertices,a.smoothedVertices());assertEquals(intervals,a.intervalStages());
                    assertEquals(first,a.firstStage());assertEquals(stitched,a.stitched());assertEquals(post,a.postSmoothing());
                    assertEquals(directWork,delta(before,actual.work()));assertEquals(peak,actual.peakRetainedBytes());
                    assertEquals(actual.work(),f.input().workBudget().snapshot());assertFalse(late.get());assertEquals(LegacyEmissionProgress.Termination.SUCCESS,progress.view().termination());
                }
            }assertEquals(baseline,f.ledger().currentBytes());
        });
    }
    @SuppressWarnings("unchecked")
    static LegacyEmissionKernel.GroupedResult directProduction(SampledEmissionInput input,LegacyEmissionProgress progress)throws Exception{
        var tracker=new RidgeTracker();input.requireOpenAndUnchanged();var binding=input.legacyBinding().orElseThrow();var budget=input.workBudget();
        budget.charge(EmissionWorkBudget.Operation.PREPARATION,binding.observations().size());
        var original=binding.observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList();
        var suppressed=(List<CrossSectionProfile>)trackerCall(tracker,"suppressLongitudinalNoise",trackerCall(tracker,"cancellableProfiles",original,CancellationProbe.NONE));
        budget.charge(EmissionWorkBudget.Operation.PREPARATION,suppressed.size());
        var seeds=(List<Double>)trackerCall(tracker,"collectSeedOffsets",suppressed);budget.charge(EmissionWorkBudget.Operation.PREPARATION,seeds.size());
        double normalization=binding.trackerNormalizationRasterPx();var proposals=new ArrayList<LegacyEmissionKernel.AnchorProposal>();
        for(Object anchor:(List<?>)trackerCall(tracker,"reliableAnchors",suppressed,normalization,trackerCall(tracker,"profileEdgeLimit",suppressed))){
            var index=anchor.getClass().getDeclaredMethod("index");var peak=anchor.getClass().getDeclaredMethod("peak");index.setAccessible(true);peak.setAccessible(true);
            proposals.add(new LegacyEmissionKernel.AnchorProposal((int)index.invoke(anchor),(org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak)peak.invoke(anchor)));
        }budget.charge(EmissionWorkBudget.Operation.PREPARATION,proposals.size());
        LegacyEmissionKernel.Smoother smoother=(p,o,s)->{try{return (List<Double>)trackerCall(tracker,"smoothOffsets",p,o,s,normalization,CancellationProbe.NONE,budget);}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new AssertionError(failure);}};
        return solve(input,suppressed,seeds,proposals,smoother,CancellationProbe.NONE,LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CUTOFF_V1,progress);
    }
    static Object trackerCall(RidgeTracker tracker,String name,Object...args)throws Exception{
        for(var method:RidgeTracker.class.getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){
            method.setAccessible(true);try{return method.invoke(tracker,args);}catch(InvocationTargetException wrapped){if(wrapped.getCause() instanceof RuntimeException failure)throw failure;throw wrapped;}
        }throw new NoSuchMethodException(name);
    }
    static Map<EmissionWorkBudget.Operation,Long> delta(EmissionWorkBudget.Snapshot before,EmissionWorkBudget.Snapshot after){
        var result=new EnumMap<EmissionWorkBudget.Operation,Long>(EmissionWorkBudget.Operation.class);
        for(var op:EmissionWorkBudget.Operation.values())result.put(op,after.operationUnits().get(op)-before.operationUnits().get(op));return result;
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void finalSupplierRevocationNeverMutatesObserverOrPublishesSuccess(boolean cancellation)throws Exception{
        var fixture=new ManagedOrdinaryAffinityTest();fixture.cache=cache;
        fixture.managed(new ManagedOrdinaryAffinityTest.Spec(.60,true,false,false,CancellationProbe.NONE,12,true),f->{
            long baseline=f.ledger().currentBytes();var primary=new java.util.concurrent.CancellationException("synthetic final supplier refusal");
            var fired=new java.util.concurrent.atomic.AtomicBoolean();var frozen=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
            try(var progress=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)){
                f.settingsHook().set(()->{
                    if(!fired.get()&&Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(s->s.getClassName().equals(OptionalEmissionContextFactory.ManagedCaptureBinding.class.getName()+"$FinalPublicationCheck")&&s.getMethodName().equals("validate"))){
                        fired.set(true);progress.close();try{frozen.set(observerFields(progress));}catch(Exception failure){throw new AssertionError(failure);}
                        if(cancellation)throw primary;
                    }
                });
                var profiles=f.input().legacyBinding().orElseThrow().observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList();
                var failure=assertThrows(RuntimeException.class,()->solve(f.input(),profiles,profiles.get(0).peaks().stream().map(p->p.offsetPx()).toList(),List.of(),(p,o,s)->o,CancellationProbe.NONE,
                    LegacyEmissionKernel.GroupedExecution.EXACT_VERSIONS_V1,progress));
                assertTrue(fired.get());assertEquals(frozen.get(),observerFields(progress));assertThrows(IllegalStateException.class,progress::view);
                assertEquals(baseline,f.ledger().currentBytes());
                if(cancellation){assertSame(primary,failure);assertSame(primary,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));}
                else{assertInstanceOf(IllegalArgumentException.class,failure);f.input().requireOpenAndUnchanged();}
            }
        });
    }
    static Map<String,Object> observerFields(LegacyEmissionProgress progress)throws Exception{
        var result=new TreeMap<String,Object>();
        for(var field:LegacyEmissionProgress.class.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())){
            field.setAccessible(true);Object value=field.get(progress);
            if(value instanceof long[] a)value=Arrays.toString(a);else if(value instanceof int[] a)value=Arrays.toString(a);
            else if(value instanceof Object[] a)value=Arrays.deepToString(a);result.put(field.getName(),value);
        }return result;
    }
    @ParameterizedTest @EnumSource(LegacyEmissionKernel.GroupedExecution.class)
    void realOppositeNativeIntervalTargetInvalidatesTheActualOrdinaryWitness(LegacyEmissionKernel.GroupedExecution execution)throws Exception{
        var fixture=new ManagedOrdinaryAffinityTest();fixture.cache=cache;
        fixture.managed(new ManagedOrdinaryAffinityTest.Spec(.60,true,false,false,CancellationProbe.NONE,12,false),f->{
            var profiles=f.input().profiles().levelZeroProfiles();double seedOffset=profiles.get(0).peaks().stream().mapToDouble(p->p.offsetPx()).min().orElseThrow();
            try(var owner=f.input().openExecutionOwner("direct-owned-interval-witness");var progress=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)){
                var budget=f.input().workBudget();var domain=new LegacyEmissionDomain(f.input().legacyBinding().orElseThrow(),profiles,List.of(seedOffset),budget,owner);
                try(var classifier=new ManagedOrdinaryAffinity(f.input(),domain)){
                    var kernel=new LegacyEmissionKernel();Object inventory=invoke(kernel,"inventory",domain,budget,owner,null,classifier);
                    progress.begin(f.input(),QUERY,EXEC);progress.groupedBegin(execution);
                    try(var evaluator=(NativeConnectorEvaluator)invoke(kernel,"evaluator",f.input(),domain,inventory,QUERY,EXEC,progress)){
                        int row=domain.rowCount()/2;var first=domain.observedAt(0).stream().min(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
                        var seed=domain.seeds().get(0);Object prefix=invoke(kernel,"initialProofNode",f.input(),domain,seed,first,owner,budget);
                        for(int r=1;r<row;r++){
                            var next=domain.observedAt(r).stream().min(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
                            prefix=invoke(kernel,"advance",f.input(),domain,inventory,prefix,next,evaluator,owner,CancellationProbe.NONE,0,f.input().context().nativePitchMeters().orElseThrow());assertNotNull(prefix);
                        }
                        var left=domain.observedAt(row).stream().min(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
                        var right=domain.observedAt(row+1).stream().max(Comparator.comparingDouble(LegacyEmissionDomain.State::offsetRasterPixels)).orElseThrow();
                        assertEquals(OrdinaryAffinityClassifier.Status.INCOMPATIBLE,classifier.classify(left,right).status(),"Actual independent source fixture must contain the forbidden target edge");
                        invoke(kernel,"pinChain",prefix,budget);
                        try{
                            progress.phase(LegacyEmissionProgress.Phase.INTERVAL,1,row-1,row+1);
                            var witness=(Optional<?>)invoke(kernel,"groupedWitness",f.input(),domain,null,prefix,right,1,inventory,evaluator,owner,CancellationProbe.NONE,f.input().context().nativePitchMeters().orElseThrow());
                            assertTrue(witness.isEmpty());assertEquals(0,progress.currentCount(LegacyEmissionProgress.Counter.WITNESS_CERTIFIED));
                            Object result=invoke(kernel,"solveGroupedStage",f.input(),domain,null,prefix,right,1,inventory,evaluator,owner,CancellationProbe.NONE,
                                f.input().context().nativePitchMeters().orElseThrow(),execution,Double.POSITIVE_INFINITY);
                            assertNotNull(result,"An independently unproved earlier crossing retains native eligibility; absent U cannot prune it");
                            invoke(kernel,"unpinChain",result,budget);
                            assertEquals(0,progress.currentCount(LegacyEmissionProgress.Counter.STRICT_CERTIFICATE_DEFERRALS));
                        }finally{invoke(kernel,"unpinChain",prefix,budget);}
                        progress.seal(null);
                        if(execution==LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CEILING_CENSUS_V1){
                            assertTrue(progress.view().hasCeilingStage());assertFalse(progress.view().hasCeilingWitness());
                            assertTrue(progress.view().ceilingCount(LegacyEmissionProgress.CeilingCounter.CANDIDATES)>0);
                        }
                    }
                }
            }
        });
    }
    static Object invoke(LegacyEmissionKernel kernel,String name,Object...args)throws Exception{
        for(var method:LegacyEmissionKernel.class.getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){
            method.setAccessible(true);try{return method.invoke(kernel,args);}catch(InvocationTargetException wrapped){if(wrapped.getCause() instanceof RuntimeException failure)throw failure;throw wrapped;}
        }throw new NoSuchMethodException(name);
    }
    @ParameterizedTest @EnumSource(LegacyEmissionKernel.GroupedExecution.class)
    void actualManagedGraphMatchesIndependentQualifiedImageOracle(LegacyEmissionKernel.GroupedExecution execution)throws Exception{
        var fixture=new ManagedOrdinaryAffinityTest();fixture.cache=cache;
        fixture.managed(new ManagedOrdinaryAffinityTest.Spec(.60,true,false,false,CancellationProbe.NONE,12,false),f->{
            var profiles=f.input().profiles().levelZeroProfiles();assertEquals(2,profiles.get(0).peaks().size());
            // Native peak intensity alone does not rank grouped cost with fixed approach geometry.
            // Reverse the genuine seed order to exercise replacement of the first enumerated seed.
            var ordered=profiles.get(0).peaks().stream().sorted(Comparator.comparingDouble((org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak p)->p.intensity()).reversed()).toList();
            var seeds=ordered.stream().map(p->p.offsetPx()).toList();var rejected=new java.util.concurrent.atomic.AtomicInteger();
            var expected=LegacyGroupedExhaustiveOracle.enumerate(f.input(),profiles,seeds,List.of(),QUERY,EXEC,domain->independentParallelAdmission(f.input(),domain,rejected));
            assertTrue(rejected.get()>0,"Real issued M1 ownership must qualify at least one known-image cross-band cut");
            assertTrue(expected.terminalPaths()>1);
            try(var actual=solve(f.input(),profiles,seeds,List.of(),(p,o,s)->o,CancellationProbe.NONE,execution,null)){
                assertOracle(expected,actual);
                assertEquals(1,actual.emission().chosenSeed().id(),()->"Independent grouped vectors must select the later genuine seed: "+expected.stages());
            }
        });
    }
    private record StateKey(int row,int id){}
    private record EdgeKey(StateKey from,StateKey to){}
    private record Qualification(boolean complete,int imageBranch){}
    /** Image is two persistent parallel Gaussian branches; UNKNOWN retains native admission. No classifier call. */
    static LegacyGroupedExhaustiveOracle.EdgeAdmission independentParallelAdmission(SampledEmissionInput input,LegacyEmissionDomain domain,
            java.util.concurrent.atomic.AtomicInteger rejected)throws Exception{
        var context=input.context();var binding=domain.binding();var grid=context.nativeGrid();
        var first=grid.toGrid(binding.observations().get(0).measurementProfile().samplingAnchor().rasterPoint());
        var last=grid.toGrid(binding.observations().get(binding.observations().size()-1).measurementProfile().samplingAnchor().rasterPoint());
        double dx=last.x()-first.x(),dy=last.y()-first.y(),centerX=(first.x()+last.x())*.5,centerY=(first.y()+last.y())*.5;
        var allowed=new HashMap<EdgeKey,Boolean>();
        try(var session=NativeConnectorEvaluator.openAffinityProfiles(input)){
            for(int row=0;row+1<domain.rowCount();row++){
                var a=context.metricAt(binding.observations().get(row).measurementProfile().samplingAnchor().rasterPoint());
                var b=context.metricAt(binding.observations().get(row+1).measurementProfile().samplingAnchor().rasterPoint());
                double length=a.distanceTo(b);var normal=new MetricPoint(-(b.yMeters()-a.yMeters())/length,(b.xMeters()-a.xMeters())/length);
                var facts=new HashMap<StateKey,Qualification>();
                for(int r:List.of(row,row+1))for(var state:domain.observedAt(r)){
                    boolean complete=false;
                    if(state.evaluatorState().sourceClass()==NativeConnectorEvaluator.SourceClass.OBSERVED&&!state.peakOwner().syntheticCenter()){
                        var profile=session.metricAnchoredQuery(state.evaluatorState().row(),domain.rasterPoint(state),normal);
                        complete=session.bindNativeState(profile,domain,state).observation().availability()==NativeMeasuredProfileReceipt.Availability.COMPLETE;
                    }
                    var raw=grid.toGrid(domain.rasterPoint(state));double branch=-dy*(raw.x()-centerX)+dx*(raw.y()-centerY);
                    facts.put(new StateKey(r,state.nativeStateId()),new Qualification(complete,branch>0?1:branch<0?-1:0));
                }
                boolean protectedSplit=false;
                for(var event:binding.protectedOccurrences())if(event.leftOriginalRow()==row&&event.rightOriginalRow()==row+1)protectedSplit=true;
                for(var left:domain.observedAt(row))for(var right:domain.observedAt(row+1)){
                    var ka=new StateKey(row,left.nativeStateId());var kb=new StateKey(row+1,right.nativeStateId());var qa=facts.get(ka);var qb=facts.get(kb);
                    boolean admit=protectedSplit||!qa.complete()||!qb.complete()||qa.imageBranch()==0||qb.imageBranch()==0||qa.imageBranch()==qb.imageBranch();
                    allowed.put(new EdgeKey(ka,kb),admit);if(!admit)rejected.incrementAndGet();
                }
            }
        }
        return (a,b)->a.kind()!=LegacyEmissionDomain.Kind.OBSERVED_PEAK||b.kind()!=LegacyEmissionDomain.Kind.OBSERVED_PEAK||b.originalRow()!=a.originalRow()+1
            ||allowed.getOrDefault(new EdgeKey(new StateKey(a.originalRow(),a.nativeStateId()),new StateKey(b.originalRow(),b.nativeStateId())),true);
    }
    static void assertOracle(LegacyGroupedExhaustiveOracle.Result expected,LegacyEmissionKernel.GroupedResult actual){
        var emission=actual.emission();assertEquals(expected.seed(),emission.chosenSeed().id());assertEquals(expected.first(),emission.firstStage());assertEquals(expected.stitched(),emission.stitched());
        assertEquals(expected.firstPoints(),emission.firstStageVertices().stream().map(ManagedGroupedCompositionTest::point).toList());
        assertEquals(expected.points(),emission.inferenceVertices().stream().map(ManagedGroupedCompositionTest::point).toList());
        assertEquals(expected.stages().size(),actual.stages().size());
        for(int i=0;i<expected.stages().size();i++){
            var e=expected.stages().get(i);var a=actual.stages().get(i);assertEquals(e.seed(),a.seedId());assertEquals(e.ordinal(),a.ordinal());
            assertEquals(Optional.ofNullable(e.prefix()),a.prefix());assertEquals(Optional.ofNullable(e.terminal()),a.terminal());
            if(a.ordinaryWitness().isPresent())assertTrue(a.terminal().orElseThrow().total()<=a.ordinaryWitness().orElseThrow().total());
        }
    }
    static LegacyGroupedExhaustiveOracle.Point point(EmissionResult.Vertex v){return new LegacyGroupedExhaustiveOracle.Point(v.position(),v.owner(),v.nativeStateId().orElse(null));}
    @Test void actualManagedDomainSelectsBothFullGroupedEnginesAndPublishesOwnedFacts()throws Exception{
        var fixture=new ManagedOrdinaryAffinityTest();fixture.cache=cache;
        fixture.managed(new ManagedOrdinaryAffinityTest.Spec(.60,true,false,false,CancellationProbe.NONE,12,true),f->{
            var profiles=f.input().profiles().levelZeroProfiles();var seeds=profiles.get(0).peaks().stream().map(p->p.offsetPx()).toList();
            for(var execution:LegacyEmissionKernel.GroupedExecution.values()){
                try(var progress=LegacyEmissionProgress.open(f.input(),QUERY,EXEC);
                    var result=solve(f.input(),profiles,seeds,List.of(),(p,o,s)->o,CancellationProbe.NONE,execution,progress)){
                    assertEquals(execution,result.execution());assertEquals(execution,progress.view().groupedExecution());
                    assertTrue(result.emission().sourceReference().modelPolicyId().startsWith("LEGACY_GROUPED_MANAGED_LOCAL_CONNECTOR_V1/"));
                    assertTrue(result.emission().sourceReference().modelPolicyId().contains(execution.name()));
                    assertTrue(result.emission().managedAffinity().isPresent());assertFalse(result.emission().affinity().isPresent());
                    assertEquals(seeds.size(),result.stages().size());assertTrue(result.stages().stream().allMatch(s->s.ordinal()==0));
                    assertEquals(result.work(),f.input().workBudget().snapshot());assertEquals(LegacyEmissionProgress.Termination.SUCCESS,progress.view().termination());
                    assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,result.emission().managedAffinity().orElseThrow().globalModeFamilyAvailability());
                }
            }
        });
    }
    static LegacyEmissionKernel.GroupedResult solve(SampledEmissionInput input,List<CrossSectionProfile> profiles,List<Double> seeds,
            List<LegacyEmissionKernel.AnchorProposal> anchors,LegacyEmissionKernel.Smoother smoother,CancellationProbe cancellation,
            LegacyEmissionKernel.GroupedExecution execution,LegacyEmissionProgress progress)throws Exception{
        try{
            var method=LegacyEmissionKernel.class.getMethod("solveManagedGroupedChartAffinity",SampledEmissionInput.class,List.class,List.class,List.class,
                LegacyEmissionKernel.Smoother.class,CancellationProbe.class,NativeConnectorEvaluator.QueryPolicy.class,NativeConnectorEvaluator.ExecutionPolicy.class,
                LegacyEmissionKernel.GroupedExecution.class,LegacyEmissionProgress.class);
            return (LegacyEmissionKernel.GroupedResult)method.invoke(new LegacyEmissionKernel(),input,profiles,seeds,anchors,smoother,cancellation,QUERY,EXEC,execution,progress);
        }catch(NoSuchMethodException missing){throw new AssertionError("Reviewed managed-grouped entry is missing",missing);}
        catch(InvocationTargetException wrapped){if(wrapped.getCause() instanceof RuntimeException failure)throw failure;throw wrapped;}
    }
}

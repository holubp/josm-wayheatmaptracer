package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Real owned managed source fixture: no reconstructed Context or classifier replacement. */
class ManagedOwnedGuideTest {
    @TempDir Path cache;
    static final NativeConnectorEvaluator.QueryPolicy QUERY=NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1;
    static final NativeConnectorEvaluator.ExecutionPolicy EXEC=NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualManagedGuideMatchesIndependentFullGraphIncludingUnknownNativeOrigins(boolean joined)throws Exception {
        var fixture=new ManagedOrdinaryAffinityTest();fixture.cache=cache;
        fixture.managed(new ManagedOrdinaryAffinityTest.Spec(.60,true,false,false,CancellationProbe.NONE,12,joined),f->{
            long baseline=f.ledger().currentBytes();
            try(var provider=new RidgeTracker().prepareOwnedOrdinaryGuide(f.input(),CancellationProbe.NONE)) {
                var rejected=new java.util.concurrent.atomic.AtomicInteger();
                var expected=LegacyGroupedExhaustiveOracle.enumerate(f.input(),provider.profiles(),provider.seeds(),provider.proposals(),QUERY,EXEC,
                    domain->ManagedGroupedCompositionTest.independentParallelAdmission(f.input(),domain,rejected));
                try(var p=LegacyEmissionProgress.open(f.input(),QUERY,EXEC);
                    var actual=new LegacyEmissionKernel().solveOwnedGuideManagedGrouped(f.input(),provider,(a,b,c)->b,CancellationProbe.NONE,QUERY,EXEC,p)) {
                    var grouped=actual.grouped().orElseThrow();ManagedGroupedCompositionTest.assertOracle(expected,grouped);
                    assertEquals(actual.work(),f.input().workBudget().snapshot());assertEquals(LegacyEmissionProgress.Termination.SUCCESS,p.view().termination());
                    assertEquals(grouped.stages().size(),actual.comparisons().size());
                    assertTrue(grouped.emission().managedAffinity().isPresent());
                    if(!joined)assertTrue(rejected.get()>0);
                    assertTrue(actual.modelIdentity().contains("managed="+ManagedOrdinaryAffinity.POLICY_ID));
                }
                if(joined)try(var owner=f.input().openExecutionOwner("unknown-native-origin-control")) {
                    var profiles=f.input().legacyBinding().orElseThrow().observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList();
                    var domain=new LegacyEmissionDomain(f.input().legacyBinding().orElseThrow(),profiles,List.of(0.0),f.input().workBudget(),owner);boolean unknown=false;
                    try(var classifier=new ManagedOrdinaryAffinity(f.input(),domain)) {
                        for(int row=0;row+1<domain.rowCount();row++)for(var a:domain.observedAt(row))for(var b:domain.observedAt(row+1))
                            unknown|=classifier.classify(a,b).status()==OrdinaryAffinityClassifier.Status.UNKNOWN;
                    }assertTrue(unknown,"Actual merged native origins retain an UNKNOWN route");
                }
            }assertEquals(baseline,f.ledger().currentBytes());
        });
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void supplierRevocationCannotPublishWitnessOrResealClosedObserver(boolean cancel)throws Exception {
        var fixture=new ManagedOrdinaryAffinityTest();fixture.cache=cache;
        fixture.managed(new ManagedOrdinaryAffinityTest.Spec(.60,true,false,false,CancellationProbe.NONE,12,true),f->{
            long baseline=f.ledger().currentBytes();var fired=new java.util.concurrent.atomic.AtomicBoolean();
            var frozen=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();var primary=new java.util.concurrent.CancellationException("synthetic guide final-source refusal");
            try(var p=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)) {
                f.settingsHook().set(()->{
                    if(!fired.get()&&onStack("ownedWitness")&&finalTicket()) {
                        fired.set(true);p.close();try{frozen.set(ManagedGroupedCompositionTest.observerFields(p));}catch(Exception failure){throw new AssertionError(failure);}
                        if(cancel)throw primary;
                    }
                });
                var failure=assertThrows(RuntimeException.class,()->new RidgeTracker().trackOwnedGuideWitnesses(f.input(),CancellationProbe.NONE,QUERY,EXEC,p));
                assertTrue(fired.get());assertEquals(frozen.get(),ManagedGroupedCompositionTest.observerFields(p));assertThrows(IllegalStateException.class,p::view);
                assertEquals(baseline,f.ledger().currentBytes());
                if(cancel){assertSame(primary,failure);assertSame(primary,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));}
                else {assertInstanceOf(IllegalArgumentException.class,failure);f.input().requireOpenAndUnchanged();}
            }
        });
    }

    @Test void previouslyCheckedOriginalProfileMutationAtLaterTicketPaymentPreventsPublication()throws Exception {
        var fixture=new ManagedOrdinaryAffinityTest();fixture.cache=cache;
        fixture.managed(new ManagedOrdinaryAffinityTest.Spec(.60,true,false,false,CancellationProbe.NONE,12,true),f->{
            var fired=new java.util.concurrent.atomic.AtomicBoolean();
            try(var p=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)) {
                long baseline=f.ledger().currentBytes();
                f.settingsHook().set(()->{
                    if(!fired.get()&&onStack("ownedWitness")&&finalTicket()){
                        fired.set(true);var normal=f.input().legacyBinding().orElseThrow().observations().get(0).profile().normalScreen();normal.x=Math.nextUp(normal.x);
                    }
                });
                assertThrows(IllegalStateException.class,()->new RidgeTracker().trackOwnedGuideWitnesses(f.input(),CancellationProbe.NONE,QUERY,EXEC,p));
                assertTrue(fired.get());assertEquals(0,p.view().count(LegacyEmissionProgress.Counter.WITNESS_CERTIFIED));assertEquals(baseline,f.ledger().currentBytes());
            }
        });
    }

    @Test void realProductionForwarderUsesSameOwnedPreparationAndHasNoLateCallback()throws Exception {
        var fixture=new ManagedOrdinaryAffinityTest();fixture.cache=cache;
        fixture.managed(new ManagedOrdinaryAffinityTest.Spec(.60,true,false,false,CancellationProbe.NONE,12,true),f->{
            long baseline=f.ledger().currentBytes();List<?> comparisons;List<?> vertices;Object costs;
            try(var result=new RidgeTracker().trackOwnedGuideEmission(f.input(),CancellationProbe.NONE,QUERY,EXEC,null)){
                comparisons=result.comparisons();vertices=result.grouped().orElseThrow().emission().smoothedVertices();costs=result.grouped().orElseThrow().emission().stitched();
            }assertEquals(baseline,f.ledger().currentBytes());
            try(var p=LegacyEmissionProgress.open(f.input(),QUERY,EXEC)) {
                var late=new java.util.concurrent.atomic.AtomicBoolean();CancellationProbe probe=()->{try{p.view();late.set(true);}catch(IllegalStateException unsealed){}return false;};
                try(var result=new RidgeTracker().trackOwnedGuideEmission(f.input(),probe,QUERY,EXEC,p)){
                    assertEquals(comparisons,result.comparisons());assertEquals(vertices,result.grouped().orElseThrow().emission().smoothedVertices());assertEquals(costs,result.grouped().orElseThrow().emission().stitched());
                    assertFalse(late.get());assertEquals(result.work(),f.input().workBudget().snapshot());
                }
            }assertEquals(baseline,f.ledger().currentBytes());
        });
    }
    static boolean onStack(String name){return Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(s->s.getMethodName().equals(name));}
    static boolean finalTicket(){return Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(s->s.getClassName().equals(OptionalEmissionContextFactory.ManagedCaptureBinding.class.getName()+"$FinalPublicationCheck")&&s.getMethodName().equals("validate"));}
}

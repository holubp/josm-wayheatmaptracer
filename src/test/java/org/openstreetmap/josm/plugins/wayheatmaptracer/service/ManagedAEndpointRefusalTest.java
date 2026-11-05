package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class ManagedAEndpointRefusalTest {
    @TempDir Path cache;
    @ParameterizedTest @ValueSource(strings={"work","cancel","source"})
    void failureAtActualProducerEntryRevokesTheUnpublishedPermitImmediately(String kind)throws Exception{
        var cancelled=new AtomicBoolean();var helper=new ManagedAEndpointProducerTest();helper.cache=cache;
        helper.withCapture(false,true,.25,cancelled::get,f->{
            long before=f.ledger().currentBytes();
            try(var permit=ManagedAEndpointProducerTest.bind(f)){
                induce(kind,f,cancelled,new AtomicReference<>(),null);
                var failure=assertThrows(RuntimeException.class,()->ManagedAEndpointProducerTest.produce(f,permit));
                assertKind(kind,failure);
                assertTrue(permit.retainedOwner().isClosed(),"Failed unpublished A production must immediately release its admitted owner");
                assertEquals(before,f.ledger().currentBytes());
                assertSame(failure,assertThrows(RuntimeException.class,permit::beginProduction));
            }
        });
    }
    @ParameterizedTest @ValueSource(strings={"work","cancel","memory","source"})
    void nativeProducerRefusalPublishesNoInputAndRollsBackItsWholeOwnership(String kind)throws Exception{
        var callback=new AtomicReference<Runnable>();var cancelled=new AtomicBoolean();
        var helper=new ManagedAEndpointProducerTest();helper.cache=cache;
        helper.withCapture(false,true,.25,()->{var r=callback.get();if(r!=null)r.run();return cancelled.get();},f->{
            long baseline=f.ledger().currentBytes();var pressure=new AtomicReference<AttemptMemoryLedger.Reservation>();var fired=new AtomicBoolean();
            try(var permit=ManagedAEndpointProducerTest.bind(f)){
                callback.set(()->{if(!fired.get()&&ManagedAEndpointPublicationTest.stack("RenderedHeatmapSampler","sampleManagedARetainingFloat")){
                    fired.set(true);induce(kind,f,cancelled,pressure,null);
                }});
                try{
                    var failure=assertThrows(RuntimeException.class,()->ManagedAEndpointProducerTest.produce(f,permit));
                    assertTrue(fired.get());assertKind(kind,failure);
                    assertSame(failure,assertThrows(RuntimeException.class,()->f.budget().charge(EmissionWorkBudget.Operation.PREPARATION,0)));
                    assertSame(failure,assertThrows(RuntimeException.class,permit::beginProduction));
                }finally{callback.set(null);if(pressure.get()!=null)pressure.get().close();}
            }
            assertEquals(baseline,f.ledger().currentBytes());
        });
    }
    @ParameterizedTest @ValueSource(strings={"work","cancel","memory","source","coarse","input-close"})
    void laterOuterCopyAdmissionCannotPublishStaleOrRefusedResults(String kind)throws Exception{
        var callback=new AtomicReference<Runnable>();var cancelled=new AtomicBoolean();var ready=new AtomicBoolean();var armed=new AtomicBoolean();
        var helper=new ManagedAEndpointProducerTest();helper.cache=cache;
        helper.withCapture(false,true,.25,()->{var r=callback.get();if(r!=null)r.run();return cancelled.get();},f->{
            long captureBytes=f.ledger().currentBytes();var pressure=new AtomicReference<AttemptMemoryLedger.Reservation>();var fired=new AtomicBoolean();
            try(var permit=ManagedAEndpointProducerTest.bind(f,()->{
                    if(armed.get()&&ManagedAEndpointPublicationTest.finalSupplier()&&ManagedAEndpointPublicationTest.stack("NativeAEmissionSolver","solve"))ready.set(true);
                    return f.settings();
                });var input=ManagedAEndpointProducerTest.produce(f,permit)){
                long baseline=f.ledger().currentBytes();armed.set(true);
                callback.set(()->{if(ready.get()&&!fired.get()&&ManagedAEndpointPublicationTest.stack("SampledEmissionInput","prepareManagedFinalPublication")
                        &&ManagedAEndpointPublicationTest.stack("CorridorAwareTracker","trackOptionalEmissionBound")&&!ManagedAEndpointPublicationTest.stack("NativeAEmissionSolver","solve")){
                    fired.set(true);induce(kind,f,cancelled,pressure,input);
                }});
                var published=new AtomicReference<CorridorAwareTracker.OptionalEmissionTracking>();
                try{
                    var failure=assertThrows(RuntimeException.class,()->published.set(new CorridorAwareTracker().trackManagedAEndpointEmission(input,ManagedAEndpointExecutionTest.QUERY,ManagedAEndpointExecutionTest.EXECUTION)));
                    assertTrue(fired.get(),"Must reach actual late outer prepayment after the inner complete scan");assertKind(kind,failure);assertNull(published.get());
                    if(kind.equals("work")||kind.equals("cancel")||kind.equals("memory"))assertSame(failure,assertThrows(RuntimeException.class,input::requireOpenAndUnchanged));
                }finally{callback.set(null);if(published.get()!=null)published.get().close();if(pressure.get()!=null)pressure.get().close();}
                assertEquals(kind.equals("input-close")?captureBytes:baseline,f.ledger().currentBytes());
            }
        });
    }
    @Test void firstActualNativePairVisitHonorsTheUnchangedPairCap()throws Exception{
        var helper=new ManagedAEndpointProducerTest();helper.cache=cache;
        helper.withCapture(false,true,.25,CancellationProbe.NONE,f->{try(var permit=ManagedAEndpointProducerTest.bind(f);var input=ManagedAEndpointProducerTest.produce(f,permit)){
            long baseline=f.ledger().currentBytes();var budget=f.budget();budget.charge(EmissionWorkBudget.Operation.TRANSITION,budget.pairLimit()-budget.pairVisits());
            var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->new CorridorAwareTracker().trackManagedAEndpointEmission(input,ManagedAEndpointExecutionTest.QUERY,ManagedAEndpointExecutionTest.EXECUTION));
            assertEquals(EmissionWorkBudget.LimitKind.PAIR_VISITS,failure.kind());assertSame(failure,assertThrows(RuntimeException.class,input::requireOpenAndUnchanged));
            assertEquals(baseline,f.ledger().currentBytes());
        }});
    }
    @Test void prearmedCancellationAtFirstPermitValidationCreatesNoOwnerOrInput()throws Exception{
        var cancelled=new AtomicBoolean();var helper=new ManagedAEndpointProducerTest();helper.cache=cache;
        helper.withCapture(false,true,.25,cancelled::get,f->{long before=f.ledger().currentBytes();cancelled.set(true);
            var failure=assertThrows(CancellationException.class,()->ManagedAEndpointProducerTest.bind(f));
            assertSame(failure,assertThrows(RuntimeException.class,()->f.budget().charge(EmissionWorkBudget.Operation.PREPARATION,0)));assertEquals(before,f.ledger().currentBytes());
        });
    }
    static void induce(String kind,ManagedAEndpointProducerTest.F f,AtomicBoolean cancelled,AtomicReference<AttemptMemoryLedger.Reservation> pressure,SampledEmissionInput input){
        switch(kind){
            case "work" -> f.budget().charge(EmissionWorkBudget.Operation.PREPARATION,f.budget().workLimit()-f.budget().workUsed());
            case "cancel" -> cancelled.set(true);
            case "memory" -> pressure.set(f.owner().reserve(f.ledger().limitBytes()-f.ledger().currentBytes()));
            case "source" -> {var node=f.nodes().get(0);node.setCoor(new LatLon(node.lat()+1e-7,node.lon()));}
            case "coarse" -> input.profiles().levels().get(1).profiles().get(0).normalScreen().x+=.125;
            case "input-close" -> input.close();
            default -> throw new AssertionError(kind);
        }
    }
    static void assertKind(String kind,RuntimeException failure){
        switch(kind){
            case "work" -> {assertInstanceOf(EmissionWorkBudget.LimitExceededException.class,failure);assertEquals(EmissionWorkBudget.LimitKind.TOTAL_WORK,((EmissionWorkBudget.LimitExceededException)failure).kind());}
            case "cancel" -> assertInstanceOf(CancellationException.class,failure);
            case "memory" -> assertInstanceOf(AttemptMemoryLedger.ResourceLimitException.class,failure);
            default -> assertInstanceOf(IllegalStateException.class,failure);
        }
    }
}

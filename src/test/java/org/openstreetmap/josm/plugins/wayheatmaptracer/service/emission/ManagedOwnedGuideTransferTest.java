package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RidgeTracker;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class ManagedOwnedGuideTransferTest {
    @TempDir Path cache;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void finalSupplierHandleCloseCannotRevokeTransferredPayload(boolean full)throws Exception {boundary(full,"close");}
    @ParameterizedTest @ValueSource(strings={"cancel","work","memory","observer","observer_cancel"})
    void finalBoundaryFailurePreservesPrimaryObserverAndOwnedCleanup(String effect)throws Exception {
        for(boolean full:List.of(false,true))boundary(full,effect);
    }
    void boundary(boolean full,String effect)throws Exception {
        var fixture=new ManagedOrdinaryAffinityTest();fixture.cache=cache;
        fixture.managed(new ManagedOrdinaryAffinityTest.Spec(.60,true,false,false,CancellationProbe.NONE,12,true),f->{
            long baseline=f.ledger().currentBytes();var fired=new AtomicBoolean();var beforeClose=new AtomicLong();var afterClose=new AtomicLong();
            var primary=new AtomicReference<RuntimeException>();var frozen=new AtomicReference<Map<String,Object>>();
            try(var provider=new RidgeTracker().prepareOwnedOrdinaryGuide(f.input(),CancellationProbe.NONE);
                var p=LegacyEmissionProgress.open(f.input(),ManagedOwnedGuideTest.QUERY,ManagedOwnedGuideTest.EXEC)) {
                f.settingsHook().set(()->{
                    if(!fired.get()&&ManagedOwnedGuideTest.finalTicket()&&ManagedOwnedGuideTest.onStack("ownedGuideExecution")
                            &&!ManagedOwnedGuideTest.onStack("ownedWitness")&&!ManagedOwnedGuideTest.onStack("solveBody")) {
                        fired.set(true);beforeClose.set(f.ledger().currentBytes());provider.close();afterClose.set(f.ledger().currentBytes());
                        if(effect.startsWith("observer")){p.close();try{frozen.set(ManagedGroupedCompositionTest.observerFields(p));}catch(Exception failure){throw new AssertionError(failure);}}
                        try {switch(effect) {
                            case "cancel","observer_cancel"->throw new java.util.concurrent.CancellationException("synthetic guide transfer publication refusal");
                            case "work"->f.input().workBudget().charge(EmissionWorkBudget.Operation.PREPARATION,Long.MAX_VALUE);
                            case "memory"->f.retainedOwner().reserve(Long.MAX_VALUE);
                        }}catch(RuntimeException failure){primary.set(failure);throw failure;}
                    }
                });
                if(effect.equals("close"))try(var result=solve(f.input(),provider,full,p)) {
                    assertTrue(fired.get());assertEquals(beforeClose.get(),afterClose.get(),"The final supplier sees an already-transferred owner");
                    long retained=f.ledger().currentBytes();provider.close();assertEquals(retained,f.ledger().currentBytes());
                    assertEquals(full,result.grouped().isPresent());assertFalse(result.comparisons().isEmpty());assertEquals(result.work(),f.input().workBudget().snapshot());
                    assertEquals(LegacyEmissionProgress.Termination.SUCCESS,p.view().termination());
                } else {
                    var failure=assertThrows(RuntimeException.class,()->solve(f.input(),provider,full,p));assertTrue(fired.get());
                    assertEquals(beforeClose.get(),afterClose.get(),"Old facade closure cannot release transferred payload, including a later refusal");
                    if(primary.get()!=null){assertSame(primary.get(),failure);assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));}
                    else{assertInstanceOf(IllegalArgumentException.class,failure);f.input().requireOpenAndUnchanged();}
                    if(frozen.get()!=null){assertEquals(frozen.get(),ManagedGroupedCompositionTest.observerFields(p));assertThrows(IllegalStateException.class,p::view);}
                    else assertNotEquals(LegacyEmissionProgress.Termination.SUCCESS,p.view().termination());
                }
                assertEquals(baseline+(effect.startsWith("observer")?0:LegacyEmissionProgress.STORAGE_BYTES),f.ledger().currentBytes());
            }
            assertEquals(baseline,f.ledger().currentBytes());
        });
    }
    static LegacyEmissionKernel.OwnedGuideResult solve(SampledEmissionInput input,RidgeTracker.OwnedOrdinaryGuide provider,boolean full,LegacyEmissionProgress progress) {
        var kernel=new LegacyEmissionKernel();
        return full?kernel.solveOwnedGuideManagedGrouped(input,provider,(a,b,c)->b,CancellationProbe.NONE,ManagedOwnedGuideTest.QUERY,ManagedOwnedGuideTest.EXEC,progress):
            kernel.evaluateOwnedOrdinaryWitnesses(input,provider,CancellationProbe.NONE,ManagedOwnedGuideTest.QUERY,ManagedOwnedGuideTest.EXEC,progress);
    }
}

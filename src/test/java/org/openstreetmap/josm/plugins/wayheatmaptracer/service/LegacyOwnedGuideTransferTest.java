package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class LegacyOwnedGuideTransferTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void oldPreparedHandleCannotReleaseLiveReturnedPayload(boolean full)throws Exception {
        try(var f=LegacyCompetitiveProofTest.fixture(false,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE))) {
            long baseline=f.ledger().currentBytes();
            try(var provider=new RidgeTracker().prepareOwnedOrdinaryGuide(f.input(),CancellationProbe.NONE)) {
                try(var result=solve(f.input(),provider,full,null)) {
                    long retained=f.ledger().currentBytes();assertTrue(retained>baseline);var work=f.input().workBudget().snapshot();
                    provider.close();provider.close();
                    assertEquals(retained,f.ledger().currentBytes(),"The caller's consumed handle cannot revoke result-owned prepared payload");
                    assertFalse(result.comparisons().isEmpty());assertEquals(full,result.grouped().isPresent());assertEquals(work,result.work());assertEquals(work,f.input().workBudget().snapshot());
                    assertThrows(IllegalArgumentException.class,()->provider.base(f.input(),0,0));
                    result.close();result.close();assertThrows(IllegalStateException.class,result::comparisons);
                }
                assertEquals(baseline,f.ledger().currentBytes());
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void closedProviderBeforeEntryCannotTransferOrMutateProgress(boolean full)throws Exception {
        try(var f=LegacyCompetitiveProofTest.fixture(false,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE));
            var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            long baseline=f.ledger().currentBytes();var provider=new RidgeTracker().prepareOwnedOrdinaryGuide(f.input(),CancellationProbe.NONE);provider.close();provider.close();
            assertEquals(baseline,f.ledger().currentBytes());var before=LegacyProofProgressOwnershipTest.snapshot(p,f,f,f.input().workBudget());
            assertThrows(IllegalArgumentException.class,()->solve(f.input(),provider,full,p));
            LegacyProofProgressOwnershipTest.assertUnchanged(before,LegacyProofProgressOwnershipTest.snapshot(p,f,f,f.input().workBudget()));
            assertThrows(IllegalStateException.class,p::view);f.input().requireOpenAndUnchanged();
        }
    }
    @Test void singleUseHandoffHasNoChargeAllocationReservationOrCallbackAfterPreparation()throws Exception {
        var armed=new AtomicBoolean();var callbacks=new AtomicInteger();CancellationProbe callback=()->{if(armed.get())callbacks.incrementAndGet();return false;};
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),callback);
        try(var a=LegacyCompetitiveProofTest.fixture(false,budget);var b=LegacyCompetitiveProofTest.fixture(false,budget)) {
            long baseline=a.ledger().currentBytes();long allocationBefore=budget.operationUnits(EmissionWorkBudget.Operation.ALLOCATION);
            try(var provider=new RidgeTracker().prepareOwnedOrdinaryGuide(a.input(),CancellationProbe.NONE)) {
                long peaks=a.profiles().stream().mapToLong(p->p.peaks().size()).sum();
                assertEquals(130+64*(peaks+a.profiles().size()),budget.operationUnits(EmissionWorkBudget.Operation.ALLOCATION)-allocationBefore,"Execution owner plus fixed129 credits includes the sole lease allocation");
                var work=budget.snapshot();long retained=a.ledger().currentBytes(),peak=a.ledger().peakBytes();armed.set(true);
                assertThrows(IllegalArgumentException.class,()->provider.transferToResult(b.input()));assertEquals(work,budget.snapshot());assertEquals(retained,a.ledger().currentBytes());
                try(var lease=provider.transferToResult(a.input())) {
                    assertThrows(IllegalArgumentException.class,()->provider.transferToResult(a.input()));provider.close();
                    assertFalse(lease.isClosed());assertEquals(work,budget.snapshot());assertEquals(retained,a.ledger().currentBytes());assertEquals(peak,a.ledger().peakBytes());assertEquals(0,callbacks.get());
                    lease.close();lease.close();assertTrue(lease.isClosed());assertEquals(baseline,a.ledger().currentBytes());
                }
            }
        }
    }
    @Test void theSoleLeaseMemoryCreditIsAdmittedBeforeAnyProviderCanBeIssued()throws Exception {
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var f=LegacyCompetitiveProofTest.fixture(false,budget);var pressure=f.owner().child("synthetic-preparation-lease-pressure")) {
            long baseline=f.ledger().currentBytes(),count=f.profiles().stream().mapToLong(p->p.peaks().size()).sum();
            long samples=f.profiles().stream().mapToLong(p->p.intensitySamples().size()).sum(),max=f.profiles().stream().mapToLong(p->p.peaks().size()).max().orElseThrow();
            long oldPayload=4096+1024L*f.profiles().size()+512*count+32*samples+256*Math.min(count,12*max);
            pressure.reserve(f.ledger().limitBytes()-baseline-oldPayload-32);
            var failure=assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->new RidgeTracker().prepareOwnedOrdinaryGuide(f.input(),CancellationProbe.NONE));
            assertEquals(oldPayload+64,failure.requestedBytes());assertSame(failure,assertThrows(RuntimeException.class,f.input()::requireOpenAndUnchanged));
            pressure.close();assertEquals(baseline,f.ledger().currentBytes());
        }
    }
    static LegacyEmissionKernel.OwnedGuideResult solve(SampledEmissionInput input,RidgeTracker.OwnedOrdinaryGuide provider,boolean full,LegacyEmissionProgress progress) {
        var kernel=new LegacyEmissionKernel();
        return full?kernel.solveOwnedGuideManagedGrouped(input,provider,(a,b,c)->b,CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,progress):
            kernel.evaluateOwnedOrdinaryWitnesses(input,provider,CancellationProbe.NONE,LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,progress);
    }
}

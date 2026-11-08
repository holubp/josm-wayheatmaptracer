package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ContinuityAttemptLedger.*;

/** Pure budget/owner tests only: no sampler, evaluator, inference engine or private input. */
class V022ContinuityAttemptLedgerTest {
    static List<Family> families(int n){List<Family> a=new ArrayList<>();for(int i=0;i<n;i++)a.add(new Family(i,"label-"+(n-i),List.of(2,3,2)));return a;}
    @Test void oneTwoThreeAndEightFamiliesReceiveFixedPhysicalOrderRaw32Quotas() {
        for(int n:List.of(1,2,3,8)) {
            var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()) {
                var input=new ArrayList<>(families(n));Collections.reverse(input);
                var plan=ContinuityAttemptLedger.plan(input,TraceBudgets.defaults(),CancellationProbe.NONE,100,new AtomicLong()::get,owner);
                assertEquals(n,plan.reservations().size());assertEquals(32,plan.reservations().stream().mapToInt(Reservation::rawQuota).sum());
                for(int i=0;i<n;i++){var r=plan.reservations().get(i);assertEquals(i,r.family().physicalOrdinal());assertEquals(32/n+(i<32%n?1:0),r.rawQuota());assertTrue(r.distinctLimit()<=r.rawQuota());assertEquals(12,r.preparationPairSlots());}
            }finally{assertEquals(0,ledger.currentBytes());}
        }
    }
    @Test void completedAndFailedActualWorkSharesOneRemainingBudgetWithoutRetryOrRawRefund() {
        var memory=AttemptMemoryLedger.production();try(var owner=memory.rootOwner()) {
            ContinuityAttemptLedger<String> ledger=ContinuityAttemptLedger.plan(families(3),new TraceBudgets(96,100,100,32,8),CancellationProbe.NONE,100,new AtomicLong()::get,owner);
            var calls=new ArrayList<TraceBudgets>();var result=ledger.runAll(lease->{
                calls.add(lease.limits());lease.progress(new Work(12,3,5));
                return calls.size()==1?Outcome.complete(new Work(12,5,7),1,1,"conditional-one"):Outcome.failed(new Work(12,4,9));
            });
            assertEquals(2,calls.size());assertEquals(64,calls.get(0).maximumPairVisits());assertEquals(59,calls.get(1).maximumPairVisits());assertEquals(93,calls.get(1).maximumTransitions());
            assertEquals(11,calls.get(0).maximumRawAlternatives());assertEquals(11,calls.get(1).maximumRawAlternatives());
            assertEquals(Status.INCOMPLETE,result.status());assertEquals(new Work(24,9,16),result.exactWork().orElseThrow());assertEquals(22,result.rawRequested());assertFalse(result.allRequiredComplete());assertFalse(result.pooledPosteriorAvailable());assertFalse(result.globalExactK32());
        }finally{assertEquals(0,memory.currentBytes());}
    }
    static TraceBudgets caps(long pairs,long scores){return new TraceBudgets(96,pairs,scores,32,8);}
    @Test void labelsAndInputPermutationCannotChangePhysicalRemainderQuotas() {
        var memory=AttemptMemoryLedger.production();try(var owner=memory.rootOwner()) {
            var renamed=List.of(new Family(2,"A",List.of(2,3,2)),new Family(0,"Z",List.of(2,3,2)),new Family(1,"Z",List.of(2,3,2)));
            var plan=ContinuityAttemptLedger.plan(renamed,caps(100,100),CancellationProbe.NONE,100,new AtomicLong()::get,owner);
            assertEquals(List.of(11,11,10),plan.reservations().stream().map(Reservation::rawQuota).toList());assertEquals(List.of(0,1,2),plan.reservations().stream().map(r->r.family().physicalOrdinal()).toList());
        }finally{assertEquals(0,memory.currentBytes());}
    }
    @Test void ninthMissingAndAmbiguousRequiredFamiliesRefuseBeforeAnyExecutor() {
        for(var input:List.of(families(9),List.<Family>of(),List.of(families(1).get(0),families(1).get(0)))) {
            var memory=AttemptMemoryLedger.production();try(var owner=memory.rootOwner()) {
                ContinuityAttemptLedger<String> ledger=ContinuityAttemptLedger.plan(input,caps(1000,1000),CancellationProbe.NONE,100,new AtomicLong()::get,owner);var calls=new AtomicInteger();
                var result=ledger.runAll(lease->{calls.incrementAndGet();throw new AssertionError("Refused plan entered executor");});assertEquals(0,calls.get());assertFalse(result.allRequiredComplete());assertTrue(result.families().isEmpty());
                assertEquals(input.size()==9?Status.UNRESOLVED_CAPACITY:input.isEmpty()?Status.INCOMPLETE:Status.INVALID_ORDER,result.status());
            }finally{assertEquals(0,memory.currentBytes());}
        }
    }
    @Test void exactRepeatedPreparationInventoryAndZeroRemainderStopWithoutInvalidBudgets() {
        for(long pairs:List.of(23L,24L)) {
            var memory=AttemptMemoryLedger.production();try(var owner=memory.rootOwner()) {
                ContinuityAttemptLedger<String> ledger=ContinuityAttemptLedger.plan(families(2),caps(pairs,100),CancellationProbe.NONE,100,new AtomicLong()::get,owner);var calls=new AtomicInteger();
                var r=ledger.runAll(lease->{calls.incrementAndGet();throw new AssertionError("No positive native pair budget");});assertEquals(0,calls.get());assertEquals(Status.RESOURCE_LIMIT,r.status());assertEquals(new Work(0,0,0),r.exactWork().orElseThrow());assertEquals(0,r.rawRequested());
            }finally{assertEquals(0,memory.currentBytes());}
        }
        var memory=AttemptMemoryLedger.production();try(var owner=memory.rootOwner()) {
            ContinuityAttemptLedger<String> ledger=ContinuityAttemptLedger.plan(families(2),caps(100,3),CancellationProbe.NONE,100,new AtomicLong()::get,owner);var calls=new AtomicInteger();
            var r=ledger.runAll(lease->{calls.incrementAndGet();return Outcome.complete(new Work(12,1,3),1,1,"first-only");});assertEquals(1,calls.get());assertEquals(Status.RESOURCE_LIMIT,r.status());assertFalse(r.allRequiredComplete());assertEquals(16,r.rawRequested());
        }finally{assertEquals(0,memory.currentBytes());}
    }
    @Test void missingFinalReceiptAndThrownFailureRetainPrefixButNeverAssumeZeroOrRetry() {
        for(boolean thrown:List.of(false,true)) {
            var memory=AttemptMemoryLedger.production();try(var owner=memory.rootOwner()) {
                ContinuityAttemptLedger<String> ledger=ContinuityAttemptLedger.plan(families(2),caps(100,100),CancellationProbe.NONE,100,new AtomicLong()::get,owner);var calls=new AtomicInteger();
                var r=ledger.runAll(lease->{calls.incrementAndGet();lease.progress(new Work(12,3,5));if(thrown)throw new Exception("fake failure");return Outcome.unavailable();});
                assertEquals(Status.UNAVAILABLE,r.status());assertEquals(new Work(12,3,5),r.knownPrefix());assertTrue(r.exactWork().isEmpty());assertTrue(r.rawReturned().isEmpty());assertEquals(16,r.rawRequested());assertTrue(r.families().get(0).conditionalResult().isEmpty());
                ledger.runAll(lease->{calls.incrementAndGet();throw new AssertionError("Retry forbidden");});assertEquals(1,calls.get());
            }finally{assertEquals(0,memory.currentBytes());}
        }
    }
    @Test void failedPreparationDebitsOnlyAttestedActualPrefixNotFutureReservedSlots() {
        var memory=AttemptMemoryLedger.production();try(var owner=memory.rootOwner()) {
            ContinuityAttemptLedger<String> ledger=ContinuityAttemptLedger.plan(families(2),caps(100,100),CancellationProbe.NONE,100,new AtomicLong()::get,owner);var calls=new AtomicInteger();
            var r=ledger.runAll(lease->{calls.incrementAndGet();lease.progress(new Work(5,0,0));return Outcome.failed(new Work(5,0,0));});
            assertEquals(1,calls.get());assertEquals(24,r.plannedPreparationPairs());assertEquals(new Work(5,0,0),r.exactWork().orElseThrow());assertEquals(Status.INCOMPLETE,r.status());assertEquals(16,r.rawRequested());assertFalse(r.allRequiredComplete());
        }finally{assertEquals(0,memory.currentBytes());}
    }
    @Test void invalidOrOverCapClaimsArePreservedAndTotalWorkBecomesUnavailable() {
        for(var invalid:List.of(new Work(12,2,5),new Work(12,100,5))) {
            var memory=AttemptMemoryLedger.production();try(var owner=memory.rootOwner()) {
                ContinuityAttemptLedger<String> ledger=ContinuityAttemptLedger.plan(families(2),caps(100,100),CancellationProbe.NONE,100,new AtomicLong()::get,owner);
                var r=ledger.runAll(lease->{lease.progress(new Work(12,3,5));lease.progress(invalid);return Outcome.complete(invalid,1,1,"cannot-escape");});
                assertEquals(Status.UNAVAILABLE,r.status());assertEquals(new Work(12,3,5),r.knownPrefix());assertTrue(r.exactWork().isEmpty());assertEquals(invalid,r.families().get(0).rejectedWorkClaim().orElseThrow());assertTrue(r.families().get(0).conditionalResult().isEmpty());
            }finally{assertEquals(0,memory.currentBytes());}
        }
    }
    @Test void duplicateAndUnusedRawSlotsRemainChargedWithNoNinthCallOrGlobalPool() {
        var memory=AttemptMemoryLedger.production();try(var owner=memory.rootOwner()) {
            ContinuityAttemptLedger<String> ledger=ContinuityAttemptLedger.plan(families(8),caps(1000,1000),CancellationProbe.NONE,100,new AtomicLong()::get,owner);var calls=new AtomicInteger();List<CancellationProbe> probes=new ArrayList<>();
            var r=ledger.runAll(lease->{calls.incrementAndGet();probes.add(lease.cancellation());assertEquals(4,lease.limits().maximumRawAlternatives());return Outcome.complete(new Work(12,1,2),1,1,"same-conditional-result");});
            assertEquals(8,calls.get());assertEquals(Status.COMPLETE,r.status());assertTrue(r.allRequiredComplete());assertEquals(32,r.rawRequested());assertEquals(8,r.rawReturned().orElseThrow());assertEquals(8,r.families().size());assertFalse(r.pooledPosteriorAvailable());assertFalse(r.globalExactK32());assertEquals(8,r.finalCandidateLimit());assertTrue(probes.stream().allMatch(p->p==probes.get(0)));
            ledger.runAll(lease->{calls.incrementAndGet();throw new AssertionError("No retries/extra alternatives");});assertEquals(8,calls.get());
        }finally{assertEquals(0,memory.currentBytes());}
    }
    @Test void revokedAndConcurrentCallCapabilitiesCannotSpendAgainOrSkipRequiredFamilies() {
        var memory=AttemptMemoryLedger.production();try(var owner=memory.rootOwner()) {
            ContinuityAttemptLedger<String> ledger=ContinuityAttemptLedger.plan(families(2),caps(100,100),CancellationProbe.NONE,100,new AtomicLong()::get,owner);var lease=ledger.reserveNext().orElseThrow();
            assertThrows(IllegalStateException.class,ledger::reserveNext);lease.finish(Outcome.complete(new Work(12,1,1),1,1,"first"));
            assertThrows(IllegalStateException.class,lease::limits);assertThrows(IllegalStateException.class,()->lease.progress(new Work(12,1,1)));lease.close();ledger.close();
            assertTrue(ledger.reserveNext().isEmpty());var r=ledger.report();assertEquals(Status.INCOMPLETE,r.status());assertFalse(r.allRequiredComplete());assertEquals(16,r.rawRequested());
        }finally{assertEquals(0,memory.currentBytes());}
    }
    @Test void sharedCancellationAndOuterDeadlineNeverSalvageCurrentCompleteGeometry() {
        for(boolean cancel:List.of(false,true)) {
            var memory=AttemptMemoryLedger.production();try(var owner=memory.rootOwner()) {
                var clock=new AtomicLong();var flag=new AtomicBoolean();ContinuityAttemptLedger<String> ledger=ContinuityAttemptLedger.plan(families(3),caps(100,100),flag::get,10,clock::get,owner);var calls=new AtomicInteger();
                var r=ledger.runAll(lease->{int call=calls.incrementAndGet();if(call==1)clock.set(6);else if(cancel)flag.set(true);else clock.set(11);return Outcome.complete(new Work(12,1,1),1,1,"conditional"+call);});
                assertEquals(2,calls.get());assertEquals(cancel?Status.CANCELLED:Status.DEADLINE,r.status());assertFalse(r.allRequiredComplete());assertTrue(r.families().get(1).conditionalResult().isEmpty());assertEquals(new Work(24,2,2),r.exactWork().orElseThrow());
            }finally{assertEquals(0,memory.currentBytes());}
        }
    }
    @Test void perFamilyScratchClosesAndOnlySuccessfulConditionalPayloadRemainsInSameAttempt() {
        for(boolean complete:List.of(false,true)) {
            var memory=AttemptMemoryLedger.production();try(var owner=memory.rootOwner()) {
                Object input=new Object();try(var charge=owner.reserve(128)){charge.adopt(input);}
                ContinuityAttemptLedger<Object> ledger=ContinuityAttemptLedger.plan(families(1),caps(100,100),CancellationProbe.NONE,100,new AtomicLong()::get,owner);List<AttemptMemoryLedger.Owner> scopes=new ArrayList<>();
                var r=ledger.runAll(lease->{scopes.add(lease.scratchOwner());scopes.add(lease.resultOwner());Object scratch=new Object(),payload=new Object();try(var charge=lease.scratchOwner().reserve(50000)){charge.adopt(scratch);}try(var charge=lease.resultOwner().reserve(1000)){charge.adopt(payload);}return complete?Outcome.complete(new Work(12,1,1),1,1,payload):Outcome.failed(new Work(12,1,1));});
                assertTrue(scopes.stream().allMatch(AttemptMemoryLedger.Owner::isClosed));assertTrue(memory.currentBytes()<memory.peakBytes()-45000);assertEquals(complete,r.families().get(0).conditionalResult().isPresent());assertTrue(owner.currentBytes()>=128);
            }finally{assertEquals(0,memory.currentBytes());}
        }
    }
    @Test void memoryRefusalAndClosedAttemptCannotMintAWorkLease() {
        var tiny=new AttemptMemoryLedger(64);try(var owner=tiny.rootOwner()){assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->ContinuityAttemptLedger.plan(families(1),caps(100,100),CancellationProbe.NONE,100,new AtomicLong()::get,owner));assertEquals(0,tiny.currentBytes());}
        var memory=AttemptMemoryLedger.production();var owner=memory.rootOwner();ContinuityAttemptLedger<String> ledger=ContinuityAttemptLedger.plan(families(1),caps(100,100),CancellationProbe.NONE,100,new AtomicLong()::get,owner);var lease=ledger.reserveNext().orElseThrow();owner.close();assertThrows(IllegalStateException.class,lease::limits);assertThrows(IllegalStateException.class,ledger::report);assertEquals(0,memory.currentBytes());
    }
}

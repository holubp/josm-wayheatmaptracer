package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Pure opt-in family-budget boundary. No inference adapter or source authority. */
final class ContinuityAttemptLedger<T> implements AutoCloseable {
    enum Status { READY, COMPLETE, INCOMPLETE, RESOURCE_LIMIT, CANCELLED, DEADLINE, UNAVAILABLE, UNRESOLVED_CAPACITY, INVALID_ORDER }
    record Family(int physicalOrdinal,String diagnosticId,List<Integer> originalStateCounts) {
        Family {originalStateCounts=List.copyOf(originalStateCounts);}
    }
    record Reservation(Family family,int rawQuota,int distinctLimit,long preparationPairSlots) { }
    enum Completion { COMPLETE, FAILED, REFUSED, UNAVAILABLE }
    record Work(long preparationPairs,long nativePairs,long scores) {
        Work {if(preparationPairs<0||nativePairs<0||scores<0)throw new IllegalArgumentException("Actual work must be nonnegative");}
    }
    record Outcome<T>(Completion completion,Optional<Work> exactWork,OptionalInt rawReturned,OptionalInt distinctReturned,T conditionalResult) {
        static <T> Outcome<T> complete(Work work,int raw,int distinct,T value){return new Outcome<>(Completion.COMPLETE,Optional.of(work),OptionalInt.of(raw),OptionalInt.of(distinct),value);}
        static <T> Outcome<T> failed(Work work){return new Outcome<>(Completion.FAILED,Optional.of(work),OptionalInt.of(0),OptionalInt.of(0),null);}
        static <T> Outcome<T> unavailable(){return new Outcome<>(Completion.UNAVAILABLE,Optional.empty(),OptionalInt.empty(),OptionalInt.empty(),null);}
    }
    record FamilyReceipt<T>(Reservation reservation,Completion completion,Optional<Work> exactWork,Work knownPrefix,
            Optional<Work> rejectedWorkClaim,OptionalInt rawReturned,Optional<T> conditionalResult) { }
    record Report<T>(Status status,String reason,List<FamilyReceipt<T>> families,long plannedPreparationPairs,
            Work knownPrefix,Optional<Work> exactWork,int rawRequested,OptionalInt rawReturned,boolean allRequiredComplete) {
        Report {families=List.copyOf(families);}
        boolean pooledPosteriorAvailable(){return false;}
        boolean globalExactK32(){return false;}
        int finalCandidateLimit(){return 8;}
    }
    @FunctionalInterface interface Executor<T> {Outcome<T> execute(ContinuityAttemptLedger<T>.Lease lease)throws Exception;}
    private final List<Reservation> reservations;
    private Status status=Status.READY;
    private String reason="planned";
    private final TraceBudgets caps;
    private final CancellationProbe cancellation,boundary;
    private final long deadline;
    private final LongSupplier clock;
    private final AttemptMemoryLedger.Owner owner;
    private long plannedPreparationPairs;
    private long actualPreparationPairs,nativePairs,scores;
    private int next,rawRequested;
    private boolean exactWorkAvailable=true,closed;
    private final List<FamilyReceipt<T>> receipts=new ArrayList<>();
    private Lease active;
    private final Thread thread=Thread.currentThread();
    private ContinuityAttemptLedger(List<Family> input,TraceBudgets caps,CancellationProbe cancellation,long deadline,
            LongSupplier clock,AttemptMemoryLedger.Owner owner) {
        this.caps=caps;this.cancellation=cancellation;this.deadline=deadline;this.clock=clock;this.owner=owner;
        boundary=()->owner.isClosed()||cancellation.cancelled()||clock.getAsLong()-deadline>=0;
        List<Reservation> sorted=new ArrayList<>();
        if(input.isEmpty()){status=Status.INCOMPLETE;reason="no-required-families";}
        else if(input.size()>8){status=Status.UNRESOLVED_CAPACITY;reason="ninth-required-family";}
        else {
            var ordered=new ArrayList<>(input);ordered.sort(Comparator.comparingInt(Family::physicalOrdinal));long units=0;
            for(int i=0;i<ordered.size();i++) {
                if(cancellation.cancelled()){status=Status.CANCELLED;reason="cancelled-planning";break;}
                if(clock.getAsLong()-deadline>=0){status=Status.DEADLINE;reason="outer-deadline";break;}
                var family=ordered.get(i);long slots=0;
                if(family.physicalOrdinal()!=i||family.diagnosticId()==null||family.diagnosticId().isBlank()||family.originalStateCounts().size()<2){status=Status.INVALID_ORDER;reason="invalid-predeclared-family";break;}
                int previous=0;
                for(int count:family.originalStateCounts()) {
                    if(cancellation.cancelled()){status=Status.CANCELLED;reason="cancelled-planning";break;}
                    if(clock.getAsLong()-deadline>=0){status=Status.DEADLINE;reason="outer-deadline";break;}
                    if(++units>8_000_000L){status=Status.RESOURCE_LIMIT;reason="planning-work";break;}
                    if(count<=0||count>caps.maximumStatesPerProfile()){status=Status.INCOMPLETE;reason="invalid-original-state-inventory";break;}
                    long pairs=(long)previous*count;
                    if(pairs>caps.maximumPairVisits()-plannedPreparationPairs-slots){status=Status.RESOURCE_LIMIT;reason="required-preparation-pair-inventory";break;}
                    slots+=pairs;previous=count;
                }
                if(status!=Status.READY)break;
                plannedPreparationPairs+=slots;int quota=32/ordered.size()+(i<32%ordered.size()?1:0);
                sorted.add(new Reservation(family,quota,Math.min(caps.maximumDistinctAlternatives(),quota),slots));
            }
        }
        reservations=status==Status.READY?List.copyOf(sorted):List.of();
        if(status!=Status.READY)plannedPreparationPairs=0;
    }
    static <T> ContinuityAttemptLedger<T> plan(List<Family> families,TraceBudgets caps,CancellationProbe cancellation,
            long deadlineNanos,LongSupplier clock,AttemptMemoryLedger.Owner owner) {
        Objects.requireNonNull(families);Objects.requireNonNull(caps);Objects.requireNonNull(cancellation);Objects.requireNonNull(clock);Objects.requireNonNull(owner);
        var defaults=TraceBudgets.defaults();
        if(caps.maximumRawAlternatives()!=32||caps.maximumDistinctAlternatives()>8||caps.maximumStatesPerProfile()>defaults.maximumStatesPerProfile()
            ||caps.maximumPairVisits()>defaults.maximumPairVisits()||caps.maximumTransitions()>defaults.maximumTransitions())throw new IllegalArgumentException("Unchanged or reduced caps; fixed raw32 and family8");
        try(var transaction=owner.child("continuity-family-plan");var charge=transaction.reserve(1024L+Math.min(9,families.size())*512L)) {
            var ledger=new ContinuityAttemptLedger<T>(families,caps,cancellation,deadlineNanos,clock,owner);charge.adopt(ledger);transaction.transferTo(owner);return ledger;
        }
    }
    private void accessible(){if(Thread.currentThread()!=thread)throw new IllegalStateException("One sequential attempt thread required");if(owner.isClosed())throw new IllegalStateException("Closed attempt owner");}
    private <V> V own(long bytes,Supplier<V> allocation){try(var charge=owner.reserve(bytes)){V result=allocation.get();charge.adopt(result);return result;}}
    List<Reservation> reservations(){accessible();return reservations;}
    Status status(){accessible();return status;}
    private Status boundaryStatus(){if(cancellation.cancelled())return Status.CANCELLED;if(clock.getAsLong()-deadline>=0)return Status.DEADLINE;return Status.READY;}
    /** At most one call capability exists, with fixed quota and shared outer controls. */
    final class Lease implements AutoCloseable {
        private final Reservation reservation;
        private final TraceBudgets limits;
        private final AttemptMemoryLedger.Owner scope,scratch,result;
        private Work prefix=new Work(0,0,0);
        private Optional<Work> rejected=Optional.empty();
        private boolean revoked;
        private Lease(Reservation reservation,TraceBudgets limits,AttemptMemoryLedger.Owner scope){this.reservation=reservation;this.limits=limits;this.scope=scope;scratch=scope.child("scratch");result=scope.child("conditional-result");}
        private void usable(){accessible();if(revoked||active!=this||status!=Status.READY)throw new IllegalStateException("Revoked family work lease");}
        TraceBudgets limits(){usable();return limits;}
        AttemptMemoryLedger.Owner scratchOwner(){usable();return scratch;}
        AttemptMemoryLedger.Owner resultOwner(){usable();return result;}
        CancellationProbe cancellation(){usable();return boundary;}
        Reservation reservation(){return reservation;}
        /** Cumulative actual work; it may still be reported during cancellation unwind. */
        void progress(Work cumulative){usable();if(!accept(this,cumulative)){settle(this,Completion.UNAVAILABLE,Optional.empty(),OptionalInt.empty(),null,Status.UNAVAILABLE,"invalid-work-receipt");throw new IllegalStateException("Invalid actual-work claim; attempt stopped");}}
        void finish(Outcome<T> outcome) {
            usable();Objects.requireNonNull(outcome);
            if(outcome.exactWork().isPresent())progress(outcome.exactWork().get());else exactWorkAvailable=false;
            OptionalInt raw=outcome.rawReturned(),distinct=outcome.distinctReturned();
            boolean counts=raw.isPresent()&&distinct.isPresent()&&raw.getAsInt()>=0&&raw.getAsInt()<=reservation.rawQuota()
                    &&distinct.getAsInt()>=0&&distinct.getAsInt()<=Math.min(raw.getAsInt(),reservation.distinctLimit());
            boolean complete=outcome.completion()==Completion.COMPLETE&&outcome.exactWork().isPresent()
                    &&prefix.preparationPairs()==reservation.preparationPairSlots()&&counts&&raw.getAsInt()>0&&distinct.getAsInt()>0&&outcome.conditionalResult()!=null;
            Status stop=boundaryStatus();String why=stop==Status.READY?"family-incomplete":"outer-"+stop.name().toLowerCase(Locale.ROOT);
            if(stop==Status.READY&&(!outcome.exactWork().isPresent()||outcome.completion()==Completion.UNAVAILABLE)){stop=Status.UNAVAILABLE;why="unavailable-final-work-or-outcome";}
            else if(stop==Status.READY&&!complete)stop=Status.INCOMPLETE;
            if(!counts){stop=Status.UNAVAILABLE;why="unavailable-or-invalid-raw-receipt";raw=OptionalInt.empty();}
            settle(this,complete?Completion.COMPLETE:outcome.completion(),outcome.exactWork(),raw,
                    stop==Status.READY&&complete?outcome.conditionalResult():null,stop,why);
        }
        /** Abandonment cannot recycle a quota or assume unreported failed work is zero. */
        @Override public void close(){if(!revoked){usable();abort(this,"abandoned-family-lease");}}
    }
    Optional<Lease> reserveNext() {
        accessible();if(active!=null)throw new IllegalStateException("A family lease is already active");
        if(closed||status!=Status.READY)return Optional.empty();
        var stop=boundaryStatus();if(stop!=Status.READY){status=stop;reason="outer-boundary-before-call";return Optional.empty();}
        if(next==reservations.size()){status=Status.COMPLETE;reason="all-required-families-complete";return Optional.empty();}
        long pairs=caps.maximumPairVisits()-plannedPreparationPairs-nativePairs,remainingScores=caps.maximumTransitions()-scores;
        if(pairs<=0||remainingScores<=0){status=Status.RESOURCE_LIMIT;reason="zero-remaining-work";return Optional.empty();}
        var reservation=reservations.get(next++);rawRequested+=reservation.rawQuota();
        var limits=new TraceBudgets(caps.maximumStatesPerProfile(),pairs,remainingScores,reservation.rawQuota(),reservation.distinctLimit());
        var scope=owner.child("continuity-family-"+reservation.family().physicalOrdinal());
        try(var charge=scope.reserve(512L)) {var lease=new Lease(reservation,limits,scope);charge.adopt(lease);active=lease;return Optional.of(lease);}
        catch(RuntimeException|Error failure){scope.close();status=Status.RESOURCE_LIMIT;reason="family-memory-refusal";throw failure;}
    }
    private boolean accept(Lease lease,Work value) {
        Work old=lease.prefix;
        if(value.preparationPairs()<old.preparationPairs()||value.nativePairs()<old.nativePairs()||value.scores()<old.scores()
            ||value.preparationPairs()>lease.reservation.preparationPairSlots()
            ||((value.nativePairs()>0||value.scores()>0)&&value.preparationPairs()!=lease.reservation.preparationPairSlots())
            ||value.nativePairs()-old.nativePairs()>caps.maximumPairVisits()-plannedPreparationPairs-nativePairs
            ||value.scores()-old.scores()>caps.maximumTransitions()-scores) {
            lease.rejected=Optional.of(value);exactWorkAvailable=false;return false;
        }
        actualPreparationPairs+=value.preparationPairs()-old.preparationPairs();nativePairs+=value.nativePairs()-old.nativePairs();scores+=value.scores()-old.scores();lease.prefix=value;return true;
    }
    private void settle(Lease lease,Completion completion,Optional<Work> exact,OptionalInt raw,T conditional,Status stop,String why) {
        Status terminal=stop;
        try {
            var receipt=own(256L,()->new FamilyReceipt<>(lease.reservation,completion,exact,lease.prefix,lease.rejected,raw,Optional.ofNullable(conditional)));
            if(conditional!=null)lease.result.transferTo(owner);receipts.add(receipt);
        }catch(RuntimeException|Error failure){terminal=Status.RESOURCE_LIMIT;why="receipt-memory-refusal";throw failure;}
        finally{lease.scope.close();lease.revoked=true;active=null;status=terminal;reason=why;}
        if(status==Status.READY&&next==reservations.size()){status=Status.COMPLETE;reason="all-required-families-complete";}
    }
    private void abort(Lease lease,String why){exactWorkAvailable=false;var stop=boundaryStatus();settle(lease,Completion.UNAVAILABLE,Optional.empty(),OptionalInt.empty(),null,stop==Status.READY?Status.UNAVAILABLE:stop,why);}
    Report<T> report() {
        accessible();var work=new Work(actualPreparationPairs,nativePairs,scores);int raw=0;boolean rawKnown=active==null;
        for(var receipt:receipts){if(receipt.rawReturned().isPresent())raw+=receipt.rawReturned().getAsInt();else rawKnown=false;}
        boolean complete=status==Status.COMPLETE&&receipts.size()==reservations.size();Status reported=status==Status.READY?Status.INCOMPLETE:status;
        Optional<Work> exact=exactWorkAvailable&&active==null?Optional.of(work):Optional.empty();OptionalInt returned=rawKnown?OptionalInt.of(raw):OptionalInt.empty();
        return own(256L+receipts.size()*16L,()->new Report<>(reported,reason,receipts,plannedPreparationPairs,work,exact,rawRequested,returned,complete));
    }
    Report<T> runAll(Executor<T> executor) {
        Objects.requireNonNull(executor);Optional<Lease> nextLease;
        while((nextLease=reserveNext()).isPresent()) {
            var lease=nextLease.get();
            try{lease.finish(executor.execute(lease));}
            catch(Throwable failure){if(!lease.revoked)abort(lease,"executor-failure-"+failure.getClass().getSimpleName());break;}
        }
        return report();
    }
    /** Revokes an outstanding call capability; the caller still owns attempt inputs/results. */
    @Override public void close(){accessible();if(active!=null)abort(active,"closed-with-active-family");else if(status==Status.READY){status=Status.INCOMPLETE;reason="required-families-not-called";}closed=true;}
}

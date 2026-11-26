package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** One operation's monotonic work/deadline/cancellation budget and existing attempt owner.
 * New optional copy/hash paths share this context; it never starts another ledger or resets work.
 */
public final class GeometryWorkContext {
    public enum Kind { COPY, MAP, HASH, BIND, RESERVATION }
    public static final class LimitReached extends IllegalStateException {
        public LimitReached(){super("geometry-work-limit");}
    }
    private final long maximumVisits,maximumBytes,deadlineNanos;
    private final CancellationProbe cancellation;
    private final AttemptMemoryLedger.Owner owner;
    private static final class CounterState {
        final long[] counters=new long[Kind.values().length];
        long visits;
    }
    private final CounterState state;
    public GeometryWorkContext(long maximumVisits,long maximumBytes,long deadlineNanos,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        if(maximumVisits<0||maximumBytes<0)throw new IllegalArgumentException("Negative geometry budget");
        this.maximumVisits=maximumVisits;this.maximumBytes=maximumBytes;this.deadlineNanos=deadlineNanos;
        this.cancellation=Objects.requireNonNull(cancellation);this.owner=Objects.requireNonNull(owner);
        state=new CounterState();
    }
    private GeometryWorkContext(GeometryWorkContext original,long maximumVisits) {
        this.maximumVisits=maximumVisits;maximumBytes=original.maximumBytes;deadlineNanos=original.deadlineNanos;
        cancellation=original.cancellation;owner=original.owner;state=original.state;
    }
    /** Selects one operation phase without renewing its counters, owner, memory, cancellation or absolute deadline. */
    public GeometryWorkContext phaseView(long maximumVisits) {
        if(maximumVisits<0)throw new IllegalArgumentException("Negative geometry phase budget");
        // Admission still belongs to the inherited view. A failed handoff cannot
        // spend beyond its ceiling or hide work before a new view actually exists.
        visit(Kind.BIND,1);
        try(var admitted=reserve("geometry-phase-view",128)) {
            if(state.visits>maximumVisits)throw new LimitReached();
            var view=new GeometryWorkContext(this,maximumVisits);admitted.adopt(view);return view;
        }
    }
    /** Debit before the corresponding primitive work. Callers checkpoint at most 256 primitives apart. */
    public void visit(Kind kind,long count) {
        debit(maximumVisits,kind,count);
    }
    private void debit(long ceiling,Kind kind,long count) {
        Objects.requireNonNull(kind);if(count<0||count>256)throw new IllegalArgumentException("Geometry work batches must be at most256");
        cancellation.checkpoint();
        if(System.nanoTime()>=deadlineNanos||count>ceiling-state.visits)throw new LimitReached();
        state.visits=Math.addExact(state.visits,count);state.counters[kind.ordinal()]=Math.addExact(state.counters[kind.ordinal()],count);
    }
    /** Account on the same attempt ledger before allocation, including already live history/source pins. */
    public AttemptMemoryLedger.Reservation reserve(String name,long bytes) {
        return reserveOn(owner,name,bytes);
    }
    public AttemptMemoryLedger.Reservation reserveOn(AttemptMemoryLedger.Owner attributionOwner,String name,long bytes) {
        if(name==null||name.isBlank()||bytes<0)throw new IllegalArgumentException("Named nonnegative reservation required");
        if(!owner.sharesLedgerWith(attributionOwner))throw new IllegalArgumentException("Geometry reservation owner belongs to a foreign ledger");
        visit(Kind.RESERVATION,1);
        return attributionOwner.reserveWithin(maximumBytes,bytes);
    }
    public long visits(){return state.visits;}
    public long maximumVisits(){return maximumVisits;}
    /** Only official views of this exact private operation state qualify; owner/value equality conveys no authority. */
    public boolean sharesAccountingWith(GeometryWorkContext actual){return actual!=null&&state==actual.state;}
    public Map<Kind,Long> counters(){var result=new EnumMap<Kind,Long>(Kind.class);for(var kind:Kind.values())result.put(kind,state.counters[kind.ordinal()]);return Map.copyOf(result);}
    public AttemptMemoryLedger.Owner owner(){return owner;}
}

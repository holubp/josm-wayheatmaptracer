package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Opt-in synchronous bridge for immutable value validation during postprocessing. */
public final class PostprocessingValidationWork {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private PostprocessingValidationWork() { }
    /** Explicit synchronous scope only; no context is propagated to another thread. */
    public static Scope open(GeometryWorkContext work) {
        java.util.Objects.requireNonNull(work);
        var previous=ACTIVE.get();
        if(previous!=null&&previous.work!=work)throw new IllegalArgumentException("Foreign postprocessing context");
        var scope=new Scope(work,previous);ACTIVE.set(scope);return scope;
    }
    /** Accounts for input-dependent rows that stay live through one guarded assessment. */
    public static Scope openGrowingScratch(GeometryWorkContext work) {
        var scope=open(work);scope.growingScratch=new ArrayList<>();return scope;
    }
    public static void growScratch(long bytes) {
        var scope=growingScope();
        if(scope==null)return;
        scope.growingBytes=Math.addExact(scope.growingBytes,bytes);
        if(scope.growingBytes>scope.growingCapacity) {
            long block=Math.max(65_536L,bytes);
            var admitted=scope.work.reserve("guarded-postprocessing-growing-rows",block);
            scope.growingScratch.add(admitted);
            scope.growingCapacity=Math.addExact(scope.growingCapacity,block);
        }
    }
    private static Scope growingScope() {
        var scope=ACTIVE.get();
        while(scope!=null&&scope.growingScratch==null)scope=scope.previous;
        return scope;
    }
    /** Borrows one exact immutable region index for the lifetime of a guarded trial. */
    public static MetricRegion.YMembershipIndex membershipIndex(MetricRegion region) {
        var scope=growingScope();
        if(scope==null||region.polygons().size()<4)return null;
        if(scope.memberships!=null) {
            var known=scope.memberships.get(region);
            if(known!=null)return known;
        }
        long bytes=Math.addExact(1024L,region.groupedYMembershipBytes());
        var admission=scope.work.reserve("guarded-region-membership-index",bytes);
        try {
            var index=region.prepareGroupedYMembership(CancellationProbe.NONE,PostprocessingValidationWork::charge);
            if(scope.memberships==null)scope.memberships=new IdentityHashMap<>();
            scope.memberships.put(region,index);
            scope.growingScratch.add(admission);
            return index;
        } catch(RuntimeException|Error failure) {
            admission.close();throw failure;
        }
    }
    public static GeometryWorkContext current() { var scope=ACTIVE.get();return scope==null?null:scope.work; }
    /** Debit immediately before one validation/copy primitive, only on the explicit path. */
    public static void visit() { var work=current();if(work!=null)work.visit(GeometryWorkContext.Kind.BIND,1); }
    /** Existing scalar/contact helpers report batches; preserve their units on this context. */
    public static void charge(long count) {
        if(count<0)throw new IllegalArgumentException("Negative postprocessing work");
        var work=current();
        if(work!=null)while(count>0){long batch=Math.min(256,count);work.visit(GeometryWorkContext.Kind.MAP,batch);count-=batch;}
    }
    /** Same stable ordering, charging actual object-sort comparisons on the scoped path. */
    public static <T> java.util.Comparator<T> comparator(java.util.Comparator<T> delegate) {
        return current()==null?delegate:(a,b)->{visit();return delegate.compare(a,b);};
    }
    public static <T extends Comparable<? super T>> java.util.Comparator<T> naturalOrder() {
        return comparator(java.util.Comparator.<T>naturalOrder());
    }
    public static final class Scope implements AutoCloseable {
        private final GeometryWorkContext work;
        private final Scope previous;
        private final Thread thread=Thread.currentThread();
        private boolean closed;
        private List<AttemptMemoryLedger.Reservation> growingScratch;
        private Map<MetricRegion,MetricRegion.YMembershipIndex> memberships;
        private long growingBytes, growingCapacity;
        private Scope(GeometryWorkContext work,Scope previous) {this.work=work;this.previous=previous;}
        @Override public void close() {
            if(closed)return;
            if(Thread.currentThread()!=thread||ACTIVE.get()!=this)throw new IllegalStateException("Postprocessing scope order/thread differs");
            if(growingScratch!=null)for(var reservation:growingScratch)reservation.close();
            if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);
            closed=true;
        }
    }
}

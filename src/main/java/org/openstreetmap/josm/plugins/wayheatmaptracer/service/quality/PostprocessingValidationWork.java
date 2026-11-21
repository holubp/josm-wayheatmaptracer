package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

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
        private Scope(GeometryWorkContext work,Scope previous) {this.work=work;this.previous=previous;}
        @Override public void close() {
            if(closed)return;
            if(Thread.currentThread()!=thread||ACTIVE.get()!=this)throw new IllegalStateException("Postprocessing scope order/thread differs");
            if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);
            closed=true;
        }
    }
}

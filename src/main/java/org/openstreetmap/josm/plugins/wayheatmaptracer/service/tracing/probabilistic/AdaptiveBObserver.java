package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Scoped coordinate-free private/test diagnostics; callbacks may stream indices but cannot alter views. */
@FunctionalInterface
public interface AdaptiveBObserver {
    AdaptiveBObserver NONE = (pass, row, compact, original) -> { };
    /** Called once for every retained cell, with pass zero and at most one refinement pass. */
    void retainedCell(int pass, int row, int compactIndex, int originalIndex);

    /** Explicit same-thread private/test observation; affects no policy or solver decisions. */
    static AutoCloseable observe(AdaptiveBObserver observer) {
        if (observer == null) throw new IllegalArgumentException("Observer is required");
        AdaptiveBObserver previous = State.CURRENT.get();
        Thread owner = Thread.currentThread(); State.CURRENT.set(observer);
        return new AutoCloseable() {
            private boolean closed;
            public void close() {
                if (owner != Thread.currentThread()) throw new IllegalStateException("Observer scope changed thread");
                if (!closed) {
                    closed = true;
                    if (previous == null) State.CURRENT.remove(); else State.CURRENT.set(previous);
                }
            }
        };
    }
    static AdaptiveBObserver current() {
        AdaptiveBObserver observer = State.CURRENT.get(); return observer == null ? NONE : observer;
    }
    final class State {
        private static final ThreadLocal<AdaptiveBObserver> CURRENT = new ThreadLocal<>();
        private State() { }
    }
}

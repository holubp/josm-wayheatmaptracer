package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation state shared by one consumer lifecycle. */
public final class CancellationToken {
    /** Removable cancellation callback registration. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        /** Removes a callback that is no longer needed. */
        @Override
        void close();
    }

    private static final Registration EMPTY_REGISTRATION = () -> { };
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Creates an active cancellation token. */
    public CancellationToken() {
        // Active by default.
    }

    /** Marks this token cancelled. This operation is idempotent. */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            listeners.forEach(Runnable::run);
            listeners.clear();
        }
    }

    /**
     * Registers a callback that runs once cancellation is observed.
     *
     * @param listener bounded non-blocking callback
     * @return removable registration
     */
    public Registration onCancellation(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        if (cancelled.get()) {
            listener.run();
            return EMPTY_REGISTRATION;
        }
        listeners.add(listener);
        if (cancelled.get() && listeners.remove(listener)) {
            listener.run();
            return EMPTY_REGISTRATION;
        }
        return () -> listeners.remove(listener);
    }

    /**
     * Returns whether the consumer no longer needs the request.
     *
     * @return true after cancellation
     */
    public boolean isCancelled() {
        return cancelled.get();
    }
}

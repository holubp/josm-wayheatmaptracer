package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation state shared by one consumer lifecycle. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** Creates an active cancellation token. */
    public CancellationToken() {
        // Active by default.
    }

    /** Marks this token cancelled. This operation is idempotent. */
    public void cancel() {
        cancelled.set(true);
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

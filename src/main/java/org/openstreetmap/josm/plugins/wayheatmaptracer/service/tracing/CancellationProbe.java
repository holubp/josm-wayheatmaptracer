package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.concurrent.CancellationException;

/** Cooperative, side-effect-free cancellation boundary for bounded numerical work. */
@FunctionalInterface
public interface CancellationProbe {
    /** Removable callback registration used by blocking external boundaries. */
    @FunctionalInterface
    interface Registration extends AutoCloseable {
        /** Removes a callback that is no longer needed. */
        @Override
        void close();
    }

    /** Shared probe for deterministic foreground tests and replay. */
    CancellationProbe NONE = () -> false;

    /** Returns whether the owning alignment attempt has been cancelled. */
    boolean cancelled();

    /**
     * Registers a bounded callback when this probe provides an event signal.
     *
     * @param callback bounded non-blocking callback
     * @return removable callback registration
     */
    default Registration onCancellation(Runnable callback) {
        if (cancelled()) {
            callback.run();
        }
        return () -> { };
    }

    /** Throws at a solver checkpoint after cancellation. */
    default void checkpoint() {
        if (cancelled()) {
            throw new CancellationException("Alignment computation was cancelled");
        }
    }
}

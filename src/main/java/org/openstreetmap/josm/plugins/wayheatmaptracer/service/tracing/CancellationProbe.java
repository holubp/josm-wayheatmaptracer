package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.concurrent.CancellationException;

/** Cooperative, side-effect-free cancellation boundary for bounded numerical work. */
@FunctionalInterface
public interface CancellationProbe {
    /** Shared probe for deterministic foreground tests and replay. */
    CancellationProbe NONE = () -> false;

    /** Returns whether the owning alignment attempt has been cancelled. */
    boolean cancelled();

    /** Throws at a solver checkpoint after cancellation. */
    default void checkpoint() {
        if (cancelled()) {
            throw new CancellationException("Alignment computation was cancelled");
        }
    }
}

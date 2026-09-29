package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.Objects;

/** Non-wire carrier for a value and the owner of its attempt-scoped retained-memory leases. */
public record Accounted<T>(T value, AttemptMemoryLedger.Owner owner) {
    public Accounted {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(owner, "owner");
    }
}

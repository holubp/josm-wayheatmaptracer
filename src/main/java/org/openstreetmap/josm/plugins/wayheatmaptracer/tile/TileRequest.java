package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable request contract for plugin-direct tile acquisition.
 *
 * @param address credential-free tile identity
 * @param generation active cache/settings generation
 * @param purpose scheduling and diagnostic purpose
 * @param cachePolicy positive-cache read/write policy
 * @param deadline latest transport eligibility time
 * @param cancellation consumer lifecycle token
 */
public record TileRequest(
    ManagedTileAddress address,
    ManagedTileGeneration generation,
    TilePurpose purpose,
    TileCachePolicy cachePolicy,
    Instant deadline,
    CancellationToken cancellation
) {
    /** Validates required request fields. */
    public TileRequest {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(cachePolicy, "cachePolicy");
        Objects.requireNonNull(deadline, "deadline");
        cancellation = cancellation == null ? new CancellationToken() : cancellation;
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.time.Duration;

/**
 * Central internal limits for plugin-direct managed tile acquisition.
 *
 * @param workerConcurrency fixed worker count
 * @param maximumQueueSize maximum admitted queued work and scheduled callbacks
 * @param connectTimeoutMillis transport connection timeout
 * @param readTimeoutMillis transport response timeout
 * @param maximumBodyBytes encoded response byte limit
 * @param memoryCacheBytes positive memory-cache byte limit
 * @param negativeCacheEntries negative-state entry limit
 * @param noTileTtl no-tile suppression duration
 * @param contentFailureTtl invalid-content suppression duration
 * @param transientFailureTtl transient failure suppression duration
 * @param rateLimitFallback fallback rate-limit pause
 * @param rateLimitMaximum maximum operational rate-limit pause
 */
public record TileReliabilityPolicy(
    int workerConcurrency,
    int maximumQueueSize,
    int connectTimeoutMillis,
    int readTimeoutMillis,
    int maximumBodyBytes,
    long memoryCacheBytes,
    int negativeCacheEntries,
    Duration noTileTtl,
    Duration contentFailureTtl,
    Duration transientFailureTtl,
    Duration rateLimitFallback,
    Duration rateLimitMaximum
) {
    /** Validates policy bounds. */
    public TileReliabilityPolicy {
        if (workerConcurrency < 1 || maximumQueueSize < workerConcurrency || connectTimeoutMillis < 1
            || readTimeoutMillis < 1 || maximumBodyBytes < 1024 || memoryCacheBytes < 1024
            || negativeCacheEntries < 1) {
            throw new IllegalArgumentException("Tile reliability policy limits must be positive and bounded");
        }
    }

    /**
     * Returns conservative defaults from the audited reliability plan.
     *
     * @return validated default policy
     */
    public static TileReliabilityPolicy defaults() {
        return new TileReliabilityPolicy(3, 512, 10_000, 20_000, 8 * 1024 * 1024,
            96L * 1024L * 1024L, 2048, Duration.ofMinutes(5), Duration.ofMinutes(5),
            Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofMinutes(15));
    }
}

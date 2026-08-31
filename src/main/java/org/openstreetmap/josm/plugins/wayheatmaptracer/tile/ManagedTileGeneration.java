package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

/**
 * Non-negative cache and credential generation used in safe tile identities.
 *
 * @param value numeric generation value
 */
public record ManagedTileGeneration(long value) {
    /** Rejects negative generations. */
    public ManagedTileGeneration {
        if (value < 0L) {
            throw new IllegalArgumentException("Managed tile generation must be non-negative");
        }
    }
}

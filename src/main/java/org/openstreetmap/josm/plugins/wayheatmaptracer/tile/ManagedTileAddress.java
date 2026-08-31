package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.util.Locale;
import java.util.Set;

/**
 * Credential-free identity of one managed Strava source tile.
 *
 * @param activity normalized activity path component
 * @param color normalized palette path component
 * @param zoom source tile zoom
 * @param x source tile x coordinate
 * @param y source tile y coordinate
 */
public record ManagedTileAddress(String activity, String color, int zoom, int x, int y) {
    private static final Set<String> ACTIVITIES = Set.of("all", "ride", "run", "water", "winter");
    private static final Set<String> COLORS = Set.of("hot", "blue", "bluered", "purple", "gray");

    /** Validates and normalizes safe path components and tile coordinates. */
    public ManagedTileAddress {
        activity = normalize(activity, "all", ACTIVITIES, "activity");
        color = normalize(color, "hot", COLORS, "color");
        if (zoom < 0 || zoom > 30) {
            throw new IllegalArgumentException("Tile zoom must be between 0 and 30");
        }
        long maximum = (1L << zoom) - 1L;
        if (x < 0 || y < 0 || x > maximum || y > maximum) {
            throw new IllegalArgumentException("Tile coordinates are outside the zoom grid");
        }
    }

    private static String normalize(String value, String fallback, Set<String> allowed, String field) {
        String normalized = value == null || value.isBlank()
            ? fallback : value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported managed tile " + field);
        }
        return normalized;
    }
}

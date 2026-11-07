package org.openstreetmap.josm.plugins.wayheatmaptracer;

import java.util.Map;

/** Finite source fixtures for the named ordinary-action experiment, never live action overrides. */
public enum BenchmarkSourceFixture {
    HOT("managed-hot-precise-7m01-z15-v1", "hot", 7.01, Map.of(15, 6, 14, 4)),
    BLUERED("managed-bluered-precise-25m-z15-v1", "bluered", 25.0, Map.of(15, 4, 14, 2));

    private final String identity;
    private final String palette;
    private final double halfWidthMeters;
    private final Map<Integer, Integer> expectedTileCounts;

    BenchmarkSourceFixture(String identity, String palette, double halfWidthMeters,
            Map<Integer, Integer> expectedTileCounts) {
        this.identity = identity;
        this.palette = palette;
        this.halfWidthMeters = halfWidthMeters;
        this.expectedTileCounts = Map.copyOf(expectedTileCounts);
    }

    public String identity() { return identity; }
    public String palette() { return palette; }
    public double halfWidthMeters() { return halfWidthMeters; }
    public Map<Integer, Integer> expectedTileCounts() { return expectedTileCounts; }

    public static BenchmarkSourceFixture parse(String identity) {
        for (var profile : values()) if (profile.identity.equals(identity)) return profile;
        throw new IllegalArgumentException("Unknown benchmark source profile");
    }
}

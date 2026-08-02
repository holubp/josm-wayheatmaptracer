package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;

/**
 * Profile-aligned robust longitudinal reference for one elementary corridor track.
 *
 * @param slices one immutable slice per sampled profile
 */
public record LongitudinalCorridorTube(List<CorridorTubeSlice> slices) {
    /** Makes tube slices immutable and profile aligned. */
    public LongitudinalCorridorTube {
        slices = List.copyOf(slices);
        for (int index = 0; index < slices.size(); index++) {
            if (slices.get(index).profileIndex() != index) {
                throw new IllegalArgumentException("Corridor tube slices must be profile aligned");
            }
        }
    }

    /**
     * Returns one profile-aligned tube slice.
     *
     * @param profileIndex sampled profile index
     * @return matching slice
     */
    public CorridorTubeSlice at(int profileIndex) {
        return slices.get(profileIndex);
    }
}

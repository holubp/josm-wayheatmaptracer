package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/**
 * One corridor observation assigned to a longitudinal track.
 *
 * @param profileIndex longitudinal profile index
 * @param band selected cross-sectional corridor band
 * @param bridged whether this observation is the higher-index boundary of an approved unsupported gap
 * @param support direct or bounded-interpolation provenance
 */
public record CorridorTrackPoint(
    int profileIndex,
    CorridorBand band,
    boolean bridged,
    CorridorPointSupport support
) {
    /**
     * Creates a directly observed elementary track point.
     *
     * @param profileIndex longitudinal profile index
     * @param band selected band
     * @param bridged approved-gap boundary marker
     */
    public CorridorTrackPoint(int profileIndex, CorridorBand band, boolean bridged) {
        this(profileIndex, band, bridged, CorridorPointSupport.DIRECT_UNION);
    }

    /** Validates point identity and provenance. */
    public CorridorTrackPoint {
        if (profileIndex < 0 || band == null || support == null) {
            throw new IllegalArgumentException("Corridor track point values must be non-null and non-negative");
        }
    }
}

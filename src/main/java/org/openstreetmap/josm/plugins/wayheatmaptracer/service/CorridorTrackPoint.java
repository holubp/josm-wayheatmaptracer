package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/**
 * One corridor observation assigned to a longitudinal track.
 *
 * @param profileIndex longitudinal profile index
 * @param band selected cross-sectional corridor band
 * @param bridged whether this observation follows an internal unsupported gap
 */
public record CorridorTrackPoint(int profileIndex, CorridorBand band, boolean bridged) {
}

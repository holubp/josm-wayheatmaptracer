package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;

/**
 * Extracted corridor evidence for one profile at one Gaussian scale.
 *
 * @param level Gaussian level index
 * @param reduction source-pixel pitch relative to L0
 * @param effectiveSigmaL0 effective Gaussian sigma in L0 source pixels
 * @param valid whether the anchor and its usable profile samples are valid
 * @param bands extracted bands expressed in common L0 sampled-raster coordinates
 */
public record ScaleCorridorObservation(
    int level,
    int reduction,
    double effectiveSigmaL0,
    boolean valid,
    List<CorridorBand> bands
) {
    /** Makes bands immutable. */
    public ScaleCorridorObservation {
        bands = List.copyOf(bands);
    }
}

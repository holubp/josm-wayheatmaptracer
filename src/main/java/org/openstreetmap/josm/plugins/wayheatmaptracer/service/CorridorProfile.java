package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;

/**
 * Corridor observations and robust profile statistics for one way cross-section.
 *
 * @param index longitudinal profile index
 * @param source original sampled profile
 * @param bands elementary and parent corridor hypotheses
 * @param maxIntensity maximum B5 intensity
 * @param noiseFloor robust local background estimate
 * @param prominence maximum intensity above the noise floor
 * @param supported whether usable in-raster scalar samples exist
 */
public record CorridorProfile(
    int index,
    RenderedHeatmapSampler.CrossSectionProfile source,
    List<CorridorBand> bands,
    double maxIntensity,
    double noiseFloor,
    double prominence,
    boolean supported
) {
    /**
     * Makes the band collection immutable.
     */
    public CorridorProfile {
        bands = List.copyOf(bands);
    }
}

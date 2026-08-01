package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;

/**
 * Gaussian scale-space observations aligned to one longitudinal profile anchor.
 *
 * @param profileIndex longitudinal L0 profile index
 * @param observations available level observations
 */
public record MultiScaleCorridorProfile(
    int profileIndex,
    List<ScaleCorridorObservation> observations
) {
    /** Makes observations immutable. */
    public MultiScaleCorridorProfile {
        observations = List.copyOf(observations);
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/**
 * One Gaussian scale-space level with a deterministic transform to L0 coordinates.
 *
 * @param level zero-based pyramid level
 * @param reduction level pixel pitch in L0 pixels
 * @param effectiveSigmaL0 effective Gaussian sigma measured in L0 pixels
 * @param field scalar field for the level
 */
public record IntensityScaleLevel(
    int level,
    int reduction,
    double effectiveSigmaL0,
    ScalarIntensityField field
) {
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;

/**
 * Cross-section profiles sampled from geographically aligned Gaussian intensity levels.
 *
 * @param levels profiles grouped by scale level, including L0
 * @param pyramidBuildNanos scalar mapping and pyramid construction time
 * @param estimatedBytes estimated scalar value and validity-mask storage
 */
public record MultiScaleProfileSet(
    List<ScaleProfileLevel> levels,
    long pyramidBuildNanos,
    long estimatedBytes
) {
    /** Makes level storage immutable. */
    public MultiScaleProfileSet {
        levels = List.copyOf(levels);
    }

    /**
     * Returns the fine L0 profiles used for final geometry.
     *
     * @return immutable fine-profile list
     */
    public List<RenderedHeatmapSampler.CrossSectionProfile> levelZeroProfiles() {
        return levels.isEmpty() ? List.of() : levels.get(0).profiles();
    }

    /**
     * One sampled Gaussian level.
     *
     * @param level zero-based level index
     * @param reduction level pixel pitch in source L0 pixels
     * @param effectiveSigmaL0 effective Gaussian sigma in source L0 pixels
     * @param profiles cross-sections with offsets expressed in common sampled-raster L0 coordinates
     */
    public record ScaleProfileLevel(
        int level,
        int reduction,
        double effectiveSigmaL0,
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles
    ) {
        /** Makes profiles immutable. */
        public ScaleProfileLevel {
            profiles = List.copyOf(profiles);
        }
    }
}

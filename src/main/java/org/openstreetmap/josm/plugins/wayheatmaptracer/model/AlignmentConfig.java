package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.OptionalDouble;

/**
 * Immutable settings captured together at the start of one slide attempt.
 *
 * @param heatmap heatmap source, detector, and alignment settings
 * @param cleanup optional geometry cleanup settings
 * @param searchHalfWidthMetersOverride optional one-shot retry width, never persisted
 */
public record AlignmentConfig(
    ManagedHeatmapConfig heatmap,
    GeometryCleanupConfig cleanup,
    OptionalDouble searchHalfWidthMetersOverride
) {
    /** Validates that both slide-time settings groups are present. */
    public AlignmentConfig {
        if (heatmap == null || cleanup == null) {
            throw new IllegalArgumentException("Slide-time heatmap and cleanup settings must not be null");
        }
        searchHalfWidthMetersOverride = searchHalfWidthMetersOverride == null
            ? OptionalDouble.empty() : searchHalfWidthMetersOverride;
        if (searchHalfWidthMetersOverride.isPresent()
            && (!Double.isFinite(searchHalfWidthMetersOverride.getAsDouble())
                || searchHalfWidthMetersOverride.getAsDouble() <= 0.0)) {
            throw new IllegalArgumentException("One-shot search half-width must be finite and positive");
        }
    }

    /**
     * Creates one ordinary slide attempt without a wider-search retry override.
     *
     * @param heatmap heatmap source, detector, and alignment settings
     * @param cleanup optional geometry cleanup settings
     */
    public AlignmentConfig(ManagedHeatmapConfig heatmap, GeometryCleanupConfig cleanup) {
        this(heatmap, cleanup, OptionalDouble.empty());
    }

    /**
     * Wraps an existing caller with cleanup disabled for behavioral compatibility.
     *
     * @param heatmap existing heatmap settings
     * @return complete slide-time settings
     */
    public static AlignmentConfig withoutCleanup(ManagedHeatmapConfig heatmap) {
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    /**
     * Returns a copy for one explicit wider-search retry without changing saved preferences.
     *
     * @param halfWidthMeters requested physical half-width after sampler-specific validation
     * @return immutable retry configuration
     */
    public AlignmentConfig withSearchHalfWidthMetersOverride(double halfWidthMeters) {
        return new AlignmentConfig(heatmap, cleanup, OptionalDouble.of(halfWidthMeters));
    }

    /**
     * Returns settings with the one-shot width applied for consumers that sample managed tiles.
     *
     * @return source settings for this attempt; the original persisted snapshot remains unchanged
     */
    public ManagedHeatmapConfig effectiveHeatmap() {
        return searchHalfWidthMetersOverride.isPresent()
            ? heatmap.withSearchHalfWidthMeters(searchHalfWidthMetersOverride.getAsDouble())
            : heatmap;
    }

    /** Resolves unsupported legacy B postprocessing for this attempt without changing preferences. */
    public AlignmentConfig forEffectiveModernAttempt() {
        if (heatmap.trackerMode() != TrackerMode.PROBABILISTIC
                || cleanup.isDisabled() && !heatmap.simplifyEnabled()) {
            return this;
        }
        ManagedHeatmapConfig effectiveHeatmap = heatmap;
        if (heatmap.simplifyEnabled()) {
            effectiveHeatmap = new ManagedHeatmapConfig(
                heatmap.keyPairId(), heatmap.policy(), heatmap.signature(), heatmap.sessionToken(),
                heatmap.activity(), heatmap.color(), heatmap.manualLayerName(), heatmap.layerRegex(),
                heatmap.alignmentMode(), heatmap.trackerMode(), heatmap.verbose(), heatmap.debug(),
                heatmap.multiColorDetection(), heatmap.aggregateAllColorSchemes(),
                heatmap.showAggregateIntensityLayer(), heatmap.candidateRatingEnabled(),
                heatmap.parallelWayAwareness(), heatmap.allowUndownloadedAlignment(),
                heatmap.adjustJunctionNodes(), false, heatmap.crossSectionHalfWidthPx(),
                heatmap.crossSectionStepPx(), heatmap.simplifyTolerancePx(), heatmap.inferenceMode(),
                heatmap.inferenceZoom(), heatmap.validationZoom(), heatmap.searchHalfWidthMeters(),
                heatmap.sampleStepMeters(), heatmap.intensitySamplingMode(), heatmap.cacheBuster());
        }
        return new AlignmentConfig(effectiveHeatmap, cleanup.withMode(GeometryCleanupMode.NONE),
                searchHalfWidthMetersOverride);
    }
}

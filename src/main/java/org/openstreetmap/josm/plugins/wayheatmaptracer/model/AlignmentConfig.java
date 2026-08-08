package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Immutable settings captured together at the start of one slide attempt.
 *
 * @param heatmap heatmap source, detector, and alignment settings
 * @param cleanup optional geometry cleanup settings
 */
public record AlignmentConfig(ManagedHeatmapConfig heatmap, GeometryCleanupConfig cleanup) {
    /** Validates that both slide-time settings groups are present. */
    public AlignmentConfig {
        if (heatmap == null || cleanup == null) {
            throw new IllegalArgumentException("Slide-time heatmap and cleanup settings must not be null");
        }
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
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Locale;

/** Provides complete, unit-explicit geometry cleanup parameter sets. */
public enum GeometryCleanupPreset {
    /** Uses a 6 metre unsupported-ripple scale. */
    CONSERVATIVE(6.0, 0.35, 0.15, 2, 1.0, 0.95),
    /** Uses a 10 metre unsupported-ripple scale. */
    BALANCED(10.0, 0.55, 0.25, 3, 2.0, 0.90),
    /** Uses a 20 metre unsupported-ripple scale. */
    STRONG(20.0, 0.75, 0.35, 4, 3.0, 0.85),
    /** Represents user-edited values; its defaults match Balanced until edited. */
    CUSTOM(10.0, 0.55, 0.25, 3, 2.0, 0.90);

    private final double rippleScaleMeters;
    private final double rippleStrength;
    private final double laplacianStrength;
    private final int laplacianPassCount;
    private final double simplificationDeviationMeters;
    private final double minimumFitRetention;

    GeometryCleanupPreset(
        double rippleScaleMeters,
        double rippleStrength,
        double laplacianStrength,
        int laplacianPassCount,
        double simplificationDeviationMeters,
        double minimumFitRetention
    ) {
        this.rippleScaleMeters = rippleScaleMeters;
        this.rippleStrength = rippleStrength;
        this.laplacianStrength = laplacianStrength;
        this.laplacianPassCount = laplacianPassCount;
        this.simplificationDeviationMeters = simplificationDeviationMeters;
        this.minimumFitRetention = minimumFitRetention;
    }

    /**
     * Returns a complete constrained-smoothing configuration using this preset.
     *
     * @return enabled preset configuration
     */
    public GeometryCleanupConfig apply() {
        return apply(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE);
    }

    /**
     * Returns a complete configuration using this preset and mode.
     *
     * @param mode cleanup mode to use
     * @return validated preset configuration
     */
    public GeometryCleanupConfig apply(GeometryCleanupMode mode) {
        GeometryCleanupMode effectiveMode = mode == null ? GeometryCleanupMode.NONE : mode;
        return new GeometryCleanupConfig(effectiveMode, this, rippleScaleMeters, rippleStrength,
            laplacianStrength, laplacianPassCount, simplificationDeviationMeters,
            minimumFitRetention, effectiveMode != GeometryCleanupMode.NONE);
    }

    /**
     * Parses a stored preset while falling back to Balanced.
     *
     * @param value stored enum name
     * @return parsed preset, or {@link #BALANCED} for null or unknown values
     */
    public static GeometryCleanupPreset fromPreference(String value) {
        if (value == null || value.isBlank()) {
            return BALANCED;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return BALANCED;
        }
    }

    /**
     * Returns the user-readable preset label used by settings controls.
     *
     * @return localized-neutral display text
     */
    @Override
    public String toString() {
        String lower = name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}

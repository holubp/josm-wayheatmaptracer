package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Locale;

/** Selects the optional geometry cleanup operation applied to a raw alignment candidate. */
public enum GeometryCleanupMode {
    /** Leaves the raw candidate unchanged. */
    NONE,
    /** Reduces points without applying smoothing. */
    REDUCE_POINTS_ONLY,
    /** Applies constrained smoothing followed by point reduction. */
    CONSTRAINED_SMOOTH_AND_REDUCE;

    /**
     * Parses a stored preference while failing safely to disabled cleanup.
     *
     * @param value stored enum name
     * @return parsed mode, or {@link #NONE} for null or unknown values
     */
    public static GeometryCleanupMode fromPreference(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return NONE;
        }
    }

    /**
     * Returns the user-readable mode label used by settings controls.
     *
     * @return localized-neutral display text
     */
    @Override
    public String toString() {
        return switch (this) {
            case NONE -> "None";
            case REDUCE_POINTS_ONLY -> "Reduce points only";
            case CONSTRAINED_SMOOTH_AND_REDUCE -> "Constrained smoothing + reduce points";
        };
    }
}

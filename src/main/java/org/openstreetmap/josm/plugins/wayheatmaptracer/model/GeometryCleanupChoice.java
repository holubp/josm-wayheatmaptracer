package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Locale;

/** Expresses one internally consistent user-facing geometry-cleanup operation. */
public enum GeometryCleanupChoice {
    /** Do not produce a cleaned sibling. */
    OFF,
    /** Retain coordinates and only remove safely redundant points. */
    REDUCE_POINTS_ONLY,
    /** Apply the conservative constrained cleanup preset. */
    CONSERVATIVE,
    /** Apply the balanced constrained cleanup preset. */
    BALANCED,
    /** Apply the strong constrained cleanup preset. */
    STRONG,
    /** Apply user-edited constrained cleanup values. */
    CUSTOM;

    /**
     * Derives the effective choice represented by a validated runtime configuration.
     *
     * @param config runtime cleanup configuration
     * @return consistent effective choice
     */
    public static GeometryCleanupChoice fromConfig(GeometryCleanupConfig config) {
        if (config == null || config.mode() == GeometryCleanupMode.NONE) {
            return OFF;
        }
        if (config.mode() == GeometryCleanupMode.REDUCE_POINTS_ONLY) {
            return REDUCE_POINTS_ONLY;
        }
        return switch (config.preset()) {
            case CONSERVATIVE -> config.preset().matches(config) ? CONSERVATIVE : CUSTOM;
            case BALANCED -> config.preset().matches(config) ? BALANCED : CUSTOM;
            case STRONG -> config.preset().matches(config) ? STRONG : CUSTOM;
            case CUSTOM -> CUSTOM;
        };
    }

    /**
     * Parses a stored choice, falling back to Off for missing or unknown values.
     *
     * @param value stored enum name
     * @return parsed choice or {@link #OFF}
     */
    public static GeometryCleanupChoice fromPreference(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return OFF;
        }
    }

    /** Returns a compact user-readable label. */
    @Override
    public String toString() {
        return switch (this) {
            case OFF -> "Off";
            case REDUCE_POINTS_ONLY -> "Reduce points only";
            case CONSERVATIVE -> "Conservative";
            case BALANCED -> "Balanced";
            case STRONG -> "Strong";
            case CUSTOM -> "Custom...";
        };
    }
}

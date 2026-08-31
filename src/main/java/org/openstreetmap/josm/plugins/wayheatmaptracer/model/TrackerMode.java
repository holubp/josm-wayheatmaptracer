package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Ridge-tracking implementation used to convert sampled heatmap profiles into candidate geometry.
 */
public enum TrackerMode {
    /** Proven v0.2-compatible ridge tracking and fallback behavior. */
    LEGACY_V02("Legacy v0.2-compatible"),
    /** Full-profile corridor extraction and longitudinal optimization. */
    CORRIDOR_AWARE("Corridor-aware (recommended)");

    private final String label;

    TrackerMode(String label) {
        this.label = label;
    }

    /**
     * Returns the tracker selected when no explicit preference exists.
     *
     * @return public default tracker
     */
    public static TrackerMode defaultMode() {
        return CORRIDOR_AWARE;
    }

    /**
     * Parses a persisted tracker mode.
     *
     * @param value enum name stored in preferences
     * @return parsed mode, or {@link #defaultMode()} for blank or unknown values
     */
    public static TrackerMode fromPreference(String value) {
        if (value != null) {
            for (TrackerMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
        }
        return defaultMode();
    }

    @Override
    public String toString() {
        return label;
    }
}

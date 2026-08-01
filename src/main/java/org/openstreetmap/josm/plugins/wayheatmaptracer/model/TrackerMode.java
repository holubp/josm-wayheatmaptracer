package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Ridge-tracking implementation used to convert sampled heatmap profiles into candidate geometry.
 */
public enum TrackerMode {
    /** Proven v0.2-compatible ridge tracking and fallback behavior. */
    LEGACY_V02("Legacy v0.2-compatible"),
    /** Experimental full-profile corridor extraction and longitudinal optimization. */
    CORRIDOR_AWARE("Experimental corridor-aware");

    private final String label;

    TrackerMode(String label) {
        this.label = label;
    }

    /**
     * Parses a persisted tracker mode.
     *
     * @param value enum name stored in preferences
     * @return parsed mode, or {@link #LEGACY_V02} for blank or unknown values
     */
    public static TrackerMode fromPreference(String value) {
        if (value != null) {
            for (TrackerMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
        }
        return LEGACY_V02;
    }

    @Override
    public String toString() {
        return label;
    }
}

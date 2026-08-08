package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Geometry application strategy used after a ridge candidate is selected.
 */
public enum AlignmentMode {
    /** Repositions the selected way's existing nodes without replacing its node sequence. */
    MOVE_EXISTING_NODES("Move Existing Nodes"),
    /** Rebuilds the selected segment from the traced centerline while preserving protected nodes. */
    PRECISE_SHAPE("Precise Shape");

    private final String displayName;

    AlignmentMode(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the label used in settings and preview UI.
     *
     * @return localized-independent display label
     */
    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /**
     * Parses a persisted alignment mode preference.
     *
     * @param value enum name stored in preferences
     * @return parsed mode, or {@link #MOVE_EXISTING_NODES} for blank or unknown values
     */
    public static AlignmentMode fromPreference(String value) {
        if (value == null || value.isBlank()) {
            return MOVE_EXISTING_NODES;
        }
        for (AlignmentMode mode : values()) {
            if (mode.name().equals(value)) {
                return mode;
            }
        }
        return MOVE_EXISTING_NODES;
    }
}

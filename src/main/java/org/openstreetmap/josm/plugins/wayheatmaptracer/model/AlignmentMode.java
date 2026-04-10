package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

public enum AlignmentMode {
    MOVE_EXISTING_NODES("Move Existing Nodes"),
    PRECISE_SHAPE("Precise Shape");

    private final String displayName;

    AlignmentMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

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

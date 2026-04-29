package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

public enum InferenceMode {
    STABLE_FIXED_SCALE("Stable fixed scale"),
    RAW_HIGH_RESOLUTION("Raw high-resolution");

    private final String label;

    InferenceMode(String label) {
        this.label = label;
    }

    public static InferenceMode fromPreference(String value) {
        if (value == null || value.isBlank()) {
            return STABLE_FIXED_SCALE;
        }
        for (InferenceMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return STABLE_FIXED_SCALE;
    }

    public boolean stableFixedScale() {
        return this == STABLE_FIXED_SCALE;
    }

    @Override
    public String toString() {
        return label;
    }
}

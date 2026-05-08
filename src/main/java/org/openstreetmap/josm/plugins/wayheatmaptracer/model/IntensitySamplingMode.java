package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

public enum IntensitySamplingMode {
    COLOR_MAPPING("Color mapping"),
    DIRECT_LUMINANCE("Direct luminance"),
    DIRECT_VALUE("Direct max channel"),
    DIRECT_ALPHA("Direct alpha");

    private final String label;

    IntensitySamplingMode(String label) {
        this.label = label;
    }

    public static IntensitySamplingMode fromPreference(String value) {
        if (value == null || value.isBlank()) {
            return COLOR_MAPPING;
        }
        for (IntensitySamplingMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return COLOR_MAPPING;
    }

    public boolean usesColorMapping() {
        return this == COLOR_MAPPING;
    }

    public String detectorName() {
        return switch (this) {
            case COLOR_MAPPING -> "";
            case DIRECT_LUMINANCE -> "direct-luminance";
            case DIRECT_VALUE -> "direct-value";
            case DIRECT_ALPHA -> "direct-alpha";
        };
    }

    @Override
    public String toString() {
        return label;
    }
}

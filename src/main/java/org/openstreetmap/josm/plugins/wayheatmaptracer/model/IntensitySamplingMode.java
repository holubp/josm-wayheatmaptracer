package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Converts rendered or source-tile pixels into scalar intensity before ridge extraction.
 */
public enum IntensitySamplingMode {
    COLOR_MAPPING("Color mapping"),
    DIRECT_LUMINANCE("Direct luminance"),
    DIRECT_VALUE("Direct max channel"),
    DIRECT_ALPHA("Direct alpha");

    private final String label;

    IntensitySamplingMode(String label) {
        this.label = label;
    }

    /**
     * Parses a persisted intensity sampling mode preference.
     *
     * @param value enum name stored in preferences
     * @return parsed mode, or {@link #COLOR_MAPPING} for blank or unknown values
     */
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

    /**
     * Checks whether semantic palette mapping is active.
     *
     * @return {@code true} when Strava color-to-intensity mapping is used
     */
    public boolean usesColorMapping() {
        return this == COLOR_MAPPING;
    }

    /**
     * Returns the detector suffix used for direct scalar sampling modes.
     *
     * @return empty string for color mapping, otherwise a stable detector id
     */
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

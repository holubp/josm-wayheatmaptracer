package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Ridge-tracking implementation used to convert sampled heatmap evidence into candidate geometry. */
public enum TrackerMode {
    LEGACY_V02("Legacy v0.2-compatible", EngineCapabilities.LEGACY),
    CORRIDOR_AWARE("Corridor-aware (A)", EngineCapabilities.CORRIDOR_AWARE),
    PROBABILISTIC("Probabilistic longitudinal (B, experimental)", EngineCapabilities.PROBABILISTIC),
    HYBRID("Historical A+B Hybrid (unsupported)", EngineCapabilities.HISTORICAL_HYBRID),
    LEGACY_THEN_B("Hybrid (Legacy then B)", EngineCapabilities.LEGACY_THEN_B),
    DIRECTIONAL_IMAGE("Direction-aware image search (experimental)", EngineCapabilities.DIRECTIONAL_IMAGE);

    private final String label;
    private final EngineCapabilities capabilities;

    TrackerMode(String label, EngineCapabilities capabilities) {
        this.label = label;
        this.capabilities = capabilities;
    }

    /** Returns every engine deliberately exposed by the versioned tracing settings UI. */
    public static TrackerMode[] selectableValues() {
        return java.util.Arrays.stream(values()).filter(mode -> mode != HYBRID).toArray(TrackerMode[]::new);
    }

    /** Returns the public default used when no explicit preference exists. */
    public static TrackerMode defaultMode() {
        return LEGACY_V02;
    }

    /** Parses a persisted enum name, falling back only for blank or unknown values. */
    public static TrackerMode fromPreference(String value) {
        if (value != null && "HYBRID".equalsIgnoreCase(value.trim())) {
            return LEGACY_V02;
        }
        if (value != null) {
            for (TrackerMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
        }
        return defaultMode();
    }

    /** Returns explicit behavior capabilities for this engine. */
    public EngineCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public String toString() {
        return label;
    }
}

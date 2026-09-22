package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Explicit source-acquisition policy for a modern alignment attempt. */
public enum AlignmentSourceMode {
    /** Prefer managed tiles only when this engine can use them and credentials are complete. */
    AUTOMATIC,
    /** Require managed tiles; absence of credentials or capability is a visible configuration failure. */
    MANAGED_TILES,
    /** Require the selected rendered imagery layer and never discard configured credentials. */
    VISIBLE_LAYER;

    /** Parses a persisted preference without granting managed acquisition to malformed values. */
    public static AlignmentSourceMode fromPreference(String value) {
        if (value != null) {
            for (AlignmentSourceMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
        }
        return AUTOMATIC;
    }

    /** Resolves the exact source used by one attempt. */
    public AlignmentSourceMode resolve(boolean managedCredentialsConfigured,
            boolean managedSourceSupported) {
        return switch (this) {
            case VISIBLE_LAYER -> VISIBLE_LAYER;
            case AUTOMATIC -> managedCredentialsConfigured && managedSourceSupported
                ? MANAGED_TILES : VISIBLE_LAYER;
            case MANAGED_TILES -> {
                if (!managedCredentialsConfigured) {
                    throw new IllegalStateException("Managed tiles require configured access values");
                }
                if (!managedSourceSupported) {
                    throw new IllegalStateException("The selected engine does not support managed tiles");
                }
                yield MANAGED_TILES;
            }
        };
    }
}

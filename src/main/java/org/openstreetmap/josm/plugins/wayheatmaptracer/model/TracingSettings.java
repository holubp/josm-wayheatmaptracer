package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Versioned modern tracing preferences that remain separate from legacy heatmap source settings.
 *
 * @param schemaVersion persisted schema revision
 * @param engine selected tracing engine
 * @param recovery explicit recovery and network-edit permissions
 * @param diagnosticComparisons whether additional experimental engines may run for preview comparison
 */
public record TracingSettings(
    int schemaVersion,
    TrackerMode engine,
    RecoverySettings recovery,
    boolean diagnosticComparisons
) {
    /** Current persisted tracing preference schema. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Validates a complete versioned tracing setting. */
    public TracingSettings {
        if (schemaVersion < 1 || engine == null || recovery == null) {
            throw new IllegalArgumentException("Tracing settings are incomplete");
        }
    }

    /** Returns conservative settings for a known normal decision radius. */
    public static TracingSettings defaults(double ordinaryRadiusMeters) {
        return new TracingSettings(CURRENT_SCHEMA_VERSION, TrackerMode.defaultMode(),
            RecoverySettings.defaults(ordinaryRadiusMeters), false);
    }
}

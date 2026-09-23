package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Versioned modern tracing preferences that remain separate from legacy heatmap source settings.
 *
 * @param schemaVersion persisted schema revision
 * @param engine selected tracing engine
 * @param recovery explicit recovery and network-edit permissions
 * @param diagnosticComparisons retained only for binary preference compatibility; production ignores it
 * @param sourceMode persisted source acquisition policy
 */
public record TracingSettings(
    int schemaVersion,
    TrackerMode engine,
    RecoverySettings recovery,
    boolean diagnosticComparisons,
    AlignmentSourceMode sourceMode
) {
    /** Current persisted tracing preference schema. */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    /** Validates a complete versioned tracing setting. */
    public TracingSettings {
        if (schemaVersion < 1 || engine == null || recovery == null || sourceMode == null) {
            throw new IllegalArgumentException("Tracing settings are incomplete");
        }
    }

    /** Compatibility constructor for schema-one callers; source selection remains conservative. */
    public TracingSettings(int schemaVersion, TrackerMode engine, RecoverySettings recovery,
            boolean diagnosticComparisons) {
        this(schemaVersion, engine, recovery, diagnosticComparisons, AlignmentSourceMode.AUTOMATIC);
    }

    /** Returns conservative settings for a known normal decision radius. */
    public static TracingSettings defaults(double ordinaryRadiusMeters) {
        return new TracingSettings(CURRENT_SCHEMA_VERSION, TrackerMode.defaultMode(),
            RecoverySettings.defaults(ordinaryRadiusMeters), false, AlignmentSourceMode.AUTOMATIC);
    }
}

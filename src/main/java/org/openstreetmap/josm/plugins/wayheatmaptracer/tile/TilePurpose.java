package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

/** Consumer purpose used only for scheduling and safe diagnostics. */
public enum TilePurpose {
    /** Explicit selected-source settings check. */
    ACCESS_PROBE(0),
    /** Required selected-source alignment work. */
    ALIGNMENT_REQUIRED(1),
    /** Additional palette work for an explicit aggregate detector. */
    ALIGNMENT_OPTIONAL_AGGREGATE(2),
    /** Foreground calibration export. */
    CALIBRATION_EXPORT(3),
    /** Low-priority live diagnostic aggregate work. */
    LIVE_AGGREGATE_VISUALIZATION(4);

    private final int priority;

    TilePurpose(int priority) {
        this.priority = priority;
    }

    /** Returns the lower-is-earlier scheduling priority. */
    int priority() {
        return priority;
    }
}

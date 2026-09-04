package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Terminal status of one requested detector mapping. */
public enum DetectorAttemptStatus {
    /** At least one candidate passed all apply gates. */
    APPLICABLE,
    /** Meaningful evidence exists, but explicit review is required before apply. */
    REVIEW_REQUIRED,
    /** Geometry evidence exists but violates a structural safety gate. */
    STRUCTURALLY_UNSAFE,
    /** Geometry evidence exists but supported signal is inadequate. */
    INSUFFICIENT_SIGNAL,
    /** No longitudinally persistent corridor was extracted. */
    NO_PERSISTENT_CORRIDOR,
    /** Required profiles fell outside the sampled raster. */
    OFF_RASTER,
    /** Required source imagery was unavailable or incomplete. */
    SOURCE_UNAVAILABLE,
    /** The mapping was requested but could not be started. */
    NOT_RUN
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Closed evidence-correlation classes used to prevent duplicated certainty. */
public enum EvidenceCorrelationGroup {
    /** Different Strava palette renderings of the same underlying activity data. */
    STRAVA_RENDERINGS,
    /** One direct scalar source that is not a palette duplicate. */
    DIRECT_SOURCE,
    /** Deterministic public synthetic truth used only by tests and benchmarks. */
    SYNTHETIC_TRUTH
}

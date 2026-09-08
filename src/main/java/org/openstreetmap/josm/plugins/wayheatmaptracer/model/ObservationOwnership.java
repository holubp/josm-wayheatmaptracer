package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Provenance and observability at one proposed route location. */
public enum ObservationOwnership {
    DIRECT_TWO_SIDED,
    DIRECT_AMBIGUOUS,
    SHOULDER_CENSORED,
    CORE_CENSORED,
    NO_SIGNAL_VALID_RASTER,
    NO_RASTER,
    INFERRED_GAP,
    FIXED_TOPOLOGY_ONLY
}

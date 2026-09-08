package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Mutually exclusive junction behavior recorded in a trace request. */
public enum JunctionPolicy {
    FIXED,
    LEGACY_BOUNDED_MOVE,
    REATTACH
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/** Describes whether a corridor point is observed directly or boundedly inferred. */
public enum CorridorPointSupport {
    /** At least one child track has a direct observation at this profile. */
    DIRECT_UNION,
    /** Compatible child evidence brackets this profile within the configured physical gap bound. */
    BOUNDED_INTERPOLATION
}

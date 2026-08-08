package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Describes how a selected corridor row is supported. */
public enum CleanupEvidenceProvenance {
    /** Direct selected-track or direct sparse child-union evidence. */
    DIRECT,
    /** Bounded interpolation bracketed by compatible direct evidence. */
    BOUNDED_INTERPOLATION,
    /** No selected-corridor evidence at this profile. */
    UNSUPPORTED
}

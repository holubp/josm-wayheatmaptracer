package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Immutable ordered relation member and role. */
public record DetachedRelationMember(PrimitiveKey memberKey, String role) {
    /** Normalizes the optional role. */
    public DetachedRelationMember {
        if (memberKey == null) {
            throw new IllegalArgumentException("Relation member identity is required");
        }
        role = role == null ? "" : role;
    }
}

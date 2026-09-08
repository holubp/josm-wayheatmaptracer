package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.Map;

/** Immutable detached relation state preserving member order and roles. */
public record DetachedRelation(PrimitiveKey key, List<DetachedRelationMember> members,
    Map<String, String> tags, boolean deleted, boolean modified) implements DetachedPrimitive {
    /** Copies members/tags and requires a relation identity. */
    public DetachedRelation {
        if (key == null || key.type() != PrimitiveKey.Type.RELATION || members == null || tags == null) {
            throw new IllegalArgumentException("Detached relation state is inconsistent");
        }
        members = List.copyOf(members);
        tags = Map.copyOf(tags);
    }
}

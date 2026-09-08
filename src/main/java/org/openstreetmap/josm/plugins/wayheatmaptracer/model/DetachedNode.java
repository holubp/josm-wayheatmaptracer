package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Map;

/** Immutable detached node state with authoritative geographic coordinates. */
public record DetachedNode(PrimitiveKey key, GeographicPoint coordinate, Map<String, String> tags,
    boolean deleted, boolean modified) implements DetachedPrimitive {
    /** Copies tags and requires a node identity. */
    public DetachedNode {
        if (key == null || key.type() != PrimitiveKey.Type.NODE || coordinate == null || tags == null) {
            throw new IllegalArgumentException("Detached node state is inconsistent");
        }
        tags = Map.copyOf(tags);
    }
}

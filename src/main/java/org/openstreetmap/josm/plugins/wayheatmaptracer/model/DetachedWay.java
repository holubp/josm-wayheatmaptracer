package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.Map;

/** Immutable detached way state preserving node occurrence order. */
public record DetachedWay(PrimitiveKey key, List<PrimitiveKey> nodeKeys, Map<String, String> tags,
    boolean deleted, boolean modified) implements DetachedPrimitive {
    /** Copies occurrences/tags and requires node member identities. */
    public DetachedWay {
        if (key == null || key.type() != PrimitiveKey.Type.WAY || nodeKeys == null || nodeKeys.size() < 2
            || nodeKeys.stream().anyMatch(node -> node == null || node.type() != PrimitiveKey.Type.NODE)
            || tags == null) {
            throw new IllegalArgumentException("Detached way state is inconsistent");
        }
        nodeKeys = List.copyOf(nodeKeys);
        tags = Map.copyOf(tags);
    }
}

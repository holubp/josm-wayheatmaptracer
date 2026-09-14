package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Explicit closed-world referrer watches for synthetic fixtures only. */
public final class V022SnapshotFixtures {
    private V022SnapshotFixtures() {
    }

    /**
     * Derives watches only for a fixture that deliberately declares no external referrers.
     * Production snapshot capture must enumerate live referrers explicitly.
     */
    public static Map<PrimitiveKey, Set<PrimitiveKey>> closedWorldReferrerWatches(
        Map<PrimitiveKey, DetachedPrimitive> primitives) {
        Map<PrimitiveKey, Set<PrimitiveKey>> mutable = new LinkedHashMap<>();
        primitives.keySet().forEach(key -> mutable.put(key, new LinkedHashSet<>()));
        primitives.values().forEach(primitive -> {
            if (primitive instanceof DetachedWay way) {
                way.nodeKeys().forEach(node -> mutable.get(node).add(way.key()));
            } else if (primitive instanceof DetachedRelation relation) {
                relation.members().forEach(member -> mutable.get(member.memberKey()).add(relation.key()));
            }
        });
        Map<PrimitiveKey, Set<PrimitiveKey>> result = new LinkedHashMap<>();
        mutable.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }
}

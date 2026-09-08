package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Repeatable query and explicit read/write authority contract used for stale-state validation. */
public record ClosureDescriptor(
    Scope scope,
    String queryVersion,
    Set<PrimitiveKey> primitiveKeys,
    Set<PrimitiveKey> editableExistingKeys,
    Set<PrimitiveKey> movableExistingNodeKeys,
    Set<PrimitiveKey> protectedExistingNodeKeys,
    Set<PrimitiveKey> removableExistingNodeKeys,
    Map<PrimitiveKey, List<OccurrenceRange>> editableWayOccurrences,
    List<ExternalPort> externalPorts,
    MetricRegion collisionEnvelope,
    MetricRegion editRegion,
    boolean mayCreateNodes,
    boolean wayReferrersComplete,
    boolean relationReferrersComplete,
    boolean nearbyGeometryComplete
) {
    /** Closure depth required by the authorized operation. */
    public enum Scope { SELECTION_SAFETY, EDIT_COMPONENT }

    /** Copies closure state and validates independent read, movement, protection, and removal authority. */
    public ClosureDescriptor {
        if (scope == null || queryVersion == null || queryVersion.isBlank() || primitiveKeys == null
            || editableExistingKeys == null || movableExistingNodeKeys == null
            || protectedExistingNodeKeys == null || removableExistingNodeKeys == null
            || editableWayOccurrences == null || externalPorts == null
            || collisionEnvelope == null || editRegion == null || !wayReferrersComplete
            || !relationReferrersComplete || scope == Scope.EDIT_COMPONENT && !nearbyGeometryComplete) {
            throw new IllegalArgumentException("Network closure is incomplete for its declared scope");
        }
        primitiveKeys = Set.copyOf(primitiveKeys);
        editableExistingKeys = Set.copyOf(editableExistingKeys);
        movableExistingNodeKeys = Set.copyOf(movableExistingNodeKeys);
        protectedExistingNodeKeys = Set.copyOf(protectedExistingNodeKeys);
        removableExistingNodeKeys = Set.copyOf(removableExistingNodeKeys);
        if (!primitiveKeys.containsAll(editableExistingKeys)
            || !primitiveKeys.containsAll(protectedExistingNodeKeys)
            || !editableExistingKeys.containsAll(movableExistingNodeKeys)
            || !editableExistingKeys.containsAll(removableExistingNodeKeys)
            || movableExistingNodeKeys.stream().anyMatch(key -> key.type() != PrimitiveKey.Type.NODE)
            || protectedExistingNodeKeys.stream().anyMatch(key -> key.type() != PrimitiveKey.Type.NODE)
            || removableExistingNodeKeys.stream().anyMatch(key -> key.type() != PrimitiveKey.Type.NODE)
            || !java.util.Collections.disjoint(movableExistingNodeKeys, protectedExistingNodeKeys)
            || !java.util.Collections.disjoint(removableExistingNodeKeys, protectedExistingNodeKeys)
            || !java.util.Collections.disjoint(movableExistingNodeKeys, removableExistingNodeKeys)) {
            throw new IllegalArgumentException("Node authority sets must be typed, explicit, and disjoint");
        }
        Set<PrimitiveKey> editableKeys = editableExistingKeys;
        Map<PrimitiveKey, List<OccurrenceRange>> occurrenceCopy = new LinkedHashMap<>();
        editableWayOccurrences.forEach((key, value) -> {
            if (key.type() != PrimitiveKey.Type.WAY || !editableKeys.contains(key)
                || value == null || value.isEmpty()) {
                throw new IllegalArgumentException("Editable occurrence authority requires an editable way");
            }
            List<OccurrenceRange> ranges = value.stream().sorted(java.util.Comparator
                .comparingInt(OccurrenceRange::firstIndex)).toList();
            for (int index = 1; index < ranges.size(); index++) {
                if (ranges.get(index - 1).lastIndex() >= ranges.get(index).firstIndex()) {
                    throw new IllegalArgumentException("Editable way occurrence ranges must be disjoint");
                }
            }
            occurrenceCopy.put(key, List.copyOf(ranges));
        });
        editableWayOccurrences = Map.copyOf(occurrenceCopy);
        externalPorts = List.copyOf(externalPorts);
        Set<PrimitiveKey> capturedKeys = primitiveKeys;
        if (externalPorts.stream().anyMatch(port -> !capturedKeys.contains(port.wayKey())
            || !capturedKeys.contains(port.boundaryNodeKey())
            || !capturedKeys.contains(port.outsideNeighborKey()))) {
            throw new IllegalArgumentException("External ports must cross the captured closure boundary");
        }
    }
}

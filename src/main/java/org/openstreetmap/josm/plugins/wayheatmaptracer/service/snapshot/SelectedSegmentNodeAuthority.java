package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;

/** Classifies selected node identities before a live modern snapshot is detached. */
public final class SelectedSegmentNodeAuthority {
    private SelectedSegmentNodeAuthority() {
    }

    /** The strongest reason why a selected identity is retained or allowed to move. */
    public enum NodeAuthorityReason {
        SEGMENT_BOUNDARY,
        EXPLICIT_FIXED,
        TAGGED,
        EXTERNAL_REFERRER,
        ORDINARY_INTERIOR,
        AUTHORIZED_JUNCTION_BOUNDARY,
        AUTHORIZED_RECEIVER_SHAPE
    }

    /** Immutable authority sets for one selected occurrence range. */
    public record Result(Set<PrimitiveKey> editableExistingKeys, Set<PrimitiveKey> movableNodeKeys,
            Set<PrimitiveKey> removableNodeKeys, Set<PrimitiveKey> protectedNodeKeys,
            Map<PrimitiveKey, NodeAuthorityReason> reasons) {
        public Result {
            if (editableExistingKeys == null || movableNodeKeys == null || removableNodeKeys == null
                    || protectedNodeKeys == null || reasons == null) {
                throw new IllegalArgumentException("Selected node authority is incomplete");
            }
            editableExistingKeys = Set.copyOf(editableExistingKeys);
            movableNodeKeys = Set.copyOf(movableNodeKeys);
            removableNodeKeys = Set.copyOf(removableNodeKeys);
            protectedNodeKeys = Set.copyOf(protectedNodeKeys);
            reasons = Map.copyOf(reasons);
            if (!nodeKeys(movableNodeKeys) || !nodeKeys(removableNodeKeys) || !nodeKeys(protectedNodeKeys)
                    || !java.util.Collections.disjoint(movableNodeKeys, removableNodeKeys)
                    || !java.util.Collections.disjoint(movableNodeKeys, protectedNodeKeys)
                    || !java.util.Collections.disjoint(removableNodeKeys, protectedNodeKeys)
                    || !editableExistingKeys.containsAll(movableNodeKeys)
                    || !editableExistingKeys.containsAll(removableNodeKeys)) {
                throw new IllegalArgumentException("Selected node authority sets are invalid");
            }
            Set<PrimitiveKey> authority = new LinkedHashSet<>();
            authority.addAll(movableNodeKeys);
            authority.addAll(removableNodeKeys);
            authority.addAll(protectedNodeKeys);
            if (!reasons.keySet().equals(authority) || reasons.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Every selected authority node requires one reason");
            }
        }

        private static boolean nodeKeys(Set<PrimitiveKey> keys) {
            return keys.stream().allMatch(key -> key != null && key.type() == PrimitiveKey.Type.NODE);
        }
    }

    /**
     * Classifies the selected range conservatively. Only ordinary untagged, unshared interior nodes move.
     */
    public static Result classify(SelectionContext selection) {
        if (selection == null || selection.way() == null || selection.segmentNodes() == null
                || selection.segmentNodes().size() < 2 || selection.fixedNodes() == null) {
            throw new IllegalArgumentException("Selected node authority requires a complete selection");
        }
        PrimitiveKey selectedWay = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                selection.way().getUniqueId());
        Set<PrimitiveKey> editable = new LinkedHashSet<>();
        Set<PrimitiveKey> movable = new LinkedHashSet<>();
        Set<PrimitiveKey> protectedNodes = new LinkedHashSet<>();
        Map<PrimitiveKey, NodeAuthorityReason> reasons = new LinkedHashMap<>();
        editable.add(selectedWay);
        int last = selection.segmentNodes().size() - 1;
        for (int index = 0; index <= last; index++) {
            Node node = selection.segmentNodes().get(index);
            if (node == null) {
                throw new IllegalArgumentException("Selected node authority cannot classify a null node");
            }
            PrimitiveKey key = PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId());
            NodeAuthorityReason reason;
            if (node.hasKeys()) {
                reason = NodeAuthorityReason.TAGGED;
            } else if (hasExternalReferrer(selection, node)) {
                reason = NodeAuthorityReason.EXTERNAL_REFERRER;
            } else if (index == 0 || index == last) {
                reason = NodeAuthorityReason.SEGMENT_BOUNDARY;
            } else if (selection.fixedNodes().contains(node)) {
                reason = NodeAuthorityReason.EXPLICIT_FIXED;
            } else {
                reason = NodeAuthorityReason.ORDINARY_INTERIOR;
            }
            reasons.put(key, reason);
            if (reason == NodeAuthorityReason.ORDINARY_INTERIOR) {
                editable.add(key);
                movable.add(key);
            } else {
                protectedNodes.add(key);
            }
        }
        return new Result(editable, movable, Set.of(), protectedNodes, reasons);
    }

    private static boolean hasExternalReferrer(SelectionContext selection, Node node) {
        if (node.getReferrers().stream().anyMatch(referrer -> referrer != selection.way())) {
            return true;
        }
        return selection.way().getDataSet() != null
                && selection.way().getDataSet().getRelations().stream()
                        .anyMatch(relation -> relation.getMembers().stream()
                                .anyMatch(member -> member.getMember() == node));
    }
}

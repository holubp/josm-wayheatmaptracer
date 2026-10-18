package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SharedNodeDisconnectionIntent;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;

/** Per-occurrence policy for explicit, source-bound shared-node disconnection. */
public final class SharedNodeDisconnectionEligibility {
    /** Outcome reason for an occurrence-specific disconnection request. */
    public enum Reason {
        ELIGIBLE,
        EMPTY_INTENT,
        INCOMPLETE_SOURCE,
        SOURCE_MISMATCH,
        SELECTED_WAY_MISMATCH,
        SELECTED_RANGE_MISMATCH,
        OUTSIDE_SELECTED_RANGE,
        OCCURRENCE_IDENTITY_MISMATCH,
        REPEATED_OCCURRENCE,
        NOT_SHARED,
        TAGGED_NODE,
        NODE_RELATION_MEMBERSHIP,
        PARTICIPATING_WAY_RELATION,
        AMBIGUOUS_SHARED_NODE
    }

    /** One immutable answer for exactly one requested selected-way occurrence. */
    public record Decision(int occurrenceIndex, PrimitiveKey originalNodeKey, Reason reason) {
        public Decision {
            if (occurrenceIndex < 0 || originalNodeKey == null || reason == null) {
                throw new IllegalArgumentException("Disconnection decision is incomplete");
            }
        }

        public boolean eligible() {
            return reason == Reason.ELIGIBLE;
        }

        /** Short user-readable explanation suitable for a preview choice list. */
        public String explanation() {
            return switch (reason) {
                case ELIGIBLE -> "This untagged shared node can be disconnected for the selected way.";
                case EMPTY_INTENT -> "No disconnection was requested.";
                case INCOMPLETE_SOURCE -> "Current node and referrer evidence is incomplete.";
                case SOURCE_MISMATCH -> "The requested disconnection belongs to an earlier source snapshot.";
                case SELECTED_WAY_MISMATCH -> "The request belongs to a different selected way.";
                case SELECTED_RANGE_MISMATCH -> "The request belongs to a different selected occurrence range.";
                case OUTSIDE_SELECTED_RANGE -> "This occurrence is outside the selected way range.";
                case OCCURRENCE_IDENTITY_MISMATCH -> "The selected occurrence no longer refers to the requested original node.";
                case REPEATED_OCCURRENCE -> "Repeated node occurrences require manual handling.";
                case NOT_SHARED -> "The selected node is not shared with another way.";
                case TAGGED_NODE -> "Tagged shared nodes require manual handling.";
                case NODE_RELATION_MEMBERSHIP -> "A node that belongs to a relation requires manual handling.";
                case PARTICIPATING_WAY_RELATION -> "A way that belongs to a relation requires manual handling.";
                case AMBIGUOUS_SHARED_NODE -> "A node shared by several ways requires manual handling.";
            };
        }
    }

    /** One decision for every requested occurrence, preserving request order. */
    public record Result(Map<Integer, Decision> decisions) {
        public Result {
            decisions = Collections.unmodifiableMap(new LinkedHashMap<>(decisions));
        }
    }

    private SharedNodeDisconnectionEligibility() { }

    /** Evaluates the immutable request only against its exact captured-before source snapshot. */
    public static Result evaluate(NetworkSnapshot snapshot, SharedNodeDisconnectionIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("Disconnection intent is required");
        }
        if (intent.isEmpty()) {
            return new Result(Map.of());
        }
        Map<Integer, Decision> decisions = new LinkedHashMap<>();
        String sourceIdentity = snapshot == null ? null : snapshot.canonicalHash();
        for (Map.Entry<Integer, PrimitiveKey> request : intent.originalNodeKeys().entrySet()) {
            int index = request.getKey();
            PrimitiveKey nodeKey = request.getValue();
            decisions.put(index, decide(snapshot, intent, index, nodeKey, sourceIdentity));
        }
        return new Result(decisions);
    }

    private static Decision decide(NetworkSnapshot snapshot, SharedNodeDisconnectionIntent intent,
            int index, PrimitiveKey requestedNode, String sourceIdentity) {
        if (snapshot == null || snapshot.role() != SnapshotRole.CAPTURED_BEFORE
                || !snapshot.closure().wayReferrersComplete()
                || !snapshot.closure().relationReferrersComplete()
                || !snapshot.primitives().containsKey(intent.selectedWayKey())) {
            return decision(index, requestedNode, Reason.INCOMPLETE_SOURCE);
        }
        if (!sourceIdentity.equals(intent.sourceNetworkIdentity())) {
            return decision(index, requestedNode, Reason.SOURCE_MISMATCH);
        }
        if (!(snapshot.primitives().get(intent.selectedWayKey()) instanceof DetachedWay selected)) {
            return decision(index, requestedNode, Reason.SELECTED_WAY_MISMATCH);
        }
        if (snapshot.closure().editableWayOccurrences().getOrDefault(intent.selectedWayKey(), java.util.List.of())
                .stream().noneMatch(intent.selectedRange()::equals)) {
            return decision(index, requestedNode, Reason.SELECTED_RANGE_MISMATCH);
        }
        if (index < intent.selectedRange().firstIndex() || index > intent.selectedRange().lastIndex()
                || index >= selected.nodeKeys().size()) {
            return decision(index, requestedNode, Reason.OUTSIDE_SELECTED_RANGE);
        }
        PrimitiveKey actualNode = selected.nodeKeys().get(index);
        if (!actualNode.equals(requestedNode)) {
            return decision(index, requestedNode, Reason.OCCURRENCE_IDENTITY_MISMATCH);
        }
        if (selected.nodeKeys().stream().filter(actualNode::equals).count() != 1) {
            return decision(index, requestedNode, Reason.REPEATED_OCCURRENCE);
        }
        Set<PrimitiveKey> incoming = snapshot.incomingReferrerWatches().get(actualNode);
        if (incoming == null || incoming.stream().anyMatch(key -> !snapshot.primitives().containsKey(key))) {
            return decision(index, requestedNode, Reason.INCOMPLETE_SOURCE);
        }
        DetachedPrimitive detachedNode = snapshot.primitives().get(actualNode);
        if (!(detachedNode instanceof DetachedNode node)) {
            return decision(index, requestedNode, Reason.INCOMPLETE_SOURCE);
        }
        Set<PrimitiveKey> ways = incoming.stream().filter(key -> key.type() == PrimitiveKey.Type.WAY)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (ways.size() < 2 || !ways.contains(intent.selectedWayKey())) {
            return decision(index, requestedNode, Reason.NOT_SHARED);
        }
        if (!node.tags().isEmpty()) {
            return decision(index, requestedNode, Reason.TAGGED_NODE);
        }
        if (incoming.stream().anyMatch(key -> key.type() == PrimitiveKey.Type.RELATION
                && snapshot.primitives().get(key) instanceof DetachedRelation)) {
            return decision(index, requestedNode, Reason.NODE_RELATION_MEMBERSHIP);
        }
        for (PrimitiveKey way : ways) {
            Set<PrimitiveKey> wayReferrers = snapshot.incomingReferrerWatches().get(way);
            if (wayReferrers == null || wayReferrers.stream()
                    .anyMatch(key -> !snapshot.primitives().containsKey(key))) {
                return decision(index, requestedNode, Reason.INCOMPLETE_SOURCE);
            }
            if (wayReferrers.stream().anyMatch(key -> key.type() == PrimitiveKey.Type.RELATION
                    && snapshot.primitives().get(key) instanceof DetachedRelation)) {
                return decision(index, requestedNode, Reason.PARTICIPATING_WAY_RELATION);
            }
        }
        return decision(index, requestedNode, Reason.ELIGIBLE);
    }

    private static Decision decision(int index, PrimitiveKey node, Reason reason) {
        return new Decision(index, node, reason);
    }
}

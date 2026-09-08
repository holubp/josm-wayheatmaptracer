package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable complete before/after network edit reviewed and applied as one transaction. */
public record AlignmentEditPlan(
    PrimitiveKey selectedWayKey,
    OccurrenceRange selectedRange,
    NetworkSnapshot before,
    NetworkSnapshot after,
    LocalMetricFrame metricFrame,
    RecoveryPermissions permissions,
    String settingsHash,
    String evidenceHash,
    String parameterHash,
    String routeIdentity,
    Map<PrimitiveKey, List<GeographicPoint>> finalPreviewWays,
    ValidationReport validation
) {
    /** Copies state and proves identity, authority, geometry, preview, and permission invariants. */
    public AlignmentEditPlan {
        if (selectedWayKey == null || selectedWayKey.type() != PrimitiveKey.Type.WAY || selectedRange == null
            || before == null || after == null || metricFrame == null || permissions == null || validation == null
            || finalPreviewWays == null || !before.datasetIdentity().equals(after.datasetIdentity())
            || before.sourceGeneration() != after.sourceGeneration()
            || !before.closure().equals(after.closure())
            || !before.primitives().containsKey(selectedWayKey) || !after.primitives().containsKey(selectedWayKey)) {
            throw new IllegalArgumentException("Edit plan snapshots, closure, or selected way are inconsistent");
        }
        settingsHash = requireText(settingsHash, "settingsHash");
        evidenceHash = requireText(evidenceHash, "evidenceHash");
        parameterHash = requireText(parameterHash, "parameterHash");
        routeIdentity = requireText(routeIdentity, "routeIdentity");
        Map<PrimitiveKey, List<GeographicPoint>> previewCopy = new LinkedHashMap<>();
        finalPreviewWays.forEach((key, points) -> previewCopy.put(key, List.copyOf(points)));
        finalPreviewWays = Map.copyOf(previewCopy);
        DetachedWay selected = requireWay(before, selectedWayKey);
        if (selectedRange.lastIndex() >= selected.nodeKeys().size()) {
            throw new IllegalArgumentException("Selected occurrence range exceeds source way");
        }
        validateIdentityNamespaces(before, after);
        Set<PrimitiveKey> writeSet = writeSet(before, after);
        if (writeSet.isEmpty()) {
            throw new IllegalArgumentException("An edit plan must contain a material change");
        }
        validateCreatedAndRemoved(before, after, writeSet);
        validateNodeMovementAuthority(before, after, selectedWayKey, selectedRange, permissions);
        Set<PrimitiveKey> changedExisting = writeSet.stream()
            .filter(before.primitives()::containsKey).collect(Collectors.toUnmodifiableSet());
        if (!before.closure().editableExistingKeys().containsAll(changedExisting)) {
            throw new IllegalArgumentException("Structural diff exceeds explicit primitive edit authority");
        }
        validateSemanticChanges(before, after, writeSet, permissions, selectedWayKey);
        validateOccurrenceAuthority(before, after, writeSet);
        validateMetricAuthority(before, after, writeSet, metricFrame);
        Set<PrimitiveKey> affectedWays = affectedWayKeys(before, after, writeSet);
        if (!finalPreviewWays.keySet().equals(affectedWays)) {
            throw new IllegalArgumentException("Final preview must contain exactly every affected way geometry");
        }
        validatePreviewMatchesAfter(after, finalPreviewWays);
    }

    /** Returns all changed, created, and removed typed primitive identities. */
    public Set<PrimitiveKey> writePrimitiveKeys() {
        return writeSet(before, after);
    }

    /** Returns every way whose visible geometry changes, including shared-node movement. */
    public Set<PrimitiveKey> affectedWayKeys() {
        return affectedWayKeys(before, after, writePrimitiveKeys());
    }

    /** Returns proposed plan-local nodes absent from the before snapshot. */
    public Map<PrimitiveKey, DetachedPrimitive> createdPrimitives() {
        return difference(after.primitives(), before.primitives().keySet());
    }

    /** Returns explicitly authorized existing shape nodes absent from the after snapshot. */
    public Map<PrimitiveKey, DetachedPrimitive> removedPrimitives() {
        return difference(before.primitives(), after.primitives().keySet());
    }

    /** Returns the schema-versioned identity that review confirmation binds to. */
    public String canonicalHash() {
        CanonicalEncoder encoder = new CanonicalEncoder().field("alignment-edit-plan-v3");
        NetworkSnapshot.encodeKey(encoder, selectedWayKey);
        encoder.field(selectedRange.firstIndex()).field(selectedRange.lastIndex())
            .field(before.canonicalHash()).field(after.canonicalHash())
            .field(metricFrame.projectionId())
            .field(Double.toHexString(metricFrame.origin().latitudeDegrees()))
            .field(Double.toHexString(metricFrame.origin().longitudeDegrees()))
            .field(metricFrame.distortionCertificate().method())
            .field(Double.toHexString(metricFrame.distortionCertificate().maximumRelativeDistanceError()))
            .field(Double.toHexString(metricFrame.distortionCertificate().southWest().latitudeDegrees()))
            .field(Double.toHexString(metricFrame.distortionCertificate().southWest().longitudeDegrees()))
            .field(Double.toHexString(metricFrame.distortionCertificate().northEast().latitudeDegrees()))
            .field(Double.toHexString(metricFrame.distortionCertificate().northEast().longitudeDegrees()))
            .field(settingsHash).field(evidenceHash).field(parameterHash).field(routeIdentity)
            .field(permissions.toString()).field(validation.disposition().name());
        validation.findingCodes().forEach(code -> encoder.field("finding").field(code));
        finalPreviewWays.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            NetworkSnapshot.encodeKey(encoder.field("preview-way"), entry.getKey());
            entry.getValue().forEach(point -> encoder.field(Double.toHexString(point.latitudeDegrees()))
                .field(Double.toHexString(point.longitudeDegrees())));
        });
        return encoder.sha256();
    }

    private static void validateIdentityNamespaces(NetworkSnapshot before, NetworkSnapshot after) {
        if (!before.primitives().keySet().equals(before.closure().primitiveKeys())
            || before.primitives().keySet().stream()
                .anyMatch(key -> key.identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE)) {
            throw new IllegalArgumentException("Before snapshot must materialize the complete existing closure");
        }
        Set<PrimitiveKey> added = new LinkedHashSet<>(after.primitives().keySet());
        added.removeAll(before.primitives().keySet());
        if (added.stream().anyMatch(key -> key.identityKind() != PrimitiveKey.IdentityKind.PLAN_LOCAL
            || key.type() != PrimitiveKey.Type.NODE)) {
            throw new IllegalArgumentException("Only plan-local nodes may be created by v0.22 alignment");
        }
    }

    private static void validateCreatedAndRemoved(NetworkSnapshot before, NetworkSnapshot after,
        Set<PrimitiveKey> writeSet) {
        Set<PrimitiveKey> added = new LinkedHashSet<>(after.primitives().keySet());
        added.removeAll(before.primitives().keySet());
        if (!added.isEmpty() && !before.closure().mayCreateNodes()) {
            throw new IllegalArgumentException("Closure does not authorize new nodes");
        }
        for (PrimitiveKey key : added) {
            DetachedNode node = (DetachedNode) after.primitives().get(key);
            if (!node.tags().isEmpty()) {
                throw new IllegalArgumentException("Plan-local alignment shape nodes must be untagged");
            }
        }
        Set<PrimitiveKey> removed = new LinkedHashSet<>(before.primitives().keySet());
        removed.removeAll(after.primitives().keySet());
        if (!before.closure().removableExistingNodeKeys().containsAll(removed)) {
            throw new IllegalArgumentException("Removed primitives exceed the explicit safe-removal set");
        }
        for (PrimitiveKey key : removed) {
            DetachedPrimitive primitive = before.primitives().get(key);
            if (!(primitive instanceof DetachedNode node) || !node.tags().isEmpty()
                || after.incomingReferrers().containsKey(key)) {
                throw new IllegalArgumentException("Only authorized untagged unreferenced nodes may be removed");
            }
            Set<PrimitiveKey> oldReferrers = before.incomingReferrers().getOrDefault(key, Set.of());
            if (!writeSet.containsAll(oldReferrers)) {
                throw new IllegalArgumentException("Every old referrer of a removed node must be rewritten");
            }
        }
    }

    private static void validateNodeMovementAuthority(NetworkSnapshot before, NetworkSnapshot after,
        PrimitiveKey selectedWayKey, OccurrenceRange selectedRange, RecoveryPermissions permissions) {
        Set<PrimitiveKey> movable = before.closure().movableExistingNodeKeys();
        Set<PrimitiveKey> protectedNodes = before.closure().protectedExistingNodeKeys();
        for (Map.Entry<PrimitiveKey, DetachedPrimitive> entry : before.primitives().entrySet()) {
            if (!(entry.getValue() instanceof DetachedNode oldNode)
                || !(after.primitives().get(entry.getKey()) instanceof DetachedNode newNode)
                || oldNode.coordinate().equals(newNode.coordinate())) {
                continue;
            }
            PrimitiveKey nodeKey = entry.getKey();
            if (!movable.contains(nodeKey) || protectedNodes.contains(nodeKey)) {
                throw new IllegalArgumentException("Moved existing node lacks explicit movement authority");
            }
            for (DetachedPrimitive primitive : before.primitives().values()) {
                if (!(primitive instanceof DetachedWay way)) {
                    continue;
                }
                for (int occurrence = 0; occurrence < way.nodeKeys().size(); occurrence++) {
                    if (!way.nodeKeys().get(occurrence).equals(nodeKey)) {
                        continue;
                    }
                    boolean occurrenceEditable = occurrenceAuthorized(before.closure(), way.key(), occurrence);
                    boolean selectedBoundary = way.key().equals(selectedWayKey)
                        && (occurrence == selectedRange.firstIndex() || occurrence == selectedRange.lastIndex());
                    if (!occurrenceEditable
                        || selectedBoundary && permissions.junctionPolicy() == JunctionPolicy.FIXED) {
                        throw new IllegalArgumentException("Moved node occurrence is fixed by selection authority");
                    }
                    if (!way.key().equals(selectedWayKey) && !permissions.reconstructIncidentWays()
                        && !nodeSharedWithSelected(before, nodeKey, selectedWayKey)) {
                        throw new IllegalArgumentException(
                            "Incident shape-node movement requires reconstruction permission");
                    }
                }
            }
        }
    }

    private static boolean occurrenceAuthorized(ClosureDescriptor closure, PrimitiveKey wayKey, int occurrence) {
        List<OccurrenceRange> ranges = closure.editableWayOccurrences().get(wayKey);
        return ranges != null && ranges.stream().anyMatch(range -> occurrence >= range.firstIndex()
            && occurrence <= range.lastIndex());
    }

    private static boolean nodeSharedWithSelected(NetworkSnapshot snapshot, PrimitiveKey nodeKey,
        PrimitiveKey selectedWayKey) {
        Set<PrimitiveKey> referrers = snapshot.incomingReferrers().getOrDefault(nodeKey, Set.of());
        return referrers.contains(selectedWayKey) && referrers.stream()
            .anyMatch(key -> key.type() == PrimitiveKey.Type.WAY && !key.equals(selectedWayKey));
    }

    private static void validateSemanticChanges(NetworkSnapshot before, NetworkSnapshot after,
        Set<PrimitiveKey> writes, RecoveryPermissions permissions, PrimitiveKey selectedWayKey) {
        for (PrimitiveKey key : writes) {
            DetachedPrimitive oldValue = before.primitives().get(key);
            DetachedPrimitive newValue = after.primitives().get(key);
            if (oldValue == null || newValue == null) {
                continue;
            }
            if (!oldValue.tags().equals(newValue.tags())) {
                throw new IllegalArgumentException("Alignment edit plans cannot retag existing primitives");
            }
            if (oldValue instanceof DetachedRelation oldRelation
                && newValue instanceof DetachedRelation newRelation) {
                if (permissions.junctionPolicy() != JunctionPolicy.REATTACH
                    || !relationSemanticsPreserved(before, after, oldRelation, newRelation)) {
                    throw new IllegalArgumentException("Relation member edits exceed reattachment semantics");
                }
            }
            if (oldValue instanceof DetachedWay && !key.equals(selectedWayKey)
                && permissions.junctionPolicy() != JunctionPolicy.REATTACH) {
                throw new IllegalArgumentException("Incident-way edits require explicit junction reattachment");
            }
        }
    }

    private static boolean relationSemanticsPreserved(NetworkSnapshot beforeSnapshot,
        NetworkSnapshot afterSnapshot, DetachedRelation before, DetachedRelation after) {
        if (before.members().equals(after.members())) {
            return true;
        }
        if (!"restriction".equals(before.tags().get("type"))
            || before.members().size() != after.members().size()) {
            return false;
        }
        int differences = 0;
        PrimitiveKey oldVia = null;
        PrimitiveKey newVia = null;
        for (int index = 0; index < before.members().size(); index++) {
            DetachedRelationMember left = before.members().get(index);
            DetachedRelationMember right = after.members().get(index);
            if (left.equals(right)) {
                continue;
            }
            differences++;
            if (!"via".equals(left.role()) || !left.role().equals(right.role())
                || left.memberKey().type() != PrimitiveKey.Type.NODE
                || right.memberKey().type() != PrimitiveKey.Type.NODE) {
                return false;
            }
            oldVia = left.memberKey();
            newVia = right.memberKey();
        }
        if (differences != 1 || oldVia == null || newVia == null) {
            return false;
        }
        PrimitiveKey from = uniqueWayMember(before.members(), "from");
        PrimitiveKey to = uniqueWayMember(before.members(), "to");
        return from != null && to != null
            && beforeSnapshot.closure().removableExistingNodeKeys().contains(oldVia)
            && !afterSnapshot.primitives().containsKey(oldVia)
            && !beforeSnapshot.primitives().containsKey(newVia)
            && newVia.identityKind() == PrimitiveKey.IdentityKind.PLAN_LOCAL
            && wayContains(beforeSnapshot, from, oldVia) && wayContains(beforeSnapshot, to, oldVia)
            && wayContains(afterSnapshot, from, newVia) && wayContains(afterSnapshot, to, newVia);
    }

    private static PrimitiveKey uniqueWayMember(List<DetachedRelationMember> members, String role) {
        PrimitiveKey result = null;
        for (DetachedRelationMember member : members) {
            if (!role.equals(member.role())) {
                continue;
            }
            if (result != null || member.memberKey().type() != PrimitiveKey.Type.WAY) {
                return null;
            }
            result = member.memberKey();
        }
        return result;
    }

    private static boolean wayContains(NetworkSnapshot snapshot, PrimitiveKey wayKey, PrimitiveKey nodeKey) {
        DetachedPrimitive primitive = snapshot.primitives().get(wayKey);
        return primitive instanceof DetachedWay way && way.nodeKeys().contains(nodeKey);
    }

    private static void validateOccurrenceAuthority(NetworkSnapshot before, NetworkSnapshot after,
        Set<PrimitiveKey> writes) {
        for (PrimitiveKey key : writes) {
            DetachedPrimitive oldValue = before.primitives().get(key);
            DetachedPrimitive newValue = after.primitives().get(key);
            if (oldValue instanceof DetachedWay oldWay && newValue instanceof DetachedWay newWay
                && !oldWay.nodeKeys().equals(newWay.nodeKeys())) {
                List<OccurrenceRange> ranges = before.closure().editableWayOccurrences().get(key);
                if (ranges == null || !sequenceChangesAuthorized(oldWay.nodeKeys(), newWay.nodeKeys(), ranges,
                    before.closure().protectedExistingNodeKeys())) {
                    throw new IllegalArgumentException("Way node-list change exceeds editable occurrence ranges");
                }
            }
        }
    }

    private static boolean sequenceChangesAuthorized(List<PrimitiveKey> before, List<PrimitiveKey> after,
        List<OccurrenceRange> ranges, Set<PrimitiveKey> protectedNodes) {
        if (new HashSet<>(before).size() != before.size() || new HashSet<>(after).size() != after.size()) {
            return false;
        }
        boolean[] editable = new boolean[before.size()];
        for (OccurrenceRange range : ranges) {
            if (range.lastIndex() >= before.size()) {
                return false;
            }
            for (int index = range.firstIndex(); index <= range.lastIndex(); index++) {
                editable[index] = !protectedNodes.contains(before.get(index));
            }
        }
        List<Integer> fixedBeforeIndexes = new ArrayList<>();
        for (int index = 0; index < before.size(); index++) {
            if (!editable[index]) {
                fixedBeforeIndexes.add(index);
            }
        }
        int previousBefore = -1;
        int previousAfter = -1;
        for (int beforeIndex : fixedBeforeIndexes) {
            int afterIndex = after.indexOf(before.get(beforeIndex));
            if (afterIndex <= previousAfter || !authorizedGap(editable, previousBefore + 1, beforeIndex - 1,
                after.subList(previousAfter + 1, afterIndex), before.subList(previousBefore + 1, beforeIndex))) {
                return false;
            }
            previousBefore = beforeIndex;
            previousAfter = afterIndex;
        }
        return authorizedGap(editable, previousBefore + 1, before.size() - 1,
            after.subList(previousAfter + 1, after.size()), before.subList(previousBefore + 1, before.size()));
    }

    private static boolean authorizedGap(boolean[] editable, int from, int to,
        List<PrimitiveKey> afterGap, List<PrimitiveKey> beforeGap) {
        boolean canChange = false;
        for (int index = from; index <= to; index++) {
            canChange |= editable[index];
        }
        return canChange || afterGap.equals(beforeGap);
    }

    private static void validateMetricAuthority(NetworkSnapshot before, NetworkSnapshot after,
        Set<PrimitiveKey> writes, LocalMetricFrame frame) {
        MetricRegion editRegion = before.closure().editRegion();
        for (PrimitiveKey key : writes) {
            DetachedPrimitive oldValue = before.primitives().get(key);
            DetachedPrimitive newValue = after.primitives().get(key);
            if (oldValue instanceof DetachedNode oldNode && newValue instanceof DetachedNode newNode
                && !oldNode.coordinate().equals(newNode.coordinate())) {
                MetricPoint oldPoint = frame.toMetric(oldNode.coordinate());
                MetricPoint newPoint = frame.toMetric(newNode.coordinate());
                if (!editRegion.containsSegment(oldPoint, newPoint)) {
                    throw new IllegalArgumentException("Moved node leaves the authorized edit region");
                }
            } else if (oldValue == null && newValue instanceof DetachedNode newNode) {
                if (!editRegion.contains(frame.toMetric(newNode.coordinate()))) {
                    throw new IllegalArgumentException("Created node lies outside the authorized edit region");
                }
            } else if (oldValue instanceof DetachedNode oldNode && newValue == null
                && !editRegion.contains(frame.toMetric(oldNode.coordinate()))) {
                throw new IllegalArgumentException("Removed node lies outside the authorized edit region");
            }
        }
        for (PrimitiveKey key : affectedWayKeys(before, after, writes)) {
            if (!(after.primitives().get(key) instanceof DetachedWay way)) {
                continue;
            }
            for (int index = 1; index < way.nodeKeys().size(); index++) {
                PrimitiveKey leftKey = way.nodeKeys().get(index - 1);
                PrimitiveKey rightKey = way.nodeKeys().get(index);
                DetachedNode left = requireNode(after, leftKey);
                DetachedNode right = requireNode(after, rightKey);
                if (segmentGeometryUnchanged(before, key, leftKey, rightKey,
                    left.coordinate(), right.coordinate())) {
                    continue;
                }
                if (!editRegion.containsSegment(frame.toMetric(left.coordinate()), frame.toMetric(right.coordinate()))) {
                    throw new IllegalArgumentException("Changed way segment leaves the authorized edit region");
                }
            }
        }
    }

    private static boolean segmentGeometryUnchanged(NetworkSnapshot before, PrimitiveKey wayKey,
        PrimitiveKey leftKey, PrimitiveKey rightKey, GeographicPoint left, GeographicPoint right) {
        DetachedPrimitive value = before.primitives().get(wayKey);
        if (!(value instanceof DetachedWay way)) {
            return false;
        }
        for (int index = 1; index < way.nodeKeys().size(); index++) {
            if (way.nodeKeys().get(index - 1).equals(leftKey) && way.nodeKeys().get(index).equals(rightKey)) {
                return requireNode(before, leftKey).coordinate().equals(left)
                    && requireNode(before, rightKey).coordinate().equals(right);
            }
        }
        return false;
    }

    private static void validatePreviewMatchesAfter(NetworkSnapshot after,
        Map<PrimitiveKey, List<GeographicPoint>> previews) {
        previews.forEach((wayKey, preview) -> {
            DetachedWay way = requireWay(after, wayKey);
            List<GeographicPoint> applied = way.nodeKeys().stream()
                .map(key -> requireNode(after, key).coordinate()).toList();
            if (!applied.equals(preview)) {
                throw new IllegalArgumentException("Reviewed preview does not equal the proposed after-state");
            }
        });
    }

    private static Set<PrimitiveKey> writeSet(NetworkSnapshot before, NetworkSnapshot after) {
        Set<PrimitiveKey> keys = new LinkedHashSet<>(before.primitives().keySet());
        keys.addAll(after.primitives().keySet());
        keys.removeIf(key -> before.primitives().containsKey(key) && after.primitives().containsKey(key)
            && before.primitives().get(key).equals(after.primitives().get(key)));
        return Set.copyOf(keys);
    }

    private static Set<PrimitiveKey> affectedWayKeys(NetworkSnapshot before, NetworkSnapshot after,
        Set<PrimitiveKey> writes) {
        Set<PrimitiveKey> result = writes.stream().filter(key -> key.type() == PrimitiveKey.Type.WAY)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        writes.stream().filter(key -> key.type() == PrimitiveKey.Type.NODE).forEach(node -> {
            before.incomingReferrers().getOrDefault(node, Set.of()).stream()
                .filter(key -> key.type() == PrimitiveKey.Type.WAY).forEach(result::add);
            after.incomingReferrers().getOrDefault(node, Set.of()).stream()
                .filter(key -> key.type() == PrimitiveKey.Type.WAY).forEach(result::add);
        });
        return Set.copyOf(result);
    }

    private static DetachedWay requireWay(NetworkSnapshot snapshot, PrimitiveKey key) {
        DetachedPrimitive value = snapshot.primitives().get(key);
        if (!(value instanceof DetachedWay way)) {
            throw new IllegalArgumentException("Expected detached way is missing");
        }
        return way;
    }

    private static DetachedNode requireNode(NetworkSnapshot snapshot, PrimitiveKey key) {
        DetachedPrimitive value = snapshot.primitives().get(key);
        if (!(value instanceof DetachedNode node)) {
            throw new IllegalArgumentException("Expected detached node is missing");
        }
        return node;
    }

    private static Map<PrimitiveKey, DetachedPrimitive> difference(Map<PrimitiveKey, DetachedPrimitive> values,
        Set<PrimitiveKey> excluded) {
        Map<PrimitiveKey, DetachedPrimitive> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!excluded.contains(key)) {
                result.put(key, value);
            }
        });
        return Map.copyOf(result);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

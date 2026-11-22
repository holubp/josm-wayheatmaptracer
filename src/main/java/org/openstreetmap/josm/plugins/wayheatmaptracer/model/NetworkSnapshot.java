package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.PostprocessingValidationWork;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier;

/** Detached immutable network closure captured on JOSM's owning thread. */
public record NetworkSnapshot(String snapshotId, SnapshotRole role, String datasetIdentity,
    long sourceGeneration, ClosureDescriptor closure, Map<PrimitiveKey, DetachedPrimitive> primitives,
    Map<PrimitiveKey, Set<PrimitiveKey>> incomingReferrerWatches,
    NonTransportSemanticWitness semanticWitness,
    Map<ExternalPort, PrimitiveKey> proposedPortReplacements) {
    /** Historical and factual snapshots carry no proposed port identity replacements. */
    public NetworkSnapshot(String snapshotId, SnapshotRole role, String datasetIdentity,
            long sourceGeneration, ClosureDescriptor closure,
            Map<PrimitiveKey, DetachedPrimitive> primitives,
            Map<PrimitiveKey, Set<PrimitiveKey>> incomingReferrerWatches,
            NonTransportSemanticWitness semanticWitness) {
        this(snapshotId, role, datasetIdentity, sourceGeneration, closure, primitives,
                incomingReferrerWatches, semanticWitness, Map.of());
    }
    /** Historical snapshots carry no additive semantic proof. */
    public NetworkSnapshot(String snapshotId, SnapshotRole role, String datasetIdentity,
            long sourceGeneration, ClosureDescriptor closure,
            Map<PrimitiveKey, DetachedPrimitive> primitives,
            Map<PrimitiveKey, Set<PrimitiveKey>> incomingReferrerWatches) {
        this(snapshotId, role, datasetIdentity, sourceGeneration, closure, primitives,
                incomingReferrerWatches, null);
    }
    static final int MAX_MATERIALIZED_PRIMITIVES = 250_000;
    static final int MAX_PAYLOAD_REFERENCES = 1_000_000;
    static final int MAX_REFERRER_WATCH_IDENTITIES = 1_000_000;

    /** Copies state and validates identity namespaces, internal references, and external ports. */
    public NetworkSnapshot {
        org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.PostprocessingValidationWork.visit();
        if (snapshotId == null || snapshotId.isBlank() || role == null
            || datasetIdentity == null || datasetIdentity.isBlank()
            || sourceGeneration < 0 || closure == null || primitives == null
            || incomingReferrerWatches == null || proposedPortReplacements == null) {
            throw new IllegalArgumentException("Network snapshot is incomplete");
        }
        validateInventoryBounds(primitives, incomingReferrerWatches);
        // Bulk immutable-library copies are admitted/debited before their opaque copy loops.
        PostprocessingValidationWork.charge(2L*primitives.size()+incomingReferrerWatches.size()+proposedPortReplacements.size());
        primitives = Map.copyOf(new LinkedHashMap<>(primitives));
        Map<PrimitiveKey, Set<PrimitiveKey>> watchCopy = new LinkedHashMap<>();
        incomingReferrerWatches.forEach((key, value) -> {PostprocessingValidationWork.charge(value.size());watchCopy.put(key, Set.copyOf(value));});
        incomingReferrerWatches = Map.copyOf(watchCopy);
        proposedPortReplacements = Map.copyOf(proposedPortReplacements);
        if (!proposedPortReplacements.isEmpty() && role != SnapshotRole.PROPOSED_AFTER
                || !closure.externalPorts().containsAll(proposedPortReplacements.keySet())) {
            throw new IllegalArgumentException("Only exact proposed external ports may carry replacement identities");
        }
        for (var entry : proposedPortReplacements.entrySet()) {
            PostprocessingValidationWork.visit();
            var original = primitives.get(entry.getKey().boundaryNodeKey());
            var destination = primitives.get(entry.getValue());
            if (entry.getValue().identityKind() != PrimitiveKey.IdentityKind.PLAN_LOCAL
                    || !closure.protectedExistingNodeKeys().contains(entry.getKey().boundaryNodeKey())
                    || !(original instanceof DetachedNode oldNode)
                    || !(destination instanceof DetachedNode newNode)
                    || !oldNode.coordinate().equals(newNode.coordinate())) {
                throw new IllegalArgumentException("Proposed external port replacement must preserve its protected boundary coordinate");
            }
        }
        Set<PrimitiveKey> keys = primitives.keySet();
        Set<PrimitiveKey> existingKeys = keys.stream().peek(meteredValue -> PostprocessingValidationWork.visit())
            .filter(key -> key.identityKind() == PrimitiveKey.IdentityKind.OSM_UNIQUE)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<PrimitiveKey> plannedKeys = keys.stream().peek(meteredValue -> PostprocessingValidationWork.visit())
            .filter(key -> key.identityKind() == PrimitiveKey.IdentityKind.PLAN_LOCAL)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (primitives.entrySet().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).anyMatch(entry -> !entry.getKey().equals(entry.getValue().key()))
            || !closure.primitiveKeys().containsAll(existingKeys)
            || plannedKeys.stream().peek(meteredValue -> PostprocessingValidationWork.visit()).anyMatch(key -> key.type() != PrimitiveKey.Type.NODE)) {
            throw new IllegalArgumentException("Primitive map violates the typed closure identity contract");
        }
        if (role == SnapshotRole.CAPTURED_BEFORE
            && (!keys.equals(closure.primitiveKeys()) || !plannedKeys.isEmpty())) {
            throw new IllegalArgumentException("Captured-before snapshot must exactly materialize the closure");
        }
        if (role == SnapshotRole.PROPOSED_AFTER) {
            Set<PrimitiveKey> omitted = new LinkedHashSet<>(closure.primitiveKeys());
            omitted.removeAll(existingKeys);
            if (!closure.removableExistingNodeKeys().containsAll(omitted)) {
                throw new IllegalArgumentException("Proposed-after snapshot omits an unauthorized primitive");
            }
        }
        if (!incomingReferrerWatches.keySet().equals(keys)) {
            throw new IllegalArgumentException(
                "Referrer watches require an entry for every materialized primitive");
        }
        for (DetachedPrimitive primitive : primitives.values()) {
            PostprocessingValidationWork.visit();
            if (primitive.deleted()) {
                throw new IllegalArgumentException("Active snapshots represent deletion by absence, not a flag");
            }
            if (primitive instanceof DetachedWay way && !scopedWayNodesPresent(keys,way.nodeKeys())) {
                throw new IllegalArgumentException("Way node occurrence is outside the materialized snapshot");
            }
            if (primitive instanceof DetachedRelation relation
                && relation.members().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).anyMatch(member -> !keys.contains(member.memberKey()))) {
                throw new IllegalArgumentException("Relation member is outside the materialized snapshot");
            }
        }
        Map<PrimitiveKey, Set<PrimitiveKey>> internalReferrers = internalIncomingReferrers(primitives);
        incomingReferrerWatches.forEach((target, watched) -> { PostprocessingValidationWork.visit();
            if (watched.stream().peek(meteredValue -> PostprocessingValidationWork.visit()).anyMatch(referrer -> referrer.identityKind()
                != PrimitiveKey.IdentityKind.OSM_UNIQUE
                || referrer.type() != PrimitiveKey.Type.WAY
                    && referrer.type() != PrimitiveKey.Type.RELATION)) {
                throw new IllegalArgumentException(
                    "Referrer watches may contain only existing way or relation identities");
            }
            Set<PrimitiveKey> internal = internalReferrers.get(target);
            if (!watched.containsAll(internal)
                || watched.stream().peek(meteredValue -> PostprocessingValidationWork.visit()).filter(keys::contains).anyMatch(referrer -> !internal.contains(referrer))) {
                throw new IllegalArgumentException(
                    "Materialized reference payload and referrer watch disagree");
            }
        });
        for (ExternalPort port : closure.externalPorts()) {
            PostprocessingValidationWork.visit();
            DetachedPrimitive primitive = primitives.get(port.wayKey());
            if (!(primitive instanceof DetachedWay way) || !portMatches(port, way, role, primitives,
                    proposedPortReplacements.getOrDefault(port, port.boundaryNodeKey()))) {
                throw new IllegalArgumentException("External port is inconsistent with its captured adjacency");
            }
        }
        DetachedValueVerifier.verify(java.util.Arrays.asList(role, closure, primitives,
                incomingReferrerWatches, semanticWitness, proposedPortReplacements));
    }

    static boolean scopedWayNodesPresent(Set<PrimitiveKey> keys,java.util.List<PrimitiveKey> nodes) {
        if(PostprocessingValidationWork.current()==null)return keys.containsAll(nodes);
        for(var node:nodes){PostprocessingValidationWork.visit();if(!keys.contains(node))return false;}
        return true;
    }
    private static void validateInventoryBounds(Map<PrimitiveKey, DetachedPrimitive> primitives,
        Map<PrimitiveKey, Set<PrimitiveKey>> watches) {
        if (primitives.size() > MAX_MATERIALIZED_PRIMITIVES
            || watches.size() > MAX_MATERIALIZED_PRIMITIVES) {
            throw new IllegalArgumentException("Network snapshot primitive inventory exceeds its budget");
        }
        long payloadReferences = 0;
        for (DetachedPrimitive primitive : primitives.values()) {
            PostprocessingValidationWork.visit();
            payloadReferences += primitive instanceof DetachedWay way ? way.nodeKeys().size()
                : primitive instanceof DetachedRelation relation ? relation.members().size() : 0;
            if (payloadReferences > MAX_PAYLOAD_REFERENCES) {
                throw new IllegalArgumentException("Network snapshot payload reference budget exceeded");
            }
        }
        long watchedIdentities = 0;
        for (Map.Entry<PrimitiveKey, Set<PrimitiveKey>> entry : watches.entrySet()) {
            PostprocessingValidationWork.visit();
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("Referrer watch entries cannot be null");
            }
            watchedIdentities += entry.getValue().size();
            if (watchedIdentities > MAX_REFERRER_WATCH_IDENTITIES) {
                throw new IllegalArgumentException("Network snapshot watch identity budget exceeded");
            }
        }
    }

    private static boolean portMatches(ExternalPort port, DetachedWay way, SnapshotRole role,
        Map<PrimitiveKey, DetachedPrimitive> primitives, PrimitiveKey destinationBoundary) {
        DetachedPrimitive outsidePrimitive = primitives.get(port.outsideNeighborKey());
        if (!(outsidePrimitive instanceof DetachedNode outsideNode)
            || !outsideNode.coordinate().equals(port.outsideNeighborCoordinate())) {
            return false;
        }
        if (role == SnapshotRole.CAPTURED_BEFORE) {
            int boundary = port.boundaryOccurrenceIndex();
            int outside = port.side() == ExternalPort.Side.BEFORE ? boundary - 1 : boundary + 1;
            return boundary >= 0 && boundary < way.nodeKeys().size()
                && outside >= 0 && outside < way.nodeKeys().size()
                && way.nodeKeys().get(boundary).equals(destinationBoundary)
                && way.nodeKeys().get(outside).equals(port.outsideNeighborKey());
        }
        int matches = 0;
        for (int boundary = 0; boundary < way.nodeKeys().size(); boundary++) {
            PostprocessingValidationWork.visit();
            int outside = port.side() == ExternalPort.Side.BEFORE ? boundary - 1 : boundary + 1;
            if (outside >= 0 && outside < way.nodeKeys().size()
                && way.nodeKeys().get(boundary).equals(destinationBoundary)
                && way.nodeKeys().get(outside).equals(port.outsideNeighborKey())) {
                matches++;
            }
        }
        return matches == 1;
    }

    /** Derives references from materialized way occurrences and relation members only. */
    public Map<PrimitiveKey, Set<PrimitiveKey>> internalIncomingReferrers() {
        return internalIncomingReferrers(primitives);
    }

    private static Map<PrimitiveKey, Set<PrimitiveKey>> internalIncomingReferrers(
        Map<PrimitiveKey, DetachedPrimitive> values) {
        Map<PrimitiveKey, Set<PrimitiveKey>> mutable = new LinkedHashMap<>();
        values.keySet().forEach(key -> {PostprocessingValidationWork.visit();mutable.put(key, new LinkedHashSet<>());});
        values.values().forEach(primitive -> { PostprocessingValidationWork.visit();
            if (primitive instanceof DetachedWay way) {
                way.nodeKeys().forEach(node -> {PostprocessingValidationWork.visit();mutable.get(node).add(way.key());});
            } else if (primitive instanceof DetachedRelation relation) {
                relation.members().forEach(member -> {PostprocessingValidationWork.visit();mutable.get(member.memberKey()).add(relation.key());});
            }
        });
        Map<PrimitiveKey, Set<PrimitiveKey>> result = new LinkedHashMap<>();
        mutable.forEach((key, value) -> {PostprocessingValidationWork.charge(value.size()+1L);result.put(key, Set.copyOf(value));});
        return Map.copyOf(result);
    }

    /** Returns a recapture-stable hash excluding attempt-local snapshot ID. */
    public String canonicalHash() {
        return canonicalHashInternal(PostprocessingValidationWork.current());
    }
    /** Same encoding under the caller's explicit synchronous postprocessing context.
     * Caller-owned materialization scratch covers the encoder and sorted key staging.
     */
    public String canonicalHash(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work) {
        try(var scope=PostprocessingValidationWork.open(work)) {return canonicalHashInternal(work);}
    }
    private String canonicalHashInternal(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work) {
        CanonicalEncoder encoder = new CanonicalEncoder(work).field("network-snapshot-v5")
            .field(role.name()).field(datasetIdentity).field(sourceGeneration).field(closure.scope().name())
            .field(closure.queryVersion()).field(closure.mayCreateNodes())
            .field(closure.wayReferrersComplete()).field(closure.relationReferrersComplete())
            .field(closure.nearbyGeometryComplete());
        closure.primitiveKeys().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).sorted(PostprocessingValidationWork.naturalOrder()).forEach(key -> encodeKey(encoder.field("read"), key));
        closure.editableExistingKeys().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).sorted(PostprocessingValidationWork.naturalOrder()).forEach(key -> encodeKey(encoder.field("editable"), key));
        closure.movableExistingNodeKeys().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).sorted(PostprocessingValidationWork.naturalOrder())
            .forEach(key -> encodeKey(encoder.field("movable"), key));
        closure.protectedExistingNodeKeys().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).sorted(PostprocessingValidationWork.naturalOrder())
            .forEach(key -> encodeKey(encoder.field("protected"), key));
        closure.removableExistingNodeKeys().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).sorted(PostprocessingValidationWork.naturalOrder())
            .forEach(key -> encodeKey(encoder.field("removable"), key));
        closure.editableWayOccurrences().entrySet().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).sorted(PostprocessingValidationWork.comparator(Map.Entry.comparingByKey())).forEach(entry -> { PostprocessingValidationWork.visit();
            encodeKey(encoder.field("occurrence-authority"), entry.getKey());
            entry.getValue().forEach(range -> encoder.field(range.firstIndex()).field(range.lastIndex()));
        });
        encodeRegion(encoder.field("collision-region"), closure.collisionEnvelope());
        encodeRegion(encoder.field("edit-region"), closure.editRegion());
        closure.externalPorts().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).sorted(PostprocessingValidationWork.comparator(Comparator.comparing(ExternalPort::wayKey)
            .thenComparing(ExternalPort::boundaryNodeKey).thenComparingInt(ExternalPort::boundaryOccurrenceIndex)))
            .forEach(port -> encodePort(encoder, port));
        primitives.entrySet().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).sorted(PostprocessingValidationWork.comparator(Map.Entry.comparingByKey()))
            .forEach(entry -> encodePrimitive(encoder, entry.getValue()));
        incomingReferrerWatches.entrySet().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).sorted(PostprocessingValidationWork.comparator(Map.Entry.comparingByKey())).forEach(entry -> { PostprocessingValidationWork.visit();
            encodeKey(encoder.field("referrer-watch"), entry.getKey());
            entry.getValue().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).sorted(PostprocessingValidationWork.naturalOrder()).forEach(key -> encodeKey(encoder, key));
        });
        if (!proposedPortReplacements.isEmpty()) {
            encoder.field("proposed-port-replacements-v1");
            proposedPortReplacements.entrySet().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).sorted(PostprocessingValidationWork.comparator(Map.Entry.comparingByKey(
                    Comparator.comparing(ExternalPort::wayKey)
                            .thenComparingInt(ExternalPort::boundaryOccurrenceIndex)
                            .thenComparing(ExternalPort::side)
                            .thenComparing(ExternalPort::boundaryNodeKey)
                            .thenComparing(ExternalPort::outsideNeighborKey)))).forEach(entry -> { PostprocessingValidationWork.visit();
                                encodePort(encoder, entry.getKey());
                                encodeKey(encoder, entry.getValue());
                            });
        }
        return encoder.sha256();
    }

    static void encodePrimitive(CanonicalEncoder encoder, DetachedPrimitive primitive) {
        encodeKey(encoder.field("primitive"), primitive.key());
        encoder.field(primitive.modified());
        primitive.tags().entrySet().stream().peek(meteredValue -> PostprocessingValidationWork.visit()).sorted(PostprocessingValidationWork.comparator(Map.Entry.comparingByKey()))
            .forEach(entry -> encoder.field("tag").field(entry.getKey()).field(entry.getValue()));
        if (primitive instanceof DetachedNode node) {
            encoder.field(Double.toHexString(node.coordinate().latitudeDegrees()))
                .field(Double.toHexString(node.coordinate().longitudeDegrees()));
        } else if (primitive instanceof DetachedWay way) {
            way.nodeKeys().forEach(key -> encodeKey(encoder.field("node-occurrence"), key));
        } else if (primitive instanceof DetachedRelation relation) {
            relation.members().forEach(member -> { PostprocessingValidationWork.visit();
                encodeKey(encoder.field("relation-member"), member.memberKey());
                encoder.field(member.role());
            });
        }
    }

    private static void encodePort(CanonicalEncoder encoder, ExternalPort port) {
        encodeKey(encoder.field("external-port"), port.wayKey());
        encodeKey(encoder, port.boundaryNodeKey());
        encodeKey(encoder, port.outsideNeighborKey());
        encoder.field(port.boundaryOccurrenceIndex()).field(port.side().name())
            .field(Double.toHexString(port.outsideNeighborCoordinate().latitudeDegrees()))
            .field(Double.toHexString(port.outsideNeighborCoordinate().longitudeDegrees()));
    }

    private static void encodeRegion(CanonicalEncoder encoder, MetricRegion region) {
        region.polygons().forEach(polygon -> { PostprocessingValidationWork.visit();
            encoder.field("polygon").field(polygon.size());
            polygon.forEach(point -> encoder.field(Double.toHexString(point.xMeters()))
                .field(Double.toHexString(point.yMeters())));
        });
    }

    static void encodeKey(CanonicalEncoder encoder, PrimitiveKey key) {
        encoder.field(key.type().name()).field(key.identityKind().name()).field(key.id());
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier;

/** Detached immutable network closure captured on JOSM's owning thread. */
public record NetworkSnapshot(String snapshotId, SnapshotRole role, String datasetIdentity,
    long sourceGeneration, ClosureDescriptor closure, Map<PrimitiveKey, DetachedPrimitive> primitives) {
    /** Copies state and validates identity namespaces, internal references, and external ports. */
    public NetworkSnapshot {
        if (snapshotId == null || snapshotId.isBlank() || role == null
            || datasetIdentity == null || datasetIdentity.isBlank()
            || sourceGeneration < 0 || closure == null || primitives == null) {
            throw new IllegalArgumentException("Network snapshot is incomplete");
        }
        primitives = Map.copyOf(new LinkedHashMap<>(primitives));
        Set<PrimitiveKey> keys = primitives.keySet();
        Set<PrimitiveKey> existingKeys = keys.stream()
            .filter(key -> key.identityKind() == PrimitiveKey.IdentityKind.OSM_UNIQUE)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<PrimitiveKey> plannedKeys = keys.stream()
            .filter(key -> key.identityKind() == PrimitiveKey.IdentityKind.PLAN_LOCAL)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (primitives.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(entry.getValue().key()))
            || !closure.primitiveKeys().containsAll(existingKeys)
            || plannedKeys.stream().anyMatch(key -> key.type() != PrimitiveKey.Type.NODE)) {
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
        for (DetachedPrimitive primitive : primitives.values()) {
            if (primitive.deleted()) {
                throw new IllegalArgumentException("Active snapshots represent deletion by absence, not a flag");
            }
            if (primitive instanceof DetachedWay way && !keys.containsAll(way.nodeKeys())) {
                throw new IllegalArgumentException("Way node occurrence is outside the materialized snapshot");
            }
            if (primitive instanceof DetachedRelation relation
                && relation.members().stream().anyMatch(member -> !keys.contains(member.memberKey()))) {
                throw new IllegalArgumentException("Relation member is outside the materialized snapshot");
            }
        }
        for (ExternalPort port : closure.externalPorts()) {
            DetachedPrimitive primitive = primitives.get(port.wayKey());
            if (!(primitive instanceof DetachedWay way) || !portMatches(port, way, role, primitives)) {
                throw new IllegalArgumentException("External port is inconsistent with its captured adjacency");
            }
        }
        DetachedValueVerifier.verify(java.util.List.of(role, closure, primitives));
    }

    private static boolean portMatches(ExternalPort port, DetachedWay way, SnapshotRole role,
        Map<PrimitiveKey, DetachedPrimitive> primitives) {
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
                && way.nodeKeys().get(boundary).equals(port.boundaryNodeKey())
                && way.nodeKeys().get(outside).equals(port.outsideNeighborKey());
        }
        int matches = 0;
        for (int boundary = 0; boundary < way.nodeKeys().size(); boundary++) {
            int outside = port.side() == ExternalPort.Side.BEFORE ? boundary - 1 : boundary + 1;
            if (outside >= 0 && outside < way.nodeKeys().size()
                && way.nodeKeys().get(boundary).equals(port.boundaryNodeKey())
                && way.nodeKeys().get(outside).equals(port.outsideNeighborKey())) {
                matches++;
            }
        }
        return matches == 1;
    }

    /** Derives complete within-snapshot incoming references from way occurrences and relation members. */
    public Map<PrimitiveKey, Set<PrimitiveKey>> incomingReferrers() {
        Map<PrimitiveKey, Set<PrimitiveKey>> mutable = new LinkedHashMap<>();
        primitives.values().forEach(primitive -> {
            if (primitive instanceof DetachedWay way) {
                way.nodeKeys().forEach(node -> mutable.computeIfAbsent(node,
                    ignored -> new LinkedHashSet<>()).add(way.key()));
            } else if (primitive instanceof DetachedRelation relation) {
                relation.members().forEach(member -> mutable.computeIfAbsent(member.memberKey(),
                    ignored -> new LinkedHashSet<>()).add(relation.key()));
            }
        });
        Map<PrimitiveKey, Set<PrimitiveKey>> result = new LinkedHashMap<>();
        mutable.forEach((key, value) -> result.put(key, Set.copyOf(value)));
        return Map.copyOf(result);
    }

    /** Returns a recapture-stable hash excluding attempt-local snapshot ID. */
    public String canonicalHash() {
        CanonicalEncoder encoder = new CanonicalEncoder().field("network-snapshot-v4")
            .field(role.name()).field(datasetIdentity).field(sourceGeneration).field(closure.scope().name())
            .field(closure.queryVersion()).field(closure.mayCreateNodes())
            .field(closure.wayReferrersComplete()).field(closure.relationReferrersComplete())
            .field(closure.nearbyGeometryComplete());
        closure.primitiveKeys().stream().sorted().forEach(key -> encodeKey(encoder.field("read"), key));
        closure.editableExistingKeys().stream().sorted().forEach(key -> encodeKey(encoder.field("editable"), key));
        closure.movableExistingNodeKeys().stream().sorted()
            .forEach(key -> encodeKey(encoder.field("movable"), key));
        closure.protectedExistingNodeKeys().stream().sorted()
            .forEach(key -> encodeKey(encoder.field("protected"), key));
        closure.removableExistingNodeKeys().stream().sorted()
            .forEach(key -> encodeKey(encoder.field("removable"), key));
        closure.editableWayOccurrences().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            encodeKey(encoder.field("occurrence-authority"), entry.getKey());
            entry.getValue().forEach(range -> encoder.field(range.firstIndex()).field(range.lastIndex()));
        });
        encodeRegion(encoder.field("collision-region"), closure.collisionEnvelope());
        encodeRegion(encoder.field("edit-region"), closure.editRegion());
        closure.externalPorts().stream().sorted(Comparator.comparing(ExternalPort::wayKey)
            .thenComparing(ExternalPort::boundaryNodeKey).thenComparingInt(ExternalPort::boundaryOccurrenceIndex))
            .forEach(port -> encodePort(encoder, port));
        primitives.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> encodePrimitive(encoder, entry.getValue()));
        incomingReferrers().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            encodeKey(encoder.field("referrers"), entry.getKey());
            entry.getValue().stream().sorted().forEach(key -> encodeKey(encoder, key));
        });
        return encoder.sha256();
    }

    static void encodePrimitive(CanonicalEncoder encoder, DetachedPrimitive primitive) {
        encodeKey(encoder.field("primitive"), primitive.key());
        encoder.field(primitive.modified());
        primitive.tags().entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> encoder.field("tag").field(entry.getKey()).field(entry.getValue()));
        if (primitive instanceof DetachedNode node) {
            encoder.field(Double.toHexString(node.coordinate().latitudeDegrees()))
                .field(Double.toHexString(node.coordinate().longitudeDegrees()));
        } else if (primitive instanceof DetachedWay way) {
            way.nodeKeys().forEach(key -> encodeKey(encoder.field("node-occurrence"), key));
        } else if (primitive instanceof DetachedRelation relation) {
            relation.members().forEach(member -> {
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
        region.polygons().forEach(polygon -> {
            encoder.field("polygon").field(polygon.size());
            polygon.forEach(point -> encoder.field(Double.toHexString(point.xMeters()))
                .field(Double.toHexString(point.yMeters())));
        });
    }

    static void encodeKey(CanonicalEncoder encoder, PrimitiveKey key) {
        encoder.field(key.type().name()).field(key.identityKind().name()).field(key.id());
    }
}

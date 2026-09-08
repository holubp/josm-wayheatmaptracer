package org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Detached metric-space network used by the junction topology planner. */
public final class TopologyNetwork {
    /** Primitive namespace used to prevent cross-type and existing/planned identity collisions. */
    public enum PrimitiveType { NODE, WAY, RELATION }

    /** Distinguishes captured identities from stable plan-local identities. */
    public enum IdentityNamespace { EXISTING, PLAN_LOCAL }

    /** Whether all relation members and metadata required for safety analysis were captured. */
    public enum Completeness { COMPLETE, INCOMPLETE }

    /** Typed detached primitive identity. */
    public record Id(PrimitiveType type, IdentityNamespace identityNamespace, long value)
        implements Comparable<Id> {
        public Id {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(identityNamespace, "identityNamespace");
            if (identityNamespace == IdentityNamespace.PLAN_LOCAL && value <= 0) {
                throw new IllegalArgumentException("Plan-local identities must be positive");
            }
        }

        /** Creates a captured primitive identity. */
        public static Id existing(PrimitiveType type, long value) {
            return new Id(type, IdentityNamespace.EXISTING, value);
        }

        /** Creates a stable plan-local primitive identity. */
        public static Id planned(PrimitiveType type, long value) {
            return new Id(type, IdentityNamespace.PLAN_LOCAL, value);
        }

        @Override
        public int compareTo(Id other) {
            int typeOrder = type.compareTo(other.type);
            if (typeOrder != 0) {
                return typeOrder;
            }
            int namespaceOrder = identityNamespace.compareTo(other.identityNamespace);
            return namespaceOrder != 0 ? namespaceOrder : Long.compare(value, other.value);
        }
    }

    /** Point in one explicitly supplied local metric frame. */
    public record Point(double xMeters, double yMeters) {
        public Point {
            if (!Double.isFinite(xMeters) || !Double.isFinite(yMeters)) {
                throw new IllegalArgumentException("Topology coordinates must be finite");
            }
        }

        /** Returns Euclidean distance in the shared metric frame. */
        public double distance(Point other) {
            Objects.requireNonNull(other, "other");
            return Math.hypot(xMeters - other.xMeters, yMeters - other.yMeters);
        }
    }

    /** Detached node state. */
    public record Node(Id id, Point point, Map<String, String> tags) {
        public Node {
            requireType(id, PrimitiveType.NODE);
            Objects.requireNonNull(point, "point");
            tags = immutableTags(tags);
        }
    }

    /** Detached way state preserving every node occurrence in order. */
    public record Way(Id id, List<Id> nodeIds, Map<String, String> tags) {
        public Way {
            requireType(id, PrimitiveType.WAY);
            Objects.requireNonNull(nodeIds, "nodeIds");
            if (nodeIds.size() < 2 || nodeIds.stream().anyMatch(node -> node == null
                || node.type() != PrimitiveType.NODE)) {
                throw new IllegalArgumentException("A way requires at least two typed node occurrences");
            }
            nodeIds = List.copyOf(nodeIds);
            tags = immutableTags(tags);
        }

        /** Returns whether this way is closed by identity. */
        public boolean closed() {
            return nodeIds.get(0).equals(nodeIds.get(nodeIds.size() - 1));
        }
    }

    /** Ordered relation member and role. */
    public record RelationMember(Id memberId, String role) {
        public RelationMember {
            Objects.requireNonNull(memberId, "memberId");
            role = role == null ? "" : role;
        }
    }

    /** Detached relation state preserving member order, roles, tags and capture completeness. */
    public record Relation(Id id, List<RelationMember> members, Map<String, String> tags,
        Completeness completeness) {
        public Relation {
            requireType(id, PrimitiveType.RELATION);
            Objects.requireNonNull(members, "members");
            if (members.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Relation members cannot be null");
            }
            members = List.copyOf(members);
            tags = immutableTags(tags);
            Objects.requireNonNull(completeness, "completeness");
        }
    }

    private final Map<Id, Node> nodes;
    private final Map<Id, Way> ways;
    private final Map<Id, Relation> relations;

    /** Creates a deeply immutable detached network and validates complete internal references. */
    public TopologyNetwork(Collection<Node> nodes, Collection<Way> ways, Collection<Relation> relations) {
        this.nodes = index(nodes, Node::id, "node");
        this.ways = index(ways, Way::id, "way");
        this.relations = index(relations, Relation::id, "relation");
        for (Way way : this.ways.values()) {
            if (!this.nodes.keySet().containsAll(way.nodeIds())) {
                throw new IllegalArgumentException("Way references a node outside the detached network: " + way.id());
            }
        }
        for (Relation relation : this.relations.values()) {
            if (relation.completeness() == Completeness.COMPLETE) {
                for (RelationMember member : relation.members()) {
                    if (!contains(member.memberId())) {
                        throw new IllegalArgumentException(
                            "Complete relation references a primitive outside the detached network: " + relation.id());
                    }
                }
            }
        }
    }

    /** Returns immutable nodes keyed by typed identity. */
    public Map<Id, Node> nodes() {
        return nodes;
    }

    /** Returns immutable ways keyed by typed identity. */
    public Map<Id, Way> ways() {
        return ways;
    }

    /** Returns immutable relations keyed by typed identity. */
    public Map<Id, Relation> relations() {
        return relations;
    }

    /** Returns one required node. */
    public Node node(Id id) {
        return required(nodes, id, "node");
    }

    /** Returns one required way. */
    public Way way(Id id) {
        return required(ways, id, "way");
    }

    /** Returns one required relation. */
    public Relation relation(Id id) {
        return required(relations, id, "relation");
    }

    /** Returns whether the typed primitive is present. */
    public boolean contains(Id id) {
        return switch (id.type()) {
            case NODE -> nodes.containsKey(id);
            case WAY -> ways.containsKey(id);
            case RELATION -> relations.containsKey(id);
        };
    }

    static TopologyNetwork fromMaps(Map<Id, Node> nodes, Map<Id, Way> ways,
        Map<Id, Relation> relations) {
        return new TopologyNetwork(nodes.values(), ways.values(), relations.values());
    }

    private static Map<String, String> immutableTags(Map<String, String> tags) {
        Objects.requireNonNull(tags, "tags");
        if (tags.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException("Tags cannot contain null keys or values");
        }
        return Map.copyOf(new LinkedHashMap<>(tags));
    }

    private static void requireType(Id id, PrimitiveType type) {
        if (id == null || id.type() != type) {
            throw new IllegalArgumentException("Expected " + type + " identity");
        }
    }

    private static <T> Map<Id, T> index(Collection<T> values,
        java.util.function.Function<T, Id> keyFunction, String label) {
        Objects.requireNonNull(values, label + "s");
        Map<Id, T> result = new LinkedHashMap<>();
        for (T value : values) {
            Objects.requireNonNull(value, label);
            Id key = keyFunction.apply(value);
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("Duplicate " + label + " identity: " + key);
            }
        }
        return Map.copyOf(result);
    }

    private static <T> T required(Map<Id, T> values, Id id, String label) {
        T result = values.get(id);
        if (result == null) {
            throw new IllegalArgumentException("Unknown " + label + " identity: " + id);
        }
        return result;
    }
}

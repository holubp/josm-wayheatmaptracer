package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Type-safe OSM or plan-local primitive identity. */
public record PrimitiveKey(Type type, IdentityKind identityKind, long id) implements Comparable<PrimitiveKey> {
    /** Primitive namespace. */
    public enum Type { NODE, WAY, RELATION }

    /** Whether the ID is an existing JOSM unique ID or a stable plan-local identity. */
    public enum IdentityKind { OSM_UNIQUE, PLAN_LOCAL }

    /** Validates required identity metadata. */
    public PrimitiveKey {
        if (type == null || identityKind == null) {
            throw new IllegalArgumentException("Primitive identity requires a type and namespace");
        }
    }

    /** Creates an identity for an existing captured primitive. */
    public static PrimitiveKey existing(Type type, long uniqueId) {
        return new PrimitiveKey(type, IdentityKind.OSM_UNIQUE, uniqueId);
    }

    /** Creates a stable plan-local identity for a proposed primitive. */
    public static PrimitiveKey planned(Type type, long localId) {
        return new PrimitiveKey(type, IdentityKind.PLAN_LOCAL, localId);
    }

    @Override
    public int compareTo(PrimitiveKey other) {
        int typeOrder = type.compareTo(other.type);
        if (typeOrder != 0) {
            return typeOrder;
        }
        int namespaceOrder = identityKind.compareTo(other.identityKind);
        return namespaceOrder != 0 ? namespaceOrder : Long.compare(id, other.id);
    }
}

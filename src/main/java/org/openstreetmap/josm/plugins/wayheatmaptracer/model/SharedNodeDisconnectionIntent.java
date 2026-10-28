package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact occurrence-scoped opt-in to disconnect selected shared nodes from other ways. */
public record SharedNodeDisconnectionIntent(PrimitiveKey selectedWayKey, OccurrenceRange selectedRange,
        Map<Integer, PrimitiveKey> originalNodeKeys, String sourceNetworkIdentity) {
    /** Copies the requested occurrence identities and enforces the default-empty representation. */
    public SharedNodeDisconnectionIntent {
        if (originalNodeKeys == null) {
            throw new IllegalArgumentException("Disconnection occurrence identities are required");
        }
        Map<Integer, PrimitiveKey> copy = new LinkedHashMap<>();
        originalNodeKeys.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Integer index = entry.getKey();
            PrimitiveKey node = entry.getValue();
            if (index == null || index < 0 || node == null || node.type() != PrimitiveKey.Type.NODE
                    || node.identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE) {
                throw new IllegalArgumentException("Disconnection requires existing node occurrences");
            }
            copy.put(index, node);
        });
        originalNodeKeys = Collections.unmodifiableMap(copy);
        if (originalNodeKeys.isEmpty()) {
            if (selectedWayKey != null || selectedRange != null
                    || sourceNetworkIdentity != null && !sourceNetworkIdentity.isEmpty()) {
                throw new IllegalArgumentException("Empty disconnection intent cannot carry source authority");
            }
        } else if (selectedWayKey == null || selectedWayKey.type() != PrimitiveKey.Type.WAY
                || selectedWayKey.identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE
                || selectedRange == null || sourceNetworkIdentity == null || sourceNetworkIdentity.isBlank()) {
            throw new IllegalArgumentException("Disconnection intent requires frozen way, range, and source identity");
        }
    }

    /** Returns the inert default used by existing callers and persisted settings. */
    public static SharedNodeDisconnectionIntent none() {
        return new SharedNodeDisconnectionIntent(null, null, Map.of(), null);
    }

    /** Whether the caller has explicitly selected any shared-node occurrences to disconnect. */
    public boolean isEmpty() {
        return originalNodeKeys.isEmpty();
    }

    /** Stable identity independent of caller map iteration order. */
    public String canonicalHash() {
        if (isEmpty()) {
            throw new IllegalStateException("Empty disconnection intent has no authority identity");
        }
        CanonicalEncoder encoder = new CanonicalEncoder().field("shared-node-disconnection-intent-v1");
        NetworkSnapshot.encodeKey(encoder, selectedWayKey);
        encoder.field(selectedRange.firstIndex()).field(selectedRange.lastIndex())
                .field(sourceNetworkIdentity);
        originalNodeKeys.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            encoder.field(entry.getKey());
            NetworkSnapshot.encodeKey(encoder, entry.getValue());
        });
        return encoder.sha256();
    }
}

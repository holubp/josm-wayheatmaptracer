package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Additive, read-only relation evidence bound to an unchanged historical network core. */
public record NonTransportSemanticWitness(String coreHash, String datasetIdentity,
        long sourceGeneration, Map<PrimitiveKey, RelationEvidence> relations) {
    /** One ordered member, including factual complete live geometry at capture time. */
    public record Member(PrimitiveKey key, String role, boolean complete) {
        public Member {
            if (key == null || key.identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE
                    || role == null) throw new IllegalArgumentException("Incomplete semantic member");
        }
    }

    /** Complete relation payload and its incoming parent identities; never edit authority. */
    public record RelationEvidence(PrimitiveKey key, Map<String, String> tags,
            List<Member> members, boolean complete, boolean deleted, boolean modified,
            Set<PrimitiveKey> parentWatches) {
        public RelationEvidence {
            if (key == null || key.type() != PrimitiveKey.Type.RELATION || tags == null
                    || key.identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE
                    || members == null || parentWatches == null
                    || parentWatches.stream().anyMatch(parent -> parent.type() != PrimitiveKey.Type.RELATION
                            || parent.identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE)) {
                throw new IllegalArgumentException("Incomplete semantic relation evidence");
            }
            tags = Map.copyOf(tags);
            members = List.copyOf(members);
            parentWatches = Set.copyOf(parentWatches);
        }
    }

    public NonTransportSemanticWitness {
        if (coreHash == null || !coreHash.matches("[0-9a-f]{64}")
                || datasetIdentity == null || datasetIdentity.isBlank() || sourceGeneration < 0
                || relations == null || relations.size() > 250_000
                || relations.entrySet().stream().anyMatch(e -> !e.getKey().equals(e.getValue().key()))) {
            throw new IllegalArgumentException("Semantic witness is not bound to a network core");
        }
        long references = 0;
        long tags = 0;
        for (RelationEvidence relation : relations.values()) {
            references += relation.members().size() + relation.parentWatches().size();
            tags += relation.tags().size();
            if (references > 1_000_000 || tags > 1_000_000) {
                throw new IllegalArgumentException("Semantic witness inventory exceeds its budget");
            }
        }
        relations = Map.copyOf(relations);
    }

    /** Requires exact source identity and core payload; absent witnesses never prove semantics. */
    public boolean matches(NetworkSnapshot network) {
        return network.role() == SnapshotRole.CAPTURED_BEFORE
                && datasetIdentity.equals(network.datasetIdentity())
                && sourceGeneration == network.sourceGeneration()
                && coreHash.equals(network.canonicalHash());
    }

    /** Identity used by review and replay in addition to the preserved core identity. */
    public String canonicalHash() {
        CanonicalEncoder encoder = new CanonicalEncoder().field("nontransport-semantic-witness-v1")
                .field(coreHash).field(datasetIdentity).field(sourceGeneration);
        relations.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            RelationEvidence value = entry.getValue();
            NetworkSnapshot.encodeKey(encoder, value.key());
            encoder.field(value.complete()).field(value.deleted()).field(value.modified());
            encoder.field(value.tags().size());
            value.tags().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(tag ->
                    encoder.field(tag.getKey()).field(tag.getValue()));
            encoder.field(value.members().size());
            value.members().forEach(member -> {
                NetworkSnapshot.encodeKey(encoder.field("member"), member.key());
                encoder.field(member.role()).field(member.complete());
            });
            encoder.field(value.parentWatches().size());
            value.parentWatches().stream().sorted().forEach(key ->
                    NetworkSnapshot.encodeKey(encoder.field("parent"), key));
        });
        return encoder.sha256();
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            Set<PrimitiveKey> parentWatches, Optional<Boolean> payloadComplete) {
        /** Historical constructor: payload completeness was not separately captured. */
        public RelationEvidence(PrimitiveKey key, Map<String, String> tags, List<Member> members,
                boolean complete, boolean deleted, boolean modified, Set<PrimitiveKey> parentWatches) {
            this(key, tags, members, complete, deleted, modified, parentWatches, Optional.empty());
        }

        public RelationEvidence {
            if (key == null || key.type() != PrimitiveKey.Type.RELATION || tags == null
                    || key.identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE
                    || members == null || parentWatches == null || payloadComplete == null
                    || complete && payloadComplete.filter(value -> !value).isPresent()
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
        boolean hasPayloadFact = relations.values().stream()
                .anyMatch(relation -> relation.payloadComplete().isPresent());
        CanonicalEncoder encoder = new CanonicalEncoder().field(hasPayloadFact
                        ? "nontransport-semantic-witness-v2" : "nontransport-semantic-witness-v1")
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
            if (hasPayloadFact) {
                encoder.field(value.payloadComplete().isPresent());
                value.payloadComplete().ifPresent(encoder::field);
            }
        });
        return encoder.sha256();
    }

    /** Compares all captured facts while allowing a historical witness to omit the new payload fact. */
    public boolean hasSameKnownFactsAs(NonTransportSemanticWitness current) {
        if (current == null || !coreHash.equals(current.coreHash)
                || !datasetIdentity.equals(current.datasetIdentity)
                || sourceGeneration != current.sourceGeneration) return false;
        return hasSameRelationFactsAs(current);
    }

    /** Compares relation facts while allowing a legacy row to omit payload completeness. */
    public boolean hasSameRelationFactsAs(NonTransportSemanticWitness current) {
        if (current == null || !relations.keySet().equals(current.relations.keySet())) return false;
        for (var entry : relations.entrySet()) {
            RelationEvidence expected = entry.getValue();
            RelationEvidence actual = current.relations.get(entry.getKey());
            if (!expected.key().equals(actual.key()) || !expected.tags().equals(actual.tags())
                    || !expected.members().equals(actual.members()) || expected.complete() != actual.complete()
                    || expected.deleted() != actual.deleted() || expected.modified() != actual.modified()
                    || !expected.parentWatches().equals(actual.parentWatches())
                    || expected.payloadComplete().isPresent()
                            && !expected.payloadComplete().equals(actual.payloadComplete())) return false;
        }
        return true;
    }

    /** Null-safe known-fact comparison for boundaries that recapture live relation evidence. */
    public static boolean sameKnownFacts(NonTransportSemanticWitness expected,
            NonTransportSemanticWitness current) {
        return expected == null ? current == null : expected.hasSameKnownFactsAs(current);
    }

    /** Null-safe relation-only comparison for source/effective snapshots sharing a core. */
    public static boolean sameKnownRelationFacts(NonTransportSemanticWitness expected,
            NonTransportSemanticWitness current) {
        return expected == null ? current == null : expected.hasSameRelationFactsAs(current);
    }
}

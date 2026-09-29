package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Immutable input to the additive Format-15 serializer. */
public final class Format15Bundle {
    /** The diagnostic schema version introduced by the v0.22 replay foundation. */
    public static final int FORMAT_VERSION = 15;

    private final String buildIdentity;
    private final String sourceIdentityHash;
    private final String parameterHash;
    private final Map<String, Format15Artifact> artifacts;

    /** Validates and copies build, source, parameter, and artifact identities. */
    public Format15Bundle(String buildIdentity, String sourceIdentityHash, String parameterHash,
        Map<String, Format15Artifact> artifacts) {
        if (buildIdentity == null || buildIdentity.isBlank()) {
            throw new IllegalArgumentException("Format-15 build identity is required");
        }
        Format15Safety.requireSafeExportedMetadata(buildIdentity);
        this.buildIdentity = buildIdentity;
        this.sourceIdentityHash = Format15Safety.requiredHash(sourceIdentityHash, "sourceIdentityHash");
        this.parameterHash = Format15Safety.requiredHash(parameterHash, "parameterHash");
        if (artifacts == null || artifacts.isEmpty() || artifacts.size() > Format15Safety.MAX_ARTIFACTS) {
            throw new IllegalArgumentException("Format-15 artifact set is empty or too large");
        }
        Map<String, Format15Artifact> copy = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<String, Format15Artifact> entry : artifacts.entrySet()) {
            Format15Artifact artifact = entry.getValue();
            if (artifact == null || !entry.getKey().equals(artifact.name())
                || copy.put(entry.getKey(), artifact) != null) {
                throw new IllegalArgumentException("Format-15 artifact identity is inconsistent");
            }
            total += artifact.sizeBytes();
            if (total > Format15Safety.MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("Format-15 artifact set exceeds the total limit");
            }
        }
        this.artifacts = Map.copyOf(copy);
    }

    /** Returns the diagnostic build identity. */
    public String buildIdentity() {
        return buildIdentity;
    }

    /** Returns the hash of the frozen source/evidence identity. */
    public String sourceIdentityHash() {
        return sourceIdentityHash;
    }

    /** Returns the hash of the effective algorithm parameters. */
    public String parameterHash() {
        return parameterHash;
    }

    /** Returns the immutable artifact map. */
    public Map<String, Format15Artifact> artifacts() {
        return artifacts;
    }

    /** Returns artifact names in deterministic lexical order. */
    public Set<String> artifactNames() {
        return Set.copyOf(new TreeSet<>(artifacts.keySet()));
    }

    /** Returns one artifact by name, failing clearly when it is absent. */
    public Format15Artifact artifact(String name) {
        return Optional.ofNullable(artifacts.get(name))
            .orElseThrow(() -> new IllegalArgumentException("Missing Format-15 artifact: " + name));
    }
}

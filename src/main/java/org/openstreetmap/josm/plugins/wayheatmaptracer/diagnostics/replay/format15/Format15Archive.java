package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayCapability;

/** Immutable archive view returned by the strict Format-15 and legacy reader. */
public final class Format15Archive {
    private final int formatVersion;
    private final String buildIdentity;
    private final String sourceIdentityHash;
    private final String parameterHash;
    private final Map<String, Format15Artifact> artifacts;
    private final ReplayCapability capability;

    Format15Archive(int formatVersion, String buildIdentity, String sourceIdentityHash,
        String parameterHash, Map<String, Format15Artifact> artifacts, ReplayCapability capability) {
        this.formatVersion = formatVersion;
        this.buildIdentity = buildIdentity == null ? "" : buildIdentity;
        this.sourceIdentityHash = sourceIdentityHash == null ? "" : sourceIdentityHash;
        this.parameterHash = parameterHash == null ? "" : parameterHash;
        this.artifacts = Map.copyOf(artifacts);
        this.capability = capability;
    }

    /** Returns the declared diagnostic format version. */
    public int formatVersion() {
        return formatVersion;
    }

    /** Returns the recorded build identity, if present. */
    public String buildIdentity() {
        return buildIdentity;
    }

    /** Returns the recorded source identity hash, or empty for old formats. */
    public String sourceIdentityHash() {
        return sourceIdentityHash;
    }

    /** Returns the recorded parameter hash, or empty for old formats. */
    public String parameterHash() {
        return parameterHash;
    }

    /** Returns immutable non-manifest artifact content. */
    public Map<String, Format15Artifact> artifacts() {
        return artifacts;
    }

    /** Returns artifact names in the validated archive. */
    public Set<String> artifactNames() {
        return artifacts.keySet();
    }

    /** Looks up one validated artifact without opening a file or using a network. */
    public Optional<Format15Artifact> artifact(String name) {
        return Optional.ofNullable(artifacts.get(name));
    }

    /** Returns the honest replay capability derived from the validated member inventory. */
    public ReplayCapability capability() {
        return capability;
    }
}

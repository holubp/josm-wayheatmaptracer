package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Explicit inference capabilities that replace correctness-affecting enum equality tests. */
public record EngineCapabilities(
    EvidenceInput evidenceInput,
    boolean supportsImageRefit,
    boolean requiresExplicitNodeAssignments,
    boolean supportsNetworkEditPlan,
    boolean usesLegacyRanking,
    boolean usesCommonFinalRanking,
    ImageRecovery imageRecovery,
    boolean experimental
) {
    /** Returns whether this engine requires a detached scalar evidence snapshot. */
    public boolean requiresEvidenceSnapshot() {
        return evidenceInput != EvidenceInput.LEGACY_PROFILES;
    }

    /** Frozen evidence representation consumed by an engine. */
    public enum EvidenceInput { LEGACY_PROFILES, SCALAR_SNAPSHOT, SCALAR_SNAPSHOT_WITH_A_PROPOSALS }

    /** How the engine may use direction-aware image search. */
    public enum ImageRecovery { NONE, SELECTIVE, DIRECT }

    /** Compatibility route numerics; explicit reattachment may wrap its output downstream. */
    public static final EngineCapabilities LEGACY = new EngineCapabilities(
        EvidenceInput.LEGACY_PROFILES, false, false, true, true, false, ImageRecovery.NONE, false);

    /** Existing A uses scalar evidence and common final validation without image-route recovery. */
    public static final EngineCapabilities CORRIDOR_AWARE = new EngineCapabilities(
        EvidenceInput.SCALAR_SNAPSHOT, true, true, true, false, true, ImageRecovery.NONE, false);

    /** Standalone experimental B. */
    public static final EngineCapabilities PROBABILISTIC = new EngineCapabilities(
        EvidenceInput.SCALAR_SNAPSHOT, true, true, true, false, true, ImageRecovery.NONE, true);

    /** A proposals plus independent B and selective image recovery. */
    public static final EngineCapabilities HYBRID = new EngineCapabilities(
        EvidenceInput.SCALAR_SNAPSHOT_WITH_A_PROPOSALS, true, true, true, false, true,
        ImageRecovery.SELECTIVE, true);

    /** Direct image-space route inference. */
    public static final EngineCapabilities DIRECTIONAL_IMAGE = new EngineCapabilities(
        EvidenceInput.SCALAR_SNAPSHOT, true, true, true, false, true, ImageRecovery.DIRECT, true);
}

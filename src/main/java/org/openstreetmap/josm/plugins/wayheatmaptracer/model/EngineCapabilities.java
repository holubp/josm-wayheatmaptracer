package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Explicit inference capabilities that replace correctness-affecting enum equality tests. */
public record EngineCapabilities(
    EvidenceInput evidenceInput,
    boolean supportsManagedSource,
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
        EvidenceInput.LEGACY_PROFILES, false, false, false, true, true, false, ImageRecovery.NONE, false);

    /** Existing A uses scalar evidence and common final validation without image-route recovery. */
    public static final EngineCapabilities CORRIDOR_AWARE = new EngineCapabilities(
        EvidenceInput.SCALAR_SNAPSHOT, true, true, true, true, false, true, ImageRecovery.NONE, false);

    /** Standalone experimental B. */
    public static final EngineCapabilities PROBABILISTIC = new EngineCapabilities(
        EvidenceInput.SCALAR_SNAPSHOT, true, true, true, true, false, true, ImageRecovery.NONE, true);

    /** Historical archive identity only; no live A+B implementation remains. */
    public static final EngineCapabilities HISTORICAL_HYBRID = new EngineCapabilities(
        EvidenceInput.SCALAR_SNAPSHOT_WITH_A_PROPOSALS, false, false, false, false, false, false,
        ImageRecovery.NONE, true);

    /** Sequential Legacy proposals refined by independent interval-local B runs. */
    public static final EngineCapabilities LEGACY_THEN_B = new EngineCapabilities(
        EvidenceInput.SCALAR_SNAPSHOT, true, false, true, true, false, true,
        ImageRecovery.NONE, true);

    /** Direct image-space route inference. */
    public static final EngineCapabilities DIRECTIONAL_IMAGE = new EngineCapabilities(
        EvidenceInput.SCALAR_SNAPSHOT, false, true, true, true, false, true, ImageRecovery.DIRECT, true);
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Optional;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier;

/** Frozen effective request supplied to one tracing engine. */
public record TraceRequest(
    PrimitiveKey selectedWayKey,
    OccurrenceRange selectedRange,
    TrackerMode engine,
    AlignmentMode geometryMode,
    RecoveryPermissions permissions,
    TraceBudgets budgets,
    String evidenceSnapshotId,
    String evidenceContentHash,
    String networkSnapshotId,
    String networkContentHash,
    String settingsHash,
    String parameterHash,
    String samplerId,
    double configuredSampleStepMeters,
    ProfileChainage profileChainage,
    EvidenceResolution evidenceResolution,
    Optional<CorridorTraceInput> corridorInput
) {
    /** Validates immutable identities, measured chainage, and factual resolution support. */
    public TraceRequest {
        if (selectedWayKey == null || selectedWayKey.type() != PrimitiveKey.Type.WAY || selectedRange == null
            || engine == null || geometryMode == null || permissions == null || budgets == null
            || blank(evidenceSnapshotId) || blank(evidenceContentHash) || blank(networkSnapshotId)
            || blank(networkContentHash) || blank(settingsHash)
            || blank(parameterHash) || blank(samplerId) || !Double.isFinite(configuredSampleStepMeters)
            || configuredSampleStepMeters <= 0.0 || profileChainage == null || evidenceResolution == null
            || corridorInput == null
            || Math.abs(profileChainage.configuredStepMeters() - configuredSampleStepMeters) > 1e-12
            || !evidenceResolution.covers(profileChainage)) {
            throw new IllegalArgumentException("Trace request is incomplete");
        }
        DetachedValueVerifier.verify(java.util.List.of(selectedWayKey, selectedRange, engine, geometryMode,
            permissions, budgets, profileChainage, evidenceResolution, corridorInput));
    }

    /** Creates requests for engines that do not consume frozen corridor profile locations. */
    public TraceRequest(
        PrimitiveKey selectedWayKey,
        OccurrenceRange selectedRange,
        TrackerMode engine,
        AlignmentMode geometryMode,
        RecoveryPermissions permissions,
        TraceBudgets budgets,
        String evidenceSnapshotId,
        String evidenceContentHash,
        String networkSnapshotId,
        String networkContentHash,
        String settingsHash,
        String parameterHash,
        String samplerId,
        double configuredSampleStepMeters,
        ProfileChainage profileChainage,
        EvidenceResolution evidenceResolution
    ) {
        this(selectedWayKey, selectedRange, engine, geometryMode, permissions, budgets,
            evidenceSnapshotId, evidenceContentHash, networkSnapshotId, networkContentHash,
            settingsHash, parameterHash, samplerId, configuredSampleStepMeters, profileChainage,
            evidenceResolution, Optional.empty());
    }

    /** Returns capabilities derived solely from the stable engine selection. */
    public EngineCapabilities capabilities() {
        return engine.capabilities();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Machine-readable cleanup evidence eligibility or skip reason. */
public enum CleanupEvidenceStatus {
    /** Complete finite evidence is available for cleanup. */
    COMPLETE,
    /** Candidate came from a tracker that does not provide cleanup evidence. */
    LEGACY_NOT_AVAILABLE,
    /** At least one sampled profile lacks its slide-time projected offset transform. */
    MISSING_PROJECTED_TRANSFORM,
    /** Candidate rows do not align exactly with the shared sampling frame. */
    MISALIGNED_CANDIDATE_ROWS,
    /** Longitudinal selected-corridor evidence is incomplete. */
    INCOMPLETE_LONGITUDINAL_EVIDENCE,
    /** Retaining the detector frame would exceed the named cleanup memory limit. */
    MEMORY_LIMIT_EXCEEDED
}

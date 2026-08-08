package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

/**
 * Immutable candidate-owned cleanup evidence with one detector-level frame shared by reference.
 *
 * @param samplingFrame detector-level scalar sampling frame
 * @param profiles candidate-specific selected-corridor rows
 * @param status cleanup eligibility or typed skip reason
 */
public record CandidateCleanupEvidence(
    CleanupSamplingFrame samplingFrame,
    List<CandidateCleanupProfile> profiles,
    CleanupEvidenceStatus status
) {
    /** Makes candidate rows immutable and requires non-null ownership metadata. */
    public CandidateCleanupEvidence {
        samplingFrame = java.util.Objects.requireNonNull(samplingFrame, "samplingFrame");
        profiles = List.copyOf(profiles);
        status = java.util.Objects.requireNonNull(status, "status");
    }

    /**
     * Returns empty typed evidence for legacy and compatibility candidates.
     *
     * @return empty typed evidence
     */
    public static CandidateCleanupEvidence empty() {
        return new CandidateCleanupEvidence(
            CleanupSamplingFrame.empty(), List.of(), CleanupEvidenceStatus.LEGACY_NOT_AVAILABLE);
    }

    /**
     * Creates a non-cleanable evidence record with an explicit reason.
     *
     * @param frame retained frame, which may be empty when construction exceeded its memory limit
     * @param rows any candidate rows that remain useful for diagnosis
     * @param status typed reason other than {@link CleanupEvidenceStatus#COMPLETE}
     * @return typed skipped evidence
     */
    public static CandidateCleanupEvidence skipped(
        CleanupSamplingFrame frame,
        List<CandidateCleanupProfile> rows,
        CleanupEvidenceStatus status
    ) {
        if (status == CleanupEvidenceStatus.COMPLETE) {
            throw new IllegalArgumentException("Skipped cleanup evidence requires a non-complete status");
        }
        return new CandidateCleanupEvidence(frame, rows, status);
    }

    /**
     * Validates candidate rows against the shared detector frame and returns a typed result.
     *
     * @param frame shared detector sampling frame
     * @param rows candidate-specific selected-track rows
     * @return complete evidence or a typed non-cleanable result
     */
    public static CandidateCleanupEvidence validated(
        CleanupSamplingFrame frame,
        List<CandidateCleanupProfile> rows
    ) {
        List<CandidateCleanupProfile> immutableRows = List.copyOf(rows);
        if (frame.profiles().isEmpty()) {
            return new CandidateCleanupEvidence(frame, immutableRows,
                CleanupEvidenceStatus.MISALIGNED_CANDIDATE_ROWS);
        }
        if (frame.profiles().stream().anyMatch(profile -> profile.projectedLateralTransform() == null)) {
            return new CandidateCleanupEvidence(frame, immutableRows,
                CleanupEvidenceStatus.MISSING_PROJECTED_TRANSFORM);
        }
        if (frame.profiles().size() != immutableRows.size()) {
            return new CandidateCleanupEvidence(frame, immutableRows,
                CleanupEvidenceStatus.MISALIGNED_CANDIDATE_ROWS);
        }
        for (int index = 0; index < immutableRows.size(); index++) {
            CandidateCleanupProfile row = immutableRows.get(index);
            if (row.profileIndex() != index
                || (row.provenance() != CleanupEvidenceProvenance.UNSUPPORTED
                    && !frame.profiles().get(index).anchorWithinRaster())) {
                return new CandidateCleanupEvidence(frame, immutableRows,
                    CleanupEvidenceStatus.MISALIGNED_CANDIDATE_ROWS);
            }
        }
        return new CandidateCleanupEvidence(frame, immutableRows, CleanupEvidenceStatus.COMPLETE);
    }

    /**
     * Creates evidence that must be complete, for tests and internal proven-complete construction.
     *
     * @param frame shared detector sampling frame
     * @param rows aligned candidate rows
     * @return complete cleanup evidence
     */
    public static CandidateCleanupEvidence complete(
        CleanupSamplingFrame frame,
        List<CandidateCleanupProfile> rows
    ) {
        CandidateCleanupEvidence result = validated(frame, rows);
        if (!result.eligible()) {
            throw new IllegalArgumentException("Cleanup evidence is incomplete: " + result.status());
        }
        return result;
    }

    /**
     * Reports whether every required cleanup input is complete and aligned.
     *
     * @return true only for complete evidence
     */
    public boolean eligible() {
        return status == CleanupEvidenceStatus.COMPLETE;
    }

    /**
     * Estimates retained candidate-row memory excluding the shared detector frame.
     *
     * @return candidate-specific byte estimate
     */
    public long estimatedCandidateBytes() {
        return 96L * profiles.size();
    }
}

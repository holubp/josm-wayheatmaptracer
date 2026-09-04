package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

/**
 * Typed decision describing how one candidate may proceed from preview.
 *
 * @param disposition automatic, reviewable, or blocked state
 * @param reasons machine-readable reasons supporting the disposition
 */
public record CandidateAssessment(Disposition disposition, List<Reason> reasons) {
    /** Normalizes the reason list to an immutable value. */
    public CandidateAssessment {
        if (disposition == null) {
            throw new IllegalArgumentException("Candidate disposition is required");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** Candidate handling states used by preview and apply. */
    public enum Disposition {
        /** Candidate passes every automatic evidence and safety gate. */
        APPLICABLE,
        /** Candidate is structurally safe but needs explicit human acceptance of incomplete evidence. */
        REVIEW_REQUIRED,
        /** Candidate has no meaningful signal or fails a non-overridable safety gate. */
        HARD_BLOCKED
    }

    /** Machine-readable causes for non-automatic candidate handling. */
    public enum Reason {
        /** No meaningful heatmap signal supports the candidate. */
        NO_HEATMAP_SIGNAL,
        /** Too few profiles support safe alignment. */
        INSUFFICIENT_SIGNAL_SUPPORT,
        /** Longitudinal heatmap evidence does not cover the complete selected segment. */
        INCOMPLETE_LONGITUDINAL_CORRIDOR,
        /** One or more geometry, topology, or assignment checks failed. */
        STRUCTURAL_SAFETY_FAILURE
    }

    /**
     * Returns whether the candidate can be applied without user confirmation.
     *
     * @return true when automatic Apply is permitted
     */
    public boolean automaticallyApplicable() {
        return disposition == Disposition.APPLICABLE;
    }

    /**
     * Returns whether explicit review may promote the candidate.
     *
     * @return true when user confirmation may enable Apply
     */
    public boolean reviewRequired() {
        return disposition == Disposition.REVIEW_REQUIRED;
    }
}

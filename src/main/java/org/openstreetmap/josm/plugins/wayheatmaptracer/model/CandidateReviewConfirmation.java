package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * Session-local proof that the user reviewed one exact candidate preview.
 *
 * @param candidateId reviewed candidate identifier
 * @param previewGeometry exact projected geometry displayed during review
 * @param proposedNodePositions immutable existing-node targets reviewed with the geometry
 */
public record CandidateReviewConfirmation(
    String candidateId,
    List<EastNorth> previewGeometry,
    Map<Long, EastNorth> proposedNodePositions
) {
    /** Normalizes reviewed geometry and assignments to immutable values. */
    public CandidateReviewConfirmation {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("Reviewed candidate identifier is required");
        }
        previewGeometry = List.copyOf(previewGeometry);
        proposedNodePositions = Map.copyOf(proposedNodePositions);
    }

    /**
     * Captures the exact geometry and assignments currently displayed for a candidate.
     *
     * @param candidate candidate selected in preview
     * @param previewGeometry exact displayed final geometry
     * @return immutable session-local confirmation
     */
    public static CandidateReviewConfirmation capture(
        CenterlineCandidate candidate,
        List<EastNorth> previewGeometry
    ) {
        return new CandidateReviewConfirmation(
            candidate.id(), previewGeometry, candidate.proposedNodePositions());
    }

    /**
     * Checks that neither candidate identity, displayed geometry, nor assignments changed.
     *
     * @param candidate currently selected candidate
     * @param currentPreviewGeometry currently displayed projected geometry
     * @return true only for the exact reviewed candidate state
     */
    public boolean matches(CenterlineCandidate candidate, List<EastNorth> currentPreviewGeometry) {
        return candidateId.equals(candidate.id())
            && previewGeometry.equals(currentPreviewGeometry)
            && proposedNodePositions.equals(candidate.proposedNodePositions());
    }
}

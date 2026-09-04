package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateAssessment;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateGeometryCleanup;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorCoverage;
/** Verifies action-level candidate selection before the modeless preview opens. */
class AlignWayActionTest {
    @Test
    void ordinaryRetryIsCappedAtFourteenMeters() {
        assertEquals(14.0, AlignWayAction.ordinaryRetryMaximumMeters(7.01, 80.0), 0.0);
        assertEquals(14.0, AlignWayAction.ordinaryRetryMaximumMeters(10.0, 80.0), 0.0);
        assertEquals(14.0, AlignWayAction.ordinaryRetryMaximumMeters(14.0, 80.0), 0.0);
        assertEquals(20.0, AlignWayAction.ordinaryRetryMaximumMeters(20.0, 80.0), 0.0);
        assertEquals(14.0, AlignWayAction.defaultRetryWidthMeters(7.01, 14.0), 0.0);
    }

    @Test
    void initiallySelectsApplicableCleanedSiblingAheadOfInspectionOnlyRawCandidate() {
        CenterlineCandidate raw = candidate("hot/ridge-1");
        CenterlineCandidate cleaned = candidate("hot/ridge-1#cleaned");
        AlignmentResult result = result(List.of(raw, cleaned), List.of(cleaned));

        assertSame(cleaned, AlignWayAction.initialCandidate(result));
    }

    @Test
    void explicitlyRequestedCleanupPrefersChangedCleanedSiblingWithoutReorderingCandidates() {
        CenterlineCandidate raw = candidate("hot/ridge-1").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE, "hot/ridge-1"));
        CenterlineCandidate cleaned = candidate("hot/ridge-1#cleaned").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.CLEANED, "hot/ridge-1"));
        AlignmentResult result = result(List.of(raw, cleaned), List.of(raw, cleaned));

        assertSame(cleaned, AlignWayAction.initialCandidate(result));
        assertSame(raw, result.candidates().get(0));
    }

    @Test
    void explicitlyRequestedCleanupAlsoPrefersAChangedPartialSibling() {
        CenterlineCandidate raw = candidate("hot/ridge-1").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE, "hot/ridge-1"));
        CenterlineCandidate partial = candidate("hot/ridge-1#cleaned").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED, "hot/ridge-1"));

        assertSame(partial, AlignWayAction.initialCandidate(
            result(List.of(raw, partial), List.of(raw, partial))));
    }

    @Test
    void fallsBackToFirstInspectionCandidateOnlyWhenNoneAreApplicable() {
        CenterlineCandidate first = candidate("hot/ridge-1");
        CenterlineCandidate second = candidate("hot/ridge-2");

        assertSame(first, AlignWayAction.initialCandidate(result(List.of(first, second), List.of())));
    }


    @Test
    void initiallyPrefersReviewRequiredCandidateOverHardBlockedCandidate() {
        CenterlineCandidate blocked = candidate("hot/ridge-blocked");
        CenterlineCandidate reviewable = candidate("hot/ridge-review").withEvidence(
            new CandidateEvidence("hot", 4, 4, 0, 0, 3.2, 0.8, 0.2, 0.9, 0.5, 0.1, List.of())
                .withCorridorCoverage(coverage(true, false, 0, "unresolved-search-edge-censoring")));

        assertSame(reviewable, AlignWayAction.initialCandidate(
            result(List.of(blocked, reviewable), List.of())));
    }
    @Test
    void rejectsAnEmptyCandidateResult() {
        AlignmentResult result = result(List.of(), List.of());

        assertThrows(IllegalStateException.class, () -> AlignWayAction.initialCandidate(result));
    }

    @Test
    void cleanupDetailDescribesTheSelectedCandidateRatherThanTheCandidateList() {
        CenterlineCandidate raw = candidate("hot/ridge-1").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE, "hot/ridge-1"));
        CenterlineCandidate cleaned = candidate("hot/ridge-1#cleaned").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.CLEANED, "hot/ridge-1"));

        String rawDetail = AlignWayAction.cleanupDetail(raw);
        String cleanedDetail = AlignWayAction.cleanupDetail(cleaned);

        assertTrue(rawDetail.contains("cleaned alternative available"));
        assertTrue(rawDetail.contains("before 3"));
        assertTrue(cleanedDetail.contains("fully applied"));
        assertTrue(cleanedDetail.contains("after 2"));
        assertTrue(!rawDetail.equals(cleanedDetail));
    }
    @Test
    void cleanupStatusMakesPartialAndSkippedProcessingVisible() {
        CenterlineCandidate partial = candidate("hot/ridge-1#cleaned").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED, "hot/ridge-1"));
        CenterlineCandidate partialWithoutProtected = candidate("hot/ridge-3#cleaned").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED, "hot/ridge-3")
            .withIntervalSummary(2, 1, 0));
        CenterlineCandidate unchangedAroundProtected = candidate("hot/ridge-4").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.UNCHANGED, "hot/ridge-4")
            .withIntervalSummary(1, 0, 1));
        CenterlineCandidate skipped = candidate("hot/ridge-2").withGeometryCleanup(cleanup(
            CandidateGeometryCleanup.Outcome.SKIPPED, "hot/ridge-2"));

        assertTrue(AlignWayAction.cleanupStatus(partial).contains("partially cleaned in 1 interval"));
        assertTrue(AlignWayAction.cleanupStatus(partial).contains("1 protected neighborhood"));
        assertTrue(AlignWayAction.cleanupStatus(partialWithoutProtected)
            .contains("other eligible geometry stayed unchanged for safety"));
        assertTrue(AlignWayAction.cleanupStatus(unchangedAroundProtected)
            .contains("1 protected neighborhood"));
        assertTrue(AlignWayAction.cleanupStatus(skipped).contains("skipped"));
        assertTrue(partial.displayName().contains("partially cleaned"));
    }


    @Test
    void offersWiderRetryForBridgedOrUnresolvedCorridorCoverageOnly() {
        CenterlineCandidate bridged = candidate("hot/ridge-1").withEvidence(CandidateEvidence.empty()
            .withCorridorCoverage(coverage(true, true, 1, "complete-with-search-edge-bridge")));
        CenterlineCandidate unresolved = candidate("hot/ridge-2").withEvidence(CandidateEvidence.empty()
            .withCorridorCoverage(coverage(true, false, 0, "unresolved-search-edge-censoring")));
        CenterlineCandidate complete = candidate("hot/ridge-3").withEvidence(CandidateEvidence.empty()
            .withCorridorCoverage(coverage(true, true, 0, "complete")));
        CenterlineCandidate genericBridge = candidate("hot/ridge-4").withEvidence(CandidateEvidence.empty()
            .withCorridorCoverage(coverage(true, true, 1, "complete")));

        assertTrue(AlignWayAction.canRetryWithWiderSearch(bridged));
        assertTrue(AlignWayAction.canRetryWithWiderSearch(unresolved));
        assertTrue(!AlignWayAction.canRetryWithWiderSearch(complete));
        assertTrue(!AlignWayAction.canRetryWithWiderSearch(genericBridge));
    }


    @Test
    void candidateCoverageMessagesExplainSearchEdgeStateWithoutRawEnums() {
        CenterlineCandidate bridged = candidate("hot/ridge-1").withEvidence(CandidateEvidence.empty()
            .withCorridorCoverage(coverage(true, true, 2, "complete-with-search-edge-bridge")));
        CenterlineCandidate unresolved = candidate("hot/ridge-2").withEvidence(CandidateEvidence.empty()
            .withCorridorCoverage(coverage(true, false, 0, "unresolved-search-edge-censoring")));
        CandidateAssessment applicable = new CandidateAssessment(
            CandidateAssessment.Disposition.APPLICABLE, List.of());
        CandidateAssessment review = new CandidateAssessment(
            CandidateAssessment.Disposition.REVIEW_REQUIRED,
            List.of(CandidateAssessment.Reason.INCOMPLETE_LONGITUDINAL_CORRIDOR));
        CandidateAssessment blocked = new CandidateAssessment(
            CandidateAssessment.Disposition.HARD_BLOCKED,
            List.of(CandidateAssessment.Reason.STRUCTURAL_SAFETY_FAILURE));

        assertTrue(AlignWayAction.candidateListLabel(bridged, applicable, false)
            .contains("search-edge gaps bridged"));
        assertTrue(AlignWayAction.candidateListLabel(unresolved, review, false)
            .contains("review required"));
        assertTrue(AlignWayAction.candidateListLabel(unresolved, review, true)
            .contains("review confirmed"));
        assertTrue(AlignWayAction.coverageStatus(bridged, 7.0, applicable, false)
            .contains("Search-edge gaps were interpolated"));
        assertTrue(AlignWayAction.coverageStatus(unresolved, 7.0, review, false).contains("7.0 m search boundary"));
        assertTrue(AlignWayAction.coverageStatus(unresolved, 7.0, review, false).contains("Review required"));
        assertTrue(AlignWayAction.canConfirmCandidate(review));
        assertTrue(!AlignWayAction.canConfirmCandidate(blocked));
    }

    private static CenterlineCandidate candidate(String id) {
        return new CenterlineCandidate(id, 0.8, List.of(), List.of());
    }

    private static CandidateGeometryCleanup cleanup(
        CandidateGeometryCleanup.Outcome outcome,
        String parentId
    ) {
        CandidateGeometryCleanup report = new CandidateGeometryCleanup(parentId, outcome, "test", List.of(),
            3, 3, outcome == CandidateGeometryCleanup.Outcome.CLEANED
                || outcome == CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED ? 2 : 3,
            0, 0, 0, 0, 0, 1.0, 1.0, 0.0,
            OptionalDouble.empty(), OptionalDouble.empty());
        return outcome == CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED
            ? report.withIntervalSummary(2, 1, 1) : report;
    }


    private static CorridorCoverage coverage(boolean measured, boolean complete, int bridges, String reason) {
        return new CorridorCoverage(measured, complete, 4, 4, 1.0, 0, 3,
            0.0, 0.0, 0, 0.0, bridges, false, reason);
    }
    private static AlignmentResult result(
        List<CenterlineCandidate> candidates,
        List<CenterlineCandidate> applicable
    ) {
        return new AlignmentResult(null, null, candidates, List.of(), List.of(), List.of(),
            null, null, List.of(), applicable);
    }
}

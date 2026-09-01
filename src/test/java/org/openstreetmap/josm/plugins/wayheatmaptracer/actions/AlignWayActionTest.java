package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateGeometryCleanup;

/** Verifies action-level candidate selection before the modeless preview opens. */
class AlignWayActionTest {
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

    private static CenterlineCandidate candidate(String id) {
        return new CenterlineCandidate(id, 0.8, List.of(), List.of());
    }

    private static CandidateGeometryCleanup cleanup(
        CandidateGeometryCleanup.Outcome outcome,
        String parentId
    ) {
        return new CandidateGeometryCleanup(parentId, outcome, "test", List.of(),
            3, 3, outcome == CandidateGeometryCleanup.Outcome.CLEANED
                || outcome == CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED ? 2 : 3,
            0, 0, 0, 0, 0, 1.0, 1.0, 0.0,
            OptionalDouble.empty(), OptionalDouble.empty());
    }

    private static AlignmentResult result(
        List<CenterlineCandidate> candidates,
        List<CenterlineCandidate> applicable
    ) {
        return new AlignmentResult(null, null, candidates, List.of(), List.of(), List.of(),
            null, null, List.of(), applicable);
    }
}

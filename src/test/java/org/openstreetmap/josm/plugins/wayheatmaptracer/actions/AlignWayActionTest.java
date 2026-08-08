package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;

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

    private static CenterlineCandidate candidate(String id) {
        return new CenterlineCandidate(id, 0.8, List.of(), List.of());
    }

    private static AlignmentResult result(
        List<CenterlineCandidate> candidates,
        List<CenterlineCandidate> applicable
    ) {
        return new AlignmentResult(null, null, candidates, List.of(), List.of(), List.of(),
            null, null, List.of(), applicable);
    }
}

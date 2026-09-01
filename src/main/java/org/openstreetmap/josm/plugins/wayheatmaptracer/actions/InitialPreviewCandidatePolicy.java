package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import java.util.List;
import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateGeometryCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;

/** Selects the initially displayed candidate without changing detector or candidate ordering. */
final class InitialPreviewCandidatePolicy {
    private InitialPreviewCandidatePolicy() {
    }

    /**
     * Prefers an applicable changed cleaned sibling of the highest-ranked applicable base.
     *
     * @param allCandidates stable complete candidate order
     * @param applicableCandidates stable applicable-candidate order
     * @return candidate to display, or {@code null} when no candidate exists
     */
    static CenterlineCandidate select(
        List<CenterlineCandidate> allCandidates,
        List<CenterlineCandidate> applicableCandidates
    ) {
        Objects.requireNonNull(allCandidates, "allCandidates");
        Objects.requireNonNull(applicableCandidates, "applicableCandidates");
        CenterlineCandidate first = applicableCandidates.isEmpty()
            ? allCandidates.stream().findFirst().orElse(null) : applicableCandidates.get(0);
        if (first == null || first.geometryCleanup().cleanedCandidate()) {
            return first;
        }
        CandidateGeometryCleanup cleanup = first.geometryCleanup();
        if (cleanup.outcome() != CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE) {
            return first;
        }
        for (CenterlineCandidate candidate : applicableCandidates) {
            CandidateGeometryCleanup sibling = candidate.geometryCleanup();
            if (sibling.cleanedCandidate()
                && sibling.parentCandidateId().equals(first.id())
                && changed(first, candidate)) {
                return candidate;
            }
        }
        return first;
    }

    private static boolean changed(CenterlineCandidate base, CenterlineCandidate cleaned) {
        if (!base.finalPreviewPoints().isEmpty() || !cleaned.finalPreviewPoints().isEmpty()) {
            return !base.finalPreviewPoints().equals(cleaned.finalPreviewPoints());
        }
        return base.geometryCleanup().beforePointCount() != cleaned.geometryCleanup().afterPointCount();
    }
}

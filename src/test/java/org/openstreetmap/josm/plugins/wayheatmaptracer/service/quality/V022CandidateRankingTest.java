package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

/** T075-T080: deterministic physical ranking shared by every modern engine. */
class V022CandidateRankingTest {
    private final CandidateRankingPolicy ranking = new CandidateRankingPolicy();

    @Test
    void T075_completeCoherentRouteOutranksFourteenPercentFragment() {
        CandidateRankingPolicy.Candidate coherent = candidate("coherent", TrackerMode.PROBABILISTIC, true,
                75, 100, 5, 0, 0.18, 10, 0, false, false);
        CandidateRankingPolicy.Candidate fragment = candidate("fragment", TrackerMode.CORRIDOR_AWARE, false,
                14, 100, 0, 0, 0.03, 2, 0, false, false);

        assertEquals("coherent", ranking.rank(List.of(fragment, coherent)).get(0).id());
    }

    @Test
    void T076_highCoverageWrongBranchLosesToLowerCoverageCorrectBranch() {
        CandidateRankingPolicy.Candidate wrong = candidate("wrong", TrackerMode.PROBABILISTIC, true,
                98, 100, 1, 1, 0.01, 3, 0, false, false);
        CandidateRankingPolicy.Candidate correct = candidate("correct", TrackerMode.CORRIDOR_AWARE, true,
                72, 100, 8, 0, 0.16, 4, 0, false, false);

        assertEquals("correct", ranking.rank(List.of(wrong, correct)).get(0).id());
    }

    @Test
    void T077_engineOwnedObjectivesAreNeverComparedAcrossEngines() {
        CandidateRankingPolicy.Candidate first = candidate("a", TrackerMode.CORRIDOR_AWARE, true,
                96, 100, 1, 0, 0.1, 3, 10000, false, false);
        CandidateRankingPolicy.Candidate second = candidate("b", TrackerMode.PROBABILISTIC, true,
                96, 100, 1, 0, 0.1, 3, -10000, false, false);

        assertEquals("a", ranking.rank(List.of(second, first)).get(0).id());
    }

    @Test
    void T078_severeLocalDefectOutweighsGoodAverageImageFit() {
        CandidateRankingPolicy.Candidate defective = candidate("defective", TrackerMode.CORRIDOR_AWARE, true,
                99, 100, 1, 1, 0.01, 3, 0, false, false);
        CandidateRankingPolicy.Candidate sound = candidate("sound", TrackerMode.CORRIDOR_AWARE, true,
                95, 100, 2, 0, 0.12, 3, 0, false, false);

        assertEquals("sound", ranking.rank(List.of(defective, sound)).get(0).id());
    }

    @Test
    void T079_rankingIsDeterministicUnderEnumerationPermutation() {
        List<CandidateRankingPolicy.Candidate> candidates = List.of(
                candidate("c", TrackerMode.DIRECTIONAL_IMAGE, true, 96, 100, 1, 0, .1, 3, 0, false, false),
                candidate("a", TrackerMode.CORRIDOR_AWARE, true, 96, 100, 1, 0, .1, 3, 0, false, false),
                candidate("b", TrackerMode.PROBABILISTIC, true, 96, 100, 1, 0, .1, 3, 0, false, false));
        List<CandidateRankingPolicy.Candidate> reversed = new ArrayList<>(candidates);
        java.util.Collections.reverse(reversed);

        assertEquals(ranking.rank(candidates), ranking.rank(reversed));

        CandidateRankingPolicy.Candidate improved = candidate("z", "family-x", true, true);
        CandidateRankingPolicy.Candidate raw = candidate("a", "family-x", false, false);
        CandidateRankingPolicy.Candidate other = candidate("m", "family-y", false, false);
        List<CandidateRankingPolicy.Candidate> cycle = List.of(improved, raw, other);
        List<CandidateRankingPolicy.Candidate> cycleReversed = new ArrayList<>(cycle);
        java.util.Collections.reverse(cycleReversed);
        assertEquals(ranking.rank(cycle), ranking.rank(cycleReversed),
                "cleanup sibling preference must not make the total order cyclic");
    }

    @Test
    void T080_pointCountAndHybridLabelProvideNoAutomaticBonus() {
        CandidateRankingPolicy.Candidate ordinary = candidate("a-ordinary", TrackerMode.PROBABILISTIC, true,
                96, 100, 1, 0, .1, 50, 0, false, false);
        CandidateRankingPolicy.Candidate hybridCleaned = candidate("z-hybrid", TrackerMode.HYBRID, true,
                96, 100, 1, 0, .1, 3, 0, true, false);

        assertEquals("a-ordinary", ranking.rank(List.of(hybridCleaned, ordinary)).get(0).id());
    }

    @Test
    void physicalQualityRejectsUnavailableImageCost() {
        assertThrows(IllegalArgumentException.class, () -> new CandidateRankingPolicy.PhysicalQuality(
                true, false, 0, 0, 0, 10, 10, 0, Double.POSITIVE_INFINITY,
                0, 0, 0, 0));
    }

    @Test
    void physicalQualityRejectsImpossibleSupportRanges() {
        assertThrows(IllegalArgumentException.class, () -> new CandidateRankingPolicy.PhysicalQuality(
                true, false, 0, 0, 0, 11, 10, 0, 0.1, 0.1, 0.1, 0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new CandidateRankingPolicy.PhysicalQuality(
                true, false, 0, 0, 0, 8, 10, 11, 0.1, 0.1, 0.1, 0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new CandidateRankingPolicy.PhysicalQuality(
                true, false, 0, 0, 0, 8, 10, 1, 0.1, 0.1, 0.1, 0, 1.1));
        assertThrows(IllegalArgumentException.class, () -> new CandidateRankingPolicy.PhysicalQuality(
                true, false, 0, 0, 0, 0, 0, 0, 0.1, 0.1, 0.1, 0, 0.5));
    }

    private static CandidateRankingPolicy.Candidate candidate(String id, TrackerMode engine, boolean complete,
            double directLength, double totalLength, double unsupportedSpan, int severeDefects, double imageFit,
            int pointCount, double engineObjective, boolean cleaned, boolean hardBlocked) {
        CandidateRankingPolicy.PhysicalQuality quality = new CandidateRankingPolicy.PhysicalQuality(
                complete, hardBlocked, severeDefects, 0, 0, directLength, totalLength, unsupportedSpan,
                imageFit, Double.isFinite(imageFit) ? imageFit : 0.0, 0.05, 1, 0.5);
        return new CandidateRankingPolicy.Candidate(id, engine, "native", quality, engineObjective,
                pointCount, cleaned, "family", false);
    }

    private static CandidateRankingPolicy.Candidate candidate(String id, String family,
            boolean cleaned, boolean cleanupImprovement) {
        CandidateRankingPolicy.PhysicalQuality quality = new CandidateRankingPolicy.PhysicalQuality(
                true, false, 0, 0, 0, 10, 10, 0, 0, 0, 0, 0, 0);
        return new CandidateRankingPolicy.Candidate(id, TrackerMode.CORRIDOR_AWARE, "native", quality,
                0, 3, cleaned, family, cleanupImprovement);
    }
}

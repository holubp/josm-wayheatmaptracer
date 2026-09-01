package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

class CenterlineCandidateTest {
    @Test
    void formatsDetectorCandidateForUsers() {
        CenterlineCandidate candidate = new CenterlineCandidate(
            "purple/ridge-4",
            -399.4,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(1, 1)),
            List.of(0.0, 1.0)
        );

        assertEquals("Purple detector - ridge 4 - no signal", candidate.displayName());
        assertFalse(candidate.toString().contains("-399.4"));
    }

    @Test
    void formatsConsensusAndParallelContext() {
        CenterlineCandidate candidate = new CenterlineCandidate(
            "consensus-3/dual/ridge-1/near mapped parallel",
            12.0,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(1, 1)),
            List.of(0.0, 1.0)
        );

        assertTrue(candidate.displayName().contains("Consensus 3 detectors"));
        assertTrue(candidate.displayName().contains("Dual detector"));
        assertTrue(candidate.displayName().contains("mapped parallel"));
    }

    @Test
    void formatsFusedConsensusWithoutFakeDetectorMode() {
        CenterlineCandidate candidate = new CenterlineCandidate(
            "consensus-3/consensus/ridge-1",
            42.0,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(1, 1)),
            List.of(0.0, 1.0)
        ).withEvidence(new CandidateEvidence(
            "consensus",
            2,
            2,
            0,
            0,
            1.0,
            0.5,
            0.2,
            1.0,
            0.5,
            0.0,
            List.of("blue", "hot", "gray")
        ));

        assertEquals("Consensus: blue + hot + gray - ridge 1 - strong", candidate.displayName());
        assertFalse(candidate.displayName().contains("Blue detector"));
        assertFalse(candidate.displayName().contains("Consensus detector"));
    }

    @Test
    void formatsLowConfidenceWarningsForUsers() {
        CenterlineCandidate candidate = new CenterlineCandidate(
            "blue/ridge-1",
            3.0,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(1, 1)),
            List.of(0.0, 1.0)
        ).withSafetyWarnings(List.of("low support", "weak z13 validation"));

        assertTrue(candidate.displayName().contains("low support"));
        assertTrue(candidate.displayName().contains("weak z13 validation"));
    }

    @Test
    void formatsSparseBundleAsCorridorForUsers() {
        CenterlineCandidate candidate = new CenterlineCandidate(
            "hot/bundle-3", 3.0, List.of(), List.of());

        assertTrue(candidate.displayName().contains("sparse corridor 3"));
        assertFalse(candidate.displayName().contains("bundle 3"));
    }

    @Test
    void preservesImmutableProposedNodePositionsAcrossCandidateCopies() {
        Map<Long, EastNorth> mutable = new java.util.LinkedHashMap<>();
        mutable.put(123L, new EastNorth(4.0, 5.0));
        CenterlineCandidate candidate = new CenterlineCandidate(
            "blue/ridge-1", 3.0, List.of(), List.of())
            .withFinalPreviewGeometry(List.of(new EastNorth(0.0, 0.0), new EastNorth(4.0, 5.0)), mutable);
        mutable.clear();

        CenterlineCandidate copy = candidate.withScore(4.0).withSafetyWarnings(List.of("test"));

        assertEquals(Map.of(123L, new EastNorth(4.0, 5.0)), copy.proposedNodePositions());
        assertThrows(UnsupportedOperationException.class,
            () -> copy.proposedNodePositions().put(456L, new EastNorth(1.0, 1.0)));
        assertThrows(IllegalArgumentException.class,
            () -> candidate.withFinalPreviewGeometry(candidate.finalPreviewPoints(),
                Map.of(123L, new EastNorth(Double.NaN, 1.0))));
    }

    @Test
    void preservesSharedCleanupEvidenceAcrossCandidateCopies() {
        double[] nativeIntensity = {0.1, 0.8, 0.2};
        CleanupSamplingProfile profile = new CleanupSamplingProfile(
            0, 0.0, true, 6.0,
            new ProjectedLateralTransform(new EastNorth(10.0, 20.0), 0.25, -0.5),
            new double[] {-1.0, 0.0, 1.0}, nativeIntensity,
            new double[] {0.2, 0.7, 0.3}, new double[] {0.3, 0.6, 0.4},
            new boolean[] {true, true, true});
        CleanupSamplingFrame frame = new CleanupSamplingFrame("hot", List.of(profile));
        CandidateCleanupEvidence cleanupEvidence = CandidateCleanupEvidence.complete(
            frame,
            List.of(new CandidateCleanupProfile(
                0, -1.0, 1.0, -2.0, 2.0,
                0.1, 0.5, CleanupEvidenceProvenance.DIRECT,
                0.8, 0.8, false)));
        CenterlineCandidate candidate = new CenterlineCandidate(
            "hot/ridge-1", 1.0, List.of(), List.of()).withCleanupEvidence(cleanupEvidence);

        nativeIntensity[1] = 0.0;
        CenterlineCandidate copy = candidate.withId("hot/ridge-copy")
            .withScore(2.0)
            .withEastNorthPoints(List.of(new EastNorth(10.0, 20.0)))
            .withSafetyWarnings(List.of("test"));

        assertSame(frame, copy.cleanupEvidence().samplingFrame());
        assertEquals(0.8, copy.cleanupEvidence().samplingFrame().profiles().get(0).nativeIntensityAt(1), 1e-9);
        assertEquals(new EastNorth(10.5, 19.0), profile.projectedPointAtOffset(2.0));
        assertTrue(copy.cleanupEvidence().eligible());
    }

    @Test
    void marksMisalignedCleanupEvidenceWithTypedSkipReason() {
        CleanupSamplingProfile profile = new CleanupSamplingProfile(
            0, 0.0, true, 6.0,
            new ProjectedLateralTransform(new EastNorth(0.0, 0.0), 1.0, 0.0),
            new double[] {0.0}, new double[] {1.0}, new double[] {1.0}, new double[] {1.0},
            new boolean[] {true});
        CleanupSamplingFrame frame = new CleanupSamplingFrame("hot", List.of(profile));

        CandidateCleanupEvidence evidence = CandidateCleanupEvidence.validated(frame, List.of());

        assertFalse(evidence.eligible());
        assertEquals(CleanupEvidenceStatus.MISALIGNED_CANDIDATE_ROWS, evidence.status());
    }

    @Test
    void preservesExplicitCleanupParentAndMetricsAcrossCandidateCopies() {
        CandidateGeometryCleanup report = new CandidateGeometryCleanup(
            "hot/ridge-1", CandidateGeometryCleanup.Outcome.CLEANED, "cleaned",
            List.of("smoothing-applied", "points-reduced"), 84, 84, 19,
            2, 1, 14, 9, 1, 0.92, 0.90, 0.35,
            OptionalDouble.of(1.8), OptionalDouble.of(0.91));
        CenterlineCandidate candidate = new CenterlineCandidate(
            "opaque-candidate-id", 1.0, List.of(), List.of()).withGeometryCleanup(report);

        CenterlineCandidate copy = candidate.withId("opaque-copy").withScore(2.0)
            .withFinalPreviewPoints(List.of(new EastNorth(0.0, 0.0), new EastNorth(1.0, 0.0)))
            .withSafetyWarnings(List.of("inspection only"));

        assertSame(report, copy.geometryCleanup());
        assertEquals("hot/ridge-1", copy.geometryCleanup().parentCandidateId());
        assertTrue(copy.displayName().contains("cleaned (84 -> 19 points)"));
        assertThrows(UnsupportedOperationException.class,
            () -> copy.geometryCleanup().reasons().add("mutable"));
    }

    @Test
    void labelsBothRawAndCleanedSiblingsExplicitly() {
        CandidateGeometryCleanup rawReport = new CandidateGeometryCleanup(
            "hot/ridge-1", CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE,
            "cleaned-sibling-created", List.of(), 8, 8, 8, 0, 0, 1, 0, 0,
            1.0, 1.0, 0.0, OptionalDouble.empty(), OptionalDouble.empty());
        CandidateGeometryCleanup cleanedReport = new CandidateGeometryCleanup(
            "hot/ridge-1", CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED,
            "cleanup-partially-applied", List.of(), 8, 8, 5, 1, 0, 2, 1, 0,
            1.0, 1.0, 0.0, OptionalDouble.empty(), OptionalDouble.empty());

        CenterlineCandidate raw = new CenterlineCandidate(
            "hot/ridge-1", 1.0, List.of(), List.of()).withGeometryCleanup(rawReport);
        CenterlineCandidate cleaned = raw.withId("hot/ridge-1#cleaned")
            .withGeometryCleanup(cleanedReport);

        assertTrue(raw.displayName().contains(" - raw"));
        assertTrue(cleaned.displayName().contains(" - cleaned (8 -> 5 points)"));
    }

    @Test
    void unsupportedCleanupRowsCannotAuthorizeMovement() {
        assertThrows(IllegalArgumentException.class, () -> new CandidateCleanupProfile(
            0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            0.0, 1.0, CleanupEvidenceProvenance.UNSUPPORTED,
            0.1, 0.0, false));
        assertThrows(IllegalArgumentException.class, () -> new CandidateCleanupProfile(
            0, -1.0, 1.0, -2.0, 2.0,
            0.0, 1.0, CleanupEvidenceProvenance.UNSUPPORTED,
            0.0, 0.0, false));
    }
}

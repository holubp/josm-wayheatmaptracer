package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Map;

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
}

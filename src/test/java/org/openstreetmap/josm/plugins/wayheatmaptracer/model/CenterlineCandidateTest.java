package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.List;

import org.junit.jupiter.api.Test;

class CenterlineCandidateTest {
    @Test
    void formatsDetectorCandidateForUsers() {
        CenterlineCandidate candidate = new CenterlineCandidate(
            "purple/ridge-4",
            -399.4,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(1, 1)),
            List.of(0.0, 1.0)
        );

        assertEquals("Purple detector - ridge 4 - very weak", candidate.displayName());
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
}

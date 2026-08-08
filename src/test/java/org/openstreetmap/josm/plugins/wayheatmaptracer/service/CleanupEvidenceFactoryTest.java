package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingFrame;

class CleanupEvidenceFactoryTest {
    @Test
    void combinedDetectorBudgetIncludesCandidateRows() {
        CleanupSamplingFrame frame = new CleanupSamplingFrame("hot", List.of());

        assertTrue(CleanupEvidenceFactory.withinRetentionBudget(frame, 20, 1_000));
        assertFalse(CleanupEvidenceFactory.withinRetentionBudget(frame, 2_000, 1_000));
        assertThrows(IllegalArgumentException.class,
            () -> CleanupEvidenceFactory.withinRetentionBudget(frame, -1, 1));
    }
}

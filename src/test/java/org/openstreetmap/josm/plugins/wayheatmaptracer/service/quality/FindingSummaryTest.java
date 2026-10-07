package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class FindingSummaryTest {
    @Test
    void repeatedFindingsAreCountedWithoutLosingDistinctReasons() {
        assertEquals("LOCAL_SHAPE_IMAGE_AMBIGUITY (REVIEW) × 3, SEARCH_TRUNCATED × 1, "
                        + "UNAVAILABLE_IMAGE_QUALITY (HARD_BLOCK) × 2",
                FindingSummary.summarize(List.of(
                        "LOCAL_SHAPE_IMAGE_AMBIGUITY (REVIEW)",
                        "SEARCH_TRUNCATED",
                        "LOCAL_SHAPE_IMAGE_AMBIGUITY (REVIEW)",
                        "UNAVAILABLE_IMAGE_QUALITY (HARD_BLOCK)",
                        "LOCAL_SHAPE_IMAGE_AMBIGUITY (REVIEW)",
                        "UNAVAILABLE_IMAGE_QUALITY (HARD_BLOCK)")));
    }
}

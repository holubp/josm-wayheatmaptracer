package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

class CorridorResourceFailureEvidenceTest {
    @Test void typedFactsAreImmutableAndLegacyMessageRemainsCompatible() {
        var facts = new EnumMap<CorridorResourceLimitException.Metric, Long>(CorridorResourceLimitException.Metric.class);
        facts.put(CorridorResourceLimitException.Metric.DECLARED_PAIRS, 12L);
        var failure = new CorridorResourceLimitException("legacy", CorridorResourceLimitException.Stage.ORIGINAL_ADMISSION,
            CorridorResourceLimitException.Reason.DECLARED_WORK_CAP, facts);
        facts.clear();
        assertEquals("legacy", failure.getMessage());
        assertEquals(12L, failure.metrics().get(CorridorResourceLimitException.Metric.DECLARED_PAIRS));
        assertThrows(UnsupportedOperationException.class, () -> failure.metrics().clear());
        assertEquals(CorridorResourceLimitException.Stage.UNAVAILABLE, new CorridorResourceLimitException("legacy").stage());
    }

    @Test void hardCacheFailureKeepsWorkAndSeparatelyRequestedBytes() {
        var work = new CorridorCenterlineOptimizer.HardGeometryWork(
            CorridorCenterlineOptimizer.HardGeometryOutcome.RESOURCE_LIMIT, 4, 3, 2, 1, 1, 5, 6, 0);
        var failure = new CorridorCenterlineOptimizer.HardGeometryResourceLimitException(work, 134217729L);
        assertEquals(work, failure.work());
        assertEquals(134217729L, failure.requestedCacheBytes().orElseThrow());
        assertTrue(new CorridorCenterlineOptimizer.HardGeometryResourceLimitException(work).requestedCacheBytes().isEmpty());
    }
}

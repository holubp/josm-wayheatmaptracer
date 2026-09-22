package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

class IncidentWayReconstructorTest {
    @Test
    void monotonicOccurrenceOrderAcceptsStrictlyOrderedPoints() {
        assertDoesNotThrow(() -> IncidentWayReconstructor.requireMonotonicOccurrenceOrder(List.of(
                new MetricPoint(0.0, 0.0),
                new MetricPoint(1.0, 0.2),
                new MetricPoint(2.0, -0.1),
                new MetricPoint(3.0, 0.0))));
    }

    @Test
    void monotonicOccurrenceOrderRejectsAnInvertedInteriorOccurrence() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> IncidentWayReconstructor.requireMonotonicOccurrenceOrder(List.of(
                        new MetricPoint(0.0, 0.0),
                        new MetricPoint(2.0, 0.0),
                        new MetricPoint(1.0, 0.0),
                        new MetricPoint(3.0, 0.0))));

        assertTrue(failure.getMessage().contains("incident occurrence order"),
                failure::getMessage);
    }
}

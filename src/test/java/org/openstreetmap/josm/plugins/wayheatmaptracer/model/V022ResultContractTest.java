package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Additional invariant tests that keep solver and authorization result states truthful. */
class V022ResultContractTest {
    @Test
    void completeInferenceRequiresAComputedRoute() {
        assertThrows(IllegalArgumentException.class, () -> new TraceHypothesisSet(
            TrackerMode.PROBABILISTIC, List.of(), TraceHypothesisSet.Status.COMPLETE,
            false, 1, 0, "complete"));
    }

    @Test
    void noRouteCannotRetainAHiddenApplicableHypothesis() {
        assertThrows(IllegalArgumentException.class, () -> new TraceHypothesisSet(
            TrackerMode.PROBABILISTIC, List.of(hypothesis()), TraceHypothesisSet.Status.NO_ROUTE,
            false, 1, 0, "none"));
    }

    @Test
    void alternativeTruncationIsCompatibleWithCompletedExactInference() {
        assertDoesNotThrow(() -> new TraceHypothesisSet(
            TrackerMode.PROBABILISTIC, List.of(hypothesis()), TraceHypothesisSet.Status.COMPLETE,
            true, 2, 1, "exact lattice; alternatives capped"));
    }

    @Test
    void hardBlockedValidationNeedsAnExplicitFinding() {
        assertThrows(IllegalArgumentException.class,
            () -> new ValidationReport(ValidationReport.Disposition.HARD_BLOCKED, List.of()));
    }

    @Test
    void metricRegionRejectsDegenerateAndSelfIntersectingPolygons() {
        assertThrows(IllegalArgumentException.class, () -> new MetricRegion(List.of(List.of(
            new MetricPoint(0, 0), new MetricPoint(1, 0), new MetricPoint(2, 0)))));
        assertThrows(IllegalArgumentException.class, () -> new MetricRegion(List.of(List.of(
            new MetricPoint(0, 0), new MetricPoint(2, 2), new MetricPoint(0, 2), new MetricPoint(2, 0)))));
    }

    private static TraceHypothesis hypothesis() {
        return new TraceHypothesis("route", "branch", List.of(new MetricPoint(0, 0), new MetricPoint(1, 0)),
            List.of(ObservationOwnership.DIRECT_TWO_SIDED, ObservationOwnership.DIRECT_TWO_SIDED),
            1.0, 1.0, Map.of());
    }
}

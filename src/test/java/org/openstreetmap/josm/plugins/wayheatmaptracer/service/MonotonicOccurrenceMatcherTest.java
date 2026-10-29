package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class MonotonicOccurrenceMatcherTest {
    @Test void reusesEveryAvailableOccurrenceBeforeMinimizingFractionError() {
        assertEquals(Map.of(0, 0, 1, 1), MonotonicOccurrenceMatcher.match(
                List.of(.1, .2), List.of(.19, .9), List.of(0, 0), List.of(0, 0), CancellationProbe.NONE));
    }

    @Test void choosesNearestMonotonicSlotsAndKeepsNativeTieOrder() {
        assertEquals(Map.of(1, 0, 3, 1), MonotonicOccurrenceMatcher.match(
                List.of(.25, .75), List.of(.1, .25, .5, .75, .9), List.of(0, 0),
                List.of(0, 0, 0, 0, 0), CancellationProbe.NONE));
        assertEquals(Map.of(0, 0), MonotonicOccurrenceMatcher.match(
                List.of(.5), List.of(.25, .75), List.of(0), List.of(0, 0), CancellationProbe.NONE));
    }

    @Test void neverMatchesAcrossProtectedAnchorIntervals() {
        assertEquals(Map.of(1, 0), MonotonicOccurrenceMatcher.match(
                List.of(.2), List.of(.2, .9), List.of(1), List.of(0, 1), CancellationProbe.NONE));
    }

    @Test void cancellationAndInvalidInputsFailBeforeMatching() {
        assertThrows(CancellationException.class, () -> MonotonicOccurrenceMatcher.match(
                List.of(.2), List.of(.2), List.of(0), List.of(0), () -> true));
        assertThrows(IllegalArgumentException.class, () -> MonotonicOccurrenceMatcher.match(
                List.of(Double.NaN), List.of(.2), List.of(0), List.of(0), CancellationProbe.NONE));
    }
}

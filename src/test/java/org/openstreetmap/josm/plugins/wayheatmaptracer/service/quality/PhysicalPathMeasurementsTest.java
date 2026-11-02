package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

class PhysicalPathMeasurementsTest {
    @Test
    void measuresTurnsAndPhysicalLength() {
        var rightTurn = PhysicalPathMeasurements.measure(List.of(p(0, 0), p(1, 0), p(1, 1)));
        assertEquals(List.of(90.0), rightTurn.vertexTurnsDegrees());
        assertEquals(2.0, rightTurn.lengthMeters());

        var straight = PhysicalPathMeasurements.measure(List.of(p(0, 0), p(1, 0), p(2, 0)));
        assertEquals(List.of(0.0), straight.vertexTurnsDegrees());

        var reversal = PhysicalPathMeasurements.measure(List.of(p(0, 0), p(1, 0), p(0, 0)));
        assertEquals(List.of(180.0), reversal.vertexTurnsDegrees());

        assertEquals(5.0, PhysicalPathMeasurements.measure(List.of(p(0, 0), p(3, 4))).lengthMeters());
    }

    @Test
    void zeroSegmentsAreCountedAndAdjacentTurnsOmitted() {
        var result = PhysicalPathMeasurements.measure(List.of(p(0, 0), p(0, 0), p(1, 0), p(1, 0), p(1, 1)));
        assertEquals(2, result.zeroLengthSegmentCount());
        assertEquals(2.0, result.lengthMeters());
        assertEquals(List.of(), result.vertexTurnsDegrees());
    }

    @Test
    void resultDefensivelyCopiesAndValidatesValues() {
        var turns = new ArrayList<>(List.of(0.0, 90.0));
        var result = PhysicalPathMeasurements.immutableResult(turns, 3.0, 1);
        turns.add(45.0);
        assertEquals(List.of(0.0, 90.0), result.vertexTurnsDegrees());
        assertThrows(UnsupportedOperationException.class, () -> result.vertexTurnsDegrees().add(1.0));
        assertThrows(IllegalArgumentException.class,
                () -> PhysicalPathMeasurements.immutableResult(List.of(Double.NaN), 0.0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PhysicalPathMeasurements.immutableResult(List.of(), -1.0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PhysicalPathMeasurements.immutableResult(List.of(), 0.0, -1));
    }

    @Test
    void rejectsNullAndShortPaths() {
        assertThrows(IllegalArgumentException.class, () -> PhysicalPathMeasurements.measure(null));
        assertThrows(IllegalArgumentException.class, () -> PhysicalPathMeasurements.measure(List.of(p(0, 0))));
        var points = new ArrayList<MetricPoint>();
        points.add(p(0, 0));
        points.add(null);
        assertThrows(IllegalArgumentException.class, () -> PhysicalPathMeasurements.measure(points));
        assertTrue(Double.isFinite(p(0, 0).xMeters()));
    }

    private static MetricPoint p(double x, double y) {
        return new MetricPoint(x, y);
    }
}

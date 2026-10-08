package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BenchmarkDecisionComparatorTest {
    @Test
    void scalarComparisonRejectsOneUlpAndSignedZero() {
        assertDoesNotThrow(() -> BenchmarkDecisionComparatorMain.requireRawValuesEqual(
                new double[] {1.0, -0.0}, new double[] {1.0, -0.0}, "hot"));
        assertThrows(IllegalStateException.class,
                () -> BenchmarkDecisionComparatorMain.requireRawValuesEqual(
                        new double[] {1.0}, new double[] {Math.nextUp(1.0)}, "hot"));
        assertThrows(IllegalStateException.class,
                () -> BenchmarkDecisionComparatorMain.requireRawValuesEqual(
                        new double[] {0.0}, new double[] {-0.0}, "hot"));
    }
}

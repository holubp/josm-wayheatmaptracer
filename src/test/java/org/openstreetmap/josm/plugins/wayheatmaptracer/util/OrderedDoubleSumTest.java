package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OrderedDoubleSumTest {
    @Test
    void positiveSmallTermsArePreservedBesideAnExactlyRepresentableLargeTerm() {
        assertEquals(0x1.0000000000001p53, sum(0x1.0p53, 1.0, 1.0));
        assertEquals(6.0, sum(1.0, 2.0, 3.0));
    }

    @Test
    void cancellationRetainsTheIndependentlyKnownTwoUnitResidual() {
        assertEquals(2.0, sum(1e16, 1.0, 1.0, -1e16));
        assertEquals(-2.0, sum(-1e16, -1.0, -1.0, 1e16));
    }

    @Test
    void overflowAndExplicitInfinitiesUseTheSimpleSumFallback() {
        assertEquals(Double.POSITIVE_INFINITY, sum(Double.MAX_VALUE, Double.MAX_VALUE));
        assertEquals(Double.NEGATIVE_INFINITY, sum(-Double.MAX_VALUE, -Double.MAX_VALUE));
        assertEquals(Double.POSITIVE_INFINITY, sum(Double.POSITIVE_INFINITY, 2.0));
        assertTrue(Double.isNaN(sum(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)));
        assertTrue(Double.isNaN(sum(Double.NaN, 1.0)));
        assertEquals(0L, Double.doubleToRawLongBits(sum()));
        assertEquals(0L, Double.doubleToRawLongBits(sum(-0.0)));
    }

    private static double sum(double... values) {
        OrderedDoubleSum sum = new OrderedDoubleSum();
        for (double value : values) sum.add(value);
        return sum.value();
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport.AngularMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport.Status;

class V022ImageOrientationMismatchTest {
    @Test
    void emptySupportKeepsPositiveZeroEvenForInvalidBearings() {
        var support = ImageOrientationSupport.unknown(Status.UNKNOWN_FLAT);
        for (double bearing : bearings()) {
            assertEquals(0L, Double.doubleToRawLongBits(support.mismatchSquared(bearing)));
            assertEquals(outcome(() -> frozenStream(support, bearing)),
                    outcome(() -> support.mismatchSquared(bearing)));
        }
    }

    @Test
    void everyRawBitAndFailureMatchesFrozenStreamInImmutableEncounterOrder() {
        AngularMode zero = mode(0, 0);
        AngularMode vertical = mode(Math.PI / 2, Math.PI / 2);
        AngularMode wrapped = mode(3 * Math.PI / 4, Math.PI / 4);
        AngularMode plateau = mode(Math.PI / 8, 3 * Math.PI / 8);
        for (List<AngularMode> modes : List.of(List.of(zero), List.of(vertical), List.of(wrapped),
                List.of(plateau), List.of(zero, vertical), List.of(vertical, zero),
                List.of(zero, zero, wrapped), List.of(wrapped, plateau, vertical),
                List.of(vertical, plateau, wrapped))) {
            var support = new ImageOrientationSupport(Status.MEASURED_TWO_SIDED, modes, 1.0);
            for (double bearing : bearings()) {
                assertEquals(outcome(() -> frozenStream(support, bearing)),
                        outcome(() -> support.mismatchSquared(bearing)));
            }
        }
    }

    @Test
    void independentPointPlateauAndWrappedAnalyticExpectationsKeepRawBits() {
        var horizontal = support(mode(0, 0));
        bits(0.0, horizontal.mismatchSquared(0.0));
        bits(0.0, horizontal.mismatchSquared(-0.0));
        bits(1.0, horizontal.mismatchSquared(Math.PI / 2));
        double quarterSine = StrictMath.sin(Math.PI / 4);
        bits(quarterSine * quarterSine, horizontal.mismatchSquared(Math.PI / 4));
        var plateau = support(mode(Math.PI / 8, 3 * Math.PI / 8));
        bits(0.0, plateau.mismatchSquared(Math.PI / 4));
        double eighthSine = StrictMath.sin(Math.PI / 8);
        bits(eighthSine * eighthSine, plateau.mismatchSquared(0));
        var wrapped = support(mode(3 * Math.PI / 4, Math.PI / 4));
        bits(0.0, wrapped.mismatchSquared(0));
        bits(quarterSine * quarterSine, wrapped.mismatchSquared(Math.PI / 2));
    }

    /** Exact old stream implementation, frozen before the production loop change. */
    private static double frozenStream(ImageOrientationSupport support, double bearingRadians) {
        if (support.modes().isEmpty()) {
            return 0.0;
        }
        double distance = support.modes().stream().mapToDouble(mode -> mode.distanceTo(bearingRadians))
            .min().orElse(0.0);
        double sine = StrictMath.sin(distance);
        return sine * sine;
    }

    private static double[] bearings() {
        return new double[] {0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MIN_NORMAL, -Double.MIN_NORMAL, Math.PI / 8, Math.PI / 4, Math.PI / 2,
                3 * Math.PI / 4, Math.nextDown(Math.PI / 4), Math.nextUp(Math.PI / 4),
                Math.nextDown(Math.PI), Math.PI, Math.nextUp(Math.PI), -Math.PI,
                -1000 * Math.PI, 1000 * Math.PI, Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
                Double.longBitsToDouble(0x7ff8000000000001L)};
    }

    private static Outcome outcome(DoubleSupplier action) {
        try {
            return new Outcome(Double.doubleToRawLongBits(action.getAsDouble()), null, null);
        } catch (IllegalArgumentException failure) {
            return new Outcome(null, failure.getClass().getName(), failure.getMessage());
        }
    }

    private static void bits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    private static ImageOrientationSupport support(AngularMode mode) {
        return new ImageOrientationSupport(Status.MEASURED_TWO_SIDED, List.of(mode), 1.0);
    }

    private static AngularMode mode(double start, double end) {
        return new AngularMode(start, end, start, 1.0);
    }

    private record Outcome(Long rawBits, String failureType, String message) { }
}

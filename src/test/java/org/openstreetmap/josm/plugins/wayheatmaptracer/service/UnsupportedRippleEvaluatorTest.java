package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.Test;

class UnsupportedRippleEvaluatorTest {
    @Test
    void detectsPhysicalShortReversalsButNotCoherentOrSingleApexMotion() {
        UnsupportedRippleEvaluator evaluator = new UnsupportedRippleEvaluator();

        var alternating = evaluator.evaluate(tube(index -> (index & 1) == 0 ? -3.0 : 3.0, 0.0),
            1.0, 10.0, true);
        var coherent = evaluator.evaluate(tube(index -> index * 0.4, 0.8), 1.0, 10.0, true);
        var apex = evaluator.evaluate(tube(index -> index <= 10 ? index : 20 - index, 0.8),
            1.0, 10.0, true);

        assertTrue(alternating.get(10).unsupportedWeight() > 0.5);
        assertEquals(2.0, alternating.get(10).reversalSpacingMeters(), 1e-9);
        assertEquals(0.0, coherent.get(10).unsupportedWeight(), 1e-9);
        assertEquals(0.0, apex.get(10).unsupportedWeight(), 1e-9);
    }

    @Test
    void disabledEvaluationIsExactlyNeutral() {
        var decisions = new UnsupportedRippleEvaluator().evaluate(
            tube(index -> (index & 1) == 0 ? -3.0 : 3.0, 0.0), 1.0, 20.0, false);

        assertTrue(decisions.stream().allMatch(value -> value.unsupportedWeight() == 0.0
            && value.reason().equals("disabled")));
    }

    @Test
    void supportedPhysicalReversalsAtSixTenAndTwentyMetersRemainNeutral() {
        UnsupportedRippleEvaluator evaluator = new UnsupportedRippleEvaluator();
        for (int spacingMeters : List.of(6, 10, 20)) {
            int profilesPerLeg = Math.max(2, spacingMeters / 2);
            LongitudinalCorridorTube supported = tube(
                index -> triangle(index, profilesPerLeg), 1.0);
            var decisions = evaluator.evaluate(supported, 1.0, 20.0, true);

            assertTrue(decisions.stream().allMatch(value -> value.unsupportedWeight() == 0.0),
                "supported spacing=" + spacingMeters + " decisions=" + decisions);
        }
    }

    @Test
    void residualAmplitudeSeparatesSubPixelNoiseFromVisibleRipple() {
        UnsupportedRippleEvaluator evaluator = new UnsupportedRippleEvaluator();
        var tiny = evaluator.evaluate(tube(index -> (index & 1) == 0 ? -0.08 : 0.08, 0.0),
            1.0, 10.0, true);
        var visible = evaluator.evaluate(tube(index -> (index & 1) == 0 ? -0.50 : 0.50, 0.0),
            1.0, 10.0, true);
        assertTrue(tiny.get(10).unsupportedWeight() <= 0.10);
        assertTrue(visible.get(10).unsupportedWeight() >= 0.40);
    }

    @Test
    void shortDenseWindowRequiresMinimumPhysicalSpan() {
        var decisions = new UnsupportedRippleEvaluator().evaluate(tube(
            index -> (index & 1) == 0 ? -3.0 : 3.0, 0.0, index -> index * 0.1, false),
            1.0, 10.0, true);
        assertEquals(0.0, decisions.get(10).unsupportedWeight(), 1e-9);
        assertEquals("insufficient-physical-span", decisions.get(10).reason());
    }

    @Test
    void robustTrendAndSourcePixelNormalizationAreStable() {
        UnsupportedRippleEvaluator evaluator = new UnsupportedRippleEvaluator();
        var slope = evaluator.evaluate(tube(
            index -> index * 0.4 + ((index & 1) == 0 ? -0.5 : 0.5), 0.0), 1.0, 10.0, true);
        var doubled = evaluator.evaluate(tube(
            index -> index * 0.8 + ((index & 1) == 0 ? -1.0 : 1.0), 0.0,
            index -> index * 2.0, false), 2.0, 10.0, true);
        assertEquals(0.2, slope.get(10).trendSlopePxPerMeter(), 0.03);
        assertEquals(slope.get(10).residualAmplitudeSourcePixels(),
            doubled.get(10).residualAmplitudeSourcePixels(), 1e-9);
        assertEquals(slope.get(10).unsupportedWeight(), doubled.get(10).unsupportedWeight(), 1e-9);
    }

    @Test
    void scaleConflictKeepsAttributionButRevokesAuthorization() {
        var conflict = new UnsupportedRippleEvaluator().evaluate(tube(
            index -> (index & 1) == 0 ? -0.5 : 0.5, 0.0, index -> index * 2.0, true),
            1.0, 10.0, true);
        assertTrue(conflict.get(10).unsupportedWeight() > 0.0);
        assertEquals(0.0, conflict.get(10).trendAuthorization(), 1e-9);
        assertEquals("trend-unauthorized-scale-conflict", conflict.get(10).reason());
    }

    @Test
    void boundaryCensoredWindowsReportCoverageAndCannotAuthorizeIntervention() {
        var decisions = new UnsupportedRippleEvaluator().evaluate(
            tube(index -> (index & 1) == 0 ? -0.5 : 0.5, 0.0), 1.0, 10.0, true);

        assertEquals("boundary-censored-window", decisions.get(0).reason());
        assertEquals(0.5, decisions.get(0).directCoverage(), 1e-9);
        assertEquals(0.0, decisions.get(0).trendAuthorization(), 1e-9);
        assertEquals(0.0, decisions.get(0).unsupportedWeight(), 1e-9);
        assertTrue(decisions.get(10).directCoverage() > decisions.get(0).directCoverage());
    }

    private double triangle(int index, int profilesPerLeg) {
        int period = profilesPerLeg * 2;
        int phase = index % period;
        return phase <= profilesPerLeg ? phase : period - phase;
    }

    private LongitudinalCorridorTube tube(IntToDoubleFunction center, double motionSupport) {
        return tube(center, motionSupport, index -> index * 2.0, false);
    }

    private LongitudinalCorridorTube tube(IntToDoubleFunction center, double motionSupport,
        IntToDoubleFunction distance, boolean scaleConflict) {
        List<CorridorTubeSlice> slices = new ArrayList<>();
        for (int index = 0; index <= 20; index++) {
            double value = center.applyAsDouble(index);
            slices.add(new CorridorTubeSlice(
                index, distance.applyAsDouble(index), value, 0.0,
                value, 0.0, value, 0.0,
                1.0, motionSupport, motionSupport > 0.0 ? "coherent-direction" : "reversing-noise",
                0.0, value - 1.0, value + 1.0, value - 2.0, value + 2.0,
                1.0, 1.0, scaleConflict, false, value, value, value, true));
        }
        return new LongitudinalCorridorTube(slices);
    }
}

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

    private double triangle(int index, int profilesPerLeg) {
        int period = profilesPerLeg * 2;
        int phase = index % period;
        return phase <= profilesPerLeg ? phase : period - phase;
    }

    private LongitudinalCorridorTube tube(IntToDoubleFunction center, double motionSupport) {
        List<CorridorTubeSlice> slices = new ArrayList<>();
        for (int index = 0; index <= 20; index++) {
            double value = center.applyAsDouble(index);
            slices.add(new CorridorTubeSlice(
                index, index * 2.0, value, 0.0,
                value, 0.0, value, 0.0,
                1.0, motionSupport, motionSupport > 0.0 ? "coherent-direction" : "reversing-noise",
                0.0, value - 1.0, value + 1.0, value - 2.0, value + 2.0,
                1.0, 1.0, false, false, value, value, value, true));
        }
        return new LongitudinalCorridorTube(slices);
    }
}

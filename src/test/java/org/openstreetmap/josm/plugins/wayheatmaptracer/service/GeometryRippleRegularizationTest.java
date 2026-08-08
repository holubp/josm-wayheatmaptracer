package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;

class GeometryRippleRegularizationTest {
    @Test
    void disabledConfigurationPreservesExactRawOptimizerOutput() {
        GeometryCleanupAcceptanceFixtures.Fixture fixture = fixture("ripple-3m-to-6m");
        CorridorAwareTracker tracker = new CorridorAwareTracker();

        CorridorAwareTracker.TrackingResult legacyCall = tracker.trackDetailed(fixture.profiles(), 1.0);
        CorridorAwareTracker.TrackingResult explicitDisabled = tracker.trackDetailed(
            fixture.profiles(), 1.0, JunctionContext.empty(), "hot", GeometryCleanupConfig.disabled());

        assertEquals(legacyCall.candidates().stream().map(CenterlineCandidate::offsetsPx).toList(),
            explicitDisabled.candidates().stream().map(CenterlineCandidate::offsetsPx).toList());
        assertEquals(legacyCall.optimizations().values().stream().map(value -> value.totalCost()).toList(),
            explicitDisabled.optimizations().values().stream().map(value -> value.totalCost()).toList());
        assertEquals(legacyCall.optimizations().values().stream().map(value -> value.transitionEvaluations()).toList(),
            explicitDisabled.optimizations().values().stream().map(value -> value.transitionEvaluations()).toList());
    }

    @Test
    void presetsMonotonicallySuppressUnsupportedShortRipple() {
        GeometryCleanupAcceptanceFixtures.Fixture fixture = fixture("ripple-3m-to-6m");
        List<Double> ripple = List.of(
            highFrequencyRms(best(fixture, GeometryCleanupPreset.CONSERVATIVE)),
            highFrequencyRms(best(fixture, GeometryCleanupPreset.BALANCED)),
            highFrequencyRms(best(fixture, GeometryCleanupPreset.STRONG)));

        assertTrue(ripple.get(1) <= ripple.get(0) + 1e-9, "preset ripple RMS=" + ripple);
        assertTrue(ripple.get(2) <= ripple.get(1) + 1e-9, "preset ripple RMS=" + ripple);
        assertTrue(ripple.get(2) <= ripple.get(0) * 0.60,
            "Strong suppression did not reach the 40% calibration gate: " + ripple);
    }

    @Test
    void strongRegularizationPreservesSupportedSwitchbackAmplitude() {
        for (String name : List.of("bend-6m", "bend-10m", "curve-20m", "sine", "switchback")) {
            GeometryCleanupAcceptanceFixtures.Fixture fixture = fixture(name);
            CenterlineCandidate baseline = new CorridorAwareTracker().trackDetailed(fixture.profiles(), 1.0)
                .candidates().get(0);
            CenterlineCandidate strong = best(fixture, GeometryCleanupPreset.STRONG);

            assertTrue(amplitude(strong.offsetsPx()) >= 0.90 * amplitude(baseline.offsetsPx()),
                name + ": baseline=" + amplitude(baseline.offsetsPx())
                    + ", strong=" + amplitude(strong.offsetsPx()));
        }
    }

    @Test
    void enabledRegularizationPreservesExactStateSpaceAndPhysicalScaleBehavior() {
        GeometryCleanupAcceptanceFixtures.Fixture rippleFixture = fixture("ripple-3m-to-6m");
        CorridorAwareTracker.TrackingResult baseline = new CorridorAwareTracker()
            .trackDetailed(rippleFixture.profiles(), 1.0);
        GeometryCleanupConfig strongConfig = GeometryCleanupPreset.STRONG
            .apply(GeometryCleanupMode.REDUCE_POINTS_ONLY);
        CorridorAwareTracker.TrackingResult strong = new CorridorAwareTracker().trackDetailed(
            rippleFixture.profiles(), 1.0, JunctionContext.empty(), "hot", strongConfig);
        for (String trackId : baseline.optimizations().keySet()) {
            var before = baseline.optimizations().get(trackId);
            var after = strong.optimizations().get(trackId);
            assertEquals(before.maximumOffsetStates(), after.maximumOffsetStates(), trackId);
            assertEquals(before.maximumPairStates(), after.maximumPairStates(), trackId);
            assertEquals(before.transitionEvaluations(), after.transitionEvaluations(), trackId);
            assertEquals(before.profileCostEvaluations(), after.profileCostEvaluations(), trackId);
        }

        List<Double> physicalAmplitudes = List.of(
            physicalAmplitude(best(fixture("z13-coarse-step"), GeometryCleanupPreset.STRONG), 0.50),
            physicalAmplitude(best(fixture("z15-reference-step"), GeometryCleanupPreset.STRONG), 1.0),
            physicalAmplitude(best(fixture("z16-fine-step"), GeometryCleanupPreset.STRONG), 2.0));
        assertTrue(physicalAmplitudes.stream().allMatch(value -> value >= 9.0),
            "physical amplitudes=" + physicalAmplitudes);
        double range = physicalAmplitudes.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
            - physicalAmplitudes.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        assertTrue(range <= 1.5, "physical amplitude range=" + range + ", values=" + physicalAmplitudes);
    }

    private CenterlineCandidate best(
        GeometryCleanupAcceptanceFixtures.Fixture fixture,
        GeometryCleanupPreset preset
    ) {
        GeometryCleanupConfig config = preset.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY);
        return new CorridorAwareTracker().trackDetailed(
            fixture.profiles(), 1.0, JunctionContext.empty(), "hot", config).candidates().get(0);
    }

    private GeometryCleanupAcceptanceFixtures.Fixture fixture(String name) {
        return GeometryCleanupAcceptanceFixtures.all().stream()
            .filter(value -> value.name().equals(name)).findFirst().orElseThrow();
    }

    private double highFrequencyRms(CenterlineCandidate candidate) {
        if (candidate.offsetsPx().size() < 3) {
            return 0.0;
        }
        double sum = 0.0;
        for (int index = 2; index < candidate.offsetsPx().size(); index++) {
            double acceleration = candidate.offsetsPx().get(index)
                - 2.0 * candidate.offsetsPx().get(index - 1)
                + candidate.offsetsPx().get(index - 2);
            sum += acceleration * acceleration;
        }
        return Math.sqrt(sum / (candidate.offsetsPx().size() - 2));
    }

    private double amplitude(List<Double> offsets) {
        return (offsets.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
            - offsets.stream().mapToDouble(Double::doubleValue).min().orElseThrow()) / 2.0;
    }

    private double physicalAmplitude(CenterlineCandidate candidate, double metresPerRasterPixel) {
        return amplitude(candidate.offsetsPx()) * metresPerRasterPixel;
    }
}

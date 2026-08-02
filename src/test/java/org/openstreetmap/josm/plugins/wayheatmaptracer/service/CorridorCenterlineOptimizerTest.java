package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

class CorridorCenterlineOptimizerTest {
    @Test
    void staysAtRobustCenterWhenNativeBrightestPixelAlternatesAcrossPlateau() {
        Scenario scenario = scenario(index -> 0.0, true);

        CorridorCenterlineOptimizer.OptimizationResult result = new CorridorCenterlineOptimizer()
            .optimize(scenario.track(), scenario.profiles(), 1.0);

        double rms = Math.sqrt(result.offsetsPx().stream().mapToDouble(value -> value * value).average().orElseThrow());
        assertTrue(rms <= 0.5, "RMS center error was " + rms);
        assertEquals(1.0, result.inCorridorFraction(), 1e-9);
    }

    @Test
    void retainsSustainedSineAmplitude() {
        Scenario scenario = scenario(index -> 5.0 * Math.sin(index * Math.PI / 10.0), false);

        CorridorCenterlineOptimizer.OptimizationResult result = new CorridorCenterlineOptimizer()
            .optimize(scenario.track(), scenario.profiles(), 1.0);

        double amplitude = (result.offsetsPx().stream().mapToDouble(Double::doubleValue).max().orElseThrow()
            - result.offsetsPx().stream().mapToDouble(Double::doubleValue).min().orElseThrow()) / 2.0;
        assertTrue(amplitude >= 4.5, "Retained amplitude was " + amplitude);
    }

    @Test
    void fixesEndpointsExactlyAndBuildsAProgressiveApproach() {
        Scenario scenario = scenario(index -> 6.0, false);
        JunctionContext constraints = new JunctionContext(List.of(
            new EndpointConstraint(0, 1L, true, true, 0.0, 0.0, 6),
            new EndpointConstraint(29, 2L, true, true, 0.0, 0.0, 6)
        ));

        CorridorCenterlineOptimizer.OptimizationResult result = new CorridorCenterlineOptimizer()
            .optimize(scenario.track(), scenario.profiles(), 1.0, constraints);

        assertEquals(0.0, result.offsetsPx().get(0), 1e-9);
        assertEquals(0.0, result.offsetsPx().get(result.offsetsPx().size() - 1), 1e-9);
        assertTrue(result.offsetsPx().get(1) <= 3.0, "First approach offset was " + result.offsetsPx().get(1));
        assertTrue(result.offsetsPx().get(28) <= 3.0, "Last approach offset was " + result.offsetsPx().get(28));
    }

    @Test
    void movableJunctionCannotLeaveItsOriginalPositionByMoreThanConfiguredMaximum() {
        Scenario scenario = scenario(index -> 4.0, false);
        JunctionContext constraints = new JunctionContext(List.of(
            new EndpointConstraint(0, 1L, false, true, 3.0, 1.25, 6)
        ));

        CorridorCenterlineOptimizer.OptimizationResult result = new CorridorCenterlineOptimizer()
            .optimize(scenario.track(), scenario.profiles(), 1.0, constraints);

        assertTrue(result.offsetsPx().get(0) >= 0.0);
        assertTrue(result.offsetsPx().get(0) <= 3.0);
    }

    @Test
    void movableJunctionWithoutLocalBandStillHonorsConfiguredMaximum() {
        Scenario scenario = scenario(index -> 8.0, false);
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>(scenario.track().points());
        points.remove(0);
        CorridorTrack track = new CorridorTrack("track", points, 29.0, 29.0 / 30.0,
            false, List.of(), "");
        JunctionContext constraints = new JunctionContext(List.of(
            new EndpointConstraint(0, 1L, false, true, 3.0, 1.25, 6)
        ));

        CorridorCenterlineOptimizer.OptimizationResult result = new CorridorCenterlineOptimizer()
            .optimize(track, scenario.profiles(), 1.0, constraints);

        assertTrue(Math.abs(result.offsetsPx().get(0)) <= 3.0);
    }

    @Test
    void diagnosticRowTotalsMatchTheWeightedObjectiveTerms() {
        Scenario scenario = scenario(index -> 1.5, false);

        CorridorCenterlineOptimizer.OptimizationResult result = new CorridorCenterlineOptimizer()
            .optimize(scenario.track(), scenario.profiles(), 1.0);

        for (CorridorCenterlineOptimizer.CostRow row : result.costs()) {
            assertEquals(row.dataCost() + row.continuityCost() + row.accelerationCost() + row.endpointCost(),
                row.weightedTotal(), 1e-12);
        }
        assertEquals(result.totalCost(), result.costs().stream()
            .mapToDouble(CorridorCenterlineOptimizer.CostRow::weightedTotal).sum(), 1e-9);
    }

    private Scenario scenario(java.util.function.IntToDoubleFunction centerFunction, boolean alternatingNative) {
        List<CorridorProfile> profiles = new ArrayList<>();
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            double center = centerFunction.applyAsDouble(i);
            CorridorBand band = new CorridorBand("band", center, center - 4.0, center + 4.0,
                center - 2.0, center + 2.0, List.of(center, center), 1.0, 0.0, 1.0,
                0.95, 0.85, 0.5, false, List.of());
            List<IntensitySample> samples = new ArrayList<>();
            for (int offset = (int) Math.floor(center - 5.0); offset <= Math.ceil(center + 5.0); offset++) {
                double distance = Math.abs(offset - center);
                double filtered = distance <= 2.0 ? 0.95 : (distance <= 4.0 ? 0.55 : 0.05);
                double nativeValue = filtered;
                if (alternatingNative && distance <= 2.0) {
                    double preferred = center + (i % 2 == 0 ? -1.0 : 1.0);
                    nativeValue = Math.abs(offset - preferred) < 0.2 ? 1.0 : 0.88;
                }
                samples.add(new IntensitySample(offset, nativeValue, filtered, filtered, true));
            }
            RenderedHeatmapSampler.CrossSectionProfile source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(i * 5.0, 0.0), i * 5.0, 0.0, i * 5.0),
                new Point2D.Double(0.0, 1.0), List.of(), true, samples);
            profiles.add(new CorridorProfile(i, source, List.of(band), 1.0, 0.0, 1.0, true));
            points.put(i, new CorridorTrackPoint(i, band, false));
        }
        return new Scenario(new CorridorTrack("track", points, 30.0, 1.0, false, List.of(), ""), profiles);
    }

    private record Scenario(CorridorTrack track, List<CorridorProfile> profiles) {
    }
}

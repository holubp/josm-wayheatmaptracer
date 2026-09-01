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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

class CorridorCenterlineOptimizerTest {
    @Test
    void preservesExactOptimizerBaselineWhileBoundingInvariantWork() {
        CorridorCenterlineOptimizer optimizer = new CorridorCenterlineOptimizer();
        Scenario plateauScenario = scenario(index -> 0.0, true);
        CorridorCenterlineOptimizer.OptimizationResult plateau = optimizer.optimize(
            plateauScenario.track(), plateauScenario.profiles(), 1.0);
        Scenario sineScenario = scenario(index -> 5.0 * Math.sin(index * Math.PI / 10.0), false);
        CorridorCenterlineOptimizer.OptimizationResult sine = optimizer.optimize(
            sineScenario.track(), sineScenario.profiles(), 1.0);

        assertEquals(java.util.Collections.nCopies(30, 0.0), plateau.offsetsPx());
        assertEquals(0.0, plateau.totalCost());
        assertEquals(9, plateau.maximumOffsetStates());
        assertEquals(81, plateau.maximumPairStates());
        assertEquals(20_493L, plateau.transitionEvaluations());
        assertEquals(7_934_189_944_734_589_313L, costChecksum(plateau.costs()));

        assertEquals(List.of(
            0.2547627247472146, 1.455819241042368, 2.6568757573375215, 4.0,
            4.755282581475767, 5.0, 4.755282581475768, 4.0, 2.4502301537276283,
            1.396802246667421, 0.0, -1.3968022466674197, -2.450230153727627, -4.0,
            -4.755282581475767, -5.0, -4.755282581475768, -4.0, -2.4502301537276288,
            -1.3968022466674217, -1.839136020201776E-15, 1.3968022466674188,
            2.450230153727627, 4.0, 4.755282581475767, 5.0, 4.755282581475768, 4.0,
            2.938926261462367, 1.545084971874739), sine.offsetsPx());
        assertEquals(1.1246728433656754, sine.totalCost());
        assertEquals(17, sine.maximumOffsetStates());
        assertEquals(272, sine.maximumPairStates());
        assertEquals(91_633L, sine.transitionEvaluations());
        assertEquals(-8_737_784_222_494_701_449L, costChecksum(sine.costs()));

        assertTrue(plateau.profileCostEvaluations() <= 30L * plateau.maximumOffsetStates());
        assertTrue(sine.profileCostEvaluations() <= 30L * sine.maximumOffsetStates());
        assertEquals(plateau.profileCostEvaluations(), plateau.pointTableEntries());
        assertEquals(sine.profileCostEvaluations(), sine.pointTableEntries());
        assertTrue(plateau.retainedPairStateAllocations() <= plateau.transitionEvaluations());
        assertTrue(sine.retainedPairStateAllocations() <= sine.transitionEvaluations());
    }

    private long costChecksum(List<CorridorCenterlineOptimizer.CostRow> costs) {
        long checksum = 1L;
        for (CorridorCenterlineOptimizer.CostRow row : costs) {
            checksum = 31L * checksum + Double.doubleToLongBits(row.chosenOffsetPx());
            checksum = 31L * checksum + Double.doubleToLongBits(row.dataCost());
            checksum = 31L * checksum + Double.doubleToLongBits(row.continuityCost());
            checksum = 31L * checksum + Double.doubleToLongBits(row.accelerationCost());
            checksum = 31L * checksum + Double.doubleToLongBits(row.endpointCost());
            checksum = 31L * checksum + Double.doubleToLongBits(row.weightedTotal());
        }
        return checksum;
    }

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
    void rejectsShortRunStrandDriftInsideOneBroadCorridor() {
        Scenario scenario = scenario(index -> switch ((index / 3) % 4) {
            case 0, 3 -> -1.0;
            default -> 1.0;
        }, false, 1.5);

        CorridorCenterlineOptimizer.OptimizationResult result = new CorridorCenterlineOptimizer()
            .optimize(scenario.track(), scenario.profiles(), 1.0);

        double highFrequencyRms = secondDifferenceRms(result.offsetsPx());
        double meanAbsoluteOffset = result.offsetsPx().stream().mapToDouble(Math::abs).average().orElseThrow();
        assertTrue(highFrequencyRms <= 0.35,
            "Short-run strand drift should not become centerline ripple; RMS was " + highFrequencyRms
                + " for " + result.offsetsPx());
        assertTrue(meanAbsoluteOffset <= 0.55,
            "A shared broad corridor should remain centered; mean absolute offset was " + meanAbsoluteOffset
                + " for " + result.offsetsPx());
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
            assertEquals(row.dataCost() + row.continuityCost() + row.accelerationCost()
                    + row.absoluteShortWaveTurnCost() + row.endpointCost(),
                row.weightedTotal(), 1e-12);
        }
        assertEquals(result.totalCost(), result.costs().stream()
            .mapToDouble(CorridorCenterlineOptimizer.CostRow::weightedTotal).sum(), 1e-9);
    }

    @Test
    void enabledRippleDiagnosticsReconcileWithTheExactObjective() {
        Scenario scenario = scenario(index -> (index & 1) == 0 ? -5.0 : 5.0, false, 2.0);
        LongitudinalCorridorTube tube = new CorridorTubeBuilder().build(
            scenario.track(), scenario.profiles(), 1.0, Map.of());
        var cleanup = GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY);

        var result = new CorridorCenterlineOptimizer().optimize(scenario.track(), scenario.profiles(), 1.0,
            JunctionContext.empty(), Map.of(), tube, cleanup);

        assertTrue(result.costs().stream().anyMatch(row -> row.absoluteShortWaveTurnCost() > 0.0));
        assertTrue(result.costs().stream().anyMatch(row -> row.rippleAdditionalCost() > 0.0));
        for (var row : result.costs()) {
            assertEquals(row.dataCost() + row.continuityCost() + row.accelerationCost()
                    + row.absoluteShortWaveTurnCost() + row.endpointCost(),
                row.weightedTotal(), 1e-12);
        }
        assertEquals(result.totalCost(), result.costs().stream()
            .mapToDouble(CorridorCenterlineOptimizer.CostRow::weightedTotal).sum(), 1e-9);
    }

    @Test
    void completeBroadCoreUsesTwoSidedChannelCenterInsteadOfBiasedStabilityTube() {
        List<CorridorProfile> profiles = new ArrayList<>();
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        for (int index = 0; index < 30; index++) {
            CorridorBand band = new CorridorBand("band", 1.0, -4.0, 4.0, -2.0, 2.0,
                List.of(0.0, 0.0, 0.0), 1.0, 0.0, 1.0,
                0.9, 0.9, 1.0, 0.95, 0.95, 0.5, false, List.of());
            List<IntensitySample> samples = new ArrayList<>();
            for (int offset = -5; offset <= 5; offset++) {
                double intensity = Math.abs(offset) <= 2 ? 1.0 : Math.abs(offset) <= 4 ? 0.55 : 0.0;
                samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
            }
            RenderedHeatmapSampler.CrossSectionProfile source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(index * 2.0, 0.0), index * 2.0, 0.0, index * 2.0),
                new Point2D.Double(0.0, 1.0), List.of(), true, samples);
            profiles.add(new CorridorProfile(index, source, List.of(band), 1.0, 0.0, 1.0, true));
            points.put(index, new CorridorTrackPoint(index, band, false));
        }
        CorridorTrack track = new CorridorTrack("track", points, 30.0, 1.0, false, List.of(), "");
        LongitudinalCorridorTube tube = new CorridorTubeBuilder().build(
            track, profiles, 1.0, Map.of());

        var result = new CorridorCenterlineOptimizer().optimize(
            track, profiles, 1.0, JunctionContext.empty(), Map.of(),
            tube, GeometryCleanupConfig.disabled());

        double meanAbsoluteCenterError = result.offsetsPx().subList(3, 27).stream()
            .mapToDouble(Math::abs).average().orElseThrow();
        assertTrue(meanAbsoluteCenterError <= 0.25,
            "complete broad-core center error=" + meanAbsoluteCenterError + " offsets=" + result.offsetsPx());
    }

    @Test
    void endpointOwnedRowsReceiveNoCleanupOnlyCost() {
        Scenario scenario = scenario(index -> (index & 1) == 0 ? -2.0 : 2.0, false, 2.0);
        LongitudinalCorridorTube tube = new CorridorTubeBuilder().build(
            scenario.track(), scenario.profiles(), 1.0, Map.of());
        var cleanup = GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY);
        JunctionContext constraints = new JunctionContext(List.of(
            new EndpointConstraint(0, 1L, true, true, 0.0, 0.0, 6),
            new EndpointConstraint(29, 2L, true, true, 0.0, 0.0, 6)
        ));

        var result = new CorridorCenterlineOptimizer().optimize(scenario.track(), scenario.profiles(), 1.0,
            constraints, Map.of(), tube, cleanup);

        assertEquals(0.0, result.costs().get(0).rippleAdditionalCost(), 1e-12);
        assertEquals(0.0, result.costs().get(29).rippleAdditionalCost(), 1e-12);
        assertEquals(0.0, result.offsetsPx().get(0), 1e-12);
        assertEquals(0.0, result.offsetsPx().get(29), 1e-12);
    }

    @Test
    void absoluteTurnCostReducesRippleWithoutChangingExactStateSpace() {
        var cleanup = GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY);
        CorridorCenterlineOptimizer withoutAbsolute = new CorridorCenterlineOptimizer(
            CorridorOptimizationParameters.defaults(), new RippleRegularizationParameters(0.0, 0.02, 0.05, 1.0));
        List<Double> beforeRms = new ArrayList<>();
        List<Double> afterRms = new ArrayList<>();
        for (double amplitude : List.of(0.5, 1.0, 1.5, 2.0, 2.5, 3.0)) {
            Scenario scenario = scenario(index -> (index & 1) == 0 ? -amplitude : amplitude, false, 2.0);
            LongitudinalCorridorTube tube = new CorridorTubeBuilder().build(
                scenario.track(), scenario.profiles(), 1.0, Map.of());
            var before = withoutAbsolute.optimize(scenario.track(), scenario.profiles(), 1.0,
                JunctionContext.empty(), Map.of(), tube, cleanup);
            var after = new CorridorCenterlineOptimizer().optimize(scenario.track(), scenario.profiles(), 1.0,
                JunctionContext.empty(), Map.of(), tube, cleanup);
            beforeRms.add(secondDifferenceRms(before.offsetsPx()));
            afterRms.add(secondDifferenceRms(after.offsetsPx()));
            assertEquals(before.maximumOffsetStates(), after.maximumOffsetStates());
            assertEquals(before.maximumPairStates(), after.maximumPairStates());
            assertEquals(before.transitionEvaluations(), after.transitionEvaluations());
            assertEquals(before.profileCostEvaluations(), after.profileCostEvaluations());
        }
        assertTrue(afterRms.stream().mapToDouble(Double::doubleValue).sum()
                < beforeRms.stream().mapToDouble(Double::doubleValue).sum(),
            "absolute before=" + beforeRms + ", after=" + afterRms);
    }

    private double secondDifferenceRms(List<Double> values) {
        double squared = 0.0;
        for (int index = 1; index + 1 < values.size(); index++) {
            double residual = values.get(index)
                - (values.get(index - 1) + values.get(index + 1)) / 2.0;
            squared += residual * residual;
        }
        return values.size() < 3 ? 0.0 : Math.sqrt(squared / (values.size() - 2));
    }

    private Scenario scenario(java.util.function.IntToDoubleFunction centerFunction, boolean alternatingNative) {
        return scenario(centerFunction, alternatingNative, 5.0);
    }

    private Scenario scenario(
        java.util.function.IntToDoubleFunction centerFunction,
        boolean alternatingNative,
        double profileSpacing
    ) {
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
                new ProfileSamplingAnchor(new EastNorth(i * profileSpacing, 0.0),
                    i * profileSpacing, 0.0, i * profileSpacing),
                new Point2D.Double(0.0, 1.0), List.of(), true, samples);
            profiles.add(new CorridorProfile(i, source, List.of(band), 1.0, 0.0, 1.0, true));
            points.put(i, new CorridorTrackPoint(i, band, false));
        }
        return new Scenario(new CorridorTrack("track", points, 30.0, 1.0, false, List.of(), ""), profiles);
    }

    private record Scenario(CorridorTrack track, List<CorridorProfile> profiles) {
    }
}

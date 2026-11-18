package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorCenterlineOptimizer.OriginalDomain;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

/** Bound public analytical regression using production R5 restriction and the exact native objective. */
class CorridorObservedShoulderDomainTest {
    @Test
    void preservesMatchedFaintConcentratedBendAndSwitchbackWithNativeTube() throws Exception {
        for (String shape : List.of("concentrated-bend", "switchback")) {
            double[] centers = new double[41];
            for (int index = 0; index < centers.length; index++) {
                centers[index] = shape.equals("concentrated-bend")
                    ? 4 * Math.exp(-.5 * Math.pow((index - 20) / 3.0, 2))
                    : 4 * Math.sin(index * Math.PI / 10.0);
            }
            Fixture samples = fixture(centers, centers, centers, index -> 1.0);
            var tube = new CorridorTubeBuilder(CorridorTubeBuilder.MotionPolicy.TARGET_OWNED_APEX)
                .build(samples.track(), samples.profiles(), 1.0, Map.of());
            var optimizer = guarded();
            var original = optimizer.prepareOriginalDomain(samples.track(), samples.profiles(), 1.0,
                JunctionContext.empty(), Map.of(), tube, org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),context(centers.length));
            var baseline = optimizer.optimizeOriginalDomain(original);
            var restricted = optimizer.optimizeOriginalDomain(restrictProduction(original));
            double expectedRange = java.util.Arrays.stream(centers).max().orElseThrow()
                - java.util.Arrays.stream(centers).min().orElseThrow();
            double range = restricted.offsetsPx().stream().mapToDouble(Double::doubleValue).max().orElseThrow()
                - restricted.offsetsPx().stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            System.out.println("MATCHED " + shape + " latent-range=" + expectedRange + " retained-range=" + range
                + " baseline=" + baseline.offsetsPx() + " restricted=" + restricted.offsetsPx());
            assertTrue(range >= .9 * expectedRange, shape + " must retain at least 90% amplitude");
            for (int apex : shape.equals("concentrated-bend") ? List.of(20) : List.of(5, 15, 25, 35)) {
                int chosen = apex;
                for (int index = apex - 2; index <= apex + 2; index++) {
                    if (centers[apex] > 0
                        ? restricted.offsetsPx().get(index) > restricted.offsetsPx().get(chosen)
                        : restricted.offsetsPx().get(index) < restricted.offsetsPx().get(chosen)) chosen = index;
                }
                double apexError = Math.hypot(2.0 * (chosen - apex), restricted.offsetsPx().get(chosen) - centers[apex]);
                assertTrue(apexError <= 1.0, shape + " apex error in factual source pixels=" + apexError);
            }
        }
    }

    // The old isolated rendered-raster natural experiment failed the fixed gate. Its exact
    // source, XML and logs remain frozen in build/g006-observed-shoulder-public-20261118/.
    // Production scalar composition is covered separately; the old failure is not relabeled.

    @Test
    void restrictingMeasuredShouldersMustReduceNativeWeakReferenceKink() throws Exception {
        // Catches the native objective following an off-shoulder weak tube and making its jump free.
        double[] centers = {0, .1, .25, .5, .75, 1, 1.75, 2.25, 2.65, 3, 3.15, 3.25, 3.3, 3.3, 3.3};
        double[] effective = {-.5, -.5, -.5, -.5, -.5, -.5, -.25, 2, 2.4, 2.8, 3.05, 3.2, 3.3, 3.3, 3.3};
        double[] local = {0, .1, .25, .4, .5, .5, 1.25, 2, 2.4, 2.8, 3.05, 3.2, 3.3, 3.3, 3.3};
        Fixture fixture = fixture(centers, effective, local, index -> index <= 6 ? 0.0 : 1.0);
        var optimizer = guarded();
        OriginalDomain original = optimizer.prepareOriginalDomain(fixture.track(), fixture.profiles(), 1.0,
            JunctionContext.empty(), Map.of(), fixture.tube(), org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults(),context(centers.length));
        var baseline = optimizer.optimizeOriginalDomain(original);
        var band = fixture.track().points().get(6).band();
        assertTrue(original.states().get(6).stream().anyMatch(x -> x >= band.shoulderMinPx()
            && x <= band.shoulderMaxPx()), "The native domain must already have measured alternatives");
        assertTrue(baseline.offsetsPx().get(6) < band.shoulderMinPx(),
            "Baseline must reproduce the off-shoulder mechanism: " + baseline.offsetsPx());
        assertEquals(0.0, baseline.costs().get(7).accelerationCost(), 1e-3,
            "The native reference jump must be essentially free in tube-relative curvature");
        assertTrue(peakTurnDegrees(baseline.offsetsPx(), 4, 9) > 30.0);

        OriginalDomain experimental = restrictProduction(original);
        var restricted = optimizer.optimizeOriginalDomain(experimental);
        printEvidence("weak-reference", original, experimental, baseline, restricted);
        assertTrue(restricted.offsetsPx().get(6) >= band.shoulderMinPx(),
            "Restricted solve must stay inside the measured shoulder");
        double oldRms = secondDifferenceRms(baseline.offsetsPx(), 4, 9);
        double newRms = secondDifferenceRms(restricted.offsetsPx(), 4, 9);
        double oldTurn = peakTurnDegrees(baseline.offsetsPx(), 4, 9);
        double newTurn = peakTurnDegrees(restricted.offsetsPx(), 4, 9);
        assertTrue(newRms <= .60 * oldRms, "40% RMS gate: old=" + oldRms + " new=" + newRms);
        assertTrue(newTurn <= .75 * oldTurn, "25% turn gate: old=" + oldTurn + " new=" + newTurn);
        assertTrue(peakTurnDegrees(restricted.offsetsPx(), 3, 10) < oldTurn,
            "An equally large adjacent reversal must not replace the original kink");
        assertFalse(restricted.offsetsPx().stream().allMatch(x -> x == 0.0));
    }

    private OriginalDomain restrictProduction(OriginalDomain original) {
        return guarded().prepareObservedShoulderDomain(original, Map.of(original.track().id(),original.track()), java.util.Optional.empty());
    }

    private CorridorCenterlineOptimizer guarded() {
        return new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform.metricGrid(
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(0,0),1,0,0,1),
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE);
    }
    private CorridorWorkingSchedule.BoundContext context(int size) {
        var e=org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorPolicyFixture.straight(true,false,java.util.Set.of()).evidence();
        return CorridorWorkingSchedule.bindContext("public-observed-domain", "public-network", e, "native",
            new CorridorWorkingSchedule.SourceProtection(0,(size-1)*2.0,List.of()),"public-source-proof",
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE);
    }

    private Fixture fixture(double[] centers, double[] effective, double[] local,
        java.util.function.IntToDoubleFunction motion) {
        List<CorridorProfile> profiles = new ArrayList<>();
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        List<CorridorTubeSlice> slices = new ArrayList<>();
        for (int index = 0; index < centers.length; index++) {
            double center = centers[index];
            CorridorBand band = new CorridorBand("fine-" + index, center, center - 1.1, center + 1.25,
                center - .325, center + .325, List.of(center, center, center), .155, .09, 1.0,
                .7, .95, 1, .7, .92, .5, false, List.of());
            List<IntensitySample> samples = new ArrayList<>();
            // A faint finite-width cross section, never a one-bright-pixel fixture.
            for (int step = -48; step <= 48; step++) {
                double offset = step / 8.0;
                double intensity = .09 + .065 * Math.exp(-.5 * Math.pow((offset - center) / .6, 2));
                samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
            }
            var source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(index * 2.0, 0), index * 2.0, 0, index * 2.0),
                new Point2D.Double(0, 1), List.of(), true, samples);
            profiles.add(new CorridorProfile(index, source, List.of(band), .155, .09, .065, true));
            points.put(index, new CorridorTrackPoint(index, band, false));
            slices.add(new CorridorTubeSlice(index, index * 2.0, effective[index], 0,
                local[index], 0, effective[index], 0, 1.3125, motion.applyAsDouble(index),
                motion.applyAsDouble(index) == 0 ? "reversing-noise" : "supported-apex", 0,
                band.coreMinPx(), band.coreMaxPx(), band.shoulderMinPx(), band.shoulderMaxPx(),
                1.3125, .5, false, false, center, center, center, true));
        }
        return new Fixture(new CorridorTrack("public-direct", points, centers.length * 2.0, 1,
            false, List.of(), ""), List.copyOf(profiles), new LongitudinalCorridorTube(slices));
    }

    private double secondDifferenceRms(List<Double> offsets, int start, int end) {
        double squared = 0;
        for (int index = start; index <= end; index++) {
            double difference = offsets.get(index - 1) - 2 * offsets.get(index) + offsets.get(index + 1);
            squared += difference * difference;
        }
        return Math.sqrt(squared / (end - start + 1));
    }

    private double peakTurnDegrees(List<Double> offsets, int start, int end) {
        double maximum = 0;
        for (int index = start; index <= end; index++) {
            double before = Math.atan2(offsets.get(index) - offsets.get(index - 1), 2.0);
            double after = Math.atan2(offsets.get(index + 1) - offsets.get(index), 2.0);
            maximum = Math.max(maximum, Math.abs(Math.toDegrees(after - before)));
        }
        return maximum;
    }

    private void printEvidence(String name, OriginalDomain original, OriginalDomain experimental,
        CorridorCenterlineOptimizer.OptimizationResult baseline,
        CorridorCenterlineOptimizer.OptimizationResult restricted) {
        System.out.println("PUBLIC TEST-ONLY EXPERIMENT " + name);
        System.out.println("baseline objective=" + baseline.totalCost() + " offsets=" + baseline.offsetsPx());
        System.out.println("restricted objective=" + restricted.totalCost() + " offsets=" + restricted.offsetsPx());
        System.out.println("baseline rms=" + secondDifferenceRms(baseline.offsetsPx(), 4, 9)
            + " peak-turn=" + peakTurnDegrees(baseline.offsetsPx(), 4, 9));
        System.out.println("restricted rms=" + secondDifferenceRms(restricted.offsetsPx(), 4, 9)
            + " peak-turn=" + peakTurnDegrees(restricted.offsetsPx(), 4, 9));
        for (int index = 0; index < original.states().size(); index++) {
            System.out.println("row=" + index + " native-states=" + original.states().get(index)
                + " experiment-states=" + experimental.states().get(index));
            System.out.println("native-cost=" + baseline.costs().get(index));
            System.out.println("experiment-cost=" + restricted.costs().get(index));
        }
    }

    private record Fixture(CorridorTrack track, List<CorridorProfile> profiles, LongitudinalCorridorTube tube) { }
}

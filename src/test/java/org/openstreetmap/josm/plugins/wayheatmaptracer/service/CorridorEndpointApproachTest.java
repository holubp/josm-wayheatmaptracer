package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

class CorridorEndpointApproachTest {
    @Test
    void fixesEndpointAndApproachesAStableBranchWithoutTerminalReversal() {
        Scenario scenario = scenario(6.0, 16, true);
        JunctionContext context = new JunctionContext(List.of(
            new EndpointConstraint(0, 1L, true, true, 0.0, 0.0, 6)
        ));

        CorridorCenterlineOptimizer.OptimizationResult result = new CorridorCenterlineOptimizer().optimize(
            scenario.track(), scenario.profiles(), 1.0, context, Map.of());

        assertEquals(0.0, result.offsetsPx().get(0), 1e-9);
        for (int index = 1; index <= 5; index++) {
            assertTrue(result.offsetsPx().get(index) >= result.offsetsPx().get(index - 1) - 1e-9,
                "terminal approach reversed at profile " + index + ": " + result.offsetsPx());
        }
        assertTrue(maximumTurnDegrees(result.screenPoints(), 0, 6) <= 15.0,
            "terminal turn exceeded 15 degrees: " + result.screenPoints());
    }

    @Test
    void reportsUnsupportedApproachWhenNoInteriorCorridorExists() {
        Scenario scenario = scenario(0.0, 8, false);
        JunctionContext context = new JunctionContext(List.of(
            new EndpointConstraint(0, 1L, true, true, 0.0, 0.0, 6)
        ));
        LongitudinalCorridorTube tube = new CorridorTubeBuilder().build(
            scenario.track(), scenario.profiles(), 1.0, Map.of());

        EndpointApproachModel model = new EndpointApproachBuilder().build(
            scenario.track(), scenario.profiles(), tube, context, Map.of());

        assertFalse(model.supportsConstraint(0));
        assertEquals("no-reliable-interior-corridor", model.approaches().get(0).reason());
    }

    @Test
    void selectsPhysicalEndpointAnchorIndependentlyOfRasterScale() {
        JunctionContext context = new JunctionContext(List.of(
            new EndpointConstraint(0, 1L, true, true, 0.0, 0.0, 6)
        ));
        Scenario normal = scenario(4.0, 16, true, 5.0);
        Scenario oversampled = scenario(4.0, 16, true, 120.0);

        EndpointApproachModel first = new EndpointApproachBuilder().build(normal.track(), normal.profiles(),
            new CorridorTubeBuilder().build(normal.track(), normal.profiles(), 1.0, Map.of()), context, Map.of());
        EndpointApproachModel second = new EndpointApproachBuilder().build(oversampled.track(), oversampled.profiles(),
            new CorridorTubeBuilder().build(oversampled.track(), oversampled.profiles(), 1.0, Map.of()), context, Map.of());

        assertTrue(first.supportsConstraint(0));
        assertTrue(second.supportsConstraint(0));
        assertEquals(first.approaches().get(0).interiorAnchorProfileIndex(),
            second.approaches().get(0).interiorAnchorProfileIndex());
    }

    private Scenario scenario(double center, int count, boolean withEvidence) {
        return scenario(center, count, withEvidence, 5.0);
    }

    private Scenario scenario(double center, int count, boolean withEvidence, double rasterSpacing) {
        List<CorridorProfile> profiles = new ArrayList<>();
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            CorridorBand band = new CorridorBand("band", center, center - 4.0, center + 4.0,
                center - 2.0, center + 2.0, List.of(center), 1.0, 0.02, 1.0,
                0.9, 0.8, 0.5, false, List.of());
            List<IntensitySample> samples = new ArrayList<>();
            for (int offset = (int) center - 5; offset <= (int) center + 5; offset++) {
                double distance = Math.abs(offset - center);
                double intensity = distance <= 2.0 ? 0.95 : (distance <= 4.0 ? 0.55 : 0.02);
                samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
            }
            RenderedHeatmapSampler.CrossSectionProfile source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(index * 2.0, 0.0), index * rasterSpacing, 0.0,
                    index * 2.0),
                new Point2D.Double(0.0, 1.0), List.of(), true, samples);
            profiles.add(new CorridorProfile(index, source, withEvidence ? List.of(band) : List.of(),
                withEvidence ? 1.0 : 0.0, 0.02, withEvidence ? 0.98 : 0.0, true));
            if (withEvidence) {
                points.put(index, new CorridorTrackPoint(index, band, false));
            }
        }
        if (!withEvidence) {
            CorridorBand seed = new CorridorBand("seed", 0.0, -1.0, 1.0, -0.5, 0.5,
                List.of(0.0), 0.01, 0.0, 1.0, 0.05, 0.05, 0.5, false, List.of());
            points.put(0, new CorridorTrackPoint(0, seed, false));
        }
        return new Scenario(new CorridorTrack("track", points, count, 1.0, false, List.of(), ""), profiles);
    }

    private double maximumTurnDegrees(List<Point2D.Double> points, int start, int endInclusive) {
        double maximum = 0.0;
        for (int index = Math.max(start + 1, 1); index < Math.min(endInclusive, points.size() - 1); index++) {
            double first = Math.atan2(points.get(index).y - points.get(index - 1).y,
                points.get(index).x - points.get(index - 1).x);
            double second = Math.atan2(points.get(index + 1).y - points.get(index).y,
                points.get(index + 1).x - points.get(index).x);
            double difference = Math.abs(Math.toDegrees(second - first));
            maximum = Math.max(maximum, Math.min(difference, 360.0 - difference));
        }
        return maximum;
    }

    private record Scenario(CorridorTrack track, List<CorridorProfile> profiles) {
    }
}

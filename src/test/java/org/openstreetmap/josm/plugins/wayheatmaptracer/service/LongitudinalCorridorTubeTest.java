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

class LongitudinalCorridorTubeTest {
    @Test
    void stabilizesAlternatingCentersInsideOneBroadCorridor() {
        Scenario scenario = scenario(index -> index % 2 == 0 ? -1.0 : 1.0, 21);

        LongitudinalCorridorTube tube = new CorridorTubeBuilder().build(
            scenario.track(), scenario.profiles(), 1.0, Map.of());

        assertTrue(tube.slices().subList(4, 17).stream()
            .allMatch(slice -> Math.abs(slice.centerOffsetPx()) <= 0.25));
        assertTrue(tube.slices().stream().allMatch(slice -> slice.uncertaintyPx() >= 0.5));
    }

    @Test
    void retainsAStableWeakNarrowStrand() {
        Scenario scenario = scenario(index -> 3.25, 11);

        LongitudinalCorridorTube tube = new CorridorTubeBuilder().build(
            scenario.track(), scenario.profiles(), 1.0, Map.of());

        assertTrue(tube.slices().stream().allMatch(slice -> Math.abs(slice.centerOffsetPx() - 3.25) < 1e-9));
        assertEquals(3.25, tube.at(5).rawCenterPx(), 1e-9);
    }

    @Test
    void physicalWindowsAndTangentsIgnoreRasterOversampling() {
        Scenario normal = scenario(index -> index * 0.15, 13, 6.0);
        Scenario oversampled = scenario(index -> index * 0.15, 13, 144.0);

        LongitudinalCorridorTube first = new CorridorTubeBuilder().build(
            normal.track(), normal.profiles(), 1.0, Map.of());
        LongitudinalCorridorTube second = new CorridorTubeBuilder().build(
            oversampled.track(), oversampled.profiles(), 1.0, Map.of());

        for (int index = 0; index < first.slices().size(); index++) {
            assertEquals(first.at(index).distanceMeters(), second.at(index).distanceMeters(), 1e-9);
            assertEquals(first.at(index).centerOffsetPx(), second.at(index).centerOffsetPx(), 1e-9);
            assertEquals(first.at(index).tangentOffsetPerMeter(), second.at(index).tangentOffsetPerMeter(), 1e-9);
        }
    }

    @Test
    void reversingShortRunsDoNotAuthorizeTheLocalReference() {
        Scenario scenario = scenario(index -> switch ((index / 3) % 4) {
            case 0, 3 -> -1.0;
            default -> 1.0;
        }, 30);

        LongitudinalCorridorTube tube = new CorridorTubeBuilder().build(
            scenario.track(), scenario.profiles(), 1.0, Map.of());

        assertEquals(0.0, tube.at(15).motionSupport(), 1e-9);
        assertEquals("reversing-noise", tube.at(15).motionSupportReason());
    }

    @Test
    void onePersistentApexAuthorizesTheLocalTurnReference() {
        Scenario scenario = scenario(index -> 5.0 * Math.sin(index * Math.PI / 10.0), 30);

        LongitudinalCorridorTube tube = new CorridorTubeBuilder().build(
            scenario.track(), scenario.profiles(), 1.0, Map.of());

        assertTrue(tube.at(5).motionSupport() > 0.5);
        assertTrue(tube.at(5).motionSupportReason().startsWith("supported-apex"));
    }

    private Scenario scenario(java.util.function.IntToDoubleFunction centers, int count) {
        return scenario(centers, count, 6.0);
    }

    private Scenario scenario(java.util.function.IntToDoubleFunction centers, int count, double rasterSpacing) {
        List<CorridorProfile> profiles = new ArrayList<>();
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            double center = centers.applyAsDouble(index);
            CorridorBand band = new CorridorBand("band", center, -4.0, 4.0, -2.0, 2.0,
                List.of(center), 0.25, 0.02, 1.0, 0.35, 0.75, 0.5, false, List.of());
            RenderedHeatmapSampler.CrossSectionProfile source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(index * 1.5, 0.0), index * rasterSpacing, 0.0,
                    index * 1.5),
                new Point2D.Double(0.0, 1.0), List.of(), true, List.of());
            profiles.add(new CorridorProfile(index, source, List.of(band), 0.25, 0.02, 0.23, true));
            points.put(index, new CorridorTrackPoint(index, band, false));
        }
        return new Scenario(new CorridorTrack("track", points, count, 1.0, false, List.of(), ""), profiles);
    }

    private record Scenario(CorridorTrack track, List<CorridorProfile> profiles) {
    }
}

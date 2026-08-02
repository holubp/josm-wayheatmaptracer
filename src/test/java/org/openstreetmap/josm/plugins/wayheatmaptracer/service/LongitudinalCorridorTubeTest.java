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

    private Scenario scenario(java.util.function.IntToDoubleFunction centers, int count) {
        List<CorridorProfile> profiles = new ArrayList<>();
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            double center = centers.applyAsDouble(index);
            CorridorBand band = new CorridorBand("band", center, -4.0, 4.0, -2.0, 2.0,
                List.of(center), 0.25, 0.02, 1.0, 0.35, 0.75, 0.5, false, List.of());
            RenderedHeatmapSampler.CrossSectionProfile source = new RenderedHeatmapSampler.CrossSectionProfile(
                new EastNorth(index * 1.5, 0.0), new Point2D.Double(index * 6.0, 0.0),
                new Point2D.Double(0.0, 1.0), List.of(), true, List.of());
            profiles.add(new CorridorProfile(index, source, List.of(band), 0.25, 0.02, 0.23, true));
            points.put(index, new CorridorTrackPoint(index, band, false));
        }
        return new Scenario(new CorridorTrack("track", points, count, 1.0, false, List.of(), ""), profiles);
    }

    private record Scenario(CorridorTrack track, List<CorridorProfile> profiles) {
    }
}

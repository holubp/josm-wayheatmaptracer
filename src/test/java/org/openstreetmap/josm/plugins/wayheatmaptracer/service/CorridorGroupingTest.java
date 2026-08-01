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

class CorridorGroupingTest {
    @Test
    void formsParentForPersistentShallowValleyAndRetainsChildren() {
        Scenario scenario = scenario(0.72);

        CorridorGrouping.GroupingResult result = new CorridorGrouping().group(scenario.tracks(), scenario.profiles());

        assertEquals(3, result.tracks().size());
        CorridorTrack parent = result.tracks().stream().filter(CorridorTrack::parent).findFirst().orElseThrow();
        assertEquals("combined", parent.groupingDecision());
        assertEquals(2, parent.childTrackIds().size());
    }

    @Test
    void keepsDeepValleyCarriagewaysSeparate() {
        Scenario scenario = scenario(0.20);

        CorridorGrouping.GroupingResult result = new CorridorGrouping().group(scenario.tracks(), scenario.profiles());

        assertEquals(2, result.tracks().size());
        assertTrue(result.tracks().stream().noneMatch(CorridorTrack::parent));
        assertEquals("separate", result.decisions().get(0).decision());
    }

    private Scenario scenario(double valley) {
        List<CorridorProfile> profiles = new ArrayList<>();
        Map<Integer, CorridorTrackPoint> leftPoints = new LinkedHashMap<>();
        Map<Integer, CorridorTrackPoint> rightPoints = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            CorridorBand left = band("left", -3.0, -5.0, 0.0);
            CorridorBand right = band("right", 3.0, 0.0, 5.0);
            leftPoints.put(i, new CorridorTrackPoint(i, left, false));
            rightPoints.put(i, new CorridorTrackPoint(i, right, false));
            List<IntensitySample> samples = new ArrayList<>();
            for (int offset = -6; offset <= 6; offset++) {
                double intensity = Math.abs(offset) <= 1 ? valley : (Math.abs(offset) == 3 ? 1.0 : 0.75);
                samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
            }
            RenderedHeatmapSampler.CrossSectionProfile source = new RenderedHeatmapSampler.CrossSectionProfile(
                new EastNorth(i * 10.0, 0.0), new Point2D.Double(i * 10.0, 0.0),
                new Point2D.Double(0.0, 1.0), List.of(), true, samples);
            profiles.add(new CorridorProfile(i, source, List.of(left, right), 1.0, 0.0, 1.0, true));
        }
        CorridorTrack left = new CorridorTrack("left", leftPoints, 10.0, 1.0, false, List.of(), "");
        CorridorTrack right = new CorridorTrack("right", rightPoints, 10.0, 1.0, false, List.of(), "");
        return new Scenario(List.of(left, right), profiles);
    }

    private CorridorBand band(String id, double center, double shoulderMin, double shoulderMax) {
        return new CorridorBand(id, center, shoulderMin, shoulderMax, center - 1.0, center + 1.0,
            List.of(center), 1.0, 0.0, 1.0, 0.9, 0.8, 0.5, false, List.of());
    }

    private record Scenario(List<CorridorTrack> tracks, List<CorridorProfile> profiles) {
    }
}

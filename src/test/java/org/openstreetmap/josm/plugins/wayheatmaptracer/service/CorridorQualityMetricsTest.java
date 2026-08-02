package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

class CorridorQualityMetricsTest {
    @Test
    void detectsAlternatingExcursionsIndependentlyOfOptimizerWeights() {
        List<CorridorProfile> profiles = new ArrayList<>();
        List<CorridorTubeSlice> slices = new ArrayList<>();
        List<Double> offsets = new ArrayList<>();
        List<Point2D.Double> points = new ArrayList<>();
        var trackPoints = new LinkedHashMap<Integer, CorridorTrackPoint>();
        for (int index = 0; index < 15; index++) {
            double offset = index % 2 == 0 ? -1.0 : 1.0;
            CorridorBand band = new CorridorBand("band", 0.0, -3.0, 3.0, -2.0, 2.0,
                List.of(0.0), 1.0, 0.0, 1.0, 0.9, 0.5, 0.5, false, List.of());
            var source = new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(index * 2.0, 0.0),
                new Point2D.Double(index * 5.0, 0.0), new Point2D.Double(0.0, 1.0), List.of(), true, List.of());
            profiles.add(new CorridorProfile(index, source, List.of(band), 1.0, 0.0, 1.0, true));
            slices.add(new CorridorTubeSlice(index, index * 2.0, 0.0, 0.0, 0.0,
                -2.0, 2.0, -3.0, 3.0, 0.5, 1.0, false, false, 0.0, 0.0, 0.0, true));
            offsets.add(offset);
            points.add(new Point2D.Double(index * 5.0, offset));
            trackPoints.put(index, new CorridorTrackPoint(index, band, false));
        }
        CorridorTrack track = new CorridorTrack("track", trackPoints, 15.0, 1.0, false, List.of(), "");

        var quality = new CorridorQualityCalculator().calculate(track, profiles,
            new LongitudinalCorridorTube(slices), offsets, points, 1.0,
            new EndpointApproachModel(List.of()));

        assertTrue(quality.highFrequencyP95SourcePx() > 1.0);
        assertTrue(quality.unsupportedExcursions() >= 10);
        assertEquals(0, quality.forwardProgressViolations());
        assertTrue(quality.longitudinalPersistence() < 0.25);
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorCoverage;

class CorridorCoverageCalculatorTest {
    @Test
    void rejectsAVisibleLocalIslandWithInformativeEvidenceBeyondIt() {
        List<CorridorProfile> profiles = profiles(10, 2.5);
        CorridorTrack island = track(profiles, 2, 5, false);

        CorridorCoverage coverage = new CorridorCoverageCalculator().calculate(
            island, profiles, new EndpointApproachModel(List.of()));

        assertFalse(coverage.complete());
        assertTrue(coverage.informativeEvidenceBeyondTrack());
        assertEquals("unsupported-leading-corridor", coverage.reason());
    }

    @Test
    void acceptsAContinuousLowIntensityStrand() {
        List<CorridorProfile> profiles = profiles(10, 2.5);
        CorridorTrack full = track(profiles, 0, 9, false);

        CorridorCoverage coverage = new CorridorCoverageCalculator().calculate(
            full, profiles, new EndpointApproachModel(List.of()));

        assertTrue(coverage.complete());
        assertEquals(1.0, coverage.informativeCoverageRatio(), 1e-9);
    }

    @Test
    void acceptsOnlyTrackerApprovedBoundedInternalBridge() {
        List<CorridorProfile> profiles = profiles(10, 2.5);
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        for (int index : List.of(0, 1, 2, 6, 7, 8, 9)) {
            points.put(index, new CorridorTrackPoint(index, profiles.get(index).bands().get(0), index == 6));
        }
        CorridorTrack bridged = new CorridorTrack("track", points, 1.0, 0.7, false, List.of(), "");

        CorridorCoverage coverage = new CorridorCoverageCalculator().calculate(
            bridged, profiles, new EndpointApproachModel(List.of()));

        assertTrue(coverage.complete());
        assertEquals(1, coverage.approvedBridgeCount());
        assertEquals(3, coverage.maximumInternalUnsupportedProfiles());
        assertEquals(10.0, coverage.maximumInternalUnsupportedMeters(), 1e-9);
    }

    private List<CorridorProfile> profiles(int count, double spacingMeters) {
        return java.util.stream.IntStream.range(0, count).mapToObj(index -> {
            CorridorBand band = new CorridorBand("band", 0.0, -2.0, 2.0, -0.5, 0.5,
                List.of(0.0), 0.12, 0.05, 1.0, 0.20, 0.35, 0.5, false, List.of());
            var source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(index * spacingMeters, 0.0), index * 40.0, 0.0,
                    index * spacingMeters),
                new Point2D.Double(0.0, 1.0), List.of(), true, List.of());
            return new CorridorProfile(index, source, List.of(band), 0.12, 0.05, 0.07, true);
        }).toList();
    }

    private CorridorTrack track(List<CorridorProfile> profiles, int first, int last, boolean bridged) {
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        for (int index = first; index <= last; index++) {
            points.put(index, new CorridorTrackPoint(index, profiles.get(index).bands().get(0), bridged));
        }
        return new CorridorTrack("track", points, 1.0, points.size() / (double) profiles.size(),
            false, List.of(), "");
    }
}

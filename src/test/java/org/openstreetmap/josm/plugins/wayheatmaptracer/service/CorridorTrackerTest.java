package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

class CorridorTrackerTest {
    @Test
    void preservesTwoParallelIdentitiesAndRejectsOneProfileExcursion() {
        List<CorridorProfile> profiles = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            List<CorridorBand> bands = new ArrayList<>(List.of(band("left", -5.0), band("right", 5.0)));
            if (i == 6) {
                bands.add(band("noise", 16.0));
            }
            profiles.add(profile(i, bands));
        }

        List<CorridorTrack> tracks = new CorridorTracker().track(profiles, 1.0);

        assertEquals(2, tracks.size());
        assertTrue(tracks.stream().allMatch(track -> track.points().size() == 12));
        assertFalse(tracks.stream().flatMap(track -> track.points().values().stream())
            .anyMatch(point -> point.band().centerOffsetPx() > 10.0));
    }

    @Test
    void bridgesTwoUnsupportedProfilesWithoutSnappingToZero() {
        List<CorridorProfile> profiles = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            profiles.add(profile(i, i == 4 || i == 5 ? List.of() : List.of(band("ridge", 6.0))));
        }

        List<CorridorTrack> tracks = new CorridorTracker().track(profiles, 1.0);

        assertEquals(1, tracks.size());
        assertTrue(tracks.get(0).points().get(6).bridged());
        assertEquals(6.0, tracks.get(0).points().get(6).band().centerOffsetPx(), 1e-9);
    }

    private CorridorProfile profile(int index, List<CorridorBand> bands) {
        RenderedHeatmapSampler.CrossSectionProfile source = new RenderedHeatmapSampler.CrossSectionProfile(
            new EastNorth(index * 10.0, 0.0),
            new Point2D.Double(index * 10.0, 0.0),
            new Point2D.Double(0.0, 1.0),
            List.of(),
            true,
            List.of()
        );
        return new CorridorProfile(index, source, bands, 1.0, 0.0, 1.0, true);
    }

    private CorridorBand band(String id, double center) {
        return new CorridorBand(id, center, center - 2.0, center + 2.0, center - 0.5, center + 0.5,
            List.of(center, center), 1.0, 0.0, 1.0, 0.9, 0.85, 0.5, false, List.of());
    }
}

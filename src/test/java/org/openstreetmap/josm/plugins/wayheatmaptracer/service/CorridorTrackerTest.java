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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorCoverage;

class CorridorTrackerTest {
    @Test
    void canonicalBridgeBoundaryIsIndependentOfTrackingDirection() {
        List<CorridorProfile> profiles = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            profiles.add(profile(index, index == 3 || index == 4
                ? List.of() : List.of(band("ridge", 2.0)), 2.5));
        }
        CorridorTracker tracker = new CorridorTracker();

        CorridorTrack forward = tracker.trackFromSeed(profiles, 0, profiles.get(0).bands().get(0), 1.0);
        CorridorTrack backward = tracker.trackFromSeed(profiles, 8, profiles.get(8).bands().get(0), 1.0);

        for (CorridorTrack track : List.of(forward, backward)) {
            assertFalse(track.points().get(2).bridged(), "left boundary must not own bridge approval");
            assertTrue(track.points().get(5).bridged(), "higher-index boundary must own bridge approval");
            CorridorCoverage coverage = new CorridorCoverageCalculator().calculate(
                track, profiles, new EndpointApproachModel(List.of()));
            assertTrue(coverage.complete(), "tracker-approved bounded gap must be complete");
            assertEquals(1, coverage.approvedBridgeCount());
        }
    }

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
            profiles.add(profile(i, i == 4 || i == 5 ? List.of() : List.of(band("ridge", 6.0)), 5.0));
        }

        List<CorridorTrack> tracks = new CorridorTracker().track(profiles, 1.0);

        assertEquals(1, tracks.size());
        assertTrue(tracks.get(0).points().get(6).bridged());
        assertEquals(6.0, tracks.get(0).points().get(6).band().centerOffsetPx(), 1e-9);
    }

    @Test
    void coreCensoredBandsCannotBecomeDirectPositionEvidence() {
        List<CorridorProfile> profiles = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            CorridorBand observation = index == 4 || index == 5
                ? censoredBand("edge", 20.0) : band("ridge", 2.0);
            profiles.add(profile(index, List.of(observation), 2.5));
        }

        CorridorTrack track = new CorridorTracker().track(profiles, 1.0).stream()
            .filter(candidate -> candidate.points().containsKey(0) && candidate.points().containsKey(8))
            .findFirst().orElseThrow();

        assertFalse(track.points().containsKey(4));
        assertFalse(track.points().containsKey(5));
        assertTrue(track.points().get(6).bridged());
    }

    @Test
    void gapsAcrossTemporaryNearbyStrandInsteadOfChangingIdentity() {
        List<CorridorProfile> profiles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            List<CorridorBand> bands = i >= 7 && i <= 13
                ? List.of(band("temporary-side", 3.0))
                : List.of(band("main", 0.0));
            profiles.add(profile(i, bands, 2.0));
        }

        List<CorridorTrack> tracks = new CorridorTracker().track(profiles, 1.0);

        CorridorTrack main = tracks.stream()
            .filter(track -> track.points().containsKey(0) && track.points().containsKey(19))
            .findFirst().orElseThrow();
        assertTrue(main.points().values().stream()
            .allMatch(point -> Math.abs(point.band().centerOffsetPx()) <= 0.5));
        assertTrue(main.points().get(14).bridged());
    }

    @Test
    void censoredGapCannotUseSustainedMotionToSwitchToAnotherStrand() {
        List<CorridorProfile> profiles = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            List<CorridorBand> bands;
            if (index <= 2) {
                bands = List.of(band("main", 12.0 + 2.0 * index));
            } else if (index <= 4) {
                bands = List.of(censoredBand("edge", 18.0));
            } else {
                bands = List.of(band("competing", 19.0 + 2.0 * (index - 5)));
            }
            profiles.add(profile(index, bands, 2.0));
        }

        CorridorTrack track = new CorridorTracker().trackFromSeed(
            profiles, 0, profiles.get(0).bands().get(0), 1.0);

        assertFalse(track.points().containsKey(5),
            "A censored gap must not use future same-side motion to switch strands");
    }

    @Test
    void scaleConflictCannotAuthorizeAConsistentLookingStrandChange() {
        List<CorridorProfile> profiles = new ArrayList<>();
        Map<String, BandScaleEvidence> scaleEvidence = new LinkedHashMap<>();
        for (int index = 0; index < 12; index++) {
            boolean competing = index >= 3 && index <= 6;
            CorridorBand observation = band(competing ? "side" : "main", competing ? 2.0 * (index - 2) : 0.0);
            profiles.add(profile(index, List.of(observation), 2.0));
            if (competing) {
                scaleEvidence.put(CorridorCenterlineOptimizer.scaleEvidenceKey(index, "side"),
                    new BandScaleEvidence(0.5, Double.NaN, Double.NaN, List.of(0), true, false));
            }
        }

        List<CorridorTrack> tracks = new CorridorTracker().track(profiles, 1.0, scaleEvidence);

        CorridorTrack main = tracks.stream()
            .filter(track -> track.points().containsKey(0) && track.points().containsKey(11))
            .findFirst().orElseThrow();
        assertTrue(main.points().values().stream()
            .allMatch(point -> Math.abs(point.band().centerOffsetPx()) <= 0.5));
        assertTrue(main.points().get(7).bridged());
    }

    @Test
    void doesNotRejoinSignalAcrossMoreThanTwentyMeters() {
        List<CorridorProfile> profiles = List.of(
            profile(0, List.of(band("ridge", 0.0)), 11.0),
            profile(1, List.of(band("ridge", 0.0)), 11.0),
            profile(2, List.of(), 11.0),
            profile(3, List.of(band("ridge", 0.0)), 11.0),
            profile(4, List.of(band("ridge", 0.0)), 11.0)
        );

        List<CorridorTrack> tracks = new CorridorTracker().track(profiles, 1.0);

        assertFalse(tracks.stream().anyMatch(track ->
            track.points().containsKey(1) && track.points().containsKey(3)));
    }

    @Test
    void boundedPhysicalGapBridgesRegardlessOfRasterOversampling() {
        List<CorridorProfile> scaleOne = new ArrayList<>();
        List<CorridorProfile> scaleSix = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            List<CorridorBand> bandsScaleOne = index >= 3 && index <= 7
                ? List.of(band("temporary-side", 80.0))
                : List.of(band("ridge", 0.0));
            List<CorridorBand> bandsScaleSix = index >= 3 && index <= 7
                ? List.of(band("temporary-side", 480.0))
                : List.of(band("ridge", 0.0));
            scaleOne.add(profile(index, bandsScaleOne, 2.6, 40.0));
            scaleSix.add(profile(index, bandsScaleSix, 2.6, 240.0));
        }

        CorridorTrack first = new CorridorTracker().track(scaleOne, 24.0).stream()
            .filter(track -> track.points().containsKey(0) && track.points().containsKey(9))
            .findFirst().orElseThrow();
        CorridorTrack second = new CorridorTracker().track(scaleSix, 144.0).stream()
            .filter(track -> track.points().containsKey(0) && track.points().containsKey(9))
            .findFirst().orElseThrow();

        assertEquals(first.points().keySet(), second.points().keySet());
        assertTrue(first.points().get(8).bridged());
        assertTrue(second.points().get(8).bridged());
    }

    private CorridorProfile profile(int index, List<CorridorBand> bands) {
        return profile(index, bands, 10.0);
    }

    private CorridorProfile profile(int index, List<CorridorBand> bands, double spacing) {
        return profile(index, bands, spacing, spacing);
    }

    private CorridorProfile profile(int index, List<CorridorBand> bands, double groundSpacing, double rasterSpacing) {
        RenderedHeatmapSampler.CrossSectionProfile source = new RenderedHeatmapSampler.CrossSectionProfile(
            new ProfileSamplingAnchor(new EastNorth(index * groundSpacing, 0.0),
                index * rasterSpacing, 0.0, index * groundSpacing),
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

    private CorridorBand censoredBand(String id, double center) {
        return new CorridorBand(id, center, center - 2.0, center + 2.0, center - 0.5, center + 0.5,
            List.of(center, center), 1.0, 0.0, 1.0, 0.0, 0.0, 0.0,
            0.9, 0.0, 4.0, false, List.of(),
            CorridorBand.BoundaryCompleteness.CORE_CENSORED, CorridorBand.BoundarySide.RIGHT);
    }
}

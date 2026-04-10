package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

class RidgeTrackerTest {
    @Test
    void keepsParallelCandidatesDistinct() {
        RidgeTracker tracker = new RidgeTracker();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = List.of(
            profile(0, -6, 0.9, 6, 0.8),
            profile(10, -6, 0.9, 6, 0.8),
            profile(20, -6, 0.9, 6, 0.8),
            profile(30, -6, 0.9, 6, 0.8)
        );

        var candidates = tracker.track(profiles);

        assertTrue(candidates.size() >= 2);
    }

    @Test
    void resistsOneOffJumpToStrayPeak() {
        RidgeTracker tracker = new RidgeTracker();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = List.of(
            profile(0, 0, 0.88, 14, 0.55),
            profile(10, 0.5, 0.90, 15, 0.50),
            profile(20, 1.0, 0.88, 14, 0.54),
            profile(30, 1.5, 0.70, 16, 0.95),
            profile(40, 2.0, 0.89, 14, 0.58),
            profile(50, 2.5, 0.90, 15, 0.56)
        );

        var candidates = tracker.track(profiles);

        assertTrue(candidates.size() >= 1);
        var best = candidates.get(0);
        assertEquals(6, best.offsetsPx().size());
        assertTrue(best.offsetsPx().get(3) < 8.0, "Tracker should stay on the main corridor rather than jump to the stray branch");
    }

    @Test
    void seedsIgnoreLeadingNoSignalProfiles() {
        RidgeTracker tracker = new RidgeTracker();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = List.of(
            profile(0, 0, 0.0),
            profile(10, 0, 0.0),
            profile(20, -8, 0.75, 8, 0.72),
            profile(30, -8, 0.78, 8, 0.74),
            profile(40, -8, 0.80, 8, 0.76)
        );

        var candidates = tracker.track(profiles);

        assertTrue(candidates.size() >= 1);
        assertTrue(Math.abs(candidates.get(0).offsetsPx().get(2)) >= 4.0,
            "Tracker should not stay biased to the zero-offset seed after initial no-signal profiles");
    }

    private RenderedHeatmapSampler.CrossSectionProfile profile(double x, double leftOffset, double leftIntensity, double rightOffset, double rightIntensity) {
        return new RenderedHeatmapSampler.CrossSectionProfile(
            new EastNorth(x, 0),
            new Point2D.Double(x, 0),
            new Point2D.Double(0, 1),
            List.of(
                new RenderedHeatmapSampler.CrossSectionPeak(leftOffset, leftIntensity),
                new RenderedHeatmapSampler.CrossSectionPeak(rightOffset, rightIntensity)
            )
        );
    }

    private RenderedHeatmapSampler.CrossSectionProfile profile(double x, double offset, double intensity) {
        return new RenderedHeatmapSampler.CrossSectionProfile(
            new EastNorth(x, 0),
            new Point2D.Double(x, 0),
            new Point2D.Double(0, 1),
            List.of(new RenderedHeatmapSampler.CrossSectionPeak(offset, intensity))
        );
    }
}

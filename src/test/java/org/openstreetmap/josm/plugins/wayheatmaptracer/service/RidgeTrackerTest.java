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
            emptyProfile(0),
            emptyProfile(10),
            profile(20, -8, 0.75, 8, 0.72),
            profile(30, -8, 0.78, 8, 0.74),
            profile(40, -8, 0.80, 8, 0.76)
        );

        var candidates = tracker.track(profiles);

        assertTrue(candidates.size() >= 1);
        assertTrue(Math.abs(candidates.get(0).offsetsPx().get(2)) >= 4.0,
            "Tracker should not stay biased to the zero-offset seed after initial no-signal profiles");
    }

    @Test
    void allEmptyProfilesProduceOnlyNoSignalPlaceholder() {
        RidgeTracker tracker = new RidgeTracker();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = List.of(
            emptyProfile(0),
            emptyProfile(10),
            emptyProfile(20)
        );

        var candidates = tracker.track(profiles);

        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).id().contains("no-signal"));
        assertTrue(!candidates.get(0).evidence().hasSignal());
        assertTrue(candidates.get(0).score() < -1000.0);
    }

    @Test
    void bridgesNoSignalGapsWithoutSnappingBackToSourceAxis() {
        RidgeTracker tracker = new RidgeTracker();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = List.of(
            profile(0, 14, 0.82),
            profile(10, 14, 0.80),
            emptyProfile(20),
            emptyProfile(30),
            profile(40, 15, 0.81),
            profile(50, 15, 0.83)
        );

        var candidates = tracker.track(profiles);

        assertTrue(candidates.size() >= 1);
        var best = candidates.get(0);
        assertTrue(best.offsetsPx().stream().allMatch(offset -> offset > 9.0),
            "Tracker should keep the same off-axis corridor through short no-signal gaps");
    }

    @Test
    void prefersContinuousRidgeOverLocallyStrongerParallelDistractor() {
        RidgeTracker tracker = new RidgeTracker();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = List.of(
            profile(0, 0, 0.72, 16, 0.42),
            profile(10, 0, 0.72, 16, 0.45),
            profile(20, 0, 0.70, 16, 0.95),
            profile(30, 0, 0.72, 16, 0.43),
            profile(40, 0, 0.74, 16, 0.44)
        );

        var candidates = tracker.track(profiles);

        assertTrue(candidates.size() >= 1);
        assertTrue(candidates.get(0).offsetsPx().stream().mapToDouble(Math::abs).max().orElse(0.0) < 8.0,
            "A one-section stronger parallel trace should not beat the longitudinally continuous ridge");
    }

    @Test
    void keepsSustainedSineLikeCurvatureInsteadOfFlatteningIt() {
        RidgeTracker tracker = new RidgeTracker();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = List.of(
            profile(0, 0, 0.88),
            profile(10, 4, 0.88),
            profile(20, 8, 0.88),
            profile(30, 4, 0.88),
            profile(40, 0, 0.88),
            profile(50, -4, 0.88),
            profile(60, -8, 0.88),
            profile(70, -4, 0.88),
            profile(80, 0, 0.88)
        );

        var best = tracker.track(profiles).get(0);

        double max = best.offsetsPx().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double min = best.offsetsPx().stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        assertTrue(max >= 6.0 && min <= -6.0,
            "Sustained low-frequency bends should keep their amplitude while short zig-zags are smoothed");
    }

    @Test
    void rejectsAlternatingHighIntensityNoise() {
        RidgeTracker tracker = new RidgeTracker();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = List.of(
            profile(0, 0, 0.78),
            profile(10, 0, 0.35, 12, 1.00),
            profile(20, 0, 0.35, -12, 1.00),
            profile(30, 0, 0.35, 12, 1.00),
            profile(40, 0, 0.78)
        );

        var candidates = tracker.track(profiles);

        assertTrue(candidates.size() >= 1);
        assertTrue(candidates.get(0).offsetsPx().stream().mapToDouble(Math::abs).max().orElse(0.0) <= 4.0,
            "Tracker should require sustained support before accepting a zig-zagging ridge");
    }

    @Test
    void suppressesWanderingOutlierStrandsBesideStrongCenter() {
        RidgeTracker tracker = new RidgeTracker();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = List.of(
            profileWithWidths(0, 0, 0.86, 24, 16, 0.40, 2),
            profileWithWidths(10, 0, 0.84, 24, -18, 0.42, 2),
            profileWithWidths(20, 0, 0.85, 24, 20, 0.41, 2),
            profileWithWidths(30, 0, 0.86, 24, -16, 0.39, 2),
            profileWithWidths(40, 0, 0.84, 24, 18, 0.43, 2)
        );

        var candidates = tracker.track(profiles);

        assertTrue(candidates.size() >= 1);
        assertTrue(candidates.get(0).offsetsPx().stream().mapToDouble(Math::abs).max().orElse(0.0) <= 4.0,
            "Narrow side strands should not pull the result away from a coherent high-intensity center");
    }

    @Test
    void preservesLowIntensityStrandWhenNoStrongerCenterExists() {
        RidgeTracker tracker = new RidgeTracker();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = List.of(
            profileWithWidth(0, 13, 0.30, 2),
            profileWithWidth(10, 15, 0.31, 2),
            profileWithWidth(20, 14, 0.29, 2),
            profileWithWidth(30, 16, 0.30, 2),
            profileWithWidth(40, 15, 0.32, 2)
        );

        var candidates = tracker.track(profiles);

        assertTrue(candidates.size() >= 1);
        assertTrue(candidates.get(0).offsetsPx().stream().allMatch(offset -> offset > 10.0),
            "Sparse low-intensity paths may legitimately consist only of individual strands");
    }

    @Test
    void prefersSyntheticCorridorCenterOverAlternatingShoulders() {
        RidgeTracker tracker = new RidgeTracker();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = List.of(
            profileWithWidths(0, -14, 0.54, 4, -2, 0.50, 4, -8, 0.56, 12),
            profileWithWidths(10, -16, 0.57, 4, -2, 0.52, 4, -9, 0.58, 14),
            profileWithWidths(20, -14, 0.53, 4, -2, 0.54, 4, -8, 0.56, 12),
            profileWithWidths(30, -16, 0.56, 4, -2, 0.51, 4, -9, 0.58, 14),
            profileWithWidths(40, -14, 0.52, 4, -2, 0.53, 4, -8, 0.56, 12)
        );

        var best = tracker.track(profiles).get(0);

        assertTrue(best.offsetsPx().stream().allMatch(offset -> offset < -5.0 && offset > -11.0),
            "Broad heatmap conduits should trace their center instead of alternating between shoulders");
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

    private RenderedHeatmapSampler.CrossSectionProfile profileWithWidth(double x, double offset, double intensity, double width) {
        return new RenderedHeatmapSampler.CrossSectionProfile(
            new EastNorth(x, 0),
            new Point2D.Double(x, 0),
            new Point2D.Double(0, 1),
            List.of(new RenderedHeatmapSampler.CrossSectionPeak(offset, intensity, width, false))
        );
    }

    private RenderedHeatmapSampler.CrossSectionProfile profileWithWidths(
        double x,
        double leftOffset,
        double leftIntensity,
        double leftWidth,
        double rightOffset,
        double rightIntensity,
        double rightWidth
    ) {
        return new RenderedHeatmapSampler.CrossSectionProfile(
            new EastNorth(x, 0),
            new Point2D.Double(x, 0),
            new Point2D.Double(0, 1),
            List.of(
                new RenderedHeatmapSampler.CrossSectionPeak(leftOffset, leftIntensity, leftWidth, false),
                new RenderedHeatmapSampler.CrossSectionPeak(rightOffset, rightIntensity, rightWidth, false)
            )
        );
    }

    private RenderedHeatmapSampler.CrossSectionProfile profileWithWidths(
        double x,
        double leftOffset,
        double leftIntensity,
        double leftWidth,
        double rightOffset,
        double rightIntensity,
        double rightWidth,
        double centerOffset,
        double centerIntensity,
        double centerWidth
    ) {
        return new RenderedHeatmapSampler.CrossSectionProfile(
            new EastNorth(x, 0),
            new Point2D.Double(x, 0),
            new Point2D.Double(0, 1),
            List.of(
                new RenderedHeatmapSampler.CrossSectionPeak(leftOffset, leftIntensity, leftWidth, false),
                new RenderedHeatmapSampler.CrossSectionPeak(rightOffset, rightIntensity, rightWidth, false),
                new RenderedHeatmapSampler.CrossSectionPeak(centerOffset, centerIntensity, centerWidth, true)
            )
        );
    }

    private RenderedHeatmapSampler.CrossSectionProfile emptyProfile(double x) {
        return new RenderedHeatmapSampler.CrossSectionProfile(
            new EastNorth(x, 0),
            new Point2D.Double(x, 0),
            new Point2D.Double(0, 1),
            List.of()
        );
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * Deterministic, unit-explicit inputs for the geometry-cleanup baseline.
 *
 * <p>These are test data only. The tracker output is intentionally not modified or interpreted as
 * cleaned geometry in this checkpoint.</p>
 */
final class GeometryCleanupAcceptanceFixtures {
    static final double METRES_PER_SOURCE_PIXEL = 1.0;
    static final double PROFILE_STEP_METRES = 2.0;
    static final int PROFILE_COUNT = 61;

    private GeometryCleanupAcceptanceFixtures() {
    }

    static List<Fixture> all() {
        List<Fixture> fixtures = new ArrayList<>();
        fixtures.add(single("ripple-3m-to-6m", index -> ((index & 1) == 0 ? 3.0 : -6.0), 0.82, 0.0));
        fixtures.add(single("bend-6m", index -> 6.0 * Math.sin(index * Math.PI / 30.0), 0.82, 0.0));
        fixtures.add(single("bend-10m", index -> 10.0 * Math.sin(index * Math.PI / 30.0), 0.82, 0.0));
        fixtures.add(single("curve-20m", index -> 20.0 * Math.sin(index * Math.PI / 30.0), 0.82, 0.0));
        fixtures.add(single("sine", index -> 8.0 * Math.sin(index * 2.0 * Math.PI / 60.0), 0.78, 0.0));
        fixtures.add(single("switchback", GeometryCleanupAcceptanceFixtures::switchback, 0.78, 0.0));
        fixtures.add(single("weak-holes", index -> 4.0 * Math.sin(index * Math.PI / 30.0), 0.18, 0.24));
        fixtures.add(single("medium-holes", index -> 4.0 * Math.sin(index * Math.PI / 30.0), 0.42, 0.16));
        fixtures.add(single("sparse-union", index -> ((index / 4) & 1) == 0 ? -4.0 : 4.0, 0.24, 0.0));
        fixtures.add(single("wandering-outlier", index -> index == 30 ? 14.0 : 1.5, 0.72, 0.0));
        fixtures.add(parallel("parallel-lane-vs-carriageway"));
        fixtures.add(scaled("z13-coarse-step", index -> 10.0 * Math.sin(index * Math.PI / 30.0),
            0.78, 0.50, 5.0, 0.0));
        fixtures.add(scaled("z15-reference-step", index -> 10.0 * Math.sin(index * Math.PI / 30.0),
            0.78, 1.0, 2.0, 0.0));
        fixtures.add(scaled("z16-fine-step", index -> 10.0 * Math.sin(index * Math.PI / 30.0),
            0.78, 2.0, 1.0, 0.0));
        fixtures.add(single("protected-anchor-control", index -> 3.0 * Math.sin(index * Math.PI / 30.0), 0.82, 0.0)
            .withProtectedAnchors(List.of(0, 20, 40, 60)));
        fixtures.add(single("topology-crossing-control", index -> index < 30 ? -2.0 : 2.0, 0.82, 0.0)
            .withConnectedControl(List.of(
                new Point2D.Double(0.0, -8.0), new Point2D.Double(60.0, 8.0))));
        return List.copyOf(fixtures);
    }

    private static Fixture single(String name, IntToDoubleFunction centreMetres,
        double peakIntensity, double holeFraction) {
        return scaled(name, centreMetres, peakIntensity, METRES_PER_SOURCE_PIXEL, PROFILE_STEP_METRES,
            holeFraction);
    }

    private static Fixture scaled(String name, IntToDoubleFunction centreMetres,
        double peakIntensity, double metresPerSourcePixel, double profileStepMetres) {
        return scaled(name, centreMetres, peakIntensity, metresPerSourcePixel, profileStepMetres, 0.0);
    }

    private static Fixture scaled(String name, IntToDoubleFunction centreMetres,
        double peakIntensity, double metresPerSourcePixel, double profileStepMetres, double holeFraction) {
        return new Fixture(name, profiles(centreMetres, peakIntensity, holeFraction, false,
            metresPerSourcePixel, profileStepMetres), metresPerSourcePixel, profileStepMetres, List.of(), List.of());
    }

    private static Fixture parallel(String name) {
        return new Fixture(name, profiles(index -> 0.0, 0.80, 0.0, true),
            METRES_PER_SOURCE_PIXEL, PROFILE_STEP_METRES, List.of(), List.of());
    }

    private static List<RenderedHeatmapSampler.CrossSectionProfile> profiles(
        IntToDoubleFunction centreMetres, double peakIntensity, double holeFraction, boolean parallel) {
        return profiles(centreMetres, peakIntensity, holeFraction, parallel,
            METRES_PER_SOURCE_PIXEL, PROFILE_STEP_METRES);
    }

    private static List<RenderedHeatmapSampler.CrossSectionProfile> profiles(
        IntToDoubleFunction centreMetres, double peakIntensity, double holeFraction, boolean parallel,
        double metresPerSourcePixel, double profileStepMetres) {
        List<RenderedHeatmapSampler.CrossSectionProfile> result = new ArrayList<>();
        for (int index = 0; index < PROFILE_COUNT; index++) {
            double centrePx = centreMetres.applyAsDouble(index) / metresPerSourcePixel;
            boolean hole = holeFraction > 0.0 && index % Math.round(1.0 / holeFraction) == 0
                && index > 2 && index < PROFILE_COUNT - 3;
            List<RenderedHeatmapSampler.IntensitySample> samples = hole
                ? List.of()
                : samples(centrePx, peakIntensity, parallel, metresPerSourcePixel);
            List<RenderedHeatmapSampler.CrossSectionPeak> peaks = hole
                ? List.of()
                : parallel
                    ? List.of(peak(-5.0, peakIntensity), peak(5.0, peakIntensity * 0.96))
                    : List.of(peak(centrePx, peakIntensity));
            result.add(new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(index * profileStepMetres, 0.0),
                    index * profileStepMetres / metresPerSourcePixel, 0.0,
                    index * profileStepMetres),
                new Point2D.Double(0.0, 1.0), peaks, !hole, samples));
        }
        return List.copyOf(result);
    }

    private static List<RenderedHeatmapSampler.IntensitySample> samples(
        double centrePx, double peakIntensity, boolean parallel, double metresPerSourcePixel) {
        List<RenderedHeatmapSampler.IntensitySample> samples = new ArrayList<>();
        int halfWidthPx = (int) Math.ceil(18.0 / metresPerSourcePixel);
        for (int offset = -halfWidthPx; offset <= halfWidthPx; offset++) {
            double intensity = 0.02 + gaussian(offset, centrePx, peakIntensity);
            if (parallel) {
                intensity = Math.max(intensity, 0.02 + gaussian(offset, -5.0, peakIntensity)
                    + gaussian(offset, 5.0, peakIntensity * 0.96));
            }
            samples.add(new RenderedHeatmapSampler.IntensitySample(offset, intensity, intensity, intensity, true));
        }
        return List.copyOf(samples);
    }

    private static double gaussian(double offsetPx, double centrePx, double peakIntensity) {
        double distance = offsetPx - centrePx;
        return peakIntensity * Math.exp(-0.5 * distance * distance / 4.0);
    }

    private static RenderedHeatmapSampler.CrossSectionPeak peak(double offsetPx, double intensity) {
        return new RenderedHeatmapSampler.CrossSectionPeak(offsetPx, intensity, 4.0, false,
            intensity, 0.02, intensity, 0.8, 0.9, 0.95);
    }

    private static double switchback(int index) {
        int phase = index % 20;
        double rising = -8.0 + 16.0 * phase / 20.0;
        return (index / 20) % 2 == 0 ? rising : -rising;
    }

    record Fixture(String name, List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        double metresPerSourcePixel, double profileStepMetres, List<Integer> protectedAnchorIndexes,
        List<Point2D.Double> connectedControl) {
        Fixture {
            profiles = List.copyOf(profiles);
            protectedAnchorIndexes = List.copyOf(protectedAnchorIndexes);
            connectedControl = connectedControl.stream()
                .map(point -> new Point2D.Double(point.x, point.y)).toList();
        }

        Fixture withProtectedAnchors(List<Integer> indexes) {
            return new Fixture(name, profiles, metresPerSourcePixel, profileStepMetres, indexes, connectedControl);
        }

        Fixture withConnectedControl(List<Point2D.Double> control) {
            return new Fixture(name, profiles, metresPerSourcePixel, profileStepMetres,
                protectedAnchorIndexes, control);
        }
    }
}

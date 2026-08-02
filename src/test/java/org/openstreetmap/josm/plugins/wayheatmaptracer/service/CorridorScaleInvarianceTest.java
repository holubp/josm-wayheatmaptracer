package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

/** Verifies that corridor optimization is invariant to sampled-raster scale. */
class CorridorScaleInvarianceTest {
    private static final int PROFILE_COUNT = 48;

    @Test
    void suppressesAlternatingPlateauAliasingAtRealisticRasterScales() {
        List<List<Double>> normalizedOffsets = new ArrayList<>();
        for (double scale : List.of(1.0, 6.0, 24.0)) {
            Scenario scenario = broadDiagonalPlateau(scale);
            CorridorCenterlineOptimizer.OptimizationResult result = new CorridorCenterlineOptimizer()
                .optimize(scenario.track(), scenario.profiles(), scale);
            List<Double> offsets = result.offsetsPx().stream().map(value -> value / scale).toList();
            normalizedOffsets.add(offsets);

            double rms = highFrequencyRms(offsets);
            double p95 = highFrequencyP95(offsets);
            double meanBias = Math.abs(offsets.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
            assertTrue(rms <= 0.15, "scale=" + scale + " high-frequency RMS=" + rms);
            assertTrue(p95 <= 0.25, "scale=" + scale + " high-frequency p95=" + p95);
            assertTrue(meanBias <= 0.25, "scale=" + scale + " mean center bias=" + meanBias);
        }

        List<Double> reference = normalizedOffsets.get(0);
        for (int scaleIndex = 1; scaleIndex < normalizedOffsets.size(); scaleIndex++) {
            List<Double> actual = normalizedOffsets.get(scaleIndex);
            double maximumDifference = 0.0;
            for (int i = 0; i < reference.size(); i++) {
                maximumDifference = Math.max(maximumDifference, Math.abs(reference.get(i) - actual.get(i)));
            }
            assertTrue(maximumDifference <= 0.25,
                "normalized result changed by " + maximumDifference + " source pixels");
        }
    }

    @Test
    void actualDiagonalGeometryDoesNotAcquireRasterScaleDependentSawteeth() {
        for (double scale : List.of(1.0, 6.0, 24.0)) {
            Scenario scenario = broadDiagonalPlateau(scale);
            CorridorCenterlineOptimizer.OptimizationResult result = new CorridorCenterlineOptimizer()
                .optimize(scenario.track(), scenario.profiles(), scale);
            List<Double> crossTrack = new ArrayList<>();
            Point2D.Double normal = new Point2D.Double(-0.6, 0.8);
            for (int i = 0; i < result.screenPoints().size(); i++) {
                Point2D.Double anchor = scenario.profiles().get(i).source().anchorScreen();
                Point2D.Double point = result.screenPoints().get(i);
                crossTrack.add(((point.x - anchor.x) * normal.x + (point.y - anchor.y) * normal.y) / scale);
            }
            assertTrue(highFrequencyRms(crossTrack) <= 0.15,
                "scale=" + scale + " geometric high-frequency RMS=" + highFrequencyRms(crossTrack));
        }
    }

    private Scenario broadDiagonalPlateau(double scale) {
        List<CorridorProfile> profiles = new ArrayList<>();
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        Point2D.Double tangent = new Point2D.Double(0.8, 0.6);
        Point2D.Double normal = new Point2D.Double(-0.6, 0.8);
        for (int i = 0; i < PROFILE_COUNT; i++) {
            double nominalCenter = (i % 2 == 0 ? -0.75 : 0.75) * scale;
            CorridorBand band = new CorridorBand("band", nominalCenter, -4.0 * scale, 4.0 * scale,
                -2.0 * scale, 2.0 * scale, List.of(nominalCenter), 1.0, 0.02, 0.98,
                0.96, 0.72, 0.7 * scale, false, List.of());
            List<IntensitySample> samples = new ArrayList<>();
            for (int sourceOffset = -5; sourceOffset <= 5; sourceOffset++) {
                double intensity = Math.abs(sourceOffset) <= 2 ? 0.96
                    : (Math.abs(sourceOffset) <= 4 ? 0.58 : 0.04);
                if (sourceOffset == (i % 2 == 0 ? -1 : 1)) {
                    intensity = 1.0;
                }
                samples.add(new IntensitySample(sourceOffset * scale, intensity, 0.96, 0.96, true));
            }
            Point2D.Double anchor = new Point2D.Double(
                tangent.x * i * 4.0 * scale,
                tangent.y * i * 4.0 * scale
            );
            RenderedHeatmapSampler.CrossSectionProfile source = new RenderedHeatmapSampler.CrossSectionProfile(
                new EastNorth(anchor.x, anchor.y), anchor, normal, List.of(), true, samples);
            profiles.add(new CorridorProfile(i, source, List.of(band), 1.0, 0.02, 0.98, true));
            points.put(i, new CorridorTrackPoint(i, band, false));
        }
        return new Scenario(new CorridorTrack("track", points, PROFILE_COUNT, 1.0, false, List.of(), ""), profiles);
    }

    private double highFrequencyRms(List<Double> values) {
        if (values.size() < 3) {
            return 0.0;
        }
        double sumSquares = 0.0;
        for (int i = 1; i < values.size() - 1; i++) {
            double residual = values.get(i) - (values.get(i - 1) + values.get(i + 1)) / 2.0;
            sumSquares += residual * residual;
        }
        return Math.sqrt(sumSquares / (values.size() - 2));
    }

    private double highFrequencyP95(List<Double> values) {
        List<Double> residuals = new ArrayList<>();
        for (int i = 1; i < values.size() - 1; i++) {
            residuals.add(Math.abs(values.get(i) - (values.get(i - 1) + values.get(i + 1)) / 2.0));
        }
        residuals.sort(Double::compareTo);
        return residuals.isEmpty() ? 0.0 : residuals.get((int) Math.floor(0.95 * (residuals.size() - 1)));
    }

    private record Scenario(CorridorTrack track, List<CorridorProfile> profiles) {
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

/** Guards the bounded in-memory scale-space implementation on a representative long crop. */
class CorridorScaleSpacePerformanceTest {
    @Test
    void representativeLongCropStaysWithinMemoryAndGenerousRuntimeCeilings() {
        BufferedImage raster = new BufferedImage(1024, 384, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < raster.getWidth(); x++) {
            raster.setRGB(x, 190 + (int) Math.round(8.0 * Math.sin(x / 80.0)), 0xFFFFFFFF);
        }
        ScalarIntensityField field = ScalarIntensityField.fromRaster(raster, 0, 0,
            raster.getWidth() - 1, raster.getHeight() - 1, "hot", IntensitySamplingMode.DIRECT_VALUE);

        long started = System.nanoTime();
        GaussianIntensityPyramid pyramid = GaussianIntensityPyramid.build(field, 32);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(pyramid.estimatedBytes() < GaussianIntensityPyramid.MAX_ESTIMATED_BYTES,
            "estimated bytes=" + pyramid.estimatedBytes());
        assertTrue(elapsedMillis < 5_000L, "pyramid build took " + elapsedMillis + " ms");
    }

    @Test
    void exactPairStateOptimizerRemainsBoundedForFourHundredProfiles() {
        List<CorridorProfile> profiles = new ArrayList<>();
        var points = new LinkedHashMap<Integer, CorridorTrackPoint>();
        for (int index = 0; index < 400; index++) {
            double center = 2.0 * Math.sin(index / 40.0);
            CorridorBand band = new CorridorBand("band", center, center - 5.0, center + 5.0,
                center - 2.0, center + 2.0, List.of(center), 1.0, 0.02, 1.0,
                0.9, 0.65, 0.6, false, List.of());
            List<IntensitySample> samples = new ArrayList<>();
            for (int offset = -6; offset <= 6; offset++) {
                double distance = Math.abs(offset - center);
                double intensity = distance <= 2.0 ? 0.95 : (distance <= 5.0 ? 0.5 : 0.02);
                samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
            }
            var source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(index * 1.5, 0.0), index * 4.0, 0.0, index * 1.5),
                new Point2D.Double(0.0, 1.0), List.of(), true, samples);
            profiles.add(new CorridorProfile(index, source, List.of(band), 1.0, 0.02, 0.98, true));
            points.put(index, new CorridorTrackPoint(index, band, false));
        }
        CorridorTrack track = new CorridorTrack("track", points, 400.0, 1.0, false, List.of(), "");

        long started = System.nanoTime();
        var result = new CorridorCenterlineOptimizer().optimize(track, profiles, 1.0);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(result.offsetsPx().size() == 400);
        assertTrue(result.maximumOffsetStates() <= 21,
            "maximum offset states=" + result.maximumOffsetStates());
        assertTrue(result.maximumPairStates() <= 21 * 21,
            "maximum pair states=" + result.maximumPairStates());
        assertTrue(result.transitionEvaluations() <= 400L * 21L * 21L * 21L,
            "transition evaluations=" + result.transitionEvaluations());
        assertTrue(elapsedMillis < 10_000L, "exact optimizer took " + elapsedMillis + " ms");
    }
}

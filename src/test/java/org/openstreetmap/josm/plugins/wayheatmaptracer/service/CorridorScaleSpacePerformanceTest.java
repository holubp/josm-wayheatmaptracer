package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;

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
}

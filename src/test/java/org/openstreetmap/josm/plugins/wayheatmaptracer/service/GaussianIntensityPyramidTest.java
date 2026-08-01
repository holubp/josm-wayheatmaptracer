package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;

/** Tests deterministic filtering, decimation, and L0 coordinate transforms. */
class GaussianIntensityPyramidTest {
    @Test
    void symmetricImpulseRetainsItsL0CenterAcrossLevels() {
        BufferedImage raster = new BufferedImage(33, 33, BufferedImage.TYPE_INT_ARGB);
        raster.setRGB(16, 16, 0xFFFFFFFF);
        ScalarIntensityField levelZero = ScalarIntensityField.fromRaster(
            raster, 0, 0, 32, 32, "hot", IntensitySamplingMode.DIRECT_VALUE);

        GaussianIntensityPyramid pyramid = GaussianIntensityPyramid.build(levelZero);

        assertEquals(3, pyramid.levels().size());
        for (IntensityScaleLevel level : pyramid.levels()) {
            double center = level.field().sample(16.0, 16.0);
            double left = level.field().sample(16.0 - level.reduction(), 16.0);
            double right = level.field().sample(16.0 + level.reduction(), 16.0);
            assertTrue(center > left, "level " + level.level() + " center=" + center + " left=" + left);
            assertEquals(left, right, 1e-9, "level " + level.level() + " shifted the impulse");
        }
    }

    @Test
    void missingCropHaloStaysInvalidInsteadOfBecomingAFalseDarkEdge() {
        BufferedImage raster = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                raster.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        GaussianIntensityPyramid pyramid = GaussianIntensityPyramid.build(ScalarIntensityField.fromRaster(
            raster, 0, 0, 15, 15, "hot", IntensitySamplingMode.DIRECT_VALUE));

        assertTrue(Double.isNaN(pyramid.levels().get(1).field().sample(0.0, 0.0)));
        assertEquals(1.0, pyramid.levels().get(1).field().sample(8.0, 8.0), 1e-9);
        assertTrue(pyramid.estimatedBytes() < GaussianIntensityPyramid.MAX_ESTIMATED_BYTES);
    }

    @Test
    void decimationPhaseIsStableWhenTheCropOriginChanges() {
        BufferedImage raster = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        raster.setRGB(16, 16, 0xFFFFFFFF);
        GaussianIntensityPyramid evenCrop = GaussianIntensityPyramid.build(ScalarIntensityField.fromRaster(
            raster, 0, 0, 36, 36, "hot", IntensitySamplingMode.DIRECT_VALUE));
        GaussianIntensityPyramid oddCrop = GaussianIntensityPyramid.build(ScalarIntensityField.fromRaster(
            raster, 1, 1, 37, 37, "hot", IntensitySamplingMode.DIRECT_VALUE));

        for (int level = 1; level <= 2; level++) {
            assertEquals(evenCrop.levels().get(level).field().sample(16.0, 16.0),
                oddCrop.levels().get(level).field().sample(16.0, 16.0), 1e-9,
                "crop origin changed level " + level + " phase");
        }
    }
}

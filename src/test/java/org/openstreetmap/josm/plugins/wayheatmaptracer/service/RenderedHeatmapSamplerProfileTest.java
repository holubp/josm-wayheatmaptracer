package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;

class RenderedHeatmapSamplerProfileTest {
    @Test
    void retainsNativeAndBothFilteredIntensityScales() {
        BufferedImage image = new BufferedImage(21, 21, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            image.setRGB(10, y, 0xFFFFFFFF);
            image.setRGB(9, y, 0xFF808080);
            image.setRGB(11, y, 0xFF808080);
        }
        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();

        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = sampler.sampleProfilesOnScaledRaster(
            image,
            List.of(new Point2D.Double(10, 5), new Point2D.Double(10, 15)),
            3,
            1,
            "hot",
            1.0,
            1.0,
            IntensitySamplingMode.DIRECT_VALUE
        );

        assertEquals(2, profiles.size());
        assertEquals(7, profiles.get(0).intensitySamples().size());
        RenderedHeatmapSampler.IntensitySample center = profiles.get(0).intensitySamples().get(3);
        assertEquals(0.0, center.offsetPx(), 1e-9);
        assertEquals(1.0, center.nativeIntensity(), 1e-9);
        assertTrue(center.lightFilteredIntensity() > 0.5);
        assertTrue(center.standardFilteredIntensity() > 0.5);
        assertTrue(center.insideRaster());
    }
}

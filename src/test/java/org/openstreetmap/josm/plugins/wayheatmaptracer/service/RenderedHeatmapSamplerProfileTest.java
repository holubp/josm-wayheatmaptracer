package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;

class RenderedHeatmapSamplerProfileTest {
    @BeforeAll
    static void setProjection() {
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void physicalProfileDistanceIsIndependentOfRasterScale() {
        var projection = ProjectionRegistry.getProjection();
        List<org.openstreetmap.josm.data.coor.EastNorth> source = List.of(
            projection.latlon2eastNorth(new LatLon(49.44, 15.95)),
            projection.latlon2eastNorth(new LatLon(49.4400234, 15.95))
        );

        List<ProfileSamplingAnchor> scaleOne = ProfileSamplingAnchor.pair(source,
            List.of(new Point2D.Double(10.0, 10.0), new Point2D.Double(50.0, 10.0)));
        List<ProfileSamplingAnchor> scaleSix = ProfileSamplingAnchor.pair(source,
            List.of(new Point2D.Double(60.0, 60.0), new Point2D.Double(300.0, 60.0)));

        assertEquals(scaleOne.get(1).cumulativeGroundDistanceMeters(),
            scaleSix.get(1).cumulativeGroundDistanceMeters(), 1e-9);
        assertTrue(scaleOne.get(1).cumulativeGroundDistanceMeters() > 2.5);
        assertTrue(scaleOne.get(1).cumulativeGroundDistanceMeters() < 2.7);
        assertEquals(40.0, scaleOne.get(0).rasterCoordinate().distance(scaleOne.get(1).rasterCoordinate()), 1e-9);
    }

    @Test
    void renderedFallbackSelectsCoarseLevelsFromEstimatedSourcePixelPitch() {
        BufferedImage raster = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB);
        for (int y = 82; y <= 98; y++) {
            for (int x = 30; x < 290; x++) {
                raster.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        MultiScaleProfileSet result = new RenderedHeatmapSampler().sampleMultiScaleProfilesOnScaledRaster(
            raster,
            List.of(new Point2D.Double(50.0, 90.0), new Point2D.Double(270.0, 90.0)),
            12,
            1,
            "hot",
            1.0,
            1.0,
            IntensitySamplingMode.DIRECT_VALUE,
            6.0
        );

        assertEquals(List.of(1, 8, 16), result.levels().stream().map(MultiScaleProfileSet.ScaleProfileLevel::reduction).toList());
        assertTrue(result.levelZeroProfiles().stream().allMatch(RenderedHeatmapSampler.CrossSectionProfile::anchorWithinRaster));
    }

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

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.openstreetmap.josm.data.coor.EastNorth;
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
        assertFalse(profiles.get(0).projectedLateralTransform().isPresent());
        RenderedHeatmapSampler.IntensitySample center = profiles.get(0).intensitySamples().get(3);
        assertEquals(0.0, center.offsetPx(), 1e-9);
        assertEquals(1.0, center.nativeIntensity(), 1e-9);
        assertTrue(center.lightFilteredIntensity() > 0.5);
        assertTrue(center.standardFilteredIntensity() > 0.5);
        assertTrue(center.insideRaster());
    }

    @Test
    void outsideSignalCannotExpandOrInfluenceDecisionProfile() {
        BufferedImage image = new BufferedImage(25, 25, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            image.setRGB(15, y, 0xFFFFFFFF);
        }

        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = new RenderedHeatmapSampler()
            .sampleProfilesOnScaledRaster(
                image,
                List.of(new Point2D.Double(10, 5), new Point2D.Double(10, 20)),
                3,
                1,
                "hot",
                1.0,
                1.0,
                IntensitySamplingMode.DIRECT_VALUE
            );

        List<RenderedHeatmapSampler.IntensitySample> samples = profiles.get(0).intensitySamples();
        assertEquals(7, samples.size());
        assertEquals(-3.0, samples.get(0).offsetPx(), 1e-9);
        assertEquals(3.0, samples.get(samples.size() - 1).offsetPx(), 1e-9);
        assertEquals(samples.get(0).nativeIntensity(), samples.get(0).standardFilteredIntensity(), 0.0);
    }

    @Test
    void capturesExactSlideTimeProjectedLateralOffsetTransform() {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        List<ProfileSamplingAnchor> anchors = ProfileSamplingAnchor.pair(
            List.of(new EastNorth(500.0, 700.0), new EastNorth(500.0, 710.0)),
            List.of(new Point2D.Double(10.0, 5.0), new Point2D.Double(10.0, 25.0)));

        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = new RenderedHeatmapSampler()
            .sampleProfilesOnAnchors(
                image,
                anchors,
                3,
                1,
                "hot",
                1.0,
                1.0,
                IntensitySamplingMode.DIRECT_VALUE,
                (rasterX, rasterY) -> new EastNorth(100.0 + 2.0 * rasterX, 200.0 - 3.0 * rasterY)
            );

        var transform = profiles.get(0).projectedLateralTransform().orElseThrow();
        assertEquals(new EastNorth(120.0, 185.0), transform.atOffset(0.0));
        assertEquals(new EastNorth(115.0, 185.0), transform.atOffset(2.5));
        assertEquals(new EastNorth(124.0, 185.0), transform.atOffset(-2.0));
    }

    @Test
    void compatibilityProfilesExposeUnavailableProjectedTransform() {
        var profile = new RenderedHeatmapSampler.CrossSectionProfile(
            new EastNorth(1.0, 2.0),
            new Point2D.Double(3.0, 4.0),
            new Point2D.Double(0.0, 1.0),
            List.of()
        );

        assertFalse(profile.projectedLateralTransform().isPresent());
    }

    @Test
    void multiScaleProfilesReuseTheSameCapturedProjectionTransform() {
        BufferedImage image = new BufferedImage(80, 50, BufferedImage.TYPE_INT_ARGB);
        List<ProfileSamplingAnchor> anchors = ProfileSamplingAnchor.pair(
            List.of(new EastNorth(0.0, 0.0), new EastNorth(20.0, 0.0)),
            List.of(new Point2D.Double(15.0, 20.0), new Point2D.Double(65.0, 20.0)));

        MultiScaleProfileSet profileSet = new RenderedHeatmapSampler().sampleMultiScaleProfilesOnAnchors(
            image,
            anchors,
            4,
            1,
            "hot",
            1.0,
            1.0,
            IntensitySamplingMode.DIRECT_VALUE,
            1.0,
            (rasterX, rasterY) -> new EastNorth(10.0 + 0.5 * rasterX, 30.0 - 0.25 * rasterY)
        );

        for (MultiScaleProfileSet.ScaleProfileLevel level : profileSet.levels()) {
            var transform = level.profiles().get(0).projectedLateralTransform().orElseThrow();
            assertEquals(new EastNorth(17.5, 25.0), transform.atOffset(0.0));
            assertEquals(new EastNorth(17.5, 24.5), transform.atOffset(2.0));
        }
    }
}

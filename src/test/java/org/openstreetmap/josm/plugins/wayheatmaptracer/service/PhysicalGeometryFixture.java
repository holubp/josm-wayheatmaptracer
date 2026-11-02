package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;

/** Independent straight image ridge sampled through regular or folded source-normal charts. */
final class PhysicalGeometryFixture {
    static final double CENTER = 64.0;
    static final double DISPLACEMENT = 9.7;

    private PhysicalGeometryFixture() { }

    /** One fixture pixel represents one metre; image truth is y=64 independently of source geometry. */
    static List<RenderedHeatmapSampler.CrossSectionProfile> straightRidgeThroughParabola(double radius) {
        BufferedImage raster = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < raster.getHeight(); y++) {
            int value = (int) Math.round(255.0 * Math.exp(-0.5 * Math.pow((y - CENTER) / 0.8, 2)));
            for (int x = 0; x < raster.getWidth(); x++) {
                raster.setRGB(x, y, 0xff000000 | value << 16 | value << 8 | value);
            }
        }
        List<Point2D.Double> anchors = new ArrayList<>();
        // Pad the range so every observed profile has the same central-secant normal rule.
        for (int j = -9; j <= 9; j++) {
            anchors.add(new Point2D.Double(CENTER + j,
                    CENTER - DISPLACEMENT + j * j / (2.0 * radius)));
        }
        return new RenderedHeatmapSampler().sampleMultiScaleProfilesOnScaledRaster(
                raster, anchors, 25, 1, "hot", 1.0, 1.0,
                IntensitySamplingMode.DIRECT_VALUE, 1.0).levelZeroProfiles().subList(1, 18);
    }

    static Point2D.Double strongestObservedPoint(RenderedHeatmapSampler.CrossSectionProfile profile) {
        var peak = profile.peaks().stream().filter(p -> !p.syntheticCenter())
                .max(java.util.Comparator.comparingDouble(RenderedHeatmapSampler.CrossSectionPeak::intensity))
                .orElseThrow();
        return new Point2D.Double(profile.anchorScreen().x + profile.normalScreen().x * peak.offsetPx(),
                profile.anchorScreen().y + profile.normalScreen().y * peak.offsetPx());
    }
}

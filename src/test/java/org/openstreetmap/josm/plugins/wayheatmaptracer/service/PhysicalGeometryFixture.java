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
    static final double NATIVE_PITCH = 1.556;

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

    /** Paints an independent 150-degree arc opposite the source, within the explicit 25m search. */
    static List<RenderedHeatmapSampler.CrossSectionProfile> supportedOppositeArcProfiles() {
        double radius = 4 * NATIVE_PITCH;
        double first = Math.toRadians(105), last = Math.toRadians(255);
        BufferedImage raster = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                double dx = x - CENTER, dy = y - CENTER;
                double angle = Math.atan2(dy, dx);
                if (angle < 0) angle += 2 * Math.PI;
                double distance = angle >= first && angle <= last ? Math.abs(Math.hypot(dx, dy) - radius)
                        : Math.min(Math.hypot(dx - radius * Math.cos(first), dy - radius * Math.sin(first)),
                                Math.hypot(dx - radius * Math.cos(last), dy - radius * Math.sin(last)));
                int value = (int) Math.round(255 * Math.exp(-0.5 * distance * distance / (0.7 * 0.7)));
                raster.setRGB(x, y, 0xff000000 | value << 16 | value << 8 | value);
            }
        }
        var anchors = new ArrayList<Point2D.Double>();
        for (int i = 0; i <= 40; i++) {
            double a = Math.toRadians(-75 + 150.0 * i / 40);
            anchors.add(new Point2D.Double(CENTER + 8 * NATIVE_PITCH * Math.cos(a),
                    CENTER + 8 * NATIVE_PITCH * Math.sin(a)));
        }
        return new RenderedHeatmapSampler().sampleMultiScaleProfilesOnScaledRaster(raster, anchors,
                25, 1, "hot", 1, 1, IntensitySamplingMode.DIRECT_VALUE, NATIVE_PITCH).levelZeroProfiles();
    }
}

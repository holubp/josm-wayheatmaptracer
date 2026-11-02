package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;

/** Independent image controls for retaining real bends without freezing isolated native noise. */
class RidgeTrackerRasterSupportTest {
    @Test
    void supportedStraypointMustNotOverridePersistentStraightTraceAcrossShortGap() {
        var profiles = sample(false);
        var candidate = new RidgeTracker().track(profiles).get(0);
        int nearGap = java.util.stream.IntStream.range(0, profiles.size()).boxed()
                .min(java.util.Comparator.comparingDouble(i -> Math.abs(profiles.get(i).anchorScreen().x - 66)))
                .orElseThrow();
        assertTrue(Math.abs(candidate.offsetsPx().get(nearGap)) <= 4,
                "Native-positive alone must not freeze an isolated excursion: " + candidate.offsetsPx());
    }

    @Test
    void supportedMultiProfileBendMustNotBeFlattened() {
        var candidate = new RidgeTracker().track(sample(true)).get(0);
        assertTrue(candidate.offsetsPx().stream().mapToDouble(Double::doubleValue).max().orElseThrow() >= 7,
                "The independently drawn nine-pixel bend must retain its apex");
    }

    private static java.util.List<RenderedHeatmapSampler.CrossSectionProfile> sample(boolean bend) {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                double intensity;
                if (bend) {
                    double center = 64 + 9 * StrictMath.sin((x - 16) * StrictMath.PI / 96);
                    intensity = 0.95 * gaussian(y - center, 3);
                } else {
                    double dx = x - 64;
                    double trace = Math.abs(dx) > 8 ? 0.72 * gaussian(y - 64, 1) : 0;
                    intensity = Math.max(trace, 0.98 * gaussian(dx, 3.2) * gaussian(y - 74, 1));
                }
                int v = (int) Math.round(255 * intensity);
                image.setRGB(x, y, 0xff000000 | v << 16 | v << 8 | v);
            }
        }
        var anchors = new ArrayList<Point2D.Double>();
        for (int x = 16; x <= 112; x += 12) anchors.add(new Point2D.Double(x, 64));
        return new RenderedHeatmapSampler().sampleMultiScaleProfilesOnScaledRaster(image, anchors,
                25, 1, "hot", 1, 1, IntensitySamplingMode.DIRECT_VALUE, 1).levelZeroProfiles();
    }

    private static double gaussian(double d, double sigma) {
        return StrictMath.exp(-0.5 * d * d / (sigma * sigma));
    }
}

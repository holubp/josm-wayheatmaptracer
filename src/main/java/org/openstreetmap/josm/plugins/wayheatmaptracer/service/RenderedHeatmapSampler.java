package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

public final class RenderedHeatmapSampler {
    public static final double RASTER_SCALE = 6.0;

    public BufferedImage captureLayer(ImageryLayer imageryLayer, MapView mapView) {
        PluginLog.verbose("Capturing heatmap layer '%s' into off-screen raster %dx%d.",
            imageryLayer.getName(),
            (int) Math.round(mapView.getWidth() * RASTER_SCALE),
            (int) Math.round(mapView.getHeight() * RASTER_SCALE));
        BufferedImage image = new BufferedImage(
            (int) Math.round(mapView.getWidth() * RASTER_SCALE),
            (int) Math.round(mapView.getHeight() * RASTER_SCALE),
            BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.scale(RASTER_SCALE, RASTER_SCALE);
            graphics.setClip(0, 0, mapView.getWidth(), mapView.getHeight());
            mapView.paintLayer(imageryLayer, graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    public List<CrossSectionProfile> sampleProfiles(
        BufferedImage raster,
        MapView mapView,
        List<EastNorth> sourcePolyline,
        int halfWidthPx,
        int stepPx,
        String colorMode
    ) {
        List<EastNorth> dense = PolylineMath.resampleBySpacing(sourcePolyline, 10.0);
        if (dense.size() < 2) {
            return Collections.emptyList();
        }
        List<Point2D.Double> denseScreen = new ArrayList<>(dense.size());
        for (EastNorth point : dense) {
            denseScreen.add(toRasterScreen(point, mapView));
        }
        PluginLog.verbose("Sampling %d cross-sections from %d source vertices (halfWidth=%d px, step=%d px).",
            dense.size(), sourcePolyline.size(), halfWidthPx, stepPx);
        return sampleProfilesOnRaster(raster, denseScreen, halfWidthPx, stepPx, colorMode, RASTER_SCALE);
    }

    List<CrossSectionProfile> sampleProfilesOnRaster(
        BufferedImage raster,
        List<Point2D.Double> denseScreenPolyline,
        int halfWidthPx,
        int stepPx,
        String colorMode,
        double rasterScale
    ) {
        if (denseScreenPolyline.size() < 2) {
            return Collections.emptyList();
        }
        List<CrossSectionProfile> profiles = new ArrayList<>();
        for (int i = 0; i < denseScreenPolyline.size(); i++) {
            Point2D.Double current = denseScreenPolyline.get(i);
            Point2D.Double prevScreen = denseScreenPolyline.get(Math.max(0, i - 1));
            Point2D.Double nextScreen = denseScreenPolyline.get(Math.min(denseScreenPolyline.size() - 1, i + 1));
            Point2D.Double tangent = PolylineMath.normalize(nextScreen.x - prevScreen.x, nextScreen.y - prevScreen.y);
            Point2D.Double normal = new Point2D.Double(-tangent.y, tangent.x);
            Point2D.Double baseScreen = current;

            List<CrossSectionPeak> samples = new ArrayList<>();
            List<OffsetSample> offsets = new ArrayList<>();
            int scaledHalfWidth = Math.max(1, (int) Math.round(halfWidthPx * rasterScale));
            int scaledStep = Math.max(1, (int) Math.round(stepPx * rasterScale));
            for (int offset = -scaledHalfWidth; offset <= scaledHalfWidth; offset += scaledStep) {
                double x = baseScreen.x + normal.x * offset;
                double y = baseScreen.y + normal.y * offset;
                double intensity = intensityAt(raster, x, y, colorMode);
                offsets.add(new OffsetSample(offset, intensity));
            }
            samples.addAll(extractBrightBands(offsets));
            if (samples.isEmpty()) {
                double strongest = offsets.stream().mapToDouble(OffsetSample::intensity).max().orElse(0.0);
                samples.add(new CrossSectionPeak(0.0, strongest));
            }

            profiles.add(new CrossSectionProfile(new EastNorth(baseScreen.x, baseScreen.y), baseScreen, normal, samples));
        }
        if (!profiles.isEmpty()) {
            int maxPeaks = profiles.stream().mapToInt(profile -> profile.peaks().size()).max().orElse(0);
            PluginLog.verbose("Heatmap sampling produced %d profiles; max peaks on one profile=%d.", profiles.size(), maxPeaks);
            int logCount = PluginPreferences.isDebugEnabled() ? profiles.size() : Math.min(5, profiles.size());
            for (int i = 0; i < logCount; i++) {
                CrossSectionProfile profile = profiles.get(i);
                PluginLog.debug("Profile[%d] anchorScreen=(%.1f,%.1f) bands=%s", i, profile.anchorScreen().x, profile.anchorScreen().y,
                    profile.peaks().stream().map(peak -> String.format("%.1f@%.2f", peak.offsetPx(), peak.intensity())).toList());
            }
        }
        return profiles;
    }

    private Point2D.Double toRasterScreen(EastNorth point, MapView mapView) {
        Point2D point2D = mapView.getPoint2D(point);
        return new Point2D.Double(point2D.getX() * RASTER_SCALE, point2D.getY() * RASTER_SCALE);
    }

    private List<CrossSectionPeak> extractBrightBands(List<OffsetSample> offsets) {
        double maxIntensity = offsets.stream().mapToDouble(OffsetSample::intensity).max().orElse(0.0);
        if (maxIntensity <= 0.18) {
            return List.of();
        }

        List<OffsetSample> smoothed = smoothProfile(offsets);
        double localPeakThreshold = Math.max(0.22, maxIntensity * 0.52);
        List<CrossSectionPeak> peaks = new ArrayList<>();
        for (int index = 0; index < smoothed.size(); index++) {
            double current = smoothed.get(index).intensity;
            double previous = index == 0 ? current : smoothed.get(index - 1).intensity;
            double next = index == smoothed.size() - 1 ? current : smoothed.get(index + 1).intensity;
            if (current < localPeakThreshold || current < previous || current < next) {
                continue;
            }

            int start = index;
            int end = index;
            double shoulderThreshold = Math.max(0.16, current * 0.60);
            while (start > 0 && smoothed.get(start - 1).intensity >= shoulderThreshold) {
                start--;
            }
            while (end + 1 < smoothed.size() && smoothed.get(end + 1).intensity >= shoulderThreshold) {
                end++;
            }
            peaks.add(buildBandPeak(offsets, smoothed, index, start, end));
        }

        if (peaks.isEmpty()) {
            int strongest = 0;
            for (int i = 1; i < smoothed.size(); i++) {
                if (smoothed.get(i).intensity > smoothed.get(strongest).intensity) {
                    strongest = i;
                }
            }
            peaks.add(buildBandPeak(offsets, smoothed, strongest, strongest, strongest));
        }
        List<CrossSectionPeak> merged = mergeClosePeaks(peaks, estimateSampleStep(offsets));
        return addPairedShoulderCenters(merged, estimateSampleStep(offsets));
    }

    private List<CrossSectionPeak> addPairedShoulderCenters(List<CrossSectionPeak> peaks, double sampleStep) {
        if (peaks.size() < 2) {
            return peaks;
        }
        List<CrossSectionPeak> augmented = new ArrayList<>(peaks);
        for (int i = 0; i < peaks.size(); i++) {
            CrossSectionPeak left = peaks.get(i);
            if (left.offsetPx() >= 0.0) {
                continue;
            }
            for (int j = i + 1; j < peaks.size(); j++) {
                CrossSectionPeak right = peaks.get(j);
                if (right.offsetPx() <= 0.0) {
                    continue;
                }
                double gap = right.offsetPx() - left.offsetPx();
                double weaker = Math.min(left.intensity(), right.intensity());
                double stronger = Math.max(left.intensity(), right.intensity());
                boolean balanced = stronger == 0.0 || weaker / stronger >= 0.55;
                if (balanced && weaker >= 0.32 && gap >= sampleStep * 1.5 && gap <= sampleStep * 6.0) {
                    double center = (left.offsetPx() * right.intensity() + right.offsetPx() * left.intensity())
                        / (left.intensity() + right.intensity());
                    augmented.add(new CrossSectionPeak(center, weaker * 0.93));
                }
            }
        }
        return mergeClosePeaks(augmented, sampleStep);
    }

    private CrossSectionPeak buildBandPeak(List<OffsetSample> offsets, List<OffsetSample> smoothed, int peakIndex, int start, int end) {
        double weightedOffset = 0.0;
        double weightSum = 0.0;
        double peakIntensity = 0.0;
        for (int i = start; i <= end; i++) {
            double weight = smoothed.get(i).intensity;
            weightedOffset += offsets.get(i).offsetPx * weight;
            weightSum += weight;
            peakIntensity = Math.max(peakIntensity, offsets.get(i).intensity);
        }
        double midpoint = (offsets.get(start).offsetPx + offsets.get(end).offsetPx) / 2.0;
        double weightedCenter = weightSum == 0.0 ? midpoint : weightedOffset / weightSum;
        double center = midpoint * 0.70 + weightedCenter * 0.30;
        double supportWidth = Math.abs(offsets.get(end).offsetPx - offsets.get(start).offsetPx);
        double confidence = Math.min(1.0, peakIntensity * (0.88 + Math.min(0.12, supportWidth / 80.0)));
        if (Math.abs(center - offsets.get(peakIndex).offsetPx) > supportWidth * 0.75 + estimateSampleStep(offsets)) {
            center = offsets.get(peakIndex).offsetPx;
        }
        return new CrossSectionPeak(center, confidence);
    }

    private List<OffsetSample> smoothProfile(List<OffsetSample> offsets) {
        if (offsets.size() < 3) {
            return offsets;
        }
        List<OffsetSample> smoothed = new ArrayList<>(offsets.size());
        for (int i = 0; i < offsets.size(); i++) {
            double center = offsets.get(i).intensity;
            double left = i == 0 ? center : offsets.get(i - 1).intensity;
            double right = i == offsets.size() - 1 ? center : offsets.get(i + 1).intensity;
            smoothed.add(new OffsetSample(offsets.get(i).offsetPx, 0.20 * left + 0.60 * center + 0.20 * right));
        }
        return smoothed;
    }

    private List<CrossSectionPeak> mergeClosePeaks(List<CrossSectionPeak> peaks, double sampleStep) {
        if (peaks.size() < 2) {
            return peaks;
        }
        List<CrossSectionPeak> sorted = new ArrayList<>(peaks);
        sorted.sort(java.util.Comparator.comparingDouble(CrossSectionPeak::offsetPx));
        List<CrossSectionPeak> merged = new ArrayList<>();
        CrossSectionPeak current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            CrossSectionPeak next = sorted.get(i);
            if (Math.abs(next.offsetPx() - current.offsetPx()) <= sampleStep * 1.5) {
                double total = current.intensity() + next.intensity();
                double center = total == 0.0
                    ? (current.offsetPx() + next.offsetPx()) / 2.0
                    : (current.offsetPx() * current.intensity() + next.offsetPx() * next.intensity()) / total;
                current = new CrossSectionPeak(center, Math.max(current.intensity(), next.intensity()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private double estimateSampleStep(List<OffsetSample> offsets) {
        if (offsets.size() < 2) {
            return 1.0;
        }
        return Math.abs(offsets.get(1).offsetPx - offsets.get(0).offsetPx);
    }

    private double intensityAt(BufferedImage image, double x, double y, String colorMode) {
        int sx = (int) Math.round(x);
        int sy = (int) Math.round(y);
        if (sx < 0 || sy < 0 || sx >= image.getWidth() || sy >= image.getHeight()) {
            return 0.0;
        }
        int argb = image.getRGB(sx, sy);
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha == 0) {
            return 0.0;
        }
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;

        return colorIntensity(red, green, blue, colorMode);
    }

    static double colorIntensity(int red, int green, int blue, String colorMode) {
        double luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0;
        float[] hsv = java.awt.Color.RGBtoHSB(red, green, blue, null);
        double saturation = hsv[1];
        double value = hsv[2];
        double hue = hsv[0] * 360.0;

        String mode = colorMode == null ? "hot" : colorMode.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "bluered" -> blueRedIntensity(red, blue, hue, saturation, luminance, value);
            case "gray" -> grayIntensity(hue, saturation, luminance, value);
            case "purple" -> purpleIntensity(hue, saturation, luminance, value);
            case "blue" -> blueIntensity(red, green, blue, hue, saturation, luminance, value);
            case "dual" -> dualColorIntensity(red, green, blue, hue, saturation, luminance, value);
            case "hot" -> 0.85 * luminance + 0.15 * value;
            default -> 0.85 * luminance + 0.15 * value;
        };
    }

    private static double blueRedIntensity(int red, int blue, double hue, double saturation, double luminance, double value) {
        double redScore = hueAffinity(hue, 350.0, 38.0) * (0.85 + 0.15 * value);
        double blueScore = hueAffinity(hue, 235.0, 50.0) * (0.20 + 0.38 * (1.0 - luminance));
        double bridgeScore = hueAffinity(hue, 315.0, 45.0) * (0.75 + 0.25 * value);
        double coolToWarm = Math.max(0.0, Math.min(1.0, (red - blue + 255.0) / 510.0));
        return saturation * Math.max(
            redScore * (0.92 + 0.55 * coolToWarm),
            Math.max(blueScore * 0.40, bridgeScore * (0.85 + 0.35 * coolToWarm))
        );
    }

    private static double blueIntensity(int red, int green, int blue, double hue, double saturation, double luminance, double value) {
        double blueAffinity = Math.max(hueAffinity(hue, 210.0, 55.0), hueAffinity(hue, 230.0, 45.0));
        double coolness = Math.max(0.0, blue - red * 0.65 - green * 0.15) / 255.0;
        double brightness = 0.72 * luminance + 0.28 * value;
        double saturationFit = 1.0 - Math.min(1.0, Math.abs(saturation - 0.45) / 0.55);
        return (0.55 + 0.45 * blueAffinity) * brightness * (0.70 + 0.30 * saturationFit) * (0.75 + 0.25 * coolness);
    }

    private static double grayIntensity(double hue, double saturation, double luminance, double value) {
        double grayBase = (1.0 - saturation) * (0.06 + 0.10 * luminance);
        double pinkScore = hueAffinity(hue, 332.0, 34.0) * saturation * (0.68 + 0.32 * value);
        double violetScore = hueAffinity(hue, 260.0, 40.0) * saturation * (0.82 + 0.18 * value);
        return Math.max(grayBase, Math.max(pinkScore, violetScore));
    }

    private static double purpleIntensity(double hue, double saturation, double luminance, double value) {
        double affinity = Math.max(hueAffinity(hue, 285.0, 35.0), hueAffinity(hue, 315.0, 35.0));
        return affinity * (0.55 + 0.45 * saturation) * value * (0.60 + 0.40 * luminance);
    }

    private static double dualColorIntensity(int red, int green, int blue, double hue, double saturation, double luminance, double value) {
        double warmCool = Math.max(
            blueRedIntensity(red, blue, hue, saturation, luminance, value),
            purpleIntensity(hue, saturation, luminance, value)
        );
        double brightCenter = (0.85 * luminance + 0.15 * value) * (0.65 + 0.35 * (1.0 - saturation));
        double blueCenter = blueIntensity(red, green, blue, hue, saturation, luminance, value) * 0.92;
        return Math.max(warmCool, Math.max(brightCenter, blueCenter));
    }

    private static double hueAffinity(double hue, double target, double width) {
        double distance = Math.abs(hue - target);
        distance = Math.min(distance, 360.0 - distance);
        return Math.max(0.0, 1.0 - distance / width);
    }

    public record CrossSectionProfile(
        EastNorth anchor,
        Point2D.Double anchorScreen,
        Point2D.Double normalScreen,
        List<CrossSectionPeak> peaks
    ) {
    }

    public record CrossSectionPeak(double offsetPx, double intensity) {
    }

    private record OffsetSample(double offsetPx, double intensity) {
    }
}

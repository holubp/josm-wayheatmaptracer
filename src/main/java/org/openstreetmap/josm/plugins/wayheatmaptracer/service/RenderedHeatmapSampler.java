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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

/**
 * Captures rendered heatmap imagery and converts cross-sections through a way into ridge peak evidence.
 *
 * <p>The sampler deliberately separates palette mapping from profile filtering. Pixels are first mapped to a
 * scalar heatmap intensity, then the one-dimensional cross-section profile is filtered at two conservative
 * scales. This keeps debug output useful: every ridge peak can report whether its raw maximum agrees with
 * denoised profiles rather than hiding the choice inside final geometry smoothing.</p>
 */
public final class RenderedHeatmapSampler {
    /**
     * Oversampling factor used when rendering JOSM imagery layers into an off-screen raster.
     */
    public static final double RASTER_SCALE = 6.0;
    public static final double AGGREGATE_POWER_MEAN = 1.25;
    private static final double[] LIGHT_BINOMIAL_KERNEL = {1.0, 2.0, 1.0};
    private static final double[] STANDARD_BINOMIAL_KERNEL = {1.0, 4.0, 6.0, 4.0, 1.0};
    private static final int FINE_LOCALIZATION_PHASES = 2;

    /**
     * Renders an imagery layer into an off-screen ARGB raster using the current map view state.
     *
     * @param imageryLayer heatmap imagery layer to paint
     * @param mapView JOSM map view whose current bounds and scale define the capture
     * @return oversampled raster containing the rendered layer
     */
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
        return sampleProfiles(raster, mapView, sourcePolyline, halfWidthPx, stepPx, colorMode, IntensitySamplingMode.COLOR_MAPPING);
    }

    /**
     * Samples ridge candidates from a rendered heatmap along a source polyline.
     *
     * @param raster rendered heatmap image
     * @param mapView map view used to project source coordinates into the raster
     * @param sourcePolyline source way geometry in projected coordinates
     * @param halfWidthPx search half-width in view pixels before raster oversampling
     * @param stepPx cross-section sample step in view pixels before raster oversampling
     * @param colorMode Strava color mapping or detector mapping name
     * @param intensitySamplingMode color-to-intensity strategy
     * @return cross-section profiles with extracted heatmap peaks
     */
    public List<CrossSectionProfile> sampleProfiles(
        BufferedImage raster,
        MapView mapView,
        List<EastNorth> sourcePolyline,
        int halfWidthPx,
        int stepPx,
        String colorMode,
        IntensitySamplingMode intensitySamplingMode
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
        return sampleProfilesOnRaster(raster, denseScreen, halfWidthPx, stepPx, colorMode, RASTER_SCALE, intensitySamplingMode);
    }

    List<CrossSectionProfile> sampleProfilesOnRaster(
        BufferedImage raster,
        List<Point2D.Double> denseScreenPolyline,
        int halfWidthPx,
        int stepPx,
        String colorMode,
        double rasterScale
    ) {
        return sampleProfilesOnRaster(raster, denseScreenPolyline, halfWidthPx, stepPx, colorMode, rasterScale,
            IntensitySamplingMode.COLOR_MAPPING);
    }

    List<CrossSectionProfile> sampleProfilesOnRaster(
        BufferedImage raster,
        List<Point2D.Double> denseScreenPolyline,
        int halfWidthPx,
        int stepPx,
        String colorMode,
        double rasterScale,
        IntensitySamplingMode intensitySamplingMode
    ) {
        return sampleProfilesOnScaledRaster(raster, denseScreenPolyline, halfWidthPx, stepPx, colorMode,
            rasterScale, 1.0, intensitySamplingMode);
    }

    List<CrossSectionProfile> sampleProfilesOnScaledRaster(
        BufferedImage raster,
        List<Point2D.Double> denseScreenPolyline,
        int halfWidthPx,
        int stepPx,
        String colorMode,
        double rasterScale,
        double sourceCoordinateScale,
        IntensitySamplingMode intensitySamplingMode
    ) {
        return sampleProfilesOnAnchors(raster, ProfileSamplingAnchor.rasterOnly(denseScreenPolyline),
            halfWidthPx, stepPx, colorMode, rasterScale, sourceCoordinateScale, intensitySamplingMode);
    }

    /**
     * Samples profiles from explicitly paired map, raster, and physical anchors.
     *
     * @param raster source raster
     * @param anchors aligned profile sampling anchors
     * @param halfWidthPx configured search half-width
     * @param stepPx configured lateral step
     * @param colorMode palette mapping
     * @param rasterScale raster oversampling scale
     * @param sourceCoordinateScale raster pixels per source-image pixel
     * @param intensitySamplingMode scalar intensity source
     * @return sampled cross-section profiles
     */
    List<CrossSectionProfile> sampleProfilesOnAnchors(
        BufferedImage raster,
        List<ProfileSamplingAnchor> anchors,
        int halfWidthPx,
        int stepPx,
        String colorMode,
        double rasterScale,
        double sourceCoordinateScale,
        IntensitySamplingMode intensitySamplingMode
    ) {
        if (anchors.size() < 2) {
            return Collections.emptyList();
        }
        double coordinateScale = sourceCoordinateScale > 0.0 && Double.isFinite(sourceCoordinateScale)
            ? sourceCoordinateScale
            : 1.0;
        List<CrossSectionProfile> profiles = new ArrayList<>();
        for (int i = 0; i < anchors.size(); i++) {
            ProfileSamplingAnchor samplingAnchor = anchors.get(i);
            Point2D.Double current = samplingAnchor.rasterCoordinate();
            Point2D.Double prevScreen = anchors.get(Math.max(0, i - 1)).rasterCoordinate();
            Point2D.Double nextScreen = anchors.get(Math.min(anchors.size() - 1, i + 1)).rasterCoordinate();
            Point2D.Double tangent = PolylineMath.normalize(nextScreen.x - prevScreen.x, nextScreen.y - prevScreen.y);
            Point2D.Double normal = new Point2D.Double(-tangent.y, tangent.x);
            Point2D.Double baseScreen = current;

            List<CrossSectionPeak> samples = new ArrayList<>();
            List<OffsetSample> offsets = new ArrayList<>();
            List<Boolean> insideRaster = new ArrayList<>();
            int scaledHalfWidth = Math.max(1, (int) Math.round(halfWidthPx * rasterScale));
            int scaledStep = Math.max(1, (int) Math.round(stepPx * rasterScale));
            boolean anchorWithinRaster = isInsideRaster(raster, baseScreen.x / coordinateScale, baseScreen.y / coordinateScale);
            for (int offset = -scaledHalfWidth; offset <= scaledHalfWidth; offset += scaledStep) {
                double x = baseScreen.x + normal.x * offset;
                double y = baseScreen.y + normal.y * offset;
                double sourceX = x / coordinateScale;
                double sourceY = y / coordinateScale;
                double intensity = intensityAt(raster, sourceX, sourceY, colorMode, intensitySamplingMode);
                offsets.add(new OffsetSample(offset, intensity));
                insideRaster.add(isInsideRaster(raster, sourceX, sourceY));
            }
            ProfileFilters filters = profileFilters(offsets);
            samples.addAll(extractBrightBands(offsets, filters));
            if (samples.isEmpty()) {
                double strongest = offsets.stream().mapToDouble(OffsetSample::intensity).max().orElse(0.0);
                samples.add(new CrossSectionPeak(0.0, strongest, 0.0, true, 0.0, 0.0, strongest, 0.0, 0.0, 0.0));
            }

            profiles.add(new CrossSectionProfile(samplingAnchor, normal,
                samples, anchorWithinRaster, intensitySamples(filters, insideRaster)));
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

    /**
     * Maps one already acquired raster to scalar intensity and samples aligned L0/L1/L2 profiles.
     *
     * @param raster source raster already acquired for this slide
     * @param denseScreenPolyline profile anchors in sampled-raster coordinates
     * @param halfWidthPx configured search half-width before raster oversampling
     * @param stepPx configured cross-section step before raster oversampling
     * @param colorMode detector palette mapping
     * @param rasterScale sampled-raster oversampling factor
     * @param sourceCoordinateScale sampled-raster pixels per source image pixel
     * @param intensitySamplingMode scalar source mode
     * @return geographically aligned Gaussian-level profiles
     */
    MultiScaleProfileSet sampleMultiScaleProfilesOnScaledRaster(
        BufferedImage raster,
        List<Point2D.Double> denseScreenPolyline,
        int halfWidthPx,
        int stepPx,
        String colorMode,
        double rasterScale,
        double sourceCoordinateScale,
        IntensitySamplingMode intensitySamplingMode
    ) {
        return sampleMultiScaleProfilesOnScaledRaster(raster, denseScreenPolyline, halfWidthPx, stepPx,
            colorMode, rasterScale, sourceCoordinateScale, intensitySamplingMode, 1.0);
    }

    /**
     * Samples Gaussian profiles while respecting the source-pixel pitch of an oversampled rendered raster.
     *
     * @param raster source raster already acquired for this slide
     * @param denseScreenPolyline profile anchors in sampled-raster coordinates
     * @param halfWidthPx configured search half-width before oversampling
     * @param stepPx configured cross-section step before oversampling
     * @param colorMode detector palette mapping
     * @param rasterScale sampled-raster oversampling factor
     * @param sourceCoordinateScale sampled-raster coordinates per image pixel
     * @param intensitySamplingMode scalar source mode
     * @param sourcePixelPitchInImagePixels estimated native source-pixel pitch in image pixels
     * @return fine profiles plus two source-aware coarse Gaussian levels
     */
    MultiScaleProfileSet sampleMultiScaleProfilesOnScaledRaster(
        BufferedImage raster,
        List<Point2D.Double> denseScreenPolyline,
        int halfWidthPx,
        int stepPx,
        String colorMode,
        double rasterScale,
        double sourceCoordinateScale,
        IntensitySamplingMode intensitySamplingMode,
        double sourcePixelPitchInImagePixels
    ) {
        return sampleMultiScaleProfilesOnAnchors(raster, ProfileSamplingAnchor.rasterOnly(denseScreenPolyline),
            halfWidthPx, stepPx, colorMode, rasterScale, sourceCoordinateScale, intensitySamplingMode,
            sourcePixelPitchInImagePixels);
    }

    /** Samples Gaussian levels using one explicit physical anchor sequence for every level. */
    MultiScaleProfileSet sampleMultiScaleProfilesOnAnchors(
        BufferedImage raster,
        List<ProfileSamplingAnchor> anchors,
        int halfWidthPx,
        int stepPx,
        String colorMode,
        double rasterScale,
        double sourceCoordinateScale,
        IntensitySamplingMode intensitySamplingMode,
        double sourcePixelPitchInImagePixels
    ) {
        if (anchors.size() < 2) {
            return new MultiScaleProfileSet(List.of(), 0L, 0L);
        }
        double coordinateScale = validCoordinateScale(sourceCoordinateScale);
        int scaledHalfWidth = Math.max(1, (int) Math.round(halfWidthPx * rasterScale));
        double sourcePitch = Double.isFinite(sourcePixelPitchInImagePixels) && sourcePixelPitchInImagePixels > 0.0
            ? sourcePixelPitchInImagePixels : 1.0;
        List<Point2D.Double> rasterPolyline = anchors.stream().map(ProfileSamplingAnchor::rasterCoordinate).toList();
        CropBounds crop = cropBounds(raster, rasterPolyline, scaledHalfWidth, coordinateScale,
            Math.max(12.0, 8.0 * sourcePitch));
        long started = System.nanoTime();
        ScalarIntensityField levelZero = ScalarIntensityField.fromRaster(raster, crop.minX(), crop.minY(),
            crop.maxX(), crop.maxY(), colorMode, intensitySamplingMode);
        GaussianIntensityPyramid pyramid = GaussianIntensityPyramid.build(levelZero,
            Math.max(4, (int) Math.ceil(4.0 * sourcePitch)));
        long buildNanos = System.nanoTime() - started;
        List<MultiScaleProfileSet.ScaleProfileLevel> levels = new ArrayList<>();
        for (IntensityScaleLevel level : selectedAnalysisLevels(pyramid, sourcePitch)) {
            levels.add(new MultiScaleProfileSet.ScaleProfileLevel(level.level(), level.reduction(),
                level.effectiveSigmaL0(), sampleProfilesFromField(level, anchors,
                    scaledHalfWidth, Math.max(1, (int) Math.round(stepPx * rasterScale)), coordinateScale)));
        }
        PluginLog.verbose("Built scalar Gaussian pyramid for '%s': levels=%s crop=%dx%d bytes=%d time=%.1fms.",
            colorMode, levels.stream().map(value -> "L" + value.level() + ":" + value.reduction() + "x").toList(),
            levelZero.width(), levelZero.height(), pyramid.estimatedBytes(), buildNanos / 1_000_000.0);
        return new MultiScaleProfileSet(levels, buildNanos, pyramid.estimatedBytes());
    }

    private List<IntensityScaleLevel> selectedAnalysisLevels(
        GaussianIntensityPyramid pyramid,
        double sourcePixelPitch
    ) {
        List<IntensityScaleLevel> available = pyramid.levels();
        List<IntensityScaleLevel> selected = new ArrayList<>();
        selected.add(available.get(0));
        for (double target : List.of(2.0 * sourcePixelPitch, 4.0 * sourcePixelPitch)) {
            IntensityScaleLevel nearest = available.stream()
                .filter(level -> level.level() > 0)
                .min(java.util.Comparator
                    .comparingDouble((IntensityScaleLevel level) -> Math.abs(level.reduction() - target))
                    .thenComparingInt(IntensityScaleLevel::reduction))
                .orElse(null);
            if (nearest != null && selected.stream().noneMatch(level -> level.reduction() == nearest.reduction())) {
                selected.add(nearest);
            }
        }
        return selected;
    }

    /** Samples aligned Gaussian profiles from the complete managed all-color aggregate. */
    MultiScaleProfileSet sampleMultiScaleProfilesOnAggregatedScaledRasters(
        java.util.Map<String, BufferedImage> rastersByColor,
        List<Point2D.Double> denseScreenPolyline,
        int halfWidthPx,
        int stepPx,
        double rasterScale,
        double sourceCoordinateScale
    ) {
        return sampleMultiScaleProfilesOnAggregatedAnchors(rastersByColor,
            ProfileSamplingAnchor.rasterOnly(denseScreenPolyline), halfWidthPx, stepPx, rasterScale,
            sourceCoordinateScale);
    }

    /** Samples an all-color Gaussian field using one explicit physical anchor sequence. */
    MultiScaleProfileSet sampleMultiScaleProfilesOnAggregatedAnchors(
        java.util.Map<String, BufferedImage> rastersByColor,
        List<ProfileSamplingAnchor> anchors,
        int halfWidthPx,
        int stepPx,
        double rasterScale,
        double sourceCoordinateScale
    ) {
        if (anchors.size() < 2 || rastersByColor.isEmpty()) {
            return new MultiScaleProfileSet(List.of(), 0L, 0L);
        }
        double coordinateScale = validCoordinateScale(sourceCoordinateScale);
        int scaledHalfWidth = Math.max(1, (int) Math.round(halfWidthPx * rasterScale));
        BufferedImage reference = rastersByColor.values().iterator().next();
        List<Point2D.Double> rasterPolyline = anchors.stream().map(ProfileSamplingAnchor::rasterCoordinate).toList();
        CropBounds crop = cropBounds(reference, rasterPolyline, scaledHalfWidth, coordinateScale, 12.0);
        long started = System.nanoTime();
        ScalarIntensityField levelZero = ScalarIntensityField.fromAggregatedRasters(rastersByColor,
            crop.minX(), crop.minY(), crop.maxX(), crop.maxY());
        GaussianIntensityPyramid pyramid = GaussianIntensityPyramid.build(levelZero);
        long buildNanos = System.nanoTime() - started;
        List<MultiScaleProfileSet.ScaleProfileLevel> levels = new ArrayList<>();
        for (IntensityScaleLevel level : pyramid.levels()) {
            levels.add(new MultiScaleProfileSet.ScaleProfileLevel(level.level(), level.reduction(),
                level.effectiveSigmaL0(), sampleProfilesFromField(level, anchors,
                    scaledHalfWidth, Math.max(1, (int) Math.round(stepPx * rasterScale)), coordinateScale)));
        }
        PluginLog.verbose("Built all-color scalar Gaussian pyramid: levels=%s crop=%dx%d bytes=%d time=%.1fms.",
            levels.stream().map(value -> "L" + value.level() + ":" + value.reduction() + "x").toList(),
            levelZero.width(), levelZero.height(), pyramid.estimatedBytes(), buildNanos / 1_000_000.0);
        return new MultiScaleProfileSet(levels, buildNanos, pyramid.estimatedBytes());
    }

    private List<CrossSectionProfile> sampleProfilesFromField(
        IntensityScaleLevel level,
        List<ProfileSamplingAnchor> anchors,
        int scaledHalfWidth,
        int scaledStep,
        double coordinateScale
    ) {
        List<CrossSectionProfile> profiles = new ArrayList<>(anchors.size());
        double physicalStep = Math.max(scaledStep, coordinateScale * level.reduction());
        int localizationPhases = level.level() == 0 ? FINE_LOCALIZATION_PHASES : 1;
        double levelStep = physicalStep / localizationPhases;
        for (int i = 0; i < anchors.size(); i++) {
            ProfileSamplingAnchor samplingAnchor = anchors.get(i);
            Point2D.Double current = samplingAnchor.rasterCoordinate();
            Point2D.Double previous = anchors.get(Math.max(0, i - 1)).rasterCoordinate();
            Point2D.Double next = anchors.get(Math.min(anchors.size() - 1, i + 1)).rasterCoordinate();
            Point2D.Double tangent = PolylineMath.normalize(next.x - previous.x, next.y - previous.y);
            Point2D.Double normal = new Point2D.Double(-tangent.y, tangent.x);
            List<OffsetSample> offsets = new ArrayList<>();
            List<Boolean> inside = new ArrayList<>();
            for (double offset = -scaledHalfWidth; offset <= scaledHalfWidth + 1e-9; offset += levelStep) {
                double sourceX = (current.x + normal.x * offset) / coordinateScale;
                double sourceY = (current.y + normal.y * offset) / coordinateScale;
                double intensity = level.field().sample(sourceX, sourceY);
                boolean valid = Double.isFinite(intensity);
                offsets.add(new OffsetSample(offset, valid ? intensity : 0.0));
                inside.add(valid);
            }
            ProfileFilters filters = profileFilters(offsets, localizationPhases);
            List<CrossSectionPeak> peaks = new ArrayList<>(extractBrightBands(offsets, filters));
            if (peaks.isEmpty()) {
                double strongest = offsets.stream().mapToDouble(OffsetSample::intensity).max().orElse(0.0);
                peaks.add(new CrossSectionPeak(0.0, strongest, 0.0, true, 0.0, 0.0, strongest,
                    0.0, 0.0, 0.0));
            }
            boolean anchorValid = Double.isFinite(level.field().sample(current.x / coordinateScale,
                current.y / coordinateScale));
            profiles.add(new CrossSectionProfile(samplingAnchor, normal,
                peaks, anchorValid, intensitySamples(filters, inside)));
        }
        return profiles;
    }

    private CropBounds cropBounds(
        BufferedImage raster,
        List<Point2D.Double> polyline,
        int scaledHalfWidth,
        double coordinateScale,
        double convolutionHalo
    ) {
        double minimumX = polyline.stream().mapToDouble(point -> point.x / coordinateScale).min().orElse(0.0);
        double maximumX = polyline.stream().mapToDouble(point -> point.x / coordinateScale).max().orElse(0.0);
        double minimumY = polyline.stream().mapToDouble(point -> point.y / coordinateScale).min().orElse(0.0);
        double maximumY = polyline.stream().mapToDouble(point -> point.y / coordinateScale).max().orElse(0.0);
        double radius = scaledHalfWidth / coordinateScale + convolutionHalo;
        return new CropBounds(
            Math.max(0, (int) Math.floor(minimumX - radius)),
            Math.max(0, (int) Math.floor(minimumY - radius)),
            Math.min(raster.getWidth() - 1, (int) Math.ceil(maximumX + radius)),
            Math.min(raster.getHeight() - 1, (int) Math.ceil(maximumY + radius))
        );
    }

    private double validCoordinateScale(double sourceCoordinateScale) {
        return sourceCoordinateScale > 0.0 && Double.isFinite(sourceCoordinateScale) ? sourceCoordinateScale : 1.0;
    }

    private record CropBounds(int minX, int minY, int maxX, int maxY) {
    }

    List<CrossSectionProfile> sampleProfilesOnAggregatedScaledRasters(
        java.util.Map<String, BufferedImage> rastersByColor,
        List<Point2D.Double> denseScreenPolyline,
        int halfWidthPx,
        int stepPx,
        double rasterScale,
        double sourceCoordinateScale
    ) {
        return sampleProfilesOnAggregatedAnchors(rastersByColor,
            ProfileSamplingAnchor.rasterOnly(denseScreenPolyline), halfWidthPx, stepPx, rasterScale,
            sourceCoordinateScale);
    }

    /** Samples an all-color scalar field with explicit physical anchors. */
    List<CrossSectionProfile> sampleProfilesOnAggregatedAnchors(
        java.util.Map<String, BufferedImage> rastersByColor,
        List<ProfileSamplingAnchor> anchors,
        int halfWidthPx,
        int stepPx,
        double rasterScale,
        double sourceCoordinateScale
    ) {
        if (anchors.size() < 2 || rastersByColor.isEmpty()) {
            return Collections.emptyList();
        }
        double coordinateScale = sourceCoordinateScale > 0.0 && Double.isFinite(sourceCoordinateScale)
            ? sourceCoordinateScale
            : 1.0;
        List<CrossSectionProfile> profiles = new ArrayList<>();
        for (int i = 0; i < anchors.size(); i++) {
            ProfileSamplingAnchor samplingAnchor = anchors.get(i);
            Point2D.Double current = samplingAnchor.rasterCoordinate();
            Point2D.Double prevScreen = anchors.get(Math.max(0, i - 1)).rasterCoordinate();
            Point2D.Double nextScreen = anchors.get(Math.min(anchors.size() - 1, i + 1)).rasterCoordinate();
            Point2D.Double tangent = PolylineMath.normalize(nextScreen.x - prevScreen.x, nextScreen.y - prevScreen.y);
            Point2D.Double normal = new Point2D.Double(-tangent.y, tangent.x);

            List<CrossSectionPeak> samples = new ArrayList<>();
            List<OffsetSample> offsets = new ArrayList<>();
            List<Boolean> insideRaster = new ArrayList<>();
            int scaledHalfWidth = Math.max(1, (int) Math.round(halfWidthPx * rasterScale));
            int scaledStep = Math.max(1, (int) Math.round(stepPx * rasterScale));
            boolean anchorWithinRaster = rastersByColor.values().stream()
                .allMatch(raster -> isInsideRaster(raster, current.x / coordinateScale, current.y / coordinateScale));
            for (int offset = -scaledHalfWidth; offset <= scaledHalfWidth; offset += scaledStep) {
                double x = current.x + normal.x * offset;
                double y = current.y + normal.y * offset;
                double sourceX = x / coordinateScale;
                double sourceY = y / coordinateScale;
                double intensity = aggregatedSourceIntensityAt(rastersByColor, sourceX, sourceY);
                offsets.add(new OffsetSample(offset, intensity));
                insideRaster.add(rastersByColor.values().stream().allMatch(raster -> isInsideRaster(raster, sourceX, sourceY)));
            }
            ProfileFilters filters = profileFilters(offsets);
            samples.addAll(extractBrightBands(offsets, filters));
            if (samples.isEmpty()) {
                double strongest = offsets.stream().mapToDouble(OffsetSample::intensity).max().orElse(0.0);
                samples.add(new CrossSectionPeak(0.0, strongest, 0.0, true, 0.0, 0.0, strongest, 0.0, 0.0, 0.0));
            }
            profiles.add(new CrossSectionProfile(samplingAnchor, normal,
                samples, anchorWithinRaster, intensitySamples(filters, insideRaster)));
        }
        PluginLog.verbose("Aggregated heatmap sampling produced %d profiles from color sources %s.",
            profiles.size(), rastersByColor.keySet());
        return profiles;
    }

    private Point2D.Double toRasterScreen(EastNorth point, MapView mapView) {
        Point2D point2D = mapView.getPoint2D(point);
        return new Point2D.Double(point2D.getX() * RASTER_SCALE, point2D.getY() * RASTER_SCALE);
    }

    private List<CrossSectionPeak> extractBrightBands(List<OffsetSample> offsets) {
        return extractBrightBands(offsets, profileFilters(offsets));
    }

    private List<CrossSectionPeak> extractBrightBands(List<OffsetSample> offsets, ProfileFilters filters) {
        List<OffsetSample> smoothed = filters.standardFiltered();
        ProfileStats stats = profileStats(smoothed);
        if (stats.maxIntensity() <= 0.14 || stats.maxProminence() <= 0.025) {
            return List.of();
        }

        boolean strongProfile = stats.maxIntensity() >= 0.35 && stats.maxProminence() >= 0.16;
        double localPeakThreshold = strongProfile
            ? Math.max(0.22, stats.maxIntensity() * 0.52)
            : Math.max(
                stats.noiseFloor() + Math.max(0.035, stats.maxProminence() * 0.30),
                stats.maxIntensity() * 0.30
            );
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
            double prominence = Math.max(0.0, current - stats.noiseFloor());
            double shoulderThreshold = strongProfile
                ? Math.max(0.16, current * 0.60)
                : Math.max(stats.noiseFloor() + prominence * 0.22, current * 0.45);
            while (start > 0 && smoothed.get(start - 1).intensity >= shoulderThreshold) {
                start--;
            }
            while (end + 1 < smoothed.size() && smoothed.get(end + 1).intensity >= shoulderThreshold) {
                end++;
            }
            peaks.add(buildBandPeak(offsets, filters, stats, index, start, end));
        }

        if (peaks.isEmpty()) {
            int strongest = 0;
            for (int i = 1; i < smoothed.size(); i++) {
                if (smoothed.get(i).intensity > smoothed.get(strongest).intensity) {
                    strongest = i;
                }
            }
            peaks.add(buildBandPeak(offsets, filters, stats, strongest, strongest, strongest));
        }
        List<CrossSectionPeak> merged = mergeClosePeaks(peaks, filters.physicalSampleStepPx());
        return addPairedShoulderCenters(merged, filters.physicalSampleStepPx());
    }

    private List<IntensitySample> intensitySamples(ProfileFilters filters, List<Boolean> insideRaster) {
        List<IntensitySample> samples = new ArrayList<>(filters.raw().size());
        for (int i = 0; i < filters.raw().size(); i++) {
            samples.add(new IntensitySample(
                filters.raw().get(i).offsetPx(),
                filters.raw().get(i).intensity(),
                filters.lightFiltered().get(i).intensity(),
                filters.standardFiltered().get(i).intensity(),
                i < insideRaster.size() && insideRaster.get(i)
            ));
        }
        return List.copyOf(samples);
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
                    double prominence = Math.min(left.prominence(), right.prominence()) * 0.92;
                    double noiseFloor = Math.max(left.noiseFloor(), right.noiseFloor());
                    double maxIntensity = Math.max(left.maxProfileIntensity(), right.maxProfileIntensity());
                    double gradientStrength = Math.min(left.gradientStrength(), right.gradientStrength()) * 0.85;
                    double gradientBalance = Math.min(left.gradientBalance(), right.gradientBalance());
                    double nativeFilteredAgreement = Math.min(left.nativeFilteredAgreement(), right.nativeFilteredAgreement());
                    double scaleAgreement = Math.min(left.scaleAgreement(), right.scaleAgreement());
                    double centerUncertainty = Math.max(left.centerUncertaintyPx(), right.centerUncertaintyPx());
                    augmented.add(new CrossSectionPeak(center, weaker * 0.93, gap, true, prominence, noiseFloor, maxIntensity,
                        gradientStrength, gradientBalance, nativeFilteredAgreement,
                        center, center, center, 0.0, scaleAgreement, centerUncertainty));
                }
            }
        }
        return mergeClosePeaks(augmented, sampleStep);
    }

    private CrossSectionPeak buildBandPeak(
        List<OffsetSample> offsets,
        ProfileFilters filters,
        ProfileStats stats,
        int peakIndex,
        int start,
        int end
    ) {
        List<OffsetSample> smoothed = filters.standardFiltered();
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
        double shoulderCenter = midpoint * 0.70 + weightedCenter * 0.30;
        double supportWidth = Math.abs(offsets.get(end).offsetPx - offsets.get(start).offsetPx);
        double center = shoulderCenter;
        double sampleStep = filters.physicalSampleStepPx();
        if (supportWidth >= sampleStep * 3.0 && peakIntensity >= 0.45) {
            double coreThreshold = Math.max(stats.noiseFloor() + Math.max(0.0, peakIntensity - stats.noiseFloor()) * 0.72,
                peakIntensity * 0.86);
            int coreStart = peakIndex;
            int coreEnd = peakIndex;
            while (coreStart > start && smoothed.get(coreStart - 1).intensity >= coreThreshold) {
                coreStart--;
            }
            while (coreEnd < end && smoothed.get(coreEnd + 1).intensity >= coreThreshold) {
                coreEnd++;
            }
            if (coreStart <= coreEnd) {
                double coreCenter = bandCenter(offsets, smoothed, coreStart, coreEnd);
                center = coreCenter * 0.85 + shoulderCenter * 0.15;
            }
        }
        double nativePeakOffset = nativePeakCenter(offsets, start, end, peakIntensity);
        ScaleEvidence scaleEvidence = scaleEvidence(filters, start, end, peakIndex, sampleStep);
        double filteredPeakOffset = scaleEvidence.lightCenterPx();
        double nativeFilteredDistance = Math.abs(nativePeakOffset - filteredPeakOffset);
        double nativeFilteredAgreement = 1.0 - Math.min(1.0,
            nativeFilteredDistance / Math.max(sampleStep, supportWidth * 0.50 + sampleStep * 1.5));
        if (nativeFilteredAgreement >= 0.55) {
            if (scaleEvidence.scaleAgreement() >= 0.90 && peakIntensity >= 0.45) {
                center = center * 0.25 + filteredPeakOffset * 0.50 + nativePeakOffset * 0.25;
            } else {
                center = center * 0.70 + filteredPeakOffset * 0.18 + nativePeakOffset * 0.12;
            }
        }
        double confidence = Math.min(1.0, peakIntensity * (0.88 + Math.min(0.12, supportWidth / 80.0)));
        if (Math.abs(center - offsets.get(peakIndex).offsetPx) > supportWidth * 0.75 + sampleStep) {
            center = offsets.get(peakIndex).offsetPx;
        }
        double prominence = Math.max(0.0, peakIntensity - stats.noiseFloor());
        double prominenceWeight = stats.maxProminence() <= 0.0 ? 0.0 : prominence / stats.maxProminence();
        GradientEvidence gradient = gradientEvidence(smoothed, peakIndex, start, end, filters.filterStride());
        double gradientReward = gradient.strength() * (0.06 + 0.08 * gradient.balance());
        double calibratedConfidence = Math.min(1.0, confidence * 0.68
            + prominenceWeight * 0.18
            + gradientReward
            + Math.min(0.08, supportWidth / 120.0));
        calibratedConfidence *= 0.72 + 0.28 * nativeFilteredAgreement;
        calibratedConfidence *= 0.78 + 0.22 * scaleEvidence.scaleAgreement();
        return new CrossSectionPeak(center, calibratedConfidence, supportWidth, false,
            prominence, stats.noiseFloor(), stats.maxIntensity(), gradient.strength(), gradient.balance(),
            nativeFilteredAgreement, scaleEvidence.rawCenterPx(), scaleEvidence.lightCenterPx(),
            scaleEvidence.standardCenterPx(), scaleEvidence.scaleOffsetRmsPx(), scaleEvidence.scaleAgreement(),
            scaleEvidence.centerUncertaintyPx());
    }

    private double bandCenter(List<OffsetSample> offsets, List<OffsetSample> values, int start, int end) {
        double weightedOffset = 0.0;
        double weightSum = 0.0;
        for (int i = start; i <= end; i++) {
            double weight = values.get(i).intensity;
            weightedOffset += offsets.get(i).offsetPx * weight;
            weightSum += weight;
        }
        double midpoint = (offsets.get(start).offsetPx + offsets.get(end).offsetPx) / 2.0;
        double weightedCenter = weightSum == 0.0 ? midpoint : weightedOffset / weightSum;
        return midpoint * 0.70 + weightedCenter * 0.30;
    }

    private double nativePeakCenter(List<OffsetSample> offsets, int start, int end, double peakIntensity) {
        if (peakIntensity <= 0.0) {
            return offsets.get((start + end) / 2).offsetPx;
        }
        double threshold = peakIntensity - 1e-9;
        int nativeStart = start;
        int nativeEnd = end;
        while (nativeStart <= end && offsets.get(nativeStart).intensity < threshold) {
            nativeStart++;
        }
        while (nativeEnd >= start && offsets.get(nativeEnd).intensity < threshold) {
            nativeEnd--;
        }
        if (nativeStart > nativeEnd) {
            return offsets.get((start + end) / 2).offsetPx;
        }
        return (offsets.get(nativeStart).offsetPx + offsets.get(nativeEnd).offsetPx) / 2.0;
    }

    private GradientEvidence gradientEvidence(
        List<OffsetSample> smoothed,
        int peakIndex,
        int start,
        int end,
        int filterStride
    ) {
        if (smoothed.size() < 3) {
            return new GradientEvidence(0.0, 0.0);
        }
        int stride = Math.max(1, filterStride);
        int leftIndex = Math.max(stride, Math.min(peakIndex, start + stride));
        int rightIndex = Math.min(smoothed.size() - stride - 1, Math.max(peakIndex, end - stride));
        double leftRise = Math.max(0.0,
            smoothed.get(leftIndex).intensity - smoothed.get(leftIndex - stride).intensity);
        double rightFall = Math.max(0.0,
            smoothed.get(rightIndex).intensity - smoothed.get(rightIndex + stride).intensity);
        double strength = Math.min(1.0, (leftRise + rightFall) * 4.0);
        double balance = leftRise + rightFall <= 0.0
            ? 0.0
            : 1.0 - Math.abs(leftRise - rightFall) / (leftRise + rightFall);
        return new GradientEvidence(strength, Math.max(0.0, Math.min(1.0, balance)));
    }

    private ProfileFilters profileFilters(List<OffsetSample> offsets) {
        return profileFilters(offsets, 1);
    }

    /**
     * Filters one profile while retaining the same physical B3/B5 support on an interleaved localization grid.
     *
     * @param offsets scalar samples ordered by lateral offset
     * @param filterStride sample-index distance corresponding to one physical source sample
     * @return raw, B3, and B5 profiles on the input localization grid
     */
    private ProfileFilters profileFilters(List<OffsetSample> offsets, int filterStride) {
        int stride = Math.max(1, filterStride);
        double physicalSampleStep = estimateSampleStep(offsets) * stride;
        if (offsets.size() < 3) {
            return new ProfileFilters(offsets, offsets, offsets, stride, physicalSampleStep);
        }
        List<OffsetSample> light = signalGatedPowerBinomialSmooth(
            offsets, LIGHT_BINOMIAL_KERNEL, stride, 0.45, 0.30, 0.15);
        if (offsets.size() >= 1 + (STANDARD_BINOMIAL_KERNEL.length - 1) * stride) {
            List<OffsetSample> standard = signalGatedPowerBinomialSmooth(
                offsets, STANDARD_BINOMIAL_KERNEL, stride, 0.35, 0.25, 0.10);
            return new ProfileFilters(offsets, light, standard, stride, physicalSampleStep);
        }
        return new ProfileFilters(offsets, light, light, stride, physicalSampleStep);
    }

    private List<OffsetSample> signalGatedPowerBinomialSmooth(
        List<OffsetSample> offsets,
        double[] kernel,
        int filterStride,
        double strongBlend,
        double mediumBlend,
        double weakBlend
    ) {
        double max = offsets.stream().mapToDouble(OffsetSample::intensity).max().orElse(0.0);
        if (max <= 0.0) {
            return offsets;
        }
        int radius = kernel.length / 2;
        double power = max < 0.35 ? 1.25 : 2.0;
        double blend = max >= 0.55 ? strongBlend : (max >= 0.25 ? mediumBlend : weakBlend);
        List<OffsetSample> smoothed = new ArrayList<>(offsets.size());
        for (int i = 0; i < offsets.size(); i++) {
            double weighted = 0.0;
            double total = 0.0;
            for (int k = -radius; k <= radius; k++) {
                int index = Math.max(0, Math.min(offsets.size() - 1, i + k * filterStride));
                double intensity = offsets.get(index).intensity;
                double mask = signalMask(intensity, max);
                double weight = kernel[k + radius] * mask;
                weighted += Math.pow(Math.max(0.0, intensity), power) * weight;
                total += weight;
            }
            double raw = offsets.get(i).intensity;
            double filtered = total <= 1e-9 ? raw : Math.pow(weighted / total, 1.0 / power);
            double smoothedIntensity = raw * (1.0 - blend) + filtered * blend;
            smoothed.add(new OffsetSample(offsets.get(i).offsetPx, smoothedIntensity));
        }
        return smoothed;
    }

    private ScaleEvidence scaleEvidence(ProfileFilters filters, int start, int end, int peakIndex, double sampleStep) {
        int windowStart = Math.max(0, start - 1);
        int windowEnd = Math.min(filters.raw().size() - 1, end + 1);
        double rawCenter = maxPlateauCenter(filters.raw(), windowStart, windowEnd, peakIndex);
        double lightCenter = maxPlateauCenter(filters.lightFiltered(), windowStart, windowEnd, peakIndex);
        double standardCenter = maxPlateauCenter(filters.standardFiltered(), windowStart, windowEnd, peakIndex);
        double mean = (rawCenter + lightCenter + standardCenter) / 3.0;
        double rms = Math.sqrt((square(rawCenter - mean) + square(lightCenter - mean) + square(standardCenter - mean)) / 3.0);
        double matchRadius = Math.max(1.0, sampleStep);
        double agreement = Math.exp(-(rms * rms) / (2.0 * matchRadius * matchRadius));
        double uncertainty = Math.max(sampleStep / 2.0, rms);
        return new ScaleEvidence(rawCenter, lightCenter, standardCenter, rms, agreement, uncertainty);
    }

    private double maxPlateauCenter(List<OffsetSample> samples, int start, int end, int fallbackIndex) {
        if (samples.isEmpty()) {
            return 0.0;
        }
        int safeStart = Math.max(0, Math.min(start, samples.size() - 1));
        int safeEnd = Math.max(safeStart, Math.min(end, samples.size() - 1));
        double max = Double.NEGATIVE_INFINITY;
        for (int i = safeStart; i <= safeEnd; i++) {
            max = Math.max(max, samples.get(i).intensity());
        }
        if (!Double.isFinite(max)) {
            return samples.get(Math.max(0, Math.min(fallbackIndex, samples.size() - 1))).offsetPx();
        }
        int first = -1;
        int last = -1;
        double threshold = max - 1e-9;
        for (int i = safeStart; i <= safeEnd; i++) {
            if (samples.get(i).intensity() >= threshold) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return samples.get(Math.max(0, Math.min(fallbackIndex, samples.size() - 1))).offsetPx();
        }
        return (samples.get(first).offsetPx() + samples.get(last).offsetPx()) / 2.0;
    }

    private double square(double value) {
        return value * value;
    }

    private double signalMask(double intensity, double max) {
        if (max <= 0.0 || intensity <= 0.0) {
            return 0.0;
        }
        if (max < 0.25) {
            return 0.25 + 0.75 * Math.min(1.0, intensity / max);
        }
        double floor = max * 0.18;
        double width = Math.max(0.08, max * 0.42);
        return Math.max(0.0, Math.min(1.0, (intensity - floor) / width));
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
                current = new CrossSectionPeak(center,
                    Math.max(current.intensity(), next.intensity()),
                    Math.max(current.supportWidthPx(), next.supportWidthPx()),
                    current.syntheticCenter() && next.syntheticCenter(),
                    Math.max(current.prominence(), next.prominence()),
                    Math.max(current.noiseFloor(), next.noiseFloor()),
                    Math.max(current.maxProfileIntensity(), next.maxProfileIntensity()),
                    Math.max(current.gradientStrength(), next.gradientStrength()),
                    Math.min(current.gradientBalance(), next.gradientBalance()),
                    Math.min(current.nativeFilteredAgreement(), next.nativeFilteredAgreement()),
                    mergeCenter(current.rawCenterPx(), next.rawCenterPx(), current.intensity(), next.intensity()),
                    mergeCenter(current.lightFilteredCenterPx(), next.lightFilteredCenterPx(), current.intensity(), next.intensity()),
                    mergeCenter(current.standardFilteredCenterPx(), next.standardFilteredCenterPx(), current.intensity(), next.intensity()),
                    Math.max(current.scaleOffsetRmsPx(), next.scaleOffsetRmsPx()),
                    Math.min(current.scaleAgreement(), next.scaleAgreement()),
                    Math.max(current.centerUncertaintyPx(), next.centerUncertaintyPx()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private double mergeCenter(double leftCenter, double rightCenter, double leftWeight, double rightWeight) {
        double total = leftWeight + rightWeight;
        return total <= 0.0 ? (leftCenter + rightCenter) / 2.0 : (leftCenter * leftWeight + rightCenter * rightWeight) / total;
    }

    private double estimateSampleStep(List<OffsetSample> offsets) {
        if (offsets.size() < 2) {
            return 1.0;
        }
        return Math.abs(offsets.get(1).offsetPx - offsets.get(0).offsetPx);
    }

    private ProfileStats profileStats(List<OffsetSample> offsets) {
        if (offsets.isEmpty()) {
            return new ProfileStats(0.0, 0.0, 0.0);
        }
        List<Double> intensities = offsets.stream()
            .map(OffsetSample::intensity)
            .sorted()
            .toList();
        double maxIntensity = intensities.get(intensities.size() - 1);
        int lowerQuartileIndex = Math.max(0, Math.min(intensities.size() - 1, (int) Math.floor((intensities.size() - 1) * 0.25)));
        int medianIndex = Math.max(0, Math.min(intensities.size() - 1, (int) Math.floor((intensities.size() - 1) * 0.50)));
        double noiseFloor = Math.min(intensities.get(medianIndex) * 0.80, intensities.get(lowerQuartileIndex) * 1.25);
        noiseFloor = Math.max(0.0, Math.min(noiseFloor, maxIntensity));
        return new ProfileStats(maxIntensity, noiseFloor, Math.max(0.0, maxIntensity - noiseFloor));
    }

    private double intensityAt(BufferedImage image, double x, double y, String colorMode, IntensitySamplingMode intensitySamplingMode) {
        int sx = (int) Math.round(x);
        int sy = (int) Math.round(y);
        if (!isInsideRaster(image, sx, sy)) {
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

        IntensitySamplingMode source = intensitySamplingMode == null ? IntensitySamplingMode.COLOR_MAPPING : intensitySamplingMode;
        if (!source.usesColorMapping()) {
            return directIntensity(red, green, blue, alpha, source);
        }
        return colorIntensity(red, green, blue, colorMode);
    }

    static double aggregatedSourceIntensityAt(java.util.Map<String, BufferedImage> rastersByColor, double x, double y) {
        double weighted = 0.0;
        double totalWeight = 0.0;
        double power = AGGREGATE_POWER_MEAN;
        for (java.util.Map.Entry<String, BufferedImage> entry : rastersByColor.entrySet()) {
            double weight = aggregateSourceWeight(entry.getKey());
            weighted += weight * Math.pow(sourceIntensityAt(entry.getValue(), x, y, entry.getKey()), power);
            totalWeight += weight;
        }
        return totalWeight <= 0.0 ? 0.0 : Math.min(1.0, Math.max(0.0, Math.pow(weighted / totalWeight, 1.0 / power)));
    }

    static double aggregateSourceWeight(String color) {
        return switch (color == null ? "" : color.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "bluered" -> 0.30;
            case "gray" -> 0.22;
            case "purple" -> 0.20;
            case "hot" -> 0.16;
            case "blue" -> 0.12;
            default -> 0.10;
        };
    }

    public static BufferedImage renderAggregatedIntensityRaster(java.util.Map<String, BufferedImage> rastersByColor) {
        if (rastersByColor.isEmpty()) {
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        }
        int width = rastersByColor.values().stream().mapToInt(BufferedImage::getWidth).min().orElse(1);
        int height = rastersByColor.values().stream().mapToInt(BufferedImage::getHeight).min().orElse(1);
        BufferedImage rendered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                rendered.setRGB(x, y, aggregateDisplayColor(aggregatedSourceIntensityAt(rastersByColor, x, y)));
            }
        }
        return rendered;
    }

    private static int aggregateDisplayColor(double intensity) {
        double value = Math.max(0.0, Math.min(1.0, intensity));
        if (value <= 0.005) {
            return 0;
        }
        int alpha = Math.max(20, Math.min(255, (int) Math.round(255 * Math.pow(value, 0.72))));
        return (alpha << 24) | 0x00FFFFFF;
    }

    private static double sourceIntensityAt(BufferedImage image, double x, double y, String colorMode) {
        int sx = (int) Math.round(x);
        int sy = (int) Math.round(y);
        if (!isInsideRaster(image, sx, sy)) {
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

    private static boolean isInsideRaster(BufferedImage image, double x, double y) {
        int sx = (int) Math.round(x);
        int sy = (int) Math.round(y);
        return sx >= 0 && sy >= 0 && sx < image.getWidth() && sy < image.getHeight();
    }

    static double directIntensity(int red, int green, int blue, int alpha, IntensitySamplingMode mode) {
        return switch (mode == null ? IntensitySamplingMode.COLOR_MAPPING : mode) {
            case DIRECT_ALPHA -> alpha / 255.0;
            case DIRECT_VALUE -> Math.max(red, Math.max(green, blue)) / 255.0;
            case DIRECT_LUMINANCE -> (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0;
            case COLOR_MAPPING -> 0.0;
        };
    }

    static double colorIntensity(int red, int green, int blue, String colorMode) {
        double luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0;
        float[] hsv = java.awt.Color.RGBtoHSB(red, green, blue, null);
        double saturation = hsv[1];
        double value = hsv[2];
        double hue = hsv[0] * 360.0;

        String mode = colorMode == null ? "hot" : colorMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (isCombinedColorMode(mode)) {
            return combinedIntensity(red, green, blue, hue, saturation, luminance, value, mode);
        }
        return singleColorIntensity(red, green, blue, hue, saturation, luminance, value, mode);
    }

    private static double singleColorIntensity(
        int red,
        int green,
        int blue,
        double hue,
        double saturation,
        double luminance,
        double value,
        String mode
    ) {
        return switch (mode) {
            case "bluered" -> blueRedIntensity(red, blue, hue, saturation, luminance, value);
            case "bluered-cool" -> blueRedCoolIntensity(red, green, blue, hue, saturation, luminance, value);
            case "bluered-corridor" -> corridorPresence(blueRedCoolIntensity(red, green, blue, hue, saturation, luminance, value));
            case "gray" -> grayIntensity(hue, saturation, luminance, value);
            case "gray-magenta" -> grayMagentaIntensity(hue, saturation, value);
            case "gray-corridor" -> corridorPresence(grayIntensity(hue, saturation, luminance, value));
            case "gray-strict" -> strictGrayIntensity(hue, saturation, luminance, value);
            case "purple" -> purpleIntensity(hue, saturation, luminance, value);
            case "purple-strict" -> strictPurpleIntensity(hue, saturation, luminance, value);
            case "blue" -> blueIntensity(red, green, blue, hue, saturation, luminance, value);
            case "dual" -> dualColorIntensity(red, green, blue, hue, saturation, luminance, value);
            case "dual-corridor" -> corridorPresence(dualColorIntensity(red, green, blue, hue, saturation, luminance, value));
            case "hot-corridor" -> corridorPresence(0.85 * luminance + 0.15 * value);
            case "hot-strict" -> Math.pow(0.85 * luminance + 0.15 * value, 1.35);
            case "hot" -> 0.85 * luminance + 0.15 * value;
            default -> 0.85 * luminance + 0.15 * value;
        };
    }

    private static double combinedIntensity(
        int red,
        int green,
        int blue,
        double hue,
        double saturation,
        double luminance,
        double value,
        String mode
    ) {
        List<IntensityComponent> components = intensityComponents(mode);
        double weighted = 0.0;
        double totalWeight = 0.0;
        for (IntensityComponent component : components) {
            weighted += component.weight()
                * singleColorIntensity(red, green, blue, hue, saturation, luminance, value, component.mode());
            totalWeight += component.weight();
        }
        if (totalWeight <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, weighted / totalWeight));
    }

    static boolean isCombinedColorMode(String colorMode) {
        String mode = colorMode == null ? "" : colorMode.trim().toLowerCase(java.util.Locale.ROOT);
        return "bluered-combined".equals(mode)
            || "gray-combined".equals(mode)
            || "multi-combined".equals(mode)
            || "all-colors-combined".equals(mode);
    }

    static List<IntensityComponent> intensityComponents(String colorMode) {
        String mode = colorMode == null ? "" : colorMode.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "bluered-combined" -> List.of(
                new IntensityComponent("bluered", 0.42),
                new IntensityComponent("bluered-cool", 0.24),
                new IntensityComponent("bluered-corridor", 0.22),
                new IntensityComponent("dual-corridor", 0.12)
            );
            case "gray-combined" -> List.of(
                new IntensityComponent("gray", 0.40),
                new IntensityComponent("gray-magenta", 0.25),
                new IntensityComponent("gray-corridor", 0.23),
                new IntensityComponent("dual-corridor", 0.12)
            );
            case "multi-combined" -> List.of(
                new IntensityComponent("dual-corridor", 0.30),
                new IntensityComponent("bluered-corridor", 0.25),
                new IntensityComponent("gray-corridor", 0.20),
                new IntensityComponent("hot-corridor", 0.15),
                new IntensityComponent("blue", 0.10)
            );
            case "all-colors-combined" -> List.of(
                new IntensityComponent("source:bluered", 0.30),
                new IntensityComponent("source:gray", 0.22),
                new IntensityComponent("source:purple", 0.20),
                new IntensityComponent("source:hot", 0.16),
                new IntensityComponent("source:blue", 0.12)
            );
            default -> List.of(new IntensityComponent(mode.isBlank() ? "hot" : mode, 1.0));
        };
    }

    private static double blueRedIntensity(int red, int blue, double hue, double saturation, double luminance, double value) {
        double redDominance = Math.max(0.0, red - blue * 0.55) / 255.0;
        double redScore = Math.max(hueAffinity(hue, 350.0, 40.0), hueAffinity(hue, 8.0, 32.0))
            * saturation * (0.82 + 0.18 * value) * (1.00 + 0.32 * redDominance);
        double magentaScore = Math.max(
            Math.max(hueAffinity(hue, 318.0, 40.0), hueAffinity(hue, 334.0, 34.0)),
            hueAffinity(hue, 285.0, 42.0) * 0.86)
            * saturation * (0.72 + 0.28 * value) * (0.95 + 0.25 * redDominance);
        double darkBlueScore = hueAffinity(hue, 235.0, 52.0)
            * saturation * (0.34 + 0.26 * value + 0.40 * (1.0 - luminance));
        double lightBlueScore = Math.max(hueAffinity(hue, 202.0, 42.0), hueAffinity(hue, 216.0, 50.0))
            * (0.40 + 0.60 * saturation) * (0.20 + 0.34 * luminance + 0.16 * value);
        return Math.max(redScore, Math.max(magentaScore, Math.max(darkBlueScore * 0.82, lightBlueScore * 0.55)));
    }

    private static double blueRedCoolIntensity(
        int red,
        int green,
        int blue,
        double hue,
        double saturation,
        double luminance,
        double value
    ) {
        double redScore = Math.max(hueAffinity(hue, 350.0, 40.0), hueAffinity(hue, 8.0, 32.0))
            * (0.84 + 0.16 * value);
        double magentaScore = Math.max(hueAffinity(hue, 318.0, 42.0), hueAffinity(hue, 334.0, 34.0))
            * (0.72 + 0.28 * value);
        double blueScore = Math.max(hueAffinity(hue, 225.0, 56.0), hueAffinity(hue, 240.0, 48.0))
            * (0.42 + 0.28 * (1.0 - luminance) + 0.30 * value);
        double cyanScore = hueAffinity(hue, 198.0, 42.0) * (0.28 + 0.38 * value + 0.24 * saturation);
        double coolness = Math.max(0.0, blue - red * 0.45 - green * 0.05) / 255.0;
        double redWarmth = Math.max(0.0, red - blue * 0.55) / 255.0;
        double warm = Math.max(redScore * (0.95 + 0.35 * redWarmth), magentaScore);
        double cool = Math.max(blueScore * (0.76 + 0.24 * coolness), cyanScore * 0.76);
        return saturation * Math.max(warm, cool);
    }

    private static double blueIntensity(int red, int green, int blue, double hue, double saturation, double luminance, double value) {
        double blueAffinity = Math.max(hueAffinity(hue, 210.0, 55.0), hueAffinity(hue, 230.0, 45.0));
        double coolness = Math.max(0.0, blue - red * 0.65 - green * 0.15) / 255.0;
        double brightness = 0.72 * luminance + 0.28 * value;
        double saturationFit = 1.0 - Math.min(1.0, Math.abs(saturation - 0.45) / 0.55);
        return (0.55 + 0.45 * blueAffinity) * brightness * (0.70 + 0.30 * saturationFit) * (0.75 + 0.25 * coolness);
    }

    private static double grayIntensity(double hue, double saturation, double luminance, double value) {
        double grayBase = (1.0 - saturation) * (0.04 + 0.16 * luminance) * (0.45 + 0.55 * value);
        double magentaScore = grayMagentaIntensity(hue, saturation, value);
        double violetScore = hueAffinity(hue, 268.0, 46.0) * saturation * (0.66 + 0.34 * value);
        return Math.max(grayBase * 0.70, Math.max(magentaScore, violetScore * 0.88));
    }

    private static double grayMagentaIntensity(double hue, double saturation, double value) {
        double pinkScore = Math.max(hueAffinity(hue, 318.0, 42.0), hueAffinity(hue, 334.0, 34.0))
            * saturation * (0.70 + 0.30 * value);
        double violetScore = hueAffinity(hue, 278.0, 42.0) * saturation * (0.58 + 0.42 * value);
        return Math.max(pinkScore, violetScore * 0.82);
    }

    private static double strictGrayIntensity(double hue, double saturation, double luminance, double value) {
        double chroma = grayIntensity(hue, saturation, luminance, value);
        double contrastGate = Math.max(0.0, (chroma - 0.16) / 0.84);
        double saturationGate = Math.max(0.0, Math.min(1.0, (saturation - 0.22) / 0.38));
        return chroma * contrastGate * (0.35 + 0.65 * saturationGate);
    }

    private static double purpleIntensity(double hue, double saturation, double luminance, double value) {
        double primaryAffinity = hueAffinity(hue, 260.0, 48.0);
        double legacyAffinity = Math.max(hueAffinity(hue, 285.0, 40.0) * 0.86, hueAffinity(hue, 315.0, 38.0) * 0.72);
        double affinity = Math.max(primaryAffinity, legacyAffinity);
        double chromaPath = affinity * (0.42 + 0.58 * saturation) * (0.34 + 0.66 * value);
        double brightLavender = affinity * (0.52 + 0.48 * value) * (0.54 + 0.46 * luminance);
        double paleCore = (0.85 * luminance + 0.15 * value) * (0.58 + 0.42 * affinity) * (1.0 - 0.40 * saturation);
        return Math.max(chromaPath, Math.max(brightLavender * 0.95, paleCore));
    }

    private static double strictPurpleIntensity(double hue, double saturation, double luminance, double value) {
        double purple = purpleIntensity(hue, saturation, luminance, value);
        double saturationGate = Math.max(0.0, Math.min(1.0, (saturation - 0.35) / 0.45));
        double brightnessGate = Math.max(0.0, Math.min(1.0, (value - 0.24) / 0.50));
        return purple * saturationGate * brightnessGate;
    }

    private static double dualColorIntensity(int red, int green, int blue, double hue, double saturation, double luminance, double value) {
        double warmCool = Math.max(
            blueRedCoolIntensity(red, green, blue, hue, saturation, luminance, value),
            Math.max(purpleIntensity(hue, saturation, luminance, value), grayIntensity(hue, saturation, luminance, value) * 0.88)
        );
        double brightCenter = (0.85 * luminance + 0.15 * value) * (0.65 + 0.35 * (1.0 - saturation));
        double blueCenter = blueIntensity(red, green, blue, hue, saturation, luminance, value) * 0.92;
        return Math.max(warmCool, Math.max(brightCenter, blueCenter));
    }

    private static double corridorPresence(double intensity) {
        if (intensity <= 0.0) {
            return 0.0;
        }
        return Math.pow(Math.min(1.0, intensity), 0.55);
    }

    private static double hueAffinity(double hue, double target, double width) {
        double distance = Math.abs(hue - target);
        distance = Math.min(distance, 360.0 - distance);
        return Math.max(0.0, 1.0 - distance / width);
    }

    /**
     * Sampled heatmap evidence at one point along the source way.
     *
     * @param samplingAnchor paired source, raster, and physical-distance position
     * @param normalScreen unit normal used for offset sampling
     * @param peaks candidate heatmap ridges found on this cross-section
     * @param anchorWithinRaster whether the source point was inside the sampled raster
     * @param intensitySamples complete raw and filtered cross-section evidence
     */
    public record CrossSectionProfile(
        ProfileSamplingAnchor samplingAnchor,
        Point2D.Double normalScreen,
        List<CrossSectionPeak> peaks,
        boolean anchorWithinRaster,
        List<IntensitySample> intensitySamples
    ) {
        /** Makes peak and full-profile evidence immutable. */
        public CrossSectionProfile {
            java.util.Objects.requireNonNull(samplingAnchor, "samplingAnchor");
            peaks = List.copyOf(peaks);
            intensitySamples = List.copyOf(intensitySamples);
        }

        /**
         * Returns the source position in the active JOSM projection.
         *
         * @return source position in the active JOSM projection
         */
        public EastNorth anchor() {
            return samplingAnchor.sourceMapCoordinate();
        }

        /**
         * Returns the sampled-raster anchor.
         *
         * @return sampled-raster anchor
         */
        public Point2D.Double anchorScreen() {
            return samplingAnchor.rasterCoordinate();
        }

        /**
         * Returns monotonic cumulative ground distance from the first profile.
         *
         * @return cumulative ground distance in metres
         */
        public double cumulativeGroundDistanceMeters() {
            return samplingAnchor.cumulativeGroundDistanceMeters();
        }

        /**
         * Creates a profile from an explicit physical anchor without retained full-profile samples.
         *
         * @param samplingAnchor paired source, raster, and physical-distance position
         * @param normalScreen raster-space unit normal
         * @param peaks extracted ridge peaks
         */
        public CrossSectionProfile(ProfileSamplingAnchor samplingAnchor, Point2D.Double normalScreen,
            List<CrossSectionPeak> peaks) {
            this(samplingAnchor, normalScreen, peaks, true, List.of());
        }

        /**
         * Creates an in-raster profile without retained full-profile samples.
         *
         * @param anchor profile anchor
         * @param anchorScreen raster-space anchor
         * @param normalScreen raster-space unit normal
         * @param peaks extracted legacy ridge peaks
         */
        public CrossSectionProfile(EastNorth anchor, Point2D.Double anchorScreen,
            Point2D.Double normalScreen, List<CrossSectionPeak> peaks) {
            this(new ProfileSamplingAnchor(anchor, anchorScreen.x, anchorScreen.y, 0.0),
                normalScreen, peaks, true, List.of());
        }

        /**
         * Creates a profile without retained full-profile samples.
         *
         * @param anchor profile anchor
         * @param anchorScreen raster-space anchor
         * @param normalScreen raster-space unit normal
         * @param peaks extracted legacy ridge peaks
         * @param anchorWithinRaster whether the anchor lies in the raster
         */
        public CrossSectionProfile(
            EastNorth anchor,
            Point2D.Double anchorScreen,
            Point2D.Double normalScreen,
            List<CrossSectionPeak> peaks,
            boolean anchorWithinRaster
        ) {
            this(new ProfileSamplingAnchor(anchor, anchorScreen.x, anchorScreen.y, 0.0),
                normalScreen, peaks, anchorWithinRaster, List.of());
        }

        /**
         * Compatibility constructor for legacy and isolated tests without a physical anchor sequence.
         *
         * @param anchor profile anchor in projected coordinates
         * @param anchorScreen raster-space anchor
         * @param normalScreen raster-space unit normal
         * @param peaks extracted ridge peaks
         * @param anchorWithinRaster whether the anchor lies in the raster
         * @param intensitySamples retained raw and filtered cross-section evidence
         */
        public CrossSectionProfile(
            EastNorth anchor,
            Point2D.Double anchorScreen,
            Point2D.Double normalScreen,
            List<CrossSectionPeak> peaks,
            boolean anchorWithinRaster,
            List<IntensitySample> intensitySamples
        ) {
            this(new ProfileSamplingAnchor(anchor, anchorScreen.x, anchorScreen.y, 0.0), normalScreen,
                peaks, anchorWithinRaster, intensitySamples);
        }
    }

    /**
     * Scalar heatmap evidence retained at one lateral cross-section offset.
     *
     * @param offsetPx lateral offset in sampled-raster pixels
     * @param nativeIntensity palette-mapped intensity before filtering
     * @param lightFilteredIntensity intensity after the B3 filter
     * @param standardFilteredIntensity intensity after the B5 filter
     * @param insideRaster whether this sample was inside every required source raster
     */
    public record IntensitySample(
        double offsetPx,
        double nativeIntensity,
        double lightFilteredIntensity,
        double standardFilteredIntensity,
        boolean insideRaster
    ) {
    }

    /**
     * Candidate ridge peak extracted from one cross-section.
     *
     * @param offsetPx lateral offset in sampled raster pixels
     * @param intensity calibrated peak confidence after palette mapping and profile evidence
     * @param supportWidthPx width of the detected heat band around the peak
     * @param syntheticCenter true when the peak is inferred from paired shoulders or fallback evidence
     * @param prominence distance from local noise floor to the profile maximum
     * @param noiseFloor estimated background intensity for the profile
     * @param maxProfileIntensity maximum filtered profile intensity
     * @param gradientStrength local rise/fall evidence around the peak
     * @param gradientBalance balance of left and right gradients
     * @param nativeFilteredAgreement agreement between raw and primary filtered peak centers
     * @param rawCenterPx raw-profile center for the same peak neighborhood
     * @param lightFilteredCenterPx B3 filtered-profile center for the same peak neighborhood
     * @param standardFilteredCenterPx B5 filtered-profile center for the same peak neighborhood
     * @param scaleOffsetRmsPx RMS spread of raw/B3/B5 centers in pixels
     * @param scaleAgreement confidence that the peak is stable across filter scales
     * @param centerUncertaintyPx conservative center uncertainty derived from sample step and scale spread
     */
    public record CrossSectionPeak(
        double offsetPx,
        double intensity,
        double supportWidthPx,
        boolean syntheticCenter,
        double prominence,
        double noiseFloor,
        double maxProfileIntensity,
        double gradientStrength,
        double gradientBalance,
        double nativeFilteredAgreement,
        double rawCenterPx,
        double lightFilteredCenterPx,
        double standardFilteredCenterPx,
        double scaleOffsetRmsPx,
        double scaleAgreement,
        double centerUncertaintyPx
    ) {
        public CrossSectionPeak(double offsetPx, double intensity) {
            this(offsetPx, intensity, 0.0, false);
        }

        public CrossSectionPeak(double offsetPx, double intensity, double supportWidthPx, boolean syntheticCenter) {
            this(offsetPx, intensity, supportWidthPx, syntheticCenter, intensity, 0.0, intensity, 0.0, 0.0, 1.0,
                offsetPx, offsetPx, offsetPx, 0.0, 1.0, 0.5);
        }

        public CrossSectionPeak(
            double offsetPx,
            double intensity,
            double supportWidthPx,
            boolean syntheticCenter,
            double prominence,
            double noiseFloor,
            double maxProfileIntensity,
            double gradientStrength,
            double gradientBalance,
            double nativeFilteredAgreement
        ) {
            this(offsetPx, intensity, supportWidthPx, syntheticCenter, prominence, noiseFloor, maxProfileIntensity,
                gradientStrength, gradientBalance, nativeFilteredAgreement, offsetPx, offsetPx, offsetPx, 0.0, 1.0, 0.5);
        }
    }

    private record OffsetSample(double offsetPx, double intensity) {
    }

    private record ProfileFilters(
        List<OffsetSample> raw,
        List<OffsetSample> lightFiltered,
        List<OffsetSample> standardFiltered,
        int filterStride,
        double physicalSampleStepPx
    ) {
    }

    private record ProfileStats(double maxIntensity, double noiseFloor, double maxProminence) {
    }

    private record GradientEvidence(double strength, double balance) {
    }

    private record ScaleEvidence(
        double rawCenterPx,
        double lightCenterPx,
        double standardCenterPx,
        double scaleOffsetRmsPx,
        double scaleAgreement,
        double centerUncertaintyPx
    ) {
    }

    /**
     * Weighted component used by internal combined color-to-intensity mappings.
     *
     * @param mode detector mapping name
     * @param weight contribution of the mapping to the combined scalar intensity
     */
    public record IntensityComponent(String mode, double weight) {
    }
}

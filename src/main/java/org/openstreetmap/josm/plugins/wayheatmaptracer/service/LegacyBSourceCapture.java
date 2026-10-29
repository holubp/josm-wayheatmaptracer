package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

/** Captures only the numerical settings and actual source pixels consumed by sequential Hybrid. */
public final class LegacyBSourceCapture {
    private LegacyBSourceCapture() { }

    /** Credential-free slide-time choices; source-specific clamps remain those of native Legacy. */
    public record Settings(int visibleHalfWidthPx, int visibleStepPx, InferenceMode inferenceMode,
            int inferenceZoom, int validationZoom, double searchHalfWidthMeters,
            double sampleStepMeters, IntensitySamplingMode intensityMode) {
        public Settings {
            Objects.requireNonNull(inferenceMode);
            Objects.requireNonNull(intensityMode);
            if (visibleHalfWidthPx <= 0 || visibleStepPx <= 0
                    || inferenceZoom < 10 || inferenceZoom > 16
                    || validationZoom < 10 || validationZoom > 16
                    || !Double.isFinite(searchHalfWidthMeters) || searchHalfWidthMeters <= 0
                    || !Double.isFinite(sampleStepMeters) || sampleStepMeters <= 0) {
                throw new IllegalArgumentException("Legacy sampling settings are invalid");
            }
        }

        /** Copy at the configuration boundary; the worker never retains credentials. */
        public static Settings capture(ManagedHeatmapConfig config) {
            Objects.requireNonNull(config);
            return new Settings(config.crossSectionHalfWidthPx(), config.crossSectionStepPx(),
                    config.inferenceMode() == null ? InferenceMode.STABLE_FIXED_SCALE : config.inferenceMode(),
                    TileHeatmapSampler.effectiveInferenceZoom(config),
                    Math.max(10, Math.min(16, config.validationZoom())),
                    Math.max(2, config.searchHalfWidthMeters()), Math.max(.5, config.sampleStepMeters()),
                    config.intensitySamplingMode() == null ? IntensitySamplingMode.COLOR_MAPPING
                            : config.intensitySamplingMode());
        }
    }

    /** Freezes the exact original visible anchors, with native rendered-layer coordinate phase. */
    public static LegacyVisibleProfileSampler.Input visible(Settings settings,
            LiveBPreviewService.VisibleRaster raster, List<ProjectedPoint> source, String detector) {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(raster);
        requireDetector(settings, detector);
        if (source == null || source.size() < 2) {
            throw new IllegalArgumentException("Original projected source is required");
        }
        var projection = Projections.getProjectionByCode(raster.projectionCode());
        if (projection == null) { throw new IllegalArgumentException("Captured projection is unavailable"); }
        var projected = source.stream().map(point -> new EastNorth(point.east(), point.north())).toList();
        var pixels = source.stream().map(point -> new Point2D.Double(
                (point.east() - raster.minimumEast()) / raster.projectionUnitsPerViewPixel()
                        * RenderedHeatmapSampler.RASTER_SCALE,
                (raster.maximumNorth() - point.north()) / raster.projectionUnitsPerViewPixel()
                        * RenderedHeatmapSampler.RASTER_SCALE)).toList();
        var anchors = ProfileSamplingAnchor.pair(projected, pixels, projection).stream().map(anchor ->
                new LegacyVisibleProfileSampler.Anchor(new ProjectedPoint(
                        anchor.sourceMapCoordinate().east(), anchor.sourceMapCoordinate().north()),
                        new RasterPoint(anchor.rasterX(), anchor.rasterY()),
                        anchor.cumulativeGroundDistanceMeters())).toList();
        int halfWidth = Math.max(6, Math.min(120, settings.visibleHalfWidthPx()));
        int step = Math.min(halfWidth, Math.max(1, Math.min(32, settings.visibleStepPx())));
        return new LegacyVisibleProfileSampler.Input(raster, anchors, List.of(detector),
                new LegacyVisibleProfileSampler.Options(halfWidth, step,
                        RenderedHeatmapSampler.RASTER_SCALE, settings.intensityMode()));
    }

    private static void requireDetector(Settings settings, String detector) {
        if (detector == null || detector.isBlank() || !settings.intensityMode().usesColorMapping()
                && !detector.equals(settings.intensityMode().detectorName())) {
            throw new IllegalArgumentException("Detector must match its factual intensity source");
        }
    }

    /**
     * Prepares native Legacy sampling from the actual acquired inference rasters, with no extra
     * network requests. Runtime generation and aggregate-receipt validation remain the live owner's
     * responsibility; this numerical freeze grants no acquisition or edit authority.
     */
    public static LegacyManagedProfileSampler.Input managed(Settings settings,
            ManagedModernPreviewSource.SourceRasters sources, List<ProjectedPoint> source,
            String projectionCode, String detector) {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(sources);
        requireDetector(settings, detector);
        var projection = Projections.getProjectionByCode(projectionCode);
        if (projection == null || source == null || source.size() < 2
                || sources.selectedRaster().zoom() != settings.inferenceZoom()) {
            throw new IllegalArgumentException("Captured managed sampling frame is unavailable");
        }
        boolean aggregate = "all-colors-combined".equals(detector);
        if (aggregate && sources.aggregateAvailability()
                != ManagedModernPreviewSource.AggregateAvailability.COMPLETE) {
            throw new IllegalArgumentException("Complete acquired aggregate is required");
        }
        double sumLatitude = 0;
        for (var point : source) {
            sumLatitude += projection.eastNorth2latlon(new EastNorth(point.east(), point.north())).lat();
        }
        double latitude = sumLatitude / source.size();
        var inference = TileHeatmapSampler.legacySamplingParameters(settings.inferenceZoom(), latitude,
                settings.searchHalfWidthMeters(), settings.sampleStepMeters());
        int validationZoom = Math.min(settings.inferenceZoom(), settings.validationZoom());
        var validation = TileHeatmapSampler.legacySamplingParameters(validationZoom, latitude,
                settings.searchHalfWidthMeters(), settings.sampleStepMeters());
        var mosaics = new LinkedHashMap<String, LegacyManagedProfileSampler.Mosaic>();
        // Preserve acquired palette order: it determines native aggregate reference ownership.
        for (var entry : sources.palettes().entrySet()) {
            if (!aggregate && !entry.getKey().equals(sources.plan().selectedColor())) { continue; }
            var raster = entry.getValue();
            if (!(raster.transform() instanceof SupportedInputRasterTransform.WebMercator transform)
                    || transform.rasterPixelsPerWorldPixel() != 1 || transform.zoom() != settings.inferenceZoom()) {
                throw new IllegalArgumentException("Managed Legacy requires its exact native world-pixel frame");
            }
            for (boolean valid : raster.validity()) {
                if (!valid) { throw new IllegalArgumentException("Managed Legacy raster support is incomplete"); }
            }
            var image = settings.inferenceMode().stableFixedScale()
                    ? TileHeatmapSampler.stableInferenceImage(raster.image(), entry.getKey(), raster.zoom())
                    : raster.image();
            double virtualScale = inference.metersPerPixel()
                    / TileHeatmapSampler.REFERENCE_VIEW_METERS_PER_PIXEL * RenderedHeatmapSampler.RASTER_SCALE;
            mosaics.put(entry.getKey() + "@" + raster.zoom(), new LegacyManagedProfileSampler.Mosaic(
                    entry.getKey(), raster.zoom(), transform.inputWorldPixelBoundaryX(),
                    transform.inputWorldPixelBoundaryY(), image.getWidth(), image.getHeight(),
                    image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()),
                    inference, settings.inferenceMode().stableFixedScale(), virtualScale));
        }
        return new LegacyManagedProfileSampler.Input(settings.inferenceMode(), settings.inferenceZoom(),
                settings.validationZoom(), settings.inferenceZoom(), validationZoom, TileHeatmapSampler.TILE_SIZE,
                mosaics, inference, validation, source, projectionCode, sources.plan().selectedColor(),
                List.of(detector), settings.intensityMode(), RenderedHeatmapSampler.RASTER_SCALE);
    }
}

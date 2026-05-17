package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginDirectories;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

/**
 * Downloads managed Strava source tiles, builds mosaics, and samples cross-sections at fixed tile zooms.
 */
public final class TileHeatmapSampler {
    public static final int TILE_SIZE = 512;
    public static final int DEFAULT_INFERENCE_ZOOM = 15;
    public static final int DEFAULT_VALIDATION_ZOOM = 13;
    public static final double REFERENCE_VIEW_METERS_PER_PIXEL = 0.389;
    public static final double REFERENCE_RASTER_SCALE = RenderedHeatmapSampler.RASTER_SCALE;
    private static final String HEATMAP_URL = "https://content-a.strava.com/identified/globalheat/%s/%s/%d/%d/%d.png%s";
    private static final List<String> BASE_AGGREGATE_COLORS = List.of("hot", "blue", "bluered", "purple", "gray");

    /**
     * Prepares source-tile mosaics for a selected polyline using normal configured search width.
     *
     * @param config current plugin settings
     * @param sourcePolyline source way geometry in projected coordinates
     * @param tileColors Strava color schemes to download
     * @return downloaded mosaic set for inference and validation zooms
     */
    public TileMosaicSet prepare(
        ManagedHeatmapConfig config,
        List<EastNorth> sourcePolyline,
        List<String> tileColors
    ) {
        return prepare(config, sourcePolyline, tileColors, false);
    }

    /**
     * Prepares source-tile mosaics for a selected polyline.
     *
     * @param config current plugin settings
     * @param sourcePolyline source way geometry in projected coordinates
     * @param tileColors Strava color schemes to download
     * @param sketchLikeSelection whether the selected geometry is a rough full-way sketch
     * @return downloaded mosaic set for inference and validation zooms
     */
    public TileMosaicSet prepare(
        ManagedHeatmapConfig config,
        List<EastNorth> sourcePolyline,
        List<String> tileColors,
        boolean sketchLikeSelection
    ) {
        if (sourcePolyline.size() < 2) {
            throw new IllegalStateException("Selected segment is too short for tile sampling.");
        }
        InferenceMode inferenceMode = inferenceMode(config);
        int requestedInferenceZoom = clampZoom(config.inferenceZoom(), 10, 16);
        int requestedValidationZoom = clampZoom(config.validationZoom(), 10, 16);
        int inferenceZoom = effectiveInferenceZoom(config);
        int validationZoom = effectiveValidationZoom(config);
        double latitude = representativeLatitude(sourcePolyline);
        SamplingParameters inference = parametersFor(config, inferenceZoom, latitude, sketchLikeSelection);
        SamplingParameters validation = parametersFor(config, validationZoom, latitude, sketchLikeSelection);

        Map<String, TileMosaic> mosaics = new LinkedHashMap<>();
        for (String color : tileColors) {
            TileMosaic inferenceMosaic = loadMosaic(config, color, sourcePolyline, inference, inferenceMode.stableFixedScale());
            mosaics.put(key(color, inferenceZoom), inferenceMosaic);
            if (validationZoom != inferenceZoom) {
                TileMosaic validationMosaic = loadMosaic(config, color, sourcePolyline, validation, false);
                mosaics.put(key(color, validationZoom), validationMosaic);
            }
        }
        PluginLog.verbose(
            "Prepared managed heatmap tile sampling, mode=%s requestedInferenceZoom=%d inferenceZoom=%d validationZoom=%d colors=%s halfWidth=%.1fm step=%.1fm sketch=%s.",
            inferenceMode.name(), requestedInferenceZoom, inferenceZoom, validationZoom, tileColors,
            inference.halfWidthMeters(), inference.sampleStepMeters(), sketchLikeSelection
        );
        return new TileMosaicSet(inferenceMode, requestedInferenceZoom, requestedValidationZoom,
            inferenceZoom, validationZoom, TILE_SIZE, mosaics, inference, validation);
    }

    /**
     * Samples cross-section profiles from a prepared source-tile mosaic.
     *
     * @param mosaic mosaic to sample
     * @param sourcePolyline source way geometry in projected coordinates
     * @param detectorMode palette mapping or direct intensity detector name
     * @param config current plugin settings
     * @return sampled cross-section profiles with peak diagnostics
     */
    public List<RenderedHeatmapSampler.CrossSectionProfile> sampleProfiles(
        TileMosaic mosaic,
        List<EastNorth> sourcePolyline,
        String detectorMode,
        ManagedHeatmapConfig config
    ) {
        List<EastNorth> dense = PolylineMath.resampleBySpacing(sourcePolyline, Math.max(4.0, mosaic.parameters().sampleStepMeters()));
        if (dense.size() < 2) {
            return List.of();
        }
        List<Point2D.Double> local = new ArrayList<>(dense.size());
        for (EastNorth point : dense) {
            Point2D.Double world = toWorldPixel(point, mosaic.zoom());
            local.add(new Point2D.Double(
                (world.x - mosaic.originWorldPxX()) * mosaic.virtualRasterScale(),
                (world.y - mosaic.originWorldPxY()) * mosaic.virtualRasterScale()
            ));
        }
        int referenceHalfWidthPx = Math.max(1, (int) Math.round(mosaic.parameters().halfWidthMeters() / REFERENCE_VIEW_METERS_PER_PIXEL));
        int referenceStepPx = Math.max(1, (int) Math.round(mosaic.parameters().sampleStepMeters() / REFERENCE_VIEW_METERS_PER_PIXEL));
        PluginLog.verbose("Sampling %d fixed-tile cross-sections for color '%s' z%d (halfWidth=%d ref px/%.1fm, step=%d ref px/%.1fm, virtualScale=%.2f).",
            dense.size(),
            mosaic.color(),
            mosaic.zoom(),
            referenceHalfWidthPx,
            mosaic.parameters().halfWidthMeters(),
            referenceStepPx,
            mosaic.parameters().sampleStepMeters(),
            mosaic.virtualRasterScale());
        return new RenderedHeatmapSampler().sampleProfilesOnScaledRaster(
            mosaic.image(),
            local,
            referenceHalfWidthPx,
            referenceStepPx,
            detectorMode,
            REFERENCE_RASTER_SCALE,
            mosaic.virtualRasterScale(),
            config.intensitySamplingMode()
        );
    }

    /**
     * Samples cross-section profiles from a fused intensity field across multiple source color mosaics.
     *
     * @param mosaics prepared source-tile mosaics for the same zoom and source geometry
     * @param zoom source tile zoom to aggregate
     * @param sourcePolyline source way geometry in projected coordinates
     * @return sampled profiles from the aggregated color intensity field
     */
    public List<RenderedHeatmapSampler.CrossSectionProfile> sampleAggregatedProfiles(
        TileMosaicSet mosaics,
        int zoom,
        List<EastNorth> sourcePolyline
    ) {
        AggregateSourceFrame frame = aggregateSourceFrame(mosaics, zoom);
        if (frame == null) {
            return List.of();
        }
        TileMosaic reference = frame.reference();
        Map<String, BufferedImage> images = frame.images();
        List<EastNorth> dense = PolylineMath.resampleBySpacing(sourcePolyline, Math.max(4.0, reference.parameters().sampleStepMeters()));
        if (dense.size() < 2) {
            return List.of();
        }
        List<Point2D.Double> local = new ArrayList<>(dense.size());
        for (EastNorth point : dense) {
            Point2D.Double world = toWorldPixel(point, reference.zoom());
            local.add(new Point2D.Double(
                (world.x - reference.originWorldPxX()) * reference.virtualRasterScale(),
                (world.y - reference.originWorldPxY()) * reference.virtualRasterScale()
            ));
        }
        int referenceHalfWidthPx = Math.max(1, (int) Math.round(reference.parameters().halfWidthMeters() / REFERENCE_VIEW_METERS_PER_PIXEL));
        int referenceStepPx = Math.max(1, (int) Math.round(reference.parameters().sampleStepMeters() / REFERENCE_VIEW_METERS_PER_PIXEL));
        PluginLog.verbose("Sampling %d fixed-tile aggregate cross-sections at z%d from colors %s.",
            dense.size(), zoom, images.keySet());
        return new RenderedHeatmapSampler().sampleProfilesOnAggregatedScaledRasters(
            images,
            local,
            referenceHalfWidthPx,
            referenceStepPx,
            REFERENCE_RASTER_SCALE,
            reference.virtualRasterScale()
        );
    }

    /**
     * Builds a colorized visualization of the same fused scalar intensity field used by all-color detection.
     *
     * @param mosaics prepared source-tile mosaics
     * @param zoom source tile zoom to visualize
     * @return georeferenced aggregate intensity image, or {@code null} when no aggregate frame exists
     */
    public AggregateVisualization buildAggregatedIntensityVisualization(TileMosaicSet mosaics, int zoom) {
        AggregateSourceFrame frame = aggregateSourceFrame(mosaics, zoom);
        if (frame == null) {
            return null;
        }
        TileMosaic reference = frame.reference();
        BufferedImage image = RenderedHeatmapSampler.renderAggregatedIntensityRaster(frame.images());
        EastNorth topLeft = toEastNorth(reference.originWorldPxX(), reference.originWorldPxY(), reference.zoom());
        EastNorth topRight = toEastNorth(reference.originWorldPxX() + image.getWidth(), reference.originWorldPxY(), reference.zoom());
        EastNorth bottomLeft = toEastNorth(reference.originWorldPxX(), reference.originWorldPxY() + image.getHeight(), reference.zoom());
        EastNorth bottomRight = toEastNorth(reference.originWorldPxX() + image.getWidth(), reference.originWorldPxY() + image.getHeight(), reference.zoom());
        return new AggregateVisualization(
            image,
            reference.zoom(),
            frame.images().keySet().stream().toList(),
            topLeft,
            topRight,
            bottomLeft,
            bottomRight,
            aggregateMetadataJson(reference, frame.images())
        );
    }

    /**
     * Projects mosaic-local candidate points back to JOSM projected coordinates.
     *
     * @param mosaic source mosaic that defines the world-pixel origin
     * @param localPoints candidate points in virtual raster coordinates
     * @return candidate points in projected coordinates
     */
    public List<EastNorth> projectCandidate(TileMosaic mosaic, List<Point2D.Double> localPoints) {
        return localPoints.stream()
            .map(point -> toEastNorth(
                point.x / mosaic.virtualRasterScale() + mosaic.originWorldPxX(),
                point.y / mosaic.virtualRasterScale() + mosaic.originWorldPxY(),
                mosaic.zoom()))
            .toList();
    }

    private TileMosaic loadMosaic(
        ManagedHeatmapConfig config,
        String color,
        List<EastNorth> sourcePolyline,
        SamplingParameters parameters,
        boolean stableInference
    ) {
        List<Point2D.Double> worldPixels = sourcePolyline.stream()
            .map(point -> toWorldPixel(point, parameters.zoom()))
            .toList();
        BoundsPx bounds = boundsFor(worldPixels, Math.max(64, parameters.halfWidthPx() * 3));
        int minTileX = floorTile(bounds.minX());
        int maxTileX = floorTile(bounds.maxX());
        int minTileY = floorTile(bounds.minY());
        int maxTileY = floorTile(bounds.maxY());
        double originX = minTileX * (double) TILE_SIZE;
        double originY = minTileY * (double) TILE_SIZE;
        int width = (maxTileX - minTileX + 1) * TILE_SIZE;
        int height = (maxTileY - minTileY + 1) * TILE_SIZE;

        BufferedImage rawMosaic = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = rawMosaic.createGraphics();
        List<TileRecord> records = new ArrayList<>();
        Map<String, BufferedImage> tileImages = new LinkedHashMap<>();
        try {
            for (int x = minTileX; x <= maxTileX; x++) {
                for (int y = minTileY; y <= maxTileY; y++) {
                    FetchedTile fetched = fetchTile(config, color, parameters.zoom(), x, y);
                    TileRecord record = fetched.record();
                    records.add(record);
                    if (!record.usable()) {
                        throw new IllegalStateException("Required heatmap tile is not usable: " + record.describe());
                    }
                    graphics.drawImage(fetched.image(), (x - minTileX) * TILE_SIZE, (y - minTileY) * TILE_SIZE, null);
                    tileImages.put(color + "/z" + parameters.zoom() + "-x" + x + "-y" + y + ".png", fetched.image());
                }
            }
        } finally {
            graphics.dispose();
        }
        BufferedImage samplingImage = stableInference ? stableInferenceImage(rawMosaic, color, parameters.zoom()) : rawMosaic;
        double virtualRasterScale = parameters.metersPerPixel() / REFERENCE_VIEW_METERS_PER_PIXEL * REFERENCE_RASTER_SCALE;
        return new TileMosaic(color, parameters.zoom(), originX, originY, samplingImage, records, tileImages,
            parameters, stableInference, virtualRasterScale);
    }

    private BufferedImage stableInferenceImage(BufferedImage source, String color, int zoom) {
        int radius = stableInferenceDilationRadius(zoom);
        if (radius <= 0) {
            PluginLog.verbose("Using raw stable fixed-scale inference raster for color '%s' z%d without heat dilation.",
                color, zoom);
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        int[] horizontalArgb = new int[width * height];
        double[] horizontalIntensity = new double[width * height];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int bestArgb = 0;
                double bestIntensity = 0.0;
                for (int xx = Math.max(0, x - radius); xx <= Math.min(width - 1, x + radius); xx++) {
                    int argb = source.getRGB(xx, y);
                    double intensity = detectorIntensity(argb, color);
                    if (intensity > bestIntensity) {
                        bestIntensity = intensity;
                        bestArgb = argb;
                    }
                }
                horizontalArgb[row + x] = bestArgb;
                horizontalIntensity[row + x] = bestIntensity;
            }
        }

        BufferedImage expanded = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int bestArgb = 0;
                double bestIntensity = 0.0;
                for (int yy = Math.max(0, y - radius); yy <= Math.min(height - 1, y + radius); yy++) {
                    int index = yy * width + x;
                    if (horizontalIntensity[index] > bestIntensity) {
                        bestIntensity = horizontalIntensity[index];
                        bestArgb = horizontalArgb[index];
                    }
                }
                expanded.setRGB(x, y, bestArgb);
            }
        }
        PluginLog.verbose("Built stable fixed-scale inference raster for color '%s' z%d using %d px heat dilation.",
            color, zoom, radius);
        return expanded;
    }

    static int stableInferenceDilationRadius(int zoom) {
        if (zoom >= 15) {
            return 0;
        }
        return clamp(15 - zoom, 1, 2);
    }

    private double detectorIntensity(int argb, String color) {
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha == 0) {
            return 0.0;
        }
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return RenderedHeatmapSampler.colorIntensity(red, green, blue, color);
    }

    private FetchedTile fetchTile(ManagedHeatmapConfig config, String color, int zoom, int x, int y) {
        String activity = safe(config.activity(), "all");
        String tileColor = safe(color, "hot");
        File cacheFile = managedTileCacheFile(config, activity, tileColor, zoom, x, y);
        FetchedTile cached = readCachedTile(cacheFile, tileColor, zoom, x, y);
        if (cached != null) {
            return cached;
        }
        String url = HEATMAP_URL.formatted(activity, tileColor, zoom, x, y, "");
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Cookie", config.toCookieHeader());
            connection.setRequestProperty("User-Agent", "JOSM WayHeatmapTracer");
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                return failedTile(tileColor, zoom, x, y, response, "http-error", "HTTP " + response);
            }
            byte[] bytes = connection.getInputStream().readAllBytes();
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return failedTile(tileColor, zoom, x, y, response, "decode-error", "tile was not an image");
            }
            TileRecord record = classifyTile(tileColor, zoom, x, y, response, bytes, image);
            if (record.usable()) {
                writeCachedTile(cacheFile, bytes, tileColor, zoom, x, y);
            }
            return new FetchedTile(image, record);
        } catch (IOException ex) {
            return failedTile(tileColor, zoom, x, y, -1, "network-error", ex.getMessage());
        }
    }

    private FetchedTile readCachedTile(File cacheFile, String color, int zoom, int x, int y) {
        if (!cacheFile.isFile()) {
            return null;
        }
        Path path = cacheFile.toPath();
        try {
            byte[] bytes = Files.readAllBytes(path);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                Files.deleteIfExists(path);
                return null;
            }
            TileRecord record = classifyTile(color, zoom, x, y, 200, bytes, image);
            if (!record.usable()) {
                Files.deleteIfExists(path);
                return null;
            }
            return new FetchedTile(image, record);
        } catch (IOException ex) {
            return null;
        }
    }

    private void writeCachedTile(File cacheFile, byte[] bytes, String color, int zoom, int x, int y) {
        try {
            File parent = cacheFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.write(cacheFile.toPath(), bytes);
        } catch (IOException ex) {
            PluginLog.verbose("Unable to cache managed heatmap tile %s z%d x=%d y=%d: %s",
                color, zoom, x, y, ex.getMessage());
        }
    }

    static File managedTileCacheFile(ManagedHeatmapConfig config, String activity, String color, int zoom, int x, int y) {
        File root = new File(PluginDirectories.ensurePluginDataDirectory(), "managed-source-tile-cache");
        File generation = new File(root, "cache-" + Math.max(0L, config.cacheBuster()));
        File activityDir = new File(generation, safePathPart(activity, "all"));
        File colorDir = new File(activityDir, safePathPart(color, "hot"));
        return new File(new File(new File(colorDir, Integer.toString(zoom)), Integer.toString(x)), y + ".png");
    }

    private static String safePathPart(String value, String fallback) {
        String normalized = value == null || value.isBlank()
            ? fallback
            : value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9_.-]", "_");
    }

    private FetchedTile failedTile(String color, int zoom, int x, int y, int response, String quality, String error) {
        TileRecord record = new TileRecord(color, zoom, x, y, false, response, 0, 0, 0, "", quality, 0.0, 0.0, 0, error);
        return new FetchedTile(new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB), record);
    }

    private TileRecord classifyTile(String color, int zoom, int x, int y, int response, byte[] bytes, BufferedImage image) {
        String hash = sha256(bytes);
        int width = image.getWidth();
        int height = image.getHeight();
        if (width != TILE_SIZE || height != TILE_SIZE) {
            return new TileRecord(color, zoom, x, y, false, response, bytes.length, width, height, hash,
                "bad-dimensions", 0.0, 0.0, 0, "expected " + TILE_SIZE + "x" + TILE_SIZE);
        }

        int nonTransparent = 0;
        int heatPixels = 0;
        java.util.Set<Integer> sampledColors = new java.util.HashSet<>();
        for (int py = 0; py < height; py += 8) {
            for (int px = 0; px < width; px += 8) {
                int argb = image.getRGB(px, py);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha > 16) {
                    nonTransparent++;
                }
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;
                if (RenderedHeatmapSampler.colorIntensity(red, green, blue, color) > 0.16) {
                    heatPixels++;
                }
                sampledColors.add(argb);
            }
        }
        int samples = Math.max(1, (width / 8) * (height / 8));
        double opaqueRatio = (double) nonTransparent / samples;
        double heatCoverage = (double) heatPixels / samples;
        boolean placeholderSuspected = opaqueRatio > 0.92 && heatCoverage < 0.003 && sampledColors.size() <= 24;
        String quality = placeholderSuspected ? "placeholder-suspected" : (heatCoverage == 0.0 ? "empty-valid" : "valid");
        boolean usable = !placeholderSuspected;
        String error = placeholderSuspected ? "tile looks like an authentication/error placeholder; clear cache or refresh cookies" : "";
        return new TileRecord(color, zoom, x, y, usable, response, bytes.length, width, height, hash,
            quality, opaqueRatio, heatCoverage, sampledColors.size(), error);
    }

    private SamplingParameters parametersFor(ManagedHeatmapConfig config, int zoom, double latitude, boolean sketchLikeSelection) {
        double metersPerPixel = metersPerPixel(zoom, latitude);
        double halfMeters = effectiveSearchHalfWidthMeters(config, sketchLikeSelection);
        double stepMeters = Math.max(0.5, config.sampleStepMeters());
        int halfWidthPx = clamp((int) Math.round(halfMeters / metersPerPixel), 6, 96);
        int stepPx = clamp((int) Math.round(stepMeters / metersPerPixel), 1, Math.max(1, halfWidthPx / 3));
        return new SamplingParameters(zoom, latitude, metersPerPixel, halfMeters, stepMeters, halfWidthPx, stepPx);
    }

    static double effectiveSearchHalfWidthMeters(ManagedHeatmapConfig config, boolean sketchLikeSelection) {
        return Math.max(2.0, config.searchHalfWidthMeters());
    }

    private double representativeLatitude(List<EastNorth> points) {
        double sum = 0.0;
        int count = 0;
        for (EastNorth point : points) {
            sum += ProjectionRegistry.getProjection().eastNorth2latlon(point).lat();
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    static double metersPerPixel(int zoom, double latitude) {
        return Math.cos(Math.toRadians(latitude)) * 156543.03392804097 / (Math.pow(2.0, zoom) * (TILE_SIZE / 256.0));
    }

    private Point2D.Double toWorldPixel(EastNorth eastNorth, int zoom) {
        LatLon latLon = ProjectionRegistry.getProjection().eastNorth2latlon(eastNorth);
        double lat = Math.max(-85.05112878, Math.min(85.05112878, latLon.lat()));
        double lon = latLon.lon();
        double scale = TILE_SIZE * Math.pow(2.0, zoom);
        double x = (lon + 180.0) / 360.0 * scale;
        double sinLat = Math.sin(Math.toRadians(lat));
        double y = (0.5 - Math.log((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)) * scale;
        return new Point2D.Double(x, y);
    }

    private EastNorth toEastNorth(double worldX, double worldY, int zoom) {
        double scale = TILE_SIZE * Math.pow(2.0, zoom);
        double lon = worldX / scale * 360.0 - 180.0;
        double mercator = Math.PI * (1.0 - 2.0 * worldY / scale);
        double lat = Math.toDegrees(Math.atan(Math.sinh(mercator)));
        return ProjectionRegistry.getProjection().latlon2eastNorth(new LatLon(lat, lon));
    }

    private BoundsPx boundsFor(List<Point2D.Double> points, double margin) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Point2D.Double point : points) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
        return new BoundsPx(minX - margin, minY - margin, maxX + margin, maxY + margin);
    }

    private int floorTile(double worldPx) {
        return (int) Math.floor(worldPx / TILE_SIZE);
    }

    static int effectiveInferenceZoom(ManagedHeatmapConfig config) {
        return clampZoom(config.inferenceZoom(), 10, 16);
    }

    static int effectiveValidationZoom(ManagedHeatmapConfig config) {
        int requestedValidationZoom = clampZoom(config.validationZoom(), 10, 16);
        int inferenceZoom = effectiveInferenceZoom(config);
        return clampZoom(Math.min(inferenceZoom, requestedValidationZoom), 10, 16);
    }

    private static InferenceMode inferenceMode(ManagedHeatmapConfig config) {
        return config.inferenceMode() == null ? InferenceMode.STABLE_FIXED_SCALE : config.inferenceMode();
    }

    private static int clampZoom(int zoom, int min, int max) {
        return Math.max(min, Math.min(max, zoom));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String key(String color, int zoom) {
        return color + "@" + zoom;
    }

    private boolean sameSamplingFrame(TileMosaic reference, TileMosaic mosaic) {
        return reference.zoom() == mosaic.zoom()
            && Math.abs(reference.originWorldPxX() - mosaic.originWorldPxX()) < 1e-9
            && Math.abs(reference.originWorldPxY() - mosaic.originWorldPxY()) < 1e-9
            && reference.image().getWidth() == mosaic.image().getWidth()
            && reference.image().getHeight() == mosaic.image().getHeight()
            && Math.abs(reference.virtualRasterScale() - mosaic.virtualRasterScale()) < 1e-9;
    }

    private AggregateSourceFrame aggregateSourceFrame(TileMosaicSet mosaics, int zoom) {
        List<TileMosaic> colorMosaics = mosaics.mosaics().values().stream()
            .filter(mosaic -> mosaic.zoom() == zoom)
            .filter(mosaic -> BASE_AGGREGATE_COLORS.contains(mosaic.color()))
            .toList();
        if (colorMosaics.isEmpty()) {
            return null;
        }
        TileMosaic reference = colorMosaics.get(0);
        Map<String, BufferedImage> images = new LinkedHashMap<>();
        for (TileMosaic mosaic : colorMosaics) {
            if (sameSamplingFrame(reference, mosaic)) {
                images.put(mosaic.color(), mosaic.image());
            }
        }
        List<String> missing = BASE_AGGREGATE_COLORS.stream()
            .filter(color -> !images.containsKey(color))
            .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("All-color heatmap aggregation requires matching source mosaics for "
                + BASE_AGGREGATE_COLORS + "; missing " + missing + ".");
        }
        return new AggregateSourceFrame(reference, images);
    }

    private String aggregateMetadataJson(TileMosaic reference, Map<String, BufferedImage> images) {
        StringBuilder builder = new StringBuilder("{\"type\":\"all-colors-combined-visualization\",")
            .append("\"zoom\":").append(reference.zoom())
            .append(",\"palette\":\"white-on-transparent\",")
            .append("\"colors\":[");
        int index = 0;
        for (String color : images.keySet()) {
            if (index++ > 0) {
                builder.append(',');
            }
            builder.append('"').append(color).append('"');
        }
        builder.append("],\"weights\":{");
        index = 0;
        for (String color : images.keySet()) {
            if (index++ > 0) {
                builder.append(',');
            }
            builder.append('"').append(color).append("\":")
                .append(RenderedHeatmapSampler.aggregateSourceWeight(color));
        }
        return builder.append("}}").toString();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            return "";
        }
    }

    /**
     * Collection of prepared mosaics for inference and validation zooms.
     *
     * @param inferenceMode configured inference strategy
     * @param requestedInferenceZoom user-requested inference zoom
     * @param requestedValidationZoom user-requested validation zoom
     * @param inferenceZoom effective inference zoom
     * @param validationZoom effective validation zoom
     * @param tileSize source tile size in pixels
     * @param mosaics mosaics keyed by color and zoom
     * @param inferenceParameters effective inference sampling parameters
     * @param validationParameters effective validation sampling parameters
     */
    public record TileMosaicSet(
        InferenceMode inferenceMode,
        int requestedInferenceZoom,
        int requestedValidationZoom,
        int inferenceZoom,
        int validationZoom,
        int tileSize,
        Map<String, TileMosaic> mosaics,
        SamplingParameters inferenceParameters,
        SamplingParameters validationParameters
    ) {
        /**
         * Returns the inference mosaic for a color.
         *
         * @param color Strava color scheme name
         * @return prepared inference mosaic
         */
        public TileMosaic require(String color) {
            return require(color, inferenceZoom);
        }

        /**
         * Returns the mosaic for a color and zoom.
         *
         * @param color Strava color scheme name
         * @param zoom source tile zoom
         * @return prepared mosaic
         */
        public TileMosaic require(String color, int zoom) {
            TileMosaic mosaic = mosaics.get(color + "@" + zoom);
            if (mosaic == null) {
                throw new IllegalStateException("No managed heatmap mosaic was prepared for color " + color + " z" + zoom);
            }
            return mosaic;
        }

        /**
         * Returns the validation mosaic for a color.
         *
         * @param color Strava color scheme name
         * @return prepared validation mosaic
         */
        public TileMosaic validation(String color) {
            return require(color, validationZoom);
        }

        /**
         * Serializes redacted mosaic metadata for debug exports.
         *
         * @return JSON manifest without cookies or signed URLs
         */
        public String manifestJson() {
            StringBuilder builder = new StringBuilder("{\"inferenceMode\":\"")
                .append(inferenceMode.name())
                .append("\",\"requestedInferenceZoom\":")
                .append(requestedInferenceZoom)
                .append(",\"requestedValidationZoom\":")
                .append(requestedValidationZoom)
                .append(",\"inferenceZoom\":")
                .append(inferenceZoom)
                .append(",\"validationZoom\":")
                .append(validationZoom)
                .append(",\"tileSize\":")
                .append(tileSize)
                .append(",\"inferenceParameters\":")
                .append(inferenceParameters.toJson())
                .append(",\"validationParameters\":")
                .append(validationParameters.toJson())
                .append(",\"mosaics\":[");
            int index = 0;
            for (TileMosaic mosaic : mosaics.values()) {
                if (index++ > 0) {
                    builder.append(',');
                }
                builder.append(mosaic.toJson());
            }
            return builder.append("]}").toString();
        }
    }

    /**
     * Downloaded source-tile mosaic for one color and zoom.
     *
     * @param color Strava color scheme name
     * @param zoom source tile zoom
     * @param originWorldPxX world-pixel x coordinate of mosaic origin
     * @param originWorldPxY world-pixel y coordinate of mosaic origin
     * @param image rendered mosaic image
     * @param tiles redacted source tile metadata
     * @param tileImages raw downloaded tile images keyed by safe debug names
     * @param parameters effective sampling parameters
     * @param stableInferenceRaster whether stable fixed-scale virtual raster scaling was applied
     * @param virtualRasterScale scale from source pixels to sampler raster pixels
     */
    public record TileMosaic(
        String color,
        int zoom,
        double originWorldPxX,
        double originWorldPxY,
        BufferedImage image,
        List<TileRecord> tiles,
        Map<String, BufferedImage> tileImages,
        SamplingParameters parameters,
        boolean stableInferenceRaster,
        double virtualRasterScale
    ) {
        String toJson() {
            StringBuilder builder = new StringBuilder("{\"color\":\"")
                .append(color)
                .append("\",\"zoom\":")
                .append(zoom)
                .append(",\"stableInferenceRaster\":")
                .append(stableInferenceRaster)
                .append(",\"originWorldPxX\":")
                .append(originWorldPxX)
                .append(",\"originWorldPxY\":")
                .append(originWorldPxY)
                .append(",\"virtualRasterScale\":")
                .append(virtualRasterScale)
                .append(",\"parameters\":")
                .append(parameters.toJson())
                .append(",\"tiles\":[");
            for (int i = 0; i < tiles.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(tiles.get(i).toJson());
            }
            return builder.append("]}").toString();
        }
    }

    /**
     * Effective sampling parameters for a fixed source-tile zoom.
     *
     * @param zoom source tile zoom
     * @param latitude representative latitude used for meters-per-pixel calculation
     * @param metersPerPixel source tile meters per pixel
     * @param halfWidthMeters cross-section search half-width in meters
     * @param sampleStepMeters distance between sampled profiles in meters
     * @param halfWidthPx source-tile half-width in pixels
     * @param stepPx source-tile profile step in pixels
     */
    public record SamplingParameters(
        int zoom,
        double latitude,
        double metersPerPixel,
        double halfWidthMeters,
        double sampleStepMeters,
        int halfWidthPx,
        int stepPx
    ) {
        String toJson() {
            return "{\"zoom\":" + zoom + ",\"latitude\":" + latitude + ",\"metersPerPixel\":" + metersPerPixel
                + ",\"halfWidthMeters\":" + halfWidthMeters + ",\"sampleStepMeters\":" + sampleStepMeters
                + ",\"halfWidthPx\":" + halfWidthPx + ",\"stepPx\":" + stepPx + "}";
        }
    }

    /**
     * Redacted metadata and quality assessment for one downloaded heatmap tile.
     *
     * @param color Strava color scheme name
     * @param zoom source tile zoom
     * @param x tile x coordinate
     * @param y tile y coordinate
     * @param usable whether the tile can be used for sampling
     * @param httpStatus HTTP status observed during download
     * @param byteSize downloaded byte size
     * @param width decoded image width
     * @param height decoded image height
     * @param sha256 tile content hash
     * @param quality quality classification such as usable or low-resolution placeholder
     * @param opaqueRatio fraction of opaque pixels
     * @param heatCoverage fraction of heat-colored pixels
     * @param sampledColorCount number of sampled colors in the tile
     * @param error redacted fetch/decode error
     */
    public record TileRecord(
        String color,
        int zoom,
        int x,
        int y,
        boolean usable,
        int httpStatus,
        int byteSize,
        int width,
        int height,
        String sha256,
        String quality,
        double opaqueRatio,
        double heatCoverage,
        int sampledColorCount,
        String error
    ) {
        String toJson() {
            return "{\"color\":\"" + color + "\",\"zoom\":" + zoom + ",\"x\":" + x + ",\"y\":" + y
                + ",\"usable\":" + usable + ",\"httpStatus\":" + httpStatus + ",\"byteSize\":" + byteSize
                + ",\"width\":" + width + ",\"height\":" + height + ",\"sha256\":\"" + sha256 + "\""
                + ",\"quality\":\"" + quality + "\",\"opaqueRatio\":" + opaqueRatio
                + ",\"heatCoverage\":" + heatCoverage + ",\"sampledColorCount\":" + sampledColorCount
                + ",\"error\":\"" + escape(error) + "\"}";
        }

        String describe() {
            return color + " z" + zoom + " x=" + x + " y=" + y + " quality=" + quality + " status=" + httpStatus
                + (error == null || error.isBlank() ? "" : " error=" + error);
        }
    }

    private record FetchedTile(BufferedImage image, TileRecord record) {
    }

    private record AggregateSourceFrame(TileMosaic reference, Map<String, BufferedImage> images) {
    }

    private record BoundsPx(double minX, double minY, double maxX, double maxY) {
    }

    /**
     * Georeferenced visualization of the fused all-color source intensity field.
     *
     * @param image colorized aggregate intensity image
     * @param zoom source tile zoom
     * @param colors source colors included in the aggregate
     * @param topLeft projected top-left corner
     * @param topRight projected top-right corner
     * @param bottomLeft projected bottom-left corner
     * @param bottomRight projected bottom-right corner
     * @param metadataJson redacted visualization metadata
     */
    public record AggregateVisualization(
        BufferedImage image,
        int zoom,
        List<String> colors,
        EastNorth topLeft,
        EastNorth topRight,
        EastNorth bottomLeft,
        EastNorth bottomRight,
        String metadataJson
    ) {
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

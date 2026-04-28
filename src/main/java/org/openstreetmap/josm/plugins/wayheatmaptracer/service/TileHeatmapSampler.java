package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

public final class TileHeatmapSampler {
    public static final int TILE_SIZE = 512;
    public static final int DEFAULT_INFERENCE_ZOOM = 15;
    public static final int DEFAULT_VALIDATION_ZOOM = 13;
    private static final String HEATMAP_URL = "https://content-a.strava.com/identified/globalheat/%s/%s/%d/%d/%d.png%s";

    public TileMosaicSet prepare(
        ManagedHeatmapConfig config,
        List<EastNorth> sourcePolyline,
        List<String> tileColors
    ) {
        if (sourcePolyline.size() < 2) {
            throw new IllegalStateException("Selected segment is too short for tile sampling.");
        }
        int inferenceZoom = clampZoom(config.inferenceZoom(), 10, 16);
        int validationZoom = Math.min(inferenceZoom, clampZoom(config.validationZoom(), 10, 16));
        double latitude = representativeLatitude(sourcePolyline);
        SamplingParameters inference = parametersFor(config, inferenceZoom, latitude);
        SamplingParameters validation = parametersFor(config, validationZoom, latitude);

        Map<String, TileMosaic> mosaics = new LinkedHashMap<>();
        for (String color : tileColors) {
            TileMosaic inferenceMosaic = loadMosaic(config, color, sourcePolyline, inference);
            mosaics.put(key(color, inferenceZoom), inferenceMosaic);
            if (validationZoom != inferenceZoom) {
                TileMosaic validationMosaic = loadMosaic(config, color, sourcePolyline, validation);
                mosaics.put(key(color, validationZoom), validationMosaic);
            }
        }
        PluginLog.verbose(
            "Prepared managed heatmap tile sampling, inferenceZoom=%d validationZoom=%d colors=%s halfWidth=%.1fm step=%.1fm.",
            inferenceZoom, validationZoom, tileColors, config.searchHalfWidthMeters(), config.sampleStepMeters()
        );
        return new TileMosaicSet(inferenceZoom, validationZoom, TILE_SIZE, mosaics, inference, validation);
    }

    public List<RenderedHeatmapSampler.CrossSectionProfile> sampleProfiles(
        TileMosaic mosaic,
        List<EastNorth> sourcePolyline,
        String detectorMode
    ) {
        List<EastNorth> dense = PolylineMath.resampleBySpacing(sourcePolyline, Math.max(4.0, mosaic.parameters().sampleStepMeters()));
        if (dense.size() < 2) {
            return List.of();
        }
        List<Point2D.Double> local = new ArrayList<>(dense.size());
        for (EastNorth point : dense) {
            Point2D.Double world = toWorldPixel(point, mosaic.zoom());
            local.add(new Point2D.Double(world.x - mosaic.originWorldPxX(), world.y - mosaic.originWorldPxY()));
        }
        PluginLog.verbose("Sampling %d fixed-tile cross-sections for color '%s' z%d (halfWidth=%d px/%.1fm, step=%d px/%.1fm).",
            dense.size(),
            mosaic.color(),
            mosaic.zoom(),
            mosaic.parameters().halfWidthPx(),
            mosaic.parameters().halfWidthMeters(),
            mosaic.parameters().stepPx(),
            mosaic.parameters().sampleStepMeters());
        return new RenderedHeatmapSampler().sampleProfilesOnRaster(
            mosaic.image(),
            local,
            mosaic.parameters().halfWidthPx(),
            mosaic.parameters().stepPx(),
            detectorMode,
            1.0
        );
    }

    public List<EastNorth> projectCandidate(TileMosaic mosaic, List<Point2D.Double> localPoints) {
        return localPoints.stream()
            .map(point -> toEastNorth(point.x + mosaic.originWorldPxX(), point.y + mosaic.originWorldPxY(), mosaic.zoom()))
            .toList();
    }

    private TileMosaic loadMosaic(ManagedHeatmapConfig config, String color, List<EastNorth> sourcePolyline, SamplingParameters parameters) {
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

        BufferedImage mosaic = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = mosaic.createGraphics();
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
        return new TileMosaic(color, parameters.zoom(), originX, originY, mosaic, records, tileImages, parameters);
    }

    private FetchedTile fetchTile(ManagedHeatmapConfig config, String color, int zoom, int x, int y) {
        String activity = safe(config.activity(), "all");
        String tileColor = safe(color, "hot");
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
            return new FetchedTile(image, record);
        } catch (IOException ex) {
            return failedTile(tileColor, zoom, x, y, -1, "network-error", ex.getMessage());
        }
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

    private SamplingParameters parametersFor(ManagedHeatmapConfig config, int zoom, double latitude) {
        double metersPerPixel = metersPerPixel(zoom, latitude);
        double halfMeters = Math.max(2.0, config.searchHalfWidthMeters());
        double stepMeters = Math.max(0.5, config.sampleStepMeters());
        int halfWidthPx = clamp((int) Math.round(halfMeters / metersPerPixel), 6, 96);
        int stepPx = clamp((int) Math.round(stepMeters / metersPerPixel), 1, Math.max(1, halfWidthPx / 3));
        return new SamplingParameters(zoom, latitude, metersPerPixel, halfMeters, stepMeters, halfWidthPx, stepPx);
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

    private int clampZoom(int zoom, int min, int max) {
        return Math.max(min, Math.min(max, zoom));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String key(String color, int zoom) {
        return color + "@" + zoom;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            return "";
        }
    }

    public record TileMosaicSet(
        int inferenceZoom,
        int validationZoom,
        int tileSize,
        Map<String, TileMosaic> mosaics,
        SamplingParameters inferenceParameters,
        SamplingParameters validationParameters
    ) {
        public TileMosaic require(String color) {
            return require(color, inferenceZoom);
        }

        public TileMosaic require(String color, int zoom) {
            TileMosaic mosaic = mosaics.get(color + "@" + zoom);
            if (mosaic == null) {
                throw new IllegalStateException("No managed heatmap mosaic was prepared for color " + color + " z" + zoom);
            }
            return mosaic;
        }

        public TileMosaic validation(String color) {
            return require(color, validationZoom);
        }

        public String manifestJson() {
            StringBuilder builder = new StringBuilder("{\"inferenceZoom\":")
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

    public record TileMosaic(
        String color,
        int zoom,
        double originWorldPxX,
        double originWorldPxY,
        BufferedImage image,
        List<TileRecord> tiles,
        Map<String, BufferedImage> tileImages,
        SamplingParameters parameters
    ) {
        String toJson() {
            StringBuilder builder = new StringBuilder("{\"color\":\"")
                .append(color)
                .append("\",\"zoom\":")
                .append(zoom)
                .append(",\"originWorldPxX\":")
                .append(originWorldPxX)
                .append(",\"originWorldPxY\":")
                .append(originWorldPxY)
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

    private record BoundsPx(double minX, double minY, double maxX, double maxY) {
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

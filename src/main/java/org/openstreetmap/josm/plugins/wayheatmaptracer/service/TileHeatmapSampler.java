package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
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
    public static final int SAMPLING_ZOOM = 15;
    private static final String HEATMAP_URL = "https://content-a.strava.com/identified/globalheat/%s/%s/%d/%d/%d.png";

    public TileMosaicSet prepare(
        ManagedHeatmapConfig config,
        List<EastNorth> sourcePolyline,
        List<String> tileColors,
        int halfWidthPx
    ) {
        List<Point2D.Double> worldPixels = sourcePolyline.stream()
            .map(this::toWorldPixel)
            .toList();
        if (worldPixels.size() < 2) {
            throw new IllegalStateException("Selected segment is too short for tile sampling.");
        }

        BoundsPx bounds = boundsFor(worldPixels, Math.max(128, halfWidthPx * 4));
        int minTileX = floorTile(bounds.minX());
        int maxTileX = floorTile(bounds.maxX());
        int minTileY = floorTile(bounds.minY());
        int maxTileY = floorTile(bounds.maxY());
        double originX = minTileX * (double) TILE_SIZE;
        double originY = minTileY * (double) TILE_SIZE;
        int width = (maxTileX - minTileX + 1) * TILE_SIZE;
        int height = (maxTileY - minTileY + 1) * TILE_SIZE;

        Map<String, TileMosaic> mosaics = new LinkedHashMap<>();
        for (String color : tileColors) {
            mosaics.put(color, loadMosaic(config, color, minTileX, maxTileX, minTileY, maxTileY, originX, originY, width, height));
        }
        PluginLog.verbose(
            "Prepared fixed heatmap tile sampling at zoom %d, colors=%s, tiles=%d..%d/%d..%d, mosaic=%dx%d.",
            SAMPLING_ZOOM, tileColors, minTileX, maxTileX, minTileY, maxTileY, width, height
        );
        return new TileMosaicSet(mosaics);
    }

    public List<RenderedHeatmapSampler.CrossSectionProfile> sampleProfiles(
        TileMosaic mosaic,
        List<EastNorth> sourcePolyline,
        int halfWidthPx,
        int stepPx,
        String detectorMode
    ) {
        List<EastNorth> dense = PolylineMath.resampleBySpacing(sourcePolyline, 10.0);
        if (dense.size() < 2) {
            return List.of();
        }
        List<Point2D.Double> local = new ArrayList<>(dense.size());
        for (EastNorth point : dense) {
            Point2D.Double world = toWorldPixel(point);
            local.add(new Point2D.Double(world.x - mosaic.originWorldPxX(), world.y - mosaic.originWorldPxY()));
        }
        PluginLog.verbose("Sampling %d fixed-tile cross-sections for color '%s' (halfWidth=%d px, step=%d px).",
            dense.size(), mosaic.color(), halfWidthPx, stepPx);
        return new RenderedHeatmapSampler().sampleProfilesOnRaster(
            mosaic.image(),
            local,
            halfWidthPx,
            stepPx,
            detectorMode,
            1.0
        );
    }

    public List<EastNorth> projectCandidate(TileMosaic mosaic, List<Point2D.Double> localPoints) {
        return localPoints.stream()
            .map(point -> toEastNorth(point.x + mosaic.originWorldPxX(), point.y + mosaic.originWorldPxY()))
            .toList();
    }

    private TileMosaic loadMosaic(
        ManagedHeatmapConfig config,
        String color,
        int minTileX,
        int maxTileX,
        int minTileY,
        int maxTileY,
        double originX,
        double originY,
        int width,
        int height
    ) {
        BufferedImage mosaic = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = mosaic.createGraphics();
        List<TileRecord> records = new ArrayList<>();
        Map<String, BufferedImage> tileImages = new LinkedHashMap<>();
        try {
            for (int x = minTileX; x <= maxTileX; x++) {
                for (int y = minTileY; y <= maxTileY; y++) {
                    BufferedImage tile = fetchTile(config, color, x, y);
                    graphics.drawImage(tile, (x - minTileX) * TILE_SIZE, (y - minTileY) * TILE_SIZE, null);
                    records.add(new TileRecord(color, SAMPLING_ZOOM, x, y, true, ""));
                    tileImages.put(color + "/z" + SAMPLING_ZOOM + "-x" + x + "-y" + y + ".png", tile);
                }
            }
        } finally {
            graphics.dispose();
        }
        return new TileMosaic(color, SAMPLING_ZOOM, originX, originY, mosaic, records, tileImages);
    }

    private BufferedImage fetchTile(ManagedHeatmapConfig config, String color, int x, int y) {
        String activity = safe(config.activity(), "all");
        String tileColor = safe(color, "hot");
        String url = HEATMAP_URL.formatted(activity, tileColor, SAMPLING_ZOOM, x, y);
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Cookie", config.toCookieHeader());
            connection.setRequestProperty("User-Agent", "JOSM WayHeatmapTracer");
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                throw new IOException("HTTP " + response);
            }
            BufferedImage image = ImageIO.read(connection.getInputStream());
            if (image == null) {
                throw new IOException("tile was not an image");
            }
            return image;
        } catch (IOException ex) {
            throw new IllegalStateException(
                "Failed to fetch required heatmap tile at zoom " + SAMPLING_ZOOM + " color " + tileColor
                    + " x=" + x + " y=" + y + ": " + ex.getMessage(),
                ex
            );
        }
    }

    private Point2D.Double toWorldPixel(EastNorth eastNorth) {
        LatLon latLon = ProjectionRegistry.getProjection().eastNorth2latlon(eastNorth);
        double lat = Math.max(-85.05112878, Math.min(85.05112878, latLon.lat()));
        double lon = latLon.lon();
        double scale = TILE_SIZE * Math.pow(2.0, SAMPLING_ZOOM);
        double x = (lon + 180.0) / 360.0 * scale;
        double sinLat = Math.sin(Math.toRadians(lat));
        double y = (0.5 - Math.log((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)) * scale;
        return new Point2D.Double(x, y);
    }

    private EastNorth toEastNorth(double worldX, double worldY) {
        double scale = TILE_SIZE * Math.pow(2.0, SAMPLING_ZOOM);
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

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public record TileMosaicSet(Map<String, TileMosaic> mosaics) {
        public TileMosaic require(String color) {
            TileMosaic mosaic = mosaics.get(color);
            if (mosaic == null) {
                throw new IllegalStateException("No fixed-tile mosaic was prepared for color " + color);
            }
            return mosaic;
        }

        public String manifestJson() {
            StringBuilder builder = new StringBuilder("{\"samplingZoom\":")
                .append(SAMPLING_ZOOM)
                .append(",\"tileSize\":")
                .append(TILE_SIZE)
                .append(",\"colors\":[");
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
        Map<String, BufferedImage> tileImages
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

    public record TileRecord(String color, int zoom, int x, int y, boolean present, String error) {
        String toJson() {
            return "{\"color\":\"" + color + "\",\"zoom\":" + zoom + ",\"x\":" + x + ",\"y\":" + y
                + ",\"present\":" + present + ",\"error\":\"" + (error == null ? "" : error.replace("\"", "\\\"")) + "\"}";
        }
    }

    private record BoundsPx(double minX, double minY, double maxX, double maxY) {
    }
}

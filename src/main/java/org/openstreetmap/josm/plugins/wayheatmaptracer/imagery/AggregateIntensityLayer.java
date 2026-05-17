package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.ImageIcon;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.ProjectionBounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

/**
 * Non-editable white-on-transparent map layer that visualizes the fused all-color managed heatmap intensity field.
 */
public class AggregateIntensityLayer extends Layer {
    public static final String LAYER_NAME = "WayHeatmapTracer aggregate intensity";
    private static final Icon ICON = createIcon();
    private static final int ZOOM = 15;
    private static final int TILE_SIZE = TileHeatmapSampler.TILE_SIZE;
    private static final int MAX_VISIBLE_TILES = 256;
    private static final List<String> SOURCE_COLORS = List.of("hot", "blue", "bluered", "purple", "gray");
    private static final String HEATMAP_URL = "https://content-a.strava.com/identified/globalheat/%s/%s/%d/%d/%d.png";
    private static final ExecutorService TILE_EXECUTOR = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "WayHeatmapTracer aggregate tiles");
        thread.setDaemon(true);
        return thread;
    });

    private final ManagedHeatmapConfig config;
    private final Map<TileKey, BufferedImage> tileCache = new ConcurrentHashMap<>();
    private final Set<TileKey> loading = ConcurrentHashMap.newKeySet();

    private AggregateIntensityLayer(ManagedHeatmapConfig config) {
        super(LAYER_NAME);
        this.config = config;
        setOpacity(0.80);
    }

    /**
     * Replaces any previous live aggregate visualization layer from current settings.
     *
     * @param config managed heatmap configuration
     * @param managedLayer refreshed managed color-scheme layer, used for layer ordering
     */
    public static void applyOrUpdateManagedLayer(ManagedHeatmapConfig config, ImageryLayer managedLayer) {
        if (config == null || !config.showAggregateIntensityLayer() || !config.hasManagedAccessValues()) {
            removeExisting();
            return;
        }
        removeExisting();
        AggregateIntensityLayer layer = new AggregateIntensityLayer(config);
        MainApplication.getLayerManager().addLayer(layer);
        moveJustAboveManagedLayer(layer, managedLayer);
        PluginLog.verbose("Aggregate intensity layer added from settings.");
    }

    /**
     * Replaces any previous aggregate visualization layer with a static slide-result visualization.
     *
     * @param visualization aggregate intensity image to show
     */
    public static void show(TileHeatmapSampler.AggregateVisualization visualization) {
        if (visualization == null || MainApplication.getLayerManager() == null) {
            return;
        }
        removeExisting();
        MainApplication.getLayerManager().addLayer(new StaticAggregateIntensityLayer(visualization));
    }

    /**
     * Removes the aggregate visualization layer if one is present.
     */
    public static void removeExisting() {
        if (MainApplication.getLayerManager() == null) {
            return;
        }
        MainApplication.getLayerManager().getLayers().stream()
            .filter(AggregateIntensityLayer.class::isInstance)
            .toList()
            .forEach(layer -> MainApplication.getLayerManager().removeLayer(layer));
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bbox) {
        if (!isVisible() || bbox == null) {
            return;
        }
        TileRange range = tileRange(bbox);
        if (range.tileCount() > MAX_VISIBLE_TILES) {
            return;
        }
        for (int x = range.minX(); x <= range.maxX(); x++) {
            for (int y = range.minY(); y <= range.maxY(); y++) {
                TileKey key = new TileKey(x, y);
                BufferedImage image = tileCache.get(key);
                if (image == null) {
                    requestTile(key, mv);
                    continue;
                }
                drawTile(g, mv, image, x, y);
            }
        }
    }

    @Override
    public Icon getIcon() {
        return ICON;
    }

    @Override
    public String getToolTipText() {
        return "WayHeatmapTracer all-color aggregate intensity visualization";
    }

    @Override
    public void mergeFrom(Layer from) {
        throw new UnsupportedOperationException("Aggregate intensity layers cannot be merged.");
    }

    @Override
    public boolean isMergable(Layer other) {
        return false;
    }

    @Override
    public void visitBoundingBox(BoundingXYVisitor v) {
        v.visit(ProjectionRegistry.getProjection().getWorldBoundsLatLon());
    }

    @Override
    public Object getInfoComponent() {
        return "<html><b>" + getName() + "</b><br>"
            + "Colors: " + String.join(", ", SOURCE_COLORS) + "<br>"
            + "Visualization only; not used as editable OSM data.</html>";
    }

    @Override
    public Action[] getMenuEntries() {
        return new Action[0];
    }

    @Override
    public boolean isSavable() {
        return false;
    }

    @Override
    public ProjectionBounds getViewProjectionBounds() {
        return null;
    }

    private void requestTile(TileKey key, MapView mapView) {
        if (!loading.add(key)) {
            return;
        }
        TILE_EXECUTOR.submit(() -> {
            try {
                BufferedImage image = loadAggregateTile(key);
                if (image != null) {
                    tileCache.put(key, image);
                }
            } catch (RuntimeException ex) {
                PluginLog.verbose("Aggregate intensity tile z%d x=%d y=%d failed: %s", ZOOM, key.x(), key.y(), ex.getMessage());
            } finally {
                loading.remove(key);
                mapView.repaint();
            }
        });
    }

    private BufferedImage loadAggregateTile(TileKey key) {
        Map<String, BufferedImage> sources = new LinkedHashMap<>();
        for (String color : SOURCE_COLORS) {
            BufferedImage image = fetchSourceTile(color, key);
            if (image == null) {
                return null;
            }
            sources.put(color, image);
        }
        return RenderedHeatmapSampler.renderAggregatedIntensityRaster(sources);
    }

    private BufferedImage fetchSourceTile(String color, TileKey key) {
        String activity = safe(config.activity(), "all");
        String tileColor = safe(color, "hot");
        String cacheQuery = config.cacheBuster() > 0 ? "?whtr-cache=" + config.cacheBuster() : "";
        String url = HEATMAP_URL.formatted(activity, tileColor, ZOOM, key.x(), key.y()) + cacheQuery;
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Cookie", config.toCookieHeader());
            connection.setRequestProperty("User-Agent", "JOSM WayHeatmapTracer");
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                return null;
            }
            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = input.readAllBytes();
            }
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception ex) {
            return null;
        }
    }

    private void drawTile(Graphics2D g, MapView mapView, BufferedImage image, int x, int y) {
        EastNorth topLeft = worldToEastNorth(x * (double) TILE_SIZE, y * (double) TILE_SIZE);
        EastNorth topRight = worldToEastNorth((x + 1.0) * TILE_SIZE, y * (double) TILE_SIZE);
        EastNorth bottomLeft = worldToEastNorth(x * (double) TILE_SIZE, (y + 1.0) * TILE_SIZE);
        Point2D tl = mapView.getPoint2D(topLeft);
        Point2D tr = mapView.getPoint2D(topRight);
        Point2D bl = mapView.getPoint2D(bottomLeft);
        Rectangle clip = g.getClipBounds();
        Rectangle tileBounds = new Rectangle(
            (int) Math.floor(Math.min(tl.getX(), Math.min(tr.getX(), bl.getX()))),
            (int) Math.floor(Math.min(tl.getY(), Math.min(tr.getY(), bl.getY()))),
            (int) Math.ceil(Math.max(tl.getX(), Math.max(tr.getX(), bl.getX())) - Math.min(tl.getX(), Math.min(tr.getX(), bl.getX()))),
            (int) Math.ceil(Math.max(tl.getY(), Math.max(tr.getY(), bl.getY())) - Math.min(tl.getY(), Math.min(tr.getY(), bl.getY())))
        );
        if (clip != null && !tileBounds.intersects(clip)) {
            return;
        }
        AffineTransform transform = new AffineTransform(
            (tr.getX() - tl.getX()) / image.getWidth(),
            (tr.getY() - tl.getY()) / image.getWidth(),
            (bl.getX() - tl.getX()) / image.getHeight(),
            (bl.getY() - tl.getY()) / image.getHeight(),
            tl.getX(),
            tl.getY()
        );
        Graphics2D copy = (Graphics2D) g.create();
        try {
            copy.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) getOpacity()));
            copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            copy.drawImage(image, transform, null);
        } finally {
            copy.dispose();
        }
    }

    private TileRange tileRange(Bounds bounds) {
        int maxTile = (1 << ZOOM) - 1;
        LatLon min = bounds.getMin();
        LatLon max = bounds.getMax();
        int minX = clampTile(lonToTileX(min.lon()), maxTile);
        int maxX = clampTile(lonToTileX(max.lon()), maxTile);
        int minY = clampTile(latToTileY(max.lat()), maxTile);
        int maxY = clampTile(latToTileY(min.lat()), maxTile);
        return new TileRange(Math.min(minX, maxX), Math.max(minX, maxX), Math.min(minY, maxY), Math.max(minY, maxY));
    }

    private int lonToTileX(double lon) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << ZOOM));
    }

    private int latToTileY(double lat) {
        double clipped = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double sinLat = Math.sin(Math.toRadians(clipped));
        double y = (0.5 - Math.log((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)) * (1 << ZOOM);
        return (int) Math.floor(y);
    }

    private int clampTile(int value, int maxTile) {
        return Math.max(0, Math.min(maxTile, value));
    }

    private EastNorth worldToEastNorth(double worldX, double worldY) {
        double scale = TILE_SIZE * Math.pow(2.0, ZOOM);
        double lon = worldX / scale * 360.0 - 180.0;
        double mercator = Math.PI * (1.0 - 2.0 * worldY / scale);
        double lat = Math.toDegrees(Math.atan(Math.sinh(mercator)));
        return ProjectionRegistry.getProjection().latlon2eastNorth(new LatLon(lat, lon));
    }

    private static void moveJustAboveManagedLayer(Layer aggregate, ImageryLayer managedLayer) {
        if (managedLayer == null || MainApplication.getLayerManager() == null) {
            return;
        }
        List<Layer> layers = MainApplication.getLayerManager().getLayers();
        int managedIndex = layers.indexOf(managedLayer);
        if (managedIndex >= 0) {
            MainApplication.getLayerManager().moveLayer(aggregate, Math.max(0, managedIndex));
        }
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static Icon createIcon() {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            for (int y = 0; y < 16; y++) {
                int alpha = Math.round(20 + 200 * (y / 15f));
                g.setColor(new Color(255, 255, 255, alpha));
                g.drawLine(0, y, 15, y);
            }
            g.setColor(new Color(0, 0, 0, 120));
            g.drawRect(1, 1, 13, 13);
        } finally {
            g.dispose();
        }
        return new ImageIcon(image);
    }

    private record TileKey(int x, int y) {
    }

    private record TileRange(int minX, int maxX, int minY, int maxY) {
        private int tileCount() {
            return (maxX - minX + 1) * (maxY - minY + 1);
        }
    }

    private static final class StaticAggregateIntensityLayer extends AggregateIntensityLayer {
        private final TileHeatmapSampler.AggregateVisualization visualization;

        private StaticAggregateIntensityLayer(TileHeatmapSampler.AggregateVisualization visualization) {
            super(null);
            this.visualization = visualization;
            setName(LAYER_NAME + " z" + visualization.zoom());
        }

        @Override
        public void paint(Graphics2D g, MapView mv, Bounds bbox) {
            if (visualization.image() == null || !isVisible()) {
                return;
            }
            Point2D topLeft = mv.getPoint2D(visualization.topLeft());
            Point2D topRight = mv.getPoint2D(visualization.topRight());
            Point2D bottomLeft = mv.getPoint2D(visualization.bottomLeft());
            AffineTransform transform = new AffineTransform(
                (topRight.getX() - topLeft.getX()) / visualization.image().getWidth(),
                (topRight.getY() - topLeft.getY()) / visualization.image().getWidth(),
                (bottomLeft.getX() - topLeft.getX()) / visualization.image().getHeight(),
                (bottomLeft.getY() - topLeft.getY()) / visualization.image().getHeight(),
                topLeft.getX(),
                topLeft.getY()
            );
            Graphics2D copy = (Graphics2D) g.create();
            try {
                copy.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) getOpacity()));
                copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                copy.drawImage(visualization.image(), transform, null);
            } finally {
                copy.dispose();
            }
        }
    }
}

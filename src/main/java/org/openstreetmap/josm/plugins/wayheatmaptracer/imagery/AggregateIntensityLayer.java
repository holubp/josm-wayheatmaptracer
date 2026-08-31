package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;

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
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CancellationToken;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileAddress;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileCachePolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TilePurpose;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

/**
 * Non-editable white-on-transparent map layer that visualizes the fused all-color managed heatmap intensity field.
 */
public class AggregateIntensityLayer extends Layer {
    /** Stable display name used to locate and replace the diagnostic aggregate layer. */
    public static final String LAYER_NAME = "WayHeatmapTracer aggregate intensity";
    private static final Icon ICON = createIcon();
    private static final int ZOOM = 15;
    private static final int TILE_SIZE = TileHeatmapSampler.TILE_SIZE;
    private static final int MAX_VISIBLE_TILES = 256;
    private static final int MAX_RENDERED_TILES = 64;
    private static final int MAX_COMPOSITE_STATES = 2_048;
    private static final List<String> SOURCE_COLORS = List.of("hot", "blue", "bluered", "purple", "gray");
    private final ManagedHeatmapConfig config;
    private final TileFetchCoordinator coordinator;
    private final CancellationToken lifecycle = new CancellationToken();
    private final AtomicBoolean destroyed = new AtomicBoolean();
    private final Map<TileKey, CompositeState> composites = new ConcurrentHashMap<>();
    private volatile String layerStatus = "Waiting for visible aggregate tiles";

    private AggregateIntensityLayer(ManagedHeatmapConfig config) {
        this(config, ManagedTileRuntime.initialize(config));
    }

    AggregateIntensityLayer(ManagedHeatmapConfig config, TileFetchCoordinator coordinator) {
        super(LAYER_NAME);
        this.config = config;
        this.coordinator = coordinator;
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
            layerStatus = "Aggregate preview paused: zoom in to load at most 256 z15 tiles";
            return;
        }
        for (int x = range.minX(); x <= range.maxX(); x++) {
            for (int y = range.minY(); y <= range.maxY(); y++) {
                TileKey key = new TileKey(x, y);
                CompositeState state = composites.get(key);
                if (state == null) {
                    requestTile(key, mv);
                    continue;
                }
                if (state.status() == CompositeStatus.COMPLETE && state.image() != null) {
                    drawTile(g, mv, state.image(), x, y);
                }
            }
        }
    }

    @Override
    public Icon getIcon() {
        return ICON;
    }

    @Override
    public String getToolTipText() {
        return "WayHeatmapTracer all-color aggregate intensity visualization - " + layerStatus;
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
            + "Status: " + layerStatus + "<br>"
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

    @Override
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        lifecycle.cancel();
        composites.clear();
        super.destroy();
    }

    private void requestTile(TileKey key, MapView mapView) {
        if (destroyed.get() || composites.size() >= MAX_COMPOSITE_STATES
            || composites.putIfAbsent(key, CompositeState.loading()) != null) {
            return;
        }
        ManagedTileGeneration generation = new ManagedTileGeneration(Math.max(0L, config.cacheBuster()));
        coordinator.updateActiveGeneration(generation);
        Map<String, CompletableFuture<TileFetchResult>> requests = new LinkedHashMap<>();
        for (String color : SOURCE_COLORS) {
            TileRequest request = new TileRequest(new ManagedTileAddress(config.activity(), color, ZOOM,
                key.x(), key.y()), generation, TilePurpose.LIVE_AGGREGATE_VISUALIZATION,
                TileCachePolicy.USE_CACHE, Instant.now().plusSeconds(30), lifecycle);
            requests.put(color, coordinator.fetch(request, CredentialSnapshot.fromConfig(config))
                .toCompletableFuture());
        }
        CompletableFuture.allOf(requests.values().toArray(CompletableFuture[]::new)).whenComplete((unused, error) -> {
            if (destroyed.get() || lifecycle.isCancelled()) {
                return;
            }
            if (!coordinator.dispatch(TilePurpose.LIVE_AGGREGATE_VISUALIZATION,
                () -> completeTile(key, mapView, requests)) && !destroyed.get() && !lifecycle.isCancelled()) {
                Instant retry = Instant.now().plusSeconds(1);
                CompositeState delayed = new CompositeState(CompositeStatus.FAILED_UNTIL, null, 0,
                    retry, "queue-full");
                composites.put(key, delayed);
                layerStatus = "Aggregate preview delayed - tile worker queue is full";
                repaintOnEdt(mapView);
                coordinator.schedule(retry, () -> {
                    if (!destroyed.get() && composites.remove(key, delayed)) {
                        repaintOnEdt(mapView);
                    }
                });
            }
        });
    }

    private void completeTile(TileKey key, MapView mapView,
        Map<String, CompletableFuture<TileFetchResult>> requests) {
        if (destroyed.get() || lifecycle.isCancelled()) {
            return;
        }
        Map<String, BufferedImage> sources = new LinkedHashMap<>();
        TileFetchResult representativeFailure = null;
        for (Map.Entry<String, CompletableFuture<TileFetchResult>> entry : requests.entrySet()) {
            TileFetchResult result = completedResult(entry.getValue());
            if (result != null && result.usable()) {
                sources.put(entry.getKey(), result.image());
            } else if (representativeFailure == null) {
                representativeFailure = result;
            }
        }
        CompositeState next;
        if (sources.size() == SOURCE_COLORS.size()) {
            BufferedImage aggregate = RenderedHeatmapSampler.renderAggregatedIntensityRaster(sources);
            next = new CompositeState(CompositeStatus.COMPLETE, aggregate, sources.size(), null, "ready");
            layerStatus = "Complete aggregate tiles available";
        } else {
            TileFetchStatus status = representativeFailure == null ? TileFetchStatus.NETWORK_ERROR
                : representativeFailure.status();
            Instant retry = representativeFailure == null ? null : representativeFailure.retryNotBefore();
            if (retry == null && status != TileFetchStatus.AUTH_FAILURE) {
                retry = Instant.now().plus(retryDelay(status));
            }
            next = new CompositeState(status == TileFetchStatus.AUTH_FAILURE
                ? CompositeStatus.AUTH_BLOCKED : status == TileFetchStatus.RATE_LIMITED
                    ? CompositeStatus.RATE_LIMITED : CompositeStatus.FAILED_UNTIL,
                null, sources.size(), retry, status.name());
            layerStatus = "Incomplete aggregate " + sources.size() + "/5 - " + status.name();
        }
        if (destroyed.get() || lifecycle.isCancelled()) {
            return;
        }
        composites.put(key, next);
        trimRenderedTiles(key);
        repaintOnEdt(mapView);
        if (next.retryNotBefore() != null) {
            coordinator.schedule(next.retryNotBefore(), () -> {
                if (!destroyed.get() && composites.remove(key, next)) {
                    repaintOnEdt(mapView);
                }
            });
        }
    }

    private void trimRenderedTiles(TileKey retained) {
        long excess = composites.values().stream()
            .filter(state -> state.status() == CompositeStatus.COMPLETE && state.image() != null)
            .count() - MAX_RENDERED_TILES;
        if (excess <= 0) {
            return;
        }
        for (Map.Entry<TileKey, CompositeState> entry : composites.entrySet()) {
            if (excess <= 0) break;
            if (!entry.getKey().equals(retained) && entry.getValue().status() == CompositeStatus.COMPLETE
                && composites.remove(entry.getKey(), entry.getValue())) {
                excess--;
            }
        }
    }

    private TileFetchResult completedResult(CompletableFuture<TileFetchResult> future) {
        if (future == null || future.isCancelled() || future.isCompletedExceptionally()) {
            return null;
        }
        return future.getNow(null);
    }

    private Duration retryDelay(TileFetchStatus status) {
        return switch (status) {
            case NO_TILE, CONTENT_TYPE_ERROR, BODY_TOO_LARGE, DECODE_ERROR, BAD_DIMENSIONS,
                PLACEHOLDER_SUSPECTED -> Duration.ofMinutes(5);
            default -> Duration.ofSeconds(1);
        };
    }

    private void repaintOnEdt(MapView mapView) {
        if (destroyed.get() || mapView == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            mapView.repaint();
        } else {
            SwingUtilities.invokeLater(() -> {
                if (!destroyed.get()) {
                    mapView.repaint();
                }
            });
        }
    }

    void requestTileForTesting(int x, int y) {
        requestTile(new TileKey(x, y), null);
    }

    int compositeCountForTesting() {
        return composites.size();
    }

    String compositeStatusForTesting(int x, int y) {
        CompositeState state = composites.get(new TileKey(x, y));
        return state == null ? "ABSENT" : state.status().name();
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
            MainApplication.getLayerManager().moveLayer(aggregate, Math.min(layers.size() - 1, managedIndex + 1));
        }
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

    private enum CompositeStatus { LOADING, COMPLETE, FAILED_UNTIL, AUTH_BLOCKED, RATE_LIMITED }

    private record CompositeState(CompositeStatus status, BufferedImage image, int readySources,
                                  Instant retryNotBefore, String reason) {
        static CompositeState loading() {
            return new CompositeState(CompositeStatus.LOADING, null, 0, null, "loading");
        }
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

package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

import java.util.Optional;

import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileUrlBuilder;

/**
 * Creates and locates the plugin-managed Strava heatmap TMS layer.
 */
public final class ManagedImageryService {
    /** Stable JOSM imagery id of the plugin-managed Strava layer. */
    public static final String MANAGED_LAYER_ID = "wayheatmaptracer.managed.heatmap";
    /** User-facing base name of the plugin-managed Strava layer. */
    public static final String MANAGED_LAYER_NAME = "WayHeatmapTracer Heatmap";
    private static final ManagedTileUrlBuilder URL_BUILDER = new ManagedTileUrlBuilder();

    private ManagedImageryService() {
    }

    /**
     * Recreates the managed heatmap layer from current settings.
     *
     * @return newly added imagery layer
     * @throws IllegalStateException when managed Strava access values are incomplete
     */
    public static ImageryLayer applyOrUpdateManagedLayer() {
        ManagedHeatmapConfig config = PluginPreferences.load();
        if (!config.hasManagedAccessValues()) {
            throw new IllegalStateException("Managed heatmap access values are incomplete.");
        }

        findManagedLayer().ifPresent(layer -> MainApplication.getLayerManager().removeLayer(layer));

        String activity = sanitizeOption(config.activity(), "all");
        String color = sanitizeOption(config.color(), "hot");
        ImageryInfo info = new ImageryInfo(
            MANAGED_LAYER_NAME + " (" + activity + "/" + color + ")",
            URL_BUILDER.buildJosmTemplate(activity, color,
                new ManagedTileGeneration(Math.max(0L, config.cacheBuster()))),
            "tms",
            null,
            config.toCookieHeader(),
            MANAGED_LAYER_ID
        );
        info.setDefaultMaxZoom(15);
        ImageryLayer layer = new ManagedHeatmapLayer(info);
        MainApplication.getLayerManager().addLayer(layer);
        return layer;
    }

    /**
     * Locates the current managed heatmap layer by its imagery id.
     *
     * @return managed layer if it is currently loaded
     */
    public static Optional<ImageryLayer> findManagedLayer() {
        return MainApplication.getLayerManager().getLayers().stream()
            .filter(ImageryLayer.class::isInstance)
            .map(ImageryLayer.class::cast)
            .filter(layer -> {
                ImageryInfo info = layer.getInfo();
                return info != null && MANAGED_LAYER_ID.equals(info.getId());
            })
            .findFirst();
    }

    private static String sanitizeOption(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}

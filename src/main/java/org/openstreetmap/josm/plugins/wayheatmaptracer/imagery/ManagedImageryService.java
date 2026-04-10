package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

import java.util.Optional;

import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;

public final class ManagedImageryService {
    public static final String MANAGED_LAYER_ID = "wayheatmaptracer.managed.heatmap";
    public static final String MANAGED_LAYER_NAME = "WayHeatmapTracer Heatmap";
    private static final String HEATMAP_URL_TEMPLATE = "tms[15]:https://content-a.strava.com/identified/globalheat/%s/%s/{zoom}/{x}/{y}.png";

    private ManagedImageryService() {
    }

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
            HEATMAP_URL_TEMPLATE.formatted(activity, color),
            "tms",
            null,
            config.toCookieHeader(),
            MANAGED_LAYER_ID
        );
        info.setDefaultMaxZoom(15);
        ImageryLayer layer = ImageryLayer.create(info);
        MainApplication.getLayerManager().addLayer(layer);
        return layer;
    }

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

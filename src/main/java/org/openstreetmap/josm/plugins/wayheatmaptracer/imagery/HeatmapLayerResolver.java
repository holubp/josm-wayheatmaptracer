package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

public final class HeatmapLayerResolver {
    private HeatmapLayerResolver() {
    }

    public static ImageryLayer resolve() {
        return resolveOptional().orElseThrow(() -> {
            ManagedHeatmapConfig config = PluginPreferences.load();
            PluginLog.verbose("Failed to resolve heatmap layer. manual='%s' regex='%s'.",
                config.manualLayerName(), config.layerRegex());
            return new IllegalStateException(
                "No visible heatmap imagery layer was resolved. Refresh the managed layer, choose a layer manually, or update the regex."
            );
        });
    }

    public static Optional<ImageryLayer> resolveOptional() {
        ManagedHeatmapConfig config = PluginPreferences.load();
        Optional<ImageryLayer> managed = resolveManaged();
        if (managed.isPresent()) {
            PluginLog.verbose("Resolved heatmap layer via managed layer: '%s'.", managed.get().getName());
            return managed;
        }

        Optional<ImageryLayer> manual = resolveManualExact(config);
        if (manual.isPresent()) {
            PluginLog.verbose("Resolved heatmap layer via manual selection: '%s'.", manual.get().getName());
            return manual;
        }

        Optional<ImageryLayer> regex = resolveRegex(config);
        if (regex.isPresent()) {
            PluginLog.verbose("Resolved heatmap layer via regex '%s': '%s'.", config.layerRegex(), regex.get().getName());
            return regex;
        }

        return Optional.empty();
    }

    private static Optional<ImageryLayer> resolveManaged() {
        return ManagedImageryService.findManagedLayer().filter(ImageryLayer::isVisible);
    }

    private static Optional<ImageryLayer> resolveManualExact(ManagedHeatmapConfig config) {
        String manualName = config.manualLayerName();
        if (manualName == null || manualName.isBlank()) {
            return Optional.empty();
        }
        return MainApplication.getLayerManager().getLayers().stream()
            .filter(ImageryLayer.class::isInstance)
            .map(ImageryLayer.class::cast)
            .filter(ImageryLayer::isVisible)
            .filter(layer -> manualName.equals(layer.getName()))
            .findFirst();
    }

    private static Optional<ImageryLayer> resolveRegex(ManagedHeatmapConfig config) {
        String regex = config.layerRegex();
        if (regex == null || regex.isBlank()) {
            return Optional.empty();
        }
        final Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException ex) {
            throw new IllegalStateException("Invalid layer regex: " + ex.getMessage(), ex);
        }

        return MainApplication.getLayerManager().getLayers().stream()
            .filter(ImageryLayer.class::isInstance)
            .map(ImageryLayer.class::cast)
            .filter(ImageryLayer::isVisible)
            .filter(layer -> pattern.matcher(layer.getName()).matches())
            .findFirst();
    }
}

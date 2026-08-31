package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.io.File;
import java.nio.file.Path;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginDirectories;

/** Plugin-lifecycle owner and compatibility access point for the shared coordinator. */
public final class ManagedTileRuntime {
    private static TileFetchCoordinator coordinator;

    private ManagedTileRuntime() { }

    /**
     * Initializes the shared runtime once and activates the supplied generation.
     *
     * @param config current managed settings, or null for lazy compatibility access
     * @return plugin-owned coordinator
     */
    public static synchronized TileFetchCoordinator initialize(ManagedHeatmapConfig config) {
        if (coordinator == null) {
            TileReliabilityPolicy policy = TileReliabilityPolicy.defaults();
            TileDecoderClassifier classifier = new TileDecoderClassifier();
            File data = PluginDirectories.ensurePluginDataDirectory();
            Path root = new File(data, "managed-source-tile-cache").toPath();
            ManagedTileCache cache = new ManagedTileCache(root, classifier, policy.maximumBodyBytes());
            coordinator = new TileFetchCoordinator(new HttpUrlConnectionTileTransport(policy), cache,
                classifier, policy);
        }
        if (config != null) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(Math.max(0L, config.cacheBuster())));
        }
        return coordinator;
    }

    /**
     * Returns the initialized shared coordinator, creating it lazily for compatibility call sites.
     *
     * @return plugin-owned coordinator
     */
    public static synchronized TileFetchCoordinator coordinator() {
        return initialize(null);
    }

    /**
     * Activates a new settings generation so stale completions cannot publish.
     *
     * @param config saved managed settings
     */
    public static synchronized void updateConfig(ManagedHeatmapConfig config) {
        initialize(config);
    }

    /**
     * Returns current credential-free acquisition diagnostics without starting the runtime.
     *
     * @return safe diagnostics JSON
     */
    public static synchronized String diagnosticsJsonIfInitialized() {
        return coordinator == null
            ? "{\"formatVersion\":1,\"runtimeState\":\"not-initialized\",\"recentResults\":[]}"
            : coordinator.diagnosticsJson();
    }

    /** Closes all plugin-owned tile workers and clears the shared reference. */
    public static synchronized void close() {
        if (coordinator != null) {
            coordinator.close();
            coordinator = null;
        }
    }
}

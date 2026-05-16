package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.io.File;

/**
 * Resolves writable plugin directories for debug and calibration artifacts.
 */
public final class PluginDirectories {
    private PluginDirectories() {
    }

    /**
     * Ensures the temporary plugin data directory exists.
     *
     * @return writable directory used for exported bundles
     */
    public static File ensurePluginDataDirectory() {
        File root = new File(System.getProperty("java.io.tmpdir"), "wayheatmaptracer");
        root.mkdirs();
        return root;
    }
}

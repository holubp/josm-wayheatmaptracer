package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.io.File;

public final class PluginDirectories {
    private PluginDirectories() {
    }

    public static File ensurePluginDataDirectory() {
        File root = new File(System.getProperty("java.io.tmpdir"), "wayheatmaptracer");
        root.mkdirs();
        return root;
    }
}

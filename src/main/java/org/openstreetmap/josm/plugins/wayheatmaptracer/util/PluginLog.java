package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.tools.Logging;

public final class PluginLog {
    private static final String PREFIX = "[WayHeatmapTracer] ";

    private PluginLog() {
    }

    public static void verbose(String format, Object... args) {
        if (PluginPreferences.isVerboseEnabled()) {
            Logging.info(PREFIX + String.format(format, args));
        }
    }

    public static void debug(String format, Object... args) {
        if (PluginPreferences.isDebugEnabled()) {
            Logging.info(PREFIX + "DEBUG " + String.format(format, args));
        }
    }
}

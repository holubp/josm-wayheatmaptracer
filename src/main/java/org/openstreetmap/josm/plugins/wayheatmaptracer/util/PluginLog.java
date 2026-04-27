package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.ArrayDeque;
import java.util.Deque;

import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.tools.Logging;

public final class PluginLog {
    private static final String PREFIX = "[WayHeatmapTracer] ";
    private static final int MAX_SESSION_LINES = 4_000;
    private static final ThreadLocal<Deque<String>> SESSION_LINES = new ThreadLocal<>();

    private PluginLog() {
    }

    public static void beginSlideSession() {
        SESSION_LINES.set(new ArrayDeque<>());
    }

    public static String currentSlideLog() {
        Deque<String> lines = SESSION_LINES.get();
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    public static void endSlideSession() {
        SESSION_LINES.remove();
    }

    public static void verbose(String format, Object... args) {
        String message = PREFIX + String.format(format, args);
        append(message);
        if (PluginPreferences.isVerboseEnabled()) {
            Logging.info(message);
        }
    }

    public static void debug(String format, Object... args) {
        String message = PREFIX + "DEBUG " + String.format(format, args);
        append(message);
        if (PluginPreferences.isDebugEnabled()) {
            Logging.info(message);
        }
    }

    private static void append(String message) {
        Deque<String> lines = SESSION_LINES.get();
        if (lines == null) {
            return;
        }
        while (lines.size() >= MAX_SESSION_LINES) {
            lines.removeFirst();
        }
        lines.addLast(message);
    }
}

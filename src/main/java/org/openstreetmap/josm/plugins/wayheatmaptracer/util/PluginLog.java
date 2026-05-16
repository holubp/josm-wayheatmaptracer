package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.ArrayDeque;
import java.util.Deque;

import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.tools.Logging;

/**
 * Captures per-slide verbose diagnostics while also honoring the user's console logging settings.
 */
public final class PluginLog {
    private static final String PREFIX = "[WayHeatmapTracer] ";
    private static final int MAX_SESSION_LINES = 4_000;
    private static final ThreadLocal<Deque<String>> SESSION_LINES = new ThreadLocal<>();

    private PluginLog() {
    }

    /**
     * Starts collecting log lines for a new slide attempt.
     */
    public static void beginSlideSession() {
        SESSION_LINES.set(new ArrayDeque<>());
    }

    /**
     * Returns the currently collected slide log.
     *
     * @return newline-terminated log text, or an empty string when no session exists
     */
    public static String currentSlideLog() {
        Deque<String> lines = SESSION_LINES.get();
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    /**
     * Stops collecting log lines for the current slide attempt.
     */
    public static void endSlideSession() {
        SESSION_LINES.remove();
    }

    /**
     * Records a verbose diagnostic message and optionally writes it to the JOSM log.
     *
     * @param format {@link String#format(String, Object...)} pattern
     * @param args format arguments
     */
    public static void verbose(String format, Object... args) {
        String message = PREFIX + String.format(format, args);
        append(message);
        if (PluginPreferences.isVerboseEnabled()) {
            Logging.info(message);
        }
    }

    /**
     * Records a debug diagnostic message and optionally writes it to the JOSM log.
     *
     * @param format {@link String#format(String, Object...)} pattern
     * @param args format arguments
     */
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

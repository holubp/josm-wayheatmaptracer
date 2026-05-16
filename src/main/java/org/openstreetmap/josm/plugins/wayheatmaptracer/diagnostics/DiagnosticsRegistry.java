package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

/**
 * Stores the latest slide debug bundle for later export from the UI.
 */
public final class DiagnosticsRegistry {
    private static volatile LastSlideDebugBundle lastBundle;

    private DiagnosticsRegistry() {
    }

    /**
     * Replaces the currently exportable debug bundle.
     *
     * @param bundle last slide bundle, or {@code null} to clear it
     */
    public static void setLastBundle(LastSlideDebugBundle bundle) {
        lastBundle = bundle;
    }

    /**
     * Returns the most recent slide debug bundle.
     *
     * @return last bundle, or {@code null} before any slide has run
     */
    public static LastSlideDebugBundle getLastBundle() {
        return lastBundle;
    }
}

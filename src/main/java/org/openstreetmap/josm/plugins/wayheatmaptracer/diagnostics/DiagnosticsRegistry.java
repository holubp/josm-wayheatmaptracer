package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

public final class DiagnosticsRegistry {
    private static volatile LastSlideDebugBundle lastBundle;

    private DiagnosticsRegistry() {
    }

    public static void setLastBundle(LastSlideDebugBundle bundle) {
        lastBundle = bundle;
    }

    public static LastSlideDebugBundle getLastBundle() {
        return lastBundle;
    }
}

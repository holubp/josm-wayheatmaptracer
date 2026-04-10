package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

public final class DiagnosticsRegistry {
    private static volatile String lastBundle;

    private DiagnosticsRegistry() {
    }

    public static void setLastBundle(String json) {
        lastBundle = json;
    }

    public static String getLastBundle() {
        return lastBundle;
    }
}


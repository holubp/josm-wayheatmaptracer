package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import java.io.File;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Bundle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15BundleWriter;

/**
 * Stores the latest slide debug bundle for later export from the UI.
 */
public final class DiagnosticsRegistry {
    private static volatile LastSlideDebugBundle lastBundle;
    private static volatile Format15Bundle lastModernBundle;

    private DiagnosticsRegistry() {
    }

    /**
     * Replaces the currently exportable debug bundle.
     *
     * @param bundle last slide bundle, or {@code null} to clear it
     */
    public static synchronized void setLastBundle(LastSlideDebugBundle bundle) {
        lastBundle = bundle;
        lastModernBundle = null;
    }

    /** Replaces the current export with the newest modern attempt, including terminal failures. */
    public static synchronized void setLastModernBundle(Format15Bundle bundle) {
        if (bundle == null) {
            throw new IllegalArgumentException("Modern diagnostic bundle is required");
        }
        lastModernBundle = bundle;
        lastBundle = null;
    }

    /** Returns whether either workflow has an attempt ready for export. */
    public static boolean hasLatest() {
        return lastModernBundle != null || lastBundle != null;
    }

    /** Writes exactly the most recently registered attempt. */
    public static File writeLatest(File file) throws Exception {
        Format15Bundle modern;
        LastSlideDebugBundle legacy;
        synchronized (DiagnosticsRegistry.class) {
            modern = lastModernBundle;
            legacy = lastBundle;
        }
        if (modern != null) {
            Format15BundleWriter.write(modern, file.toPath());
            return file;
        }
        if (legacy != null) {
            return legacy.writeTo(file);
        }
        throw new IllegalStateException("No alignment diagnostics are available");
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

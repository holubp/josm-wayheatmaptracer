package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

/** Refuses Redo when a visible imagery layer cannot prove its pixels stayed unchanged. */
public final class VisibleSourceRevisionUnavailableException extends IllegalStateException {
    /** Creates a fixed, credential-free refusal. */
    public VisibleSourceRevisionUnavailableException() {
        super("This visible source cannot verify unchanged tiles for Redo; run a new alignment.");
    }
}

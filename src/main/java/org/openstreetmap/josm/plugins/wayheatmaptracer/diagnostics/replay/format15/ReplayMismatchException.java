package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

/** Indicates that replay inputs do not match the frozen diagnostic identity. */
public final class ReplayMismatchException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    /** Creates a non-sensitive replay identity mismatch. */
    public ReplayMismatchException(String message) {
        super(message);
    }
}

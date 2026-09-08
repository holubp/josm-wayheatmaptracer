package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay;

/** Increasing deterministic replay capabilities advertised by a debug bundle. */
public enum ReplayLevel {
    SCALAR_INFERENCE,
    RASTER_INFERENCE,
    FINAL_GEOMETRY,
    FULL_EDIT_PLAN
}

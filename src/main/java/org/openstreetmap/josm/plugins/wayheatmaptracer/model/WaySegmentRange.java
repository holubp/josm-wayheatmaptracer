package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Inclusive node-index range within a way.
 *
 * @param startIndex first node index in the selected segment
 * @param endIndex last node index in the selected segment
 */
public record WaySegmentRange(int startIndex, int endIndex) {
}

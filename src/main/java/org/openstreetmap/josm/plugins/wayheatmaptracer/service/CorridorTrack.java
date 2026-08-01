package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;
import java.util.Map;

/**
 * A longitudinally stable corridor identity assembled from cross-section observations.
 *
 * @param id deterministic track identifier
 * @param points observations keyed by profile index
 * @param score accumulated association quality
 * @param supportRatio fraction of all profiles directly supported by the track
 * @param parent whether the track represents a grouped parent corridor
 * @param childTrackIds elementary tracks represented by a parent
 * @param groupingDecision grouping evidence label, or empty for elementary tracks
 */
public record CorridorTrack(
    String id,
    Map<Integer, CorridorTrackPoint> points,
    double score,
    double supportRatio,
    boolean parent,
    List<String> childTrackIds,
    String groupingDecision
) {
    /**
     * Makes track evidence immutable.
     */
    public CorridorTrack {
        points = Map.copyOf(points);
        childTrackIds = List.copyOf(childTrackIds);
    }
}

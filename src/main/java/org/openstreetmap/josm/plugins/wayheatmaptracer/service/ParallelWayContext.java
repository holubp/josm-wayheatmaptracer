package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * Read-only geometry and non-sensitive tags for one nearby parallel OSM way.
 *
 * @param wayId OSM way identifier
 * @param geometry projected way geometry
 * @param tags assignment-relevant tags only
 * @param meanDistanceMeters mean distance from the selected segment
 * @param directionAgreement absolute tangent cosine in the {@code [0,1]} range
 * @param side signed side relative to the selected way direction
 * @param overlapRatio fraction of contextual vertices inside the search corridor
 */
public record ParallelWayContext(
    long wayId,
    List<EastNorth> geometry,
    Map<String, String> tags,
    double meanDistanceMeters,
    double directionAgreement,
    double side,
    double overlapRatio
) {
    /**
     * Makes context geometry and tags immutable.
     */
    public ParallelWayContext {
        geometry = List.copyOf(geometry);
        tags = Map.copyOf(tags);
    }
}

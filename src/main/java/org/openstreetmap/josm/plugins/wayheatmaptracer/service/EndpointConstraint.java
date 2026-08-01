package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/**
 * Boundary condition applied to one sampled profile near an endpoint or shared junction.
 *
 * @param profileIndex constrained profile index
 * @param nodeId OSM node identifier used only for diagnostics
 * @param fixed whether zero lateral displacement is exact
 * @param junction whether the node is shared by another way
 * @param maxDisplacementPx hard lateral displacement limit for movable nodes
 * @param priorWeight penalty for moving a movable node away from its source coordinate
 * @param approachWindowProfiles number of profiles over which approach direction is stabilized
 */
public record EndpointConstraint(
    int profileIndex,
    long nodeId,
    boolean fixed,
    boolean junction,
    double maxDisplacementPx,
    double priorWeight,
    int approachWindowProfiles
) {
}

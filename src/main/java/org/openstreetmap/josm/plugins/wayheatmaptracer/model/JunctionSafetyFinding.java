package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * Reproducible connected-way topology finding evaluated on final preview geometry.
 *
 * @param reasonCode machine-readable finding type
 * @param geometryStage geometry stage used for the decision
 * @param junctionNodeId shared junction node id
 * @param connectedWayId connected way id
 * @param connectedStartNodeId first adjacent connected-segment node id
 * @param connectedEndNodeId second adjacent connected-segment node id
 * @param candidateSegmentIndex intersecting final-preview segment index
 * @param junctionPoint projected junction coordinate
 * @param connectedStart projected connected-segment start
 * @param connectedEnd projected connected-segment end
 * @param intersection projected intersection coordinate
 * @param distanceFromJunctionMeters intersection distance from the shared junction
 * @param toleranceMeters allowed junction-touch tolerance
 */
public record JunctionSafetyFinding(
    String reasonCode,
    String geometryStage,
    long junctionNodeId,
    long connectedWayId,
    long connectedStartNodeId,
    long connectedEndNodeId,
    int candidateSegmentIndex,
    EastNorth junctionPoint,
    EastNorth connectedStart,
    EastNorth connectedEnd,
    EastNorth intersection,
    double distanceFromJunctionMeters,
    double toleranceMeters
) {
}

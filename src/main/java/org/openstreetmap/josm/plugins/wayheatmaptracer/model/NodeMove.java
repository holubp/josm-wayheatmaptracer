package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;

/**
 * Target coordinate for moving an existing OSM node during alignment apply/redo.
 *
 * @param node existing OSM node to move
 * @param target projected coordinate that should be assigned to the node
 */
public record NodeMove(Node node, EastNorth target) {
}

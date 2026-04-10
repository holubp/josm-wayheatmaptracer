package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;

public record NodeMove(Node node, EastNorth target) {
}


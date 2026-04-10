package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

public record SelectionContext(
    Way way,
    int startIndex,
    int endIndex,
    List<Node> segmentNodes,
    Set<Node> fixedNodes
) {
    public boolean isFullWaySelection() {
        return startIndex == 0 && endIndex == way.getNodesCount() - 1;
    }
}


package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Validated selected segment of a single OSM way.
 *
 * @param way selected way
 * @param startIndex first selected node index in the way
 * @param endIndex last selected node index in the way
 * @param segmentNodes selected contiguous node sequence
 * @param fixedNodes nodes that must remain fixed unless junction adjustment is explicitly enabled
 */
public record SelectionContext(
    Way way,
    int startIndex,
    int endIndex,
    List<Node> segmentNodes,
    Set<Node> fixedNodes
) {
    /**
     * Checks whether the selected segment covers the whole way.
     *
     * @return {@code true} when start and end indices cover all way nodes
     */
    public boolean isFullWaySelection() {
        return startIndex == 0 && endIndex == way.getNodesCount() - 1;
    }
}

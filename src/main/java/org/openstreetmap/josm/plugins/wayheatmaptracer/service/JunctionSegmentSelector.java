package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.WaySegmentRange;

public final class JunctionSegmentSelector {
    public WaySegmentRange longestJunctionBoundedSegment(Way way) {
        if (way.getNodesCount() < 2) {
            throw new IllegalArgumentException("Way must contain at least two nodes.");
        }
        List<Integer> anchors = junctionOrEndpointIndices(way);
        WaySegmentRange best = null;
        double bestLength = -1.0;
        for (int i = 1; i < anchors.size(); i++) {
            int start = anchors.get(i - 1);
            int end = anchors.get(i);
            if (end <= start) {
                continue;
            }
            double length = length(way, start, end);
            if (length > bestLength) {
                bestLength = length;
                best = new WaySegmentRange(start, end);
            }
        }
        if (best == null) {
            return new WaySegmentRange(0, way.getNodesCount() - 1);
        }
        return best;
    }

    private List<Integer> junctionOrEndpointIndices(Way way) {
        List<Integer> anchors = new ArrayList<>();
        anchors.add(0);
        for (int i = 1; i < way.getNodesCount() - 1; i++) {
            Node node = way.getNode(i);
            if (node.referrers(Way.class).count() > 1) {
                anchors.add(i);
            }
        }
        int last = way.getNodesCount() - 1;
        if (anchors.get(anchors.size() - 1) != last) {
            anchors.add(last);
        }
        return anchors;
    }

    private double length(Way way, int startIndex, int endIndex) {
        double length = 0.0;
        for (int i = startIndex + 1; i <= endIndex; i++) {
            EastNorth previous = way.getNode(i - 1).getEastNorth(ProjectionRegistry.getProjection());
            EastNorth current = way.getNode(i).getEastNorth(ProjectionRegistry.getProjection());
            if (previous != null && current != null) {
                length += previous.distance(current);
            }
        }
        return length;
    }
}

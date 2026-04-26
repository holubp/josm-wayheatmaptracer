package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

public final class SelectionResolver {
    private SelectionResolver() {
    }

    public static SelectionContext resolve(DataSet dataSet) {
        return resolve(dataSet, false);
    }

    public static SelectionContext resolve(DataSet dataSet, boolean adjustJunctionNodes) {
        Collection<Way> ways = dataSet.getSelectedWays();
        Collection<Node> nodes = dataSet.getSelectedNodes();

        if (ways.size() != 1) {
            throw new IllegalStateException("Select exactly one way.");
        }

        Way way = ways.iterator().next();
        if (nodes.size() != 0 && nodes.size() != 2) {
            throw new IllegalStateException("Select either only the way, or the way plus exactly two nodes on that way.");
        }

        int start = 0;
        int end = way.getNodesCount() - 1;
        if (nodes.size() == 2) {
            List<Integer> indexes = new ArrayList<>();
            for (Node node : nodes) {
                int index = way.getNodes().indexOf(node);
                if (index < 0) {
                    throw new IllegalStateException("Selected nodes must belong to the selected way.");
                }
                indexes.add(index);
            }
            indexes.sort(Integer::compareTo);
            start = indexes.get(0);
            end = indexes.get(1);
            if (start == end) {
                throw new IllegalStateException("Selected nodes must define a non-empty segment.");
            }
        }

        List<Node> segmentNodes = new ArrayList<>(way.getNodes().subList(start, end + 1));
        Set<Node> fixedNodes = new LinkedHashSet<>();
        if (!adjustJunctionNodes) {
            fixedNodes.add(segmentNodes.get(0));
            fixedNodes.add(segmentNodes.get(segmentNodes.size() - 1));
            PluginLog.debug("Fixed node %d because it is the segment start anchor.", segmentNodes.get(0).getUniqueId());
            PluginLog.debug("Fixed node %d because it is the segment end anchor.", segmentNodes.get(segmentNodes.size() - 1).getUniqueId());
        } else {
            PluginLog.verbose("Junction/end node adjustment is enabled; segment anchors may move.");
        }

        for (int i = 1; i < segmentNodes.size() - 1; i++) {
            Node node = segmentNodes.get(i);
            long referringWays = node.referrers(Way.class).count();
            if (!adjustJunctionNodes && referringWays > 1) {
                fixedNodes.add(node);
                PluginLog.debug("Fixed node %d because it is shared by %d ways.", node.getUniqueId(), referringWays);
            }
        }

        SelectionContext context = new SelectionContext(way, start, end, segmentNodes, fixedNodes);
        PluginLog.verbose("Resolved selection: way=%d start=%d end=%d segmentNodes=%d fixedNodes=%d fullWay=%s.",
            way.getUniqueId(), start, end, segmentNodes.size(), fixedNodes.size(), context.isFullWaySelection());
        if (!fixedNodes.isEmpty()) {
            PluginLog.debug("Fixed node ids: %s", fixedNodes.stream().map(node -> Long.toString(node.getUniqueId())).toList());
        }
        PluginLog.debug("Segment node ids: %s", segmentNodes.stream().map(node -> Long.toString(node.getUniqueId())).toList());
        return context;
    }
}

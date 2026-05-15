package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;

public final class SelectionIntegrity {
    private static final double SOURCE_POSITION_EPSILON_METERS = 0.001;

    private SelectionIntegrity() {
    }

    public static void requireNoRepeatedNodeOccurrences(Way way, int startIndex, int endIndex) {
        Map<Node, List<Integer>> occurrences = occurrenceIndexes(way);
        for (Map.Entry<Node, List<Integer>> entry : occurrences.entrySet()) {
            List<Integer> indexes = entry.getValue();
            long insideCount = indexes.stream()
                .filter(index -> index >= startIndex && index <= endIndex)
                .count();
            if (insideCount > 1) {
                throw new IllegalStateException("Selected segment contains a repeated node. Split the way or select a simpler segment before aligning.");
            }
            if (insideCount == 1 && indexes.size() > 1) {
                throw new IllegalStateException("Selected segment contains a node that also appears elsewhere in the way. Split the way or select a simpler segment before aligning.");
            }
        }
    }

    public static void requirePreviewSourceUnchanged(
        DataSet dataSet,
        SelectionContext selection,
        List<EastNorth> previewSourcePolyline
    ) {
        if (selection.way().getDataSet() != dataSet) {
            throw new IllegalStateException("The selected way changed while the heatmap preview was open. Run the slide again.");
        }
        List<Node> currentNodes = selection.way().getNodes();
        if (selection.endIndex() >= currentNodes.size()) {
            throw new IllegalStateException("The selected way changed while the heatmap preview was open. Run the slide again.");
        }
        if (previewSourcePolyline.size() != selection.segmentNodes().size()) {
            throw new IllegalStateException("The heatmap preview source snapshot is inconsistent. Run the slide again.");
        }
        for (int i = 0; i < selection.segmentNodes().size(); i++) {
            Node expectedNode = selection.segmentNodes().get(i);
            Node currentNode = currentNodes.get(selection.startIndex() + i);
            if (currentNode != expectedNode || expectedNode.getDataSet() != dataSet) {
                throw new IllegalStateException("The selected way changed while the heatmap preview was open. Run the slide again.");
            }
            EastNorth currentPosition = expectedNode.getEastNorth(ProjectionRegistry.getProjection());
            EastNorth previewPosition = previewSourcePolyline.get(i);
            if (currentPosition == null || previewPosition == null
                || currentPosition.distance(previewPosition) > SOURCE_POSITION_EPSILON_METERS) {
                throw new IllegalStateException("The selected way geometry changed while the heatmap preview was open. Run the slide again.");
            }
        }
    }

    private static Map<Node, List<Integer>> occurrenceIndexes(Way way) {
        Map<Node, List<Integer>> occurrences = new IdentityHashMap<>();
        List<Node> nodes = way.getNodes();
        for (int i = 0; i < nodes.size(); i++) {
            occurrences.computeIfAbsent(nodes.get(i), ignored -> new ArrayList<>()).add(i);
        }
        return occurrences;
    }
}

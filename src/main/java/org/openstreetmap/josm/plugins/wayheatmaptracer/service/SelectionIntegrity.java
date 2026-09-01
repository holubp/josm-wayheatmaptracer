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

/**
 * Guards modeless preview/apply operations against unsafe or stale OSM selections.
 */
public final class SelectionIntegrity {
    private static final double SOURCE_POSITION_EPSILON_METERS = 0.001;

    private SelectionIntegrity() {
    }

    /**
     * Rejects selected segments whose nodes appear more than once in the way.
     *
     * @param way selected way
     * @param startIndex first selected node index
     * @param endIndex last selected node index
     * @throws IllegalStateException when repeated-node occurrence identity would be ambiguous
     */
    public static void requireNoRepeatedNodeOccurrences(Way way, int startIndex, int endIndex) {
        Map<Node, List<Integer>> occurrences = occurrenceIndex(way).occurrences();
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

    /**
     * Builds the identity-based node occurrence index shared by selection helpers.
     *
     * @param way way whose node occurrences are indexed
     * @return immutable occurrence index with constant-time range safety queries
     */
    static NodeOccurrenceIndex occurrenceIndex(Way way) {
        return new NodeOccurrenceIndex(way);
    }

    /**
     * Ensures the way and selected source coordinates still match the slide-time preview snapshot.
     *
     * @param dataSet expected active dataset
     * @param selection slide-time selected segment
     * @param previewSourcePolyline source geometry stored when the preview was opened
     * @throws IllegalStateException when the selection or source geometry changed
     */
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

    /**
     * Identity-based occurrence information for one immutable view of a way's node sequence.
     */
    static final class NodeOccurrenceIndex {
        private final Map<Node, List<Integer>> occurrences;
        private final int[] repeatedOccurrencePrefix;

        /**
         * Indexes all node identities in way order.
         *
         * @param way source way
         */
        NodeOccurrenceIndex(Way way) {
            Map<Node, List<Integer>> mutableOccurrences = new IdentityHashMap<>();
            List<Node> nodes = way.getNodes();
            for (int i = 0; i < nodes.size(); i++) {
                mutableOccurrences.computeIfAbsent(nodes.get(i), ignored -> new ArrayList<>()).add(i);
            }
            Map<Node, List<Integer>> immutableOccurrences = new IdentityHashMap<>();
            mutableOccurrences.forEach((node, indexes) -> immutableOccurrences.put(node, List.copyOf(indexes)));
            occurrences = java.util.Collections.unmodifiableMap(immutableOccurrences);
            repeatedOccurrencePrefix = new int[nodes.size() + 1];
            for (int i = 0; i < nodes.size(); i++) {
                repeatedOccurrencePrefix[i + 1] = repeatedOccurrencePrefix[i]
                    + (occurrences.get(nodes.get(i)).size() > 1 ? 1 : 0);
            }
        }

        /**
         * Returns whether every node identity in an inclusive range occurs exactly once in the whole way.
         *
         * @param startIndex inclusive first occurrence index
         * @param endIndex inclusive last occurrence index
         * @return true when the range satisfies repeated-node selection safety
         */
        boolean rangeIsUnambiguous(int startIndex, int endIndex) {
            if (startIndex < 0 || endIndex < startIndex || endIndex + 1 >= repeatedOccurrencePrefix.length) {
                throw new IllegalArgumentException("Node occurrence range is outside the way.");
            }
            return repeatedOccurrencePrefix[endIndex + 1] == repeatedOccurrencePrefix[startIndex];
        }

        /**
         * Returns identity-based occurrence indexes for a node.
         *
         * @param node node identity to query
         * @return immutable occurrence indexes, or an empty list when absent
         */
        List<Integer> indexes(Node node) {
            return occurrences.getOrDefault(node, List.of());
        }

        /** Returns the immutable identity map used by the authoritative exception-producing validator. */
        Map<Node, List<Integer>> occurrences() {
            return occurrences;
        }
    }
}

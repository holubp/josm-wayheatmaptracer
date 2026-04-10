package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.Icon;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.tools.ImageProvider;

public final class ReplaceWaySegmentCommand extends Command {
    private final Way way;
    private final SelectionContext selection;
    private final List<EastNorth> previewPolyline;
    private final String description;

    private List<Node> originalWayNodes;
    private final Map<Node, LatLon> originalNodePositions = new IdentityHashMap<>();
    private final List<Node> createdNodes = new ArrayList<>();
    private final List<Node> removedExistingNodes = new ArrayList<>();
    private List<Node> replacementNodes;

    public ReplaceWaySegmentCommand(DataSet dataSet, Way way, SelectionContext selection, List<EastNorth> previewPolyline, String description) {
        super(dataSet);
        this.way = way;
        this.selection = selection;
        this.previewPolyline = List.copyOf(previewPolyline);
        this.description = description;
    }

    @Override
    public boolean executeCommand() {
        super.executeCommand();
        if (originalWayNodes == null) {
            originalWayNodes = new ArrayList<>(way.getNodes());
        }
        if (replacementNodes == null) {
            replacementNodes = buildReplacementNodes();
        }
        for (Node node : createdNodes) {
            if (node.getDataSet() == null) {
                getAffectedDataSet().addPrimitive(node);
            }
        }
        for (Node node : removedExistingNodes) {
            if (node.getDataSet() != null && canRemoveDroppedNode(node)) {
                getAffectedDataSet().removePrimitive(node);
            }
        }
        way.setNodes(replacementNodes);
        for (Node node : removedExistingNodes) {
            if (node.getDataSet() != null && canRemoveDroppedNode(node)) {
                getAffectedDataSet().removePrimitive(node);
            }
        }
        way.setModified(true);
        return true;
    }

    @Override
    public void undoCommand() {
        for (Node node : removedExistingNodes) {
            if (node.getDataSet() == null) {
                getAffectedDataSet().addPrimitive(node);
            }
        }
        way.setNodes(originalWayNodes);
        for (Map.Entry<Node, LatLon> entry : originalNodePositions.entrySet()) {
            entry.getKey().setCoor(entry.getValue());
        }
        for (Node node : createdNodes) {
            if (node.getDataSet() != null && node.getReferrers().isEmpty()) {
                getAffectedDataSet().removePrimitive(node);
            }
        }
        way.setModified(true);
        super.undoCommand();
    }

    private List<Node> buildReplacementNodes() {
        List<Node> before = new ArrayList<>(originalWayNodes.subList(0, selection.startIndex()));
        List<Node> after = new ArrayList<>(originalWayNodes.subList(selection.endIndex() + 1, originalWayNodes.size()));
        List<Node> segmentReplacement = new ArrayList<>();
        List<Node> orderedFixedNodes = orderedFixedNodes();

        List<Node> mutableExisting = new ArrayList<>();
        for (int i = 1; i < selection.segmentNodes().size() - 1; i++) {
            Node node = selection.segmentNodes().get(i);
            if (!selection.fixedNodes().contains(node)) {
                mutableExisting.add(node);
            }
        }

        int reuseCursor = 0;
        int fixedCursor = 0;
        for (int i = 0; i < previewPolyline.size(); i++) {
            EastNorth target = previewPolyline.get(i);
            Node node;
            if (fixedCursor < orderedFixedNodes.size() && matchesFixedAnchor(target, orderedFixedNodes.get(fixedCursor))) {
                node = orderedFixedNodes.get(fixedCursor++);
            } else if (reuseCursor < mutableExisting.size()) {
                node = mutableExisting.get(reuseCursor++);
            } else {
                node = new Node(ProjectionRegistry.getProjection().eastNorth2latlon(target));
                getAffectedDataSet().addPrimitive(node);
                createdNodes.add(node);
            }

            if (!originalNodePositions.containsKey(node)) {
                originalNodePositions.put(node, node.getCoor());
            }
            if (!selection.fixedNodes().contains(node)) {
                node.setEastNorth(target);
                node.setModified(true);
            }
            segmentReplacement.add(node);
        }

        for (int i = reuseCursor; i < mutableExisting.size(); i++) {
            Node dropped = mutableExisting.get(i);
            if (!removedExistingNodes.contains(dropped)) {
                removedExistingNodes.add(dropped);
            }
        }

        List<Node> nodes = new ArrayList<>(before.size() + segmentReplacement.size() + after.size());
        nodes.addAll(before);
        nodes.addAll(segmentReplacement);
        nodes.addAll(after);
        return nodes;
    }

    private boolean canRemoveDroppedNode(Node node) {
        return !node.hasKeys() && node.getReferrers().isEmpty();
    }

    private List<Node> orderedFixedNodes() {
        List<Node> ordered = new ArrayList<>();
        for (Node node : selection.segmentNodes()) {
            if (selection.fixedNodes().contains(node)) {
                ordered.add(node);
            }
        }
        return ordered;
    }

    private boolean matchesFixedAnchor(EastNorth target, Node node) {
        EastNorth anchor = node.getEastNorth(ProjectionRegistry.getProjection());
        return anchor != null && anchor.distance(target) < 0.01;
    }

    @Override
    public void fillModifiedData(Collection<OsmPrimitive> modified, Collection<OsmPrimitive> deleted, Collection<OsmPrimitive> added) {
        modified.add(way);
        modified.addAll(replacementNodes == null ? List.of() : replacementNodes);
        added.addAll(createdNodes);
        deleted.addAll(removedExistingNodes.stream().filter(this::canRemoveDroppedNode).toList());
    }

    @Override
    public String getDescriptionText() {
        return description;
    }

    @Override
    public Icon getDescriptionIcon() {
        return new ImageProvider("dialogs", "search").get();
    }

    @Override
    public Collection<? extends OsmPrimitive> getParticipatingPrimitives() {
        List<OsmPrimitive> primitives = new ArrayList<>();
        primitives.add(way);
        primitives.addAll(selection.segmentNodes());
        primitives.addAll(createdNodes);
        return primitives;
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PolylineMath;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PolylineMath.ProjectionOnPolyline;
import org.openstreetmap.josm.tools.ImageProvider;

/**
 * Undoable command that replaces a selected way segment with precise preview geometry.
 */
public final class ReplaceWaySegmentCommand extends Command {
    private static final double ANCHOR_MATCH_EPSILON_METERS = 0.01;
    private static final double SOFT_ANCHOR_SEARCH_FRACTION = 0.08;
    private static final double SOFT_ANCHOR_SEARCH_METERS = 35.0;

    private final Way way;
    private final SelectionContext selection;
    private final List<EastNorth> previewPolyline;
    private final String description;

    private List<Node> originalWayNodes;
    private final Map<Node, LatLon> originalNodePositions = new IdentityHashMap<>();
    private final Map<Node, EastNorth> targetNodePositions = new IdentityHashMap<>();
    private final List<Node> createdNodes = new ArrayList<>();
    private final List<Node> removedExistingNodes = new ArrayList<>();
    private List<Node> replacementNodes;

    /**
     * Creates a command for replacing only the selected segment of a way.
     *
     * @param dataSet target OSM dataset
     * @param way way whose selected segment will be replaced
     * @param selection validated selected segment metadata
     * @param previewPolyline replacement geometry in projected coordinates
     * @param description undo/redo menu description
     */
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
        applyTargetNodePositions();
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
        List<EastNorth> sourcePolyline = toEastNorth(selection.segmentNodes());
        List<Double> sourceFractions = PolylineMath.fractionsForSegment(sourcePolyline);
        List<Double> previewFractions = PolylineMath.fractionsForSegment(previewPolyline);
        List<SoftAnchor> softAnchors = softAnchors(sourcePolyline, sourceFractions, previewFractions);
        Set<Node> softAnchorNodes = new HashSet<>();
        for (SoftAnchor anchor : softAnchors) {
            softAnchorNodes.add(anchor.node());
        }

        List<Node> mutableExisting = new ArrayList<>();
        for (int i = 0; i < selection.segmentNodes().size(); i++) {
            Node node = selection.segmentNodes().get(i);
            if (!selection.fixedNodes().contains(node) && !softAnchorNodes.contains(node)) {
                mutableExisting.add(node);
            }
        }

        int fixedCursor = 0;
        int softCursor = 0;
        double startBoundary = softAnchors.stream()
            .filter(anchor -> anchor.node() == selection.segmentNodes().get(0))
            .mapToDouble(SoftAnchor::fraction)
            .findFirst()
            .orElse(0.0);
        double endBoundary = softAnchors.stream()
            .filter(anchor -> anchor.node() == selection.segmentNodes().get(selection.segmentNodes().size() - 1))
            .mapToDouble(SoftAnchor::fraction)
            .findFirst()
            .orElse(1.0);
        for (int i = 0; i < previewPolyline.size(); i++) {
            EastNorth target = previewPolyline.get(i);
            double previewFraction = previewFractions.get(i);
            if (previewFraction < startBoundary - 1e-9 || previewFraction > endBoundary + 1e-9) {
                continue;
            }
            while (softCursor < softAnchors.size()
                && softAnchors.get(softCursor).fraction() <= previewFraction + 1e-9) {
                Node anchorNode = softAnchors.get(softCursor).node();
                appendNode(segmentReplacement, anchorNode, softAnchors.get(softCursor).target());
                softCursor++;
            }
            if (!segmentReplacement.isEmpty()
                && segmentReplacement.get(segmentReplacement.size() - 1).getEastNorth(ProjectionRegistry.getProjection()).distance(target)
                    < ANCHOR_MATCH_EPSILON_METERS) {
                continue;
            }
            Node node;
            if (fixedCursor < orderedFixedNodes.size() && matchesFixedAnchor(target, orderedFixedNodes.get(fixedCursor))) {
                node = orderedFixedNodes.get(fixedCursor++);
            } else {
                node = nextMutableNode(mutableExisting);
                if (node == null) {
                    node = new Node(ProjectionRegistry.getProjection().eastNorth2latlon(target));
                    createdNodes.add(node);
                }
            }

            appendNode(segmentReplacement, node, target);
        }
        while (softCursor < softAnchors.size()) {
            appendNode(segmentReplacement, softAnchors.get(softCursor).node(), softAnchors.get(softCursor).target());
            softCursor++;
        }

        for (Node dropped : mutableExisting) {
            if (dropped != null && !removedExistingNodes.contains(dropped)) {
                removedExistingNodes.add(dropped);
            }
        }

        List<Node> nodes = new ArrayList<>(before.size() + segmentReplacement.size() + after.size());
        nodes.addAll(before);
        nodes.addAll(segmentReplacement);
        nodes.addAll(after);
        return nodes;
    }

    private Node nextMutableNode(List<Node> mutableExisting) {
        for (int i = 0; i < mutableExisting.size(); i++) {
            Node node = mutableExisting.get(i);
            if (node != null) {
                mutableExisting.set(i, null);
                return node;
            }
        }
        return null;
    }

    private boolean canRemoveDroppedNode(Node node) {
        return !node.hasKeys() && node.getReferrers().isEmpty();
    }

    private void applyTargetNodePositions() {
        for (Map.Entry<Node, EastNorth> entry : targetNodePositions.entrySet()) {
            entry.getKey().setEastNorth(entry.getValue());
            entry.getKey().setModified(true);
        }
    }

    private void appendNode(List<Node> segmentReplacement, Node node, EastNorth target) {
        if (!segmentReplacement.isEmpty() && segmentReplacement.get(segmentReplacement.size() - 1) == node) {
            return;
        }
        if (!originalNodePositions.containsKey(node)) {
            originalNodePositions.put(node, node.getCoor());
        }
        if (!selection.fixedNodes().contains(node)) {
            targetNodePositions.put(node, target);
            node.setEastNorth(target);
            node.setModified(true);
        }
        segmentReplacement.add(node);
    }

    private List<SoftAnchor> softAnchors(
        List<EastNorth> sourcePolyline,
        List<Double> sourceFractions,
        List<Double> previewFractions
    ) {
        if (previewPolyline.size() < 2) {
            return List.of();
        }
        List<SoftAnchor> anchors = new ArrayList<>();
        int last = selection.segmentNodes().size() - 1;
        double previewLength = PolylineMath.length(previewPolyline);
        double window = Math.max(SOFT_ANCHOR_SEARCH_FRACTION, SOFT_ANCHOR_SEARCH_METERS / Math.max(1.0, previewLength));
        for (int i = 0; i < selection.segmentNodes().size(); i++) {
            Node node = selection.segmentNodes().get(i);
            if (selection.fixedNodes().contains(node) || !isSoftAnchor(node, i, last)) {
                continue;
            }
            ProjectionOnPolyline projection = PolylineMath.closestPointNearFraction(
                previewPolyline,
                previewFractions,
                sourcePolyline.get(i),
                sourceFractions.get(i),
                window
            );
            anchors.add(new SoftAnchor(node, projection.point(), projection.fraction()));
        }
        anchors.sort(Comparator.comparingDouble(SoftAnchor::fraction));
        return anchors;
    }

    private boolean isSoftAnchor(Node node, int index, int last) {
        if (index == 0 || index == last || node.hasKeys()) {
            return true;
        }
        return node.getReferrers().stream().anyMatch(referrer -> referrer != way);
    }

    private List<EastNorth> toEastNorth(List<Node> nodes) {
        List<EastNorth> result = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            result.add(node.getEastNorth(ProjectionRegistry.getProjection()));
        }
        return result;
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
        return anchor != null && anchor.distance(target) < ANCHOR_MATCH_EPSILON_METERS;
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

    private record SoftAnchor(Node node, EastNorth target, double fraction) {
    }

}

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
import org.openstreetmap.josm.tools.ImageProvider;

/**
 * Undoable command that replaces a selected way segment with precise preview geometry.
 */
public final class ReplaceWaySegmentCommand extends Command {
    private static final double ANCHOR_MATCH_EPSILON_METERS = 0.01;
    private static final double PROVIDED_TARGET_EPSILON = 1e-7;

    private final Way way;
    private final SelectionContext selection;
    private final List<EastNorth> previewPolyline;
    private final Map<Long, EastNorth> proposedNodePositions;
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
        this(dataSet, way, selection, previewPolyline, null, description);
    }

    /**
     * Creates a command using the exact existing-node plan evaluated by preview safety.
     *
     * @param dataSet target OSM dataset
     * @param way way whose selected segment will be replaced
     * @param selection validated selected segment metadata
     * @param previewPolyline replacement geometry in projected coordinates
     * @param proposedNodePositions candidate-owned existing-node targets keyed by stable node id
     * @param description undo/redo menu description
     */
    public ReplaceWaySegmentCommand(
        DataSet dataSet,
        Way way,
        SelectionContext selection,
        List<EastNorth> previewPolyline,
        Map<Long, EastNorth> proposedNodePositions,
        String description
    ) {
        super(dataSet);
        this.way = way;
        this.selection = selection;
        this.previewPolyline = List.copyOf(previewPolyline);
        this.proposedNodePositions = proposedNodePositions == null || proposedNodePositions.isEmpty()
            ? null : Map.copyOf(proposedNodePositions);
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
        List<PreviewNodeAssignmentPlanner.NodeAssignment> plannedAssignments =
            PreviewNodeAssignmentPlanner.preciseAssignments(selection, sourcePolyline, previewPolyline);
        validateProposedNodePositions(plannedAssignments);
        for (PreviewNodeAssignmentPlanner.NodeAssignment assignment : plannedAssignments) {
            PluginLog.verbose(
                "Precise protected node assignment node=%d fixed=%s source=(%.3f,%.3f) target=(%.3f,%.3f).",
                assignment.node().getUniqueId(), assignment.fixed(), assignment.source().east(),
                assignment.source().north(), assignment.target().east(), assignment.target().north());
        }
        List<SoftAnchor> softAnchors = softAnchors(plannedAssignments);
        Set<Node> softAnchorNodes = new HashSet<>();
        for (SoftAnchor anchor : softAnchors) {
            softAnchorNodes.add(anchor.node());
        }

        List<Node> mutableExisting = new ArrayList<>();
        List<Double> mutableFractions = new ArrayList<>();
        for (int i = 0; i < selection.segmentNodes().size(); i++) {
            Node node = selection.segmentNodes().get(i);
            if (!selection.fixedNodes().contains(node) && !softAnchorNodes.contains(node)) {
                mutableExisting.add(node);
                mutableFractions.add(sourceFractions.get(i));
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
        List<AnchorBoundary> anchorBoundaries = anchorBoundaries(
            sourceFractions, previewFractions, softAnchors, orderedFixedNodes);
        Map<Integer, Node> mutableAssignments = assignMutableNodes(
            mutableExisting, mutableFractions, previewFractions, startBoundary, endBoundary,
            orderedFixedNodes, softAnchors, anchorBoundaries);
        for (Map.Entry<Integer, Node> entry : mutableAssignments.entrySet()) {
            int sourceIndex = mutableExisting.indexOf(entry.getValue());
            double sourceFraction = sourceIndex < 0 ? Double.NaN : mutableFractions.get(sourceIndex);
            double previewFraction = previewFractions.get(entry.getKey());
            PluginLog.verbose(
                "Precise node reuse node=%d sourceFraction=%.6f previewIndex=%d previewFraction=%.6f error=%.6f.",
                entry.getValue().getUniqueId(), sourceFraction, entry.getKey(), previewFraction,
                Math.abs(sourceFraction - previewFraction));
        }
        Set<Node> usedMutableNodes = new HashSet<>();
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
                && plannedPosition(segmentReplacement.get(segmentReplacement.size() - 1)).distance(target)
                    < ANCHOR_MATCH_EPSILON_METERS) {
                continue;
            }
            Node node;
            if (fixedCursor < orderedFixedNodes.size() && matchesFixedAnchor(target, orderedFixedNodes.get(fixedCursor))) {
                node = orderedFixedNodes.get(fixedCursor++);
            } else {
                node = mutableAssignments.get(i);
                if (node == null) {
                    node = new Node(ProjectionRegistry.getProjection().eastNorth2latlon(target));
                    createdNodes.add(node);
                }
            }

            appendNode(segmentReplacement, node, target);
            if (mutableExisting.contains(node)) {
                usedMutableNodes.add(node);
            }
        }
        while (softCursor < softAnchors.size()) {
            appendNode(segmentReplacement, softAnchors.get(softCursor).node(), softAnchors.get(softCursor).target());
            softCursor++;
        }

        for (Node dropped : mutableExisting) {
            if (!usedMutableNodes.contains(dropped) && !removedExistingNodes.contains(dropped)) {
                removedExistingNodes.add(dropped);
            }
        }

        List<Node> nodes = new ArrayList<>(before.size() + segmentReplacement.size() + after.size());
        nodes.addAll(before);
        nodes.addAll(segmentReplacement);
        nodes.addAll(after);
        return nodes;
    }

    private Map<Integer, Node> assignMutableNodes(
        List<Node> nodes,
        List<Double> sourceFractions,
        List<Double> previewFractions,
        double startBoundary,
        double endBoundary,
        List<Node> fixedNodes,
        List<SoftAnchor> softAnchors,
        List<AnchorBoundary> anchorBoundaries
    ) {
        List<Integer> slots = new ArrayList<>();
        for (int previewIndex = 0; previewIndex < previewPolyline.size(); previewIndex++) {
            double fraction = previewFractions.get(previewIndex);
            EastNorth target = previewPolyline.get(previewIndex);
            if (fraction >= startBoundary - 1e-9 && fraction <= endBoundary + 1e-9
                && fixedNodes.stream().noneMatch(node -> matchesFixedAnchor(target, node))
                && softAnchors.stream().noneMatch(anchor -> anchor.target().distance(target)
                    < ANCHOR_MATCH_EPSILON_METERS)) {
                slots.add(previewIndex);
            }
        }
        MatchCell[][] table = new MatchCell[nodes.size() + 1][slots.size() + 1];
        table[nodes.size()][slots.size()] = new MatchCell(0, 0.0, MatchDecision.DONE);
        for (int sourceIndex = nodes.size(); sourceIndex >= 0; sourceIndex--) {
            for (int slotIndex = slots.size(); slotIndex >= 0; slotIndex--) {
                if (sourceIndex == nodes.size() && slotIndex == slots.size()) {
                    continue;
                }
                MatchCell best = null;
                if (sourceIndex < nodes.size()) {
                    best = choose(best, advance(table[sourceIndex + 1][slotIndex], MatchDecision.SKIP_SOURCE,
                        0, 0.0));
                }
                if (slotIndex < slots.size()) {
                    best = choose(best, advance(table[sourceIndex][slotIndex + 1], MatchDecision.SKIP_SLOT,
                        0, 0.0));
                }
                if (sourceIndex < nodes.size() && slotIndex < slots.size()) {
                    double sourceFraction = sourceFractions.get(sourceIndex);
                    double previewFraction = previewFractions.get(slots.get(slotIndex));
                    if (anchorInterval(sourceFraction, anchorBoundaries, true)
                        == anchorInterval(previewFraction, anchorBoundaries, false)) {
                        double error = Math.abs(sourceFraction - previewFraction);
                        best = choose(best, advance(table[sourceIndex + 1][slotIndex + 1], MatchDecision.MATCH,
                            1, error));
                    }
                }
                table[sourceIndex][slotIndex] = best;
            }
        }
        Map<Integer, Node> result = new java.util.LinkedHashMap<>();
        int sourceIndex = 0;
        int slotIndex = 0;
        while (sourceIndex < nodes.size() || slotIndex < slots.size()) {
            MatchDecision decision = table[sourceIndex][slotIndex].decision();
            if (decision == MatchDecision.MATCH) {
                result.put(slots.get(slotIndex), nodes.get(sourceIndex));
                sourceIndex++;
                slotIndex++;
            } else if (decision == MatchDecision.SKIP_SOURCE) {
                sourceIndex++;
            } else if (decision == MatchDecision.SKIP_SLOT) {
                slotIndex++;
            } else {
                break;
            }
        }
        return result;
    }

    private List<AnchorBoundary> anchorBoundaries(
        List<Double> sourceFractions,
        List<Double> previewFractions,
        List<SoftAnchor> softAnchors,
        List<Node> fixedNodes
    ) {
        List<AnchorBoundary> result = new ArrayList<>();
        for (SoftAnchor anchor : softAnchors) {
            result.add(new AnchorBoundary(anchor.sourceFraction(), anchor.fraction(), anchor.node()));
        }
        for (Node fixedNode : fixedNodes) {
            int sourceIndex = identityIndexOf(selection.segmentNodes(), fixedNode);
            int previewIndex = matchingPreviewIndex(fixedNode);
            if (sourceIndex < 0 || previewIndex < 0) {
                throw new IllegalStateException("Fixed anchor is missing from source or preview geometry");
            }
            result.add(new AnchorBoundary(sourceFractions.get(sourceIndex), previewFractions.get(previewIndex),
                fixedNode));
        }
        result.sort(Comparator.comparingDouble(AnchorBoundary::sourceFraction));
        double previousPreviewFraction = Double.NEGATIVE_INFINITY;
        for (AnchorBoundary boundary : result) {
            if (boundary.previewFraction() + 1e-9 < previousPreviewFraction) {
                throw new IllegalStateException("Protected anchors are not monotonic in preview geometry");
            }
            previousPreviewFraction = boundary.previewFraction();
        }
        return List.copyOf(result);
    }

    private int anchorInterval(double fraction, List<AnchorBoundary> boundaries, boolean source) {
        int interval = 0;
        for (AnchorBoundary boundary : boundaries) {
            double boundaryFraction = source ? boundary.sourceFraction() : boundary.previewFraction();
            if (fraction <= boundaryFraction + 1e-9) {
                break;
            }
            interval++;
        }
        return interval;
    }

    private int identityIndexOf(List<Node> nodes, Node target) {
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index) == target) {
                return index;
            }
        }
        return -1;
    }

    private int matchingPreviewIndex(Node fixedNode) {
        for (int index = 0; index < previewPolyline.size(); index++) {
            if (matchesFixedAnchor(previewPolyline.get(index), fixedNode)) {
                return index;
            }
        }
        return -1;
    }

    private MatchCell advance(MatchCell following, MatchDecision decision, int matches, double error) {
        if (following == null) {
            return null;
        }
        return new MatchCell(following.matches() + matches, following.error() + error, decision);
    }

    private MatchCell choose(MatchCell first, MatchCell second) {
        if (second == null) {
            return first;
        }
        if (first == null || second.matches() > first.matches()
            || second.matches() == first.matches() && second.error() < first.error() - 1e-12
            || second.matches() == first.matches() && Math.abs(second.error() - first.error()) <= 1e-12
                && second.decision().priority < first.decision().priority) {
            return second;
        }
        return first;
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
        }
        segmentReplacement.add(node);
    }

    private EastNorth plannedPosition(Node node) {
        EastNorth target = targetNodePositions.get(node);
        return target == null ? node.getEastNorth(ProjectionRegistry.getProjection()) : target;
    }

    private List<SoftAnchor> softAnchors(List<PreviewNodeAssignmentPlanner.NodeAssignment> assignments) {
        List<SoftAnchor> anchors = new ArrayList<>();
        for (PreviewNodeAssignmentPlanner.NodeAssignment assignment : assignments) {
            if (!assignment.fixed()) {
                EastNorth target = proposedNodePositions == null
                    ? assignment.target() : proposedNodePositions.get(assignment.node().getUniqueId());
                anchors.add(new SoftAnchor(assignment.node(), target, assignment.previewFraction(),
                    assignment.sourceFraction()));
            }
        }
        anchors.sort(Comparator.comparingDouble(SoftAnchor::fraction));
        return List.copyOf(anchors);
    }

    private void validateProposedNodePositions(
        List<PreviewNodeAssignmentPlanner.NodeAssignment> assignments
    ) {
        if (proposedNodePositions == null) {
            return;
        }
        Map<Long, EastNorth> expected = PreviewNodeAssignmentPlanner.targetMap(assignments);
        if (!proposedNodePositions.keySet().equals(expected.keySet())) {
            throw new IllegalStateException("Candidate existing-node assignments do not match the selected topology");
        }
        for (Map.Entry<Long, EastNorth> entry : expected.entrySet()) {
            EastNorth provided = proposedNodePositions.get(entry.getKey());
            if (provided == null || !Double.isFinite(provided.east()) || !Double.isFinite(provided.north())
                || provided.distance(entry.getValue()) > PROVIDED_TARGET_EPSILON) {
                throw new IllegalStateException(
                    "Candidate existing-node assignment differs from the final preview plan for node " + entry.getKey());
            }
        }
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

    private record SoftAnchor(Node node, EastNorth target, double fraction, double sourceFraction) {
    }

    private record AnchorBoundary(double sourceFraction, double previewFraction, Node node) {
    }

    private record MatchCell(int matches, double error, MatchDecision decision) {
    }

    private enum MatchDecision {
        MATCH(0),
        SKIP_SLOT(1),
        SKIP_SOURCE(2),
        DONE(3);

        private final int priority;

        MatchDecision(int priority) {
            this.priority = priority;
        }
    }

}

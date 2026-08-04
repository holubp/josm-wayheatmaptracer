package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PolylineMath;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PolylineMath.ProjectionOnPolyline;

/** Pure planner shared by preview safety and precise command execution. */
public final class PreviewNodeAssignmentPlanner {
    private static final double LOCAL_SEARCH_FRACTION = 0.08;
    private static final double LOCAL_SEARCH_METERS = 35.0;

    private PreviewNodeAssignmentPlanner() {
    }

    /**
     * Plans fixed and topology-relevant existing nodes for precise-shape mode.
     *
     * @param selection selected way segment
     * @param sourcePolyline selected source geometry in node order
     * @param previewPolyline final preview geometry
     * @return immutable assignments ordered along the source segment
     */
    public static List<NodeAssignment> preciseAssignments(
        SelectionContext selection,
        List<EastNorth> sourcePolyline,
        List<EastNorth> previewPolyline
    ) {
        requireCompatibleSource(selection, sourcePolyline);
        requirePolyline(previewPolyline, "Preview");
        List<Double> sourceFractions = PolylineMath.fractionsForSegment(sourcePolyline);
        List<Double> previewFractions = PolylineMath.fractionsForSegment(previewPolyline);
        double previewLength = PolylineMath.length(previewPolyline);
        double window = Math.max(LOCAL_SEARCH_FRACTION, LOCAL_SEARCH_METERS / Math.max(1.0, previewLength));
        int last = selection.segmentNodes().size() - 1;
        List<NodeAssignment> assignments = new ArrayList<>();
        for (int index = 0; index <= last; index++) {
            Node node = selection.segmentNodes().get(index);
            double sourceFraction = sourceFractions.get(index);
            if (selection.fixedNodes().contains(node)) {
                ProjectionOnPolyline projection = PolylineMath.closestPointNearFraction(
                    previewPolyline, previewFractions, sourcePolyline.get(index), sourceFraction, 1.0);
                if (projection.point().distance(sourcePolyline.get(index)) > 1e-7) {
                    throw new IllegalStateException("Fixed selected node is missing from final preview geometry");
                }
                assignments.add(new NodeAssignment(node, sourcePolyline.get(index), sourcePolyline.get(index),
                    sourceFraction, projection.fraction(), true));
            } else if (isTopologyAnchor(selection, node, index, last)) {
                ProjectionOnPolyline projection = PolylineMath.closestPointNearFraction(
                    previewPolyline, previewFractions, sourcePolyline.get(index), sourceFraction, window);
                assignments.add(new NodeAssignment(node, sourcePolyline.get(index), projection.point(),
                    sourceFraction, projection.fraction(), false));
            }
        }
        assignments.sort(Comparator.comparingDouble(NodeAssignment::sourceFraction));
        requireMonotonic(assignments);
        return List.copyOf(assignments);
    }

    /**
     * Constrains precise preview geometry to the topology-anchor targets used by the command.
     *
     * @param selection selected way segment
     * @param sourcePolyline selected source geometry in node order
     * @param previewPolyline optimized preview geometry
     * @return geometry containing every exact proposed protected-node position
     */
    public static List<EastNorth> constrainPreciseTopology(
        SelectionContext selection,
        List<EastNorth> sourcePolyline,
        List<EastNorth> previewPolyline
    ) {
        List<NodeAssignment> assignments = preciseAssignments(selection, sourcePolyline, previewPolyline);
        Node firstNode = selection.segmentNodes().get(0);
        Node lastNode = selection.segmentNodes().get(selection.segmentNodes().size() - 1);
        NodeAssignment first = assignments.stream().filter(item -> item.node() == firstNode).findFirst()
            .orElseThrow(() -> new IllegalStateException("Selected start node has no preview assignment"));
        NodeAssignment last = assignments.stream().filter(item -> item.node() == lastNode).findFirst()
            .orElseThrow(() -> new IllegalStateException("Selected end node has no preview assignment"));
        if (last.previewFraction() + 1e-9 < first.previewFraction()) {
            throw new IllegalStateException("Proposed endpoint assignments reverse the selected segment");
        }
        List<Double> fractions = PolylineMath.fractionsForSegment(previewPolyline);
        List<EastNorth> result = new ArrayList<>();
        int assignmentIndex = 0;
        while (assignmentIndex < assignments.size()
            && assignments.get(assignmentIndex).previewFraction() < first.previewFraction() - 1e-9) {
            assignmentIndex++;
        }
        for (int index = 0; index < previewPolyline.size(); index++) {
            double fraction = fractions.get(index);
            if (fraction < first.previewFraction() - 1e-9 || fraction > last.previewFraction() + 1e-9) {
                continue;
            }
            while (assignmentIndex < assignments.size()
                && assignments.get(assignmentIndex).previewFraction() <= fraction + 1e-9
                && assignments.get(assignmentIndex).previewFraction() <= last.previewFraction() + 1e-9) {
                appendDistinct(result, assignments.get(assignmentIndex).target());
                assignmentIndex++;
            }
            appendDistinct(result, previewPolyline.get(index));
        }
        while (assignmentIndex < assignments.size()
            && assignments.get(assignmentIndex).previewFraction() <= last.previewFraction() + 1e-9) {
            appendDistinct(result, assignments.get(assignmentIndex).target());
            assignmentIndex++;
        }
        appendDistinct(result, last.target());
        if (result.size() < 2) {
            throw new IllegalStateException("Proposed endpoint assignments collapse the preview geometry");
        }
        return List.copyOf(result);
    }

    private static void appendDistinct(List<EastNorth> points, EastNorth point) {
        if (points.isEmpty() || points.get(points.size() - 1).distance(point) > 1e-9) {
            points.add(point);
        }
    }

    /**
     * Plans every selected node for move-existing-nodes mode.
     *
     * @param selection selected way segment
     * @param sourcePolyline selected source geometry in node order
     * @param previewPolyline final preview geometry
     * @return immutable assignments ordered along the source segment
     */
    public static List<NodeAssignment> moveExistingAssignments(
        SelectionContext selection,
        List<EastNorth> sourcePolyline,
        List<EastNorth> previewPolyline
    ) {
        requireCompatibleSource(selection, sourcePolyline);
        requirePolyline(previewPolyline, "Preview");
        List<Double> sourceFractions = PolylineMath.fractionsForSegment(sourcePolyline);
        List<Double> previewFractions = PolylineMath.fractionsForSegment(previewPolyline);
        List<NodeAssignment> assignments = new ArrayList<>(selection.segmentNodes().size());
        double previewLength = PolylineMath.length(previewPolyline);
        double window = Math.max(LOCAL_SEARCH_FRACTION, LOCAL_SEARCH_METERS / Math.max(1.0, previewLength));
        for (int index = 0; index < selection.segmentNodes().size(); index++) {
            Node node = selection.segmentNodes().get(index);
            double sourceFraction = sourceFractions.get(index);
            boolean fixed = selection.fixedNodes().contains(node);
            ProjectionOnPolyline projection = previewPolyline.size() == selection.segmentNodes().size()
                ? new ProjectionOnPolyline(previewPolyline.get(index), previewFractions.get(index), 0.0)
                : PolylineMath.closestPointNearFraction(previewPolyline, previewFractions,
                    sourcePolyline.get(index), sourceFraction, window);
            assignments.add(new NodeAssignment(node, sourcePolyline.get(index),
                fixed ? sourcePolyline.get(index) : projection.point(), sourceFraction,
                projection.fraction(), fixed));
        }
        requireMonotonic(assignments);
        return List.copyOf(assignments);
    }

    /**
     * Converts assignments to the stable-id representation carried by candidates.
     *
     * @param assignments planned existing-node assignments
     * @return immutable node-id to target-coordinate map
     */
    public static Map<Long, EastNorth> targetMap(List<NodeAssignment> assignments) {
        Map<Long, EastNorth> result = new LinkedHashMap<>();
        for (NodeAssignment assignment : assignments) {
            EastNorth previous = result.put(assignment.node().getUniqueId(), assignment.target());
            if (previous != null) {
                throw new IllegalStateException("Selected node occurs more than once in the assignment plan");
            }
        }
        return Map.copyOf(result);
    }

    private static boolean isTopologyAnchor(SelectionContext selection, Node node, int index, int last) {
        return index == 0 || index == last || node.hasKeys()
            || node.getReferrers().stream().anyMatch(referrer -> referrer != selection.way());
    }

    private static void requireCompatibleSource(SelectionContext selection, List<EastNorth> sourcePolyline) {
        if (selection == null || sourcePolyline == null
            || sourcePolyline.size() != selection.segmentNodes().size()) {
            throw new IllegalArgumentException("Source geometry must match the selected node sequence");
        }
        requirePolyline(sourcePolyline, "Source");
    }

    private static void requirePolyline(List<EastNorth> polyline, String label) {
        if (polyline == null || polyline.size() < 2) {
            throw new IllegalArgumentException(label + " geometry must contain at least two points");
        }
        for (EastNorth point : polyline) {
            if (point == null || !Double.isFinite(point.east()) || !Double.isFinite(point.north())) {
                throw new IllegalArgumentException(label + " geometry contains a non-finite point");
            }
        }
    }

    private static void requireMonotonic(List<NodeAssignment> assignments) {
        double previous = Double.NEGATIVE_INFINITY;
        for (NodeAssignment assignment : assignments) {
            if (assignment.previewFraction() + 1e-9 < previous) {
                throw new IllegalStateException("Existing-node targets are not monotonic in preview geometry");
            }
            previous = assignment.previewFraction();
        }
    }

    /**
     * One immutable source-to-preview assignment for an existing selected node.
     *
     * @param node existing selected node
     * @param source source coordinate captured for the slide
     * @param target exact proposed coordinate
     * @param sourceFraction longitudinal source fraction
     * @param previewFraction longitudinal preview fraction
     * @param fixed whether the node must remain at its source coordinate
     */
    public record NodeAssignment(
        Node node,
        EastNorth source,
        EastNorth target,
        double sourceFraction,
        double previewFraction,
        boolean fixed
    ) {
        /** Validates finite immutable assignment values. */
        public NodeAssignment {
            if (node == null || source == null || target == null
                || !Double.isFinite(source.east()) || !Double.isFinite(source.north())
                || !Double.isFinite(target.east()) || !Double.isFinite(target.north())
                || !Double.isFinite(sourceFraction) || !Double.isFinite(previewFraction)) {
                throw new IllegalArgumentException("Node assignment values must be finite and non-null");
            }
        }
    }
}

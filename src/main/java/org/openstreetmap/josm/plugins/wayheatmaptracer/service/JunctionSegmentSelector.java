package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.WaySegmentRange;

/**
 * Finds maximal junction-bounded way segments for the plugin-specific selection mode.
 * Shared-way nodes are legal inclusive endpoints but never occur in a returned range's open interior.
 */
public final class JunctionSegmentSelector {
    /** Creates a stateless junction-bounded segment selector. */
    public JunctionSegmentSelector() {
        // Stateless service.
    }

    /**
     * Finds the longest eligible maximal part of a way bounded by endpoints or shared-node junctions.
     * Repeated-node-ambiguous ranges are skipped, and exact length ties retain the earlier range in way order.
     *
     * @param way way to inspect
     * @return inclusive node-index range of the longest segment
     */
    public WaySegmentRange longestJunctionBoundedSegment(Way way) {
        return chooseLongest(candidates(way), ignored -> true,
            "No slideable non-branching segment is available on this way. "
                + "Repeated-node geometry may need to be split or simplified first.");
    }

    /**
     * Finds the longest eligible maximal junction-bounded segment containing one unique hint occurrence.
     * A shared junction is contained by both adjacent ranges; the longer range wins and exact ties retain
     * the earlier range in way order.
     *
     * @param way way to inspect
     * @param hintNode uniquely occurring node whose inclusive containing range is requested
     * @return inclusive node-index range of the longest eligible containing segment
     * @throws IllegalArgumentException when the hint is absent or occurs more than once
     * @throws IllegalStateException when no eligible maximal range contains the hint
     */
    public WaySegmentRange longestJunctionBoundedSegmentContaining(Way way, Node hintNode) {
        requireWaySize(way);
        if (hintNode == null) {
            throw new IllegalArgumentException("The selected node must belong to the selected way.");
        }
        List<Integer> hintIndexes = SelectionIntegrity.occurrenceIndex(way).indexes(hintNode);
        if (hintIndexes.isEmpty()) {
            throw new IllegalArgumentException("The selected node must belong to the selected way.");
        }
        if (hintIndexes.size() > 1) {
            throw new IllegalArgumentException(
                "The selected node occurs more than once in the way. "
                    + "Split the way or choose a non-repeated node before selecting a segment.");
        }
        int hintIndex = hintIndexes.get(0);
        return chooseLongest(candidates(way), range -> range.startIndex() <= hintIndex
                && hintIndex <= range.endIndex(),
            "No slideable non-branching segment containing the selected node is available.");
    }

    private List<SegmentCandidate> candidates(Way way) {
        requireWaySize(way);
        List<Integer> anchors = junctionOrEndpointIndices(way);
        SelectionIntegrity.NodeOccurrenceIndex occurrenceIndex = SelectionIntegrity.occurrenceIndex(way);
        List<SegmentCandidate> candidates = new ArrayList<>(Math.max(0, anchors.size() - 1));
        for (int i = 1; i < anchors.size(); i++) {
            int start = anchors.get(i - 1);
            int end = anchors.get(i);
            if (end > start) {
                WaySegmentRange range = new WaySegmentRange(start, end);
                candidates.add(new SegmentCandidate(range, length(way, start, end),
                    occurrenceIndex.rangeIsUnambiguous(start, end)));
            }
        }
        return List.copyOf(candidates);
    }

    private WaySegmentRange chooseLongest(
        List<SegmentCandidate> candidates,
        Predicate<WaySegmentRange> rangePredicate,
        String failureMessage
    ) {
        SegmentCandidate best = null;
        for (SegmentCandidate candidate : candidates) {
            WaySegmentRange range = candidate.range();
            if (!candidate.repeatedOccurrenceSafe()
                || !rangePredicate.test(range)) {
                continue;
            }
            if (best == null || candidate.length() > best.length()) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalStateException(failureMessage);
        }
        return best.range();
    }

    private void requireWaySize(Way way) {
        if (way == null || way.getNodesCount() < 2) {
            throw new IllegalArgumentException("Way must contain at least two nodes.");
        }
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

    /** Candidate retained in way order so strict greater-than comparison preserves deterministic ties. */
    private record SegmentCandidate(WaySegmentRange range, double length, boolean repeatedOccurrenceSafe) {
    }
}

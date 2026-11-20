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
 * Branching shared-way nodes bound candidate ranges, but are excluded from returned ranges.
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
     * Finds the longest eligible trimmed segment whose original maximal range contains one unique hint
     * occurrence. An unsafe shared-junction hint qualifies both adjacent original ranges, but the hint is
     * trimmed from either returned range. A safe end-to-end continuation may keep the hint as an endpoint.
     * The longer returned range wins; exact length ties retain the earlier original range in way order.
     *
     * @param way way to inspect
     * @param hintNode uniquely occurring node used to select its containing original range or ranges
     * @return inclusive node-index range of the longest eligible trimmed segment
     * @throws IllegalArgumentException when the hint is absent or occurs more than once
     * @throws IllegalStateException when no eligible trimmed range is associated with an original
     *     range containing the hint
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
        return chooseLongest(candidates(way), originalRange -> originalRange.startIndex() <= hintIndex
                && hintIndex <= originalRange.endIndex(),
            "No slideable non-branching segment containing the selected node is available.");
    }

    private List<SegmentCandidate> candidates(Way way) {
        requireWaySize(way);
        SelectionIntegrity.NodeOccurrenceIndex occurrenceIndex = SelectionIntegrity.occurrenceIndex(way);
        List<SegmentCandidate> candidates = new ArrayList<>();
        for (var topology : JunctionTopologyRules.ranges(0,way.getNodesCount()-1,
                index->way.getNode(index).referrers(Way.class).anyMatch(referrer->!referrer.isDeleted()&&referrer!=way),
                index->safeEndpoint(way,index))) {
                int originalStart=topology.originalFirst(),originalEnd=topology.originalLast();
                int start=topology.first(),end=topology.last();
                if (end > start) {
                    WaySegmentRange originalRange = new WaySegmentRange(originalStart, originalEnd);
                    WaySegmentRange range = new WaySegmentRange(start, end);
                    candidates.add(new SegmentCandidate(originalRange, range, length(way, start, end),
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
                || !rangePredicate.test(candidate.originalRange())) {
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

    private boolean safeEndpoint(Way selectedWay, int nodeIndex) {
        Node node = selectedWay.getNode(nodeIndex);
        List<Way> liveReferrers = node.referrers(Way.class).filter(referrer -> !referrer.isDeleted()).toList();
        return JunctionTopologyRules.safeEndpoint(liveReferrers.stream().map(referrer->
            new JunctionTopologyRules.Referrer(referrer==selectedWay,
                selectedWay.getDataSet()!=null&&selectedWay.getDataSet()==referrer.getDataSet()
                    &&node.getDataSet()==selectedWay.getDataSet(),
                isCompleteNondegenerate(referrer),occursUniquelyAtEndpoint(referrer,node))).toList());
    }

    private boolean isCompleteNondegenerate(Way way) {
        if (way.isDeleted() || way.isIncomplete() || way.hasIncompleteNodes() || way.getNodesCount() < 2
            || way.getNodes().stream().anyMatch(node -> node.isIncomplete() || !node.isLatLonKnown())) {
            return false;
        }
        EastNorth previous = null;
        boolean nondegenerate = false;
        for (Node node : way.getNodes()) {
            EastNorth current = node.getEastNorth(ProjectionRegistry.getProjection());
            if (current == null || !Double.isFinite(current.getX()) || !Double.isFinite(current.getY())) {
                return false;
            }
            if (previous != null && previous.distance(current) > 0.0) {
                nondegenerate = true;
            }
            previous = current;
        }
        return nondegenerate;
    }

    private boolean occursUniquelyAtEndpoint(Way way, Node node) {
        int occurrences = 0;
        boolean endpoint = false;
        for (int index = 0; index < way.getNodesCount(); index++) {
            if (way.getNode(index) == node) {
                occurrences++;
                endpoint = index == 0 || index == way.getNodesCount() - 1;
            }
        }
        return occurrences == 1 && endpoint;
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
    private record SegmentCandidate(
        WaySegmentRange originalRange,
        WaySegmentRange range,
        double length,
        boolean repeatedOccurrenceSafe
    ) {
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;

/** Exact shared 30/60 metre occurrence bounds for one unambiguous junction arm. */
public final class JunctionAuthorityBounds {
    private static final double INITIAL_ARM_METERS = 30.0;
    private static final double MAXIMUM_ARM_METERS = 60.0;

    private JunctionAuthorityBounds() {
    }

    /**
     * Returns the canonical local occurrence range around one junction occurrence.
     *
     * <p>Each side includes the first node at or beyond 30 metres when it remains within
     * the 60 metre hard bound. Repeated occurrences are ambiguous and fail closed.</p>
     *
     * @param way complete incident way payload
     * @param junction exact shared node identity
     * @param frame captured factual local metric frame
     * @return one canonical local occurrence range
     */
    public static OccurrenceRange localOccurrenceRange(Way way, Node junction,
            LocalMetricFrame frame) {
        if (way == null || junction == null || frame == null || way.getNodesCount() < 2) {
            throw new IllegalArgumentException("Junction authority input is incomplete");
        }
        if (new HashSet<>(way.getNodes()).size() != way.getNodesCount()) {
            throw new IllegalArgumentException(
                    "Reattachment rejects repeated incident-way node occurrences");
        }
        int junctionIndex = -1;
        for (int index = 0; index < way.getNodesCount(); index++) {
            if (way.getNode(index) != junction) {
                continue;
            }
            if (junctionIndex >= 0) {
                throw new IllegalArgumentException(
                        "Reattachment requires one unambiguous incident-way junction occurrence");
            }
            junctionIndex = index;
        }
        if (junctionIndex < 0) {
            throw new IllegalArgumentException("Incident way does not contain the junction");
        }
        int first = junctionIndex;
        double distance = 0.0;
        for (int index = junctionIndex - 1; index >= 0 && distance < INITIAL_ARM_METERS;
                index--) {
            double segment = metric(way.getNode(index), frame)
                    .distanceTo(metric(way.getNode(index + 1), frame));
            if (distance + segment > MAXIMUM_ARM_METERS) {
                break;
            }
            distance += segment;
            first = index;
        }
        int last = junctionIndex;
        distance = 0.0;
        for (int index = junctionIndex + 1;
                index < way.getNodesCount() && distance < INITIAL_ARM_METERS; index++) {
            double segment = metric(way.getNode(index - 1), frame)
                    .distanceTo(metric(way.getNode(index), frame));
            if (distance + segment > MAXIMUM_ARM_METERS) {
                break;
            }
            distance += segment;
            last = index;
        }
        return new OccurrenceRange(first, last);
    }

    /**
     * Returns the sorted union used when multiple local junction components overlap.
     *
     * @param first first complete occurrence-range collection
     * @param second second complete occurrence-range collection
     * @return immutable sorted ranges with touching or overlapping components merged
     */
    public static List<OccurrenceRange> mergeOccurrenceRanges(List<OccurrenceRange> first,
            List<OccurrenceRange> second) {
        if (first == null || second == null || first.stream().anyMatch(java.util.Objects::isNull)
                || second.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Junction occurrence ranges are incomplete");
        }
        List<OccurrenceRange> ordered = java.util.stream.Stream.concat(first.stream(), second.stream())
                .distinct().sorted(Comparator.comparingInt(OccurrenceRange::firstIndex)).toList();
        List<OccurrenceRange> result = new ArrayList<>();
        for (OccurrenceRange range : ordered) {
            if (result.isEmpty()
                    || range.firstIndex() > result.get(result.size() - 1).lastIndex() + 1) {
                result.add(range);
            } else {
                OccurrenceRange previous = result.remove(result.size() - 1);
                result.add(new OccurrenceRange(previous.firstIndex(),
                        Math.max(previous.lastIndex(), range.lastIndex())));
            }
        }
        return List.copyOf(result);
    }

    private static MetricPoint metric(Node node, LocalMetricFrame frame) {
        if (node == null || !node.isLatLonKnown()) {
            throw new IllegalArgumentException("Junction authority node has no coordinate");
        }
        return frame.toMetric(new GeographicPoint(node.lat(), node.lon()));
    }
}

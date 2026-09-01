package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;

/**
 * Finds nearby parallel highway ways for optional read-only corridor assignment context.
 */
public final class ParallelWayContextResolver {
    private static final double MIN_DIRECTION_AGREEMENT = 0.78;
    private static final double MIN_OVERLAP_RATIO = 0.35;
    private static final double MAX_CONTEXT_HALF_WIDTH_METERS = 14.0;
    private static final List<String> RELEVANT_TAGS = List.of("highway", "oneway", "lanes", "foot", "bicycle");

    /**
     * Creates a stateless nearby-way context resolver.
     */
    public ParallelWayContextResolver() {
        // Stateless resolver.
    }

    /**
     * Resolves downloaded parallel ways around the selected segment.
     *
     * @param selection selected way segment
     * @param enabled whether parallel-way awareness is enabled
     * @param searchHalfWidthMeters configured heatmap search corridor
     * @return nearby parallel contexts; never includes the selected way
     */
    public List<ParallelWayContext> resolve(
        SelectionContext selection,
        boolean enabled,
        double searchHalfWidthMeters
    ) {
        DataSet dataSet = selection.way().getDataSet();
        if (!enabled || dataSet == null) {
            return List.of();
        }
        List<EastNorth> selected = geometry(selection.segmentNodes());
        if (selected.size() < 2) {
            return List.of();
        }
        double corridor = Math.max(2.0, Math.min(MAX_CONTEXT_HALF_WIDTH_METERS, searchHalfWidthMeters));
        List<EastNorth> selectedSamples = samplePolyline(selected, Math.max(2.0, corridor * 0.5));
        List<ParallelWayContext> result = new ArrayList<>();
        for (Way way : dataSet.getWays()) {
            if (way == selection.way() || way.isDeleted() || way.get("highway") == null) {
                continue;
            }
            List<EastNorth> geometry = geometry(way.getNodes());
            if (geometry.size() < 2) {
                continue;
            }
            double direction = directionAgreement(selected, geometry);
            if (direction < MIN_DIRECTION_AGREEMENT) {
                continue;
            }
            List<Double> distances = selectedSamples.stream().map(point -> distanceToPolyline(point, geometry)).toList();
            double overlap = (double) distances.stream().filter(distance -> distance <= corridor).count() / distances.size();
            if (overlap < MIN_OVERLAP_RATIO) {
                continue;
            }
            double meanDistance = distances.stream().mapToDouble(Double::doubleValue).average().orElse(Double.POSITIVE_INFINITY);
            result.add(new ParallelWayContext(way.getUniqueId(), geometry, relevantTags(way), meanDistance,
                direction, side(selected, geometry), overlap));
        }
        return result;
    }

    private List<EastNorth> geometry(List<Node> nodes) {
        List<EastNorth> result = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            EastNorth point = node.getEastNorth(ProjectionRegistry.getProjection());
            if (point != null) {
                result.add(point);
            }
        }
        return result;
    }

    private Map<String, String> relevantTags(Way way) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (String key : RELEVANT_TAGS) {
            String value = way.get(key);
            if (value != null) {
                tags.put(key, value);
            }
        }
        return tags;
    }

    private double directionAgreement(List<EastNorth> selected, List<EastNorth> other) {
        EastNorth a = selected.get(0);
        EastNorth b = selected.get(selected.size() - 1);
        EastNorth c = other.get(0);
        EastNorth d = other.get(other.size() - 1);
        double ax = b.east() - a.east();
        double ay = b.north() - a.north();
        double bx = d.east() - c.east();
        double by = d.north() - c.north();
        double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        return denominator <= 1e-9 ? 0.0 : Math.abs((ax * bx + ay * by) / denominator);
    }

    private double side(List<EastNorth> selected, List<EastNorth> other) {
        EastNorth start = selected.get(0);
        EastNorth end = selected.get(selected.size() - 1);
        EastNorth selectedCenter = centroid(selected);
        EastNorth nearbyPoint = nearestPointOnPolyline(selectedCenter, other);
        double cross = (end.east() - start.east()) * (nearbyPoint.north() - selectedCenter.north())
            - (end.north() - start.north()) * (nearbyPoint.east() - selectedCenter.east());
        return Math.signum(cross);
    }

    private List<EastNorth> samplePolyline(List<EastNorth> geometry, double stepMeters) {
        List<EastNorth> samples = new ArrayList<>();
        samples.add(geometry.get(0));
        for (int i = 1; i < geometry.size(); i++) {
            EastNorth start = geometry.get(i - 1);
            EastNorth end = geometry.get(i);
            double length = start.distance(end);
            int intervals = Math.max(1, (int) Math.ceil(length / stepMeters));
            for (int sample = 1; sample <= intervals; sample++) {
                double fraction = (double) sample / intervals;
                samples.add(new EastNorth(
                    start.east() + fraction * (end.east() - start.east()),
                    start.north() + fraction * (end.north() - start.north())
                ));
            }
        }
        return samples;
    }

    private EastNorth centroid(List<EastNorth> geometry) {
        double east = geometry.stream().mapToDouble(EastNorth::east).average().orElse(0.0);
        double north = geometry.stream().mapToDouble(EastNorth::north).average().orElse(0.0);
        return new EastNorth(east, north);
    }

    static double distanceToPolyline(EastNorth point, List<EastNorth> polyline) {
        double minimum = Double.POSITIVE_INFINITY;
        for (int i = 1; i < polyline.size(); i++) {
            minimum = Math.min(minimum, distanceToSegment(point, polyline.get(i - 1), polyline.get(i)));
        }
        return minimum;
    }

    private EastNorth nearestPointOnPolyline(EastNorth point, List<EastNorth> polyline) {
        EastNorth nearest = polyline.get(0);
        double minimum = Double.POSITIVE_INFINITY;
        for (int i = 1; i < polyline.size(); i++) {
            EastNorth candidate = nearestPointOnSegment(point, polyline.get(i - 1), polyline.get(i));
            double distance = point.distance(candidate);
            if (distance < minimum) {
                minimum = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private static double distanceToSegment(EastNorth point, EastNorth start, EastNorth end) {
        return point.distance(nearestPointOnSegment(point, start, end));
    }

    private static EastNorth nearestPointOnSegment(EastNorth point, EastNorth start, EastNorth end) {
        double dx = end.east() - start.east();
        double dy = end.north() - start.north();
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared <= 1e-12 ? 0.0
            : ((point.east() - start.east()) * dx + (point.north() - start.north()) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        return new EastNorth(start.east() + t * dx, start.north() + t * dy);
    }
}

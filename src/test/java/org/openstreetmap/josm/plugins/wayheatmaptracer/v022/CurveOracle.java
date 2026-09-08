package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Independent dense curve and local-excursion metrics for synthetic expected-output checks. */
public final class CurveOracle {
    private CurveOracle() {
        // Utility class.
    }

    /** Symmetric nearest-polyline summary in ground metres. */
    public record DistanceMetrics(double meanMeters, double p95Meters, double maximumMeters) {
    }

    /** Worst candidate deviation from a reference curve. */
    public record LocalDefect(double chainageMeters, double amplitudeMeters) {
    }

    /** Measures both directed dense samples, rather than matching input node indexes. */
    public static DistanceMetrics symmetricDistance(List<V022Point> first, List<V022Point> second,
            double sampleSpacingMeters) {
        List<Double> distances = new ArrayList<>();
        directedDistances(first, second, sampleSpacingMeters, distances);
        directedDistances(second, first, sampleSpacingMeters, distances);
        Collections.sort(distances);
        double mean = distances.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        int p95Index = Math.max(0, (int) Math.ceil(distances.size() * 0.95) - 1);
        return new DistanceMetrics(mean, distances.get(p95Index), distances.get(distances.size() - 1));
    }

    /** Finds the largest candidate departure along candidate chainage. */
    public static LocalDefect largestUnsupportedExcursion(List<V022Point> reference, List<V022Point> candidate,
            double sampleSpacingMeters) {
        List<V022Point> sampledCandidate = samplePolyline(candidate, sampleSpacingMeters);
        double chainage = 0.0;
        double bestDistance = -1.0;
        double bestChainage = 0.0;
        V022Point previous = sampledCandidate.get(0);
        for (V022Point point : sampledCandidate) {
            chainage += point.distanceTo(previous);
            double distance = distanceToPolyline(point, reference);
            if (distance > bestDistance) {
                bestDistance = distance;
                bestChainage = chainage;
            }
            previous = point;
        }
        return new LocalDefect(bestChainage, bestDistance);
    }

    private static void directedDistances(List<V022Point> source, List<V022Point> target, double spacing,
            List<Double> output) {
        for (V022Point point : samplePolyline(source, spacing)) {
            output.add(distanceToPolyline(point, target));
        }
    }

    private static List<V022Point> samplePolyline(List<V022Point> polyline, double spacing) {
        if (polyline.size() < 2 || spacing <= 0.0) {
            throw new IllegalArgumentException("A polyline with two points and a positive sample spacing is required");
        }
        List<V022Point> sampled = new ArrayList<>();
        sampled.add(polyline.get(0));
        for (int index = 1; index < polyline.size(); index++) {
            V022Point start = polyline.get(index - 1);
            V022Point end = polyline.get(index);
            int pieces = Math.max(1, (int) Math.ceil(start.distanceTo(end) / spacing));
            for (int piece = 1; piece <= pieces; piece++) {
                sampled.add(start.interpolate(end, piece / (double) pieces));
            }
        }
        return sampled;
    }

    private static double distanceToPolyline(V022Point point, List<V022Point> polyline) {
        double best = Double.POSITIVE_INFINITY;
        for (int index = 1; index < polyline.size(); index++) {
            best = Math.min(best, AnalyticCurve.distanceToSegment(point, polyline.get(index - 1), polyline.get(index)));
        }
        return best;
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;

public final class PolylineMath {
    private PolylineMath() {
    }

    public static List<EastNorth> resampleBySpacing(List<EastNorth> polyline, double spacing) {
        if (polyline.size() < 2) {
            return polyline;
        }
        double total = length(polyline);
        int count = Math.max(2, (int) Math.round(total / Math.max(1.0, spacing)) + 1);
        return resampleByCount(polyline, count);
    }

    public static List<EastNorth> resampleByCount(List<EastNorth> polyline, int count) {
        if (polyline.isEmpty()) {
            return List.of();
        }
        if (polyline.size() == 1 || count <= 1) {
            return List.of(polyline.get(0));
        }

        double total = length(polyline);
        if (total == 0.0) {
            return new ArrayList<>(polyline);
        }

        List<EastNorth> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double target = total * i / (count - 1.0);
            result.add(interpolate(polyline, target));
        }
        return result;
    }

    public static Point2D.Double normalize(double x, double y) {
        double norm = Math.hypot(x, y);
        if (norm == 0.0) {
            return new Point2D.Double(1.0, 0.0);
        }
        return new Point2D.Double(x / norm, y / norm);
    }

    public static List<Double> fractionsForSegment(List<EastNorth> polyline) {
        if (polyline.isEmpty()) {
            return List.of();
        }
        List<Double> fractions = new ArrayList<>(polyline.size());
        double total = 0.0;
        double[] cumulative = new double[polyline.size()];
        cumulative[0] = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            total += polyline.get(i - 1).distance(polyline.get(i));
            cumulative[i] = total;
        }
        if (total == 0.0) {
            for (int i = 0; i < polyline.size(); i++) {
                fractions.add(i == polyline.size() - 1 ? 1.0 : 0.0);
            }
            return fractions;
        }
        for (double value : cumulative) {
            fractions.add(value / total);
        }
        return fractions;
    }

    public static EastNorth interpolateAtFraction(List<EastNorth> polyline, List<Double> fractions, double targetFraction) {
        if (targetFraction <= 0.0) {
            return polyline.get(0);
        }
        if (targetFraction >= 1.0) {
            return polyline.get(polyline.size() - 1);
        }
        for (int i = 1; i < polyline.size(); i++) {
            double left = fractions.get(i - 1);
            double right = fractions.get(i);
            if (targetFraction <= right) {
                double span = right - left;
                double t = span == 0.0 ? 0.0 : (targetFraction - left) / span;
                EastNorth start = polyline.get(i - 1);
                EastNorth end = polyline.get(i);
                return new EastNorth(
                    start.east() + (end.east() - start.east()) * t,
                    start.north() + (end.north() - start.north()) * t
                );
            }
        }
        return polyline.get(polyline.size() - 1);
    }

    public static ProjectionOnPolyline closestPointNearFraction(
        List<EastNorth> polyline,
        List<Double> fractions,
        EastNorth source,
        double sourceFraction,
        double window
    ) {
        double minFraction = Math.max(0.0, sourceFraction - window);
        double maxFraction = Math.min(1.0, sourceFraction + window);
        ProjectionOnPolyline best = null;
        for (int i = 1; i < polyline.size(); i++) {
            double leftFraction = fractions.get(i - 1);
            double rightFraction = fractions.get(i);
            if (rightFraction < minFraction || leftFraction > maxFraction) {
                continue;
            }
            ProjectionOnPolyline projected = projectToSegment(polyline.get(i - 1), polyline.get(i), leftFraction, rightFraction, source);
            if (projected.fraction() < minFraction || projected.fraction() > maxFraction) {
                continue;
            }
            if (best == null || projected.distance() < best.distance()) {
                best = projected;
            }
        }
        if (best != null) {
            return best;
        }
        EastNorth fallback = interpolateAtFraction(polyline, fractions, sourceFraction);
        return new ProjectionOnPolyline(fallback, sourceFraction, fallback.distance(source));
    }

    private static ProjectionOnPolyline projectToSegment(
        EastNorth start,
        EastNorth end,
        double leftFraction,
        double rightFraction,
        EastNorth source
    ) {
        double dx = end.east() - start.east();
        double dy = end.north() - start.north();
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0.0
            ? 0.0
            : ((source.east() - start.east()) * dx + (source.north() - start.north()) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        EastNorth point = new EastNorth(start.east() + dx * t, start.north() + dy * t);
        double fraction = leftFraction + (rightFraction - leftFraction) * t;
        return new ProjectionOnPolyline(point, fraction, point.distance(source));
    }

    private static EastNorth interpolate(List<EastNorth> polyline, double targetDistance) {
        double walked = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            EastNorth start = polyline.get(i - 1);
            EastNorth end = polyline.get(i);
            double segment = start.distance(end);
            if (walked + segment >= targetDistance) {
                double factor = segment == 0.0 ? 0.0 : (targetDistance - walked) / segment;
                return new EastNorth(
                    start.east() + (end.east() - start.east()) * factor,
                    start.north() + (end.north() - start.north()) * factor
                );
            }
            walked += segment;
        }
        return polyline.get(polyline.size() - 1);
    }

    public static double length(List<EastNorth> polyline) {
        double total = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            total += polyline.get(i - 1).distance(polyline.get(i));
        }
        return total;
    }

    public record ProjectionOnPolyline(EastNorth point, double fraction, double distance) {
    }
}

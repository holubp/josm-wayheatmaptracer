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

    private static double length(List<EastNorth> polyline) {
        double total = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            total += polyline.get(i - 1).distance(polyline.get(i));
        }
        return total;
    }
}


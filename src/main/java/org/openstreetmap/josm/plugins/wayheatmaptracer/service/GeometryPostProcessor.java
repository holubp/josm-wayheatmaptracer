package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;

public final class GeometryPostProcessor {
    private static final double CORNER_KEEP_DEGREES = 55.0;

    public List<EastNorth> simplify(List<EastNorth> polyline, double tolerance) {
        if (polyline.size() <= 2 || tolerance <= 0.0) {
            return polyline;
        }

        boolean[] keep = new boolean[polyline.size()];
        keep[0] = true;
        keep[polyline.size() - 1] = true;
        markSharpCorners(polyline, keep);

        int anchor = 0;
        while (anchor < polyline.size() - 1) {
            int nextAnchor = anchor + 1;
            while (nextAnchor < polyline.size() - 1 && !keep[nextAnchor]) {
                nextAnchor++;
            }
            simplifyRange(polyline, anchor, nextAnchor, tolerance, keep);
            anchor = nextAnchor;
        }

        List<EastNorth> result = new ArrayList<>();
        for (int i = 0; i < polyline.size(); i++) {
            if (keep[i]) {
                result.add(polyline.get(i));
            }
        }
        return result;
    }

    private void markSharpCorners(List<EastNorth> polyline, boolean[] keep) {
        for (int i = 1; i < polyline.size() - 1; i++) {
            EastNorth prev = polyline.get(i - 1);
            EastNorth current = polyline.get(i);
            EastNorth next = polyline.get(i + 1);
            if (turningAngleDegrees(prev, current, next) >= CORNER_KEEP_DEGREES) {
                keep[i] = true;
            }
        }
    }

    private void simplifyRange(List<EastNorth> polyline, int start, int end, double tolerance, boolean[] keep) {
        if (end <= start + 1) {
            return;
        }

        double maxDistance = -1.0;
        int maxIndex = -1;
        for (int i = start + 1; i < end; i++) {
            if (keep[i]) {
                continue;
            }
            double distance = perpendicularDistance(polyline.get(i), polyline.get(start), polyline.get(end));
            if (distance > maxDistance) {
                maxDistance = distance;
                maxIndex = i;
            }
        }

        if (maxIndex >= 0 && maxDistance > tolerance) {
            keep[maxIndex] = true;
            simplifyRange(polyline, start, maxIndex, tolerance, keep);
            simplifyRange(polyline, maxIndex, end, tolerance, keep);
        }
    }

    private double turningAngleDegrees(EastNorth prev, EastNorth current, EastNorth next) {
        double ax = current.east() - prev.east();
        double ay = current.north() - prev.north();
        double bx = next.east() - current.east();
        double by = next.north() - current.north();

        double aNorm = Math.hypot(ax, ay);
        double bNorm = Math.hypot(bx, by);
        if (aNorm == 0.0 || bNorm == 0.0) {
            return 0.0;
        }
        double cosine = (ax * bx + ay * by) / (aNorm * bNorm);
        cosine = Math.max(-1.0, Math.min(1.0, cosine));
        return Math.toDegrees(Math.acos(cosine));
    }

    private double perpendicularDistance(EastNorth point, EastNorth start, EastNorth end) {
        double dx = end.east() - start.east();
        double dy = end.north() - start.north();
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0.0) {
            return point.distance(start);
        }

        double t = ((point.east() - start.east()) * dx + (point.north() - start.north()) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        double projectionEast = start.east() + t * dx;
        double projectionNorth = start.north() + t * dy;
        return Math.hypot(point.east() - projectionEast, point.north() - projectionNorth);
    }
}

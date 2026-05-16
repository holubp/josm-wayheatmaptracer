package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * Simplifies projected preview geometry while preserving sharp bends as anchors.
 */
public final class GeometryPostProcessor {
    private static final double CORNER_KEEP_DEGREES = 55.0;

    /**
     * Simplifies a polyline with a Douglas-Peucker pass that keeps sharp corners.
     *
     * @param polyline source geometry in projected coordinates
     * @param tolerance maximum perpendicular deviation to remove a point
     * @return simplified polyline, or the original list when no simplification is needed
     */
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

    /**
     * Removes short loops created by non-adjacent segment self-intersections.
     *
     * @param polyline source geometry in projected coordinates
     * @param protectedPoints points that must not be deleted, typically fixed anchors and junctions
     * @return geometry with removable loop interiors collapsed
     */
    public List<EastNorth> removeSelfIntersectionLoops(List<EastNorth> polyline, List<EastNorth> protectedPoints) {
        List<EastNorth> result = new ArrayList<>(polyline);
        boolean changed = true;
        int passes = 0;
        while (changed && passes++ < 12 && result.size() >= 4) {
            changed = false;
            for (int i = 0; i < result.size() - 3 && !changed; i++) {
                for (int j = i + 2; j < result.size() - 1; j++) {
                    if (i == 0 && j == result.size() - 2) {
                        continue;
                    }
                    if (!segmentsIntersect(result.get(i), result.get(i + 1), result.get(j), result.get(j + 1))) {
                        continue;
                    }
                    if (containsProtectedPoint(result, i + 1, j, protectedPoints)) {
                        continue;
                    }
                    result.subList(i + 1, j + 1).clear();
                    changed = true;
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Removes tiny endpoint clusters that backtrack or create unsupported sharp turns into anchors.
     *
     * @param polyline source geometry in projected coordinates
     * @param nearDistance maximum distance from an endpoint anchor for pruning
     * @param turnThresholdDegrees minimum turn angle considered an unsupported kink
     * @return geometry with unstable endpoint-adjacent points removed
     */
    public List<EastNorth> pruneEndpointClusters(List<EastNorth> polyline, double nearDistance, double turnThresholdDegrees) {
        if (polyline.size() < 4 || nearDistance <= 0.0) {
            return polyline;
        }
        List<EastNorth> result = new ArrayList<>(polyline);
        while (result.size() >= 4 && shouldPruneStart(result, nearDistance, turnThresholdDegrees)) {
            result.remove(1);
        }
        while (result.size() >= 4 && shouldPruneEnd(result, nearDistance, turnThresholdDegrees)) {
            result.remove(result.size() - 2);
        }
        return result;
    }

    private boolean shouldPruneStart(List<EastNorth> points, double nearDistance, double turnThresholdDegrees) {
        EastNorth anchor = points.get(0);
        EastNorth first = points.get(1);
        EastNorth second = points.get(2);
        if (first.distance(anchor) > nearDistance) {
            return false;
        }
        return second.distance(anchor) <= first.distance(anchor) * 0.92
            || turningAngleDegrees(anchor, first, second) >= turnThresholdDegrees;
    }

    private boolean shouldPruneEnd(List<EastNorth> points, double nearDistance, double turnThresholdDegrees) {
        EastNorth anchor = points.get(points.size() - 1);
        EastNorth first = points.get(points.size() - 2);
        EastNorth second = points.get(points.size() - 3);
        if (first.distance(anchor) > nearDistance) {
            return false;
        }
        return second.distance(anchor) <= first.distance(anchor) * 0.92
            || turningAngleDegrees(second, first, anchor) >= turnThresholdDegrees;
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

    private boolean containsProtectedPoint(List<EastNorth> points, int startInclusive, int endInclusive, List<EastNorth> protectedPoints) {
        for (int i = startInclusive; i <= endInclusive; i++) {
            for (EastNorth protectedPoint : protectedPoints) {
                if (points.get(i).distance(protectedPoint) < 0.01) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean segmentsIntersect(EastNorth a, EastNorth b, EastNorth c, EastNorth d) {
        double denominator = (b.east() - a.east()) * (d.north() - c.north())
            - (b.north() - a.north()) * (d.east() - c.east());
        if (Math.abs(denominator) < 1e-9) {
            return false;
        }
        double ua = ((d.east() - c.east()) * (a.north() - c.north())
            - (d.north() - c.north()) * (a.east() - c.east())) / denominator;
        double ub = ((b.east() - a.east()) * (a.north() - c.north())
            - (b.north() - a.north()) * (a.east() - c.east())) / denominator;
        return ua > 1e-9 && ua < 1.0 - 1e-9 && ub > 1e-9 && ub < 1.0 - 1e-9;
    }
}

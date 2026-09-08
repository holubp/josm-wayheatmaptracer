package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import java.util.ArrayList;
import java.util.List;

/** Piecewise-linear analytic fixture branch with exact segment distance. */
public final class PolylineCurve implements AnalyticCurve {
    private final List<V022Point> vertices;
    private final double[] cumulativeLengths;

    /** Creates a curve from two or more vertices. */
    public PolylineCurve(List<V022Point> vertices) {
        if (vertices.size() < 2) {
            throw new IllegalArgumentException("A polyline requires at least two vertices");
        }
        this.vertices = List.copyOf(vertices);
        this.cumulativeLengths = new double[vertices.size()];
        for (int index = 1; index < vertices.size(); index++) {
            cumulativeLengths[index] = cumulativeLengths[index - 1]
                    + vertices.get(index - 1).distanceTo(vertices.get(index));
        }
    }

    /** Returns immutable original vertices. */
    public List<V022Point> vertices() {
        return vertices;
    }

    @Override
    public V022Point pointAtFraction(double fraction) {
        double target = Math.max(0.0, Math.min(1.0, fraction)) * lengthMeters();
        for (int index = 1; index < cumulativeLengths.length; index++) {
            if (target <= cumulativeLengths[index] || index == cumulativeLengths.length - 1) {
                double segmentLength = cumulativeLengths[index] - cumulativeLengths[index - 1];
                double local = segmentLength == 0.0 ? 0.0 : (target - cumulativeLengths[index - 1]) / segmentLength;
                return vertices.get(index - 1).interpolate(vertices.get(index), local);
            }
        }
        return vertices.get(vertices.size() - 1);
    }

    @Override
    public double lengthMeters() {
        return cumulativeLengths[cumulativeLengths.length - 1];
    }

    @Override
    public double distanceTo(V022Point point) {
        double best = Double.POSITIVE_INFINITY;
        for (int index = 1; index < vertices.size(); index++) {
            best = Math.min(best, AnalyticCurve.distanceToSegment(point, vertices.get(index - 1), vertices.get(index)));
        }
        return best;
    }

    /** Returns a translated curve without changing the source vertices. */
    public PolylineCurve translated(double dx, double dy) {
        List<V022Point> translated = new ArrayList<>(vertices.size());
        for (V022Point vertex : vertices) {
            translated.add(vertex.plus(dx, dy));
        }
        return new PolylineCurve(translated);
    }
}

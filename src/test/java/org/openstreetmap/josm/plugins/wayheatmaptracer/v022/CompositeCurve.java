package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import java.util.List;

/** Ordered analytic curve composition used for paths with a real arc between straight approaches. */
public final class CompositeCurve implements AnalyticCurve {
    private final List<AnalyticCurve> parts;
    private final double[] cumulativeLengths;

    /** Creates a nonempty composition of connected fixture curves. */
    public CompositeCurve(List<AnalyticCurve> parts) {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("A composite curve requires at least one part");
        }
        this.parts = List.copyOf(parts);
        this.cumulativeLengths = new double[parts.size()];
        for (int index = 0; index < parts.size(); index++) {
            cumulativeLengths[index] = parts.get(index).lengthMeters() + (index == 0 ? 0.0 : cumulativeLengths[index - 1]);
        }
    }

    @Override
    public V022Point pointAtFraction(double fraction) {
        double target = Math.max(0.0, Math.min(1.0, fraction)) * lengthMeters();
        for (int index = 0; index < parts.size(); index++) {
            if (target <= cumulativeLengths[index] || index == parts.size() - 1) {
                double start = index == 0 ? 0.0 : cumulativeLengths[index - 1];
                double partLength = cumulativeLengths[index] - start;
                return parts.get(index).pointAtFraction(partLength == 0.0 ? 0.0 : (target - start) / partLength);
            }
        }
        return parts.get(parts.size() - 1).pointAtFraction(1.0);
    }

    @Override
    public double lengthMeters() {
        return cumulativeLengths[cumulativeLengths.length - 1];
    }

    @Override
    public double distanceTo(V022Point point) {
        return parts.stream().mapToDouble(part -> part.distanceTo(point)).min().orElseThrow();
    }
}

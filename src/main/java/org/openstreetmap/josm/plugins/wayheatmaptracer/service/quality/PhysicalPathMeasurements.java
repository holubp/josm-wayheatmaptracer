package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

/** Physical length and defined interior turns for a path in metric coordinates. */
public final class PhysicalPathMeasurements {
    private PhysicalPathMeasurements() { }

    /**
     * Measures length and turns, omitting vertices adjacent to zero-length segments.
     * @param finalPoints ordered path points in metres
     * @return immutable physical measurements
     * @throws IllegalArgumentException if the path is null, too short, or contains invalid geometry
     */
    public static Result measure(List<MetricPoint> finalPoints) {
        if (finalPoints == null || finalPoints.size() < 2) {
            throw new IllegalArgumentException("A path requires at least two metric points");
        }
        List<MetricPoint> points = new ArrayList<>(finalPoints.size());
        for (MetricPoint point : finalPoints) {
            if (point == null || !Double.isFinite(point.xMeters()) || !Double.isFinite(point.yMeters())) {
                throw new IllegalArgumentException("Path points must have finite metric coordinates");
            }
            points.add(point);
        }

        double length = 0.0;
        int zeroLengthSegments = 0;
        double[] segmentLengths = new double[points.size() - 1];
        double[] unitX = new double[segmentLengths.length];
        double[] unitY = new double[segmentLengths.length];
        for (int i = 0; i < segmentLengths.length; i++) {
            MetricPoint start = points.get(i);
            MetricPoint end = points.get(i + 1);
            double dx = end.xMeters() - start.xMeters();
            double dy = end.yMeters() - start.yMeters();
            if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
                throw new IllegalArgumentException("Metric segment differences must be finite");
            }
            double segmentLength = StrictMath.hypot(dx, dy);
            if (!Double.isFinite(segmentLength)) {
                throw new IllegalArgumentException("Metric segment length must be finite");
            }
            segmentLengths[i] = segmentLength;
            length += segmentLength;
            if (!Double.isFinite(length)) {
                throw new IllegalArgumentException("Total metric path length must be finite");
            }
            if (segmentLength == 0.0) {
                zeroLengthSegments++;
            } else {
                unitX[i] = dx / segmentLength;
                unitY[i] = dy / segmentLength;
            }
        }

        List<Double> turns = new ArrayList<>(Math.max(0, points.size() - 2));
        for (int i = 1; i < points.size() - 1; i++) {
            if (segmentLengths[i - 1] == 0.0 || segmentLengths[i] == 0.0) {
                continue;
            }
            double incomingX = unitX[i - 1];
            double incomingY = unitY[i - 1];
            double outgoingX = unitX[i];
            double outgoingY = unitY[i];
            double cross = incomingX * outgoingY - incomingY * outgoingX;
            double dot = incomingX * outgoingX + incomingY * outgoingY;
            turns.add(StrictMath.toDegrees(StrictMath.atan2(StrictMath.abs(cross), dot)));
        }
        return new Result(turns, length, zeroLengthSegments);
    }

    /** Immutable measured values for a physical path. */
    public record Result(List<Double> vertexTurnsDegrees, double lengthMeters, int zeroLengthSegmentCount) {
        public Result {
            Objects.requireNonNull(vertexTurnsDegrees, "vertexTurnsDegrees");
            vertexTurnsDegrees = List.copyOf(vertexTurnsDegrees);
            if (!Double.isFinite(lengthMeters) || lengthMeters < 0.0 || zeroLengthSegmentCount < 0) {
                throw new IllegalArgumentException("Physical path measurements must be finite and nonnegative");
            }
            for (Double turn : vertexTurnsDegrees) {
                if (turn == null || !Double.isFinite(turn) || turn < 0.0 || turn > 180.0) {
                    throw new IllegalArgumentException("Vertex turns must be finite degrees from 0 to 180");
                }
            }
        }
    }
}

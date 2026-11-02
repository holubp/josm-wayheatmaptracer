package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

/** Physical measurements for a final metric-coordinate path. */
public final class PhysicalPathMeasurements {
    private PhysicalPathMeasurements() { }

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
        for (int i = 0; i < segmentLengths.length; i++) {
            MetricPoint start = points.get(i);
            MetricPoint end = points.get(i + 1);
            double segmentLength = StrictMath.hypot(end.xMeters() - start.xMeters(), end.yMeters() - start.yMeters());
            segmentLengths[i] = segmentLength;
            length += segmentLength;
            if (segmentLength == 0.0) {
                zeroLengthSegments++;
            }
        }

        List<Double> turns = new ArrayList<>(Math.max(0, points.size() - 2));
        for (int i = 1; i < points.size() - 1; i++) {
            if (segmentLengths[i - 1] == 0.0 || segmentLengths[i] == 0.0) {
                continue;
            }
            MetricPoint previous = points.get(i - 1);
            MetricPoint current = points.get(i);
            MetricPoint next = points.get(i + 1);
            double incomingX = current.xMeters() - previous.xMeters();
            double incomingY = current.yMeters() - previous.yMeters();
            double outgoingX = next.xMeters() - current.xMeters();
            double outgoingY = next.yMeters() - current.yMeters();
            double cosine = (incomingX * outgoingX + incomingY * outgoingY)
                    / (segmentLengths[i - 1] * segmentLengths[i]);
            double boundedCosine = Math.max(-1.0, Math.min(1.0, cosine));
            turns.add(StrictMath.toDegrees(StrictMath.acos(boundedCosine)));
        }
        return immutableResult(turns, length, zeroLengthSegments);
    }

    public static Result immutableResult(List<Double> vertexTurnsDegrees, double lengthMeters,
            int zeroLengthSegmentCount) {
        return new Result(vertexTurnsDegrees, lengthMeters, zeroLengthSegmentCount);
    }

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

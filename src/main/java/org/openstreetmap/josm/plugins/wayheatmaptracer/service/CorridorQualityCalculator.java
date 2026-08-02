package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorQuality;

/**
 * Calculates unweighted source-resolution and geometric quality for one optimized corridor candidate.
 */
public final class CorridorQualityCalculator {
    private static final int HIGH_FREQUENCY_WINDOW = 7;

    /** Creates a stateless quality calculator. */
    public CorridorQualityCalculator() {
        // Stateless calculator.
    }

    /**
     * Calculates physical quality from one complete optimizer result.
     *
     * @param track selected corridor identity
     * @param profiles profile-aligned fine evidence
     * @param tube robust longitudinal reference
     * @param offsetsPx optimized lateral offsets in sampled-raster pixels
     * @param points optimized sampled-raster geometry
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param approaches endpoint approach evidence
     * @return unweighted quality metrics
     */
    public CorridorQuality calculate(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        LongitudinalCorridorTube tube,
        List<Double> offsetsPx,
        List<Point2D.Double> points,
        double sourcePixelSizePx,
        EndpointApproachModel approaches
    ) {
        if (offsetsPx.isEmpty()) {
            return CorridorQuality.empty();
        }
        double sourcePixel = Double.isFinite(sourcePixelSizePx) && sourcePixelSizePx > 0.0
            ? sourcePixelSizePx : 1.0;
        List<Double> residuals = new ArrayList<>(offsetsPx.size());
        for (int index = 0; index < offsetsPx.size(); index++) {
            residuals.add((offsetsPx.get(index) - tube.at(index).centerOffsetPx()) / sourcePixel);
        }
        List<Double> highFrequency = highFrequencyResiduals(offsetsPx).stream()
            .map(value -> value / sourcePixel).toList();
        List<Double> deltas = differences(offsetsPx).stream().map(value -> value / sourcePixel).toList();
        List<Double> accelerations = differences(deltas);
        List<Double> turns = turnsDegrees(points);
        List<Double> curvatureChanges = differences(turns);
        int forwardViolations = forwardProgressViolations(profiles, points);
        int excursions = unsupportedExcursions(residuals);
        double maximumGap = maximumGapMeters(track, tube);
        double endpointTurn = endpointMaximumTurn(turns, approaches);
        double persistence = 1.0 / (1.0 + percentileAbs(residuals, 0.95)
            + percentileAbs(highFrequency, 0.95) + 0.5 * excursions + forwardViolations);
        boolean approachesSupported = approaches.approaches().isEmpty()
            || approaches.approaches().stream().allMatch(EndpointApproachModel.EndpointApproach::supported);
        return new CorridorQuality(
            residuals.stream().mapToDouble(Math::abs).average().orElse(0.0),
            percentileAbs(residuals, 0.95),
            rms(highFrequency),
            percentileAbs(highFrequency, 0.95),
            percentileAbs(deltas, 0.95),
            percentileAbs(accelerations, 0.95),
            percentileAbs(turns, 0.95),
            turns.stream().mapToDouble(Math::abs).max().orElse(0.0),
            percentileAbs(curvatureChanges, 0.95),
            forwardViolations,
            excursions,
            maximumGap,
            endpointTurn,
            persistence,
            approachesSupported
        );
    }

    private List<Double> highFrequencyResiduals(List<Double> values) {
        int radius = HIGH_FREQUENCY_WINDOW / 2;
        List<Double> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            int start = Math.max(0, index - radius);
            int end = Math.min(values.size() - 1, index + radius);
            double mean = values.subList(start, end + 1).stream().mapToDouble(Double::doubleValue)
                .average().orElse(values.get(index));
            result.add(values.get(index) - mean);
        }
        return result;
    }

    private List<Double> differences(List<Double> values) {
        List<Double> result = new ArrayList<>();
        for (int index = 1; index < values.size(); index++) {
            result.add(values.get(index) - values.get(index - 1));
        }
        return result;
    }

    private List<Double> turnsDegrees(List<Point2D.Double> points) {
        List<Double> result = new ArrayList<>();
        for (int index = 1; index + 1 < points.size(); index++) {
            double before = Math.atan2(points.get(index).y - points.get(index - 1).y,
                points.get(index).x - points.get(index - 1).x);
            double after = Math.atan2(points.get(index + 1).y - points.get(index).y,
                points.get(index + 1).x - points.get(index).x);
            result.add(Math.toDegrees(angleDifference(after, before)));
        }
        return result;
    }

    private int forwardProgressViolations(List<CorridorProfile> profiles, List<Point2D.Double> points) {
        int violations = 0;
        for (int index = 1; index < points.size(); index++) {
            Point2D.Double sourceBefore = profiles.get(index - 1).source().anchorScreen();
            Point2D.Double sourceAfter = profiles.get(index).source().anchorScreen();
            double sourceX = sourceAfter.x - sourceBefore.x;
            double sourceY = sourceAfter.y - sourceBefore.y;
            double candidateX = points.get(index).x - points.get(index - 1).x;
            double candidateY = points.get(index).y - points.get(index - 1).y;
            if (sourceX * candidateX + sourceY * candidateY <= 0.0) {
                violations++;
            }
        }
        return violations;
    }

    private int unsupportedExcursions(List<Double> tubeResiduals) {
        int count = 0;
        for (int index = 1; index + 1 < tubeResiduals.size(); index++) {
            double localBaseline = (tubeResiduals.get(index - 1) + tubeResiduals.get(index + 1)) / 2.0;
            if (Math.abs(tubeResiduals.get(index) - localBaseline) > 1.5) {
                count++;
            }
        }
        return count;
    }

    private double maximumGapMeters(CorridorTrack track, LongitudinalCorridorTube tube) {
        double maximum = 0.0;
        int gapStart = -1;
        for (int index = 0; index < tube.slices().size(); index++) {
            if (!track.points().containsKey(index)) {
                if (gapStart < 0) {
                    gapStart = Math.max(0, index - 1);
                }
            } else if (gapStart >= 0) {
                maximum = Math.max(maximum, tube.at(index).distanceMeters() - tube.at(gapStart).distanceMeters());
                gapStart = -1;
            }
        }
        if (gapStart >= 0) {
            maximum = Math.max(maximum, tube.at(tube.slices().size() - 1).distanceMeters()
                - tube.at(gapStart).distanceMeters());
        }
        return maximum;
    }

    private double endpointMaximumTurn(List<Double> turns, EndpointApproachModel approaches) {
        double maximum = 0.0;
        for (EndpointApproachModel.EndpointApproach approach : approaches.approaches()) {
            if (!approach.supported()) {
                continue;
            }
            int minimum = Math.min(approach.constraintProfileIndex(), approach.interiorAnchorProfileIndex());
            int maximumIndex = Math.max(approach.constraintProfileIndex(), approach.interiorAnchorProfileIndex());
            for (int profileIndex = Math.max(1, minimum); profileIndex < maximumIndex; profileIndex++) {
                int turnIndex = profileIndex - 1;
                if (turnIndex >= 0 && turnIndex < turns.size()) {
                    maximum = Math.max(maximum, Math.abs(turns.get(turnIndex)));
                }
            }
        }
        return maximum;
    }

    private double percentileAbs(List<Double> values, double percentile) {
        List<Double> sorted = values.stream().map(Math::abs).sorted(Comparator.naturalOrder()).toList();
        if (sorted.isEmpty()) {
            return 0.0;
        }
        return sorted.get((int) Math.floor(percentile * (sorted.size() - 1)));
    }

    private double rms(List<Double> values) {
        return Math.sqrt(values.stream().mapToDouble(value -> value * value).average().orElse(0.0));
    }

    private double angleDifference(double angle, double reference) {
        double difference = angle - reference;
        while (difference > Math.PI) {
            difference -= 2.0 * Math.PI;
        }
        while (difference < -Math.PI) {
            difference += 2.0 * Math.PI;
        }
        return difference;
    }
}

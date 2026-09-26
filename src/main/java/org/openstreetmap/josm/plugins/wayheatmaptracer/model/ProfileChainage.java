package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.ArrayList;
import java.util.List;

/** Explicit local measured chainage and its factual origin on the captured full source. */
public record ProfileChainage(List<Double> cumulativeGroundMeters, double configuredStepMeters,
        double sourceOriginGroundMeters) {
    /** Copies and validates a finite monotonic sequence. */
    public ProfileChainage {
        cumulativeGroundMeters = List.copyOf(cumulativeGroundMeters);
        if (cumulativeGroundMeters.isEmpty() || cumulativeGroundMeters.get(0) != 0.0
            || !Double.isFinite(configuredStepMeters) || configuredStepMeters <= 0.0
            || !Double.isFinite(sourceOriginGroundMeters) || sourceOriginGroundMeters < 0.0) {
            throw new IllegalArgumentException("Profile chainage is incomplete");
        }
        double previous = -1.0;
        for (double distance : cumulativeGroundMeters) {
            if (!Double.isFinite(distance) || distance <= previous && previous >= 0.0) {
                throw new IllegalArgumentException("Profile chainage must increase monotonically");
            }
            previous = distance;
        }
    }

    /** Existing full-source requests start their measured chainage at source zero. */
    public ProfileChainage(List<Double> cumulativeGroundMeters, double configuredStepMeters) {
        this(cumulativeGroundMeters, configuredStepMeters, 0.0);
    }

    /** Retains local-zero sampling distances with their absolute captured-source origin. */
    public ProfileChainage withSourceOrigin(double sourceOriginGroundMeters) {
        return new ProfileChainage(cumulativeGroundMeters, configuredStepMeters,
                sourceOriginGroundMeters);
    }

    /** Measures cumulative arclength from immutable anchors. */
    public static ProfileChainage measured(List<MetricPoint> anchors, double configuredStepMeters) {
        if (anchors == null || anchors.isEmpty()) {
            throw new IllegalArgumentException("At least one profile anchor is required");
        }
        List<Double> distances = new ArrayList<>(anchors.size());
        double distance = 0.0;
        distances.add(distance);
        for (int index = 1; index < anchors.size(); index++) {
            distance += anchors.get(index - 1).distanceTo(anchors.get(index));
            distances.add(distance);
        }
        return new ProfileChainage(distances, configuredStepMeters);
    }
}

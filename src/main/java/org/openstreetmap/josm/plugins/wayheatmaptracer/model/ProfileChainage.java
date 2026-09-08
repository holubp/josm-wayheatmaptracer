package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.ArrayList;
import java.util.List;

/** Explicit measured profile chainage, kept distinct from the configured sampling step. */
public record ProfileChainage(List<Double> cumulativeGroundMeters, double configuredStepMeters) {
    /** Copies and validates a finite monotonic sequence. */
    public ProfileChainage {
        cumulativeGroundMeters = List.copyOf(cumulativeGroundMeters);
        if (cumulativeGroundMeters.isEmpty() || cumulativeGroundMeters.get(0) != 0.0
            || !Double.isFinite(configuredStepMeters) || configuredStepMeters <= 0.0) {
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

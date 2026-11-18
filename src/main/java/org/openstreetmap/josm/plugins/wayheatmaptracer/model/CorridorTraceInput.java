package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.Collections;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier;

/** Frozen profile locations and physical lateral sampling step for corridor-aware Engine A. */
public record CorridorTraceInput(
    List<DetachedProfileSamplingLocation> profileLocations,
    double lateralStepMeters,
    ExecutionPolicy executionPolicy,
    Set<Integer> additionalFixedOccurrences
) {
    /** Versioned frozen A behavior; historical inputs never opt in implicitly. */
    public enum ExecutionPolicy { HISTORICAL_V1, R3_OWNED_BOUNDED_BRIDGE_V1 }

    /** Preserves historical constructors, behavior and wire encoding. */
    public CorridorTraceInput(List<DetachedProfileSamplingLocation> profileLocations, double lateralStepMeters) {
        this(profileLocations, lateralStepMeters, ExecutionPolicy.HISTORICAL_V1, Set.of());
    }

    /** Copies and validates detached sampling input without reconstructing locations from chainage. */
    public CorridorTraceInput {
        if (profileLocations == null || profileLocations.size() < 2
            || !Double.isFinite(lateralStepMeters) || lateralStepMeters <= 0.0
            || executionPolicy == null || additionalFixedOccurrences == null
            || additionalFixedOccurrences.stream().anyMatch(i -> i == null || i < 0)
            || executionPolicy == ExecutionPolicy.HISTORICAL_V1 && !additionalFixedOccurrences.isEmpty()) {
            throw new IllegalArgumentException("Corridor trace input is incomplete");
        }
        profileLocations = List.copyOf(profileLocations);
        additionalFixedOccurrences = Collections.unmodifiableSet(new TreeSet<>(additionalFixedOccurrences));
        DetachedValueVerifier.verify(profileLocations);
    }

    /** Explicitly freezes policy and original occurrence authority without changing sampling locations. */
    public CorridorTraceInput withPolicy(ExecutionPolicy policy, Set<Integer> fixedOccurrences) {
        return new CorridorTraceInput(profileLocations, lateralStepMeters, policy, fixedOccurrences);
    }

    public boolean boundedBridgePolicy() { return executionPolicy == ExecutionPolicy.R3_OWNED_BOUNDED_BRIDGE_V1; }


    /**
     * Derives one detached location for every exact request chainage on the frozen source polyline.
     *
     * <p>The input chainage is authoritative. This method deliberately does not recreate it from a
     * configured step, so an Engine A request remains aligned with the already captured sampler
     * positions and factual ground distance.</p>
     */
    public static CorridorTraceInput from(ProfileChainage chainage,
            List<MetricPoint> sourcePolyline, LocalMetricFrame coordinateFrame,
            RasterMetricTransform rasterTransform, double lateralStepMeters) {
        Objects.requireNonNull(chainage, "chainage");
        Objects.requireNonNull(sourcePolyline, "sourcePolyline");
        Objects.requireNonNull(coordinateFrame, "coordinateFrame");
        Objects.requireNonNull(rasterTransform, "rasterTransform");
        if (sourcePolyline.size() < 2) {
            throw new IllegalArgumentException("Corridor source polyline is incomplete");
        }
        List<Double> sourceChainage = new ArrayList<>(sourcePolyline.size());
        sourceChainage.add(0.0);
        for (int index = 1; index < sourcePolyline.size(); index++) {
            sourceChainage.add(sourceChainage.get(index - 1)
                    + sourcePolyline.get(index - 1).distanceTo(sourcePolyline.get(index)));
        }
        double total = sourceChainage.get(sourceChainage.size() - 1);
        if (!(total > 0.0)) {
            throw new IllegalArgumentException("Corridor source polyline has zero length");
        }
        List<DetachedProfileSamplingLocation> locations = new ArrayList<>(
                chainage.cumulativeGroundMeters().size());
        int segment = 1;
        for (double target : chainage.cumulativeGroundMeters()) {
            if (target > total + 1.0e-8) {
                throw new IllegalArgumentException("Corridor profile chainage exceeds the frozen source");
            }
            while (segment < sourceChainage.size() - 1 && sourceChainage.get(segment) < target) {
                segment++;
            }
            double start = sourceChainage.get(segment - 1);
            double end = sourceChainage.get(segment);
            double fraction = (Math.min(target, total) - start) / (end - start);
            MetricPoint first = sourcePolyline.get(segment - 1);
            MetricPoint last = sourcePolyline.get(segment);
            MetricPoint metric = new MetricPoint(first.xMeters() + fraction * (last.xMeters() - first.xMeters()),
                    first.yMeters() + fraction * (last.yMeters() - first.yMeters()));
            locations.add(DetachedProfileSamplingLocation.at(coordinateFrame.toGeographic(metric),
                    coordinateFrame, rasterTransform, target));
        }
        return new CorridorTraceInput(locations, lateralStepMeters);
    }
}

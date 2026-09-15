package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier;

/** Frozen profile locations and physical lateral sampling step for corridor-aware Engine A. */
public record CorridorTraceInput(
    List<DetachedProfileSamplingLocation> profileLocations,
    double lateralStepMeters
) {
    /** Copies and validates detached sampling input without reconstructing locations from chainage. */
    public CorridorTraceInput {
        if (profileLocations == null || profileLocations.size() < 2
            || !Double.isFinite(lateralStepMeters) || lateralStepMeters <= 0.0) {
            throw new IllegalArgumentException("Corridor trace input is incomplete");
        }
        profileLocations = List.copyOf(profileLocations);
        DetachedValueVerifier.verify(profileLocations);
    }


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

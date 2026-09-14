package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

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
}

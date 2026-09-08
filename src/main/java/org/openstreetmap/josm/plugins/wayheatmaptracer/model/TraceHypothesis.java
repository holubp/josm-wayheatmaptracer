package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;
import java.util.Map;

/** Immutable route hypothesis in slide-local metric coordinates. */
public record TraceHypothesis(String id, String branchSignature, List<MetricPoint> points,
    List<ObservationOwnership> support, double objective, double posteriorProbability,
    Map<String, Double> diagnostics) {
    /** Copies route state and validates finite scores. */
    public TraceHypothesis {
        if (id == null || id.isBlank() || branchSignature == null || branchSignature.isBlank()
            || points == null || points.size() < 2 || support == null || support.size() != points.size()
            || !Double.isFinite(objective) || !Double.isFinite(posteriorProbability)
            || posteriorProbability < 0.0 || posteriorProbability > 1.0 || diagnostics == null) {
            throw new IllegalArgumentException("Trace hypothesis is incomplete");
        }
        points = List.copyOf(points);
        support = List.copyOf(support);
        diagnostics = Map.copyOf(diagnostics);
    }
}

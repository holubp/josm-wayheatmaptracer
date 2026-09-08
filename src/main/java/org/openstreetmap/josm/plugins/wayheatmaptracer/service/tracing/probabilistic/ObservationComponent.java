package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Explicit local evidence alternative retained through mixture marginalization. */
public record ObservationComponent(String id, String evidenceLineage, Kind kind,
    double priorWeight, boolean groupedParent) {
    /** Component type; censored and missing evidence never masquerade as measured centers. */
    public enum Kind { MEASURED, CENSORED, MISSING }

    /** Validates component identity and normalized nonnegative prior weight. */
    public ObservationComponent {
        if (id == null || id.isBlank() || evidenceLineage == null || evidenceLineage.isBlank()
            || kind == null || !Double.isFinite(priorWeight) || priorWeight < 0.0 || priorWeight > 1.0) {
            throw new IllegalArgumentException("Observation component is invalid");
        }
    }
}

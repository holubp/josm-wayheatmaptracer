package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** One admitted lateral quadrature cell in physical metres. */
public record LateralStateCell(double offsetMeters, double quadratureWidthMeters,
    boolean exactAnchor, boolean mandatory, String branchLabel) {
    /** Validates finite geometry and positive integration measure. */
    public LateralStateCell {
        if (!Double.isFinite(offsetMeters) || !Double.isFinite(quadratureWidthMeters)
            || quadratureWidthMeters <= 0.0 || branchLabel == null || branchLabel.isBlank()) {
            throw new IllegalArgumentException("Lateral state cell is invalid");
        }
    }
}

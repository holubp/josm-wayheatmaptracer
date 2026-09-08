package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;

/** Complete bounded state lattice and explicit local observation alternatives for one profile. */
public record ProbabilisticStateLattice(List<LateralStateCell> cells,
    List<ObservationComponent> components, double actualPitchMeters, boolean resolutionLimited) {
    /** Copies and validates an ordered nonempty state lattice. */
    public ProbabilisticStateLattice {
        cells = List.copyOf(cells);
        components = List.copyOf(components);
        if (cells.isEmpty() || components.isEmpty() || !Double.isFinite(actualPitchMeters)
            || actualPitchMeters <= 0.0) {
            throw new IllegalArgumentException("State lattice is incomplete");
        }
        for (int index = 1; index < cells.size(); index++) {
            if (cells.get(index).offsetMeters() <= cells.get(index - 1).offsetMeters()) {
                throw new IllegalArgumentException("Lateral cells must be strictly ordered");
            }
        }
    }
}

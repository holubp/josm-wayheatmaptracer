package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Optional additive energy assigned to each adjacent profile-state pair. */
@FunctionalInterface
public interface AdjacentStatePotential {
    /**
     * Returns the finite, nonnegative energy for a transition from {@code leftState} at
     * {@code stage} to {@code rightState} at {@code stage + 1}.
     *
     * @param stage left profile index
     * @param leftState state index at the left profile
     * @param rightState state index at the right profile
     * @return additive transition energy
     */
    double energy(int stage, int leftState, int rightState);
}

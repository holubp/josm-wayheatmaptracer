package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/**
 * Documented dimensionless weights and bounds for corridor-aware centerline optimization.
 *
 * @param maxOffsetStates maximum continuous lateral states retained at one profile
 * @param coreDistanceWeight penalty for squared source-pixel distance outside the high core
 * @param shoulderDistanceWeight penalty for squared source-pixel distance outside the shoulder
 * @param tubeCenterWeight maximum robust longitudinal-center prior weight
 * @param coarseCenterWeight maximum compatible coarse-scale center prior weight
 */
public record CorridorOptimizationParameters(
    int maxOffsetStates,
    double coreDistanceWeight,
    double shoulderDistanceWeight,
    double tubeCenterWeight,
    double coarseCenterWeight
) {
    /**
     * Returns the calibrated starting parameters inherited from the pre-tube optimizer.
     *
     * @return default corridor-aware optimization parameters
     */
    public static CorridorOptimizationParameters defaults() {
        return new CorridorOptimizationParameters(21, 0.55, 4.0, 0.55, 4.0);
    }

    /** Validates bounds and non-negative weights. */
    public CorridorOptimizationParameters {
        if (maxOffsetStates < 3) {
            throw new IllegalArgumentException("At least three offset states are required");
        }
        if (coreDistanceWeight < 0.0 || shoulderDistanceWeight < 0.0
            || tubeCenterWeight < 0.0 || coarseCenterWeight < 0.0) {
            throw new IllegalArgumentException("Corridor optimization weights must be non-negative");
        }
    }
}

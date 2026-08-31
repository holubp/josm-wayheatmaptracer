package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/**
 * Internal constants for cleanup-enabled short-wave turn regularization.
 *
 * @param absoluteTurnWeight non-negative dimensionless objective weight
 * @param curvatureDeadbandRadiansPerSourcePixel ignored turn rate in radians per source pixel
 * @param curvatureScaleRadiansPerSourcePixel positive normalization scale in radians per source pixel
 * @param huberKnee positive normalized residual at which the Huber loss becomes linear
 */
record RippleRegularizationParameters(
    double absoluteTurnWeight,
    double curvatureDeadbandRadiansPerSourcePixel,
    double curvatureScaleRadiansPerSourcePixel,
    double huberKnee
) {
    RippleRegularizationParameters {
        if (!Double.isFinite(absoluteTurnWeight) || absoluteTurnWeight < 0.0
            || !Double.isFinite(curvatureDeadbandRadiansPerSourcePixel)
            || curvatureDeadbandRadiansPerSourcePixel < 0.0
            || !Double.isFinite(curvatureScaleRadiansPerSourcePixel)
            || curvatureScaleRadiansPerSourcePixel <= 0.0
            || !Double.isFinite(huberKnee) || huberKnee <= 0.0) {
            throw new IllegalArgumentException("Ripple regularization parameters must be finite and valid");
        }
    }

    /**
     * Returns the conservative calibrated seed selected by preservation and ripple fixtures.
     *
     * @return immutable default parameters
     */
    static RippleRegularizationParameters defaults() {
        return new RippleRegularizationParameters(0.20, 0.02, 0.05, 1.0);
    }

    /**
     * Evaluates the non-negative Huber loss for a normalized turn-rate excess.
     *
     * @param value non-negative normalized excess above the curvature deadband
     * @return quadratic loss below the knee and linear loss above it
     */
    double huber(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("Huber input must be finite and non-negative");
        }
        return value <= huberKnee ? 0.5 * value * value
            : huberKnee * (value - 0.5 * huberKnee);
    }
}

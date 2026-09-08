package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Versioned numerical parameters for probabilistic longitudinal inference. */
public record EvidenceModelParameters(
    String version,
    double dataWeight,
    double centerWeight,
    double turnWeight,
    double orientationWeight,
    double guideWeight,
    double turnScaleRadians,
    double temperature,
    double ambiguityEnergyDelta
) {
    /** Validates finite nonnegative weights and positive scales. */
    public EvidenceModelParameters {
        if (version == null || version.isBlank() || !finiteNonnegative(dataWeight)
            || !finiteNonnegative(centerWeight) || !finiteNonnegative(turnWeight)
            || !finiteNonnegative(orientationWeight) || !finiteNonnegative(guideWeight)
            || !Double.isFinite(turnScaleRadians) || turnScaleRadians <= 0.0
            || !Double.isFinite(temperature) || temperature <= 0.0
            || !finiteNonnegative(ambiguityEnergyDelta)) {
            throw new IllegalArgumentException("Probabilistic evidence parameters are invalid");
        }
    }

    /** Returns the normative initial v0.22 parameter set. */
    public static EvidenceModelParameters defaults() {
        return new EvidenceModelParameters("probabilistic-v0", 1.0, 1.0, 2.0, 0.5, 0.0,
            Math.toRadians(30.0), 1.0, 2.0);
    }

    /** Returns a deterministic data-only parameter set used by exact solver tests. */
    public static EvidenceModelParameters withoutShapeTerms() {
        return new EvidenceModelParameters("probabilistic-v0-data-only", 1.0, 1.0, 0.0, 0.0, 0.0,
            Math.toRadians(30.0), 1.0, 1e-9);
    }

    /** Returns the Huber loss with unit transition between quadratic and linear regimes. */
    public static double huber(double value) {
        double absolute = Math.abs(value);
        return absolute <= 1.0 ? 0.5 * value * value : absolute - 0.5;
    }

    private static boolean finiteNonnegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }
}

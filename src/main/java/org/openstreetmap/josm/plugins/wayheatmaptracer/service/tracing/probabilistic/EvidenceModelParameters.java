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
    double ambiguityEnergyDelta,
    Localization localization
) {
    /** Validates finite nonnegative weights and positive scales. */
    public EvidenceModelParameters {
        if (version == null || version.isBlank() || !finiteNonnegative(dataWeight)
            || !finiteNonnegative(centerWeight) || !finiteNonnegative(turnWeight)
            || !finiteNonnegative(orientationWeight) || !finiteNonnegative(guideWeight)
            || !Double.isFinite(turnScaleRadians) || turnScaleRadians <= 0.0
            || !Double.isFinite(temperature) || temperature <= 0.0
            || !finiteNonnegative(ambiguityEnergyDelta) || localization == null) {
            throw new IllegalArgumentException("Probabilistic evidence parameters are invalid");
        }
    }

    /** Retains the pre-localization constructor while selecting the normative localization policy. */
    public EvidenceModelParameters(String version, double dataWeight, double centerWeight,
        double turnWeight, double orientationWeight, double guideWeight, double turnScaleRadians,
        double temperature, double ambiguityEnergyDelta) {
        this(version, dataWeight, centerWeight, turnWeight, orientationWeight, guideWeight,
            turnScaleRadians, temperature, ambiguityEnergyDelta, Localization.defaults());
    }

    /** Returns the version of the direct longitudinal weak-signal policy. */
    public static String reliabilityPolicyVersion() {
        return "direct-longitudinal-2";
    }

    /** Returns the normative initial v0.22 parameter set. */
    public static EvidenceModelParameters defaults() {
        return new EvidenceModelParameters("probabilistic-v0.22-" + reliabilityPolicyVersion(), 1.0, 1.0, 2.0,
            0.5, 0.5, Math.toRadians(30.0), 1.0, 2.0, Localization.defaults());
    }

    /** Returns a deterministic data-only parameter set used by exact solver tests. */
    public static EvidenceModelParameters withoutShapeTerms() {
        return new EvidenceModelParameters("probabilistic-v0.22-" + reliabilityPolicyVersion() + "-data-only", 1.0,
            1.0, 0.0, 0.0, 0.0, Math.toRadians(30.0), 1.0, 1e-9,
            Localization.defaults());
    }

    /** Returns the Huber loss with unit transition between quadratic and linear regimes. */
    public static double huber(double value) {
        double absolute = Math.abs(value);
        return absolute <= 1.0 ? 0.5 * value * value : absolute - 0.5;
    }

    /** Version-owned bounded parameters for scalar mode and image-orientation localization. */
    public record Localization(
        int orientationHeadingCount,
        double minimumOrientationRayMeters,
        double orientationRayLengthPitches,
        double maximumOrientationStepPitches,
        double minimumOrientationValidFraction,
        double orientationBackgroundQuantile,
        double orientationProminenceFraction,
        int maximumOrientationSampleCount,
        double localModeProminenceFraction,
        double localModeShoulderFraction,
        double localModeCoreFraction,
        double routeProfileHalfWidthMeters,
        double scalarAmplitudeHalfResponse
    ) {
        /** Finest supported angular resolution for this descriptor version. */
        public static final int MAXIMUM_ORIENTATION_HEADING_COUNT = 180;
        /** Hard allocation/work budget for this descriptor version. */
        public static final int MAXIMUM_ORIENTATION_SAMPLE_BUDGET = 16_384;

        /** Validates the fixed finite extraction budget and normalized fractions. */
        public Localization {
            if (orientationHeadingCount < 3 || !positive(minimumOrientationRayMeters)
                || orientationHeadingCount > MAXIMUM_ORIENTATION_HEADING_COUNT
                || !positive(orientationRayLengthPitches) || !positive(maximumOrientationStepPitches)
                || maximumOrientationStepPitches > 0.5 || !unit(minimumOrientationValidFraction)
                || !unit(orientationBackgroundQuantile) || !unit(orientationProminenceFraction)
                || maximumOrientationSampleCount > MAXIMUM_ORIENTATION_SAMPLE_BUDGET
                || maximumOrientationSampleCount < (long) orientationHeadingCount * 2L
                || !unit(localModeProminenceFraction) || !unit(localModeShoulderFraction)
                || !unit(localModeCoreFraction) || localModeCoreFraction < localModeShoulderFraction
                || !positive(routeProfileHalfWidthMeters) || !positive(scalarAmplitudeHalfResponse)) {
                throw new IllegalArgumentException("Localization parameters are invalid");
            }
        }

        /** Returns the initial 18-heading physical descriptor and local-mode extraction policy. */
        public static Localization defaults() {
            return new Localization(18, 6.0, 4.0, 0.5, 0.75, 0.2, 0.1,
                16_384, 0.1, 0.72, 0.92, 8.0, 0.08);
        }

        /** Retains the pre-v0.22.0-alpha.9 localization constructor for fixtures. */
        public Localization(int orientationHeadingCount, double minimumOrientationRayMeters,
            double orientationRayLengthPitches, double maximumOrientationStepPitches,
            double minimumOrientationValidFraction, double orientationBackgroundQuantile,
            double orientationProminenceFraction, int maximumOrientationSampleCount,
            double localModeProminenceFraction, double localModeShoulderFraction,
            double localModeCoreFraction, double routeProfileHalfWidthMeters) {
            this(orientationHeadingCount, minimumOrientationRayMeters, orientationRayLengthPitches,
                maximumOrientationStepPitches, minimumOrientationValidFraction,
                orientationBackgroundQuantile, orientationProminenceFraction,
                maximumOrientationSampleCount, localModeProminenceFraction,
                localModeShoulderFraction, localModeCoreFraction, routeProfileHalfWidthMeters, 0.08);
        }
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static boolean finiteNonnegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }
}

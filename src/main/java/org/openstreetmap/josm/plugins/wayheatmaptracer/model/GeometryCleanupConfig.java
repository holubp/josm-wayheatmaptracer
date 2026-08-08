package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Immutable, unit-explicit settings for an optional cleaned alignment candidate.
 *
 * @param mode cleanup operation
 * @param preset origin of the numeric values
 * @param rippleScaleMeters maximum physical scale of unsupported lateral reversals
 * @param rippleStrength dimensionless unsupported-ripple penalty strength in {@code [0,1]}
 * @param laplacianStrength dimensionless normal-only smoothing strength in {@code [0,1]}
 * @param laplacianPassCount maximum deterministic smoothing passes
 * @param simplificationDeviationMeters maximum point-reduction deviation in ground metres
 * @param minimumFitRetention minimum retained heatmap-fit ratio in {@code [0,1]}
 * @param cleanedAlternativeRequested whether a cleaned sibling candidate should be generated
 */
public record GeometryCleanupConfig(
    GeometryCleanupMode mode,
    GeometryCleanupPreset preset,
    double rippleScaleMeters,
    double rippleStrength,
    double laplacianStrength,
    int laplacianPassCount,
    double simplificationDeviationMeters,
    double minimumFitRetention,
    boolean cleanedAlternativeRequested
) {
    private static final double MAX_SCALE_METERS = 100.0;
    private static final double MAX_DEVIATION_METERS = 100.0;
    private static final int MAX_PASS_COUNT = 20;

    /** Validates all physical units and mode/request consistency. */
    public GeometryCleanupConfig {
        if (mode == null || preset == null) {
            throw new IllegalArgumentException("Cleanup mode and preset must not be null");
        }
        requirePositiveRange(rippleScaleMeters, 0.5, MAX_SCALE_METERS, "rippleScaleMeters");
        requireRange(rippleStrength, 0.0, 1.0, "rippleStrength");
        requireRange(laplacianStrength, 0.0, 1.0, "laplacianStrength");
        if (laplacianPassCount < 1 || laplacianPassCount > MAX_PASS_COUNT) {
            throw new IllegalArgumentException("laplacianPassCount must be in [1, 20]");
        }
        requirePositiveRange(simplificationDeviationMeters, 0.05, MAX_DEVIATION_METERS,
            "simplificationDeviationMeters");
        requireRange(minimumFitRetention, 0.0, 1.0, "minimumFitRetention");
        if (cleanedAlternativeRequested != (mode != GeometryCleanupMode.NONE)) {
            throw new IllegalArgumentException("Cleaned-alternative request must match the cleanup mode");
        }
    }

    /**
     * Returns cleanup-disabled settings while retaining the Balanced values for later UI activation.
     *
     * @return disabled immutable configuration
     */
    public static GeometryCleanupConfig disabled() {
        return GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.NONE);
    }

    /**
     * Returns whether this configuration leaves raw candidates unchanged.
     *
     * @return {@code true} for {@link GeometryCleanupMode#NONE}
     */
    public boolean isDisabled() {
        return mode == GeometryCleanupMode.NONE;
    }

    /**
     * Returns a copy using a different mode and a consistent candidate-request flag.
     *
     * @param newMode cleanup mode to use
     * @return validated copied configuration
     */
    public GeometryCleanupConfig withMode(GeometryCleanupMode newMode) {
        GeometryCleanupMode effective = newMode == null ? GeometryCleanupMode.NONE : newMode;
        return new GeometryCleanupConfig(effective, preset, rippleScaleMeters, rippleStrength,
            laplacianStrength, laplacianPassCount, simplificationDeviationMeters,
            minimumFitRetention, effective != GeometryCleanupMode.NONE);
    }

    /**
     * Applies a complete preset while preserving the current mode.
     *
     * @param newPreset preset to apply
     * @return preset-derived configuration
     */
    public GeometryCleanupConfig withPreset(GeometryCleanupPreset newPreset) {
        if (newPreset == null) {
            throw new IllegalArgumentException("Cleanup preset must not be null");
        }
        return newPreset.apply(mode);
    }

    /**
     * Returns a custom configuration with a different unsupported-ripple scale.
     *
     * @param value unsupported-ripple scale in ground metres
     * @return custom copied configuration
     * @throws IllegalArgumentException when {@code value} is outside the supported range
     */
    public GeometryCleanupConfig withRippleScaleMeters(double value) {
        return custom(value, rippleStrength, laplacianStrength, laplacianPassCount,
            simplificationDeviationMeters, minimumFitRetention);
    }

    /**
     * Returns a custom configuration with a different ripple-penalty strength.
     *
     * @param value dimensionless ripple strength
     * @return custom copied configuration
     * @throws IllegalArgumentException when {@code value} is outside {@code [0,1]}
     */
    public GeometryCleanupConfig withRippleStrength(double value) {
        return custom(rippleScaleMeters, value, laplacianStrength, laplacianPassCount,
            simplificationDeviationMeters, minimumFitRetention);
    }

    /**
     * Returns a custom configuration with a different normal-only smoothing strength.
     *
     * @param value dimensionless Laplacian strength
     * @return custom copied configuration
     * @throws IllegalArgumentException when {@code value} is outside {@code [0,1]}
     */
    public GeometryCleanupConfig withLaplacianStrength(double value) {
        return custom(rippleScaleMeters, rippleStrength, value, laplacianPassCount,
            simplificationDeviationMeters, minimumFitRetention);
    }

    /**
     * Returns a custom configuration with a different deterministic smoothing pass limit.
     *
     * @param value smoothing pass count
     * @return custom copied configuration
     * @throws IllegalArgumentException when {@code value} is outside the supported range
     */
    public GeometryCleanupConfig withLaplacianPassCount(int value) {
        return custom(rippleScaleMeters, rippleStrength, laplacianStrength, value,
            simplificationDeviationMeters, minimumFitRetention);
    }

    /**
     * Returns a custom configuration with a different point-reduction deviation bound.
     *
     * @param value point-reduction deviation in ground metres
     * @return custom copied configuration
     * @throws IllegalArgumentException when {@code value} is outside the supported range
     */
    public GeometryCleanupConfig withSimplificationDeviationMeters(double value) {
        return custom(rippleScaleMeters, rippleStrength, laplacianStrength, laplacianPassCount,
            value, minimumFitRetention);
    }

    /**
     * Returns a custom configuration with a different raw/B3/B5 fit-retention floor.
     *
     * @param value minimum retained heatmap-fit ratio
     * @return custom copied configuration
     * @throws IllegalArgumentException when {@code value} is outside {@code [0,1]}
     */
    public GeometryCleanupConfig withMinimumFitRetention(double value) {
        return custom(rippleScaleMeters, rippleStrength, laplacianStrength, laplacianPassCount,
            simplificationDeviationMeters, value);
    }

    /**
     * Serializes the complete non-sensitive cleanup settings.
     *
     * @return redacted JSON object suitable for logs and debug exports
     */
    public String toRedactedJson() {
        return "{\"mode\":\"" + mode + "\",\"preset\":\"" + preset
            + "\",\"rippleScaleMeters\":" + rippleScaleMeters
            + ",\"rippleStrength\":" + rippleStrength
            + ",\"laplacianStrength\":" + laplacianStrength
            + ",\"laplacianPassCount\":" + laplacianPassCount
            + ",\"simplificationDeviationMeters\":" + simplificationDeviationMeters
            + ",\"minimumFitRetention\":" + minimumFitRetention
            + ",\"cleanedAlternativeRequested\":" + cleanedAlternativeRequested + "}";
    }

    private GeometryCleanupConfig custom(
        double scaleMeters,
        double ripple,
        double laplacian,
        int passes,
        double deviationMeters,
        double fitRetention
    ) {
        return new GeometryCleanupConfig(mode, GeometryCleanupPreset.CUSTOM, scaleMeters, ripple,
            laplacian, passes, deviationMeters, fitRetention, cleanedAlternativeRequested);
    }

    private static void requirePositiveRange(double value, double minimum, double maximum, String name) {
        requireRange(value, minimum, maximum, name);
    }

    private static void requireRange(double value, double minimum, double maximum, String name) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be finite and in [" + minimum + ", " + maximum + "]");
        }
    }
}

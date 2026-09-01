package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupChoice;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;

/**
 * Swing-independent binding state for geometry cleanup preferences.
 *
 * <p>The model owns only the proposed configuration. Persisting it is deliberately the dialog's
 * confirmation responsibility, so cancelling a dialog cannot modify preferences.</p>
 */
public final class GeometryCleanupSettingsModel {
    private GeometryCleanupConfig config;

    /**
     * Creates a settings model from a previously loaded configuration.
     *
     * @param config configuration to edit
     * @throws NullPointerException if {@code config} is {@code null}
     */
    public GeometryCleanupSettingsModel(GeometryCleanupConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Returns the current proposed immutable configuration.
     *
     * @return current configuration
     */
    public GeometryCleanupConfig config() {
        return config;
    }

    /**
     * Returns the effective primary choice represented by the proposed configuration.
     *
     * @return effective cleanup choice
     */
    public GeometryCleanupChoice choice() {
        return config.choice();
    }

    /**
     * Returns which groups of numeric controls apply to the selected cleanup mode.
     *
     * @return immutable enablement state for the dialog controls
     */
    public ControlState controlState() {
        boolean smoothing = config.mode() == GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE;
        boolean reduction = config.mode() != GeometryCleanupMode.NONE;
        return new ControlState(smoothing, smoothing, reduction, reduction);
    }

    /**
     * Selects one effective cleanup operation.
     *
     * @param choice selected operation
     */
    public void selectChoice(GeometryCleanupChoice choice) {
        config = config.withChoice(Objects.requireNonNull(choice, "choice"));
    }

    /**
     * Changes the cleanup mode while retaining numeric values for later reactivation.
     *
     * @param mode selected cleanup mode
     */
    public void selectMode(GeometryCleanupMode mode) {
        config = config.withMode(Objects.requireNonNull(mode, "mode"));
    }

    /**
     * Applies a complete named preset while retaining the selected cleanup mode.
     *
     * <p>{@link GeometryCleanupPreset#CUSTOM} is a display state created by numeric edits, so
     * selecting it leaves the current numeric values untouched.</p>
     *
     * @param preset selected preset
     */
    public void selectPreset(GeometryCleanupPreset preset) {
        GeometryCleanupPreset requested = Objects.requireNonNull(preset, "preset");
        if (requested != GeometryCleanupPreset.CUSTOM) {
            config = config.withPreset(requested);
        }
    }

    /**
     * Sets a custom unsupported-ripple scale.
     *
     * @param value scale in ground metres
     * @throws IllegalArgumentException when the value is outside the supported range
     */
    public void setRippleScaleMeters(double value) {
        config = config.withRippleScaleMeters(value);
    }

    /**
     * Sets a custom unsupported-ripple penalty strength.
     *
     * @param value dimensionless strength in {@code [0,1]}
     * @throws IllegalArgumentException when the value is outside {@code [0,1]}
     */
    public void setRippleStrength(double value) {
        config = config.withRippleStrength(value);
    }

    /**
     * Sets a custom normal-only Laplacian smoothing strength.
     *
     * @param value dimensionless strength in {@code [0,1]}
     * @throws IllegalArgumentException when the value is outside {@code [0,1]}
     */
    public void setLaplacianStrength(double value) {
        config = config.withLaplacianStrength(value);
    }

    /**
     * Sets the deterministic Laplacian smoothing pass limit.
     *
     * @param value positive pass count within the supported range
     * @throws IllegalArgumentException when the value is outside the supported range
     */
    public void setLaplacianPassCount(int value) {
        config = config.withLaplacianPassCount(value);
    }

    /**
     * Sets the maximum constrained point-reduction deviation.
     *
     * @param value deviation in ground metres
     * @throws IllegalArgumentException when the value is outside the supported range
     */
    public void setSimplificationDeviationMeters(double value) {
        config = config.withSimplificationDeviationMeters(value);
    }

    /**
     * Sets the minimum retained raw/B3/B5 heatmap-fit ratio.
     *
     * @param value fit ratio in {@code [0,1]}
     * @throws IllegalArgumentException when the value is outside {@code [0,1]}
     */
    public void setMinimumFitRetention(double value) {
        config = config.withMinimumFitRetention(value);
    }

    /**
     * Defines the enabled numeric control groups for a cleanup mode.
     *
     * @param ripple controls for ripple scale and strength
     * @param laplacian controls for smoothing strength and pass count
     * @param reduction control for point-reduction deviation
     * @param fitRetention control for minimum retained heatmap fit
     */
    public record ControlState(
        boolean ripple,
        boolean laplacian,
        boolean reduction,
        boolean fitRetention
    ) {
    }
}

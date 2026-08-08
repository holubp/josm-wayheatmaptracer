package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;
import java.awt.Window;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.tools.GBC;

/** Modal configuration-only editor for future geometry cleanup candidates. */
public final class GeometryCleanupSettingsDialog {
    private final Window parent;
    private final GeometryCleanupSettingsModel model;
    private final JComboBox<GeometryCleanupMode> mode = new JComboBox<>(GeometryCleanupMode.values());
    private final JComboBox<GeometryCleanupPreset> preset = new JComboBox<>(GeometryCleanupPreset.values());
    private final JSpinner rippleScaleMeters = spinner(10.0, 0.5, 100.0, 0.5);
    private final JSpinner rippleStrength = spinner(0.55, 0.0, 1.0, 0.05);
    private final JSpinner laplacianStrength = spinner(0.25, 0.0, 1.0, 0.05);
    private final JSpinner laplacianPassCount = new JSpinner(new SpinnerNumberModel(3, 1, 20, 1));
    private final JSpinner simplificationDeviationMeters = spinner(2.0, 0.05, 100.0, 0.05);
    private final JSpinner minimumFitRetention = spinner(0.90, 0.0, 1.0, 0.01);
    private boolean binding;

    /**
     * Creates a dialog initialized from the shared cleanup preferences.
     *
     * @param parent parent window for the modal dialog
     */
    public GeometryCleanupSettingsDialog(Window parent) {
        this(parent, new GeometryCleanupSettingsModel(PluginPreferences.loadGeometryCleanup()));
    }

    /**
     * Creates a dialog using a supplied model, primarily for component-level tests.
     *
     * @param parent parent window for the modal dialog
     * @param model proposed cleanup settings
     */
    GeometryCleanupSettingsDialog(Window parent, GeometryCleanupSettingsModel model) {
        this.parent = parent;
        this.model = model;
        mode.addActionListener(event -> {
            if (!binding) {
                model.selectMode((GeometryCleanupMode) mode.getSelectedItem());
                bindModel();
            }
        });
        preset.addActionListener(event -> {
            if (!binding) {
                model.selectPreset((GeometryCleanupPreset) preset.getSelectedItem());
                bindModel();
            }
        });
        rippleScaleMeters.addChangeListener(event -> updateNumeric(() ->
            model.setRippleScaleMeters(number(rippleScaleMeters))));
        rippleStrength.addChangeListener(event -> updateNumeric(() ->
            model.setRippleStrength(number(rippleStrength))));
        laplacianStrength.addChangeListener(event -> updateNumeric(() ->
            model.setLaplacianStrength(number(laplacianStrength))));
        laplacianPassCount.addChangeListener(event -> updateNumeric(() ->
            model.setLaplacianPassCount(((Number) laplacianPassCount.getValue()).intValue())));
        simplificationDeviationMeters.addChangeListener(event -> updateNumeric(() ->
            model.setSimplificationDeviationMeters(number(simplificationDeviationMeters))));
        minimumFitRetention.addChangeListener(event -> updateNumeric(() ->
            model.setMinimumFitRetention(number(minimumFitRetention))));
        bindModel();
    }

    /**
     * Displays the editor and saves only when the user confirms it.
     *
     * @return {@code true} when cleanup preferences were saved
     */
    public boolean showDialog() {
        int answer = JOptionPane.showConfirmDialog(parent, createPanel(), tr("Geometry cleanup"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return false;
        }
        PluginPreferences.saveGeometryCleanup(model.config());
        return true;
    }

    /**
     * Returns a compact user-readable description for another settings surface.
     *
     * @param config cleanup configuration to describe
     * @return compact summary
     */
    public static String summary(GeometryCleanupConfig config) {
        if (config.isDisabled()) {
            return tr("Disabled");
        }
        return tr("{0}: {1} m", config.preset(), config.rippleScaleMeters());
    }

    private JPanel createPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel(tr("Mode")), GBC.std());
        panel.add(mode, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JLabel(tr("Preset")), GBC.std());
        panel.add(preset, GBC.eol().fill(GBC.HORIZONTAL));
        add(panel, tr("Ripple scale (m)"), rippleScaleMeters, tr("Maximum scale of unsupported lateral reversals."));
        add(panel, tr("Ripple strength"), rippleStrength, tr("Penalty applied to unsupported ripple motion."));
        add(panel, tr("Laplacian strength"), laplacianStrength, tr("Normal-only smoothing strength."));
        add(panel, tr("Laplacian passes"), laplacianPassCount, tr("Maximum deterministic smoothing passes."));
        add(panel, tr("Reduction deviation (m)"), simplificationDeviationMeters,
            tr("Maximum ground deviation allowed while reducing points."));
        add(panel, tr("Fit retention"), minimumFitRetention, tr("Minimum retained heatmap-fit ratio."));
        return panel;
    }

    private void add(JPanel panel, String label, JSpinner control, String tooltip) {
        control.setToolTipText(tooltip);
        panel.add(new JLabel(label), GBC.std());
        panel.add(control, GBC.eol().fill(GBC.HORIZONTAL));
    }

    private void bindModel() {
        binding = true;
        GeometryCleanupConfig config = model.config();
        mode.setSelectedItem(config.mode());
        preset.setSelectedItem(config.preset());
        rippleScaleMeters.setValue(config.rippleScaleMeters());
        rippleStrength.setValue(config.rippleStrength());
        laplacianStrength.setValue(config.laplacianStrength());
        laplacianPassCount.setValue(config.laplacianPassCount());
        simplificationDeviationMeters.setValue(config.simplificationDeviationMeters());
        minimumFitRetention.setValue(config.minimumFitRetention());
        GeometryCleanupSettingsModel.ControlState state = model.controlState();
        rippleScaleMeters.setEnabled(state.ripple());
        rippleStrength.setEnabled(state.ripple());
        laplacianStrength.setEnabled(state.laplacian());
        laplacianPassCount.setEnabled(state.laplacian());
        simplificationDeviationMeters.setEnabled(state.reduction());
        minimumFitRetention.setEnabled(state.fitRetention());
        binding = false;
    }

    private void updateNumeric(Runnable update) {
        if (!binding) {
            update.run();
            bindModel();
        }
    }

    private static JSpinner spinner(double value, double minimum, double maximum, double step) {
        return new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
    }

    private static double number(JSpinner spinner) {
        return ((Number) spinner.getValue()).doubleValue();
    }
}

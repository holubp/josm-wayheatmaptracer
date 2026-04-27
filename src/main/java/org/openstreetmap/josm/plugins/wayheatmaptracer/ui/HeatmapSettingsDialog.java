package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;
import java.awt.Window;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.StravaCookieParser;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.StravaCookieValues;
import org.openstreetmap.josm.tools.GBC;

public final class HeatmapSettingsDialog {
    private static final String[] ACTIVITIES = {"all", "ride", "run", "water", "winter"};
    private static final String[] COLORS = {"hot", "blue", "bluered", "purple", "gray"};

    private final JTextField keyPairId = new JTextField(36);
    private final JTextField policy = new JTextField(36);
    private final JTextField signature = new JTextField(36);
    private final JTextField session = new JTextField(36);
    private final JComboBox<String> activity = new JComboBox<>(ACTIVITIES);
    private final JComboBox<String> color = new JComboBox<>(COLORS);
    private final JComboBox<String> manualLayer = new JComboBox<>();
    private final JComboBox<AlignmentMode> alignmentMode = new JComboBox<>(AlignmentMode.values());
    private final JTextField regex = new JTextField(36);
    private final JCheckBox verbose = new JCheckBox(tr("Verbose logging"));
    private final JCheckBox debug = new JCheckBox(tr("Debug overlay"));
    private final JCheckBox multiColorDetection = new JCheckBox(tr("Use all color schemes for detection"));
    private final JCheckBox parallelWayAwareness = new JCheckBox(tr("Use nearby parallel ways as alignment context"));
    private final JCheckBox allowUndownloadedAlignment = new JCheckBox(tr("Allow aligning without downloaded OSM area"));
    private final JCheckBox adjustJunctionNodes = new JCheckBox(tr("Adjust junction and endpoint nodes"));
    private final JCheckBox simplify = new JCheckBox(tr("Enable simplification"));
    private final JTextField halfWidth = new JTextField(8);
    private final JTextField step = new JTextField(8);
    private final JTextField tolerance = new JTextField(8);
    private final Window parent;

    public HeatmapSettingsDialog(Window parent) {
        this.parent = parent;
        ManagedHeatmapConfig config = PluginPreferences.load();
        keyPairId.setText(config.keyPairId());
        policy.setText(config.policy());
        signature.setText(config.signature());
        session.setText(config.sessionToken());
        activity.setSelectedItem(config.activity());
        color.setSelectedItem(config.color());
        regex.setText(config.layerRegex());
        alignmentMode.setSelectedItem(config.alignmentMode());
        verbose.setSelected(config.verbose());
        debug.setSelected(config.debug());
        multiColorDetection.setSelected(config.multiColorDetection());
        parallelWayAwareness.setSelected(config.parallelWayAwareness());
        allowUndownloadedAlignment.setSelected(config.allowUndownloadedAlignment());
        adjustJunctionNodes.setSelected(config.adjustJunctionNodes());
        simplify.setSelected(config.simplifyEnabled());
        halfWidth.setText(Integer.toString(config.crossSectionHalfWidthPx()));
        step.setText(Integer.toString(config.crossSectionStepPx()));
        tolerance.setText(Double.toString(config.simplifyTolerancePx()));

        manualLayer.addItem("");
        for (org.openstreetmap.josm.gui.layer.Layer layer : MainApplication.getLayerManager().getLayers()) {
            if (layer instanceof ImageryLayer imageryLayer) {
                manualLayer.addItem(imageryLayer.getName());
            }
        }
        manualLayer.setSelectedItem(config.manualLayerName());
    }

    public boolean showDialog() {
        JPanel panel = new JPanel(new GridBagLayout());
        JButton pasteCookies = new JButton(tr("Paste cookie header..."));
        pasteCookies.addActionListener(event -> showCookiePasteDialog());
        panel.add(pasteCookies, GBC.eol().anchor(GBC.WEST));
        panel.add(new JLabel(tr("CloudFront-Key-Pair-Id")), GBC.std());
        panel.add(keyPairId, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JLabel(tr("CloudFront-Policy")), GBC.std());
        panel.add(policy, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JLabel(tr("CloudFront-Signature")), GBC.std());
        panel.add(signature, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JLabel(tr("_strava_idcf")), GBC.std());
        panel.add(session, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JLabel(tr("Strava activity")), GBC.std());
        panel.add(activity, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JLabel(tr("Strava color")), GBC.std());
        panel.add(color, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JLabel(tr("Exact heatmap layer title")), GBC.std());
        panel.add(manualLayer, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JLabel(tr("Fallback layer regex")), GBC.std());
        panel.add(regex, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JLabel(tr("Alignment mode")), GBC.std());
        panel.add(alignmentMode, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JLabel(tr("Cross-section half-width px")), GBC.std());
        panel.add(halfWidth, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JLabel(tr("Cross-section step px")), GBC.std());
        panel.add(step, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(new JLabel(tr("Simplify tolerance")), GBC.std());
        panel.add(tolerance, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(verbose, GBC.eol());
        panel.add(debug, GBC.eol());
        panel.add(multiColorDetection, GBC.eol());
        panel.add(parallelWayAwareness, GBC.eol());
        panel.add(allowUndownloadedAlignment, GBC.eol());
        panel.add(adjustJunctionNodes, GBC.eol());
        panel.add(simplify, GBC.eol());

        int answer = JOptionPane.showConfirmDialog(
            parent,
            panel,
            tr("WayHeatmapTracer Settings"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        );
        if (answer != JOptionPane.OK_OPTION) {
            return false;
        }

        PluginPreferences.save(new ManagedHeatmapConfig(
            keyPairId.getText().trim(),
            policy.getText().trim(),
            signature.getText().trim(),
            session.getText().trim(),
            activity.getSelectedItem() == null ? "all" : activity.getSelectedItem().toString(),
            color.getSelectedItem() == null ? "hot" : color.getSelectedItem().toString(),
            manualLayer.getSelectedItem() == null ? "" : manualLayer.getSelectedItem().toString(),
            regex.getText().trim(),
            (AlignmentMode) alignmentMode.getSelectedItem(),
            verbose.isSelected(),
            debug.isSelected(),
            multiColorDetection.isSelected(),
            parallelWayAwareness.isSelected(),
            allowUndownloadedAlignment.isSelected(),
            adjustJunctionNodes.isSelected(),
            simplify.isSelected(),
            parseInt(halfWidth.getText(), 18),
            parseInt(step.getText(), 4),
            parseDouble(tolerance.getText(), 3.0)
        ));
        return true;
    }

    private void showCookiePasteDialog() {
        JTextArea pasted = new JTextArea(6, 54);
        pasted.setLineWrap(true);
        pasted.setWrapStyleWord(true);
        int answer = JOptionPane.showConfirmDialog(
            parent,
            new JScrollPane(pasted),
            tr("Paste Strava Cookie Header"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        );
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            StravaCookieValues values = StravaCookieParser.parse(pasted.getText());
            keyPairId.setText(values.keyPairId());
            policy.setText(values.policy());
            signature.setText(values.signature());
            session.setText(values.sessionToken());
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(parent, ex.getMessage(), tr("WayHeatmapTracer"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}

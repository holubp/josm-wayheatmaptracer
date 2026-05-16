package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.HeatmapCalibrationBundle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginDirectories;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Exports managed heatmap source tiles for palette and convolution-filter calibration.
 */
public class ExportCalibrationTilesAction extends JosmAction {
    private static final List<String> CALIBRATION_COLORS = List.of("hot", "blue", "bluered", "purple", "gray");

    /**
     * Creates the calibration tile export action and registers its keyboard shortcut.
     */
    public ExportCalibrationTilesAction() {
        super(
            tr("Export Heatmap Calibration Tiles"),
            null,
            tr("Export redacted Strava heatmap tile images for palette calibration"),
            Shortcut.registerShortcut(
                "wayheatmaptracer:export-calibration-tiles",
                tr("WayHeatmapTracer: Export Calibration Tiles"),
                KeyEvent.VK_P,
                Shortcut.ALT_CTRL_SHIFT
            ),
            false
        );
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null) {
            showWarning(tr("No editable OSM data layer is active."));
            return;
        }
        ManagedHeatmapConfig config = PluginPreferences.load();
        if (!config.hasManagedAccessValues()) {
            showWarning(tr("Configure Strava managed heatmap access before exporting calibration tiles."));
            return;
        }

        try {
            SelectionContext selection = SelectionResolver.resolve(dataSet, config.adjustJunctionNodes());
            List<EastNorth> sourcePolyline = selection.segmentNodes().stream()
                .map(this::eastNorth)
                .toList();
            TileHeatmapSampler.TileMosaicSet mosaics = new TileHeatmapSampler().prepare(
                config,
                sourcePolyline,
                CALIBRATION_COLORS,
                AlignmentService.isSketchLikeSelection(selection)
            );
            File dir = PluginDirectories.ensurePluginDataDirectory();
            File file = new File(dir, "heatmap-calibration-" + System.currentTimeMillis() + ".zip");
            new HeatmapCalibrationBundle(mosaics, sourceSummary(selection, config)).writeTo(file);
            showExportedDialog(file);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Failed to export heatmap calibration tiles: {0}", ex.getMessage()),
                tr("WayHeatmapTracer"),
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private EastNorth eastNorth(Node node) {
        return node.getEastNorth(ProjectionRegistry.getProjection());
    }

    private String sourceSummary(SelectionContext selection, ManagedHeatmapConfig config) {
        return "{"
            + "\"wayId\":" + selection.way().getUniqueId() + ','
            + "\"startIndex\":" + selection.startIndex() + ','
            + "\"endIndex\":" + selection.endIndex() + ','
            + "\"segmentNodeCount\":" + selection.segmentNodes().size() + ','
            + "\"activity\":\"" + escape(config.activity()) + "\","
            + "\"selectedVisibleColor\":\"" + escape(config.color()) + "\","
            + "\"exportedColors\":[\"hot\",\"blue\",\"bluered\",\"purple\",\"gray\"]"
            + "}";
    }

    private void showWarning(String message) {
        JOptionPane.showMessageDialog(
            MainApplication.getMainFrame(),
            message,
            tr("WayHeatmapTracer"),
            JOptionPane.WARNING_MESSAGE
        );
    }

    private void showExportedDialog(File file) {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        JTextField path = new JTextField(file.getAbsolutePath(), 48);
        path.setEditable(false);
        JButton copyFile = new JButton(tr("Copy file path"));
        copyFile.addActionListener(event -> copy(file.getAbsolutePath()));
        JButton copyFolder = new JButton(tr("Copy folder path"));
        copyFolder.addActionListener(event -> copy(file.getParentFile().getAbsolutePath()));

        panel.add(new JLabel(tr("Calibration bundle exported:")), GBC.eol());
        panel.add(path, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(copyFile, GBC.std());
        panel.add(copyFolder, GBC.eol());
        JOptionPane.showMessageDialog(MainApplication.getMainFrame(), panel, tr("WayHeatmapTracer"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void copy(String value) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

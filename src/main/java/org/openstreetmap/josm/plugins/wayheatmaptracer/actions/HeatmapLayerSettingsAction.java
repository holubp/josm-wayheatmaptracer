package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedImageryService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.HeatmapSettingsDialog;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;
import org.openstreetmap.josm.tools.Shortcut;

public class HeatmapLayerSettingsAction extends JosmAction {
    public HeatmapLayerSettingsAction() {
        super(
            tr("Heatmap Layer Settings"),
            null,
            tr("Configure and refresh the plugin-managed heatmap imagery layer"),
            Shortcut.registerShortcut(
                "wayheatmaptracer:settings",
                tr("WayHeatmapTracer: Heatmap Layer Settings"),
                KeyEvent.VK_U,
                Shortcut.CTRL_SHIFT
            ),
            false
        );
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        HeatmapSettingsDialog dialog = new HeatmapSettingsDialog(MainApplication.getMainFrame());
        if (!dialog.showDialog()) {
            return;
        }

        ManagedHeatmapConfig config = PluginPreferences.load();
        if (!config.hasManagedAccessValues()) {
            PluginLog.verbose("Saved settings without managed access values; using manual layer selection and regex fallback only.");
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Settings saved. No managed heatmap layer was refreshed because access values are not configured."),
                tr("WayHeatmapTracer"),
                JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        try {
            ManagedImageryService.applyOrUpdateManagedLayer();
            PluginLog.verbose("Managed heatmap layer refreshed.");
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Heatmap layer settings saved and the managed layer has been refreshed. Tile access will be checked against the selected area when sliding."),
                tr("WayHeatmapTracer"),
                JOptionPane.INFORMATION_MESSAGE
            );
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Failed to refresh the managed heatmap layer: {0}", ex.getMessage()),
                tr("WayHeatmapTracer"),
                JOptionPane.ERROR_MESSAGE
            );
        }
    }
}

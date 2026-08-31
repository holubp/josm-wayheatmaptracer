package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.AggregateIntensityLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedImageryService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CancellationToken;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.SelectedSourceHealthProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.SelectedSourceProbeResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileCachePolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.HeatmapSettingsDialog;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Opens plugin settings and refreshes the managed Strava heatmap layer when access values are configured.
 */
public class HeatmapLayerSettingsAction extends JosmAction {
    /**
     * Creates the settings action and registers its keyboard shortcut.
     */
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
        ManagedTileRuntime.updateConfig(config);
        if (!config.hasManagedAccessValues()) {
            AggregateIntensityLayer.applyOrUpdateManagedLayer(config, null);
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
            ImageryLayer managedLayer = ManagedImageryService.applyOrUpdateManagedLayer();
            AggregateIntensityLayer.applyOrUpdateManagedLayer(config, managedLayer);
            PluginLog.verbose("Managed heatmap layer refreshed.");
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Heatmap layer settings saved and the managed layer has been refreshed. The selected source will now be checked near the map center."),
                tr("WayHeatmapTracer"),
                JOptionPane.INFORMATION_MESSAGE
            );
            launchSelectedSourceProbe(config);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Failed to refresh the managed heatmap layer. Review the redacted diagnostics for details."),
                tr("WayHeatmapTracer"),
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void launchSelectedSourceProbe(ManagedHeatmapConfig config) {
        if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
            PluginLog.verbose("Selected-source access check not run because no map view is open.");
            return;
        }
        MapView mapView = MainApplication.getMap().mapView;
        LatLon location = mapView.getLatLon(mapView.getWidth() / 2, mapView.getHeight() / 2);
        CancellationToken cancellation = new CancellationToken();
        new SelectedSourceHealthProbe(ManagedTileRuntime.coordinator())
            .probe(config, location, TileCachePolicy.USE_CACHE, cancellation)
            .whenComplete((result, error) -> SwingUtilities.invokeLater(() -> showProbeResult(result, error)));
    }

    private void showProbeResult(SelectedSourceProbeResult result, Throwable error) {
        String message = error == null && result != null
            ? result.message()
            : tr("Selected-source check failed safely; see the redacted diagnostics.");
        int messageType = result != null && result.available()
            ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE;
        JOptionPane.showMessageDialog(MainApplication.getMainFrame(), message,
            tr("WayHeatmapTracer source check"), messageType);
    }
}

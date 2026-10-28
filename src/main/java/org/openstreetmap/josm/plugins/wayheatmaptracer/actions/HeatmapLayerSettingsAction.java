package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

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

        runSavedSettings(true, PluginPreferences.load(), new SettingsHost() {
            public void updateRuntime(ManagedHeatmapConfig config) { ManagedTileRuntime.updateConfig(config); }
            public void refresh(ManagedHeatmapConfig config) {
                ImageryLayer layer = config.hasManagedAccessValues()
                        ? ManagedImageryService.applyOrUpdateManagedLayer() : null;
                AggregateIntensityLayer.applyOrUpdateManagedLayer(config, layer);
                PluginLog.verbose(config.hasManagedAccessValues() ? "Managed heatmap layer refreshed."
                        : "Saved settings without managed access values; using manual layer selection and regex fallback only.");
            }
            public void probe(ManagedHeatmapConfig config) { launchSelectedSourceProbe(config); }
            public void failure() {
                JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                        tr("Failed to refresh the managed heatmap layer. Review the redacted diagnostics for details."),
                        tr("WayHeatmapTracer"), JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /** Side-effect boundary for the save/refresh workflow; inference never runs here. */
    interface SettingsHost {
        void updateRuntime(ManagedHeatmapConfig config);
        void refresh(ManagedHeatmapConfig config) throws Exception;
        void probe(ManagedHeatmapConfig config);
        void failure();
    }

    static void runSavedSettings(boolean accepted, ManagedHeatmapConfig config, SettingsHost host) {
        if (!accepted) { return; }
        host.updateRuntime(config);
        try {
            host.refresh(config);
            if (config.hasManagedAccessValues()) { host.probe(config); }
        } catch (Exception failure) {
            host.failure();
        }
    }

    private void launchSelectedSourceProbe(ManagedHeatmapConfig config) {
        if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
            PluginLog.verbose("Selected-source access check not run because no map view is open.");
            return;
        }
        MapView mapView = MainApplication.getMap().mapView;
        List<LatLon> locations = new ArrayList<>();
        for (Point point : probeStencilPixels(mapView.getWidth(), mapView.getHeight())) {
            locations.add(mapView.getLatLon(point.x, point.y));
        }
        CancellationToken cancellation = new CancellationToken();
        new SelectedSourceHealthProbe(ManagedTileRuntime.coordinator())
            .probe(config, locations, settingsProbePolicy(), cancellation)
            .whenComplete((result, error) -> dispatchProbeResult(result, error, this::showProbeResult));
    }

    static List<Point> probeStencilPixels(int width, int height) {
        int centerX = Math.max(0, width / 2);
        int centerY = Math.max(0, height / 2);
        if (width <= 1 || height <= 1) {
            return List.of(new Point(centerX, centerY));
        }
        int left = inset(width);
        int right = Math.max(left, width - 1 - left);
        int top = inset(height);
        int bottom = Math.max(top, height - 1 - top);
        return List.of(
            new Point(centerX, centerY),
            new Point(left, top),
            new Point(right, top),
            new Point(left, bottom),
            new Point(right, bottom)
        );
    }

    static TileCachePolicy settingsProbePolicy() {
        return TileCachePolicy.BYPASS_READ_ALLOW_WRITE;
    }

    static void dispatchProbeResult(SelectedSourceProbeResult result, Throwable error,
        BiConsumer<SelectedSourceProbeResult, Throwable> presenter) {
        Objects.requireNonNull(presenter, "presenter");
        SwingUtilities.invokeLater(() -> presenter.accept(result, error));
    }

    private static int inset(int size) {
        return Math.min(size - 1, Math.max(1, Math.round((size - 1) * 0.25f)));
    }

    /**
     * Chooses silent or modal presentation for a completed source probe.
     *
     * @param result structured probe result, or null when probing failed
     * @param error asynchronous probe failure, or null on normal completion
     * @return controlled message and presentation mode
     */
    static ProbePresentation probePresentation(SelectedSourceProbeResult result, Throwable error) {
        String message = error == null && result != null
            ? result.message()
            : tr("Selected-source check failed safely; see the redacted diagnostics.");
        return new ProbePresentation(message, error == null && result != null && result.available()
            ? ProbePresentationKind.NONE : ProbePresentationKind.MODAL_WARNING);
    }

    /**
     * Keeps successful checks silent and failed checks modal.
     *
     * @param result structured probe result, or null when probing failed
     * @param error asynchronous probe failure, or null on normal completion
     */
    private void showProbeResult(SelectedSourceProbeResult result, Throwable error) {
        ProbePresentation presentation = probePresentation(result, error);
        if (presentation.kind() == ProbePresentationKind.NONE) {
            PluginLog.verbose("Selected-source check succeeded without user interruption: %s",
                presentation.message());
            return;
        }
        JOptionPane.showMessageDialog(MainApplication.getMainFrame(), presentation.message(),
            tr("WayHeatmapTracer source check"), JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Presentation routing for an asynchronous selected-source probe result.
     *
     * @param message controlled diagnostic or warning text
     * @param kind whether the result is silent or requires a warning dialog
     */
    record ProbePresentation(String message, ProbePresentationKind kind) {
        ProbePresentation {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Presentation modes for the selected-source health result. */
    enum ProbePresentationKind {
        NONE,
        MODAL_WARNING
    }
}

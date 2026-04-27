package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import javax.swing.JButton;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.help.HelpUtil;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.LastSlideDebugBundle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.HeatmapLayerResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewOverlay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.MoveNodesCommand;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ReplaceWaySegmentCommand;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

public class AlignWayAction extends JosmAction {
    private final AlignmentService alignmentService = new AlignmentService();
    private final PreviewOverlay overlay = PreviewOverlay.getInstance();
    private JDialog activePreviewDialog;

    public AlignWayAction() {
        super(
            tr("Align Way to Heatmap"),
            null,
            tr("Align the selected way geometry to a heatmap imagery layer"),
            Shortcut.registerShortcut(
                "wayheatmaptracer:align",
                tr("WayHeatmapTracer: Align Way to Heatmap"),
                KeyEvent.VK_Y,
                Shortcut.CTRL_SHIFT
            ),
            true
        );
        putValue("help", HelpUtil.ht("/Plugin/WayHeatmapTracer"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (activePreviewDialog != null && activePreviewDialog.isDisplayable()) {
            activePreviewDialog.toFront();
            return;
        }
        PluginLog.beginSlideSession();
        try {
            PluginLog.verbose("Align Way to Heatmap invoked.");
            DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
            if (dataSet == null) {
                showError(tr("No editable data layer is active."));
                return;
            }
            if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
                showError(tr("No map view is available."));
                return;
            }

            ManagedHeatmapConfig config = PluginPreferences.load();
            SelectionContext selection = SelectionResolver.resolve(dataSet, config.adjustJunctionNodes());
            if (!config.allowUndownloadedAlignment()) {
                requireDownloadedAreaCoverage(selection, dataSet);
            } else {
                PluginLog.verbose("Downloaded-area coverage checks are disabled by settings.");
            }
            ImageryLayer imageryLayer = config.hasManagedAccessValues()
                ? HeatmapLayerResolver.resolveOptional().orElse(null)
                : HeatmapLayerResolver.resolve();
            MapView mapView = MainApplication.getMap().mapView;

            AlignmentResult result = alignmentService.align(selection, imageryLayer, mapView);
            DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(result, result.candidates().get(0), "preview-open", PluginLog.currentSlideLog()));

            config = PluginPreferences.load();
            showCandidatePreview(dataSet, selection, result, config);
        } catch (Exception ex) {
            overlay.hide();
            Logging.error(ex);
            PluginLog.verbose("Alignment failed with exception: %s", ex.toString());
            PluginLog.endSlideSession();
            showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
        }
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null);
    }

    private void showCandidatePreview(
        DataSet dataSet,
        SelectionContext selection,
        AlignmentResult result,
        ManagedHeatmapConfig config
    ) {
        if (result.candidates().isEmpty()) {
            throw new IllegalStateException(tr("No centerline candidate could be extracted from the heatmap."));
        }
        CenterlineCandidate initial = result.candidates().get(0);
        PreviewSelection[] current = {new PreviewSelection(initial, alignmentService.applyCandidate(result, initial))};
        overlay.show(selection, current[0].result(), initial, PluginPreferences.isDebugEnabled());
        JComboBox<CenterlineCandidate> comboBox = new JComboBox<>();
        comboBox.setModel(new DefaultComboBoxModel<>(result.candidates().toArray(CenterlineCandidate[]::new)));
        comboBox.setSelectedItem(initial);

        JPanel panel = buildSummaryPanel(current[0].result(), initial, config, result.candidates().size() > 1 ? comboBox : null);
        comboBox.addActionListener(event -> {
            CenterlineCandidate selected = (CenterlineCandidate) comboBox.getSelectedItem();
            if (selected == null) {
                return;
            }
            current[0] = new PreviewSelection(selected, alignmentService.applyCandidate(result, selected));
            overlay.show(selection, current[0].result(), selected, PluginPreferences.isDebugEnabled());
        });

        JButton apply = new JButton(tr("Apply"));
        JButton cancel = new JButton(tr("Cancel"));
        JPanel buttons = new JPanel();
        buttons.add(apply);
        buttons.add(cancel);
        panel.add(buttons, GBC.eol());

        JDialog dialog = new JDialog(MainApplication.getMainFrame(), tr("Preview Heatmap Alignment"), false);
        activePreviewDialog = dialog;
        dialog.setContentPane(panel);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(MainApplication.getMainFrame());
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                activePreviewDialog = null;
            }

            @Override
            public void windowClosing(WindowEvent e) {
                cancelPreview(current[0]);
            }
        });
        apply.addActionListener(event -> {
            try {
                applyPreview(dataSet, selection, current[0], config);
                dialog.dispose();
            } catch (Exception ex) {
                Logging.error(ex);
                PluginLog.verbose("Alignment apply failed with exception: %s", ex.toString());
                DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(current[0].result(), current[0].candidate(), "apply-failed", PluginLog.currentSlideLog()));
                showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
            } finally {
                overlay.hide();
                PluginLog.endSlideSession();
            }
        });
        cancel.addActionListener(event -> {
            cancelPreview(current[0]);
            dialog.dispose();
        });
        dialog.setVisible(true);
    }

    private void cancelPreview(PreviewSelection preview) {
        PluginLog.verbose("Alignment cancelled at preview dialog.");
        DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(preview.result(), preview.candidate(), "cancelled", PluginLog.currentSlideLog()));
        overlay.hide();
        PluginLog.endSlideSession();
    }

    private void applyPreview(DataSet dataSet, SelectionContext selection, PreviewSelection preview, ManagedHeatmapConfig config) {
        CenterlineCandidate chosen = preview.candidate();
        AlignmentResult chosenResult = preview.result();

        if (!config.allowUndownloadedAlignment()) {
            requirePreviewWithinDownloadedArea(chosenResult.previewPolyline(), dataSet);
        }

        AlignmentMode effectiveMode = AlignmentService.effectiveAlignmentMode(selection, config);
        if (effectiveMode == AlignmentMode.MOVE_EXISTING_NODES
            && chosenResult.nodeMoves().isEmpty()) {
            throw new IllegalStateException(tr("No movable interior nodes were found in the selected segment."));
        }

        if (effectiveMode == AlignmentMode.MOVE_EXISTING_NODES) {
            PluginLog.verbose("Applying move-existing-nodes alignment for candidate %s with %d node moves.", chosen.id(), chosenResult.nodeMoves().size());
            UndoRedoHandler.getInstance().add(new MoveNodesCommand(
                dataSet,
                chosenResult.nodeMoves(),
                tr("Align way to heatmap")
            ));
        } else {
            PluginLog.verbose("Applying precise-shape alignment for candidate %s with %d preview points.", chosen.id(), chosenResult.previewPolyline().size());
            UndoRedoHandler.getInstance().add(new ReplaceWaySegmentCommand(
                dataSet,
                selection.way(),
                selection,
                chosenResult.previewPolyline(),
                tr("Align way to heatmap precisely")
            ));
        }
        DiagnosticsRegistry.setLastBundle(LastSlideDebugBundle.fromResult(chosenResult, chosen, "applied", PluginLog.currentSlideLog()));
    }

    private JPanel buildSummaryPanel(AlignmentResult result, CenterlineCandidate chosen, ManagedHeatmapConfig config, JComboBox<CenterlineCandidate> candidates) {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        if (candidates != null) {
            panel.add(new JLabel(tr("Detected ridge")), GBC.std());
            panel.add(candidates, GBC.eol().fill(GBC.HORIZONTAL));
            panel.add(new JLabel(tr("Changing the ridge updates the map preview immediately.")), GBC.eol());
        } else {
            panel.add(new JLabel(tr("Candidate: {0}", chosen.toString())), GBC.eol());
        }
        AlignmentMode effectiveMode = AlignmentService.effectiveAlignmentMode(result.selection(), config);
        String modeLabel = effectiveMode == config.alignmentMode()
            ? config.alignmentMode().displayName()
            : tr("{0} (automatic for rough sketch)", effectiveMode.displayName());
        panel.add(new JLabel(tr("Mode: {0}", modeLabel)), GBC.eol());
        panel.add(new JLabel(tr("Junction/end nodes: {0}", config.adjustJunctionNodes() ? "adjustable" : "fixed")), GBC.eol());
        panel.add(new JLabel(tr("Simplification: {0}", config.simplifyEnabled() ? "enabled" : "disabled")), GBC.eol());
        panel.add(new JLabel(tr("Diagnostics file can be exported from More tools.")), GBC.eol());
        if (PluginPreferences.isDebugEnabled()) {
            panel.add(new JLabel(tr("Debug overlay is enabled.")), GBC.eol());
        }
        panel.add(new JLabel(tr("Preview legend: solid blue = selected result; orange dashed = original; dashed labeled lines = other detected ridges.")), GBC.eol());
        return panel;
    }

    private record PreviewSelection(CenterlineCandidate candidate, AlignmentResult result) {
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(
            MainApplication.getMainFrame(),
            message,
            tr("WayHeatmapTracer"),
            JOptionPane.ERROR_MESSAGE
        );
    }

    private void requireDownloadedAreaCoverage(SelectionContext selection, DataSet dataSet) {
        List<Bounds> bounds = dataSet.getDataSourceBounds();
        if (bounds == null || bounds.isEmpty()) {
            throw new IllegalStateException("This data layer has no downloaded area metadata. Download the area in JOSM before aligning ways.");
        }
        for (org.openstreetmap.josm.data.osm.Node node : selection.segmentNodes()) {
            LatLon point = node.getCoor();
            if (point == null || !isWithinDownloadedBounds(point, bounds)) {
                throw new IllegalStateException("Selected segment extends outside the downloaded area. Download a larger area first.");
            }
        }
    }

    private void requirePreviewWithinDownloadedArea(List<EastNorth> preview, DataSet dataSet) {
        List<Bounds> bounds = dataSet.getDataSourceBounds();
        if (bounds == null || bounds.isEmpty()) {
            return;
        }
        for (EastNorth point : preview) {
            LatLon latLon = org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection().eastNorth2latlon(point);
            if (!isWithinDownloadedBounds(latLon, bounds)) {
                throw new IllegalStateException("Aligned geometry would extend outside the downloaded area. Download a larger area first.");
            }
        }
    }

    private boolean isWithinDownloadedBounds(LatLon point, List<Bounds> bounds) {
        for (Bounds bound : bounds) {
            if (bound.contains(point)) {
                return true;
            }
        }
        return false;
    }
}

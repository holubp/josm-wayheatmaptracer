package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.HeatmapLayerResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
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
            ImageryLayer imageryLayer = HeatmapLayerResolver.resolve();
            MapView mapView = MainApplication.getMap().mapView;

            AlignmentResult result = alignmentService.align(selection, imageryLayer, mapView);
            DiagnosticsRegistry.setLastBundle(result.diagnostics().toJson());

            CenterlineCandidate chosen = chooseCandidate(result.candidates());
            if (chosen == null) {
                PluginLog.verbose("Alignment cancelled before candidate selection was applied.");
                return;
            }
            AlignmentResult chosenResult = alignmentService.applyCandidate(result, chosen);
            config = PluginPreferences.load();
            if (!config.allowUndownloadedAlignment()) {
                requirePreviewWithinDownloadedArea(chosenResult.previewPolyline(), dataSet);
            }

            overlay.show(selection, chosenResult, chosen, PluginPreferences.isDebugEnabled());
            try {
                int answer = JOptionPane.showConfirmDialog(
                    MainApplication.getMainFrame(),
                    buildSummaryPanel(chosenResult, chosen, config),
                    tr("Apply Heatmap Alignment"),
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE
                );
                if (answer != JOptionPane.OK_OPTION) {
                    PluginLog.verbose("Alignment cancelled at preview dialog.");
                    return;
                }

                if (config.alignmentMode() == org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.MOVE_EXISTING_NODES
                    && chosenResult.nodeMoves().isEmpty()) {
                    showError(tr("No movable interior nodes were found in the selected segment."));
                    return;
                }

                if (config.alignmentMode() == org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.MOVE_EXISTING_NODES) {
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
            } finally {
                overlay.hide();
            }
        } catch (Exception ex) {
            overlay.hide();
            Logging.error(ex);
            PluginLog.verbose("Alignment failed with exception: %s", ex.toString());
            showError(tr("WayHeatmapTracer failed: {0}", ex.getMessage()));
        }
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null);
    }

    private CenterlineCandidate chooseCandidate(List<CenterlineCandidate> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException(tr("No centerline candidate could be extracted from the heatmap."));
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        JComboBox<CenterlineCandidate> comboBox = new JComboBox<>();
        comboBox.setModel(new DefaultComboBoxModel<>(candidates.toArray(CenterlineCandidate[]::new)));

        int answer = JOptionPane.showConfirmDialog(
            MainApplication.getMainFrame(),
            comboBox,
            tr("Choose Heatmap Ridge"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        );
        return answer == JOptionPane.OK_OPTION ? (CenterlineCandidate) comboBox.getSelectedItem() : null;
    }

    private JPanel buildSummaryPanel(AlignmentResult result, CenterlineCandidate chosen, ManagedHeatmapConfig config) {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        panel.add(new JLabel(tr("Mode: {0}", config.alignmentMode().displayName())), GBC.eol());
        panel.add(new JLabel(tr("Candidate: {0}", chosen.toString())), GBC.eol());
        panel.add(new JLabel(tr("Preview points: {0}", Integer.toString(result.previewPolyline().size()))), GBC.eol());
        panel.add(new JLabel(tr("Junction/end nodes: {0}", config.adjustJunctionNodes() ? "adjustable" : "fixed")), GBC.eol());
        panel.add(new JLabel(tr("Simplification: {0}", config.simplifyEnabled() ? "enabled" : "disabled")), GBC.eol());
        if (config.alignmentMode() == org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.MOVE_EXISTING_NODES) {
            panel.add(new JLabel(tr("Movable nodes: {0}", Integer.toString(result.nodeMoves().size()))), GBC.eol());
        } else {
            SegmentChangeEstimate estimate = estimateSegmentChanges(result);
            panel.add(new JLabel(tr("Existing interior nodes reused: {0}", Integer.toString(estimate.reused()))), GBC.eol());
            panel.add(new JLabel(tr("Nodes added: {0}", Integer.toString(estimate.added()))), GBC.eol());
            panel.add(new JLabel(tr("Interior nodes removed: {0}", Integer.toString(estimate.removed()))), GBC.eol());
        }
        panel.add(new JLabel(tr("Diagnostics file can be exported from More tools.")), GBC.eol());
        if (PluginPreferences.isDebugEnabled()) {
            panel.add(new JLabel(tr("Debug overlay is enabled.")), GBC.eol());
        }
        return panel;
    }

    private SegmentChangeEstimate estimateSegmentChanges(AlignmentResult result) {
        int fixedAnchors = result.selection().fixedNodes().size();
        int previewMutable = Math.max(0, result.previewPolyline().size() - fixedAnchors);
        int mutableExisting = 0;
        for (org.openstreetmap.josm.data.osm.Node node : result.selection().segmentNodes()) {
            if (!result.selection().fixedNodes().contains(node)) {
                mutableExisting++;
            }
        }
        int reused = Math.min(previewMutable, mutableExisting);
        int added = Math.max(0, previewMutable - mutableExisting);
        int removed = Math.max(0, mutableExisting - previewMutable);
        return new SegmentChangeEstimate(reused, added, removed);
    }

    private record SegmentChangeEstimate(int reused, int added, int removed) {
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

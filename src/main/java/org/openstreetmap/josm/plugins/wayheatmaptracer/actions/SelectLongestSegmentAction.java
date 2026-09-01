package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.help.HelpUtil;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.WaySegmentRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.JunctionSegmentSelector;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Selects the longest eligible non-branching way segment, optionally constrained by one selected hint node.
 */
public final class SelectLongestSegmentAction extends JosmAction {
    /** Pure selector for the requested junction-bounded segment. */
    private final JunctionSegmentSelector selector = new JunctionSegmentSelector();

    /**
     * Creates the segment-selection action and registers it in the plugin menu.
     */
    public SelectLongestSegmentAction() {
        super(
            tr("Select Longest Heatmap Segment"),
            null,
            tr("Select the longest non-branching part of the selected way, or the longest such part containing one selected node"),
            Shortcut.registerShortcut(
                "wayheatmaptracer:select-longest-segment",
                tr("WayHeatmapTracer: Select Longest Heatmap Segment"),
                KeyEvent.CHAR_UNDEFINED,
                Shortcut.NONE
            ),
            true
        );
        putValue("help", HelpUtil.ht("/Plugin/WayHeatmapTracer"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null) {
            showError(tr("No editable data layer is active."));
            return;
        }
        SelectionRequest request;
        WaySegmentRange range;
        try {
            request = selectionRequest(dataSet);
            range = request.selectRange(selector);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            showError(ex.getMessage());
            return;
        }
        Way way = request.way();
        Node start = way.getNode(range.startIndex());
        Node end = way.getNode(range.endIndex());
        dataSet.setSelected(List.of(way, start, end));
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null);
    }

    /**
     * Validates the exact selection shapes supported by this preprocessing action.
     *
     * @param dataSet active editable dataset
     * @return selected way and optional single node hint
     * @throws IllegalStateException when any extra or unsupported primitive is selected
     */
    static SelectionRequest selectionRequest(DataSet dataSet) {
        int selectedNodeCount = dataSet.getSelectedNodes().size();
        if (dataSet.getSelectedWays().size() != 1
            || selectedNodeCount > 1
            || dataSet.getAllSelected().size() != 1 + selectedNodeCount) {
            throw new IllegalStateException(
                "Select exactly one way, optionally together with one node on that way.");
        }
        Way way = dataSet.getSelectedWays().iterator().next();
        Node hint = selectedNodeCount == 1 ? dataSet.getSelectedNodes().iterator().next() : null;
        return new SelectionRequest(way, hint);
    }

    /** Pure validated request used before changing the JOSM selection. */
    record SelectionRequest(Way way, Node hintNode) {
        /**
         * Resolves this request without mutating the dataset or its selection.
         *
         * @param selector segment selector
         * @return eligible maximal segment requested by the current selection
         */
        WaySegmentRange selectRange(JunctionSegmentSelector selector) {
            return hintNode == null
                ? selector.longestJunctionBoundedSegment(way)
                : selector.longestJunctionBoundedSegmentContaining(way, hintNode);
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(
            MainApplication.getMainFrame(),
            message,
            tr("WayHeatmapTracer"),
            JOptionPane.ERROR_MESSAGE
        );
    }
}

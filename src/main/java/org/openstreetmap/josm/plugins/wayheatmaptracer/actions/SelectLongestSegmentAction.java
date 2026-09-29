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
            tr("Select the longest non-branching segment of a way, optionally containing a selected node; a node alone works when its way is unambiguous"),
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
     * Resolves and validates the supported way-only, way-plus-node, and uniquely inferable node-only selections.
     *
     * @param dataSet active editable dataset
     * @return containing way and optional single node hint
     * @throws IllegalStateException when the selection is unsupported or a node-only selection is ambiguous,
     *     orphaned, or has incomplete way geometry
     */
    static SelectionRequest selectionRequest(DataSet dataSet) {
        int selectedWayCount = dataSet.getSelectedWays().size();
        int selectedNodeCount = dataSet.getSelectedNodes().size();
        if (dataSet.getAllSelected().size() != selectedWayCount + selectedNodeCount) {
            throw new IllegalStateException(
                "Select exactly one way, optionally with one node, or select one node whose way is unambiguous.");
        }
        if (selectedWayCount == 1 && selectedNodeCount <= 1) {
            Way way = dataSet.getSelectedWays().iterator().next();
            Node hint = selectedNodeCount == 1 ? dataSet.getSelectedNodes().iterator().next() : null;
            return new SelectionRequest(way, hint);
        }
        if (selectedWayCount == 0 && selectedNodeCount == 1) {
            Node hint = dataSet.getSelectedNodes().iterator().next();
            List<Way> liveReferrers = hint.getReferrers().stream()
                .filter(Way.class::isInstance)
                .map(Way.class::cast)
                .filter(way -> way.getDataSet() == dataSet && !way.isDeleted())
                .toList();
            if (liveReferrers.isEmpty()) {
                throw new IllegalStateException(
                    "The selected node does not belong to a live way in the active data layer.");
            }
            if (liveReferrers.size() > 1) {
                throw new IllegalStateException(
                    "The selected node belongs to more than one live way. Select the way too to choose which one.");
            }
            Way way = liveReferrers.get(0);
            if (way.isIncomplete() || way.hasIncompleteNodes() || way.getNodesCount() < 2
                || way.getNodes().stream().anyMatch(node -> node.isIncomplete() || !node.isLatLonKnown())) {
                throw new IllegalStateException(
                    "The selected node's way has incomplete geometry. Complete the way and its node coordinates first.");
            }
            return new SelectionRequest(way, hint);
        }
        throw new IllegalStateException(
            "Select exactly one way, optionally with one node, or select one node whose way is unambiguous.");
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

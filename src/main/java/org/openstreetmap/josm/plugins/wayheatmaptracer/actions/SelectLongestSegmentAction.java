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

public final class SelectLongestSegmentAction extends JosmAction {
    private final JunctionSegmentSelector selector = new JunctionSegmentSelector();

    public SelectLongestSegmentAction() {
        super(
            tr("Select Longest Heatmap Segment"),
            null,
            tr("Select the longest part of the selected way bounded by endpoints or junctions"),
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
        if (dataSet.getSelectedWays().size() != 1) {
            showError(tr("Select exactly one way."));
            return;
        }

        Way way = dataSet.getSelectedWays().iterator().next();
        WaySegmentRange range = selector.longestJunctionBoundedSegment(way);
        Node start = way.getNode(range.startIndex());
        Node end = way.getNode(range.endIndex());
        dataSet.setSelected(List.of(way, start, end));
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null);
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

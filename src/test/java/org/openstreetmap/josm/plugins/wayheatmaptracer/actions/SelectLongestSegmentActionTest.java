package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Verifies the pure selection-shape bridge used by the segment-selection action. */
class SelectLongestSegmentActionTest {
    @BeforeAll
    static void setPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void acceptsOneWayWithZeroOrOneSelectedNode() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node hint = node(0.001);
        Node end = node(0.002);
        Way way = way(start, hint, end);
        add(dataSet, way);

        dataSet.setSelected(List.of(way));
        SelectLongestSegmentAction.SelectionRequest global = SelectLongestSegmentAction.selectionRequest(dataSet);
        dataSet.setSelected(List.of(way, hint));
        SelectLongestSegmentAction.SelectionRequest hinted = SelectLongestSegmentAction.selectionRequest(dataSet);

        assertSame(way, global.way());
        assertNull(global.hintNode());
        assertSame(way, hinted.way());
        assertSame(hint, hinted.hintNode());
    }

    @Test
    void rejectsTwoNodesRelationOrAnotherWayInsteadOfIgnoringThem() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node middle = node(0.001);
        Node end = node(0.002);
        Way way = way(start, middle, end);
        Way other = way(node(0.003), node(0.004));
        Relation relation = new Relation();
        add(dataSet, way);
        add(dataSet, other);
        dataSet.addPrimitive(relation);

        dataSet.setSelected(List.of(way, start, end));
        assertThrows(IllegalStateException.class, () -> SelectLongestSegmentAction.selectionRequest(dataSet));
        dataSet.setSelected(List.of(way, relation));
        assertThrows(IllegalStateException.class, () -> SelectLongestSegmentAction.selectionRequest(dataSet));
        dataSet.setSelected(List.of(way, other));
        assertThrows(IllegalStateException.class, () -> SelectLongestSegmentAction.selectionRequest(dataSet));
        dataSet.clearSelection();
        assertThrows(IllegalStateException.class, () -> SelectLongestSegmentAction.selectionRequest(dataSet));
    }

    @Test
    void requestRejectsHintOutsideWayWithoutMutatingSelection() {
        DataSet dataSet = new DataSet();
        Way way = way(node(0.0), node(0.001));
        Node outside = node(0.002);
        add(dataSet, way);
        dataSet.addPrimitive(outside);
        dataSet.setSelected(List.of(way, outside));
        List<?> before = List.copyOf(dataSet.getAllSelected());

        SelectLongestSegmentAction.SelectionRequest request = SelectLongestSegmentAction.selectionRequest(dataSet);

        assertThrows(IllegalArgumentException.class,
            () -> request.selectRange(new org.openstreetmap.josm.plugins.wayheatmaptracer.service.JunctionSegmentSelector()));
        org.junit.jupiter.api.Assertions.assertEquals(before, List.copyOf(dataSet.getAllSelected()));
    }

    private static void add(DataSet dataSet, Way way) {
        for (Node node : way.getNodes()) {
            if (node.getDataSet() == null) {
                dataSet.addPrimitive(node);
            }
        }
        dataSet.addPrimitive(way);
    }

    private static Way way(Node... nodes) {
        Way way = new Way();
        way.setNodes(List.of(nodes));
        return way;
    }

    private static Node node(double lon) {
        return new Node(new LatLon(0.0, lon));
    }
}

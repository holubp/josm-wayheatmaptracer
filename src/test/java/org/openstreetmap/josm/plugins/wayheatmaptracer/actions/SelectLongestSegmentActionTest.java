package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void infersUniqueContainingWayFromOneSelectedNode() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node firstJunction = node(0.001);
        Node middle = node(0.004);
        Node secondJunction = node(0.007);
        Node end = node(0.008);
        Way main = way(start, firstJunction, middle, secondJunction, end);
        Way firstBranch = way(firstJunction, node(0.0011));
        Way secondBranch = way(secondJunction, node(0.0071));
        add(dataSet, main);
        add(dataSet, firstBranch);
        add(dataSet, secondBranch);
        dataSet.setSelected(List.of(start));

        SelectLongestSegmentAction.SelectionRequest request = SelectLongestSegmentAction.selectionRequest(dataSet);
        var range = request.selectRange(new org.openstreetmap.josm.plugins.wayheatmaptracer.service.JunctionSegmentSelector());

        assertSame(main, request.way());
        assertSame(start, request.hintNode());
        assertEquals(0, range.startIndex());
        assertEquals(1, range.endIndex());
    }

    @Test
    void explicitWayAndSharedNodeStillSelectsLongerAdjacentSpan() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node junction = node(0.001);
        Node middle = node(0.004);
        Node farJunction = node(0.007);
        Node end = node(0.008);
        Way main = way(start, junction, middle, farJunction, end);
        add(dataSet, main);
        add(dataSet, way(junction, node(0.0011)));
        add(dataSet, way(farJunction, node(0.0071)));
        dataSet.setSelected(List.of(main, junction));

        var range = SelectLongestSegmentAction.selectionRequest(dataSet)
            .selectRange(new org.openstreetmap.josm.plugins.wayheatmaptracer.service.JunctionSegmentSelector());

        assertEquals(1, range.startIndex());
        assertEquals(3, range.endIndex());
    }

    @Test
    void rejectsNodeOnlySelectionWhenMultipleLiveWaysReferToNode() {
        DataSet dataSet = new DataSet();
        Node shared = node(0.0);
        Way first = way(shared, node(0.001));
        Way second = way(shared, node(0.002));
        add(dataSet, first);
        add(dataSet, second);
        dataSet.setSelected(List.of(shared));
        List<?> before = List.copyOf(dataSet.getAllSelected());

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> SelectLongestSegmentAction.selectionRequest(dataSet));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("Select the way"));
        assertEquals(before, List.copyOf(dataSet.getAllSelected()));
    }

    @Test
    void rejectsOrphanNodeSelection() {
        DataSet dataSet = new DataSet();
        Node orphan = node(0.0);
        dataSet.addPrimitive(orphan);
        dataSet.setSelected(List.of(orphan));

        assertThrows(IllegalStateException.class, () -> SelectLongestSegmentAction.selectionRequest(dataSet));
    }

    @Test
    void inferredHintStillRejectsRepeatedOccurrenceInItsWay() {
        DataSet dataSet = new DataSet();
        Node repeated = node(0.0);
        Way way = way(repeated, node(0.001), repeated, node(0.002));
        add(dataSet, way);
        dataSet.setSelected(List.of(repeated));

        SelectLongestSegmentAction.SelectionRequest request = SelectLongestSegmentAction.selectionRequest(dataSet);

        assertThrows(IllegalArgumentException.class,
            () -> request.selectRange(new org.openstreetmap.josm.plugins.wayheatmaptracer.service.JunctionSegmentSelector()));
    }

    @Test
    void rejectsNodeOnlySelectionWithExtraPrimitive() {
        DataSet dataSet = new DataSet();
        Node hint = node(0.0);
        Way way = way(hint, node(0.001));
        Relation relation = new Relation();
        add(dataSet, way);
        dataSet.addPrimitive(relation);
        dataSet.setSelected(List.of(hint, relation));

        assertThrows(IllegalStateException.class, () -> SelectLongestSegmentAction.selectionRequest(dataSet));
    }

    @Test
    void ignoresDeletedWayReferrersButRejectsIncompleteLiveGeometry() {
        DataSet dataSet = new DataSet();
        Node hint = node(0.0);
        Way live = way(hint, node(0.001));
        Way deleted = way(hint, node(0.002));
        add(dataSet, live);
        add(dataSet, deleted);
        deleted.setDeleted(true);
        dataSet.setSelected(List.of(hint));

        assertSame(live, SelectLongestSegmentAction.selectionRequest(dataSet).way());

        Node unresolved = new Node(9001);
        Way incomplete = way(hint, unresolved);
        add(dataSet, incomplete);
        dataSet.setSelected(List.of(hint));
        assertThrows(IllegalStateException.class, () -> SelectLongestSegmentAction.selectionRequest(dataSet));
    }

    @Test
    void rejectsUniqueIncompleteWayAsInferenceTarget() {
        DataSet incompleteData = new DataSet();
        Node hint = node(0.0);
        Way incomplete = way(hint, new Node(9002));
        add(incompleteData, incomplete);
        incompleteData.setSelected(List.of(hint));
        assertThrows(IllegalStateException.class,
            () -> SelectLongestSegmentAction.selectionRequest(incompleteData));
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

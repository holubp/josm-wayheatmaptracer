package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class SelectionResolverTest {
    @BeforeAll
    static void setPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void fixesJunctionsByDefaultButCanAllowThemToMove() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node junction = node(0.001);
        Node end = node(0.002);
        Node branchEnd = node(0.003);
        for (Node node : List.of(start, junction, end, branchEnd)) {
            dataSet.addPrimitive(node);
        }
        Way way = way(start, junction, end);
        Way branch = way(junction, branchEnd);
        dataSet.addPrimitive(way);
        dataSet.addPrimitive(branch);
        dataSet.setSelected(List.of(way));

        SelectionContext protectedContext = SelectionResolver.resolve(dataSet, false);
        SelectionContext adjustableContext = SelectionResolver.resolve(dataSet, true);

        assertTrue(protectedContext.fixedNodes().contains(start));
        assertTrue(protectedContext.fixedNodes().contains(junction));
        assertTrue(protectedContext.fixedNodes().contains(end));
        assertFalse(adjustableContext.fixedNodes().contains(start));
        assertFalse(adjustableContext.fixedNodes().contains(junction));
        assertFalse(adjustableContext.fixedNodes().contains(end));
    }

    @Test
    void rejectsSelectedSegmentsWithRepeatedNodeInsideSegment() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node middle = node(0.001);
        Node end = node(0.002);
        for (Node node : List.of(start, middle, end)) {
            dataSet.addPrimitive(node);
        }
        Way way = way(start, middle, end, middle);
        dataSet.addPrimitive(way);
        dataSet.setSelected(List.of(way));

        assertThrows(IllegalStateException.class, () -> SelectionResolver.resolve(dataSet, false));
    }

    @Test
    void rejectsSelectedSegmentsWithNodeRepeatedOutsideSegment() {
        DataSet dataSet = new DataSet();
        Node before = node(0.0);
        Node start = node(0.001);
        Node middle = node(0.002);
        Node end = node(0.003);
        for (Node node : List.of(before, start, middle, end)) {
            dataSet.addPrimitive(node);
        }
        Way way = way(before, start, middle, end, middle);
        dataSet.addPrimitive(way);
        dataSet.setSelected(List.of(way, start, end));

        assertThrows(IllegalStateException.class, () -> SelectionResolver.resolve(dataSet, false));
    }

    @Test
    void stillRejectsOneNodeSelectionForSliding() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node middle = node(0.001);
        Node end = node(0.002);
        for (Node node : List.of(start, middle, end)) {
            dataSet.addPrimitive(node);
        }
        Way way = way(start, middle, end);
        dataSet.addPrimitive(way);
        dataSet.setSelected(List.of(way, middle));

        assertThrows(IllegalStateException.class, () -> SelectionResolver.resolve(dataSet, false));
    }

    @Test
    void previewIntegrityRejectsMovedSourceNode() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node middle = node(0.001);
        Node end = node(0.002);
        for (Node node : List.of(start, middle, end)) {
            dataSet.addPrimitive(node);
        }
        Way way = way(start, middle, end);
        dataSet.addPrimitive(way);
        dataSet.setSelected(List.of(way));
        SelectionContext selection = SelectionResolver.resolve(dataSet, false);
        List<EastNorth> source = selection.segmentNodes().stream()
            .map(node -> node.getEastNorth(ProjectionRegistry.getProjection()))
            .toList();

        middle.setCoor(new LatLon(0.0, 0.01));

        assertThrows(IllegalStateException.class,
            () -> SelectionIntegrity.requirePreviewSourceUnchanged(dataSet, selection, source));
    }

    @Test
    void occurrenceIndexMatchesRepeatedNodeSafetyByIdentity() {
        Node repeated = node(0.001);
        Node sameCoordinateDifferentIdentity = node(0.001);
        Way way = way(node(0.0), repeated, sameCoordinateDifferentIdentity, node(0.002), repeated);
        SelectionIntegrity.NodeOccurrenceIndex occurrences = SelectionIntegrity.occurrenceIndex(way);

        assertFalse(occurrences.rangeIsUnambiguous(0, 1));
        assertTrue(occurrences.rangeIsUnambiguous(2, 3));
        assertThrows(IllegalStateException.class,
            () -> SelectionIntegrity.requireNoRepeatedNodeOccurrences(way, 0, 1));
    }

    @Test
    void selectorOutputIsImmediatelyAcceptedByResolverForGlobalAndHintedModes() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node junction = node(0.002);
        Node hinted = node(0.003);
        Node secondJunction = node(0.004);
        Node end = node(0.020);
        Node branchEnd1 = node(0.0025);
        Node branchEnd2 = node(0.0045);
        for (Node node : List.of(start, junction, hinted, secondJunction, end, branchEnd1, branchEnd2)) {
            dataSet.addPrimitive(node);
        }
        Way way = way(start, junction, hinted, secondJunction, end);
        dataSet.addPrimitive(way);
        dataSet.addPrimitive(way(junction, branchEnd1));
        dataSet.addPrimitive(way(secondJunction, branchEnd2));
        JunctionSegmentSelector selector = new JunctionSegmentSelector();

        assertResolverRange(dataSet, way, selector.longestJunctionBoundedSegment(way));
        assertResolverRange(dataSet, way,
            selector.longestJunctionBoundedSegmentContaining(way, hinted));
    }

    private static void assertResolverRange(
        DataSet dataSet,
        Way way,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.WaySegmentRange range
    ) {
        dataSet.setSelected(List.of(way, way.getNode(range.startIndex()), way.getNode(range.endIndex())));

        SelectionContext resolved = SelectionResolver.resolve(dataSet, false);

        assertEquals(range.startIndex(), resolved.startIndex());
        assertEquals(range.endIndex(), resolved.endIndex());
        assertTrue(resolved.fixedNodes().contains(way.getNode(range.startIndex())));
        assertTrue(resolved.fixedNodes().contains(way.getNode(range.endIndex())));
        for (int i = range.startIndex() + 1; i < range.endIndex(); i++) {
            assertTrue(way.getNode(i).referrers(Way.class).count() <= 1,
                "Returned range must not contain a shared-way node in its open interior");
        }
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

package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

class ReplaceWaySegmentCommandTest {
    @BeforeAll
    static void setProjection() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void removesOnlyUntaggedUnreferencedDroppedNodes() {
        Fixture fixture = fixture();
        fixture.droppedTagged.put("traffic_calming", "table");
        List<EastNorth> preview = List.of(
            eastNorth(fixture.start),
            eastNorth(fixture.reused),
            eastNorth(fixture.end)
        );

        ReplaceWaySegmentCommand command = new ReplaceWaySegmentCommand(
            fixture.dataSet,
            fixture.way,
            fixture.selection,
            preview,
            "test"
        );

        command.executeCommand();

        assertEquals(List.of(fixture.start, fixture.reused, fixture.droppedTagged, fixture.end), fixture.way.getNodes());
        assertNotNull(fixture.droppedTagged.getDataSet(), "Tagged dropped nodes must survive cleanup");
        assertNull(fixture.droppedPlain.getDataSet(), "Untagged unreferenced dropped nodes should be removed");
    }

    @Test
    void movableSharedJunctionIsAdjustedLocallyInsteadOfReusedAtArbitraryPreviewIndex() {
        DataSet dataSet = new DataSet();
        Node start = nodeAtEastNorth(0.0, 0.0);
        Node first = nodeAtEastNorth(100.0, 0.0);
        Node junction = nodeAtEastNorth(200.0, 0.0);
        Node second = nodeAtEastNorth(300.0, 0.0);
        Node end = nodeAtEastNorth(400.0, 0.0);
        Node side = nodeAtEastNorth(200.0, 100.0);
        for (Node node : List.of(start, first, junction, second, end, side)) {
            dataSet.addPrimitive(node);
        }
        Way way = new Way();
        way.setNodes(List.of(start, first, junction, second, end));
        dataSet.addPrimitive(way);
        Way sideWay = new Way();
        sideWay.setNodes(List.of(junction, side));
        dataSet.addPrimitive(sideWay);
        List<EastNorth> preview = java.util.stream.IntStream.rangeClosed(0, 40)
            .mapToObj(index -> new EastNorth(index * 10.0, 20.0))
            .toList();
        SelectionContext selection = new SelectionContext(
            way,
            0,
            4,
            List.of(start, first, junction, second, end),
            Set.of()
        );

        ReplaceWaySegmentCommand command = new ReplaceWaySegmentCommand(dataSet, way, selection, preview, "test");

        command.executeCommand();

        EastNorth movedJunction = eastNorth(junction);
        assertEquals(200.0, movedJunction.east(), 1e-6);
        assertEquals(20.0, movedJunction.north(), 1e-6);
        assertTrue(way.getNodes().contains(junction), "Shared junction node must remain part of the rebuilt way");
        assertTrue(way.getNodes().indexOf(junction) > 10, "Shared junction must not be consumed near the start of a dense preview");
    }

    @Test
    void redoReappliesTargetCoordinatesForReusedExistingNodes() {
        Fixture fixture = fixture();
        EastNorth original = eastNorth(fixture.reused);
        EastNorth target = new EastNorth(original.east() + 10.0, original.north() + 5.0);
        List<EastNorth> preview = List.of(
            eastNorth(fixture.start),
            target,
            eastNorth(fixture.end)
        );
        ReplaceWaySegmentCommand command = new ReplaceWaySegmentCommand(
            fixture.dataSet,
            fixture.way,
            fixture.selection,
            preview,
            "test"
        );

        command.executeCommand();
        command.undoCommand();
        command.executeCommand();

        EastNorth moved = eastNorth(fixture.reused);
        assertEquals(target.east(), moved.east(), 1e-6);
        assertEquals(target.north(), moved.north(), 1e-6);
        assertEquals(List.of(fixture.start, fixture.reused, fixture.end), fixture.way.getNodes());
    }

    @Test
    void undoRestoresCleanDatasetModifiedState() {
        DataSet dataSet = new DataSet();
        Node start = existingNode(1, 0.0, 0.0);
        Node middle = existingNode(2, 0.0, 0.001);
        Node end = existingNode(3, 0.0, 0.002);
        for (Node node : List.of(start, middle, end)) {
            dataSet.addPrimitive(node);
        }
        Way way = new Way();
        way.setNodes(List.of(start, middle, end));
        way.setOsmId(10, 1);
        way.setModified(false);
        dataSet.addPrimitive(way);
        SelectionContext selection = new SelectionContext(
            way, 0, 2, way.getNodes(), Set.of(start, end));
        EastNorth target = eastNorth(middle).add(10.0, 5.0);
        ReplaceWaySegmentCommand command = new ReplaceWaySegmentCommand(
            dataSet, way, selection, List.of(eastNorth(start), target, eastNorth(end)), "test");

        assertFalse(dataSet.isModified());
        command.executeCommand();
        assertTrue(dataSet.isModified());

        command.undoCommand();

        assertFalse(dataSet.isModified());
        assertFalse(start.isModified());
        assertFalse(middle.isModified());
        assertFalse(end.isModified());
        assertFalse(way.isModified());
    }

    @Test
    void reusesExistingNodesNearTheirOriginalLongitudinalFractions() {
        DataSet dataSet = new DataSet();
        Node start = nodeAtEastNorth(0.0, 0.0);
        Node quarter = nodeAtEastNorth(25.0, 0.0);
        Node middle = nodeAtEastNorth(50.0, 0.0);
        Node threeQuarters = nodeAtEastNorth(75.0, 0.0);
        Node end = nodeAtEastNorth(100.0, 0.0);
        for (Node node : List.of(start, quarter, middle, threeQuarters, end)) {
            dataSet.addPrimitive(node);
        }
        Way way = new Way();
        way.setNodes(List.of(start, quarter, middle, threeQuarters, end));
        dataSet.addPrimitive(way);
        SelectionContext selection = new SelectionContext(
            way, 0, 4, List.of(start, quarter, middle, threeQuarters, end), Set.of(start, end));
        List<EastNorth> preview = java.util.stream.IntStream.rangeClosed(0, 20)
            .mapToObj(index -> new EastNorth(index * 5.0, index == 0 || index == 20 ? 0.0 : 10.0))
            .toList();
        ReplaceWaySegmentCommand command = new ReplaceWaySegmentCommand(dataSet, way, selection, preview, "test");

        command.executeCommand();

        assertTrue(Math.abs(25.0 - eastNorth(quarter).east()) <= 5.01);
        assertEquals(50.0, eastNorth(middle).east(), 1e-6);
        assertTrue(Math.abs(75.0 - eastNorth(threeQuarters).east()) <= 5.01);
        assertTrue(way.getNodes().indexOf(quarter) < way.getNodes().indexOf(middle));
        assertTrue(way.getNodes().indexOf(middle) < way.getNodes().indexOf(threeQuarters));
    }

    @Test
    void reusableNodesCannotCrossAnInteriorFixedAnchor() {
        DataSet dataSet = new DataSet();
        Node start = nodeAtEastNorth(0.0, 0.0);
        Node beforeAnchor = nodeAtEastNorth(49.0, 0.0);
        Node anchor = nodeAtEastNorth(50.0, 0.0);
        Node afterAnchor = nodeAtEastNorth(51.0, 0.0);
        Node end = nodeAtEastNorth(100.0, 0.0);
        for (Node node : List.of(start, beforeAnchor, anchor, afterAnchor, end)) {
            dataSet.addPrimitive(node);
        }
        Way way = new Way();
        way.setNodes(List.of(start, beforeAnchor, anchor, afterAnchor, end));
        dataSet.addPrimitive(way);
        SelectionContext selection = new SelectionContext(
            way, 0, 4, way.getNodes(), Set.of(start, anchor, end));
        List<EastNorth> preview = List.of(
            new EastNorth(0.0, 0.0),
            new EastNorth(50.0, 0.0),
            new EastNorth(50.0, 100.0),
            new EastNorth(100.0, 100.0),
            new EastNorth(100.0, 0.0));

        ReplaceWaySegmentCommand command = new ReplaceWaySegmentCommand(dataSet, way, selection, preview, "test");

        command.executeCommand();

        assertTrue(way.getNodes().indexOf(beforeAnchor) < way.getNodes().indexOf(anchor),
            "A reusable node from before a fixed anchor must remain before it");
        assertTrue(way.getNodes().indexOf(afterAnchor) > way.getNodes().indexOf(anchor),
            "A reusable node from after a fixed anchor must remain after it");
    }

    @Test
    void appliesCandidateOwnedSharedJunctionTargetExactlyAcrossUndoAndRedo() {
        DataSet dataSet = new DataSet();
        Node start = nodeAtEastNorth(0.0, 0.0);
        Node junction = nodeAtEastNorth(10.0, 0.0);
        Node branchEnd = nodeAtEastNorth(10.0, 20.0);
        for (Node node : List.of(start, junction, branchEnd)) {
            dataSet.addPrimitive(node);
        }
        Way selected = new Way();
        selected.setNodes(List.of(start, junction));
        dataSet.addPrimitive(selected);
        Way connected = new Way();
        connected.setNodes(List.of(junction, branchEnd));
        dataSet.addPrimitive(connected);
        SelectionContext selection = new SelectionContext(selected, 0, 1, selected.getNodes(), Set.of(start));
        List<EastNorth> preview = List.of(new EastNorth(0.0, 0.0), new EastNorth(12.0, 5.0));
        Map<Long, EastNorth> targets = PreviewNodeAssignmentPlanner.targetMap(
            PreviewNodeAssignmentPlanner.preciseAssignments(selection,
                List.of(new EastNorth(0.0, 0.0), new EastNorth(10.0, 0.0)), preview));
        EastNorth junctionTarget = targets.get(junction.getUniqueId());
        ReplaceWaySegmentCommand command = new ReplaceWaySegmentCommand(
            dataSet, selected, selection, preview, targets, "test");

        command.executeCommand();
        assertEquals(junctionTarget, eastNorth(junction));
        assertEquals(junction, connected.firstNode(), "The incident way must retain the same shared node object");
        assertEquals(new EastNorth(10.0, 20.0), eastNorth(branchEnd));

        command.undoCommand();
        assertEquals(10.0, eastNorth(junction).east(), 1e-6);
        assertEquals(0.0, eastNorth(junction).north(), 1e-6);
        assertEquals(new EastNorth(10.0, 20.0), eastNorth(branchEnd));

        command.executeCommand();
        assertEquals(junctionTarget, eastNorth(junction));
        assertEquals(junction, connected.firstNode());
    }

    @Test
    void rejectsCandidatePlanMismatchBeforeMutatingDataset() {
        DataSet dataSet = new DataSet();
        Node start = nodeAtEastNorth(0.0, 0.0);
        Node end = nodeAtEastNorth(10.0, 0.0);
        dataSet.addPrimitive(start);
        dataSet.addPrimitive(end);
        Way way = new Way();
        way.setNodes(List.of(start, end));
        dataSet.addPrimitive(way);
        SelectionContext selection = new SelectionContext(way, 0, 1, way.getNodes(), Set.of(start));
        List<EastNorth> preview = List.of(new EastNorth(0.0, 0.0), new EastNorth(12.0, 5.0));
        ReplaceWaySegmentCommand command = new ReplaceWaySegmentCommand(dataSet, way, selection, preview,
            Map.of(start.getUniqueId(), new EastNorth(0.0, 0.0),
                end.getUniqueId(), new EastNorth(100.0, 100.0)), "test");

        assertThrows(IllegalStateException.class, command::executeCommand);
        assertEquals(List.of(start, end), way.getNodes());
        assertEquals(new EastNorth(0.0, 0.0), eastNorth(start));
        assertEquals(new EastNorth(10.0, 0.0), eastNorth(end));
    }

    @Test
    void emptyCandidatePlanKeepsLegacyPreciseCommandCompatible() {
        DataSet dataSet = new DataSet();
        Node start = nodeAtEastNorth(0.0, 0.0);
        Node end = nodeAtEastNorth(10.0, 0.0);
        dataSet.addPrimitive(start);
        dataSet.addPrimitive(end);
        Way way = new Way();
        way.setNodes(List.of(start, end));
        dataSet.addPrimitive(way);
        SelectionContext selection = new SelectionContext(way, 0, 1, way.getNodes(), Set.of(start, end));
        List<EastNorth> preview = List.of(
            new EastNorth(0.0, 0.0), new EastNorth(5.0, 2.0), new EastNorth(10.0, 0.0));
        ReplaceWaySegmentCommand command = new ReplaceWaySegmentCommand(
            dataSet, way, selection, preview, Map.of(), "legacy precise test");

        command.executeCommand();

        assertEquals(3, way.getNodesCount());
        assertEquals(start, way.firstNode());
        assertEquals(end, way.lastNode());
    }

    private static Fixture fixture() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0, 0.0);
        Node reused = node(0.0, 0.001);
        Node droppedTagged = node(0.0, 0.002);
        Node droppedPlain = node(0.0, 0.003);
        Node end = node(0.0, 0.004);
        for (Node node : List.of(start, reused, droppedTagged, droppedPlain, end)) {
            dataSet.addPrimitive(node);
        }
        Way way = new Way();
        way.setNodes(List.of(start, reused, droppedTagged, droppedPlain, end));
        dataSet.addPrimitive(way);
        SelectionContext selection = new SelectionContext(
            way,
            0,
            4,
            List.of(start, reused, droppedTagged, droppedPlain, end),
            Set.of(start, end)
        );
        return new Fixture(dataSet, way, selection, start, reused, droppedTagged, droppedPlain, end);
    }

    private static Node node(double lat, double lon) {
        return new Node(new LatLon(lat, lon));
    }

    private static Node existingNode(long id, double lat, double lon) {
        Node node = node(lat, lon);
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static Node nodeAtEastNorth(double east, double north) {
        Node node = new Node(new LatLon(0.0, 0.0));
        node.setEastNorth(new EastNorth(east, north));
        return node;
    }

    private static EastNorth eastNorth(Node node) {
        return node.getEastNorth(ProjectionRegistry.getProjection());
    }

    private record Fixture(
        DataSet dataSet,
        Way way,
        SelectionContext selection,
        Node start,
        Node reused,
        Node droppedTagged,
        Node droppedPlain,
        Node end
    ) {
    }
}

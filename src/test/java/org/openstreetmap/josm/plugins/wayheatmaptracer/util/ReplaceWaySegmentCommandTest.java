package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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

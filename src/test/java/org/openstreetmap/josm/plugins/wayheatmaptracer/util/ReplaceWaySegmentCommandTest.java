package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

        assertEquals(List.of(fixture.start, fixture.reused, fixture.end), fixture.way.getNodes());
        assertNotNull(fixture.droppedTagged.getDataSet(), "Tagged dropped nodes must survive cleanup");
        assertNull(fixture.droppedPlain.getDataSet(), "Untagged unreferenced dropped nodes should be removed");
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

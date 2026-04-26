package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class SelectionResolverTest {
    @BeforeAll
    static void setPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
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

    private static Way way(Node... nodes) {
        Way way = new Way();
        way.setNodes(List.of(nodes));
        return way;
    }

    private static Node node(double lon) {
        return new Node(new LatLon(0.0, lon));
    }
}

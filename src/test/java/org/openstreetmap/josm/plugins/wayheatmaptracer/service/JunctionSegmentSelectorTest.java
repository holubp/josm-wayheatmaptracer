package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.WaySegmentRange;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class JunctionSegmentSelectorTest {
    @BeforeAll
    static void setProjection() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void selectsLongestSegmentBetweenJunctionsAndEndpoints() {
        DataSet dataSet = new DataSet();
        Node n0 = node(0.0);
        Node n1 = node(0.001);
        Node n2 = node(0.002);
        Node n3 = node(0.010);
        Node n4 = node(0.011);
        for (Node node : List.of(n0, n1, n2, n3, n4)) {
            dataSet.addPrimitive(node);
        }
        Way way = way(n0, n1, n2, n3, n4);
        Way branchAtN2 = way(n2, node(0.0025));
        Way branchAtN3 = way(n3, node(0.0105));
        dataSet.addPrimitive(way);
        dataSet.addPrimitive(branchAtN2.getNode(1));
        dataSet.addPrimitive(branchAtN2);
        dataSet.addPrimitive(branchAtN3.getNode(1));
        dataSet.addPrimitive(branchAtN3);

        WaySegmentRange range = new JunctionSegmentSelector().longestJunctionBoundedSegment(way);

        assertEquals(new WaySegmentRange(2, 3), range);
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

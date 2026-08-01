package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class ParallelWayContextResolverTest {
    @BeforeAll
    static void setProjection() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void resolvesOnlyNearbyParallelHighwaysAndNeverSelectedWay() {
        DataSet dataSet = new DataSet();
        Way selected = way(dataSet, "track", new EastNorth(0, 0), new EastNorth(100, 0));
        way(dataSet, "path", new EastNorth(0, 5), new EastNorth(100, 5));
        way(dataSet, "path", new EastNorth(50, -10), new EastNorth(50, 10));
        SelectionContext selection = new SelectionContext(selected, 0, 1, selected.getNodes(), Set.copyOf(selected.getNodes()));

        List<ParallelWayContext> contexts = new ParallelWayContextResolver().resolve(selection, true, 10.0);

        assertEquals(1, contexts.size());
        assertEquals("path", contexts.get(0).tags().get("highway"));
    }

    @Test
    void returnsNoContextWhenOptionIsDisabled() {
        DataSet dataSet = new DataSet();
        Way selected = way(dataSet, "track", new EastNorth(0, 0), new EastNorth(100, 0));
        way(dataSet, "track", new EastNorth(0, 4), new EastNorth(100, 4));
        SelectionContext selection = new SelectionContext(selected, 0, 1, selected.getNodes(), Set.copyOf(selected.getNodes()));

        assertEquals(List.of(), new ParallelWayContextResolver().resolve(selection, false, 10.0));
    }

    @Test
    void resolvesMatchingSectionOfLongParallelWay() {
        DataSet dataSet = new DataSet();
        Way selected = way(dataSet, "track", new EastNorth(0, 0), new EastNorth(100, 0));
        way(dataSet, "track", new EastNorth(-1000, 6), new EastNorth(1000, 6));
        SelectionContext selection = new SelectionContext(
            selected, 0, 1, selected.getNodes(), Set.copyOf(selected.getNodes()));

        List<ParallelWayContext> contexts = new ParallelWayContextResolver().resolve(selection, true, 10.0);

        assertEquals(1, contexts.size());
        assertEquals(6.0, contexts.get(0).meanDistanceMeters(), 0.01);
        assertEquals(1.0, contexts.get(0).overlapRatio(), 0.01);
    }

    private Way way(DataSet dataSet, String highway, EastNorth... points) {
        Way way = new Way();
        way.put("highway", highway);
        for (EastNorth point : points) {
            Node node = new Node(ProjectionRegistry.getProjection().eastNorth2latlon(point));
            dataSet.addPrimitive(node);
            way.addNode(node);
        }
        dataSet.addPrimitive(way);
        return way;
    }
}

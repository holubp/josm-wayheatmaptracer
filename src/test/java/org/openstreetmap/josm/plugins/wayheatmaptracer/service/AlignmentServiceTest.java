package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class AlignmentServiceTest {
    @BeforeAll
    static void setPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void roughSketchSelectionUsesPreciseShapeAutomatically() {
        SelectionContext sketch = selection(4);
        ManagedHeatmapConfig config = config(AlignmentMode.MOVE_EXISTING_NODES);

        assertTrue(AlignmentService.isSketchLikeSelection(sketch));
        assertEquals(AlignmentMode.PRECISE_SHAPE, AlignmentService.effectiveAlignmentMode(sketch, config));
    }

    @Test
    void detailedSelectionKeepsConfiguredAlignmentMode() {
        SelectionContext detailed = selection(8);
        ManagedHeatmapConfig config = config(AlignmentMode.MOVE_EXISTING_NODES);

        assertEquals(AlignmentMode.MOVE_EXISTING_NODES, AlignmentService.effectiveAlignmentMode(detailed, config));
    }

    @Test
    void shortSegmentOfLongerWayIsNotTreatedAsSketch() {
        SelectionContext segment = segmentSelection(8, 2, 5);
        ManagedHeatmapConfig config = config(AlignmentMode.MOVE_EXISTING_NODES);

        assertEquals(AlignmentMode.MOVE_EXISTING_NODES, AlignmentService.effectiveAlignmentMode(segment, config));
    }

    private SelectionContext selection(int nodeCount) {
        Way way = new Way();
        List<Node> nodes = java.util.stream.IntStream.range(0, nodeCount)
            .mapToObj(index -> new Node(new LatLon(0.0, index * 0.0001)))
            .toList();
        way.setNodes(nodes);
        return new SelectionContext(way, 0, nodeCount - 1, nodes, Set.of(nodes.get(0), nodes.get(nodeCount - 1)));
    }

    private SelectionContext segmentSelection(int nodeCount, int start, int end) {
        Way way = new Way();
        List<Node> nodes = java.util.stream.IntStream.range(0, nodeCount)
            .mapToObj(index -> new Node(new LatLon(0.0, index * 0.0001)))
            .toList();
        way.setNodes(nodes);
        List<Node> segment = nodes.subList(start, end + 1);
        return new SelectionContext(way, start, end, segment, Set.of(segment.get(0), segment.get(segment.size() - 1)));
    }

    private ManagedHeatmapConfig config(AlignmentMode mode) {
        return new ManagedHeatmapConfig(
            "", "", "", "",
            "all",
            "hot",
            "",
            ".*",
            mode,
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            18,
            4,
            3.0
        );
    }
}

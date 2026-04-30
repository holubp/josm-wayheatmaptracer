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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentDiagnostics;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
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
    void roughSketchSelectionIsRecognizedButKeepsConfiguredModeForV02CompatibleSliding() {
        SelectionContext sketch = selection(4);
        ManagedHeatmapConfig config = config(AlignmentMode.MOVE_EXISTING_NODES);

        assertTrue(AlignmentService.isSketchLikeSelection(sketch));
        assertEquals(AlignmentMode.MOVE_EXISTING_NODES, AlignmentService.effectiveAlignmentMode(sketch, config));
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

    @Test
    void samplingSummaryReportsVisibleRenderedLayerZoom() {
        AlignmentDiagnostics diagnostics = new AlignmentDiagnostics(
            "Strava",
            2,
            3,
            10,
            20,
            30,
            "{}",
            "{}",
            "{\"type\":\"rendered-visible-layer\",\"algorithm\":\"v0.2-compatible\",\"tileZoom\":15,\"bestTileZoom\":15,"
                + "\"rasterScale\":6.0,\"rasterWidth\":6000,\"rasterHeight\":3600,"
                + "\"viewMetersPerPixel\":0.75,\"rasterMetersPerPixel\":0.125}",
            "[\"hot\",\"blue\"]",
            "[]",
            "[]"
        );

        assertEquals("visible rendered layer, v0.2-compatible, source tile z15 (best z15), raster 6.0x, "
                + "view 0.750 m/px, sampled 0.1250 m/raster-px, capture 6000x3600",
            diagnostics.samplingSummary());
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
            3.0,
            InferenceMode.STABLE_FIXED_SCALE,
            15,
            13,
            28.0,
            6.0,
            0L
        );
    }

}

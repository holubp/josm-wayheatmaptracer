package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentDiagnostics;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
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

    @Test
    void samplingSummaryReportsEffectiveGroundScaleSampling() {
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
                + "\"viewMetersPerPixel\":0.097266,\"rasterMetersPerPixel\":0.016211,"
                + "\"effectiveHalfWidthMeters\":7.003152,\"effectiveStepMeters\":1.556256,"
                + "\"effectiveHalfWidthPx\":72,\"effectiveStepPx\":16}",
            "[\"hot\",\"blue\"]",
            "[]",
            "[]"
        );

        assertTrue(diagnostics.samplingSummary().contains("search half 7.00 m (72 px), step 1.56 m (16 px)"));
    }

    @Test
    void candidateSwitchUsesStoredSlideTimeGeometryWithoutCurrentMapView() {
        AlignmentService service = new AlignmentService();
        SelectionContext selection = selection(3);
        List<EastNorth> source = List.of(
            new EastNorth(0.0, 0.0),
            new EastNorth(10.0, 0.0),
            new EastNorth(20.0, 0.0)
        );
        CenterlineCandidate candidate = new CenterlineCandidate(
            "hot/ridge-1",
            1.0,
            List.of(new java.awt.geom.Point2D.Double(9999.0, 9999.0)),
            List.of(0.0)
        ).withEastNorthPoints(List.of(
            new EastNorth(0.0, 0.0),
            new EastNorth(10.0, 10.0),
            new EastNorth(20.0, 0.0)
        ));
        AlignmentResult base = new AlignmentResult(
            selection,
            null,
            List.of(candidate),
            source,
            source,
            List.of(),
            new AlignmentDiagnostics("Strava", 1, 0, 0, 0, 0, "{}", "{}", "{}", "[\"hot\"]", "[]", "[]"),
            null
        );

        AlignmentResult result = service.applyCandidate(base, candidate);

        assertEquals(1, result.nodeMoves().size());
        assertEquals(10.0, result.nodeMoves().get(0).target().east(), 1e-9);
        assertEquals(10.0, result.nodeMoves().get(0).target().north(), 1e-9);
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

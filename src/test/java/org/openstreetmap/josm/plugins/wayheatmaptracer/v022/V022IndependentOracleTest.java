package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Exercises independent graph, geometry, and topology oracles without production tracing. */
class V022IndependentOracleTest {
    @Test
    void tinyGraphMapAndPartitionMatchExhaustiveEnumeration() {
        TinyGraphOracle.Graph graph = TinyGraphOracle.graph(List.of(
                List.of(new TinyGraphOracle.State("a", 0.0), new TinyGraphOracle.State("b", 1.0)),
                List.of(new TinyGraphOracle.State("c", 0.25), new TinyGraphOracle.State("d", 0.75))),
                Map.of("a:c", 0.0, "a:d", 1.0, "b:c", 1.0, "b:d", 0.0));

        TinyGraphOracle.Result result = TinyGraphOracle.solve(graph);

        assertEquals(List.of("a", "c"), result.mapPath());
        assertEquals(0.25, result.mapCost(), 1.0e-12);
        assertEquals(Math.exp(-0.25) + 2.0 * Math.exp(-1.75) + Math.exp(-2.25), result.partition(), 1.0e-12);
    }

    @Test
    void symmetricCurveDistanceSamplesBothDirections() {
        List<V022Point> reference = List.of(new V022Point(0, 0), new V022Point(10, 0));
        List<V022Point> candidate = List.of(new V022Point(0, 1), new V022Point(5, 1), new V022Point(10, 1));

        CurveOracle.DistanceMetrics metrics = CurveOracle.symmetricDistance(reference, candidate, 0.1);

        assertEquals(1.0, metrics.p95Meters(), 1.0e-12);
        assertEquals(1.0, metrics.maximumMeters(), 1.0e-12);
    }

    @Test
    void isolatedDoglegIsDetectedWithoutUsingProductionMetadata() {
        SyntheticHeatmapScene scene = V022SceneCatalog.scene("S03", 11);
        CurveOracle.LocalDefect defect = CurveOracle.largestUnsupportedExcursion(
                scene.truthGraph().branches().get(0).sampledPoints(),
                scene.defectiveCandidate().orElseThrow().points(),
                0.1);

        assertTrue(defect.amplitudeMeters() >= 1.4);
        assertTrue(defect.chainageMeters() >= 53.0 && defect.chainageMeters() <= 57.0);
    }

    @Test
    void receiverInsertionPreservesEveryPortAndRejectsAnOldBacktrack() {
        TopologyOracle.Network expected = V022SceneCatalog.scene("S17", 11).truthGraph();
        TopologyOracle.Network defective = V022SceneCatalog.scene("S17", 11).defectiveNetwork().orElseThrow();

        assertTrue(TopologyOracle.hasSingleSharedJunction(expected, "X", 3));
        assertTrue(TopologyOracle.hasBacktrack(defective));
        assertFalse(TopologyOracle.hasBacktrack(expected));
    }
}

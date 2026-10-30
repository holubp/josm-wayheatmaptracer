package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

class InheritedConnectorRunsTest {
    @Test void inheritedZeroConnectorMustNotHidePhysicalFoldback() {
        var points = List.of(new MetricPoint(0, 0), new MetricPoint(8, 2), new MetricPoint(12, 2),
                new MetricPoint(12, 0), new MetricPoint(10.2, .1), new MetricPoint(10, 0),
                new MetricPoint(10, 0), new MetricPoint(10.2, 0), new MetricPoint(20, -.2));
        var findings = org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator
                .backtrackFindings(points);
        assertTrue(findings.stream().anyMatch(finding -> finding.code()
                == org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator
                        .FindingCode.ADJACENT_BACKTRACK), findings.toString());
    }

    @Test void multipleAndBoundaryZeroEdgesPreservePhysicalBacktrackDetection() {
        var points = List.of(new MetricPoint(0, 0), new MetricPoint(0, 0), new MetricPoint(10, 0),
                new MetricPoint(10, 0), new MetricPoint(10, 0), new MetricPoint(9, .1), new MetricPoint(9, .1));
        var findings = org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator
                .backtrackFindings(points);
        assertTrue(findings.stream().anyMatch(finding -> finding.code()
                == org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator
                        .FindingCode.ADJACENT_BACKTRACK), findings.toString());
        assertTrue(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator
                .backtrackFindings(List.of(new MetricPoint(0, 0), new MetricPoint(0, 0),
                        new MetricPoint(10, 0), new MetricPoint(10, 0), new MetricPoint(20, 1),
                        new MetricPoint(20, 1))).isEmpty());
    }
    private static final PrimitiveKey WAY = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 10);
    private static PrimitiveKey key(int index) { return PrimitiveKey.existing(PrimitiveKey.Type.NODE, index + 1); }
    private static Map<PrimitiveKey, DetachedPrimitive> graph(double... positions) {
        var graph = new LinkedHashMap<PrimitiveKey, DetachedPrimitive>();
        var keys = new java.util.ArrayList<PrimitiveKey>();
        for (int index = 0; index < positions.length; index++) {
            var key = key(index); keys.add(key);
            graph.put(key, new DetachedNode(key, new GeographicPoint(0, positions[index]), Map.of(), false, false));
        }
        graph.put(WAY, new DetachedWay(WAY, keys, Map.of(), false, false));
        return graph;
    }
    private static void expect(int[] expected, Map<PrimitiveKey, DetachedPrimitive> before,
            Map<PrimitiveKey, DetachedPrimitive> after) {
        assertArrayEquals(expected, ModernSingleWayEditPlanAdapter.inheritedConnectorRuns(before, after).get(WAY));
    }
    @Test void unchangedInheritedConnectorAndLongerRunAreAdjacent() {
        var source = graph(0, 1, 1, 1, 2);
        expect(new int[] {0, 1, 1, 1, 2}, source, source);
    }
    @Test void newCollapseAndMovedInheritedConnectorAreNotAdjacent() {
        expect(new int[] {0, 1, 2, 3}, graph(0, 1, 1.1, 2), graph(0, 1, 1, 2));
        expect(new int[] {0, 1, 2, 3}, graph(0, 1, 1, 2), graph(0, 1.1, 1.1, 2));
    }
    @Test void reorderedRepeatedAndNonconsecutiveOriginalIdentitiesAreNotConnectors() {
        var source = graph(0, 1, 1, 2);
        var reordered = new LinkedHashMap<>(source);
        reordered.put(WAY, new DetachedWay(WAY, List.of(key(0), key(2), key(1), key(3)), Map.of(), false, false));
        expect(new int[] {0, 1, 2, 3}, source, reordered);
        var repeated = new LinkedHashMap<>(source);
        repeated.put(WAY, new DetachedWay(WAY, List.of(key(0), key(1), key(2), key(1), key(3)), Map.of(), false, false));
        expect(new int[] {0, 1, 2, 3}, repeated, source);
        var separated = graph(0, 1, 1.5, 1, 2);
        var removed = new LinkedHashMap<>(separated);
        removed.put(WAY, new DetachedWay(WAY, List.of(key(0), key(1), key(3), key(4)), Map.of(), false, false));
        expect(new int[] {0, 1, 2, 3}, separated, removed);
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;

/** Exact topology and bounded work for preserved coincident source occurrences. */
class V022ZeroLengthAdjacencyTest {
    @Test
    void consecutiveTypedZeroRunPreservesOccurrencesWithoutAddingLength() {
        var points = List.of(p(-1, 0), p(0, 0), p(0, 0), p(0, 0), p(1, 0));
        var result = evaluate(points, existingIds(points.size()));
        assertFalse(result.has(FinalGeometryEvaluator.FindingCode.NONADJACENT_TOUCH));
        assertEquals(2.0, result.totalLengthMeters());
    }

    @Test
    void genericCoincidentDuplicatesRetainTopologyBlock() {
        var points = List.of(p(-1, 0), p(0, 0), p(0, 0), p(1, 0));
        List<FinalRoutePointId> ids = new ArrayList<>();
        for(int i=0;i<points.size();i++)ids.add(new FinalRoutePointId.GeneratedCandidatePoint("generic", i));
        assertTrue(evaluate(points, ids).has(FinalGeometryEvaluator.FindingCode.NONADJACENT_TOUCH));
    }

    @Test
    void nonconsecutiveTypedOccurrencesCannotClaimZeroRunAdjacency() {
        var points = List.of(p(-1, 0), p(0, 0), p(0, 0), p(1, 0));
        var ids = existingIds(5);
        assertTrue(evaluate(points, List.of(ids.get(0), ids.get(1), ids.get(3), ids.get(4)))
                .has(FinalGeometryEvaluator.FindingCode.NONADJACENT_TOUCH));
    }

    @Test
    void typedZeroRunDoesNotHideRemoteTouch() {
        var points = List.of(p(-1, 0), p(0, 0), p(0, 0), p(1, 0), p(1, 1), p(-1, 0));
        assertTrue(evaluate(points, existingIds(points.size()))
                .has(FinalGeometryEvaluator.FindingCode.NONADJACENT_TOUCH));
    }

    @Test
    void typedZeroRunDoesNotHideCollinearFoldback() {
        var points = List.of(p(-1, 0), p(0, 0), p(0, 0), p(-0.5, 0));
        assertTrue(evaluate(points, existingIds(points.size()))
                .has(FinalGeometryEvaluator.FindingCode.COLLINEAR_OVERLAP));
    }

    @Test
    void constructionIsLinearAndQueriesDoNotReadInputIdentities() {
        for (int middleCount : new int[] {32, 64, 128}) {
            List<MetricPoint> points = new ArrayList<>();
            points.add(p(-1, 0));
            for (int i = 0; i < middleCount; i++) {
                points.add(p(0, 0));
            }
            points.add(p(1, 0));
            CountingList<FinalRoutePointId> ids = new CountingList<>(existingIds(points.size()));
            var adjacency = new FinalGeometryEvaluator.ZeroLengthAdjacency(points, ids);
            long constructionReads = ids.reads;
            int segments = points.size() - 1;
            long pairs = 0;
            for (int first = 0; first < segments; first++) {
                for (int second = first + 2; second < segments; second++) {
                    assertTrue(adjacency.connects(first, second));
                    pairs++;
                }
            }
            assertTrue(constructionReads <= 2L * points.size());
            assertEquals(constructionReads, ids.reads, "Queries must not rescan source identities");
            assertEquals((long) middleCount * (middleCount - 1) / 2, pairs);
        }
    }

    @Test
    void zeroRunAdjacencyRequiresBoundedConstructionAndConstantPairQueries() {
        int middleCount = 32;
        List<MetricPoint> points = new ArrayList<>();
        points.add(p(-1, 0));
        for (int i = 0; i < middleCount; i++) {
            points.add(p(0, 0));
        }
        points.add(p(1, 0));
        CountingList<FinalRoutePointId> ids = new CountingList<>(existingIds(points.size()));
        var adjacency = new FinalGeometryEvaluator.ZeroLengthAdjacency(points, ids);
        int segments = points.size() - 1;
        long pairs = 0;
        for (int first = 0; first < segments; first++) {
            for (int second = first + 2; second < segments; second++) {
                assertTrue(adjacency.connects(first, second));
                pairs++;
            }
        }
        // Allow four identity reads per input occurrence and four per pair query;
        // a prepared zero-connector run summary needs no interval rescans.
        long identityReadBudget=4L*points.size()+4L*pairs;
        assertTrue(ids.reads<=identityReadBudget,
                "Zero-run adjacency rescanned interiors: "+ids.reads+" identity reads for "+pairs
                        +" pairs; linear construction plus constant query budget="+identityReadBudget);
    }

    @Test
    void coincidentNodesFromDifferentWaysCannotClaimConnectorAdjacency() {
        var points = List.of(p(-1, 0), p(0, 0), p(0, 0), p(1, 0));
        var ids = existingIds(points.size());
        ids.set(2, new FinalRoutePointId.ExistingWayNodeOccurrence(
                PrimitiveKey.existing(PrimitiveKey.Type.WAY, 200),
                PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1002), 2));
        assertTrue(evaluate(points, ids).has(FinalGeometryEvaluator.FindingCode.NONADJACENT_TOUCH));
    }

    private static FinalGeometryEvaluator.Result evaluate(List<MetricPoint> points, List<FinalRoutePointId> ids) {
        return new FinalGeometryEvaluator().evaluate(new FinalGeometryEvaluator.Request("probe", points, ids,
                QualityTestFixtures.constantImage(0.8), 1.0, Map.of(), List.of(), false, false, false, false));
    }

    private static List<FinalRoutePointId> existingIds(int size) {
        List<FinalRoutePointId> ids = new ArrayList<>();
        var way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 100);
        for (int i = 0; i < size; i++) {
            ids.add(new FinalRoutePointId.ExistingWayNodeOccurrence(way,
                    PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1000 + i), i));
        }
        return ids;
    }

    private static MetricPoint p(double x, double y) {
        return new MetricPoint(x, y);
    }
    private static final class CountingList<T> extends AbstractList<T> {
        private final List<T> values;
        long reads;

        CountingList(List<T> values) {
            this.values = values;
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public T get(int index) {
            reads++;
            return values.get(index);
        }
    }
}

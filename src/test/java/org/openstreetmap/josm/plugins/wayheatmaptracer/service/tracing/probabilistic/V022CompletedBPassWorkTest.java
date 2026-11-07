package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022CompletedBPassWorkTest {
    private static final EvidenceModelParameters DATA=new EvidenceModelParameters("completed-work-oracle",1,0,0,0,0,1,1,0);
    private static List<InferenceProfile> graph(int... widths) {
        List<InferenceProfile> p=new ArrayList<>();
        for(int i=0;i<widths.length;i++) p.add(V022ExactKBestExecutionTest.profile(i,new double[widths[i]],V022ExactKBestExecutionTest.ones(widths[i])));
        return p;
    }
    @Test void nativeCompletedCountsMatchExhaustiveReachableTriplesAndActualWork() {
        for(var p:List.of(graph(2,3,2),graph(1,2,3,1),graph(3,3,3,3))) {
            var ledger=AttemptMemoryLedger.production();
            List<CompletedBPassWork> seen=new ArrayList<>();
            try(var owner=ledger.rootOwner()) {
                var result=new ProbabilisticInference().solveCachedVersion(p,DATA,TraceBudgets.defaults(),null,
                    CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null,seen::add);
                assertFalse(result.rawPaths().isEmpty()); assertEquals(1,seen.size());
                var w=seen.get(0);
                long triples=0;
                for(int row=2;row<p.size();row++)
                    for(int a=0;a<p.get(row-2).cells().size();a++)
                        for(int c=0;c<p.get(row-1).cells().size();c++)
                            for(int e=0;e<p.get(row).cells().size();e++) triples++;
                assertEquals(triples,w.kBestHeadScores());
                assertEquals(result.workAccounting().orElseThrow().forwardBackwardScores(),w.forwardBackwardScores());
                assertEquals(result.evaluatedTransitions(),w.scoredTransitions());
                assertEquals(result.evaluatedPairVisits(),w.pairVisits());
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void refusalAndCancellationNeverPublishCompletedPass() {
        var ledger=AttemptMemoryLedger.production();
        List<CompletedBPassWork> seen=new ArrayList<>();
        try(var owner=ledger.rootOwner()) {
            var result=new ProbabilisticInference().solveCachedVersion(graph(3,3,3,3),DATA,
                new TraceBudgets(96,8000000,1,32,8),null,CancellationProbe.NONE,owner,
                ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null,seen::add);
            assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,result.status());
            assertTrue(seen.isEmpty());
            assertThrows(java.util.concurrent.CancellationException.class,()->new ProbabilisticInference().solveCachedVersion(
                graph(2,2,2),DATA,TraceBudgets.defaults(),null,()->true,owner,
                ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null,seen::add));
            assertTrue(seen.isEmpty());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void cancellationAfterCompleteNativeWorkCannotReturnThePass() {
        var ledger=AttemptMemoryLedger.production();boolean[] completed={false};
        try(var owner=ledger.rootOwner()) {
            assertThrows(java.util.concurrent.CancellationException.class,()->new ProbabilisticInference().solveCachedVersion(
                graph(2,2,2),DATA,TraceBudgets.defaults(),null,()->completed[0],owner,
                ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null,work->completed[0]=true));
            assertTrue(completed[0]);
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void sparseLegalEdgesCountOnlyReachableHeadsAndNoRouteHasNoWitness() {
        var ledger=AttemptMemoryLedger.production();var seen=new ArrayList<CompletedBPassWork>();
        try(var owner=ledger.rootOwner()) {
            var p=graph(3,3,3);
            var sparse=MetricRegion.rectangle(-.1,-.001,2.1,.001);
            var result=new ProbabilisticInference().solveCachedVersion(p,DATA,TraceBudgets.defaults(),sparse,
                CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null,seen::add);
            assertEquals(1,seen.size());assertEquals(1,seen.get(0).kBestHeadScores());
            assertEquals(result.workAccounting().orElseThrow().forwardBackwardScores(),seen.get(0).forwardBackwardScores());
            seen.clear();
            result=new ProbabilisticInference().solveCachedVersion(p,DATA,TraceBudgets.defaults(),MetricRegion.rectangle(-.1,-1,.5,1),
                CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null,seen::add);
            assertEquals(ProbabilisticInferenceResult.Status.NO_ROUTE,result.status());assertTrue(seen.isEmpty());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void unreachablePrefixNeverBecomesAReachableHeadInIndependentEnumeration() {
        var p=graph(3,3,3,3);
        var region=new MetricRegion(List.of(MetricRegion.rectangle(-.1,-.001,3.1,.001).polygons().get(0),
                MetricRegion.rectangle(.9,.039,3.1,.041).polygons().get(0)));
        boolean[][] reachable=new boolean[3][3];
        for(int a=0;a<3;a++)for(int b=0;b<3;b++)reachable[a][b]=region.containsSegment(p.get(0).point(a),p.get(1).point(b));
        long heads=0;
        for(int row=2;row<p.size();row++) {
            boolean[][] next=new boolean[3][3];
            for(int a=0;a<3;a++)for(int b=0;b<3;b++)for(int c=0;c<3;c++)
                if(reachable[a][b]&&region.containsSegment(p.get(row-1).point(b),p.get(row).point(c))) {
                    heads++;next[b][c]=true;
                }
            reachable=next;
        }
        assertEquals(2,heads,"High-strip legal triples lack a reachable original prefix");
        var ledger=AttemptMemoryLedger.production();var seen=new ArrayList<CompletedBPassWork>();
        try(var owner=ledger.rootOwner()) {
            new ProbabilisticInference().solveCachedVersion(p,DATA,TraceBudgets.defaults(),region,
                    CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null,seen::add);
            assertEquals(1,seen.size());assertEquals(heads,seen.get(0).kBestHeadScores());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void nonfiniteNumericalRouteCannotProduceCompletedWork() {
        var ledger=AttemptMemoryLedger.production();var seen=new ArrayList<CompletedBPassWork>();
        var p=List.of(V022ExactKBestExecutionTest.profile(0,new double[]{Double.MAX_VALUE},new double[]{1}),
            V022ExactKBestExecutionTest.profile(1,new double[]{Double.MAX_VALUE},new double[]{1}),
            V022ExactKBestExecutionTest.profile(2,new double[]{Double.MAX_VALUE},new double[]{1}));
        try(var owner=ledger.rootOwner()) {
            var result=new ProbabilisticInference().solveCachedVersion(p,DATA,TraceBudgets.defaults(),null,
                    CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null,seen::add);
            assertTrue(result.status()==ProbabilisticInferenceResult.Status.NO_ROUTE
                ||result.status()==ProbabilisticInferenceResult.Status.NUMERIC_FAILURE);
            assertTrue(seen.isEmpty());
        }
        assertEquals(0,ledger.currentBytes());
    }
}

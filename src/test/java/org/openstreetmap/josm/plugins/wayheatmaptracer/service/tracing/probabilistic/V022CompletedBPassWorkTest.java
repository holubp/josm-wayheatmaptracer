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
}

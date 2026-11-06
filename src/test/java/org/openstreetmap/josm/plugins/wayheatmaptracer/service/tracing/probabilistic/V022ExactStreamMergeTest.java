package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Independent old/exhaustive expectations for bounded predecessor-stream merging. */
class V022ExactStreamMergeTest {
    private static final EvidenceModelParameters DATA=new EvidenceModelParameters("stream-merge-oracle",1,0,0,0,0,1,1,0);
    private static InferenceProfile profile(double x,double... costs) {
        return V022ExactKBestExecutionTest.profile(x,costs,V022ExactKBestExecutionTest.ones(costs.length));
    }
    static List<InferenceProfile> lastBest() {
        double[] first=new double[32],before=new double[32];
        for(int i=0;i<32;i++) { first[i]=i*.001;before[i]=(31-i)*10.; }
        return List.of(profile(0,first),profile(1,before),profile(2,0),profile(3,0));
    }
    private static ProbabilisticInferenceResult run(List<InferenceProfile> p,EvidenceModelParameters parameters,
            int cap,ProbabilisticExecutionPolicy.Mode mode,MetricRegion region) {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            return new ProbabilisticInference().solve(p,parameters,new TraceBudgets(96,8_000_000,128_000_000,cap,Math.min(8,cap)),
                    region,CancellationProbe.NONE,owner,mode);
        } finally { assertEquals(0,ledger.currentBytes()); }
    }
    @Test void bestStreamLastNeedsFewerActualScoresWithoutLosingAnyLogicalExtension() {
        var p=lastBest();
        var old=run(p,DATA,32,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V3,null);
        var merged=run(p,DATA,32,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null);
        parity(old,merged);
        assertEquals(old.workAccounting().orElseThrow().logicalKBestExtensions(),
                merged.workAccounting().orElseThrow().logicalKBestExtensions());
        assertTrue(merged.workAccounting().orElseThrow().kBestScores()<old.workAccounting().orElseThrow().kBestScores(),
                "the last good predecessor must establish the cutoff before scanning bad tails");
        V022ExactKBestExecutionTest.assertPaths(V022ExactKBestExecutionTest.reference(p,32,false).paths(),merged.rawPaths());
    }
    @Test void cutoffEnergyGroupsAndRoundedLexicalCollapseMatchAllNativeCaps() {
        var fixtures=List.of(
                List.of(profile(0,1,0),profile(1,0),profile(2,0),profile(3,2e16)),
                List.of(profile(0,-0.,0.),profile(1,0.,-0.),profile(2,-0.,0.),profile(3,0)),
                List.of(profile(0,-3,-2),profile(1,.1,-.1),profile(2,-10,-11),profile(3,2,1)),
                List.of(profile(0,1e300,-1e300),profile(1,1e300),profile(2,.25,0),profile(3,2e16)),
                List.of(profile(0,0,0,0),profile(1,0,0,0),profile(2,0,0,0),profile(3,0,0,0),profile(4,0,0,0)));
        for(var p:fixtures) for(int cap:new int[]{1,2,32}) {
            var old=run(p,DATA,cap,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V3,null);
            var merged=run(p,DATA,cap,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null);
            parity(old,merged);
            V022ExactKBestExecutionTest.assertPaths(V022ExactKBestExecutionTest.reference(p,cap,false).paths(),merged.rawPaths());
        }
    }
    @Test void everyCutoffTieIsScoredAndTheLastPermittedScoreIsAdmitted() {
        var tied=List.of(profile(0,0,0,0),profile(1,0,0,0),profile(2,0,0,0),profile(3,0,0,0),profile(4,0,0,0));
        var all=run(tied,DATA,32,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null);
        assertEquals(351,all.workAccounting().orElseThrow().kBestScores());
        assertEquals(351,all.workAccounting().orElseThrow().logicalKBestExtensions());
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var solver=new ProbabilisticInference();
            var exact=solver.solve(lastBest(),DATA,new TraceBudgets(96,8_000_000,3199,32,8),null,
                    CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4);
            assertEquals(3199,exact.evaluatedTransitions());assertFalse(exact.rawPaths().isEmpty());
            var refused=solver.solve(lastBest(),DATA,new TraceBudgets(96,8_000_000,3198,32,8),null,
                    CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4);
            assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,refused.status());
            assertEquals(3198,refused.evaluatedTransitions());assertTrue(refused.rawPaths().isEmpty());
            assertEquals(1,refused.workAccounting().orElseThrow().refusedScoreAttempts());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void nonfiniteStreamsKeepConservativeBehavior() {
        var p=List.of(profile(0,0,0),profile(1,0,0),profile(2,0,Double.MAX_VALUE),profile(6,0),profile(7,0));
        parity(run(p,DATA,2,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V3,null),
                run(p,DATA,2,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null));
    }
    @Test void predecessorHeadPreparationPopAndTailClosureAreCancellableWithoutLeaks() {
        for(String phase:List.of("offer","pop","closeStream")) {
            var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                assertThrows(java.util.concurrent.CancellationException.class,() -> new ProbabilisticInference().solve(
                        lastBest(),DATA,TraceBudgets.defaults(),null,() -> Arrays.stream(Thread.currentThread().getStackTrace())
                            .anyMatch(f -> f.getClassName().endsWith("$PredecessorHeadHeap")&&f.getMethodName().equals(phase)),
                        owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4),phase);
                assertEquals(0,ledger.currentBytes(),phase);
            }
        }
    }
    @Test void predecessorHeapMemoryDenialDoesNotLeakOrPublishPartialPaths() {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            AttemptMemoryLedger.Reservation[] held={null};
            var result=new ProbabilisticInference().solve(lastBest(),DATA,TraceBudgets.defaults(),null,() -> {
                boolean inside=Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(f ->
                        f.getClassName().endsWith("$PredecessorHeadHeap")&&f.getMethodName().equals("<init>"));
                if(inside&&held[0]==null)held[0]=owner.reserve(ledger.limitBytes()-ledger.currentBytes()-64);
                return false;
            },owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4);
            assertNotNull(held[0]);assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,result.status());
            assertTrue(result.explanation().contains("k-best predecessor heads"));assertTrue(result.rawPaths().isEmpty());
            assertEquals(2112,result.workAccounting().orElseThrow().forwardBackwardScores());
            held[0].close();
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void unequalRandomGraphsAndReversedPhysicalGeometryKeepNonzeroShapeObjective() {
        var random=new Random(0x220704);
        for(int trial=0;trial<8;trial++) {
            List<InferenceProfile> p=new ArrayList<>();
            for(int row=0;row<5;row++) {
                double[] costs=new double[1+random.nextInt(4)];
                for(int i=0;i<costs.length;i++) costs[i]=(random.nextInt(9)-4)*.125;
                var base=profile(row*2,costs);
                p.add(new InferenceProfile(base.chainageMeters(),new MetricPoint(trial%2==0?row*2:10-row*2,
                        .1*row*row),base.normalUnit(),base.cells(),base.unaryCosts(),List.of(.3,.9),.7,
                        base.ownership(),false));
            }
            for(int cap:new int[]{1,2,32}) parity(run(p,EvidenceModelParameters.defaults(),cap,
                    ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V3,null),
                    run(p,EvidenceModelParameters.defaults(),cap,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null));
        }
    }
    static void parity(ProbabilisticInferenceResult old,ProbabilisticInferenceResult next) {
        assertEquals(old.status(),next.status());assertEquals(old.rawPaths(),next.rawPaths());
        assertEquals(old.distinctPaths(),next.distinctPaths());assertEquals(old.completion(),next.completion());
        assertEquals(Double.doubleToRawLongBits(old.logPartition()),Double.doubleToRawLongBits(next.logPartition()));
        assertEquals(old.evaluatedPairVisits(),next.evaluatedPairVisits());
        assertEquals(old.alternativeSearchTruncated(),next.alternativeSearchTruncated());
        var a=old.positionMarginals();var b=next.positionMarginals();assertEquals(a.size(),b.size());
        for(int i=0;i<a.size();i++) assertArrayEquals(a.get(i),b.get(i));
        assertEquals(old.workAccounting().orElseThrow().forwardBackwardScores(),
                next.workAccounting().orElseThrow().forwardBackwardScores());
        assertEquals(old.workAccounting().orElseThrow().logicalKBestExtensions(),
                next.workAccounting().orElseThrow().logicalKBestExtensions());
    }
}

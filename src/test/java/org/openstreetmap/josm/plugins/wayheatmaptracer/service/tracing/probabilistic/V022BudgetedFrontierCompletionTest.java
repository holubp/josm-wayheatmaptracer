package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

public class V022BudgetedFrontierCompletionTest {
    public static <T> T withNativeOwnerRelease(java.util.function.Supplier<T> work,java.util.function.LongConsumer closed) {
        try(var probe=BQualityProbe.install(false,new BQualityProbe.Sink() {
            public void dense(List<ProbabilisticProfile> sampled,List<InferenceProfile> dense,TraceBudgets budgets) { }
            public void closed(long current,long peak) { closed.accept(current); }
        })) { return work.get(); }
    }
    static final EvidenceModelParameters DATA=new EvidenceModelParameters("budgeted-fixture",1,0,0,0,0,1,1,0);
    record Fixture(List<ProbabilisticProfile> sampled,List<InferenceProfile> dense,List<ProbabilisticStateLattice> lattices) { }
    static Fixture fixture(boolean narrow) {
        var sampled=new ArrayList<ProbabilisticProfile>();var dense=new ArrayList<InferenceProfile>();
        var lattices=new ArrayList<ProbabilisticStateLattice>();
        for(int row=0;row<5;row++) {
            var p=new ProbabilisticProfile(row,row,new MetricPoint(row,0),new MetricPoint(0,1),-4,4,1,true,.01,
                List.of(new ProbabilisticProfile.Sample(0,.8,true)),
                List.of(new ProbabilisticProfile.Mode("mode","synthetic",0,0,.5,.8,.8,List.of(0.),List.of(0.),false)),
                List.of(),OptionalDouble.empty(),ImageOrientationSupport.unknown(ImageOrientationSupport.Status.UNKNOWN_FLAT));
            var cells=new ArrayList<LateralStateCell>();
            for(int j=-8;j<=8;j++)cells.add(new LateralStateCell(j*.5,.5,false,j==0,"source"));
            double[] costs=cells.stream().mapToDouble(c->narrow?10000*c.offsetMeters()*c.offsetMeters():0).toArray();
            sampled.add(p);dense.add(new InferenceProfile(row,p.anchor(),p.normalUnit(),cells,costs,List.of(),0,ObservationOwnership.DIRECT_TWO_SIDED,false));
            lattices.add(new ProbabilisticStateLattice(cells,List.of(new ObservationComponent("source","synthetic",ObservationComponent.Kind.MEASURED,1,false)),.5,false));
        }
        return new Fixture(sampled,dense,lattices);
    }
    static AdaptiveBInference.Result run(Fixture f,BPolicy policy,long scores,AttemptMemoryLedger.Owner owner,CancellationProbe cancel,AdaptiveBObserver observer) {
        double[] continuation=new double[f.dense().size()];Arrays.fill(continuation,Double.NaN);
        return new AdaptiveBInference().solve(f.sampled(),f.dense(),f.lattices(),continuation,DATA,
            new TraceBudgets(96,8000000,scores,32,8),null,cancel,owner,observer,ProbabilisticInference.WorkProgress.NONE,policy);
    }
    @Test void sourceBoundExplicitHashesAreDistinctAndDefaultsRemainV4() {
        for(String original:List.of(BPolicy.exactParameterHash(),BPolicy.visibleExactParameterHash())) {
            String v6=BPolicy.newCaptureParameterHash(original,BPolicy.INVENTORY_FRONTIER_BUDGETED_V6);
            assertNotEquals(BPolicy.newCaptureParameterHash(original,BPolicy.INVENTORY_FRONTIER_V5),v6);
            assertEquals(BPolicy.BUDGETED_FRONTIER_VERSION,BPolicy.adaptiveVersion(v6));
            assertEquals(BPolicy.newCaptureParameterHash(original,BPolicy.INVENTORY_PREP_V4),BPolicy.newCaptureParameterHash(original));
        }
        assertThrows(IllegalArgumentException.class,()->BPolicy.newCaptureParameterHash("unknown",BPolicy.INVENTORY_FRONTIER_BUDGETED_V6));
    }
    @Test void affordableV5AndV6MatchExactly() {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var f=fixture(false);var old=run(f,BPolicy.INVENTORY_FRONTIER_V5,128000000,owner,CancellationProbe.NONE,AdaptiveBObserver.NONE);
            var next=run(f,BPolicy.INVENTORY_FRONTIER_BUDGETED_V6,128000000,owner,CancellationProbe.NONE,AdaptiveBObserver.NONE);
            assertEquals(1,old.refinements());assertEquals(old.refinements(),next.refinements());
            assertEquals(old.provenanceHash(),next.provenanceHash());assertEquals(old.firstScores(),next.firstScores());assertEquals(old.secondScores(),next.secondScores());
            V022ExactStreamMergeTest.parity(old.inference(),next.inference());
            assertEquals(old.inference().credibleSets(),next.inference().credibleSets());
            assertEquals(BRefinementBudget.Outcome.ORIGINAL_REFINEMENT,BRefinementBudget.fromExplanation(next.inference().explanation()).orElseThrow().outcome());
            assertTrue(BRefinementBudget.fromExplanation(next.inference().explanation()).orElseThrow().lowerBoundScores().orElseThrow()
                    <=next.secondScores(),"Repeated native reachable work is a lower bound on actual nested second work");
            assertTrue(BRefinementBudget.fromExplanation(old.inference().explanation()).isEmpty());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void provedImpossibleReturnsOnlyCompleteFirstGraph() {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var f=fixture(false);var original=run(f,BPolicy.INVENTORY_FRONTIER_V5,128000000,owner,CancellationProbe.NONE,AdaptiveBObserver.NONE);
            var passes=new HashSet<Integer>();
            var next=run(f,BPolicy.INVENTORY_FRONTIER_BUDGETED_V6,original.firstScores()+1,owner,CancellationProbe.NONE,(pass,row,index,source)->passes.add(pass));
            assertEquals(Set.of(0),passes);assertEquals(0,next.refinements());assertEquals(0,next.secondScores());
            assertEquals(original.firstScores(),next.inference().evaluatedTransitions());
            assertEquals(32,next.inference().rawPaths().size());assertFalse(next.inference().posteriorUsable());
            assertEquals(ProbabilisticInferenceResult.Status.REVIEW_REQUIRED,next.inference().status());
            var w=BRefinementBudget.fromExplanation(next.inference().explanation()).orElseThrow();
            assertEquals(BRefinementBudget.Outcome.FIRST_GRAPH_PROVEN_UNAFFORDABLE,w.outcome());
            assertEquals(w,BRefinementBudget.parse(w.canonical()));
            assertEquals(1,w.remainingScores().orElseThrow());assertTrue(w.lowerBoundScores().orElseThrow()>1);
            var firstProfiles=new ArrayList<InferenceProfile>();
            for(int row=0;row<f.dense().size();row++)firstProfiles.add(AdaptiveSolverView.initial(f.sampled().get(row),f.dense().get(row),f.lattices().get(row).components(),Double.NaN,CancellationProbe.NONE,owner,BPolicy.INVENTORY_FRONTIER_V5).profile());
            var exact=new ProbabilisticInference().solve(firstProfiles,DATA,TraceBudgets.defaults(),null,CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4);
            assertEquals(exact.rawPaths(),next.inference().rawPaths());assertEquals(exact.completion(),next.inference().completion());
            assertEquals(exact.logPartition(),next.inference().logPartition());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void cancellationAfterFirstInvocationCannotReturnThePass() {
        var f=fixture(false);var ledger=AttemptMemoryLedger.production();boolean[] completed={false};
        try(var owner=ledger.rootOwner()) {
            double[] continuation=new double[f.dense().size()];Arrays.fill(continuation,Double.NaN);
            assertThrows(java.util.concurrent.CancellationException.class,()->new AdaptiveBInference().solve(
                f.sampled(),f.dense(),f.lattices(),continuation,DATA,TraceBudgets.defaults(),null,()->completed[0],owner,
                AdaptiveBObserver.NONE,(pairs,scores)->completed[0]=true,BPolicy.INVENTORY_FRONTIER_BUDGETED_V6));
            assertTrue(completed[0]);
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void witnessRejectsForgedArithmeticAndUnknownKeys() {
        var w=new BRefinementBudget.Witness(BRefinementBudget.Outcome.FIRST_GRAPH_PROVEN_UNAFFORDABLE,Optional.of(new CompletedBPassWork(10,5,20,1)),OptionalLong.of(14),OptionalLong.of(15),"a".repeat(64));
        assertEquals(w,BRefinementBudget.parse(w.canonical()));
        assertThrows(IllegalArgumentException.class,()->BRefinementBudget.parse(w.canonical()+"|unknown=1"));
        assertThrows(IllegalArgumentException.class,()->BRefinementBudget.parse(w.canonical().replace("lowerBoundScores=15","lowerBoundScores=14")));
        assertThrows(IllegalArgumentException.class,()->BRefinementBudget.parse(w.canonical().replace("remainingScores=14","remainingScores=15")));
        var original=new BRefinementBudget.Witness(BRefinementBudget.Outcome.ORIGINAL_REFINEMENT,
                Optional.of(new CompletedBPassWork(10,5,20,1)),OptionalLong.of(100),OptionalLong.of(15),"a".repeat(64));
        assertThrows(IllegalArgumentException.class,()->BRefinementBudget.parse(original.canonical()
                .replace("remainingScores=100","remainingScores="+Long.MAX_VALUE)),
                "Actual first work plus remaining work must represent a non-overflowing original budget");
    }
    @Test void noRefinementStillUsesCompletedNativeFirstGraph() {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var result=run(fixture(true),BPolicy.INVENTORY_FRONTIER_BUDGETED_V6,128000000,owner,CancellationProbe.NONE,AdaptiveBObserver.NONE);
            assertEquals(0,result.refinements());
            assertEquals(BRefinementBudget.Outcome.NOT_NEEDED,BRefinementBudget.fromExplanation(result.inference().explanation()).orElseThrow().outcome());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void cancellationAtDecisionOrFinalizationNeverReturnsSavedPass() {
        for(String phase:List.of("finish","refined")) {
            var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var full=fixture(false);
                var f=new Fixture(full.sampled().subList(0,3),full.dense().subList(0,3),full.lattices().subList(0,3));
                var reference=run(f,BPolicy.INVENTORY_FRONTIER_V5,128000000,owner,CancellationProbe.NONE,AdaptiveBObserver.NONE);
                long cap=phase.equals("refined")?128000000:reference.firstScores()+1;
                CancellationProbe cancel=()-> {
                    var frames=Thread.currentThread().getStackTrace();
                    boolean inside=Arrays.stream(frames).anyMatch(frame->frame.getMethodName().equals(phase));
                    return inside;
                };
                assertThrows(java.util.concurrent.CancellationException.class,()->run(f,BPolicy.INVENTORY_FRONTIER_BUDGETED_V6,cap,owner,cancel,AdaptiveBObserver.NONE),phase);
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
}

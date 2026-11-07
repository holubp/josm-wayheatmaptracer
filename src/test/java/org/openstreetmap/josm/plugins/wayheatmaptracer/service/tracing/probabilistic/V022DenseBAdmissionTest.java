package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022DenseBAdmissionTest {
    @Test void admittedGraphUsesEveryOriginalStateOneSolveAndUnchangedNativePosterior() {
        var f=fixture();var sampled=f.sampled();var lattices=f.lattices();var dense=f.dense();
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            int[] callbacks={0},observed={0};
            var result=new AdmittedBInference().solve(sampled,dense,lattices,new double[]{Double.NaN,Double.NaN,Double.NaN},
                    EvidenceModelParameters.withoutShapeTerms(),TraceBudgets.defaults(),null,CancellationProbe.NONE,owner,
                    (pass,row,index,original)->{assertEquals(0,pass);assertEquals(index,original);observed[0]++;},
                    (pairs,scores)->callbacks[0]++);
            assertSame(dense,result.profiles(),"the admitted branch never constructs retained sparse views");
            assertEquals(0,result.refinements());assertEquals(1,callbacks[0],"one native solve including its final progress callback");
            assertEquals(dense.stream().mapToInt(p->p.cells().size()).sum(),observed[0]);
            var exact=new ProbabilisticInference().solve(dense,EvidenceModelParameters.withoutShapeTerms(),TraceBudgets.defaults(),
                    null,CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            var actual=result.inference();assertEquals(exact.status(),actual.status());assertEquals(exact.posteriorUsable(),actual.posteriorUsable());
            assertEquals(exact.logPartition(),actual.logPartition());assertEquals(exact.completion(),actual.completion());
            assertEquals(exact.rawPaths().size(),actual.rawPaths().size());
            for(int i=0;i<exact.rawPaths().size();i++) {assertArrayEquals(exact.rawPaths().get(i).stateIndices(),actual.rawPaths().get(i).stateIndices());assertEquals(exact.rawPaths().get(i).energy(),actual.rawPaths().get(i).energy());}
            for(int i=0;i<dense.size();i++)assertArrayEquals(exact.positionMarginals().get(i),actual.positionMarginals().get(i));
            assertEquals(BPolicy.FULL_EXECUTION_VERSION,actual.workAccounting().orElseThrow().executionPolicy());
            var witness=DenseBAdmission.witnessFromExplanation(actual.explanation());
            assertTrue(DenseBAdmission.validateWitness(witness,TraceBudgets.defaults(),3).admitted());
            assertThrows(IllegalArgumentException.class,()->DenseBAdmission.validateWitness(witness.replace("|branch=dense","|branch=compact"),TraceBudgets.defaults(),3));
        }
        assertEquals(0,ledger.currentBytes());
    }
    private record Fixture(List<ProbabilisticProfile> sampled,List<ProbabilisticStateLattice> lattices,List<InferenceProfile> dense) { }
    private static Fixture fixture() {
        var sampled=new ArrayList<ProbabilisticProfile>();var lattices=new ArrayList<ProbabilisticStateLattice>();
        var dense=new ArrayList<InferenceProfile>();
        for(int row=0;row<3;row++) {
            var samples=new ArrayList<ProbabilisticProfile.Sample>();
            for(int i=-12;i<=12;i++)samples.add(new ProbabilisticProfile.Sample(i*.5,1,true));
            var p=new ProbabilisticProfile(row,row*2,new MetricPoint(row*2,0),new MetricPoint(0,1),-6,6,1,true,
                    .01,samples,List.of(),List.of(),OptionalDouble.empty(),ImageOrientationSupport.unknown(ImageOrientationSupport.Status.UNKNOWN_FLAT));
            sampled.add(p);var lattice=new ProbabilisticStateBuilder().build(p,96).lattice().orElseThrow();lattices.add(lattice);
            double[] unary=new double[lattice.cells().size()];for(int j=0;j<unary.length;j++)unary[j]=j*.01;
            dense.add(new InferenceProfile(p.chainageMeters(),p.anchor(),p.normalUnit(),lattice.cells(),unary,List.of(),0,ObservationOwnership.DIRECT_TWO_SIDED,false));
        }
        return new Fixture(sampled,lattices,dense);
    }
    @Test void rejectedAdmissionRunsOnlyTheUnchangedConditionalBranch() {
        var f=fixture();var ledger=AttemptMemoryLedger.production();var budget=new TraceBudgets(96,8_000_000,1,32,8);
        try(var owner=ledger.rootOwner()) {
            var observed=new ArrayList<String>();int[] callbacks={0};
            var actual=new AdmittedBInference().solve(f.sampled(),f.dense(),f.lattices(),new double[]{Double.NaN,Double.NaN,Double.NaN},
                    EvidenceModelParameters.withoutShapeTerms(),budget,null,CancellationProbe.NONE,owner,
                    (pass,row,index,original)->observed.add(pass+","+row+","+original),(pairs,scores)->callbacks[0]++);
            var oldObserved=new ArrayList<String>();
            var old=new AdaptiveBInference().solve(f.sampled(),f.dense(),f.lattices(),new double[]{Double.NaN,Double.NaN,Double.NaN},
                    EvidenceModelParameters.withoutShapeTerms(),budget,null,CancellationProbe.NONE,owner,
                    (pass,row,index,original)->oldObserved.add(pass+","+row+","+original),ProbabilisticInference.WorkProgress.NONE,BPolicy.ADAPTIVE_MULTIMODAL_V2);
            assertEquals(oldObserved,observed,"no full-graph observation or preliminary solve precedes compact inference");
            assertTrue(observed.size()<f.dense().stream().mapToInt(p->p.cells().size()).sum());assertEquals(1,callbacks[0]);
            assertEquals(old.inference().evaluatedTransitions(),actual.inference().evaluatedTransitions());
            assertEquals(1,actual.inference().evaluatedTransitions());assertFalse(actual.inference().posteriorUsable());
            assertEquals(BPolicy.COMPACT_EXECUTION_VERSION,actual.inference().workAccounting().orElseThrow().executionPolicy());
            assertFalse(DenseBAdmission.validateWitness(DenseBAdmission.witnessFromExplanation(actual.inference().explanation()),budget,3).admitted());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void admittedEarlyZeroRefusalCancellationAndTinyMemoryNeverRetryCompact() {
        var f=fixture();var p=f.dense().get(0);
        var limited=new InferenceProfile(p.chainageMeters(),p.anchor(),p.normalUnit(),p.cells(),p.unaryCosts(),
                ImageOrientationSupport.unknown(ImageOrientationSupport.Status.RESOURCE_LIMIT),p.ownership(),false);
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var result=new AdmittedBInference().solve(f.sampled().subList(0,1),List.of(limited),f.lattices().subList(0,1),new double[]{Double.NaN},
                    EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,CancellationProbe.NONE,owner,AdaptiveBObserver.NONE,ProbabilisticInference.WorkProgress.NONE);
            assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,result.inference().status());assertEquals(0,result.inference().evaluatedTransitions());
            assertEquals(BPolicy.FULL_EXECUTION_VERSION,result.inference().workAccounting().orElseThrow().executionPolicy());assertTrue(result.inference().rawPaths().isEmpty());
            assertEquals(0,result.refinements());assertSame(limited,result.profiles().get(0));
        }
        assertEquals(0,ledger.currentBytes());
        var tiny=new AttemptMemoryLedger(1);
        try(var owner=tiny.rootOwner()) {
            var denied=assertThrows(TraceMemoryLimitException.class,()->new AdmittedBInference().solve(f.sampled(),f.dense(),f.lattices(),new double[3],
                    EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,CancellationProbe.NONE,owner,AdaptiveBObserver.NONE,ProbabilisticInference.WorkProgress.NONE));
            assertEquals(0,denied.transitions());
        }
        assertEquals(0,tiny.currentBytes());
        var cancelled=AttemptMemoryLedger.production();
        try(var owner=cancelled.rootOwner()) {
            assertThrows(java.util.concurrent.CancellationException.class,()->new AdmittedBInference().solve(f.sampled(),f.dense(),f.lattices(),new double[3],
                    EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,()->true,owner,AdaptiveBObserver.NONE,ProbabilisticInference.WorkProgress.NONE));
            assertEquals(0,cancelled.currentBytes());
        }
    }
    @Test void selectedFullNativeCancellationAndMemoryRefusalNeverSwitchGraphs() {
        var f=fixture();var cancelled=AttemptMemoryLedger.production();long[] scores={-1};int[] checkpoints={0};
        try(var owner=cancelled.rootOwner()) {
            assertThrows(java.util.concurrent.CancellationException.class,()->new AdmittedBInference().solve(f.sampled(),f.dense(),f.lattices(),new double[3],
                    EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,()->{
                        boolean inside=Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame->frame.getClassName().equals(ProbabilisticInference.class.getName())&&frame.getMethodName().equals("forwardBackward"));
                        return inside&&++checkpoints[0]==3;
                    },owner,AdaptiveBObserver.NONE,(pairs,computed)->scores[0]=computed));
            assertEquals(1024,scores[0]);assertEquals(0,cancelled.currentBytes());
        }
        var small=new AttemptMemoryLedger(30_000);int[] observed={0};
        try(var owner=small.rootOwner()) {
            var result=new AdmittedBInference().solve(f.sampled(),f.dense(),f.lattices(),new double[3],
                    EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,CancellationProbe.NONE,owner,
                    (pass,row,index,original)->{assertEquals(0,pass);assertEquals(index,original);observed[0]++;},ProbabilisticInference.WorkProgress.NONE);
            assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,result.inference().status());assertTrue(result.inference().rawPaths().isEmpty());
            assertEquals(0,result.refinements());assertSame(f.dense(),result.profiles());
            assertEquals(f.dense().stream().mapToInt(p->p.cells().size()).sum(),observed[0]);
            assertEquals(BPolicy.FULL_EXECUTION_VERSION,result.inference().workAccounting().orElseThrow().executionPolicy());
        }
        assertEquals(0,small.currentBytes());
    }
    static DenseBAdmission.Bounds bound(int[] widths,long pairs,long scores,int k) {
        return DenseBAdmission.estimate(widths.length,i->widths[i],new TraceBudgets(Integer.MAX_VALUE,pairs,scores,k,Math.min(8,k)),CancellationProbe.NONE);
    }
    @Test void independentHandCountsIncludeZeroScoreInitialAndTerminalCases() {
        for(long[] example:new long[][]{{3,3,0},{2,3,6,0},{2,3,4,18,72},{2,3,4,2,26,168}}) {
            int n=example.length-2;int[] widths=new int[n];for(int i=0;i<n;i++)widths[i]=(int)example[i];
            var result=bound(widths,8_000_000,128_000_000,32);
            assertEquals(example[n],result.pairUpper());assertEquals(example[n+1],result.scoreUpper());assertTrue(result.admitted());
        }
    }
    @Test void everyTinyDisconnectedAdjacencyHasTheCartesianBackwardUpperBound() {
        long upper=bound(new int[]{2,2,2},100,100,32).scoreUpper();
        for(int first=0;first<16;first++)for(int second=0;second<16;second++) {
            int forward=0,backward=0;
            for(int a=0;a<2;a++)for(int b=0;b<2;b++)for(int c=0;c<2;c++) {
                if((second&(1<<(b*2+c)))!=0) {
                    backward++; // The native backward loop does not test the first edge.
                    if((first&(1<<(a*2+b)))!=0)forward++;
                }
            }
            long complete=2L*forward+backward; // K-best has one prefix per admitted first pair at N=3.
            assertTrue(complete<=upper);
            if(first==1&&second==4){assertEquals(0,forward);assertEquals(2,backward);assertEquals(2,complete);}
            if(first==1&&second==2){assertEquals(1,forward);assertEquals(2,backward);assertEquals(4,complete);}
            if(first==15&&second==15)assertEquals(upper,complete);
        }
    }
    @Test void admittedSingleAndTwoRowGraphsPreserveUnscoredInitializationAndTerminalPosterior() {
        var f=fixture();var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            for(int n=1;n<=2;n++) {
                var profiles=f.dense().subList(0,n);int[] callbacks={0};
                var actual=new AdmittedBInference().solve(f.sampled().subList(0,n),profiles,f.lattices().subList(0,n),new double[n],
                        EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,CancellationProbe.NONE,owner,
                        AdaptiveBObserver.NONE,(pairs,scores)->callbacks[0]++).inference();
                var exact=new ProbabilisticInference().solve(profiles,EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,
                        CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
                assertEquals(0,actual.evaluatedTransitions());assertEquals(1,callbacks[0]);
                assertEquals(exact.evaluatedPairVisits(),actual.evaluatedPairVisits());assertEquals(exact.logPartition(),actual.logPartition());
                assertEquals(exact.completion(),actual.completion());assertEquals(exact.posteriorUsable(),actual.posteriorUsable());
                for(int i=0;i<exact.rawPaths().size();i++){assertArrayEquals(exact.rawPaths().get(i).stateIndices(),actual.rawPaths().get(i).stateIndices());assertEquals(exact.rawPaths().get(i).energy(),actual.rawPaths().get(i).energy());}
            }
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void inclusiveBudgetsKAndStateEligibilityNeverUseRadiusOrReachability() {
        assertTrue(bound(new int[]{2,3,4},18,72,32).admitted());
        assertFalse(bound(new int[]{2,3,4},18,71,32).admitted());
        assertFalse(bound(new int[]{2,3,4},17,72,32).admitted());
        assertFalse(bound(new int[]{2,3},5,1,32).admitted(),"pair-only initialization can refuse admission");
        assertEquals(144,bound(new int[]{2,3,4,2},100,1000,1).scoreUpper());
        assertEquals(168,bound(new int[]{2,3,4,2},100,1000,2).scoreUpper());
        var state=DenseBAdmission.estimate(1,i->3,new TraceBudgets(2,100,100,32,8),CancellationProbe.NONE);
        assertFalse(state.admitted());assertFalse(state.stateEligible());
        // Disconnected [2,2,2] backward still visits every before state for each legal next pair.
        // A0={(0,0)},A1={(1,0)} has F=0,B=2,KB=0, while live A1={(0,1)} has F=1,B=2,KB=1.
        assertEquals(24,bound(new int[]{2,2,2},100,100,32).scoreUpper());
    }
    @Test void saturationAndOverflowCannotTurnExceededBoundsIntoAdmission() {
        var over=bound(new int[]{Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},Long.MAX_VALUE,Long.MAX_VALUE,32);
        assertFalse(over.admitted());assertTrue(over.scoresExceeded());assertEquals(Long.MAX_VALUE,over.scoreUpper());
        var small=bound(new int[]{2,3,4},100,50,32);assertEquals(51,small.scoreUpper());assertTrue(small.scoresExceeded());
        var pair=bound(new int[]{Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE},Long.MAX_VALUE,Long.MAX_VALUE,32);
        assertTrue(pair.pairsExceeded());
        assertThrows(java.util.concurrent.CancellationException.class,()->DenseBAdmission.estimate(3,i->2,
                TraceBudgets.defaults(),()->true));
        assertThrows(IllegalArgumentException.class,()->bound(new int[]{2,0,3},100,100,32));
    }
}

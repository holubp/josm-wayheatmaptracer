package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022SingleTrainComparatorSolverTest {
    static List<InferenceProfile> normalizedGraph(){
        var template=V022SingleTrainCostObjectiveTest.graph(true);double[] x={0,19,39,45,60,75,81,101,120};var result=new ArrayList<InferenceProfile>();
        for(int i=0;i<9;i++){var p=template.get(i==0?0:i==8?6:i<5?1:3);var unary=new double[p.cells().size()];for(int s=0;s<unary.length;s++)unary[s]=p.unaryCost(s);
            result.add(new InferenceProfile(x[i],new MetricPoint(x[i],2),new MetricPoint(0,1),p.cells(),unary,List.of(0d),.6,p.ownership(),false));}return result;
    }
    static List<List<MetricPoint>> rows(List<InferenceProfile> p){return p.stream().map(row->{var points=new ArrayList<MetricPoint>();for(int s=0;s<row.cells().size();s++)points.add(row.point(s));return List.copyOf(points);}).toList();}
    static boolean accepted(List<InferenceProfile> p,int[] s) throws Exception {
        for(int i=1;i<s.length;i++){var a=p.get(i-1).point(s[i-1]);var b=p.get(i).point(s[i]);var clip=BSingleTrainDiagnosticBridge.clip(a,b);
            if(clip.isPresent()&&!V022SingleTrainComparatorRegionTest.union().containsSegment(clip.get().get(0),clip.get().get(1)))return false;}return true;
    }
    @Test void actualPublicFullSolverMatchesIndependentNormalizedEnumerationWithAndWithoutConstraint() throws Exception {
        var p=normalizedGraph();int[] best=null,accepted=null;double min=Double.POSITIVE_INFINITY,acceptedMin=Double.POSITIVE_INFINITY;
        for(int mask=0;mask<128;mask++){int[] s=new int[9];for(int i=1;i<8;i++)s[i]=(mask>>(7-i))&1;double e=V022SingleTrainCostObjectiveTest.independent(p,s);
            if(e<min){min=e;best=s;}if(accepted(p,s)&&e<acceptedMin){acceptedMin=e;accepted=s;}}
        assertFalse(accepted(p,best));assertTrue(acceptedMin>min);var ledger=new AttemptMemoryLedger(2_000_000);
        try(var owner=ledger.rootOwner()) {
            var candidate=BSingleTrainComparatorRegion.candidate(rows(p),V022SingleTrainComparatorRegionTest.union().polygons(),owner);
            var work=new BSingleTrainComparatorRegion.Work(28,1_000_000);
            var gate=BSingleTrainComparatorRegion.certify(rows(p),V022SingleTrainComparatorRegionTest.hard(),candidate,V022SingleTrainComparatorRegionTest.union(),work,owner,CancellationProbe.NONE);
            var budgets=new TraceBudgets(96,8_000_000,128_000_000,1,1);
            var baseline=new ProbabilisticInference().solve(p,EvidenceModelParameters.defaults(),budgets,V022SingleTrainComparatorRegionTest.hard(),CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            assertArrayEquals(best,baseline.mapPath().orElseThrow().stateIndices());assertEquals(V022SingleTrainCostObjectiveTest.bits(min),V022SingleTrainCostObjectiveTest.bits(baseline.mapPath().orElseThrow().energy()));
            var actual=gate.solveOnce(region->new ProbabilisticInference().solve(p,EvidenceModelParameters.defaults(),budgets,region,CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2));
            var path=BSingleTrainComparatorTool.requireUsable(actual);assertArrayEquals(accepted,path.stateIndices());assertEquals(V022SingleTrainCostObjectiveTest.bits(acceptedMin),V022SingleTrainCostObjectiveTest.bits(path.energy()));
            assertTrue(actual.workAccounting().orElseThrow().forwardBackwardScores()>0);assertTrue(actual.workAccounting().orElseThrow().kBestScores()>0);
            assertEquals(actual.workAccounting().orElseThrow().computedScores(),actual.evaluatedTransitions());assertTrue(actual.completion().orElseThrow().rawEnumerationCapped());
            var accounted=BSingleTrainObjectiveAccounting.account(p,path.stateIndices(),EvidenceModelParameters.defaults(),CancellationProbe.NONE);BSingleTrainComparatorTool.requireBits(path.energy(),accounted.energy());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void exactLexicalTiesDoNotGainAMapCellWidthTerm() throws Exception {
        var p=new ArrayList<InferenceProfile>();
        for(int i=0;i<3;i++){var cells=i==1?List.of(new LateralStateCell(0,.5,false,true,"a"),new LateralStateCell(.25,1.5,false,true,"b")):List.of(new LateralStateCell(0,1,true,true,"anchor"));
            p.add(new InferenceProfile(i*60,new MetricPoint(i*60,0),new MetricPoint(0,1),cells,new double[cells.size()],List.of(),0,ObservationOwnership.NO_RASTER,true));}
        var ledger=new AttemptMemoryLedger(2_000_000);var params=new EvidenceModelParameters("tie",0,0,0,0,0,.5,1,2);
        try(var owner=ledger.rootOwner()){
            var candidate=BSingleTrainComparatorRegion.candidate(rows(p),V022SingleTrainComparatorRegionTest.union().polygons(),owner);
            var gate=BSingleTrainComparatorRegion.certify(rows(p),V022SingleTrainComparatorRegionTest.hard(),candidate,V022SingleTrainComparatorRegionTest.union(),new BSingleTrainComparatorRegion.Work(4,1_000_000),owner,CancellationProbe.NONE);
            var result=gate.solveOnce(region->new ProbabilisticInference().solve(p,params,new TraceBudgets(96,1000,1000,1,1),region,CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2));
            assertArrayEquals(new int[]{0,0,0},result.mapPath().orElseThrow().stateIndices());assertEquals(0,result.mapPath().orElseThrow().energy());
            assertEquals(.25,result.positionMarginals().get(1)[0],1e-14);assertEquals(.75,result.positionMarginals().get(1)[1],1e-14);
        }
        assertEquals(0,ledger.currentBytes());
    }
}

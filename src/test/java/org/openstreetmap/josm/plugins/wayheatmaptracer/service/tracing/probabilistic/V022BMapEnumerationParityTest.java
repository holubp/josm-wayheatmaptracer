package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Enumeration may change diagnostics/completion; it must preserve the exact first graph path. */
class V022BMapEnumerationParityTest {
    @Test void negativeUnaryAndExactTiesKeepTheSameFirstStatesCoordinatesAndEnergyBits() {
        for(double unary:new double[]{0,-2}) {
            var rows=new ArrayList<InferenceProfile>();
            var symmetric=List.of(new LateralStateCell(-1,1,false,true,"unlocalized"),new LateralStateCell(1,1,false,true,"unlocalized"));
            for(int i=0;i<5;i++)rows.add(new InferenceProfile(2*i,new MetricPoint(2*i,0),new MetricPoint(0,1),symmetric,
                    new double[]{unary,unary},List.of(),0,ObservationOwnership.DIRECT_TWO_SIDED,false));
            var proofLedger=new AttemptMemoryLedger(64L*1024*1024);
            try(var owner=proofLedger.rootOwner()) {
                var result=new ProbabilisticInference().solve(rows,EvidenceModelParameters.defaults(),TraceBudgets.defaults(),
                        MetricRegion.rectangle(-2,-26,12,26),CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
                assertTrue(result.rawPaths().size()>=2,"actual full solver must expose the tied first two paths");
                assertEquals(Double.doubleToLongBits(result.rawPaths().get(0).energy()),Double.doubleToLongBits(result.rawPaths().get(1).energy()));
                assertArrayEquals(new int[]{0,0,0,0,0},result.mapPath().orElseThrow().stateIndices(),"lexicographic first symmetric state sequence");
            }
            assertEquals(0,proofLedger.currentBytes());
            parity(rows,TraceBudgets.defaults());
        }
    }
    @Test void conditionedAnchorsCensoredOwnershipMetadataAndMissingGapKeepTheSameFirstPath() {
        var rows=List.of(row(0,new double[]{0,0,0},true,ObservationOwnership.FIXED_TOPOLOGY_ONLY,false),
                row(1,new double[]{2,-1,1},false,ObservationOwnership.DIRECT_TWO_SIDED,false),
                row(2,new double[]{0,0,0},false,ObservationOwnership.NO_RASTER,true),
                row(3,new double[]{1,0,0},false,ObservationOwnership.CORE_CENSORED,true),
                row(4,new double[]{0,0,0},true,ObservationOwnership.FIXED_TOPOLOGY_ONLY,false));
        parity(rows,TraceBudgets.defaults());
    }
    @Test void sameSmallActuallyCoarsenedGraphRetainsDefaultAlternativeAdmissionAndBothEnumerationCaps() {
        var defaults=TraceBudgets.defaults();
        var lower=new TraceBudgets(defaults.maximumStatesPerProfile(),defaults.maximumPairVisits(),10_000,
                defaults.maximumRawAlternatives(),defaults.maximumDistinctAlternatives());
        var original=new ArrayList<ProbabilisticProfile>();
        for(int i=0;i<3;i++)original.add(new ProbabilisticProfile(i,2*i,new MetricPoint(2*i,0),new MetricPoint(0,1),-25,25,.5,true,0,
                List.of(new ProbabilisticProfile.Sample(-25,0,true),new ProbabilisticProfile.Sample(25,0,true)),List.of(),List.of(),OptionalDouble.empty(),List.of(),0));
        var proposals=new double[]{Double.NaN,Double.NaN,Double.NaN};
        var plan=ProbabilisticLatticeAdmission.select(original,proposals,lower,CancellationProbe.NONE,null);
        assertTrue(plan.admitted());assertTrue(plan.coarsened());assertTrue(plan.nativeForwardBackwardBound()>lower.maximumTransitions());
        var builder=new ProbabilisticStateBuilder();var rows=new ArrayList<InferenceProfile>();
        for(int i=0;i<original.size();i++) {
            var lattice=builder.buildWithReservedProposal(original.get(i),plan.maximumStates(i),proposals[i],null).lattice().orElseThrow();
            var unary=new double[lattice.cells().size()];for(int s=0;s<unary.length;s++)unary[s]=(s&1)==0?-.5:0;
            rows.add(new InferenceProfile(2*i,new MetricPoint(2*i,0),new MetricPoint(0,1),lattice.cells(),unary,List.of(),0,ObservationOwnership.DIRECT_TWO_SIDED,false));
        }
        parity(rows,lower);
    }
    private static void parity(List<InferenceProfile> rows,TraceBudgets budgets) {
        var ledger=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner()) {
            var region=MetricRegion.rectangle(-2,-26,12,26);
            var full=BObservationGeometry.solveAdmitted(rows,region,budgets,BObservationGeometry.Enumeration.FULL_DEFAULT,CancellationProbe.NONE,owner);
            var map=BObservationGeometry.solveAdmitted(rows,region,budgets,BObservationGeometry.Enumeration.MAP_ONLY,CancellationProbe.NONE,owner);
            assertFalse(full.states().isEmpty());assertEquals(full.states(),map.states());
            assertEquals(full.path().points().size(),map.path().points().size());
            for(int i=0;i<full.path().points().size();i++) {
                assertEquals(Double.doubleToLongBits(full.path().points().get(i).xMeters()),Double.doubleToLongBits(map.path().points().get(i).xMeters()));
                assertEquals(Double.doubleToLongBits(full.path().points().get(i).yMeters()),Double.doubleToLongBits(map.path().points().get(i).yMeters()));
                var profile=rows.get(i);
                for(var cell:profile.cells())if(cell.exactAnchor()) {
                    var expected=new MetricPoint(profile.anchor().xMeters()+profile.normalUnit().xMeters()*cell.offsetMeters(),
                            profile.anchor().yMeters()+profile.normalUnit().yMeters()*cell.offsetMeters());
                    assertEquals(expected,full.path().points().get(i));assertEquals(expected,map.path().points().get(i));
                }
            }
            assertEquals(Double.doubleToLongBits(full.energy().orElseThrow()),Double.doubleToLongBits(map.energy().orElseThrow()));
            assertEquals(budgets,full.solverBudgets());assertEquals(1,map.solverBudgets().maximumRawAlternatives());assertEquals(1,map.solverBudgets().maximumDistinctAlternatives());
            assertEquals(budgets.maximumStatesPerProfile(),map.solverBudgets().maximumStatesPerProfile());
            assertEquals(budgets.maximumPairVisits(),map.solverBudgets().maximumPairVisits());assertEquals(budgets.maximumTransitions(),map.solverBudgets().maximumTransitions());
        }
        assertEquals(0,ledger.currentBytes());
    }
    private static InferenceProfile row(int i,double[] costs,boolean anchor,ObservationOwnership ownership,boolean missing) {
        var cells=anchor?List.of(new LateralStateCell(0,1,true,true,"unlocalized")):
                List.of(new LateralStateCell(-1,1,false,true,"unlocalized"),new LateralStateCell(0,1,false,true,"unlocalized"),new LateralStateCell(1,1,false,true,"unlocalized"));
        return new InferenceProfile(i*2,new MetricPoint(i*2,0),new MetricPoint(0,1),cells,anchor?new double[]{0}:costs,List.of(),0,ownership,missing);
    }
}

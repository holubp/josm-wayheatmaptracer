package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022SingleTrainCostObjectiveTest {
    static long bits(double value){return Double.doubleToRawLongBits(value);}
    /** Normalized physical two-ridge mixture, not a production catalog acquisition. */
    static List<InferenceProfile> graph(boolean switching) {
        var result=new ArrayList<InferenceProfile>();
        for(int i=0;i<7;i++) {
            boolean anchor=i==0||i==6;
            var cells=anchor?List.of(new LateralStateCell(-2,1,true,true,"left")):
                    List.of(new LateralStateCell(-2,.5,false,true,"left"),new LateralStateCell(2,1.5,false,true,"right"));
            double left=switching&&i>=2&&i<=4?.0001:.9,right=.99-left;
            double[] ld=anchor?new double[]{0}:new double[]{StrictMath.log(2),Double.NEGATIVE_INFINITY};
            double[] rd=anchor?new double[]{0}:new double[]{Double.NEGATIVE_INFINITY,-StrictMath.log(1.5)};
            double[] missing=anchor?new double[]{0}:new double[]{-StrictMath.log(2),-StrictMath.log(2)};
            var components=List.of(new ObservationComponent("left","left",ObservationComponent.Kind.MEASURED,left,false),
                    new ObservationComponent("right","right",ObservationComponent.Kind.MEASURED,right,false),
                    new ObservationComponent("missing","missing",ObservationComponent.Kind.MISSING,.01,false));
            var lattice=new ProbabilisticStateLattice(cells,components,.5,false);
            var mix=BObservationMixture.create(List.of(
                    BObservationMixture.Component.create("left","left",ObservationComponent.Kind.MEASURED,left,ld,null),
                    BObservationMixture.Component.create("right","right",ObservationComponent.Kind.MEASURED,right,rd,null),
                    BObservationMixture.Component.create("missing","missing",ObservationComponent.Kind.MISSING,.01,missing,null)),null);
            var reduced=mix.reduce(lattice,CancellationProbe.NONE,null);double[] unary=new double[cells.size()];
            for(int s=0;s<unary.length;s++)unary[s]=reduced.unaryCost(s);
            if(!anchor) {
                assertEquals(-StrictMath.log(.005+left/.5),unary[0],1e-14);
                assertEquals(-StrictMath.log(.005+right/1.5),unary[1],1e-14);
            }
            result.add(new InferenceProfile(1.5*i,new MetricPoint(1.5*i,0),new MetricPoint(0,1),cells,
                    unary,List.of(0d),.6,ObservationOwnership.DIRECT_TWO_SIDED,false));
        }
        return result;
    }
    static double independent(List<InferenceProfile> p,int[] s) {
        var weights=EvidenceModelParameters.defaults();
        double e=(FrozenPairLocalEnergyReference.profileEnergy(p,0,s[0],weights)
                +FrozenPairLocalEnergyReference.profileEnergy(p,1,s[1],weights))
                +FrozenPairLocalEnergyReference.pairEnergy(p,0,s[0],s[1],weights);
        for(int i=2;i<s.length;i++)e=e+FrozenPairLocalEnergyReference.increment(p,i,s[i-2],s[i-1],s[i],weights);
        return e;
    }
    @Test void normalizedTwoRidgeFullEnumerationMatchesActualSolverAndAccountingBits() throws Exception {
        for(boolean switching:List.of(false,true)) {
            var p=graph(switching);int[] best=null;double min=Double.POSITIVE_INFINITY;boolean reassociationDetected=false;
            for(int mask=0;mask<32;mask++) {
                int[] s=new int[7];for(int i=1;i<6;i++)s[i]=(mask>>(5-i))&1;
                double e=independent(p,s);
                double regrouped=(FrozenPairLocalEnergyReference.profileEnergy(p,0,s[0],EvidenceModelParameters.defaults())
                        +FrozenPairLocalEnergyReference.profileEnergy(p,1,s[1],EvidenceModelParameters.defaults()))
                        +FrozenPairLocalEnergyReference.pairEnergy(p,0,s[0],s[1],EvidenceModelParameters.defaults());
                for(int i=2;i<s.length;i++)regrouped=((regrouped+FrozenPairLocalEnergyReference.profileEnergy(p,i,s[i],EvidenceModelParameters.defaults()))
                        +FrozenPairLocalEnergyReference.pairEnergy(p,i-1,s[i-1],s[i],EvidenceModelParameters.defaults()))
                        +FrozenPairLocalEnergyReference.tripleEnergy(p,i,s[i-2],s[i-1],s[i],EvidenceModelParameters.defaults());
                reassociationDetected|=bits(e)!=bits(regrouped);
                assertEquals(bits(e),bits(BSingleTrainObjectiveAccounting.account(p,s,EvidenceModelParameters.defaults(),CancellationProbe.NONE).energy()));
                if(e<min){min=e;best=s;}
            }
            assertTrue(reassociationDetected,"fixture must detect invalid reassociation of the grouped increment");
            if(switching)assertTrue(Arrays.stream(best).anyMatch(s->s==1));
            else assertArrayEquals(new int[7],best);
            var ledger=new AttemptMemoryLedger(1_000_000);var owner=ledger.rootOwner();
            try {
                var solved=new ProbabilisticInference().solve(p,EvidenceModelParameters.defaults(),
                        new TraceBudgets(96,100_000,1_000_000,32,1),null,CancellationProbe.NONE,owner,
                        ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
                assertArrayEquals(best,solved.mapPath().orElseThrow().stateIndices());
                assertEquals(bits(min),bits(solved.mapPath().orElseThrow().energy()));
            } finally {owner.close();assertEquals(0,ledger.currentBytes());}
        }
    }
    @Test void widthsAreIntegrationMetadataAndGuideAndUnequalLongitudinalQuadratureUseActualGrouping() throws Exception {
        var original=graph(true);var changed=new ArrayList<InferenceProfile>();
        for(int i=0;i<original.size();i++) {
            var p=original.get(i);var cells=p.cells().stream().map(c->new LateralStateCell(c.offsetMeters(),c.quadratureWidthMeters()*3,c.exactAnchor(),c.mandatory(),c.branchLabel())).toList();
            double[] unary=new double[cells.size()];for(int s=0;s<unary.length;s++)unary[s]=p.unaryCost(s);
            changed.add(new InferenceProfile(p.chainageMeters(),p.anchor(),p.normalUnit(),cells,unary,List.of(0d),.6,p.ownership(),false));
        }
        int[] states={0,0,1,1,1,0,0};
        assertEquals(bits(independent(original,states)),bits(BSingleTrainObjectiveAccounting.account(changed,states,EvidenceModelParameters.defaults(),CancellationProbe.NONE).energy()));
        var guide=new ArrayList<InferenceProfile>();
        for(int i=0;i<changed.size();i++) {
            var p=changed.get(i);double[] u=new double[p.cells().size()],g=new double[u.length];
            for(int s=0;s<u.length;s++){u[s]=p.unaryCost(s);g[s]=.17*(i+s);}
            guide.add(new InferenceProfile(i*i+.25*i,new MetricPoint(i*1.5,i%2*.2),new MetricPoint(-0.0,1),p.cells(),u,List.of(0d),.6,p.ownership(),false).withStructuralGuideCosts(g));
        }
        var account=BSingleTrainObjectiveAccounting.account(guide,states,EvidenceModelParameters.defaults(),CancellationProbe.NONE);
        assertEquals(bits(independent(guide,states)),bits(account.energy()));
        for(int i=0;i<7;i++)assertEquals(bits(FrozenPairLocalEnergyReference.profileQuadrature(guide,i)),bits(((Number)account.rows().get(i).get("longitudinalQuadratureMeters")).doubleValue()));
        assertTrue(((Number)account.rows().get(2).get("tripleTerm")).doubleValue()>0);
        assertTrue(((Number)account.rows().get(3).get("tripleTerm")).doubleValue()>0);
    }
    @Test void mismatchStopsBeforeWitnessAndBoundsCancellationAndNonfiniteAreHonest() throws Exception {
        var p=graph(true);int[] s={0,0,1,1,1,0,0};var counts=new BSingleTrainObjectiveAccounting.Counts();
        assertThrows(SecurityException.class,()->BSingleTrainCostDiagnosticTool.accountPair(p,s,s,Math.nextUp(independent(p,s)),CancellationProbe.NONE,counts));
        assertEquals(7,counts.profiles);assertEquals(6,counts.pairs);assertEquals(5,counts.triples);
        assertEquals(1,counts.completedAccounts,"an archived account completed before its binding mismatch; no witness account ran");
        var result=BSingleTrainCostDiagnosticTool.accountPair(p,s,s,independent(p,s),CancellationProbe.NONE,new BSingleTrainObjectiveAccounting.Counts());
        assertEquals(bits(result.archived().energy()),bits(result.witness().energy()));
        var refusal=new BSingleTrainObjectiveAccounting.Counts(1,1,1,100);
        assertThrows(BObservationIdentificationTool.RunLimitException.class,()->BSingleTrainObjectiveAccounting.account(p,s,EvidenceModelParameters.defaults(),CancellationProbe.NONE,refusal));
        assertEquals(1,refusal.profiles);
        var cancelled=new BSingleTrainObjectiveAccounting.Counts();
        assertThrows(java.util.concurrent.CancellationException.class,()->BSingleTrainObjectiveAccounting.account(p,s,EvidenceModelParameters.defaults(),()->true,cancelled));
        assertEquals(0,cancelled.calls);
        int[] bad=s.clone();bad[3]=2;
        assertThrows(IllegalArgumentException.class,()->BSingleTrainObjectiveAccounting.account(p,bad,EvidenceModelParameters.defaults(),CancellationProbe.NONE));
        assertEquals("NEGATIVE_INFINITY",BSingleTrainObjectiveAccounting.number(Double.NEGATIVE_INFINITY));
        assertEquals("NONFINITE_NAN",BSingleTrainObjectiveAccounting.number(Double.NaN));
        assertEquals(0d,BSingleTrainObjectiveAccounting.number(0d));
    }
    @Test void fourFullSyntheticAccountsReachExactTermBoundsAndRefuseAnotherWithoutExtraWork() throws Exception {
        var template=graph(true);var profiles=new ArrayList<InferenceProfile>();
        for(int i=0;i<81;i++) {
            var original=template.get(i==0?0:i==80?6:1+i%5);var unary=new double[original.cells().size()];
            for(int s=0;s<unary.length;s++)unary[s]=original.unaryCost(s);
            profiles.add(new InferenceProfile(i*1.5,new MetricPoint(i*1.5,0),new MetricPoint(0,1),original.cells(),unary,List.of(0d),.6,original.ownership(),false));
        }
        int[] states=new int[81];var counts=new BSingleTrainObjectiveAccounting.Counts();
        for(int i=0;i<4;i++)BSingleTrainObjectiveAccounting.account(profiles,states,EvidenceModelParameters.defaults(),CancellationProbe.NONE,counts);
        assertEquals(324,counts.profiles);assertEquals(320,counts.pairs);assertEquals(316,counts.triples);assertEquals(1924,counts.calls);assertEquals(4,counts.completedAccounts);
        assertThrows(BObservationIdentificationTool.RunLimitException.class,()->BSingleTrainObjectiveAccounting.account(profiles,states,EvidenceModelParameters.defaults(),CancellationProbe.NONE,counts));
        assertEquals(1924,counts.calls);assertEquals(324,counts.profiles);
    }
    @Test void nonfiniteStoredControlRemainsNonfiniteWithoutInventingAnotherPath() throws Exception {
        var p=new ArrayList<>(graph(false));var original=p.get(3);
        p.set(3,new InferenceProfile(original.chainageMeters(),original.anchor(),original.normalUnit(),original.cells(),
                new double[]{original.unaryCost(0),Double.MAX_VALUE},List.of(0d),.6,original.ownership(),false));
        int[] archived=new int[7],witness=new int[7];witness[3]=1;var counts=new BSingleTrainObjectiveAccounting.Counts();
        var pair=BSingleTrainCostDiagnosticTool.accountPair(p,archived,witness,independent(p,archived),CancellationProbe.NONE,counts);
        assertEquals(Double.POSITIVE_INFINITY,pair.witness().energy());assertEquals("POSITIVE_INFINITY",BSingleTrainObjectiveAccounting.number(pair.witness().energy()));
        assertEquals(2,counts.completedAccounts);assertEquals(14,counts.profiles);
    }
}

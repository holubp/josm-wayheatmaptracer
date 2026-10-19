package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022SingleTrainCostSnapshotTest {
    @Test void actualKernelAdapterRowsAndIdentityAreUnchangedAndSelectedScalarsSurviveScratch() throws Exception {
        var loaded=BSingleTrainDiagnosticBridge.open(V022SingleTrainDiagnosticBridgeTest.definition(),"a".repeat(64),"b".repeat(64),"c".repeat(64),
                ()->{},d->V022SingleTrainDiagnosticBridgeTest.toy(),BObservationIdentificationTool.Controls.defaults());
        List<List<Map<String,Object>>> copied;
        try(loaded;var owner=loaded.owner.child("toy-cost-snapshot")) {
            int i=loaded.profiles.size()/2;var facts=loaded.facts.get(i);var lattice=loaded.admitted.lattices().get(i);
            var delegate=new BIdentifiedObservationKernel(new BIdentifiedObservationKernel.Parameters(1,1,0));
            var expected=BObservationAdapter.evaluate(facts,lattice,EvidenceModelParameters.defaults(),true,delegate,CancellationProbe.NONE,owner);
            var counts=new BSingleTrainObjectiveAccounting.Counts();var seen=new BObservationMixture[1];
            BObservationKernel checking=new BObservationKernel(){
                public String identityHash(){return delegate.identityHash();}
                public BObservationMixture evaluate(BIdentificationFacts.ProfileFacts f,ProbabilisticStateLattice l,CancellationProbe c,AttemptMemoryLedger.Owner o){return seen[0]=delegate.evaluate(f,l,c,o);}
            };
            var wrapper=new BSingleTrainObjectiveAccounting.SnapshotKernel(checking,new int[]{0,lattice.cells().size()-1},counts,owner);
            var actual=BObservationAdapter.evaluate(facts,lattice,EvidenceModelParameters.defaults(),true,wrapper,CancellationProbe.NONE,owner);
            assertEquals(delegate.identityHash(),wrapper.identityHash());assertEquals(2,wrapper.selected.size());copied=wrapper.selected;
            for(int s=0;s<lattice.cells().size();s++) {
                assertEquals(V022SingleTrainCostObjectiveTest.bits(expected.unaryCost(s)),V022SingleTrainCostObjectiveTest.bits(actual.unaryCost(s)));
                assertEquals(expected.orientationSupport(s),actual.orientationSupport(s));
                assertEquals(expected.orientationReliability(s),actual.orientationReliability(s));
                assertEquals(expected.structuralGuideCost(s),actual.structuralGuideCost(s));
                for(int c=0;c<actual.componentCount();c++)assertEquals(expected.componentResponsibility(s,c),actual.componentResponsibility(s,c));
            }
            for(int path=0;path<2;path++)for(int c=0;c<lattice.components().size();c++) {
                var row=copied.get(path).get(c);assertEquals(seen[0].component(c).mass(),row.get("q"));
                assertEquals(BSingleTrainObjectiveAccounting.number(seen[0].component(c).logL0DensityAt(path==0?0:lattice.cells().size()-1)),row.get("logL0Density"));
            }
            assertEquals(2*lattice.components().size(),counts.components);
            var direct=new BSingleTrainObjectiveAccounting.SnapshotKernel(checking,new int[]{0,0},new BSingleTrainObjectiveAccounting.Counts(),owner);
            var returned=direct.evaluate(facts,lattice,CancellationProbe.NONE,owner);
            assertSame(seen[0],returned,"wrapper must return the identical actual mixture, not rebuild it");
        }
        assertEquals(0,loaded.ledger.currentBytes());assertFalse(copied.get(0).get(0).isEmpty());
    }
    @Test void snapshotReservationCancellationAndClosedOwnerRefuseWithoutDelegateOrLeak() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var lattice=new ProbabilisticStateLattice(List.of(new LateralStateCell(0,1,true,true,"anchor")),
                List.of(new ObservationComponent("missing","missing",ObservationComponent.Kind.MISSING,1,false)),1,false);
        BObservationKernel delegate=new BObservationKernel(){
            public String identityHash(){return "a".repeat(64);}
            public BObservationMixture evaluate(BIdentificationFacts.ProfileFacts f,ProbabilisticStateLattice l,CancellationProbe c,AttemptMemoryLedger.Owner o){calls.incrementAndGet();throw new AssertionError("Must refuse before delegate");}
        };
        var ledger=new AttemptMemoryLedger(8);var owner=ledger.rootOwner();var counts=new BSingleTrainObjectiveAccounting.Counts();
        var wrapper=new BSingleTrainObjectiveAccounting.SnapshotKernel(delegate,new int[]{0,0},counts,owner);
        assertThrows(RuntimeException.class,()->wrapper.evaluate(null,lattice,CancellationProbe.NONE,null));assertEquals(0,ledger.currentBytes());
        assertThrows(java.util.concurrent.CancellationException.class,()->wrapper.evaluate(null,lattice,()->true,null));
        owner.close();assertThrows(IllegalStateException.class,()->wrapper.evaluate(null,lattice,CancellationProbe.NONE,null));
        assertEquals(0,calls.get());assertEquals(0,counts.components);
    }
}

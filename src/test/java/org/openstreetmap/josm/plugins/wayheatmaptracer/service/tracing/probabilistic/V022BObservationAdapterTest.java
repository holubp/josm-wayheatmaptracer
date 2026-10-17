package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022BObservationAdapterTest {
    @Test void actualProfileAndLatticeUseTheSoleKernelReducerWithoutChangingOtherInputs() {
        var facts=facts();var lattice=lattice(facts);var params=EvidenceModelParameters.defaults();var kernel=kernel();
        var baseline=new ProbabilisticObservationModel().evaluate(facts.baseline(),lattice,params);
        var expected=kernel.evaluate(facts,lattice,CancellationProbe.NONE,null).reduce(lattice,CancellationProbe.NONE,null);
        var actual=BObservationAdapter.evaluate(facts,lattice,params,true,kernel,CancellationProbe.NONE,null);
        boolean changed=false;
        for(int state=0;state<lattice.cells().size();state++) {
            assertEquals(expected.unaryCost(state),actual.unaryCost(state),0);
            changed|=baseline.unaryCost(state)!=actual.unaryCost(state);
            assertEquals(baseline.orientationSupport(state),actual.orientationSupport(state));
            assertEquals(baseline.orientationReliability(state),actual.orientationReliability(state));
            assertEquals(baseline.structuralGuideCost(state),actual.structuralGuideCost(state));
            for(int component=0;component<lattice.components().size();component++)
                assertEquals(expected.responsibility(state,component),actual.componentResponsibility(state,component),0);
        }
        assertTrue(changed,"this fixture must detect a bypass to baseline B");
        assertEquals(baseline.ownership(),actual.ownership());assertEquals(baseline.entirelyMissing(),actual.entirelyMissing());
    }
    @Test void cancellationAndRefusalLeaveNoPartiallyRetainedReplacement() {
        var facts=facts();var lattice=lattice(facts);var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            int[] count={0};
            assertThrows(java.util.concurrent.CancellationException.class,()->BObservationAdapter.evaluate(facts,lattice,
                    EvidenceModelParameters.defaults(),true,kernel(),()->++count[0]>5,owner));
            assertEquals(0,ledger.currentBytes());
        }
        var tiny=new AttemptMemoryLedger(8);
        try(var owner=tiny.rootOwner()) {
            assertThrows(RuntimeException.class,()->BObservationAdapter.evaluate(facts,lattice,
                    EvidenceModelParameters.defaults(),true,kernel(),CancellationProbe.NONE,owner));
            assertEquals(0,tiny.currentBytes());
        }
    }
    private static BIdentificationFacts.ProfileFacts facts() {
        var evidence=V022LocalizationEvidenceTest.snapshot((x,y)->.02+.65*Math.exp(-.5*Math.pow((y-34)/1.1,2)));
        var profiles=new ProbabilisticProfileFactory().create(List.of(new MetricPoint(10,30),new MetricPoint(110,30)),
                2,25,true,evidence,evidence.fields().get("scalar"));
        var profile=profiles.get(profiles.size()/2);
        return new BIdentificationFactsExtractor().extract(evidence,List.of(profile),CancellationProbe.NONE,null).get(0);
    }
    private static ProbabilisticStateLattice lattice(BIdentificationFacts.ProfileFacts facts) {
        return new ProbabilisticStateBuilder().build(facts.baseline(),96).lattice().orElseThrow();
    }
    private static BObservationKernel kernel() {
        return new BIdentifiedObservationKernel(new BIdentifiedObservationKernel.Parameters(16,1,1));
    }
}

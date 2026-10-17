package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022BObservationGeometryTest {
    @Test void actualSecondOrderSolverProducesComparableRawPathsWithExactAnchors() {
        var input=input();var ledger=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner()) {
            var baseline=BObservationGeometry.solve(input.facts,input.lattices,input.region,budgets(),null,CancellationProbe.NONE,owner);
            var candidate=BObservationGeometry.solve(input.facts,input.lattices,input.region,budgets(),kernel(),CancellationProbe.NONE,owner);
            for(var result:List.of(baseline,candidate)) {
                assertEquals(input.facts.size(),result.points().size());
                assertEquals(new MetricPoint(10,30),result.points().get(0));
                assertEquals(new MetricPoint(110,30),result.points().get(result.points().size()-1));
                assertTrue(result.transitions()>0&&result.transitions()<=budgets().maximumTransitions());
                assertTrue(result.points().stream().filter(p->p.xMeters()>=40&&p.xMeters()<=80)
                        .allMatch(p->Math.abs(p.yMeters()-34)<=1),"strong straight-band control");
            }
            assertNotEquals(baseline.modelHash(),candidate.modelHash());
            assertEquals(kernel().identityHash(),candidate.modelHash());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void cancellationAndMemoryRefusalPublishNoRawPath() {
        var input=input();var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            int[] count={0};
            assertThrows(java.util.concurrent.CancellationException.class,()->BObservationGeometry.solve(input.facts,
                    input.lattices,input.region,budgets(),kernel(),()->++count[0]>4,owner));
            assertEquals(0,ledger.currentBytes());
        }
        var tiny=new AttemptMemoryLedger(8);
        try(var owner=tiny.rootOwner()) {
            assertThrows(RuntimeException.class,()->BObservationGeometry.solve(input.facts,input.lattices,
                    input.region,budgets(),kernel(),CancellationProbe.NONE,owner));
            assertEquals(0,tiny.currentBytes());
        }
    }
    private record Input(List<BIdentificationFacts.ProfileFacts> facts,List<ProbabilisticStateLattice> lattices,MetricRegion region) { }
    private static Input input() {
        var evidence=V022LocalizationEvidenceTest.snapshot((x,y)->.02+.65*Math.exp(-.5*Math.pow((y-34)/1.1,2)));
        var profiles=new ProbabilisticProfileFactory().create(List.of(new MetricPoint(10,30),new MetricPoint(110,30)),
                5,25,true,evidence,evidence.fields().get("scalar"));
        var proposals=ProbabilisticContinuationProposals.build(profiles,CancellationProbe.NONE,null);
        var admission=ProbabilisticLatticeAdmission.select(profiles,proposals,budgets(),CancellationProbe.NONE,null);
        assertTrue(admission.admitted(),"the control must use an admitted original V3 graph");
        var lattices=new ArrayList<ProbabilisticStateLattice>();
        var builder=new ProbabilisticStateBuilder();
        for(var profile:profiles) {
            var built=admission.coarsened()?builder.buildWithReservedProposal(profile,
                    admission.maximumStates(profile.profileIndex()),proposals[profile.profileIndex()],null)
                    :builder.buildWithProposal(profile,admission.maximumStates(),proposals[profile.profileIndex()],null);
            lattices.add(built.lattice().orElseThrow());
        }
        return new Input(new BIdentificationFactsExtractor().extract(evidence,profiles,CancellationProbe.NONE,null),lattices,evidence.decisionRegion());
    }
    private static TraceBudgets budgets(){return new TraceBudgets(96,8_000_000,3_000_000,1,1);}
    private static BObservationKernel kernel(){return new BIdentifiedObservationKernel(new BIdentifiedObservationKernel.Parameters(16,1,1));}
}

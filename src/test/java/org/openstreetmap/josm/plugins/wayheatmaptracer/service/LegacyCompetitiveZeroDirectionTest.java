package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.LegacyEmissionProgress.Counter;

class LegacyCompetitiveZeroDirectionTest {
    @Test void actualCoincidentOptionalEndpointsDoNotInventAZeroEdgeDirectionOrCutoff() {
        try(var f=fixture()) {
            assertEquals(List.of(1,1,1),f.profiles().stream().map(p->p.peaks().size()).toList());
            assertTrue(f.input().legacyBinding().orElseThrow().observations().stream().allMatch(x->x.sourceClass()==NativeConnectorEvaluator.SourceClass.OBSERVED));
            assertEquals(List.of(1,2,3),f.input().legacyBinding().orElseThrow().originalRowToRetainedObservation());
            var oracle=LegacyCompetitiveProofTest.enumerate(f);assertEquals(1,oracle.size());
            try(var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC);
                var result=LegacyCompetitiveProofTest.solve(f,"EXACT_CUTOFF_PROOF_V1",p)) {
                assertEquals(oracle.get(0).costs(),result.costs());assertEquals(result.vertices().get(0).position(),result.vertices().get(2).position());
                assertNotEquals(result.vertices().get(0).position(),result.vertices().get(1).position());
                assertTrue(p.view().hasWitnessUpper());assertEquals(1,p.view().count(Counter.COMPONENT_BOUND_PREPARATIONS));
                assertEquals(0,p.view().count(Counter.STRICT_CERTIFICATE_DEFERRALS));
                assertEquals(0,p.view().count(Counter.EVALUATION_STARTED),"Unoriented optional zero edge stays on the original native refusal path");
            }
        }
    }
    @Test void physicallyCollapsedOrdinaryProposalDoesNotBecomeAFiniteStageUpper() {
        try(var f=fixture(true);var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            assertEquals(List.of(1,1),f.profiles().stream().map(x->x.peaks().size()).toList());
            var failure=assertThrows(LegacyEmissionKernel.UnavailableException.class,()->LegacyCompetitiveProofTest.solve(f,"EXACT_CUTOFF_PROOF_V1",p));
            assertEquals(LegacyEmissionKernel.UnavailableReason.NO_POSITIVE_PHYSICAL_LEG,failure.reason());
            assertEquals(1,p.view().count(Counter.WITNESS_ATTEMPTED));assertFalse(p.view().hasWitnessUpper());
            assertThrows(IllegalStateException.class,p.view()::witnessUpper);
            assertEquals(0,p.view().count(Counter.STRICT_CERTIFICATE_DEFERRALS));assertFalse(p.view().firstStageCompleted());
        }
    }
    static LegacyCompetitiveProofTest.Fixture fixture() {
        return fixture(false);
    }
    static LegacyCompetitiveProofTest.Fixture fixture(boolean onlyEndpoints) {
        int w=128,h=128;double[] values=new double[w*h];boolean[] valid=new boolean[values.length];Arrays.fill(valid,true);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double main=StrictMath.exp(-.5*Math.pow((y-47)/.8,2));
            values[y*w+x]=Math.rint(255*main)/255;
        }
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-.01,-.01),new GeographicPoint(.01,.01));
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var field=new ScalarEvidenceField(w,h,values,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,LegacyCompetitiveProofTest.FIELD,EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var evidence=new EvidenceSnapshot("competitive-zero-direction-public",frame,transform,EvidenceResolution.nativeSource(1,1),
            MetricRegion.rectangle(0,0,w-1,h-1),MetricRegion.rectangle(-.5,-.5,w-.5,h-.5),Map.of(LegacyCompetitiveProofTest.FIELD,field),"zero-direction-proof");
        // Equal endpoint anchors and identical bracketing tangents give genuinely
        // identical sampled endpoint profiles, with a positive ordinary out-and-back.
        var guide=List.of(new MetricPoint(44,50),new MetricPoint(52,52),new MetricPoint(56,50),new MetricPoint(52,52),new MetricPoint(68,50));
        var chainage=ProfileChainage.measured(guide,1);var locations=new ArrayList<DetachedProfileSamplingLocation>();
        for(int i=0;i<guide.size();i++)locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(guide.get(i)),frame,transform,chainage.cumulativeGroundMeters().get(i)));
        var source=new CorridorTraceInput(locations,1);var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence,LegacyCompetitiveProofTest.FIELD,source,chainage,25,budget,owner)) {
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence,LegacyCompetitiveProofTest.FIELD,source.profileLocations(),25,1,1,budget,permit);
            var binding=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,onlyEndpoints?List.of(1,3):List.of(1,2,3),1,List.of());
            var input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding);
            return new LegacyCompetitiveProofTest.Fixture(input,binding.observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList(),ledger,owner);
        } catch(RuntimeException failure){owner.close();assertEquals(0,ledger.currentBytes());throw failure;}
    }
}

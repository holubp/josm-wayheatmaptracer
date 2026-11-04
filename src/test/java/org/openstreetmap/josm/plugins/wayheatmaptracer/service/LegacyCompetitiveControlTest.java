package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.LegacyEmissionProgress.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;

class LegacyCompetitiveControlTest {
    static EmissionWorkBudget budget(){return new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);}
    @Test void equalValuedForeignNativeProfilesCannotSupplyAProofWitness() {
        try(var a=LegacyCompetitiveProofTest.fixture(false,budget());var b=LegacyCompetitiveProofTest.fixture(false,budget());
            var p=LegacyEmissionProgress.open(b.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            long bytes=b.ledger().currentBytes();
            assertThrows(IllegalArgumentException.class,()->new LegacyEmissionKernel().solveObservedSeedProof(b.input(),a.profiles(),0,CancellationProbe.NONE,
                LegacyEmissionKernel.CompetitiveExecution.EXACT_CUTOFF_PROOF_V1,p));
            assertEquals(bytes,b.ledger().currentBytes());assertFalse(p.view().hasWitnessUpper());
            assertEquals(0,p.view().count(Counter.WITNESS_ATTEMPTED));assertEquals(0,p.view().count(Counter.EVALUATION_STARTED));
        }
    }
    @Test void unavailableOrdinaryScalarIsARealLegalWitnessWithLossOne() {
        try(var f=LegacyCompetitiveProofTest.fixture(true,budget())) {
            var oracle=LegacyCompetitiveProofTest.enumerate(f);var expected=oracle.stream().min(LegacyCompetitiveProofTest::compare).orElseThrow();
            try(var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC);
                var actual=LegacyCompetitiveProofTest.solve(f,"EXACT_CUTOFF_PROOF_V1",p)) {
                assertEquals(expected.costs(),actual.costs());assertTrue(actual.witnessCosts().isPresent());
                assertTrue(actual.witnessCosts().orElseThrow().q()>=8,"Unknown first ordinary segment contributes factual source-span/pitch loss1");
                assertTrue(actual.arcs().stream().anyMatch(a->a.kind()==EmissionResult.ArcKind.ORDINARY_UNAVAILABLE));
                assertEquals(1,p.view().count(Counter.WITNESS_ATTEMPTED));assertEquals(1,p.view().count(Counter.WITNESS_CERTIFIED));
            }
        }
    }
    @Test void actualEqualUpperBoundRemainsOpenToFullOptionalAdmission() {
        try(var f=LegacyCompetitiveProofTest.fixture(false,true,budget(),EvidenceResolution.nativeSource(1,1));
            var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            double seed=f.profiles().get(0).peaks().get(0).offsetPx();
            try(var actual=LegacyCompetitiveProofTest.solve(f,"EXACT_CUTOFF_PROOF_V1",p,seed)) {
            assertEquals(0,p.view().witnessUpper(),0);assertEquals(new EmissionResult.Costs(0,0,0,0),actual.costs());
            assertEquals(1,p.view().count(Counter.COMPONENT_BOUND_PREPARATIONS));
            assertEquals(0,p.view().count(Counter.STRICT_CERTIFICATE_DEFERRALS));
            assertEquals(1,p.view().count(Counter.EVALUATION_STARTED));assertTrue(p.view().count(Counter.EXTRACTION_STARTED)>0);
            }
        }
    }
    @Test void optionalLossUsesContextPitchRatherThanProbeMinimum() {
        var resolution=new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,OptionalDouble.of(1),1,
            List.of(new EvidenceResolution.PitchSample(0,OptionalDouble.of(1),1),new EvidenceResolution.PitchSample(16,OptionalDouble.of(1.2),1)),OptionalDouble.of(1));
        try(var f=LegacyCompetitiveProofTest.fixture(false,false,budget(),resolution)) {
            var rows=f.input().legacyBinding().orElseThrow().observations();
            var first=rows.get(0).producerPeaks().stream().min(Comparator.comparingDouble(x->Math.abs(x.peak().offsetPx()))).orElseThrow();
            var last=rows.get(2).producerPeaks().get(0);
            var a=new RowState(0,first.originalPeakId(),first.peak().offsetPx(),SourceClass.OBSERVED,first.measurementPeakOwner());
            var b=new RowState(2,last.originalPeakId(),last.peak().offsetPx(),SourceClass.OBSERVED,last.measurementPeakOwner());
            try(var e=new NativeConnectorEvaluator(f.input(),new Domain(List.of(a,b),List.of()),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
                var receipt=e.evaluate(a,b);assertEquals(Status.ADMITTED,receipt.status());
                assertEquals(1.2,f.input().context().nativePitchMeters().orElseThrow(),0);assertEquals(1,receipt.minimumNativePitchMeters(),0);
                double mean=receipt.scalar().orElseThrow().mean().orElseThrow();
                assertEquals(receipt.sourceSpanMeters()/1.2*(1-mean),receipt.sourceWeightedLoss(),0);
                assertNotEquals(receipt.sourceSpanMeters()/receipt.minimumNativePitchMeters()*(1-mean),receipt.sourceWeightedLoss());
            }
            var expected=LegacyCompetitiveProofTest.enumerate(f).stream().min(LegacyCompetitiveProofTest::compare).orElseThrow();
            try(var result=LegacyCompetitiveProofTest.solve(f,"EXACT_CUTOFF_PROOF_V1",null)){assertEquals(expected.costs(),result.costs());}
        }
    }
    @Test void resultLeaseSurvivesInputClosureAndReleasesEverythingAtClose() {
        try(var f=LegacyCompetitiveProofTest.fixture(false,budget())) {
            var result=LegacyCompetitiveProofTest.solve(f,"EXACT_CUTOFF_PROOF_V1",null);
            var vertices=result.vertices();f.input().close();assertEquals(vertices,result.vertices());assertTrue(f.ledger().currentBytes()>0);
            result.close();result.close();assertThrows(IllegalStateException.class,result::costs);
            assertThrows(IllegalStateException.class,()->LegacyCompetitiveProofTest.solve(f,"EXACT_CUTOFF_PROOF_V1",null));
            f.owner().close();assertEquals(0,f.ledger().currentBytes());
        }
    }
    @Test void defaultObserverHasUnavailableCompetitiveMetadataAndLargerStagesAreNotAdopted() {
        try(var f=LegacyCompetitiveProofTest.fixture(false,budget());var p=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC)) {
            p.seal(null);assertFalse(p.view().hasCompetitiveExecution());assertFalse(p.view().hasWitnessUpper());
            assertThrows(IllegalStateException.class,p.view()::competitiveExecution);assertThrows(IllegalStateException.class,p.view()::witnessUpper);
            assertThrows(IllegalStateException.class,p.view()::maxCompetitiveKeyLabels);
        }
        try(var f=LegacyProgressPolicyTest.stageInput(budget())) {
            long bytes=f.ledger().currentBytes(),modes=f.input().workBudget().operationUnits(EmissionWorkBudget.Operation.MODE_EXTRACTION);
            var profiles=f.input().legacyBinding().orElseThrow().observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList();
            assertThrows(IllegalArgumentException.class,()->new LegacyEmissionKernel().solveObservedSeedProof(f.input(),profiles,0,CancellationProbe.NONE,
                LegacyEmissionKernel.CompetitiveExecution.EXACT_CUTOFF_PROOF_V1,null));
            assertEquals(bytes,f.ledger().currentBytes());assertEquals(modes,f.input().workBudget().operationUnits(EmissionWorkBudget.Operation.MODE_EXTRACTION));
        }
    }
}

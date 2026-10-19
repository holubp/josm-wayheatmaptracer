package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationFacts.*;

/** Toy source contracts only: no catalog renderer or empirical acquisition. */
class V022BGapSourceContractTest {
    private static final BIdentifiedObservationKernel KERNEL=new BIdentifiedObservationKernel(
            new BIdentifiedObservationKernel.Parameters(1,1,0));
    @Test void absentFieldAndChangedProofAreRefused() {
        var evidence=snapshot("source",0,false);
        assertThrows(IllegalArgumentException.class,()->input(evidence,"absent"));
        assertThrows(IllegalArgumentException.class,()->new BGapEvidence.ResourceContract(
                "0".repeat(64),BGapEvidence.BUILD_UNITS,BGapEvidence.BYTES,BGapEvidence.SLABS));
        assertThrows(IllegalArgumentException.class,()->new BGapEvidence.ResourceContract(
                BGapEvidence.PROOF_SHA256,BGapEvidence.BUILD_UNITS+1,BGapEvidence.BYTES,BGapEvidence.SLABS));
    }
    @Test void anotherPresentFieldCannotImpersonateTheFixedExperimentSelection() {
        var base=snapshot("source",0,false);
        var evidence=new EvidenceSnapshot(base.snapshotId(),base.coordinateFrame(),base.transform(),base.resolution(),
                base.decisionRegion(),base.evidenceRegion(),Map.of("scalar",base.fields().get("scalar"),
                        "other",base.fields().get("scalar")),base.sourceIdentity());
        assertNotNull(evidence.fields().get("other"));
        assertThrows(IllegalArgumentException.class,()->input(evidence,"other"));
    }
    @Test void sourceCloneReservationIncludesAllMasks() {
        var input=input(snapshot("source",0,false),"scalar");
        // Aligned arrays + full validation-counter field wrapper192 + counter/report128 + child owner64.
        long expected=64+24+24+192+128+64;
        assertEquals(expected,BGapEvidence.sourceCloneBytes(input.evidence().fields().get("scalar")));
        var ledger=new AttemptMemoryLedger(expected);
        try(var root=ledger.rootOwner();var copy=BGapEvidence.copySource(input,root,CancellationProbe.NONE)) {
            assertEquals(expected,ledger.currentBytes());
            assertTrue(copy.interpolationValid(0));assertFalse(copy.interpolationValid(1));
            assertTrue(copy.valid(0));assertEquals(.25,copy.value(0));
            assertEquals(28,copy.counters().sourceElements(),"actual clone reads AND stores, including both masks");
            assertEquals(32,copy.counters().buildUnits());
            assertEquals(expected,copy.counters().currentOwnedBytes());
            assertEquals(.25,input.evidence().fields().get("scalar").sample(0,0).orElseThrow());
        }
        assertEquals(0,ledger.currentBytes());
        var tiny=new AttemptMemoryLedger(expected-1);
        try(var root=tiny.rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->BGapEvidence.copySource(input,root,CancellationProbe.NONE));
            assertEquals(0,tiny.currentBytes());
        }
    }
    @Test void cancelledCloneClosesAllReservations() {
        var ledger=new AttemptMemoryLedger(4096);
        int[] calls={0};
        try(var root=ledger.rootOwner()) {
            assertThrows(CancellationException.class,()->BGapEvidence.copySource(input(snapshot("source",0,false),"scalar"),
                    root,()->++calls[0]==3));
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void aliasWithoutSourceIdentityIsUnavailable() {
        var a=input(snapshot("source-a",0,false),"scalar");
        assertTrue(BGapEvidence.sameObservation(a,a));
        assertFalse(BGapEvidence.sameObservation(a,input(snapshot("source-b",0,false),"scalar")));
        assertFalse(BGapEvidence.sameObservation(a,input(snapshot("source-a",.25,false),"scalar")));
        assertFalse(BGapEvidence.sameObservation(a,input(snapshot("source-a",0,true),"scalar")));
    }
    @Test void retainedFieldNeedsNoRecoveredNativeValues() {
        var a=input(snapshot("rendered-only",0,false),"scalar");
        assertTrue(a.evidence().resolution().nativePitchMeters().isEmpty());
        assertSame(a.evidence().fields().get("scalar"),a.evidence().fields().get(a.fieldKey()));
        assertTrue(BGapEvidence.sameObservation(a,a));
        assertEquals(6,BGapEvidence.Status.values().length);
        assertNotEquals(BGapEvidence.Status.OBSERVED_NO_GAP,BGapEvidence.Status.UNAVAILABLE_SUPPORT);
    }
    @Test void unownedFactsCannotMintTheModeBound() {
        var evidence=snapshot("source",0,false);
        var p=baseline(List.of(new ProbabilisticProfile.Sample(0,.25,true)));
        var facts=new ProfileFacts(p,List.of(),VERSION);
        var profile=adapted(p);
        var input=new BGapEvidence.Input(evidence,"scalar",List.of(facts),List.of(profile),BGapEvidence.approvedContract());
        var ledger=new AttemptMemoryLedger(4096);
        try(var root=ledger.rootOwner()) {
            assertThrows(IllegalArgumentException.class,()->BGapEvidence.requireOwnedFacts(input,root,CancellationProbe.NONE));
            try(var reservation=root.reserve(1)) { reservation.adopt(facts); }
            assertThrows(IllegalArgumentException.class,()->BGapEvidence.requireOwnedFacts(input,root,CancellationProbe.NONE));
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void actualCountersAreBoundedWithoutInferringWorkFromGraphSize() {
        var counts=new BGapEvidence.WorkCounter();
        counts.add(BGapEvidence.Work.MODE_COMPARISON,7);
        counts.add(BGapEvidence.Work.SLAB_EVALUATION,3);
        counts.add(BGapEvidence.Work.SOLVER_LOOKUP,2);
        assertEquals(7,counts.snapshot().modeComparisons());
        assertEquals(3,counts.snapshot().slabEvaluations());
        assertEquals(2,counts.snapshot().solverLookups());
        assertEquals(10,counts.snapshot().buildUnits());
        assertThrows(IllegalArgumentException.class,()->counts.add(BGapEvidence.Work.CACHE_READ,-1));
        counts.add(BGapEvidence.Work.CACHE_READ,BGapEvidence.BUILD_UNITS-10);
        assertThrows(IllegalArgumentException.class,()->counts.add(BGapEvidence.Work.PAIR_WRITE,1));
        assertEquals(0,counts.snapshot().pairWrites(),"refused work must not be reported executed");
        assertThrows(IllegalArgumentException.class,()->counts.add(BGapEvidence.Work.SLAB_EVALUATION,BGapEvidence.SLABS));
    }
    @Test void manyModeValidationIsCountedCancellableAndReusedByCloning() {
        var input=manyModeInput(2048,false);
        var ledger=new AttemptMemoryLedger(BGapEvidence.BYTES);
        try(var root=ledger.rootOwner()) {
            ownFacts(input,root);
            long before=ledger.currentBytes();
            int[] polls={0};
            assertThrows(CancellationException.class,()->BGapEvidence.requireOwnedFacts(input,root,()->++polls[0]==3));
            assertEquals(3,polls[0]);assertEquals(before,ledger.currentBytes());
            var checked=BGapEvidence.requireOwnedFacts(input,root,CancellationProbe.NONE);
            assertEquals(2048,checked.modeComparisons());
            assertTrue(checked.cacheReads()>2048,"count provenance, string and factual reads, not graph size");
            assertTrue(checked.cancellationChecks()>1);
            try(var copy=BGapEvidence.copySource(input,root,CancellationProbe.NONE)) {
                assertEquals(checked.modeComparisons(),copy.counters().modeComparisons());
                assertEquals(checked.cacheReads(),copy.counters().cacheReads());
                assertEquals(checked.sourceElements()+28,copy.counters().sourceElements());
                assertTrue(copy.counters().buildUnits()>checked.buildUnits());
            }
            assertEquals(before,ledger.currentBytes());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void laterOwnershipFailureReleasesEarlierRetainedFactsAndValidationWorkspace() {
        var first=manyModeInput(8,false);
        var firstFact=first.facts().get(0);
        var secondFact=new ProfileFacts(firstFact.baseline(),firstFact.modes(),VERSION);
        var input=new BGapEvidence.Input(first.evidence(),"scalar",List.of(firstFact,secondFact),
                List.of(first.profiles().get(0),first.profiles().get(0)),BGapEvidence.approvedContract());
        var ledger=new AttemptMemoryLedger(BGapEvidence.BYTES);
        try(var root=ledger.rootOwner()) {
            var original=root.child("original");ownFacts(first,original);
            long before=ledger.currentBytes();
            assertThrows(IllegalArgumentException.class,()->BGapEvidence.requireOwnedFacts(input,root,CancellationProbe.NONE));
            assertEquals(before,ledger.currentBytes());
            original.close();assertEquals(0,ledger.currentBytes(),"no first-fact lease may escape failed validation");
        }
    }
    @Test void snapshotNativeAvailabilityCannotBeBorrowedInEitherDirection() {
        for(boolean known:new boolean[]{false,true}) {
            var input=manyModeInput(1,known);
            var e=input.evidence();
            var opposite=new EvidenceSnapshot(e.snapshotId(),e.coordinateFrame(),e.transform(),
                    known?EvidenceResolution.renderedOnly(1):EvidenceResolution.nativeSource(1,1),
                    e.decisionRegion(),e.evidenceRegion(),e.fields(),e.resampling(),e.sourceIdentity());
            var changed=new BGapEvidence.Input(opposite,"scalar",input.facts(),input.profiles(),BGapEvidence.approvedContract());
            var ledger=new AttemptMemoryLedger(100_000);
            try(var root=ledger.rootOwner()) {
                ownFacts(input,root);long before=ledger.currentBytes();
                try(var valid=BGapEvidence.copySource(input,root,CancellationProbe.NONE)) {assertTrue(valid.valid(0));}
                assertThrows(IllegalArgumentException.class,()->BGapEvidence.copySource(changed,root,CancellationProbe.NONE));
                assertEquals(before,ledger.currentBytes());
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void originalEffectivePitchMustMatchItsActualResolutionMetadata() {
        var input=manyModeInput(1,true);var e=input.evidence();
        var changed=new EvidenceSnapshot(e.snapshotId(),e.coordinateFrame(),e.transform(),EvidenceResolution.nativeSource(2,1),
                e.decisionRegion(),e.evidenceRegion(),e.fields(),e.resampling(),e.sourceIdentity());
        var wrong=new BGapEvidence.Input(changed,"scalar",input.facts(),input.profiles(),BGapEvidence.approvedContract());
        var ledger=new AttemptMemoryLedger(100_000);
        try(var root=ledger.rootOwner()) {
            ownFacts(input,root);
            assertThrows(IllegalArgumentException.class,()->BGapEvidence.copySource(wrong,root,CancellationProbe.NONE));
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void nativeHalfMetreOversampledFactoryKeepsItsActualSamplingPitch() {
        var base=snapshot("source",0,false);
        double[] values=new double[121];Arrays.fill(values,.25);
        boolean[] validity=new boolean[121];Arrays.fill(validity,true);
        var field=new ScalarEvidenceField(11,11,values,validity,base.fields().get("scalar").lineage());
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),.25,0,0,.25);
        var region=MetricRegion.rectangle(0,0,2.5,2.5);
        var resolution=EvidenceResolution.nativeSource(.5,.5).resampledTo(.25);
        var evidence=new EvidenceSnapshot("tiny-native-o2",base.coordinateFrame(),transform,resolution,
                region,region,Map.of("scalar",field),"source");
        var profiles=new ProbabilisticProfileFactory().create(List.of(new MetricPoint(.5,1.25),new MetricPoint(2,1.25)),
                .5,.5,false,evidence,field);
        var ledger=new AttemptMemoryLedger(100_000);
        try(var root=ledger.rootOwner()) {
            var facts=new BIdentificationFactsExtractor().extract(evidence,profiles,CancellationProbe.NONE,root);
            for(var p:profiles) {
                assertTrue(p.nativePitchKnown());assertEquals(.5,p.sourcePitchMeters(),0);
                assertEquals(5,p.samples().size(),"sample gap .25m derives from native.5, not output.25");
            }
            assertEquals(.25,resolution.outputRasterPitchMeters(),0);
            var input=new BGapEvidence.Input(evidence,"scalar",facts,profiles.stream().map(V022BGapSourceContractTest::adapted).toList(),
                    BGapEvidence.approvedContract());
            try(var copy=BGapEvidence.copySource(input,root,CancellationProbe.NONE)) {assertTrue(copy.valid(0));}
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void countedSpatialPitchTraversalHasTheExactFactoryMetadataGrouping() {
        var base=snapshot("source",0,false);
        var resolution=new EvidenceResolution(EvidenceResolution.Kind.RENDERED_ONLY,OptionalDouble.empty(),1,
                List.of(new EvidenceResolution.PitchSample(0,OptionalDouble.empty(),1),
                        new EvidenceResolution.PitchSample(10,OptionalDouble.empty(),2),
                        new EvidenceResolution.PitchSample(20,OptionalDouble.empty(),.5)),OptionalDouble.empty());
        var evidence=new EvidenceSnapshot("spatial-pitch",base.coordinateFrame(),base.transform(),resolution,
                base.decisionRegion(),base.evidenceRegion(),base.fields(),"source");
        for(double chainage:new double[]{0,3.7,10,15.5,20,20+1e-13}) {
            double pitch=resolution.effectivePitchMetersAt(chainage);
            var p=new ProbabilisticProfile(0,chainage,new MetricPoint(1,.5),new MetricPoint(0,1),-1,1,pitch,false,0,
                    List.of(new ProbabilisticProfile.Sample(0,.25,true)),List.of(),List.of(),OptionalDouble.empty(),List.of(),0);
            var input=new BGapEvidence.Input(evidence,"scalar",List.of(new ProfileFacts(p,List.of(),VERSION)),
                    List.of(adapted(p)),BGapEvidence.approvedContract());
            var ledger=new AttemptMemoryLedger(10_000);
            try(var root=ledger.rootOwner()) {
                ownFacts(input,root);
                assertDoesNotThrow(()->BGapEvidence.requireOwnedFacts(input,root,CancellationProbe.NONE));
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    private static BGapEvidence.Input manyModeInput(int count,boolean known) {
        var e=snapshot("source",0,false);
        if(known)e=new EvidenceSnapshot(e.snapshotId(),e.coordinateFrame(),e.transform(),EvidenceResolution.nativeSource(1,1),
                e.decisionRegion(),e.evidenceRegion(),e.fields(),e.resampling(),e.sourceIdentity());
        var mode=new ProbabilisticProfile.Mode("m","m",-.25,.25,.5,1,1,List.of(0.0),List.of(0.0),false);
        var p=new ProbabilisticProfile(0,0,new MetricPoint(1,.5),new MetricPoint(0,1),-1,1,1,known,0,
                List.of(new ProbabilisticProfile.Sample(0,.25,true)),Collections.nCopies(count,mode),List.of(),
                OptionalDouble.empty(),List.of(),0);
        var receipt=new CorrelationReceipt(e.sourceIdentity(),e.transform().transformId(),known);
        var fact=new ModeFacts("m",List.of(),Collections.nCopies(4,receipt));
        var facts=new ProfileFacts(p,Collections.nCopies(count,fact),VERSION);
        return new BGapEvidence.Input(e,"scalar",List.of(facts),List.of(adapted(p)),BGapEvidence.approvedContract());
    }
    private static void ownFacts(BGapEvidence.Input input,AttemptMemoryLedger.Owner owner) {
        for(var fact:input.facts()) {
            long samples=96L*fact.baseline().samples().size(),modes=8192L*fact.modes().size(),characters=0;
            for(var mode:fact.baseline().modes())characters+=4L*(mode.id().length()+mode.evidenceLineage().length());
            try(var reservation=owner.reserve(1024+samples+modes+characters)) {reservation.adopt(fact);}
        }
    }
    @Test void ownedExtractorFactsRemainChargedUntilTheCopyCloses() {
        var evidence=snapshot("source",0,false);
        var p=baseline(List.of(new ProbabilisticProfile.Sample(0,.25,true)));
        var ledger=new AttemptMemoryLedger(16_384);
        try(var root=ledger.rootOwner()) {
            var original=root.child("original-facts");
            var facts=new BIdentificationFactsExtractor().extract(evidence,List.of(p),CancellationProbe.NONE,original);
            var input=new BGapEvidence.Input(evidence,"scalar",facts,List.of(adapted(p)),BGapEvidence.approvedContract());
            assertEquals(1120,ledger.currentBytes());
            BGapEvidence.requireOwnedFacts(input,root,CancellationProbe.NONE);
            assertEquals(1120,ledger.currentBytes(),"validation adds no duplicate payload charge");
            var copy=BGapEvidence.copySource(input,root,CancellationProbe.NONE);
            original.close();
            assertEquals(1120+BGapEvidence.sourceCloneBytes(evidence.fields().get("scalar")),ledger.currentBytes());
            copy.close();copy.close();
            assertEquals(0,ledger.currentBytes());
            assertThrows(IllegalStateException.class,()->copy.value(0));
            assertEquals(0,copy.counters().currentOwnedBytes());
        }
    }
    @Test void sourceGeometryAndContainersRemainTheOriginalObjects() {
        var samples=List.of(new ProbabilisticProfile.Sample(0,.25,true));
        for(var normal:List.of(new MetricPoint(-0.0,1),new MetricPoint(.6,.8))) {
            var p=new ProbabilisticProfile(0,0,new MetricPoint(1,.5),normal,-1,1,1,false,0,
                    samples,List.of(),List.of(),OptionalDouble.empty(),List.of(),0);
            var facts=new ArrayList<>(List.of(new ProfileFacts(p,List.of(),VERSION)));
            var profiles=new ArrayList<>(List.of(adapted(p)));
            var input=new BGapEvidence.Input(snapshot("source",0,false),"scalar",facts,profiles,BGapEvidence.approvedContract());
            facts.clear();profiles.clear();
            assertSame(p,input.facts().get(0).baseline());
            assertSame(p.anchor(),input.profiles().get(0).anchor());
            assertSame(normal,input.profiles().get(0).normalUnit());
            assertThrows(UnsupportedOperationException.class,()->input.facts().clear());
            var changed=new InferenceProfile(0,new MetricPoint(1,.5),normal,
                    input.profiles().get(0).cells(),new double[]{0},List.of(),0,ObservationOwnership.NO_SIGNAL_VALID_RASTER,true);
            var wrong=new BGapEvidence.Input(input.evidence(),"scalar",input.facts(),List.of(changed),BGapEvidence.approvedContract());
            var ledger=new AttemptMemoryLedger(16_384);
            try(var root=ledger.rootOwner()) {
                ownFacts(input,root);
                assertThrows(IllegalArgumentException.class,()->BGapEvidence.requireOwnedFacts(wrong,root,CancellationProbe.NONE));
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void originalReceiptCannotBorrowAnotherSourceTransformOrNativeFlag() {
        var base=snapshot("source",0,false);
        var evidence=new EvidenceSnapshot(base.snapshotId(),base.coordinateFrame(),base.transform(),EvidenceResolution.nativeSource(1,1),
                base.decisionRegion(),base.evidenceRegion(),base.fields(),base.sourceIdentity());
        var original=toyFacts(new double[]{.375,.375,.375,.375,.375});
        var mode=original.modes().get(0);
        for(var receipt:List.of(new CorrelationReceipt("source",evidence.transform().transformId(),true),
                new CorrelationReceipt("foreign",evidence.transform().transformId(),true),
                new CorrelationReceipt("source","foreign-transform",true),
                new CorrelationReceipt("source",evidence.transform().transformId(),false))) {
            var receipts=new ArrayList<>(mode.provenance());receipts.add(receipt);
            var facts=new ProfileFacts(original.baseline(),List.of(new ModeFacts(mode.lineage(),mode.values(),receipts)),VERSION);
            var input=new BGapEvidence.Input(evidence,"scalar",List.of(facts),List.of(adapted(facts.baseline())),BGapEvidence.approvedContract());
            var ledger=new AttemptMemoryLedger(100_000);
            try(var root=ledger.rootOwner()) {
                ownFacts(input,root);
                org.junit.jupiter.api.function.Executable bind=()->BGapEvidence.requireOwnedFacts(input,root,CancellationProbe.NONE);
                if(receipt.fieldLineage().equals("source") && receipt.transformId().equals(evidence.transform().transformId())
                        && receipt.nativePitchKnown())assertDoesNotThrow(bind);
                else assertThrows(IllegalArgumentException.class,bind);
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void modeCountIsEnforcedBeforeAnyOwnershipClaim() {
        var p=baseline(List.of(new ProbabilisticProfile.Sample(0,.25,true)));
        var mode=new ModeFacts("unowned",List.of(),List.of());
        var facts=new ProfileFacts(p,Collections.nCopies(8193,mode),VERSION);
        var input=new BGapEvidence.Input(snapshot("source",0,false),"scalar",List.of(facts),List.of(adapted(p)),BGapEvidence.approvedContract());
        var ledger=new AttemptMemoryLedger(4096);
        try(var root=ledger.rootOwner()) {
            assertThrows(IllegalArgumentException.class,()->BGapEvidence.requireOwnedFacts(input,root,CancellationProbe.NONE));
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void reverseUsesItsOwnFactoryMesh() {
        var factory=new ProbabilisticProfileFactory();
        var forward=factory.sourceSamplingGeometry(List.of(new MetricPoint(0,30),new MetricPoint(120,30)),1.5);
        var reverse=factory.sourceSamplingGeometry(List.of(new MetricPoint(120,30),new MetricPoint(0,30)),1.5);
        assertEquals(81,forward.points().size());assertEquals(81,reverse.points().size());
        assertEquals(0x404ebfffffffffffL,Double.doubleToRawLongBits(forward.points().get(41).xMeters()));
        assertEquals(0x404ec00000000000L,Double.doubleToRawLongBits(reverse.points().get(39).xMeters()));
        assertNotEquals(forward.points().get(41),reverse.points().get(39),"reverse is a matched control, not bitwise alias");
    }
    @Test void representableUniformNullIsExactlyZero() {
        var facts=toyFacts(new double[]{.375,.375,.375,.375,.375});
        var lattice=toyLattice();
        var mixture=KERNEL.evaluate(facts,lattice,CancellationProbe.NONE,null);
        assertEquals(0,mixture.component(1).mass(),0);
        assertEquals(BObservationMixture.Outcome.OBSERVED_ZERO_EXCESS,mixture.component(1).outcome());
        var reduced=mixture.reduce(lattice,CancellationProbe.NONE,null);
        for(int s=0;s<lattice.cells().size();s++)assertEquals(StrictMath.log(3.5),reduced.unaryCost(s),1e-14);
    }
    @Test void tinyResourcePinsTheReviewedContractAndTheIndependentNullOracle() throws Exception {
        Map<String,Object> fixture;
        try(var stream=getClass().getResourceAsStream("/b-observed-gap/source-contract.json")) {
            assertNotNull(stream);fixture=BIdentificationReceipt.parse(stream.readAllBytes());
        }
        assertEquals("synthetic-source-contract-only",fixture.get("purpose"));
        assertEquals(BGapEvidence.PROOF_SHA256,fixture.get("proofSha256"));
        assertEquals(BGapEvidence.BUILD_UNITS,((Number)fixture.get("maximumBuildUnits")).longValue());
        assertEquals(BGapEvidence.BYTES,((Number)fixture.get("maximumBytes")).longValue());
        assertEquals(BGapEvidence.SLABS,((Number)fixture.get("maximumSlabs")).longValue());
        assertEquals(8192,((Number)fixture.get("maximumFactModes")).intValue());
        for(var entry:Map.of("source-resource-proof",fixture.get("proofSha256"),
                "numerical-resource-contract",fixture.get("contractSha256")).entrySet()) {
            var path=java.nio.file.Path.of("superpowers/docs/plans/2026-10-19-b-observed-gap-"+entry.getKey()+".md");
            assertEquals(entry.getValue(),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(java.nio.file.Files.readAllBytes(path))));
        }
        @SuppressWarnings("unchecked") var oracle=(Map<String,Object>)fixture.get("syntheticNull");
        assertEquals(.25,((Number)oracle.get("background")).doubleValue(),0);
        assertEquals(.125,((Number)oracle.get("rmsDescriptor")).doubleValue(),0);
        assertEquals(.0625,((Number)oracle.get("filterDescriptor")).doubleValue(),0);
        @SuppressWarnings("unchecked") var samples=(List<Number>)oracle.get("samples");
        double left=samples.get(0).doubleValue(),center=samples.get(1).doubleValue(),right=samples.get(2).doubleValue();
        var facts=toyFacts(new double[]{left,left,center,right,right});
        assertEquals(((Number)oracle.get("expectedPresenceMass")).doubleValue(),
                KERNEL.evaluate(facts,toyLattice(),CancellationProbe.NONE,null).component(1).mass(),0);
    }
    @Test void inputEnvelopeBoundsAllShoulderSamplesNotOnlyPeak() {
        double previous=Double.POSITIVE_INFINITY;
        for(double epsilon:new double[]{.125,.03125,.0009765625,0}) {
            var facts=toyFacts(new double[]{.375+epsilon,.375+epsilon,.375+epsilon,.375+epsilon,.375+epsilon});
            var mixture=KERNEL.evaluate(facts,toyLattice(),CancellationProbe.NONE,null);
            double mass=mixture.component(1).mass();
            double bound=3.5*epsilon;
            assertEquals(bound/(1+bound),mass,1e-14,"H is width-weighted excess, not a noise probability");
            assertTrue(mass<=previous);previous=mass;
        }
        // A bound on the supplied center alone says nothing about other shoulder samples.
        var outsideCenter=toyFacts(new double[]{.375,.875,.375,.375,.375});
        assertEquals(.375,outsideCenter.baseline().samples().get(2).intensity());
        assertTrue(KERNEL.evaluate(outsideCenter,toyLattice(),CancellationProbe.NONE,null).component(1).mass()>0);
    }
    @Test void interpolationRoundingStaysInsideArithmeticEnvelope() {
        var samples=List.of(new ProbabilisticProfile.Sample(0,.1,true),new ProbabilisticProfile.Sample(1,.9,true));
        double rhoFp=64*Math.ulp(1.0); // Arithmetic bound, never source precision/SD.
        for(double u:new double[]{0,Double.MIN_VALUE,.125,.37,Math.nextDown(1.0),1}) {
            double actual=ProbabilisticObservationModel.interpolate(samples,u);
            var exact=new BigDecimal(.1).add(new BigDecimal(u).multiply(new BigDecimal(.9).subtract(new BigDecimal(.1))));
            assertTrue(new BigDecimal(actual).subtract(exact).abs().compareTo(new BigDecimal(rhoFp))<=0);
            double excess=Math.max(0,actual-.25-.125);
            assertTrue(excess<=Math.max(0,exact.doubleValue()-.375)+rhoFp);
        }
        var invalid=List.of(samples.get(0),new ProbabilisticProfile.Sample(1,.9,false));
        assertTrue(Double.isNaN(ProbabilisticObservationModel.interpolate(invalid,.5)));
    }
    @Test void inheritedOrientationNullScopeIsExplicit() {
        var original=toyFacts(new double[]{.375,.375,.375,.375,.375});
        var p=original.baseline();
        var oriented=new ProbabilisticProfile(p.profileIndex(),p.chainageMeters(),p.anchor(),p.normalUnit(),
                p.minimumOffsetMeters(),p.maximumOffsetMeters(),p.sourcePitchMeters(),p.nativePitchKnown(),p.noiseFloor(),
                p.samples(),p.modes(),p.censoredModes(),p.exactAnchorOffsetMeters(),List.of(.4),.75);
        var facts=new ProfileFacts(oriented,original.modes(),VERSION);
        var lattice=toyLattice();
        var parameters=EvidenceModelParameters.defaults();
        var baseline=new ProbabilisticObservationModel().evaluate(oriented,lattice,parameters,true,null);
        var replaced=BObservationAdapter.evaluate(facts,lattice,parameters,true,KERNEL,CancellationProbe.NONE,null);
        assertTrue(baseline.orientationCertainty()>0,"local scalar null does not imply whole-image neutrality");
        for(int s=0;s<lattice.cells().size();s++) {
            assertEquals(baseline.orientationSupport(s),replaced.orientationSupport(s));
            assertEquals(baseline.orientationReliability(s),replaced.orientationReliability(s),0);
            assertEquals(baseline.structuralGuideCost(s),replaced.structuralGuideCost(s),0);
            assertEquals(StrictMath.log(3.5),replaced.unaryCost(s),1e-14);
        }
    }
    @Test void extractorBoundaryDoesNotManufactureMeasuredMass() {
        var counts=new HashSet<Integer>();
        for(double amplitude:new double[]{0,1e-12,.03125,.125}) {
            double[] values=new double[121];boolean[] valid=new boolean[121];Arrays.fill(valid,true);
            for(int y=0;y<11;y++)for(int x=0;x<11;x++)values[y*11+x]=.25+amplitude*StrictMath.exp(-.5*(y-5)*(y-5));
            var small=snapshot("toy-mode-boundary",0,false);
            var region=MetricRegion.rectangle(0,0,10,10);
            var field=new ScalarEvidenceField(11,11,values,valid,small.fields().get("scalar").lineage());
            var evidence=new EvidenceSnapshot("toy-boundary",small.coordinateFrame(),small.transform(),
                    EvidenceResolution.nativeSource(1,1),region,region,Map.of("scalar",field),"toy-mode-boundary");
            var profiles=new ProbabilisticProfileFactory().create(List.of(new MetricPoint(2,5),new MetricPoint(8,5)),
                    2,4,false,evidence,field);
            var facts=new BIdentificationFactsExtractor().extract(evidence,profiles,CancellationProbe.NONE,null);
            int modeCount=facts.stream().mapToInt(f->f.modes().size()).sum();counts.add(modeCount);
            for(var row:facts) {
                var lattice=new ProbabilisticStateBuilder().build(row.baseline(),96).lattice().orElseThrow();
                var mixture=KERNEL.evaluate(row,lattice,CancellationProbe.NONE,null);
                double measured=0;
                for(int c=0;c<mixture.componentCount();c++)if(mixture.component(c).kind()==ObservationComponent.Kind.MEASURED)
                    measured+=mixture.component(c).mass();
                if(amplitude==0)assertEquals(0,measured,0);
                else assertTrue(measured<=8*amplitude*Math.max(1,row.modes().size())+1e-12);
                for(var mode:row.modes())for(var receipt:mode.provenance())if(receipt instanceof CorrelationReceipt own) {
                    assertEquals(evidence.sourceIdentity(),own.fieldLineage());
                    assertEquals(evidence.transform().transformId(),own.transformId());
                }
            }
        }
        assertTrue(counts.size()>1,"exercise a changing actual extractor mode set, not only frozen modes");
    }
    private static ProbabilisticStateLattice toyLattice() {
        return new ProbabilisticStateLattice(List.of(new LateralStateCell(-1,1,false,false,"m"),
                new LateralStateCell(.25,2,false,false,"m"),new LateralStateCell(1,.5,false,false,"m")),
                List.of(new ObservationComponent("missing","missing",ObservationComponent.Kind.MISSING,.5,false),
                        new ObservationComponent("m","m",ObservationComponent.Kind.MEASURED,.5,false)),1,false);
    }
    private static ProfileFacts toyFacts(double[] intensities) {
        var samples=new ArrayList<ProbabilisticProfile.Sample>();
        for(int i=0;i<5;i++)samples.add(new ProbabilisticProfile.Sample(i-2,intensities[i],true));
        var mode=new ProbabilisticProfile.Mode("m","m",-1,1,.5,1,1,List.of(0.0),List.of(0.0),false);
        var p=new ProbabilisticProfile(0,0,new MetricPoint(1,.5),new MetricPoint(0,1),-2,2,1,true,0,
                samples,List.of(mode),List.of(),OptionalDouble.empty(),List.of(),0);
        var values=new ArrayList<ScalarFact>();
        for(var feature:Feature.values()) {
            if(feature==Feature.QUANTIZATION || feature==Feature.INDEPENDENT_SAMPLE_COUNT) {
                values.add(new ScalarFact(feature,expectedUnit(feature),Availability.UNAVAILABLE,OptionalDouble.empty()));
                continue;
            }
            double value=switch(feature) {
                case PEAK -> intensities[2];case BACKGROUND -> .25;case RMS_DESCRIPTOR -> .125;
                case FILTER_DESCRIPTOR -> .0625;case SHOULDER_THRESHOLD -> .3125;
                case SHOULDER_MINIMUM -> -2;case SHOULDER_MAXIMUM -> 2;case NATIVE_PITCH -> 1;
                case CORE_MINIMUM -> -1;case CORE_MAXIMUM -> 1;
                default -> 0;
            };
            values.add(new ScalarFact(feature,expectedUnit(feature),Availability.MEASURED,OptionalDouble.of(value)));
        }
        var receipts=new ArrayList<Receipt>();
        for(var stage:org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.ObservationStage.values())
            receipts.add(new StageReceipt(new org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.NestedCenterReceipt(
                    stage,true,true,2,1,3,intensities[2],.125,0),-1,1));
        return new ProfileFacts(p,List.of(new ModeFacts("m",values,receipts)),VERSION);
    }
    static BGapEvidence.Input input(EvidenceSnapshot e,String key) {
        return new BGapEvidence.Input(e,key,List.of(),List.of(),BGapEvidence.approvedContract());
    }
    static EvidenceSnapshot snapshot(String source,double phase,boolean changedMask) {
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(phase,0),1,0,0,1);
        var region=MetricRegion.rectangle(phase,0,phase+2,1);
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"scalar",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        var field=new ScalarEvidenceField(3,2,new double[]{.25,.5,.75,.25,.5,.75},
                new boolean[]{true,true,true,true,true,true},new boolean[]{!changedMask,false},lineage);
        return new EvidenceSnapshot("toy",frame,transform,EvidenceResolution.renderedOnly(1),region,region,Map.of("scalar",field),source);
    }
    static ProbabilisticProfile baseline(List<ProbabilisticProfile.Sample> samples) {
        return new ProbabilisticProfile(0,0,new MetricPoint(1,.5),new MetricPoint(0,1),-1,1,1,false,0,
                samples,List.of(),List.of(),OptionalDouble.empty(),List.of(),0);
    }
    static InferenceProfile adapted(ProbabilisticProfile p) {
        return new InferenceProfile(p.chainageMeters(),p.anchor(),p.normalUnit(),
                List.of(new LateralStateCell(0,1,false,false,"unlocalized")),new double[]{0},
                List.of(),0,ObservationOwnership.NO_SIGNAL_VALID_RASTER,true);
    }
}

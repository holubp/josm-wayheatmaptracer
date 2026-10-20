package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.*;

/** Sealed public score passes cannot mint evaluation or selected-candidate authority. */
class V022BIdentificationRunnerTest {
    @TempDir Path temp;
    private static final Path MANIFEST=Path.of("superpowers/docs/plans/2026-10-18-b-observation-feasibility-manifest.json");

    @Test void catalogPublishesOnlyMetadataWithZeroRasterAndLabelReads() throws Exception {
        var current=assertThrows(IllegalArgumentException.class,()->BProspectiveIdentificationManifest.registerMetadata(
                BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT,ProspectiveTestInputs.expectedPins()));
        assertEquals("Reviewed source member changed: service/tracing/probabilistic/BObservationGeometry.java",current.getMessage());
        Path output=temp.resolve("catalog");
        var receipt=ProspectiveTestInputs.historicalCatalogMetadata(output);
        var fields=BIdentificationReceipt.read(receipt.summary());
        assertEquals("CATALOG_REGISTERED",receipt.status());
        assertEquals(0,((Number)fields.get("rasterReads")).intValue());
        assertEquals(0,((Number)fields.get("labelReads")).intValue());
        assertTrue(Files.isRegularFile(output.resolve("binding.json")));
        assertFalse(fields.containsKey("scores")); assertFalse(fields.containsKey("truth"));
    }

    @Test void fitCannotReadHoldoutOrMintHoldoutAuthority() throws Exception {
        var calls=new AtomicInteger();
        assertThrows(SecurityException.class,()->BObservationIdentificationTool.run(MANIFEST,Phase.HOLDOUT,temp.resolve("holdout"),null,controls(null,calls)));
        assertEquals(0,calls.get()); assertFalse(Files.exists(temp.resolve("holdout")));
        Path binding=catalog();
        var holdout=BIdentificationCatalog.publicCatalog().inventory(EvaluationAccess.catalogView()).stream().filter(k->k.split()==Split.HOLDOUT).findFirst().orElseThrow();
        assertEquals("Test subset cannot open heldout or disallowed scene values",assertThrows(SecurityException.class,
                ()->HistoricalObservationMetadataFixture.publicPlan(Phase.FIT_SELECT,List.of(holdout.id()))).getMessage());
        assertEquals(0,calls.get());
    }

    @Test void changedSourceOrManifestBindingsFailBeforeEvaluation() throws Exception {
        HistoricalObservationMetadataFixture.run("binding",temp.resolve("historical-binding-check"));
        Path binding=fixtureCatalog(); var calls=new AtomicInteger();
        var fields=new LinkedHashMap<>(BIdentificationReceipt.read(binding)); fields.put("sourceHash","0".repeat(64));
        Path changed=temp.resolve("changed-binding.json"); BIdentificationReceipt.publish(changed,fields);
        assertEquals("Fixture binding changed or belongs to another protocol",assertThrows(IllegalArgumentException.class,
                ()->BObservationIdentificationTool.runFixture(Phase.EXTRACT,temp.resolve("changed-source"),changed,controls(null,calls))).getMessage());
        assertEquals(0,calls.get());
        Path altered=temp.resolve("altered-manifest.json");
        Files.writeString(altered,Files.readString(MANIFEST).replace("area-presence-core-position-v2","unreviewed-family"));
        assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.run(altered,Phase.EXTRACT,temp.resolve("changed-manifest"),binding,controls(subset(),calls)));
        assertEquals(0,calls.get());
    }

    @Test void dummyExtractReportsUnavailableFeaturesWithoutOriginalObservationAcquisition() throws Exception {
        var calls=new AtomicInteger();
        var receipt=BObservationIdentificationTool.runFixture(Phase.EXTRACT,temp.resolve("extract"),fixtureCatalog(),controls(null,calls));
        var fields=BIdentificationReceipt.read(receipt.summary());
        assertEquals("NONQUALIFYING_FIXTURE",receipt.status()); assertEquals(4,calls.get());
        assertEquals(0,((Number)fields.get("holdoutReads")).intValue());
        assertEquals(4,((Number)fields.get("sceneCount")).intValue());
        @SuppressWarnings("unchecked") var availability=(Map<String,Object>)fields.get("featureAvailability");
        assertNotNull(availability,"actual shared extractor availability must reach the dummy EXTRACT report");
        @SuppressWarnings("unchecked") var quantization=(Map<String,Object>)availability.get("QUANTIZATION");
        assertTrue(((Number)quantization.get("UNAVAILABLE")).longValue()>0);
        assertEquals(0,((Number)quantization.get("MEASURED")).longValue());
    }

    @Test void realSubsetFitRefusesFreshSelectBeforeRenderingOrPublishing() throws Exception {
        var calls=new AtomicInteger();
        String fresh=BIdentificationCatalog.publicCatalog().definitions().stream()
                .filter(d->d.key().split()==Split.SELECT).findFirst().orElseThrow().key().id();
        // Exercise the ACTUAL public-index planner, not an earlier local-CI/source refusal.
        assertEquals(subset().size(),((List<?>)HistoricalObservationMetadataFixture.publicPlan(Phase.FIT_SELECT,subset())).size());
        assertEquals("Test subset cannot open heldout or disallowed scene values",assertThrows(SecurityException.class,
                ()->HistoricalObservationMetadataFixture.publicPlan(Phase.FIT_SELECT,List.of(fresh))).getMessage());
        assertEquals(0,calls.get());assertFalse(Files.exists(temp.resolve("scores")));
        // The all32 score/four-diagnostic production pipeline remains covered by
        // dummyFixtureExercisesActualAll32ScoresAndSelectFactoryKernelMapAndPairPipeline.
    }

    @Test void selectionUsesTheLowestSelectScoreThatPassesEveryControl() {
        var first=new BIdentifiedObservationKernel.Parameters(1,1,0);
        var second=new BIdentifiedObservationKernel.Parameters(4,1,0);
        assertEquals(second,BObservationIdentificationTool.eligibleChoice(List.of(decision(first,.1,false),decision(second,.2,true)),
                OptionalDouble.of(.3),CancellationProbe.NONE).orElseThrow());
    }
    @Test void selectionRejectsAnUnavailableOrInferiorBaselineComparisonAndAllFailedControls() {
        var p=new BIdentifiedObservationKernel.Parameters(1,1,0);
        assertTrue(BObservationIdentificationTool.eligibleChoice(List.of(decision(p,.3,true)),OptionalDouble.of(.2),CancellationProbe.NONE).isEmpty());
        assertTrue(BObservationIdentificationTool.eligibleChoice(List.of(decision(p,.1,true)),OptionalDouble.empty(),CancellationProbe.NONE).isEmpty());
        assertTrue(BObservationIdentificationTool.eligibleChoice(List.of(decision(p,.1,false)),OptionalDouble.of(.2),CancellationProbe.NONE).isEmpty());
    }
    @Test void selectionKeepsTheExactRegisteredArithmeticToleranceAndDeterministicTieOrder() {
        var a=new BIdentifiedObservationKernel.Parameters(1,1,0);var b=new BIdentifiedObservationKernel.Parameters(1,1,1);
        assertEquals(a,BObservationIdentificationTool.eligibleChoice(List.of(decision(b,.2,true),decision(a,.2,true)),OptionalDouble.of(.2),CancellationProbe.NONE).orElseThrow());
        assertEquals(a,BObservationIdentificationTool.eligibleChoice(List.of(decision(a,.2+1e-12,true)),OptionalDouble.of(.2),CancellationProbe.NONE).orElseThrow());
        assertTrue(BObservationIdentificationTool.eligibleChoice(List.of(decision(a,.2+1.1e-12,true)),OptionalDouble.of(.2),CancellationProbe.NONE).isEmpty());
    }
    @Test void nonfiniteScoresAndEmptyControlEvidenceCannotRankAndCancellationCannotChoose() {
        var p=new BIdentifiedObservationKernel.Parameters(1,1,0);
        assertTrue(BObservationIdentificationTool.eligibleChoice(List.of(decision(p,Double.POSITIVE_INFINITY,true)),OptionalDouble.of(.2),CancellationProbe.NONE).isEmpty());
        var missing=new BObservationIdentificationTool.CandidateDecision(p,OptionalDouble.of(.1),new BIdentificationAcceptance.Checks(List.of()));
        assertTrue(BObservationIdentificationTool.eligibleChoice(List.of(missing),OptionalDouble.of(.2),CancellationProbe.NONE).isEmpty());
        assertThrows(CancellationException.class,()->BObservationIdentificationTool.eligibleChoice(List.of(decision(p,.1,true)),OptionalDouble.of(.2),()->true));
    }
    private static BObservationIdentificationTool.CandidateDecision decision(BIdentifiedObservationKernel.Parameters p,double score,boolean pass) {
        return new BObservationIdentificationTool.CandidateDecision(p,OptionalDouble.of(score),new BIdentificationAcceptance.Checks(List.of(
                new BIdentificationAcceptance.Check(BIdentificationAcceptance.Code.NORMALIZATION,
                        pass?BIdentificationAcceptance.Verdict.PASS:BIdentificationAcceptance.Verdict.FAIL,1))));
    }
    @Test void pairCacheRoundTripBindsOriginalSourceFactsModelAndProfileSummaries() throws Exception {
        var directory=Files.createDirectory(temp.resolve("pair"));var ledger=AttemptMemoryLedger.production();
        var original=pairSummary();
        var handle=BIdentificationPairCache.write(directory,"pair",original,"a".repeat(64),"b".repeat(64),
                (name,fields)->BIdentificationReceipt.publish(directory.resolve(name),fields),CancellationProbe.NONE);
        var index=BIdentificationPairCache.index("pair",handle,(name,fields)->BIdentificationReceipt.publish(directory.resolve(name),fields),CancellationProbe.NONE);
        try(var owner=ledger.rootOwner()) {
            var verified=BIdentificationPairCache.readIndex(directory,index,CancellationProbe.NONE,owner);
            assertEquals(handle,verified);assertEquals(original,BIdentificationPairCache.read(directory,verified,CancellationProbe.NONE,owner));
            assertTrue(ledger.currentBytes()>0);
        }
        assertEquals(0,ledger.currentBytes());assertEquals("a".repeat(64),handle.factsHash());assertEquals("b".repeat(64),handle.modelHash());
    }
    @Test void changedPairCacheBytesFailBeforeAnyComparison() throws Exception {
        var directory=Files.createDirectory(temp.resolve("changed-pair"));
        var handle=BIdentificationPairCache.write(directory,"pair",pairSummary(),"a".repeat(64),"b".repeat(64),
                (name,fields)->BIdentificationReceipt.publish(directory.resolve(name),fields),CancellationProbe.NONE);
        Files.writeString(directory.resolve(handle.chunks().get(0).file()),"{}\n");
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            assertThrows(IllegalArgumentException.class,()->BIdentificationPairCache.read(directory,handle,CancellationProbe.NONE,owner));
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void pairCacheCancellationAndRefusalPublishNoSummaryOrRetainedOwnedPayload() throws Exception {
        var directory=Files.createDirectory(temp.resolve("cancel-pair"));
        assertThrows(CancellationException.class,()->BIdentificationPairCache.write(directory,"cancel",pairSummary(),"a".repeat(64),"b".repeat(64),
                (name,fields)->BIdentificationReceipt.publish(directory.resolve(name),fields),()->true));
        try(var files=Files.list(directory)){assertEquals(0,files.count());}
        var handle=BIdentificationPairCache.write(directory,"pair",pairSummary(),"a".repeat(64),"b".repeat(64),
                (name,fields)->BIdentificationReceipt.publish(directory.resolve(name),fields),CancellationProbe.NONE);
        var tiny=new AttemptMemoryLedger(1);
        try(var owner=tiny.rootOwner()) {
            assertThrows(RuntimeException.class,()->BIdentificationPairCache.read(directory,handle,CancellationProbe.NONE,owner));
            assertEquals(0,tiny.currentBytes());
        }
    }
    private static BIdentificationAcceptance.SceneSummary pairSummary() {
        var definition=BIdentificationCatalog.publicCatalog().definitions().stream().filter(d->d.key().split()==Split.SELECT).findFirst().orElseThrow();
        var mode=new BIdentificationAcceptance.ModeSummary("original",Optional.of(new BIdentificationAcceptance.Interval(-1,1)),
                Optional.of(new BIdentificationAcceptance.Interval(-2,2)),.4,OptionalDouble.of(.5),
                Optional.of(new BIdentificationAcceptance.Quantiles(-.5,0,.5)));
        return new BIdentificationAcceptance.SceneSummary(definition,new BIdentificationAcceptance.SourceKey(definition.key().sourceGroupId(),
                "0".repeat(64),"native-input-frame",BIdentificationAcceptance.Capability.KNOWN_NATIVE),List.of(
                    new BIdentificationAcceptance.ProfileSummary(0,new MetricPoint(0,0),new MetricPoint(0,1),true,List.of()),
                    new BIdentificationAcceptance.ProfileSummary(1,new MetricPoint(1,0),new MetricPoint(0,1),false,List.of(mode))));
    }
    @Test void requiredPairsAreFrozenFromMetadataAndCannotDisappearWhenAnObservedSourceDisagrees() {
        var definitions=BIdentificationCatalog.publicCatalog().definitions().stream().filter(d->d.key().split()==Split.SELECT).toList();
        var pairs=BObservationIdentificationTool.requiredPairs(definitions,CancellationProbe.NONE);
        assertEquals(22,pairs.size());
        assertEquals(14,pairs.stream().filter(p->p.kind()==BIdentificationAcceptance.PairKind.OVERSAMPLING).count());
        assertEquals(8,pairs.stream().filter(p->p.kind()==BIdentificationAcceptance.PairKind.REVERSAL).count());
        assertTrue(pairs.stream().anyMatch(p->p.first().contrastId().equals("palette-semantic-vs-rendered-source")&&p.first().side().equals("right")));
    }
    @Test void pairProfileMutationAfterHashCannotChangeTheVerifiedDecodedSnapshot() throws Exception {
        var directory=Files.createDirectory(temp.resolve("profile-race"));var original=pairSummary();
        var handle=BIdentificationPairCache.write(directory,"pair",original,"a".repeat(64),"b".repeat(64),
                (name,fields)->BIdentificationReceipt.publish(directory.resolve(name),fields),CancellationProbe.NONE);
        var file=directory.resolve(handle.chunks().get(0).file());
        var fields=new LinkedHashMap<>(BIdentificationReceipt.read(file));
        @SuppressWarnings("unchecked") var profile=new LinkedHashMap<>((Map<String,Object>)fields.get("profile"));
        profile.put("chainage",999);fields.put("profile",profile);
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            assertEquals(original,BIdentificationPairCache.read(directory,handle,CancellationProbe.NONE,owner,
                    ()->overwrite(file,BIdentificationReceipt.canonical(fields)+"\n")));
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void pairIndexMutationAfterHashCannotChangeTheVerifiedChunkIdentities() throws Exception {
        var directory=Files.createDirectory(temp.resolve("index-race"));
        var handle=BIdentificationPairCache.write(directory,"pair",pairSummary(),"a".repeat(64),"b".repeat(64),
                (name,fields)->BIdentificationReceipt.publish(directory.resolve(name),fields),CancellationProbe.NONE);
        var index=BIdentificationPairCache.index("pair",handle,(name,fields)->BIdentificationReceipt.publish(directory.resolve(name),fields),CancellationProbe.NONE);
        var changed=new ArrayList<>(handle.chunks().stream().map(c->Map.of("file",c.file(),"sha256",c.sha256())).toList());
        changed.set(0,Map.of("file","forged-p0000.json","sha256","f".repeat(64)));
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            assertEquals(handle,BIdentificationPairCache.readIndex(directory,index,CancellationProbe.NONE,owner,
                    ()->overwrite(directory.resolve(index.file()),BIdentificationReceipt.canonical(Map.of("chunks",changed))+"\n")));
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void verifiedCacheReadRejectsOversizedInputAtTheBoundedBuffer() throws Exception {
        var file=temp.resolve("oversized.json");var bytes=new byte[65_537];Arrays.fill(bytes,(byte)' ');Files.write(file,bytes);
        assertThrows(IllegalArgumentException.class,()->BIdentificationReceipt.readVerifiedBytes(file,BIdentificationReceipt.hash(bytes),CancellationProbe.NONE,()->{}));
    }
    @Test void cachedWrapperAndIndividualProfileIdentitiesRemainOwnedForTheirActualLifetimes() throws Exception {
        var directory=Files.createDirectory(temp.resolve("owned-pair"));var original=pairSummary();
        var handle=BIdentificationPairCache.write(directory,"pair",original,"a".repeat(64),"b".repeat(64),
                (name,fields)->BIdentificationReceipt.publish(directory.resolve(name),fields),CancellationProbe.NONE);
        var index=BIdentificationPairCache.index("pair",handle,(name,fields)->BIdentificationReceipt.publish(directory.resolve(name),fields),CancellationProbe.NONE);
        var ledger=AttemptMemoryLedger.production();
        try(var root=ledger.rootOwner();var producer=root.child("producer");var consumer=root.child("consumer")) {
            var readHandle=BIdentificationPairCache.readIndex(directory,index,CancellationProbe.NONE,producer);
            var summary=BIdentificationPairCache.read(directory,readHandle,CancellationProbe.NONE,producer);
            assertDoesNotThrow(()->consumer.retain(readHandle));assertDoesNotThrow(()->consumer.retain(summary));
            for(var profile:summary.profiles())assertDoesNotThrow(()->consumer.retain(profile));
            producer.close();assertTrue(ledger.currentBytes()>0);assertEquals(original,summary);
        }
        assertEquals(0,ledger.currentBytes());
    }
    private static void overwrite(Path file,String contents) {
        try {Files.writeString(file,contents);}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
    }
    @Test void rawResourceNumericAndMissingDiagnosticsCannotBeUsableObservedRoutes() {
        for(var status:ProbabilisticInferenceResult.Status.values())assertEquals(
                Set.of(ProbabilisticInferenceResult.Status.COMPLETE,ProbabilisticInferenceResult.Status.AMBIGUOUS,
                        ProbabilisticInferenceResult.Status.REVIEW_REQUIRED).contains(status),BObservationIdentificationTool.usableRawStatus(status));
    }
    @Test void sourcePairIdentityBindsOriginalInputRatherThanTheDifferentOversampledOutputFrame() {
        var definitions=BIdentificationCatalog.publicCatalog().definitions();
        var a=definitions.stream().filter(d->d.key().split()==Split.TRAIN&&d.contrastId().equals("multiple-physical-pitches-vs-reversed-source-order")
                &&d.side().equals("left")&&d.variantId().equals("v0")).findFirst().orElseThrow();
        var b=definitions.stream().filter(d->d.key().split()==Split.TRAIN&&d.contrastId().equals(a.contrastId())
                &&d.side().equals("left")&&d.variantId().equals("v6")).findFirst().orElseThrow();
        var access=EvaluationAccess.preselection(Phase.EXTRACT);
        var first=BIdentificationScenes.render(a.key(),access);var second=BIdentificationScenes.render(b.key(),access);
        assertNotEquals(first.evidence().resampling().outputWidth(),second.evidence().resampling().outputWidth());
        assertEquals(BObservationIdentificationTool.sourceKey(a,first),BObservationIdentificationTool.sourceKey(b,second));
    }

    @Test void repeatedScorePassHasIdenticalNonTimeReceipts() throws Exception {
        Path binding=fixtureCatalog();
        var a=BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("one"),binding,controls(null,new AtomicInteger()));
        var b=BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("two"),binding,controls(null,new AtomicInteger()));
        assertEquals(Files.readString(a.summary()),Files.readString(b.summary()));
        assertEquals(receipts(temp.resolve("one")),receipts(temp.resolve("two")));
    }

    @Test void cancellationTimeoutAndMemoryRefusalNeverPublishACandidate() throws Exception {
        // Explicitly NONQUALIFYING dummy lifecycle control.
        Path binding=fixtureCatalog(); var calls=new AtomicInteger();
        var cancelled=new BObservationIdentificationTool.Controls(null,64L*1024*1024,3_600_000_000_000L,System::nanoTime,()->true,calls::incrementAndGet);
        assertThrows(CancellationException.class,()->BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("cancelled"),binding,cancelled));
        var clock=new AtomicLong();
        var timed=new BObservationIdentificationTool.Controls(null,64L*1024*1024,1,clock::incrementAndGet,CancellationProbe.NONE,calls::incrementAndGet);
        assertThrows(BObservationIdentificationTool.RunLimitException.class,()->BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("timed"),binding,timed));
        var refused=new BObservationIdentificationTool.Controls(null,1,3_600_000_000_000L,System::nanoTime,CancellationProbe.NONE,calls::incrementAndGet);
        assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("refused"),fixtureCatalog(),refused));
        assertEquals(0,calls.get());
        assertNoCandidate(temp);
    }

    @Test void baselineAdapterUsesActualUnariesAndRefusesAnActiveArithmeticFloor() {
        var profiles=new ArrayList<ProbabilisticProfile>();
        for(int i=0;i<2;i++)profiles.add(new ProbabilisticProfile(i,2*i,new MetricPoint(2*i,0),new MetricPoint(0,1),-2,3,1,true,0,
                List.of(new ProbabilisticProfile.Sample(-2,0,true),new ProbabilisticProfile.Sample(3,0,true)),List.of(),List.of(),OptionalDouble.empty(),List.of(),0));
        var lattice=new ProbabilisticStateLattice(List.of(new LateralStateCell(-1,2,false,true,"unlocalized"),new LateralStateCell(1,3,false,true,"unlocalized")),
                List.of(new ObservationComponent("u","u",ObservationComponent.Kind.MISSING,1,false)),1,false);
        var rows=profiles.stream().map(p->new ProbabilisticObservationModel().evaluate(p,lattice,EvidenceModelParameters.defaults())).toList();
        var truth=new Truth(List.of(new LabelRegion(MetricRegion.rectangle(-10,-10,10,10),StructureLabel.OBSERVED_STRUCTURE,RasterLabel.VALID,AssociationLabel.UNIQUE,
                List.of(List.of(new MetricPoint(-10,1),new MetricPoint(10,1))))));
        var scene=new Scene(V022LocalizationEvidenceTest.snapshot((x,y)->0),List.of(new MetricPoint(0,0),new MetricPoint(2,0)),truth);
        var score=BIdentificationScore.scoreBaseline(scene,profiles,List.of(lattice,lattice),rows,CancellationProbe.NONE);
        assertEquals(-StrictMath.log(.6),score.meanLogLoss().orElseThrow(),1e-14);
        var floored=profiles.stream().map(p->new InferenceProfile(p.chainageMeters(),p.anchor(),p.normalUnit(),lattice.cells(),
                new double[]{-StrictMath.log(1e-300),-StrictMath.log(1e-300)},List.of(),0,ObservationOwnership.NO_RASTER,true,new double[][]{{1},{1}})).toList();
        assertThrows(IllegalArgumentException.class,()->BIdentificationScore.scoreBaseline(scene,profiles,List.of(lattice,lattice),floored,CancellationProbe.NONE));
    }

    @Test void runnerUsesTheOriginalContinuationAndOrdinaryAdmissionOnNativeAndCoarsenedDomains() {
        for(int count:new int[]{11,843}) {
            var profiles=continuationSeries(count);
            var policy=EvidenceModelParameters.defaults();
            var proposals=ProbabilisticContinuationProposals.build(profiles,CancellationProbe.NONE,null,policy.localization());
            var expectedPlan=ProbabilisticLatticeAdmission.select(profiles,proposals,TraceBudgets.defaults(),CancellationProbe.NONE,null);
            assertTrue(expectedPlan.admitted()); assertEquals(count==843,expectedPlan.coarsened());
            var actual=BObservationIdentificationTool.admitLattices(profiles,policy,CancellationProbe.NONE,null);
            assertEquals(expectedPlan.coarsened(),actual.coarsened());
            assertEquals(expectedPlan.maximumStates(),actual.maximumStates()); assertEquals(5,actual.finiteProposals());
            assertEquals(expectedPlan.nativeForwardBackwardBound(),actual.nativeForwardBackwardBound());
            assertEquals(expectedPlan.completeScoreBound(),actual.completeScoreBound());
            var builder=new ProbabilisticStateBuilder();
            for(int i=0;i<profiles.size();i++) {
                var expected=expectedPlan.coarsened()
                        ? builder.buildWithReservedProposal(profiles.get(i),expectedPlan.maximumStates(i),proposals[i],null)
                        : builder.buildWithProposal(profiles.get(i),expectedPlan.maximumStates(),proposals[i],null);
                assertEquals(expected.lattice().orElseThrow(),actual.lattices().get(i));
                double proposed=proposals[i];
                double tolerance=1e-6*Math.max(profiles.get(i).sourcePitchMeters(),1);
                if(Double.isFinite(proposed)) assertTrue(actual.lattices().get(i).cells().stream().anyMatch(c->Math.abs(c.offsetMeters()-proposed)<=tolerance));
            }
        }
    }

    private static List<ProbabilisticProfile> continuationSeries(int count) {
        var result=new ArrayList<ProbabilisticProfile>();
        double bearing=StrictMath.atan(.1);
        var orientation=new ImageOrientationSupport(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                List.of(new ImageOrientationSupport.AngularMode(bearing,bearing,bearing,1)),1);
        for(int i=0;i<count;i++) {
            boolean boundary=i<=2 || i>=8&&i<=10;double center=.2*(i-1);
            var modes=boundary ? List.of(new ProbabilisticProfile.Mode("b","b",center-.1,center+.1,.2,1,1,
                    List.of(center),List.of(),false,orientation,.8,1,0,i==2?4:0,i==8?4:0)):List.<ProbabilisticProfile.Mode>of();
            double half=count==11?8:25;
            result.add(new ProbabilisticProfile(i,i*2,new MetricPoint(i*2,0),new MetricPoint(0,1),-half,half,count==11?1:2,true,.02,
                    List.of(new ProbabilisticProfile.Sample(-half,.02,true),new ProbabilisticProfile.Sample(half,.02,true)),modes,List.of(),OptionalDouble.empty(),orientation));
        }
        return result;
    }

    @Test void trainScheduleMatchesExhaustiveFeasibleReferenceAndDoesNotChooseLowerLossFailures() throws Exception {
        var f=new TrainFixture(32);f.geometryFailure.put(f.parameters.get(0),f.cases.get(0).key().id());
        f.cheapFailure.add(f.parameters.get(1));f.coupledFailure.add(f.parameters.get(3));
        var result=f.run();
        assertEquals(f.exhaustiveReference(),result.shortlist());
        assertEquals(List.of(f.parameters.get(2),f.parameters.get(4),f.parameters.get(5),f.parameters.get(6)),result.shortlist());
        assertEquals(32,TrainFixture.registeredParameters().size());
        assertEquals(0,f.liveReconstructions);assertEquals(1,f.peakReconstructions);
        for(var p:f.parameters)assertEquals(f.cases.size(),result.evidence().get(p).scoredScenes());
        assertEquals(0,f.geometryCount(f.parameters.get(1)));
        assertEquals(BObservationIdentificationTool.TrainState.FAILED,result.evidence().get(f.parameters.get(0)).state());
    }

    @Test void trainRunsAllScoresAndCheapControlsBeforeAnyGeometryAndPreservesTheirOrderAndBits() throws Exception {
        var f=new TrainFixture(8);f.sceneValues=new double[]{1e16,-1e16,1};
        var result=f.run();
        assertEquals(f.cases.stream().map(d->"score:"+d.key().id()).toList(),f.events.subList(0,f.cases.size()));
        for(var p:f.parameters) {
            assertEquals(Double.doubleToRawLongBits(1d/3),Double.doubleToRawLongBits(result.scores().get(p).mean().orElseThrow()));
            assertEquals(f.cases.size(),result.evidence().get(p).cheapCheckedScenes());
        }
        assertEquals(Double.doubleToRawLongBits(1d/3),Double.doubleToRawLongBits(result.baseline().mean().orElseThrow()));
        assertEquals(4*f.cases.size(),f.totalGeometry());
    }

    @Test void trainTieOrderIsExactlyGainThenLambdaThenSpanRegardlessOfInputOrder() throws Exception {
        var f=new TrainFixture(32);Collections.reverse(f.parameters);
        for(var p:f.parameters)f.loss.put(p,7d);
        var result=f.run();
        assertEquals(TrainFixture.registeredParameters().subList(0,4),result.shortlist());
        assertEquals(result.shortlist(),f.geometryOrder.stream().distinct().toList());
    }

    @Test void trainFourthFeasibleCutoffLeavesLowerGeometryUncalledAndExplicitlyUntested() throws Exception {
        var f=new TrainFixture(8);
        for(int i=4;i<f.parameters.size();i++)f.encounteredFailure.put(f.parameters.get(i),new AssertionError("unvisited lower error"));
        var result=f.run();assertEquals(f.parameters.subList(0,4),result.shortlist());
        assertEquals(4*f.cases.size(),f.totalGeometry());
        for(int i=4;i<f.parameters.size();i++) {
            var evidence=result.evidence().get(f.parameters.get(i));
            assertEquals(BObservationIdentificationTool.TrainState.UNTESTED,evidence.state());
            assertEquals(0,evidence.testedGeometryScenes());assertEquals(f.cases.size(),evidence.skippedGeometryScenes());
            assertEquals("LOWER_THAN_FOUR_PROVED_FEASIBLE",evidence.reason());
        }
    }

    @Test void trainLastSceneAndLastCoupledFailureCannotBeCalledFeasible() throws Exception {
        var f=new TrainFixture(6);var geometryOrder=f.expectedGeometryOrder();
        f.geometryFailure.put(f.parameters.get(0),geometryOrder.get(geometryOrder.size()-1).key().id());
        f.coupledFailure.add(f.parameters.get(1));
        var result=f.run();assertEquals(f.parameters.subList(2,6),result.shortlist());
        assertEquals(f.cases.size(),f.geometryCount(f.parameters.get(0)));
        assertFalse(f.coupledCalls.contains(f.parameters.get(0)));
        assertTrue(f.coupledCalls.contains(f.parameters.get(1)));
        assertEquals(1,result.evidence().get(f.parameters.get(0)).skippedRequiredPairs());
        assertEquals(0,result.evidence().get(f.parameters.get(1)).skippedRequiredPairs());
        for(int i=0;i<2;i++)assertEquals(BObservationIdentificationTool.TrainState.FAILED,result.evidence().get(f.parameters.get(i)).state());
    }

    @Test void trainExhaustsEligibleConfigurationsWhenZeroOrFewerThanFourAreFeasible() throws Exception {
        for(int successes:new int[]{0,2}) {
            var f=new TrainFixture(6);
            for(int i=successes;i<f.parameters.size();i++)f.geometryFailure.put(f.parameters.get(i),f.expectedGeometryOrder().get(0).key().id());
            var result=f.run();assertEquals(f.parameters.subList(0,successes),result.shortlist());
            for(var p:f.parameters)assertTrue(f.geometryCount(p)>0);
            assertEquals(successes*f.cases.size()+6-successes,f.totalGeometry());
            assertTrue(result.evidence().values().stream().noneMatch(e->e.state()==BObservationIdentificationTool.TrainState.UNTESTED));
        }
    }

    @Test void missingCheapEvidenceAbortsAndUnavailableRequiredControlsCannotBeFeasible() throws Exception {
        var missing=new TrainFixture(6);missing.missingCheap.add(missing.parameters.get(0));
        assertThrows(IllegalArgumentException.class,missing::run);assertEquals(0,missing.totalGeometry());
        var f=new TrainFixture(6);f.unavailableCheap.add(f.parameters.get(0));
        var result=f.run();assertEquals(f.parameters.subList(1,5),result.shortlist());
        assertEquals(0,f.geometryCount(f.parameters.get(0)));
        assertNotEquals(BObservationIdentificationTool.TrainState.FEASIBLE,result.evidence().get(f.parameters.get(0)).state());
    }

    @Test void trainNonfiniteScoreCannotEnterTheGeometryShortlist() throws Exception {
        var f=new TrainFixture(6);f.loss.put(f.parameters.get(0),Double.POSITIVE_INFINITY);
        var result=f.run();assertEquals(f.parameters.subList(1,5),result.shortlist());
        assertEquals(0,f.geometryCount(f.parameters.get(0)));
    }

    @Test void repeatedTrainReconstructionReadsAreNotMiscountedAsNewRegisteredCases() throws Exception {
        var f=new TrainFixture(32);var expanded=new ArrayList<>(f.cases);
        for(int i=0;i<14;i++)expanded.add(TrainFixture.definition("repeat-"+i,"palette-semantic-vs-rendered-source","v1",.5,1));
        f.cases=List.copyOf(expanded);String last=f.expectedGeometryOrder().get(f.cases.size()-1).key().id();
        for(var p:f.parameters)f.geometryFailure.put(p,last);
        var result=f.run();assertTrue(result.shortlist().isEmpty());
        assertEquals(17,f.cases.size());assertEquals(544,f.totalGeometry());
        assertEquals(0,f.liveReconstructions);assertEquals(1,f.peakReconstructions);
        assertEquals(544,result.evidence().values().stream().mapToInt(BObservationIdentificationTool.TrainEvidence::testedGeometryScenes).sum());
    }

    @Test void trainUsesSelectorPriorityBeforePitchThenVariantContrastAndSide() throws Exception {
        var f=new TrainFixture(4);
        var apex=TrainFixture.definition("apex","resolved-short-apex-vs-stair-step-alias","v0",.5,1);
        f.cases=List.of(f.cases.get(0),f.cases.get(1),apex,f.cases.get(2));
        f.run();
        assertEquals(List.of(apex,f.cases.get(1),f.cases.get(0),f.cases.get(3)),f.visitedDefinitions.get(f.parameters.get(0)));
    }

    @Test void encounteredTrainRefusalNumericSourceAndCacheErrorsAbortInsteadOfTryingTheNextRank() {
        for(String reason:List.of("MEMORY","TRANSITION_BUDGET","NUMERIC_FAILURE","SOURCE_BINDING","CACHE_HASH")) {
            var f=new TrainFixture(6);var expected=new BObservationIdentificationTool.RunLimitException(reason);
            f.encounteredFailure.put(f.parameters.get(0),expected);
            assertSame(expected,assertThrows(BObservationIdentificationTool.RunLimitException.class,f::run));
            assertEquals(List.of(f.parameters.get(0)),f.geometryOrder.stream().distinct().toList());
            assertEquals(0,f.liveReconstructions);
        }
    }

    @Test void trainCancellationAtTheScoreGeometryBoundaryUsesTheOriginalGuardWithoutReset() {
        var f=new TrainFixture(6);var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        f.afterAllScores=()->cancelled.set(true);f.cancellation=cancelled::get;
        assertThrows(CancellationException.class,f::run);assertEquals(0,f.totalGeometry());
        assertEquals(f.cases.size(),f.events.size());
    }

    @Test void trainScoreAndGeometryPhasesUseOneActualToolWatchdog() {
        var f=new TrainFixture(6);var clock=new AtomicLong(7);
        var controls=new BObservationIdentificationTool.Controls(null,64L*1024*1024,100,clock::get,
                CancellationProbe.NONE,()->{});
        f.cancellation=BObservationIdentificationTool.guarded(controls,clock.get());
        f.afterAllScores=()->clock.addAndGet(100);
        var failure=assertThrows(BObservationIdentificationTool.RunLimitException.class,f::run);
        assertEquals("TIME",failure.reason);assertEquals(0,f.totalGeometry());
    }

    @Test void actualTypedMemoryRefusalAbortsTrainAndClosesTheLiveReconstruction() {
        RuntimeException refusal=null;var ledger=new AttemptMemoryLedger(1);
        try(var owner=ledger.rootOwner()) {
            try {owner.reserve(2);}catch(AttemptMemoryLedger.ResourceLimitException expected){refusal=expected;}
        }
        assertNotNull(refusal);assertEquals(0,ledger.currentBytes());
        var f=new TrainFixture(6);f.encounteredFailure.put(f.parameters.get(0),refusal);
        assertSame(refusal,assertThrows(AttemptMemoryLedger.ResourceLimitException.class,f::run));
        assertEquals(0,f.liveReconstructions);assertEquals(1,f.geometryOrder.stream().distinct().count());
    }

    @Test void trainReconstructedInputsMustMatchEveryInitialFrozenBindingBeforeEligibility() {
        for(String field:List.of("source","renderer","catalog","recipe","native","frame","capability","facts","lattice")) {
            var f=new TrainFixture(6);f.bindingMismatch=field;
            assertThrows(IllegalArgumentException.class,f::run,field);
            assertFalse(f.coupledCalls.contains(f.parameters.get(0)),field);
            assertEquals(0,f.totalGeometry(),field);assertEquals(0,f.liveReconstructions,field);
        }
    }

    @Test void missingInitialBindingAndEncounteredCoupledFailureAbortRatherThanSkippingTheRank() {
        var missing=new TrainFixture(6);missing.missingBinding=true;
        assertThrows(IllegalArgumentException.class,missing::run);assertEquals(0,missing.totalGeometry());
        var f=new TrainFixture(6);var expected=new BObservationIdentificationTool.RunLimitException("COUPLED_CACHE_HASH");
        f.coupledEncountered.put(f.parameters.get(0),expected);
        assertSame(expected,assertThrows(BObservationIdentificationTool.RunLimitException.class,f::run));
        assertEquals(List.of(f.parameters.get(0)),f.geometryOrder.stream().distinct().toList());
        assertEquals(0,f.liveReconstructions);
    }

    @Test void cancellationInsideALiveTrainReconstructionClosesItAndCannotAdvanceTheRank() {
        var f=new TrainFixture(6);var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        f.cancellation=cancelled::get;f.onGeometry=()->cancelled.set(true);
        assertThrows(CancellationException.class,f::run);assertEquals(0,f.liveReconstructions);
        assertEquals(List.of(f.parameters.get(0)),f.geometryOrder.stream().distinct().toList());
    }

    @Test void trainExplicitPairApiReusesSelectRulesAndRejectsWrongSplitGroupAndSource() {
        var f=new TrainFixture(4);var first=f.cases.get(0);var second=f.cases.get(2);
        var source=f.binding(first).source();
        var pair=BIdentificationAcceptance.bindPair(first,source,second,source,Split.TRAIN).orElseThrow();
        assertEquals(BIdentificationAcceptance.PairKind.OVERSAMPLING,pair.kind());
        assertTrue(BIdentificationAcceptance.bindPair(first,source,second,source).isEmpty());
        assertTrue(BIdentificationAcceptance.bindPair(first,source,second,source,Split.SELECT).isEmpty());
        var selectedFirst=TrainFixture.withSplit(first,Split.SELECT);var selectedSecond=TrainFixture.withSplit(second,Split.SELECT);
        assertEquals(BIdentificationAcceptance.bindPair(selectedFirst,source,selectedSecond,source),
                BIdentificationAcceptance.bindPair(selectedFirst,source,selectedSecond,source,Split.SELECT));
        assertTrue(BIdentificationAcceptance.bindPair(first,source,selectedSecond,source,Split.TRAIN).isEmpty());
        var changed=new BIdentificationAcceptance.SourceKey(source.sourceGroup(),"f".repeat(64),source.nativeFrame(),source.capability());
        assertTrue(BIdentificationAcceptance.bindPair(first,source,second,changed,Split.TRAIN).isEmpty());
        var wrongGroup=new BIdentificationAcceptance.SourceKey("wrong-group",source.nativeHash(),source.nativeFrame(),source.capability());
        assertTrue(BIdentificationAcceptance.bindPair(first,wrongGroup,second,wrongGroup,Split.TRAIN).isEmpty());
        for(var forbidden:List.of(Split.HOLDOUT,Split.REGRESSION,Split.INELIGIBLE_DIAGNOSTIC))
            assertThrows(IllegalArgumentException.class,()->BIdentificationAcceptance.bindPair(first,source,second,source,forbidden));
        var inventory=BObservationIdentificationTool.requiredPairs(f.cases,Split.TRAIN,CancellationProbe.NONE);
        assertEquals(1,inventory.size());assertEquals(first,inventory.get(0).first());assertEquals(second,inventory.get(0).second());
        assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.requiredPairs(f.cases,Split.SELECT,CancellationProbe.NONE));
        for(var forbidden:List.of(Split.HOLDOUT,Split.REGRESSION,Split.INELIGIBLE_DIAGNOSTIC))
            assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.requiredPairs(f.cases,forbidden,CancellationProbe.NONE));
        var reverseFirst=TrainFixture.definition("reverse-left","multiple-physical-pitches-vs-reversed-source-order","v1",1.75,1);
        var k=reverseFirst.key();
        var reverseSecond=new CaseDefinition(new CaseKey(k.id()+"-right",k.generatorFamilyId(),k.geometryGroupId(),k.sourceGroupId(),
                Split.TRAIN,k.recipeId()+"-right",0),reverseFirst.contrastId(),"right","v1",0,1.75,1,true,
                "NONQUALIFYING-DUMMY-NO-RENDER",List.of("DUMMY_ONLY"));
        assertEquals(BIdentificationAcceptance.PairKind.REVERSAL,
                BIdentificationAcceptance.bindPair(reverseFirst,source,reverseSecond,source,Split.TRAIN).orElseThrow().kind());
    }

    /** Nonqualifying dummy scenes; this driver enters the same trainPass invoked by run(). No render or labels. */
    static final class TrainFixture implements BObservationIdentificationTool.TrainEvaluator {
        List<BIdentifiedObservationKernel.Parameters> parameters;
        List<CaseDefinition> cases=List.of(definition("a","palette-semantic-vs-rendered-source","v0",.5,1),
                definition("b","palette-semantic-vs-rendered-source","v1",1.75,1),
                definition("c","palette-semantic-vs-rendered-source","v6",.5,2));
        final Map<BIdentifiedObservationKernel.Parameters,Double> loss=new HashMap<>();
        final Set<BIdentifiedObservationKernel.Parameters> cheapFailure=new HashSet<>(),coupledFailure=new HashSet<>(),
                missingCheap=new HashSet<>(),unavailableCheap=new HashSet<>();
        final Map<BIdentifiedObservationKernel.Parameters,String> geometryFailure=new HashMap<>();
        final Map<BIdentifiedObservationKernel.Parameters,Throwable> encounteredFailure=new HashMap<>();
        final Map<BIdentifiedObservationKernel.Parameters,RuntimeException> coupledEncountered=new HashMap<>();
        final List<String> events=new ArrayList<>();
        final List<BIdentifiedObservationKernel.Parameters> geometryOrder=new ArrayList<>(),coupledCalls=new ArrayList<>();
        final Map<BIdentifiedObservationKernel.Parameters,List<CaseDefinition>> visitedDefinitions=new HashMap<>();
        Runnable afterAllScores=()->{},onGeometry=()->{};CancellationProbe cancellation=CancellationProbe.NONE;
        double[] sceneValues;String bindingMismatch;int liveReconstructions,peakReconstructions;boolean missingBinding;
        TrainFixture(int size) {
            parameters=new ArrayList<>(registeredParameters().subList(0,size));
            for(int i=0;i<parameters.size();i++)loss.put(parameters.get(i),(double)i);
        }
        static List<BIdentifiedObservationKernel.Parameters> registeredParameters() {
            var result=new ArrayList<BIdentifiedObservationKernel.Parameters>();
            for(double g:new double[]{1,4,16,64})for(double l:new double[]{1,2})for(double b:new double[]{0,1,3,7})
                result.add(new BIdentifiedObservationKernel.Parameters(g,l,b));return result;
        }
        static CaseDefinition definition(String id,String contrast,String variant,double pitch,int oversampling) {
            return new CaseDefinition(new CaseKey("NONQUALIFYING-DUMMY/train/"+id,"dummy-family","dummy-geometry","dummy-source",
                    Split.TRAIN,"dummy-recipe-"+id,0),contrast,"left",variant,0,pitch,oversampling,false,
                    "NONQUALIFYING-DUMMY-NO-RENDER",List.of("DUMMY_ONLY"));
        }
        static CaseDefinition withSplit(CaseDefinition d,Split split) {
            var k=d.key();return new CaseDefinition(new CaseKey(k.id(),k.generatorFamilyId(),k.geometryGroupId(),k.sourceGroupId(),split,k.recipeId(),k.seed()),
                    d.contrastId(),d.side(),d.variantId(),d.phase(),d.sourcePitchMeters(),d.oversampling(),d.reversed(),d.equation(),d.capabilities());
        }
        static BIdentificationScore.SceneScore sceneScore(double value) {
            return new BIdentificationScore.SceneScore(OptionalDouble.of(value),1,0,0,0,1);
        }
        static BIdentificationAcceptance.Checks checks(boolean pass) {
            return new BIdentificationAcceptance.Checks(List.of(new BIdentificationAcceptance.Check(BIdentificationAcceptance.Code.NORMALIZATION,
                    pass?BIdentificationAcceptance.Verdict.PASS:BIdentificationAcceptance.Verdict.FAIL,1)));
        }
        BObservationIdentificationTool.TrainInputBinding binding(CaseDefinition d) {
            return new BObservationIdentificationTool.TrainInputBinding("1".repeat(64),"2".repeat(64),"3".repeat(64),
                    BIdentificationCatalog.recipeHash(d),new BIdentificationAcceptance.SourceKey(d.key().sourceGroupId(),"4".repeat(64),
                    "5".repeat(64),BIdentificationAcceptance.Capability.KNOWN_NATIVE),"6".repeat(64),"7".repeat(64));
        }
        public BObservationIdentificationTool.TrainSceneScore score(CaseDefinition d,List<BIdentifiedObservationKernel.Parameters> requested) {
            assertEquals(parameters,requested);events.add("score:"+d.key().id());
            var scores=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,BIdentificationScore.SceneScore>();
            var cheap=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,BIdentificationAcceptance.Checks>();
            for(var p:requested) {
                scores.put(p,sceneScore(sceneValues==null?loss.get(p):sceneValues[cases.indexOf(d)]));
                if(!missingCheap.contains(p))cheap.put(p,unavailableCheap.contains(p)
                        ?new BIdentificationAcceptance.Checks(List.of(new BIdentificationAcceptance.Check(BIdentificationAcceptance.Code.NORMALIZATION,
                            BIdentificationAcceptance.Verdict.UNAVAILABLE,1))):checks(!cheapFailure.contains(p)));
            }
            if(events.size()==cases.size())afterAllScores.run();
            return new BObservationIdentificationTool.TrainSceneScore(sceneScore(sceneValues==null?10:sceneValues[cases.indexOf(d)]),
                    scores,cheap,missingBinding?Optional.empty():Optional.of(binding(d)));
        }
        public BObservationIdentificationTool.TrainReconstruction reconstruct(CaseDefinition d,BIdentifiedObservationKernel.Parameters p,
                BObservationIdentificationTool.TrainInputBinding frozenInput) throws Exception {
            assertEquals(binding(d),frozenInput);
            var b=binding(d);var s=b.source();String x="e".repeat(64);
            var observed=bindingMismatch==null?b:new BObservationIdentificationTool.TrainInputBinding(
                    bindingMismatch.equals("source")?x:b.sourceHash(),bindingMismatch.equals("renderer")?x:b.rendererHash(),
                    bindingMismatch.equals("catalog")?x:b.catalogHash(),bindingMismatch.equals("recipe")?x:b.recipeHash(),
                    new BIdentificationAcceptance.SourceKey(s.sourceGroup(),bindingMismatch.equals("native")?x:s.nativeHash(),
                            bindingMismatch.equals("frame")?x:s.nativeFrame(),bindingMismatch.equals("capability")
                                    ?BIdentificationAcceptance.Capability.PHYSICAL_SAMPLING_ONLY:s.capability()),
                    bindingMismatch.equals("facts")?x:b.factsHash(),bindingMismatch.equals("lattice")?x:b.latticeHash());
            liveReconstructions++;peakReconstructions=Math.max(peakReconstructions,liveReconstructions);
            return new BObservationIdentificationTool.TrainReconstruction() {
                public BObservationIdentificationTool.TrainInputBinding input() {return observed;}
                public BIdentificationAcceptance.Checks geometry() throws Exception {
                    events.add("geometry:"+p+":"+d.key().id());geometryOrder.add(p);
                    visitedDefinitions.computeIfAbsent(p,k->new ArrayList<>()).add(d);
                    onGeometry.run();cancellation.checkpoint();
                    var failure=encounteredFailure.get(p);if(failure instanceof Error error)throw error;
                    if(failure instanceof Exception exception)throw exception;
                    return checks(!d.key().id().equals(geometryFailure.get(p)));
                }
                public void close() {liveReconstructions--;}
            };
        }
        public BIdentificationAcceptance.Checks coupled(BIdentifiedObservationKernel.Parameters p,
                List<BObservationIdentificationTool.RequiredPair> required) {
            events.add("coupled:"+p);coupledCalls.add(p);assertEquals(1,required.size());
            if(coupledEncountered.containsKey(p))throw coupledEncountered.get(p);
            return checks(!coupledFailure.contains(p));
        }
        List<CaseDefinition> expectedGeometryOrder() {
            return cases.stream().sorted(Comparator.comparingDouble(CaseDefinition::sourcePitchMeters).reversed()
                    .thenComparing(CaseDefinition::variantId).thenComparing(CaseDefinition::contrastId).thenComparing(CaseDefinition::side)).toList();
        }
        List<BIdentifiedObservationKernel.Parameters> exhaustiveReference() {
            return parameters.stream().filter(p->!cheapFailure.contains(p)&&!missingCheap.contains(p)&&!unavailableCheap.contains(p)
                    &&!geometryFailure.containsKey(p)&&!coupledFailure.contains(p)&&Double.isFinite(loss.get(p)))
                    .sorted(Comparator.comparingDouble((BIdentifiedObservationKernel.Parameters p)->loss.get(p))
                            .thenComparingDouble(BIdentifiedObservationKernel.Parameters::gain)
                            .thenComparingDouble(BIdentifiedObservationKernel.Parameters::nuisanceMultiplier)
                            .thenComparingDouble(BIdentifiedObservationKernel.Parameters::directSpanSlope)).limit(4).toList();
        }
        int geometryCount(BIdentifiedObservationKernel.Parameters p) {return visitedDefinitions.getOrDefault(p,List.of()).size();}
        int totalGeometry() {return geometryOrder.size();}
        BObservationIdentificationTool.TrainPass run() throws Exception {
            return BObservationIdentificationTool.trainPass(cases,parameters,this,cancellation);
        }
    }

    @Test void dummyFixtureExercisesActualAll32ScoresAndSelectFactoryKernelMapAndPairPipeline() throws Exception {
        var calls=new AtomicInteger();var catalog=fixtureCatalog();
        var receipt=BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("dummy-fit"),catalog,controls(null,calls));
        var fields=BIdentificationReceipt.read(receipt.summary());
        assertEquals("NONQUALIFYING_FIXTURE",receipt.status());assertEquals("NONQUALIFYING_DUMMY",fields.get("scope"));
        assertEquals(32,((List<?>)fields.get("trainConfigurations")).size());
        assertEquals(4,((List<?>)fields.get("diagnosticConfigurations")).size());
        assertEquals(4,calls.get());assertEquals(4,((Number)fields.get("rasterReads")).intValue());
        assertEquals(4,((Number)fields.get("labelReads")).intValue());assertEquals(0,((Number)fields.get("holdoutReads")).intValue());
        assertEquals(2,((Number)fields.get("selectScenes")).intValue());assertEquals(8,((Number)fields.get("selectPipelineCount")).intValue());
        assertEquals(10,((Number)fields.get("rawGeometrySolves")).intValue());assertEquals(4,((Number)fields.get("pairChecks")).intValue());
        assertEquals(1,((Number)fields.get("requiredPairCount")).intValue());
        assertFalse(fields.containsKey("shortlist"));assertFalse(fields.containsKey("choice"));
        assertEquals(false,fields.get("holdoutAuthority"));assertEquals("NONE",fields.get("selectionAuthority"));
        assertNoCandidate(temp.resolve("dummy-fit"));
    }

    @Test void dummyFixtureCatalogIsMetadataOnlyAndCannotGrantRealBindingOrHoldoutAccess() throws Exception {
        var calls=new AtomicInteger();var output=temp.resolve("dummy-catalog");
        var receipt=BObservationIdentificationTool.runFixture(Phase.CATALOG,output,null,controls(null,calls));
        var fields=BIdentificationReceipt.read(receipt.summary());assertEquals(4,((Number)fields.get("sceneCount")).intValue());
        assertEquals(0,calls.get());assertEquals(0,((Number)fields.get("rasterReads")).intValue());
        assertEquals(0,((Number)fields.get("labelReads")).intValue());
        assertThrows(SecurityException.class,()->BObservationIdentificationTool.runFixture(Phase.HOLDOUT,temp.resolve("dummy-holdout"),
                output.resolve("binding.json"),controls(null,calls)));
        assertThrows(Exception.class,()->BObservationIdentificationTool.run(MANIFEST,Phase.FIT_SELECT,temp.resolve("false-real-binding"),
                output.resolve("binding.json"),controls(null,calls)));
        assertEquals(0,calls.get());assertNoCandidate(temp);
    }

    @Test void dummyFixtureRejectsForgedBindingAndForeignCaseIdsBeforeAnyRasterAccess() throws Exception {
        Path binding=fixtureCatalog();var calls=new AtomicInteger();
        var changed=new LinkedHashMap<>(BIdentificationReceipt.read(binding));changed.put("sourceHash","f".repeat(64));
        var forged=temp.resolve("forged-dummy-binding.json");BIdentificationReceipt.publish(forged,changed);
        assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("forged-dummy"),
                forged,controls(null,calls)));
        assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("foreign-dummy"),
                binding,controls(List.of("registered-or-forged-HOLDOUT"),calls)));
        assertEquals(0,calls.get());assertNoCandidate(temp);
    }

    @Test void dummyFixtureRepeatsTheActualPipelineWithIdenticalNonTimeReceipts() throws Exception {
        Path binding=fixtureCatalog();
        var first=BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("dummy-one"),binding,controls(null,new AtomicInteger()));
        var second=BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("dummy-two"),binding,controls(null,new AtomicInteger()));
        assertEquals(Files.readString(first.summary()),Files.readString(second.summary()));
        assertEquals(receipts(temp.resolve("dummy-one")),receipts(temp.resolve("dummy-two")));
        assertEquals(4,((Number)BIdentificationReceipt.read(first.summary()).get("rasterReads")).intValue());
    }

    @Test void dummyFixtureCancellationTimeoutAndMemoryRefusalCannotPublishCandidateOrContinue() throws Exception {
        Path binding=fixtureCatalog();var calls=new AtomicInteger();
        var cancelled=new BObservationIdentificationTool.Controls(null,64L*1024*1024,3_600_000_000_000L,System::nanoTime,()->true,calls::incrementAndGet);
        assertThrows(CancellationException.class,()->BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("dummy-cancel"),binding,cancelled));
        var clock=new AtomicLong();var timed=new BObservationIdentificationTool.Controls(null,64L*1024*1024,1,clock::incrementAndGet,CancellationProbe.NONE,calls::incrementAndGet);
        assertThrows(BObservationIdentificationTool.RunLimitException.class,()->BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("dummy-time"),binding,timed));
        assertThrows(RuntimeException.class,()->BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,temp.resolve("dummy-memory"),binding,
                new BObservationIdentificationTool.Controls(null,1,3_600_000_000_000L,System::nanoTime,CancellationProbe.NONE,calls::incrementAndGet)));
        assertEquals(0,calls.get());assertNoCandidate(temp);
    }

    @Test void dummyFixtureCancellationAfterTwoRealScenesRetainsTruthfulReadCounts() throws Exception {
        Path binding=fixtureCatalog();var calls=new AtomicInteger();
        var control=new BObservationIdentificationTool.Controls(null,64L*1024*1024,3_600_000_000_000L,System::nanoTime,
                ()->calls.get()>=3,calls::incrementAndGet);
        var output=temp.resolve("dummy-mid-cancel");
        assertThrows(CancellationException.class,()->BObservationIdentificationTool.runFixture(Phase.FIT_SELECT,output,binding,control));
        var failure=BIdentificationReceipt.read(output.resolve("failure.json"));
        assertEquals(2,((Number)failure.get("rasterReads")).intValue());assertEquals(2,((Number)failure.get("labelReads")).intValue());
        assertEquals(0,((Number)failure.get("holdoutReads")).intValue());assertNoCandidate(output);
    }

    @Test void dummyFixtureExtractionUsesRealFactoryFactsAndAdmittedLattices() throws Exception {
        var calls=new AtomicInteger();var output=temp.resolve("dummy-extract");
        var receipt=BObservationIdentificationTool.runFixture(Phase.EXTRACT,output,fixtureCatalog(),controls(null,calls));
        var fields=BIdentificationReceipt.read(receipt.summary());assertEquals(4,calls.get());
        assertEquals(4,((Number)fields.get("sceneCount")).intValue());
        var first=BIdentificationReceipt.read(output.resolve("scene-000.json"));
        assertTrue(((String)first.get("id")).startsWith("NONQUALIFYING_DUMMY/"));
        assertTrue(((Number)first.get("profileCount")).intValue()>=2);
        for(String hash:List.of("factsHash","latticeHash"))assertTrue(((String)first.get(hash)).matches("[0-9a-f]{64}"));
        assertNoCandidate(output);
    }

    @Test void commandLineCannotSelectTheInternalDummyFixtureOrMintItsAuthority() {
        assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.main(new String[]{"--fixture","true"}));
    }

    @Test void realPairCacheGuardAbortsMissingRequiredIndicesInsteadOfReturningAQualityFailure() {
        var f=new TrainFixture(4);var pair=new BObservationIdentificationTool.RequiredPair(f.cases.get(0),f.cases.get(2),BIdentificationAcceptance.PairKind.OVERSAMPLING);
        var p=f.parameters.get(0);var first=guardIndex(pair.first(),f.binding(pair.first()).source(),p);
        var second=guardIndex(pair.second(),f.binding(pair.second()).source(),p);
        assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.pairBindingForEvaluation(pair,null,second,p,Split.TRAIN));
        assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.pairBindingForEvaluation(pair,first,null,p,Split.TRAIN));
        assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.pairBindingForEvaluation(pair,null,null,p,Split.TRAIN));
    }

    @Test void realPairCacheGuardAbortsSourceAndNativeFrameBindingFailures() {
        var f=new TrainFixture(4);var pair=new BObservationIdentificationTool.RequiredPair(f.cases.get(0),f.cases.get(2),BIdentificationAcceptance.PairKind.OVERSAMPLING);
        var p=f.parameters.get(0);var source=f.binding(pair.first()).source();var second=guardIndex(pair.second(),source,p);
        for(var changed:List.of(new BIdentificationAcceptance.SourceKey("wrong-group",source.nativeHash(),source.nativeFrame(),source.capability()),
                new BIdentificationAcceptance.SourceKey(source.sourceGroup(),"e".repeat(64),source.nativeFrame(),source.capability()),
                new BIdentificationAcceptance.SourceKey(source.sourceGroup(),source.nativeHash(),"wrong-frame",source.capability()))) {
            var first=guardIndex(pair.first(),changed,p);
            assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.pairBindingForEvaluation(pair,first,second,p,Split.TRAIN));
        }
    }

    @Test void realPairCacheGuardAbortsStaleCaseModelAndMalformedSnapshotMetadata() {
        var f=new TrainFixture(4);var pair=new BObservationIdentificationTool.RequiredPair(f.cases.get(0),f.cases.get(2),BIdentificationAcceptance.PairKind.OVERSAMPLING);
        var p=f.parameters.get(0);var source=f.binding(pair.first()).source();var second=guardIndex(pair.second(),source,p);
        var original=guardIndex(pair.first(),source,p);
        var wrongCase=new BIdentificationPairCache.Index(pair.second(),source,original.factsHash(),original.modelHash(),original.file(),original.sha256(),original.profiles());
        var wrongModel=new BIdentificationPairCache.Index(pair.first(),source,original.factsHash(),"e".repeat(64),original.file(),original.sha256(),original.profiles());
        var malformedFacts=new BIdentificationPairCache.Index(pair.first(),source,"not-a-frozen-hash",original.modelHash(),original.file(),original.sha256(),original.profiles());
        for(var changed:List.of(wrongCase,wrongModel,malformedFacts))
            assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.pairBindingForEvaluation(pair,changed,second,p,Split.TRAIN));
    }

    @Test void realPairCacheGuardKeepsActualComparePairQualityFailuresAsControlFailures() {
        var f=new TrainFixture(4);var pair=new BObservationIdentificationTool.RequiredPair(f.cases.get(0),f.cases.get(2),BIdentificationAcceptance.PairKind.OVERSAMPLING);
        var p=f.parameters.get(0);var source=f.binding(pair.first()).source();
        var binding=assertDoesNotThrow(()->BObservationIdentificationTool.pairBindingForEvaluation(pair,guardIndex(pair.first(),source,p),
                guardIndex(pair.second(),source,p),p,Split.TRAIN));
        assertNotNull(binding);
        var checks=assertDoesNotThrow(()->BIdentificationAcceptance.comparePair(binding,guardSummary(pair.first(),source,.4),
                guardSummary(pair.second(),source,.8),CancellationProbe.NONE));
        assertFalse(checks.passed());assertTrue(checks.checks().stream().anyMatch(c->c.code()==BIdentificationAcceptance.Code.MASS_DELTA
                &&c.verdict()==BIdentificationAcceptance.Verdict.FAIL));
    }

    private static BIdentificationPairCache.Index guardIndex(CaseDefinition definition,BIdentificationAcceptance.SourceKey source,
            BIdentifiedObservationKernel.Parameters p) {
        return new BIdentificationPairCache.Index(definition,source,"a".repeat(64),new BIdentifiedObservationKernel(p).identityHash(),
                "dummy-pair-index.json","b".repeat(64),3);
    }
    private static BIdentificationAcceptance.SceneSummary guardSummary(CaseDefinition definition,BIdentificationAcceptance.SourceKey source,double mass) {
        var mode=new BIdentificationAcceptance.ModeSummary("DUMMY_OWN_MODE",Optional.of(new BIdentificationAcceptance.Interval(-.5,.5)),
                Optional.of(new BIdentificationAcceptance.Interval(-1,1)),mass,OptionalDouble.of(.5),
                Optional.of(new BIdentificationAcceptance.Quantiles(-.1,0,.1)));
        return new BIdentificationAcceptance.SceneSummary(definition,source,List.of(
                new BIdentificationAcceptance.ProfileSummary(0,new MetricPoint(0,0),new MetricPoint(0,1),true,List.of()),
                new BIdentificationAcceptance.ProfileSummary(1,new MetricPoint(1,0),new MetricPoint(0,1),false,List.of(mode)),
                new BIdentificationAcceptance.ProfileSummary(2,new MetricPoint(2,0),new MetricPoint(0,1),true,List.of())));
    }

    @Test void realTrainDriverFixtureUsesActualInitialReconstructionMapCacheAndCoupledAssembly() throws Exception {
        var output=temp.resolve("real-train-driver");
        var receipt=BObservationIdentificationTool.runTrainFixture(output,controls(null,new AtomicInteger()),BObservationIdentificationTool.TrainFixtureFault.NONE);
        var fields=BIdentificationReceipt.read(receipt.summary());
        assertEquals("REAL_SHARED_TRAIN_DRIVER_DIAGNOSTIC",fields.get("trainingGeometry"));
        assertEquals("NONQUALIFYING_FIXTURE",receipt.status());assertEquals("NONE",fields.get("selectionAuthority"));
        assertEquals(false,fields.get("holdoutAuthority"));assertFalse(fields.containsKey("shortlist"));assertFalse(fields.containsKey("choice"));
        assertEquals(2,((Number)fields.get("trainScoredScenes")).intValue());assertEquals(2,((Number)fields.get("trainReconstructionReads")).intValue());
        assertEquals(2,((Number)fields.get("trainMapSolves")).intValue());assertEquals(1,((Number)fields.get("trainPairChecks")).intValue());
        assertEquals(4,((Number)fields.get("rasterReads")).intValue());assertEquals(4,((Number)fields.get("labelReads")).intValue());
        assertEquals(0,((Number)fields.get("holdoutReads")).intValue());
        var initial=BIdentificationReceipt.read(output.resolve("scene-000.json"));
        var reconstructed=BIdentificationReceipt.read(output.resolve("train-geometry-s000-G1-L1-B0.json"));
        assertEquals(initial.get("inputBinding"),reconstructed.get("input"));
        var coupled=BIdentificationReceipt.read(output.resolve("train-pair-check-G1-L1-B0-000.json"));
        assertFalse(((List<?>)coupled.get("checks")).isEmpty());
        assertNoCandidate(output);
    }

    @Test void realTrainDriverFixtureRejectsInitialBindingMismatchBeforeItsFirstMapCall() throws Exception {
        var output=temp.resolve("real-train-binding-mismatch");
        assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.runTrainFixture(output,controls(null,new AtomicInteger()),
                BObservationIdentificationTool.TrainFixtureFault.EXPECTED_FACTS_MISMATCH));
        var failure=BIdentificationReceipt.read(output.resolve("failure.json"));
        assertEquals(0,((Number)failure.get("trainMapSolves")).intValue());assertEquals(1,((Number)failure.get("trainReconstructionReads")).intValue());
        assertEquals(0,((Number)failure.get("holdoutReads")).intValue());assertNoCandidate(output);
    }

    @Test void realTrainDriverFixtureAbortsMissingRealCoupledIndexRatherThanReportingQualityFailure() throws Exception {
        var output=temp.resolve("real-train-missing-index");
        assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.runTrainFixture(output,controls(null,new AtomicInteger()),
                BObservationIdentificationTool.TrainFixtureFault.MISSING_PAIR_INDEX));
        var failure=BIdentificationReceipt.read(output.resolve("failure.json"));
        assertEquals(2,((Number)failure.get("trainMapSolves")).intValue());assertEquals(0,((Number)failure.get("trainPairChecks")).intValue());
        assertEquals(0,((Number)failure.get("holdoutReads")).intValue());assertNoCandidate(output);
    }

    private Path fixtureCatalog() throws Exception {
        Path output=temp.resolve("fixture-catalog");
        if(!Files.exists(output))BObservationIdentificationTool.runFixture(Phase.CATALOG,output,null,controls(null,new AtomicInteger()));
        return output.resolve("binding.json");
    }

    private Path catalog() throws Exception {
        Path output=temp.resolve("catalog");
        if(!Files.exists(output)) ProspectiveTestInputs.historicalCatalogMetadata(output);
        return output.resolve("binding.json");
    }
    private static BObservationIdentificationTool.Controls controls(List<String> cases,AtomicInteger calls) {
        return new BObservationIdentificationTool.Controls(cases,64L*1024*1024,3_600_000_000_000L,System::nanoTime,CancellationProbe.NONE,calls::incrementAndGet);
    }
    private static List<String> subset() {
        var definitions=BIdentificationCatalog.publicCatalog().definitions();
        var result=new ArrayList<String>();
        // Original TRAIN only: fresh SELECT cannot be rendered by a pre-freeze unit test.
        for(var side:List.of("left","right")) result.add(definitions.stream()
                .filter(d->d.key().split()==Split.TRAIN&&d.contrastId().equals("weak-coherent-curve-vs-disconnected-flecks")&&d.side().equals(side)&&d.variantId().equals("v0"))
                .findFirst().orElseThrow().key().id());
        return result;
    }
    private static void assertNoCandidate(Path directory) throws Exception {
        if(!Files.exists(directory))return;
        try(var paths=Files.walk(directory)) {
            assertFalse(paths.anyMatch(p->Set.of("selected-candidate.json","frozen-candidate.json").contains(p.getFileName().toString())));
        }
    }
    private static Map<String,String> receipts(Path directory) throws Exception {
        var result=new TreeMap<String,String>();
        try(var paths=Files.walk(directory)) {
            for(var file:paths.filter(Files::isRegularFile).toList())result.put(directory.relativize(file).toString(),Files.readString(file));
        }
        return result;
    }
}

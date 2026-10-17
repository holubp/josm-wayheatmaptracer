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
    private static final Path MANIFEST=Path.of("superpowers/docs/plans/2026-10-16-b-observation-identification-manifest.json");

    @Test void catalogPublishesOnlyMetadataWithZeroRasterAndLabelReads() throws Exception {
        Path output=temp.resolve("catalog");
        var receipt=BObservationIdentificationTool.run(MANIFEST,Phase.CATALOG,output,null,BObservationIdentificationTool.Controls.defaults());
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
        assertThrows(SecurityException.class,()->BObservationIdentificationTool.run(MANIFEST,Phase.FIT_SELECT,temp.resolve("forbidden"),binding,controls(List.of(holdout.id()),calls)));
        assertEquals(0,calls.get());
    }

    @Test void changedSourceOrManifestBindingsFailBeforeEvaluation() throws Exception {
        Path binding=catalog(); var calls=new AtomicInteger();
        var fields=new LinkedHashMap<>(BIdentificationReceipt.read(binding)); fields.put("sourceHash","0".repeat(64));
        Path changed=temp.resolve("changed-binding.json"); BIdentificationReceipt.publish(changed,fields);
        assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.run(MANIFEST,Phase.EXTRACT,temp.resolve("changed-source"),changed,controls(subset(),calls)));
        assertEquals(0,calls.get());
        Path altered=temp.resolve("altered-manifest.json");
        Files.writeString(altered,Files.readString(MANIFEST).replace("joint-core-excess-v1","unreviewed-family"));
        assertThrows(IllegalArgumentException.class,()->BObservationIdentificationTool.run(altered,Phase.EXTRACT,temp.resolve("changed-manifest"),binding,controls(subset(),calls)));
        assertEquals(0,calls.get());
    }

    @Test void extractUsesOnlyGuardedPublicScenesAndReportsUnavailableFeatures() throws Exception {
        var calls=new AtomicInteger();
        var receipt=BObservationIdentificationTool.run(MANIFEST,Phase.EXTRACT,temp.resolve("extract"),catalog(),controls(subset(),calls));
        var fields=BIdentificationReceipt.read(receipt.summary());
        assertEquals("EXTRACTED_PUBLIC_FACTS",receipt.status()); assertEquals(2,calls.get());
        assertEquals(0,((Number)fields.get("holdoutReads")).intValue());
        assertEquals(2,((Number)fields.get("sceneCount")).intValue());
        assertTrue(Files.readString(receipt.summary()).contains("QUANTIZATION"));
        assertTrue(Files.readString(receipt.summary()).contains("UNAVAILABLE"));
    }

    @Test void all32ConfigurationsAreReportedWithoutSelectedCandidate() throws Exception {
        var calls=new AtomicInteger();
        var receipt=BObservationIdentificationTool.run(MANIFEST,Phase.FIT_SELECT,temp.resolve("scores"),catalog(),controls(subset(),calls));
        var fields=BIdentificationReceipt.read(receipt.summary());
        assertEquals("FIT_SCORED_PENDING_GEOMETRY",receipt.status()); assertEquals(2,calls.get());
        assertEquals(32,((List<?>)fields.get("trainConfigurations")).size());
        assertEquals(4,((List<?>)fields.get("shortlist")).size());
        assertEquals(4,((List<?>)fields.get("selectConfigurations")).size());
        assertEquals(0,((Number)fields.get("holdoutReads")).intValue());
        assertEquals("TEST_SUBSET_NO_FREEZE",fields.get("selectionAuthority"));
        assertFalse(((List<?>)fields.get("acceptanceReports")).isEmpty());
        assertNoCandidate(temp.resolve("scores"));
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
        Path binding=catalog();
        var a=BObservationIdentificationTool.run(MANIFEST,Phase.FIT_SELECT,temp.resolve("one"),binding,controls(subset(),new AtomicInteger()));
        var b=BObservationIdentificationTool.run(MANIFEST,Phase.FIT_SELECT,temp.resolve("two"),binding,controls(subset(),new AtomicInteger()));
        assertEquals(Files.readString(a.summary()),Files.readString(b.summary()));
        assertEquals(receipts(temp.resolve("one")),receipts(temp.resolve("two")));
    }

    @Test void cancellationTimeoutAndMemoryRefusalNeverPublishACandidate() throws Exception {
        Path binding=catalog(); var calls=new AtomicInteger();
        var cancelled=new BObservationIdentificationTool.Controls(subset(),64L*1024*1024,3_600_000_000_000L,System::nanoTime,()->true,calls::incrementAndGet);
        assertThrows(CancellationException.class,()->BObservationIdentificationTool.run(MANIFEST,Phase.FIT_SELECT,temp.resolve("cancelled"),binding,cancelled));
        var clock=new AtomicLong();
        var timed=new BObservationIdentificationTool.Controls(subset(),64L*1024*1024,1,clock::incrementAndGet,CancellationProbe.NONE,calls::incrementAndGet);
        assertThrows(RuntimeException.class,()->BObservationIdentificationTool.run(MANIFEST,Phase.FIT_SELECT,temp.resolve("timed"),binding,timed));
        var refused=new BObservationIdentificationTool.Controls(subset(),1,3_600_000_000_000L,System::nanoTime,CancellationProbe.NONE,calls::incrementAndGet);
        assertThrows(RuntimeException.class,()->BObservationIdentificationTool.run(MANIFEST,Phase.FIT_SELECT,temp.resolve("refused"),binding,refused));
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

    private Path catalog() throws Exception {
        Path output=temp.resolve("catalog");
        if(!Files.exists(output)) BObservationIdentificationTool.run(MANIFEST,Phase.CATALOG,output,null,BObservationIdentificationTool.Controls.defaults());
        return output.resolve("binding.json");
    }
    private static BObservationIdentificationTool.Controls controls(List<String> cases,AtomicInteger calls) {
        return new BObservationIdentificationTool.Controls(cases,64L*1024*1024,3_600_000_000_000L,System::nanoTime,CancellationProbe.NONE,calls::incrementAndGet);
    }
    private static List<String> subset() {
        var definitions=BIdentificationCatalog.publicCatalog().definitions();
        var result=new ArrayList<String>();
        for(var split:List.of(Split.TRAIN,Split.SELECT)) result.add(definitions.stream()
                .filter(d->d.key().split()==split&&d.contrastId().equals("weak-coherent-curve-vs-disconnected-flecks")&&d.side().equals("left")&&d.variantId().equals("v0"))
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

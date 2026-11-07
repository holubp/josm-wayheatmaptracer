package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.B06HybridFixture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticTraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

class V022BRunAdmissionReplayTest {
    private static Format15Bundle live(B06HybridFixture.Actual actual) {
        var input=actual.frozen();var bundle=Format15ProductionBundleFactory.create("b06-actual-live",input);
        return Format15ProductionBundleFactory.withActualBAdmissions(LegacyBReplayWork.attach(bundle,input,actual.computation()),input,actual.computation());
    }
    private static Format15Archive archive(Format15Bundle bundle,Path path)throws Exception {
        Format15BundleWriter.write(bundle,path);return Format15ArchiveReader.read(path);
    }
    private static void childReplay(boolean compact,Path out)throws Exception {
        var actual=B06HybridFixture.run(compact,false,false);var input=actual.frozen();var bundle=live(actual);
        var evidence=Format15BRunAdmissionReceipt.read(bundle.artifact(Format15BRunAdmissionReceipt.ARTIFACT));
        assertTrue(Format15BRunAdmissionReceipt.attested(evidence,input.request()));
        assertTrue(evidence.entries().stream().anyMatch(e->e.executed()&&e.work().orElseThrow().hasPolicy(compact?BPolicy.INVENTORY_COMPACT_VERSION:BPolicy.INVENTORY_FULL_VERSION)),evidence.toString());
        assertTrue(actual.computation().result().batch().sections().stream().flatMap(s->s.execution().stream()).anyMatch(e->!e.run().result().hypotheses().isEmpty()),"Actual child execution must produce geometry");
        var decoded=FrozenReplayCodec.decode(FrozenReplayCodec.encode(input));assertEquals(input.request(),decoded.request());
        var replay=Format15ReplayRunner.replay(archive(bundle,out.resolve("actual.zip")),ReplayLevel.FINAL_GEOMETRY,input.canonicalHash(),input.request().parameterHash());
        if(compact) {
            assertEquals(TraceHypothesisSet.Status.AMBIGUOUS,replay.inference().status(),"Conditional child uncertainty must survive guarded assembly");
            assertTrue(replay.inference().hypotheses().stream().allMatch(h->h.posteriorProbability().isEmpty()),"Section evidence cannot become a full-way posterior");
        }
        assertEquals(actual.computation().result().batch().sections().stream().flatMap(s->s.execution().stream()).map(e->e.run().result().workAccounting()).toList(),replay.nativeBExecutionWork());
        assertEquals(bundle.artifact(Format15BRunAdmissionReceipt.ARTIFACT).sha256(),replay.bRunAdmissions().sha256());
        assertEquals(actual.computation().result().batch().usage().transitions(),replay.inference().evaluatedTransitions());
        var expected=Format15ProductionBundleFactory.createWithExpectedScalarOutput("b06-expectation",decoded,TrackerMode.LEGACY_THEN_B);
        assertTrue(expected.artifactNames().contains(Format15BRunAdmissionReceipt.ARTIFACT),"Expected-output factory must transport actual child evidence");
        var expectedArchive=archive(expected,out.resolve("expected.zip"));
        assertDoesNotThrow(()->Format15ReplayRunner.replay(expectedArchive,ReplayLevel.SCALAR_INFERENCE,decoded.canonicalHash(),decoded.request().parameterHash()));
        var altered=new LinkedHashMap<>(expected.artifacts());
        String expectation=new String(expected.artifact(ScalarReplayExpectation.ARTIFACT_NAME).bytes(),java.nio.charset.StandardCharsets.UTF_8);
        String changed=expectation.replace("\"requestedEngine\":\"LEGACY_THEN_B\"","\"requestedEngine\":\"PROBABILISTIC\"");
        assertNotEquals(expectation,changed);
        altered.put(ScalarReplayExpectation.ARTIFACT_NAME,Format15Artifact.text(ScalarReplayExpectation.ARTIFACT_NAME,changed));
        var forged=new Format15Archive(15,expected.buildIdentity(),expected.sourceIdentityHash(),expected.parameterHash(),altered,expectedArchive.capability());
        assertThrows(ReplayMismatchException.class,()->Format15NumericalPolicyReceipt.validate(forged,decoded,decoded.canonicalHash()),"An attestation cannot silently change the expected engine");
    }
    @Test void actualFullChildLiveExportDecodeAndOrdinaryReplay(@TempDir Path out)throws Exception{childReplay(false,out);}
    @Test void actualCompactChildLiveExportDecodeAndOrdinaryReplayUsesRemainingBudget(@TempDir Path out)throws Exception{childReplay(true,out);}
    @Test void zeroNativeSectionsRemainExplicitAndReplayDoesNotInventBWork(@TempDir Path out)throws Exception {
        var actual=B06HybridFixture.run(false,false,true);var bundle=live(actual);var data=Format15BRunAdmissionReceipt.read(bundle.artifact(Format15BRunAdmissionReceipt.ARTIFACT));
        assertTrue(data.entries().stream().noneMatch(Format15BRunAdmissionReceipt.Entry::executed));assertTrue(Format15BRunAdmissionReceipt.work(data).isEmpty());
        var input=actual.frozen();var replay=Format15ReplayRunner.replay(archive(bundle,out.resolve("zero.zip")),ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash());
        assertTrue(replay.nativeBExecutionWork().isEmpty());
    }
    @Test void resourceLimitedChildExportsItsActualAllocationStatusAndPositiveWork(@TempDir Path out)throws Exception {
        var remaining=new TraceBudgets(96,8_000_000,1024,32,8);
        var actual=B06HybridFixture.runWithRemainingBudget(remaining);var input=actual.frozen();var bundle=live(actual);
        var data=Format15BRunAdmissionReceipt.read(bundle.artifact(Format15BRunAdmissionReceipt.ARTIFACT));
        var executed=data.entries().stream().filter(Format15BRunAdmissionReceipt.Entry::executed).toList();
        assertEquals(1,executed.size());var entry=executed.get(0);
        assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT,entry.status());assertEquals(remaining,entry.budgets());
        assertEquals(1024,entry.usage().transitions());assertEquals(1024,entry.work().orElseThrow().computedTransitions());
        assertTrue(entry.work().orElseThrow().hasPolicy(BPolicy.INVENTORY_COMPACT_VERSION));
        assertTrue(Format15BRunAdmissionReceipt.attested(data,input.request()),"Real bounded resource work is reproducible without claiming completion");
        var replay=Format15ReplayRunner.replay(archive(bundle,out.resolve("resource.zip")),ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash());
        assertEquals(bundle.artifact(Format15BRunAdmissionReceipt.ARTIFACT).sha256(),replay.bRunAdmissions().sha256());
        assertTrue(replay.nativeBExecutionWork().stream().flatMap(Optional::stream).allMatch(w->w.computedTransitions()==1024));
    }
    @Test void missingAndSwappedCompanionsCannotBeReboundIntoStrictSuccess(@TempDir Path out)throws Exception {
        var actual=B06HybridFixture.run(false,true,false);var input=actual.frozen();var bundle=live(actual);
        var missing=new LinkedHashMap<>(bundle.artifacts());missing.remove(Format15BRunAdmissionReceipt.ARTIFACT);
        var bad=new Format15Bundle(bundle.buildIdentity(),bundle.sourceIdentityHash(),bundle.parameterHash(),missing);
        assertThrows(ReplayMismatchException.class,()->Format15ReplayRunner.replay(archive(bad,out.resolve("missing.zip")),ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash()));
        var decoded=Format15BRunAdmissionReceipt.read(bundle.artifact(Format15BRunAdmissionReceipt.ARTIFACT));assertTrue(decoded.entries().size()>=2,"Need independently owned actual sections");
        var entries=new ArrayList<>(decoded.entries());Collections.swap(entries,0,1);
        var swapped=Format15BRunAdmissionReceipt.encode(new Format15BRunAdmissionReceipt.Decoded(decoded.sourceHash(),decoded.inputHash(),decoded.parameterHash(),decoded.kind(),entries));
        var rebound=new LinkedHashMap<>(bundle.artifacts());rebound.put(Format15BRunAdmissionReceipt.ARTIFACT,swapped);
        rebound.put(LegacyBReplayWork.ARTIFACT,Format15Artifact.binary(LegacyBReplayWork.ARTIFACT,
                Format15BRunAdmissionReceipt.workArtifact(Format15BRunAdmissionReceipt.read(swapped)).bytes()));
        rebound.put(Format15NumericalPolicyReceipt.ARTIFACT,Format15NumericalPolicyReceipt.actualRuns(bundle.sourceIdentityHash(),input.canonicalHash(),input,swapped));
        var selfConsistent=new Format15Bundle(bundle.buildIdentity(),bundle.sourceIdentityHash(),bundle.parameterHash(),rebound);
        assertThrows(ReplayMismatchException.class,()->Format15ReplayRunner.replay(archive(selfConsistent,out.resolve("swapped.zip")),ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash()));
        var original=decoded.entries().stream().filter(Format15BRunAdmissionReceipt.Entry::executed).findFirst().orElseThrow();
        var forgedEntry=new Format15BRunAdmissionReceipt.Entry(original.id(),original.reason(),"f".repeat(64),original.parameterHash(),original.profiles(),original.budgets(),original.usage(),original.status(),original.witness(),original.work());
        var foreignEntries=new ArrayList<>(decoded.entries());foreignEntries.set(foreignEntries.indexOf(original),forgedEntry);
        var foreign=Format15BRunAdmissionReceipt.encode(new Format15BRunAdmissionReceipt.Decoded(decoded.sourceHash(),decoded.inputHash(),decoded.parameterHash(),decoded.kind(),foreignEntries));
        var foreignArtifacts=new LinkedHashMap<>(bundle.artifacts());foreignArtifacts.put(Format15BRunAdmissionReceipt.ARTIFACT,foreign);
        foreignArtifacts.put(Format15NumericalPolicyReceipt.ARTIFACT,Format15NumericalPolicyReceipt.actualRuns(bundle.sourceIdentityHash(),input.canonicalHash(),input,foreign));
        var foreignBundle=new Format15Bundle(bundle.buildIdentity(),bundle.sourceIdentityHash(),bundle.parameterHash(),foreignArtifacts);
        var rejected=assertThrows(ReplayMismatchException.class,()->Format15ReplayRunner.replay(archive(foreignBundle,out.resolve("foreign-request.zip")),ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash()));
        assertEquals("b-run-actual-reference-mismatch",rejected.getMessage(),"Reconstructed guarded requests must defeat a self-consistent forged companion");
        var wrongCount=new Format15BRunAdmissionReceipt.Entry(original.id(),original.reason(),original.requestHash(),original.parameterHash(),original.profiles()+1,original.budgets(),original.usage(),original.status(),original.witness(),original.work());
        var countEntries=new ArrayList<>(decoded.entries());countEntries.set(countEntries.indexOf(original),wrongCount);
        var invalidCount=new Format15BRunAdmissionReceipt.Decoded(decoded.sourceHash(),decoded.inputHash(),decoded.parameterHash(),decoded.kind(),countEntries);
        assertThrows(IllegalArgumentException.class,()->Format15BRunAdmissionReceipt.attested(invalidCount,input.request()));
        var lowerBudget=new TraceBudgets(original.budgets().maximumStatesPerProfile(),original.budgets().maximumPairVisits(),1,original.budgets().maximumRawAlternatives(),original.budgets().maximumDistinctAlternatives());
        var wrongBudget=new Format15BRunAdmissionReceipt.Entry(original.id(),original.reason(),original.requestHash(),original.parameterHash(),original.profiles(),lowerBudget,original.usage(),original.status(),original.witness(),original.work());
        var budgetEntries=new ArrayList<>(decoded.entries());budgetEntries.set(budgetEntries.indexOf(original),wrongBudget);
        assertThrows(IllegalArgumentException.class,()->Format15BRunAdmissionReceipt.attested(new Format15BRunAdmissionReceipt.Decoded(decoded.sourceHash(),decoded.inputHash(),decoded.parameterHash(),decoded.kind(),budgetEntries),input.request()));
    }
    private static FrozenReplayInput standalone() {
        // Use a genuine ridge fixture. The small frozen-frame codec fixture is flat and correctly refuses before scoring.
        var f=B06HybridFixture.run(false,true,true).frozen();var r=f.request();
        var exact=new TraceRequest(r.selectedWayKey(),r.selectedRange(),TrackerMode.PROBABILISTIC,r.geometryMode(),r.permissions(),r.budgets(),
                r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),
                BPolicy.visibleExactParameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
        return new FrozenReplayInput(BPolicy.adaptiveRequest(exact,BPolicy.INVENTORY_PREP_V4),f.evidence(),f.network(),f.options());
    }
    @Test void earlyAndPartialCancellationKeepRealWorkAndAccessibleUnattestedDiagnostics(@TempDir Path out)throws Exception {
        var input=standalone();
        for(boolean partial:new boolean[]{false,true}) {
            ModernDiagnosticCounters.begin();
            try {
                var ledger=AttemptMemoryLedger.production();TraceEngineRun nativeRun;
                var forwardChecks=new java.util.concurrent.atomic.AtomicInteger();
                try(var owner=ledger.rootOwner()) {
                    nativeRun=new ProbabilisticTraceEngine(input.options().fieldName()).traceWithUsage(input.request(),input.evidence(),input.network(),
                            ()->!partial||StackWalker.getInstance().walk(frames->frames.anyMatch(frame->frame.getClassName().equals(
                                    "org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticInference")
                                    &&frame.getMethodName().equals("forwardBackward")))&&forwardChecks.incrementAndGet()>=3,owner).value();
                }
                assertEquals(0,ledger.currentBytes());assertEquals(TraceHypothesisSet.Status.CANCELLED,nativeRun.result().status(),nativeRun.result().explanation());
                if(partial)assertTrue(nativeRun.usage().transitions()>0);else assertEquals(0,nativeRun.usage().transitions());
                assertEquals(nativeRun.usage().transitions(),nativeRun.result().evaluatedTransitions());assertTrue(nativeRun.result().workAccounting().isEmpty(),"Do not invent stage accounting after cancellation");
                var actual=new ModernTracePipeline.Result(nativeRun.result(),List.of());
                var exported=Format15ProductionBundleFactory.createLive("b06-failed-attempt",input,actual,"cancelled","synthetic",-1,null,false,false,ModernDiagnosticCounters.snapshot());
                var bundle=Format15ProductionBundleFactory.withCurrentNumericalPolicy(exported,input,TrackerMode.PROBABILISTIC,List.of(nativeRun.result()));
                var readable=archive(bundle,out.resolve(partial?"partial.zip":"early.zip"));
                assertTrue(readable.artifact("frozen-input.bin").isPresent());assertTrue(new String(readable.artifact("numerical-policy.json").orElseThrow().bytes(),java.nio.charset.StandardCharsets.UTF_8).contains("execution-unattested"));
                assertThrows(ReplayMismatchException.class,()->Format15ReplayRunner.replay(readable,ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash()));
            }finally{ModernDiagnosticCounters.end();}
        }
    }
    @Test void runtimeMemoryDenialClosesOwnerAndDoesNotInventDiagnosticStageWork() {
        var input=standalone();var ledger=new AttemptMemoryLedger(1);
        try(var owner=ledger.rootOwner()) {
            var failure=assertThrows(TraceMemoryLimitException.class,()->new ProbabilisticTraceEngine(input.options().fieldName())
                    .traceWithUsage(input.request(),input.evidence(),input.network(),CancellationProbe.NONE,owner));
            assertEquals(0,failure.transitions());assertEquals(0,failure.pairVisits());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void diagnosticSerializationCapsAreIndependentFromRuntimeOwnership() {
        var nativeLedger=AttemptMemoryLedger.production();
        var skipped=new Format15BRunAdmissionReceipt.Entry("section","WORK_BUDGET",null,null,0,null,null,null,null,Optional.empty());
        var tooMany=new Format15BRunAdmissionReceipt.Decoded("a".repeat(64),"b".repeat(64),"c".repeat(64),"REFERENCE",Collections.nCopies(Format15BRunAdmissionReceipt.MAX_ENTRIES+1,skipped));
        assertThrows(IllegalArgumentException.class,()->Format15BRunAdmissionReceipt.encode(tooMany));assertEquals(0,nativeLedger.currentBytes(),"Artifact bounds do not measure native heap or release an owner");
    }
}

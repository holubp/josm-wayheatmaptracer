package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;

class V022DenseBAdmissionReplayTest {
    private static FrozenReplayInput input() {
        var f=V022FrozenFrameTest.input(V022FrozenFrameTest.frame());var r=f.request();
        var exact=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
                r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),
                BPolicy.exactParameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput());
        return new FrozenReplayInput(BPolicy.adaptiveRequest(exact,BPolicy.DENSE_ADMITTED_V3),f.evidence(),f.network(),f.options());
    }
    @Test void newRecordedPolicySurvivesOrdinaryFrozenProductionReplay(@TempDir Path directory)throws Exception {
        var input=FrozenReplayCodec.decode(FrozenReplayCodec.encode(input()));
        assertEquals(BPolicy.DENSE_ADMITTED_V3,BPolicy.recorded(input.request()));
        assertNotEquals(BPolicy.adaptiveParameterHash(BPolicy.ADAPTIVE_MULTIMODAL_V2),input.request().parameterHash());
        var bundle=Format15ProductionBundleFactory.createWithExpectedScalarOutput("dense-admission-test",input,TrackerMode.PROBABILISTIC);
        assertTrue(bundle.artifactNames().contains(Format15DenseBAdmissionReceipt.ARTIFACT));
        var path=directory.resolve("admitted.zip");Format15BundleWriter.write(bundle,path);var archive=Format15ArchiveReader.read(path);
        assertEquals(TrackerMode.PROBABILISTIC,Format15NumericalPolicyReceipt.validate(archive,input,input.canonicalHash()));
        var actual=Format15ReplayRunner.replay(archive,ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash());
        assertTrue(actual.inference().workAccounting().orElseThrow().hasPolicy(BPolicy.FULL_EXECUTION_VERSION));
        assertTrue(actual.inference().explanation().contains("|branch=dense|rows=1,1\n"));
        assertTrue(actual.inference().hypotheses().stream().allMatch(h->h.posteriorProbability().isPresent()));
    }
    @Test void admissionMustMatchFrozenBudgetsAndActualReconstructedCensus(@TempDir Path directory)throws Exception {
        var input=input();var bundle=Format15ProductionBundleFactory.createWithExpectedScalarOutput("dense-admission-tamper",input,TrackerMode.PROBABILISTIC);
        var original=bundle.artifact(Format15DenseBAdmissionReceipt.ARTIFACT);
        String json=new String(original.bytes(),StandardCharsets.UTF_8);
        var wrongDecision=new LinkedHashMap<>(bundle.artifacts());
        wrongDecision.put(original.name(),Format15Artifact.text(original.name(),json.replace("|branch=dense|","|branch=compact|")));
        var wrongPath=directory.resolve("decision.zip");Format15BundleWriter.write(new Format15Bundle(bundle.buildIdentity(),bundle.sourceIdentityHash(),bundle.parameterHash(),wrongDecision),wrongPath);
        assertThrows(ReplayMismatchException.class,()->Format15NumericalPolicyReceipt.validate(Format15ArchiveReader.read(wrongPath),input,input.canonicalHash()));
        // A self-consistent declared census is still not factual authority: native replay reconstructs 1,1.
        var forged=new LinkedHashMap<>(bundle.artifacts());
        forged.put(original.name(),Format15Artifact.text(original.name(),json.replace("|pairs=1|","|pairs=2|").replace("|rows=1,1","|rows=2,1")));
        var forgedPath=directory.resolve("census.zip");Format15BundleWriter.write(new Format15Bundle(bundle.buildIdentity(),bundle.sourceIdentityHash(),bundle.parameterHash(),forged),forgedPath);
        var forgedArchive=Format15ArchiveReader.read(forgedPath);
        assertDoesNotThrow(()->Format15NumericalPolicyReceipt.validate(forgedArchive,input,input.canonicalHash()));
        assertEquals("dense-admission-runtime-witness-mismatch",assertThrows(ReplayMismatchException.class,()->Format15ReplayRunner.replay(
                forgedArchive,ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash())).getMessage());
    }
    @Test void rejectedBoundCapturesAndReplaysConditionalCompactWork(@TempDir Path directory)throws Exception {
        var f=input();var r=f.request();
        var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),RecoveryPermissions.disabled(1),
                new TraceBudgets(16,100_000,1,4,4),r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),
                r.settingsHash(),r.parameterHash(),r.samplerId(),.5,new ProfileChainage(List.of(0.0,.5,1.0),.5),r.evidenceResolution(),r.corridorInput());
        var input=new FrozenReplayInput(request,f.evidence(),f.network(),f.options());
        var bundle=Format15ProductionBundleFactory.createWithExpectedScalarOutput("compact-admission-test",input,TrackerMode.PROBABILISTIC);
        var path=directory.resolve("compact.zip");Format15BundleWriter.write(bundle,path);var archive=Format15ArchiveReader.read(path);
        var actual=Format15ReplayRunner.replay(archive,ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash());
        assertTrue(actual.inference().workAccounting().orElseThrow().hasPolicy(BPolicy.COMPACT_EXECUTION_VERSION));
        assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT,actual.inference().status());assertEquals(1,actual.inference().evaluatedTransitions());
        assertTrue(actual.inference().hypotheses().isEmpty());assertTrue(actual.inference().explanation().contains("|branch=compact|"));
    }
}

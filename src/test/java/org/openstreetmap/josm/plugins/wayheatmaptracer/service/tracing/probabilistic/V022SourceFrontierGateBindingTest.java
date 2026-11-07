package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;

/** Metadata substitutions reuse one genuine native V5 compact/full pair. */
class V022SourceFrontierGateBindingTest {
    @Test void typedSceneCompletionKeepsMissingNativeRunsUnavailable() {
        var scene=WideCompactScenes.scene("thin-hairpin",BPolicy.INVENTORY_FRONTIER_V5);
        var report=new WideCompactRetentionFlow.Report();
        WideCompactRetentionMain.completeScene(report,scene,WideCompactQualityMain.historical(scene.input()),null,
                scene.input(),null,Path.of("unused-missing-native-output"),BPolicy.INVENTORY_FRONTIER_V5);
        assertEquals(WideCompactRetentionFlow.Status.UNAVAILABLE,report.scene(scene.id()).stage(WideCompactRetentionFlow.Stage.CANDIDATE_IDENTITY));
        assertEquals(WideCompactRetentionFlow.Status.UNAVAILABLE,report.scene(scene.id()).stage(WideCompactRetentionFlow.Stage.CANDIDATE_EXECUTION));
        assertEquals(WideCompactRetentionAssessment.Verdict.UNAVAILABLE,report.scene(scene.id()).result().verdict());
        for(BPolicy invalid:new BPolicy[]{null,BPolicy.EXACT_DEFAULT,BPolicy.ADAPTIVE_MULTIMODAL_V2})
            assertThrows(IllegalArgumentException.class,()->WideCompactRetentionMain.completeScene(new WideCompactRetentionFlow.Report(),
                    scene,WideCompactQualityMain.historical(scene.input()),null,scene.input(),null,
                    Path.of("unused-invalid-policy"),invalid));
    }

    @Test void candidateIdentityRejectsSettingsChangedAfterActualNativeExecution() throws Exception {
        var scene=WideCompactScenes.scene("thin-hairpin",BPolicy.INVENTORY_FRONTIER_V5);
        var out=Path.of("build/reports/source-frontier-gate-binding").resolve(UUID.randomUUID().toString());
        Files.createDirectories(out);
        System.out.println("V5 gate binding evidence: "+out.toAbsolutePath());
        var candidate=WideCompactQualityMain.execute(scene,scene.input(),false,true,out.resolve("candidate"),BPolicy.INVENTORY_FRONTIER_V5);
        WideCompactQualityMain.candidateProof(candidate,scene,BPolicy.INVENTORY_FRONTIER_V5);
        var reference=WideCompactRetentionMain.executeReference(scene,out.resolve("reference"),BPolicy.INVENTORY_FRONTIER_V5);
        var backend=WideCompactRetentionMain.referenceBackend(scene,reference);
        var referenceInput=reference.pipeline().result().inference().workAccounting().orElseThrow().hasPolicy(BQualityProbe.ORACLE_VERSION)
                ?scene.input():WideCompactQualityMain.historical(scene.input());
        var positive=WideCompactRetentionMain.measurePair(scene,referenceInput,reference,backend,scene.input(),candidate,
                new WideCompactRetentionFlow.SceneRecord(scene.id()),out.resolve("positive"),BPolicy.INVENTORY_FRONTIER_V5);
        assertEquals(WideCompactRetentionAssessment.Verdict.RETENTION_PASS,positive.result().verdict());
        assertEquals(WideCompactRetentionFlow.Status.PASS,positive.record().stage(WideCompactRetentionFlow.Stage.CANDIDATE_IDENTITY));
        var r=scene.input().request();
        var alteredRequest=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
                r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash()+"-changed",
                r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
        var altered=new FrozenReplayInput(alteredRequest,scene.input().evidence(),scene.input().network(),scene.input().options(),
                scene.input().planSearchRadiusMeters(),scene.input().legacyB());
        var rejected=WideCompactRetentionMain.measurePair(scene,referenceInput,reference,backend,altered,candidate,
                new WideCompactRetentionFlow.SceneRecord(scene.id()),out.resolve("changed-settings"),BPolicy.INVENTORY_FRONTIER_V5);
        Files.writeString(out.resolve("binding-result.txt"),"positive="+positive.result()+"\nchangedSettingsIdentity="
                +rejected.record().stage(WideCompactRetentionFlow.Stage.CANDIDATE_IDENTITY)+"\nchangedSettings="+rejected.result()+"\n");
        assertEquals(WideCompactRetentionFlow.Status.FAILED,rejected.record().stage(WideCompactRetentionFlow.Stage.CANDIDATE_IDENTITY));
        assertEquals(WideCompactRetentionAssessment.Verdict.FAILED,rejected.result().verdict());
        assertEquals(scene.input().canonicalHash(),candidate.executedInputHash());
        var variants=new LinkedHashMap<String,FrozenReplayInput>();
        variants.put("unknown-source-policy",changed(scene.input(),r.settingsHash(),"unknown-source-policy",r.samplerId(),25));
        variants.put("unknown-source-sampler",changed(scene.input(),r.settingsHash(),r.parameterHash(),"unknown-source-sampler",25));
        variants.put("changed-radius",changed(scene.input(),r.settingsHash(),r.parameterHash(),r.samplerId(),26));
        variants.put("changed-plan-radius",new FrozenReplayInput(r,scene.input().evidence(),scene.input().network(),
                scene.input().options(),26,scene.input().legacyB()));
        for(var entry:variants.entrySet()) {
            var bad=WideCompactRetentionMain.measurePair(scene,referenceInput,reference,backend,entry.getValue(),candidate,
                    new WideCompactRetentionFlow.SceneRecord(scene.id()),out.resolve(entry.getKey()),BPolicy.INVENTORY_FRONTIER_V5);
            assertRejected(bad);
        }
        // Matching substituted scene/input metadata still cannot change what the genuine run executed.
        var reboundScene=new WideCompactScenes.Scene(scene.id(),altered,scene.analytic(),scene.strong(),scene.hairpin(),scene.length());
        var rebound=WideCompactRetentionMain.measurePair(reboundScene,referenceInput,reference,backend,altered,candidate,
                new WideCompactRetentionFlow.SceneRecord(scene.id()),out.resolve("rebound-scene"),BPolicy.INVENTORY_FRONTIER_V5);
        assertRejected(rebound);
        // These are negative wrappers around the unchanged native output, never fabricated positive proofs.
        for(String capturedHash:new String[]{null,"0".repeat(64)}) {
            var wrongCapture=new WideCompactQualityMain.Run(candidate.pipeline(),candidate.capture(),candidate.counters(),capturedHash);
            var bad=WideCompactRetentionMain.measurePair(scene,referenceInput,reference,backend,scene.input(),wrongCapture,
                    new WideCompactRetentionFlow.SceneRecord(scene.id()),out.resolve(capturedHash==null?"missing-capture":"wrong-capture"),BPolicy.INVENTORY_FRONTIER_V5);
            assertRejected(bad);
        }
        var wrongTypedPolicy=WideCompactRetentionMain.measurePair(scene,referenceInput,reference,backend,altered,candidate,
                new WideCompactRetentionFlow.SceneRecord(scene.id()),out.resolve("downgraded-policy"),BPolicy.INVENTORY_PREP_V4);
        assertRejected(wrongTypedPolicy);
        Files.writeString(out.resolve("green-binding-checks.txt"),"positive=RETENTION_PASS\nsettings/source/sampler/radius/plan-radius/rebound-scene/missing-capture/wrong-capture/downgraded-policy=REJECTED\n");
    }

    private static void assertRejected(WideCompactRetentionMain.PairEvaluation value) {
        assertEquals(WideCompactRetentionFlow.Status.FAILED,value.record().stage(WideCompactRetentionFlow.Stage.CANDIDATE_IDENTITY));
        assertEquals(WideCompactRetentionAssessment.Verdict.FAILED,value.result().verdict());
    }

    private static FrozenReplayInput changed(FrozenReplayInput source,String settings,String parameter,String sampler,double radius) {
        var r=source.request();
        var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),
                radius==r.permissions().ordinaryRadiusMeters()?r.permissions():RecoveryPermissions.disabled(radius),r.budgets(),
                r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),settings,parameter,sampler,
                r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
        return new FrozenReplayInput(request,source.evidence(),source.network(),source.options(),
                Math.max(source.planSearchRadiusMeters(),radius),source.legacyB());
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V022WideCompactRetentionFlowTest {
    @Test void missingStagesNeverBecomeValidatedEvidenceOrGatePass() {
        var scene=new WideCompactRetentionFlow.SceneRecord("remote-drift");
        var checks=scene.runnerChecks();
        assertEquals(WideCompactRetentionAssessment.Evidence.UNAVAILABLE,checks.referenceExecution());
        assertEquals(WideCompactRetentionAssessment.Evidence.UNAVAILABLE,checks.candidateSafety());
        assertEquals(WideCompactRetentionFlow.Status.UNAVAILABLE,scene.status());
        assertFalse(WideCompactRetentionFlow.accepted(scene));
    }

    @Test void onlyCompletedProductionStagesCanPopulateRunnerValidatedFlags() {
        var scene=new WideCompactRetentionFlow.SceneRecord("remote-drift");
        for(var stage:WideCompactRetentionFlow.Stage.values()) scene.mark(stage,WideCompactRetentionFlow.Status.PASS,"actual check completed");
        var checks=scene.runnerChecks();
        assertEquals(WideCompactRetentionAssessment.Evidence.RUNNER_VALIDATED,checks.referenceExecution());
        assertEquals(WideCompactRetentionAssessment.Evidence.RUNNER_VALIDATED,checks.referenceDenseComplete());
        assertEquals(WideCompactRetentionAssessment.Evidence.RUNNER_VALIDATED,checks.candidateNativeAuthority());
        scene.mark(WideCompactRetentionFlow.Stage.CANDIDATE_SAFETY,WideCompactRetentionFlow.Status.UNAVAILABLE,"no native route");
        assertEquals(WideCompactRetentionAssessment.Evidence.UNAVAILABLE,scene.runnerChecks().candidateSafety());
    }

    @Test void assessmentUsesOnlyStageDerivedRunnerFlags() {
        var scene=new WideCompactRetentionFlow.SceneRecord("remote-drift");
        for(var stage:WideCompactRetentionFlow.Stage.values()) scene.mark(stage,WideCompactRetentionFlow.Status.PASS,"actual stage");
        scene.mark(WideCompactRetentionFlow.Stage.CANDIDATE_SAFETY,WideCompactRetentionFlow.Status.UNAVAILABLE,"safety stage absent");
        var input=exampleInput("remote-drift");
        var result=scene.setAssessment(input);
        assertEquals(WideCompactRetentionAssessment.Evidence.UNAVAILABLE,scene.runnerChecks().candidateSafety());
        assertEquals(WideCompactRetentionAssessment.Verdict.UNAVAILABLE,result.verdict());
    }

    @Test void reportPreservesFirstFailureAndDoesNotConvertUnavailableToPass() {
        var report=new WideCompactRetentionFlow.Report();
        report.ensureScene("faint-apex");
        var first=new IllegalStateException("reference native authority failed");
        report.fail("faint-apex",WideCompactRetentionFlow.Stage.REFERENCE_NATIVE_AUTHORITY,first);
        report.fail("faint-apex",WideCompactRetentionFlow.Stage.CANDIDATE_SAFETY,new IllegalStateException("later candidate failure"));
        assertSame(first,report.firstFailure().cause());
        assertEquals(WideCompactRetentionFlow.Status.FAILED,report.firstFailure().status());
        assertEquals(WideCompactRetentionFlow.Status.FAILED,report.status());
        assertTrue(WideCompactRetentionFlow.exitFailed(report));
    }

    @Test void assessmentStatusIsSeparateFromAnyReleaseQualification() {
        var report=new WideCompactRetentionFlow.Report();
        for(String id:WideCompactScenes.IDS) {
            var scene=report.scene(id);
            for(var stage:WideCompactRetentionFlow.Stage.values()) scene.mark(stage,WideCompactRetentionFlow.Status.PASS,"stage pass");
            scene.setAssessment(exampleInput(id));
        }
        assertEquals(WideCompactRetentionAssessment.Verdict.ASSESSED_WITH_UNSUITABLE_REFERENCE,report.scene("diffuse-three-strand").result().verdict());
        assertEquals(WideCompactRetentionFlow.Status.PASS,report.status());
        assertFalse(report.releaseQualified());
        assertFalse(WideCompactRetentionFlow.exitFailed(report));
    }

    @Test void reportWritesSeparateGateStatusAndTheFrozenManifest(@TempDir Path output) throws Exception {
        var report=new WideCompactRetentionFlow.Report();
        for(String id:WideCompactScenes.IDS) {
            var scene=report.scene(id);for(var stage:WideCompactRetentionFlow.Stage.values())scene.mark(stage,WideCompactRetentionFlow.Status.PASS,"actual stage");
            scene.setAssessment(exampleInput(id));
        }
        report.write(output,"deadbeef",WideCompactQualityMain.manifest());
        var json=Files.readString(output.resolve("summary.json"));
        assertTrue(json.contains("public-wide-compact-performance-retention"));
        assertTrue(json.contains("\"releaseQualified\":false"));
        assertTrue(json.contains("\"assessmentVersion\":\""+WideCompactRetentionAssessment.VERSION+"\""));
        assertTrue(Files.readString(output.resolve("public-wide-compact-v1.properties")).contains("spec="+WideCompactScenes.SPEC));
    }

    @Test void summaryJsonParsesFaintDebtAndPartialUnavailableEvidence() throws Exception {
        var report=new WideCompactRetentionFlow.Report();
        var faint=report.scene("faint-apex");
        for(var stage:WideCompactRetentionFlow.Stage.values())faint.mark(stage,WideCompactRetentionFlow.Status.PASS,"actual stage");
        faint.setAssessment(exampleInput("faint-apex"));
        var parsed=parseSummary(report.json("source\twith\bcontrols"));
        var scenes=(java.util.Map<?,?>)parsed.get("scenes");
        var row=(java.util.Map<?,?>)scenes.get("faint-apex");
        var assessment=(java.util.Map<?,?>)row.get("assessment");
        assertEquals("RETENTION_PASS_WITH_BASELINE_DEBT",assessment.get("verdict"));
        var amplitudes=(java.util.Map<?,?>)assessment.get("amplitudes");
        assertEquals("y=0m",amplitudes.get("referenceBaseline"));
        assertEquals("x=50±2p",amplitudes.get("candidateInterval"));
        assertEquals("source\twith\bcontrols",parsed.get("sourceCommit"));

        var unavailable=report.scene("remote-drift");
        var original=exampleInput("remote-drift");
        var invalid=new WideCompactRetentionAssessment.Metric(Double.NaN,WideCompactRetentionAssessment.Evidence.RUNNER_VALIDATED);
        unavailable.setAssessment(new WideCompactRetentionAssessment.Input(original.sceneId(),original.strength(),unavailable.runnerChecks(),
                new WideCompactRetentionAssessment.Distances(invalid,
                    new WideCompactRetentionAssessment.Metric(Double.POSITIVE_INFINITY,invalid.evidence()),
                    new WideCompactRetentionAssessment.Metric(Double.NEGATIVE_INFINITY,invalid.evidence()),invalid),false,false,null,null,
                original.referenceGeometry(),original.candidateGeometry()));
        var partial=parseSummary(report.json("partial"));
        assertEquals("UNAVAILABLE",partial.get("status"));
        assertEquals(Boolean.FALSE,partial.get("releaseQualified"));
        var partialScenes=(java.util.Map<?,?>)partial.get("scenes");
        var missing=(java.util.Map<?,?>)partialScenes.get("remote-drift");
        assertNull(missing.get("candidateRun"));
        var missingAssessment=(java.util.Map<?,?>)missing.get("assessment");
        var measurements=(java.util.Map<?,?>)missingAssessment.get("measurements");
        for(String name:java.util.List.of("rawP95","rawCertifiedMaximum","finalP95","finalCertifiedMaximum")) {
            var missingMetric=(java.util.Map<?,?>)measurements.get(name);
            assertNull(missingMetric.get("value"));
            assertEquals("UNAVAILABLE",missingMetric.get("evidence"));
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String,Object> parseSummary(String json) throws Exception {
        // Reuse the production archive's strict parser, without broadening its package API.
        var parser=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader.class
                .getDeclaredMethod("parseObject",byte[].class,String.class);
        parser.setAccessible(true);
        return (java.util.Map<String,Object>)parser.invoke(null,json.getBytes(java.nio.charset.StandardCharsets.UTF_8),"retention-summary.json");
    }

    @Test void workflowIsManuallyDispatchableAndPushRestrictedToDedicatedBranch() throws Exception {
        var workflow=Files.readString(Path.of(".github/workflows/wide-compact-retention.yml"));
        assertTrue(workflow.contains("workflow_dispatch:"));
        assertTrue(workflow.contains("branches: [\"codex/v022-b-retention-qualification-20261107\"]"));
        assertTrue(workflow.contains("v022WideCompactRetention"));
        var build=Files.readString(Path.of("build.gradle.kts"));
        assertTrue(build.contains("tasks.register<JavaExec>(\"v022WideCompactRetention\")"));
        assertTrue(build.contains("WideCompactRetentionMain"));
        assertEquals(Path.of(WideCompactRetentionMain.DEFAULT_OUTPUT),WideCompactRetentionMain.invocation(new String[]{"--retention"}).output());
        assertEquals(Path.of("test-output"),WideCompactRetentionMain.invocation(new String[]{"--retention","test-output"}).output());
        assertThrows(IllegalArgumentException.class,()->WideCompactRetentionMain.invocation(new String[]{"--diagnostic"}));
    }

    @Test void tinyFullCompactAndFullBypassUseActualProductionOutputs(@TempDir Path output) throws Exception {
        var scene=WideCompactScenes.tinyRetention();
        var pair=WideCompactRetentionMain.executeTinyProductionPair(output);
        for(var stage:WideCompactRetentionFlow.Stage.values())
            assertEquals(WideCompactRetentionFlow.Status.PASS,pair.record().stage(stage),stage+": "+pair.record().detail(stage));
        assertEquals(WideCompactRetentionAssessment.Verdict.RETENTION_PASS,pair.result().verdict());
        assertEquals(WideCompactRetentionAssessment.Evidence.RUNNER_VALIDATED,pair.input().distances().rawP95().evidence());
        assertEquals(WideCompactRetentionAssessment.Evidence.RUNNER_VALIDATED,pair.input().distances().finalCertifiedMaximum().evidence());
        assertEquals(0,pair.record().referenceFacts().ownerClosedBytes());
        assertEquals(0,pair.record().candidateFacts().ownerClosedBytes());
        assertTrue(pair.record().referenceFacts().denseStates()>0);
        assertTrue(pair.record().candidateFacts().denseStates()>0);
        assertTrue(pair.record().candidateFacts().retainedCells()>0);
        assertEquals(BPolicy.EXACT_DEFAULT.version(),pair.record().referenceFacts().policy());
        assertEquals(BPolicy.INVENTORY_COMPACT_VERSION,pair.record().candidateFacts().policy());
        assertNotEquals(pair.record().referenceFacts().inputHash(),pair.record().candidateFacts().inputHash());
        assertEquals(pair.record().referenceFacts().evidenceHash(),pair.record().candidateFacts().evidenceHash());
        assertEquals(pair.record().referenceFacts().networkHash(),pair.record().candidateFacts().networkHash());
        var compact=pair.candidateRun();
        System.out.println("Tiny retention actual reference="+pair.record().referenceFacts()+"; candidate="+pair.record().candidateFacts()
                +"; originalAdmission="+compact.capture().bound+"; rawP95="+pair.input().distances().rawP95()
                +"; finalMax="+pair.input().distances().finalCertifiedMaximum());
        assertTrue(compact.capture().bound.scoresExceeded());
        assertFalse(compact.capture().bound.pairsExceeded());
        assertTrue(compact.pipeline().usage().transitions()>0);
        assertThrows(AssertionError.class,()->WideCompactQualityMain.candidateProof(compact,scene),
                "Public candidate proof must retain the default-budget contract");

        // Reuse the actual historical full solve as the bypass candidate; no fake engine or extra solve.
        var fullInput=WideCompactQualityMain.historical(scene.input());
        var negative=WideCompactRetentionMain.measurePair(scene,fullInput,pair.referenceRun(),"HISTORICAL_FULL_PRODUCTION",
                fullInput,pair.referenceRun(),new WideCompactRetentionFlow.SceneRecord(scene.id()),output.resolve("full-bypass"));
        assertFalse(negative.candidateRun().pipeline().result().inference().workAccounting().orElseThrow()
                .hasPolicy(BPolicy.INVENTORY_COMPACT_VERSION));
        assertEquals(WideCompactRetentionFlow.Status.FAILED,
                negative.record().stage(WideCompactRetentionFlow.Stage.CANDIDATE_EXECUTION));
        assertEquals(WideCompactRetentionAssessment.Evidence.FAILED,negative.input().runnerChecks().candidateExecution());
        assertEquals(WideCompactRetentionAssessment.Verdict.FAILED,negative.result().verdict());
    }

    private static WideCompactRetentionAssessment.Input exampleInput(String id) {
        var e=WideCompactRetentionAssessment.Evidence.RUNNER_VALIDATED;double p=WideCompactRetentionAssessment.P;
        var good=new WideCompactRetentionAssessment.Metric(0.0,e);
        var max=new WideCompactRetentionAssessment.Metric(p*.1,e);
        var distances=new WideCompactRetentionAssessment.Distances(good,max,good,max);
        boolean faint=id.equals("faint-apex");
        var amp=faint?new WideCompactRetentionAssessment.Amplitudes(new WideCompactRetentionAssessment.Metric(1.923077,e),new WideCompactRetentionAssessment.Metric(1.923077,e),"x=50±2p","x=50±2p","y=0m","y=0m"):
                new WideCompactRetentionAssessment.Amplitudes(null,null,null,null,null,null);
        WideCompactRetentionAssessment.AbsoluteAmplitude absolute=null;
        if(faint)absolute=new WideCompactRetentionAssessment.AbsoluteAmplitude(new WideCompactRetentionAssessment.Metric(1.923077,e),new WideCompactRetentionAssessment.Metric(1.923077,e),new WideCompactRetentionAssessment.Metric(3.0,e),new WideCompactRetentionAssessment.Metric(2.7,e),"x=50±2p","y=0m");
        var reference=new WideCompactRetentionAssessment.GeometryOracle(good,max,e);
        var candidate=reference;
        if(id.equals("diffuse-three-strand")) {
            distances=new WideCompactRetentionAssessment.Distances(new WideCompactRetentionAssessment.Metric(2.307692,e),max,good,max);
            reference=new WideCompactRetentionAssessment.GeometryOracle(new WideCompactRetentionAssessment.Metric(1.153846,e),new WideCompactRetentionAssessment.Metric(1.926272,e),e);
            candidate=new WideCompactRetentionAssessment.GeometryOracle(new WideCompactRetentionAssessment.Metric(1.153846,e),new WideCompactRetentionAssessment.Metric(1.518346,e),e);
        }
        return new WideCompactRetentionAssessment.Input(id,WideCompactRetentionAssessment.Strength.WEAK,
                new WideCompactRetentionAssessment.RunnerChecks(e,e,e,e,e,e,e,e,e),distances,faint,faint,amp,absolute,reference,candidate);
    }
}

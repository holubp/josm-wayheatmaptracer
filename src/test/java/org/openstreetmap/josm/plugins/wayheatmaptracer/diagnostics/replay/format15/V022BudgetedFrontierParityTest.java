package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;

class V022BudgetedFrontierParityTest {
    @Test void diagnosticMapOrderIsRepresentationalButEveryKeyAndValueSpellingIsExact() {
        String baseline="route=0\nraw=TraceHypothesis[points=[same], diagnostics={alpha=1.0, beta=-0.0}]\nquality=unchanged\n";
        String reordered=baseline.replace("alpha=1.0, beta=-0.0","beta=-0.0, alpha=1.0");
        assertDoesNotThrow(()->BudgetedFrontierParity.assertQualityTextPreserved(baseline,reordered));
        for(String bad:List.of("alpha=1.000, beta=-0.0","alpha=1.0","alpha=1.0, beta=-0.0, gamma=2.0",
                "alpha=1.0, beta=-0.0, alpha=1.0","alpha=1.0, beta=0.0"))
            assertThrows(AssertionError.class,()->BudgetedFrontierParity.assertQualityTextPreserved(baseline,
                    baseline.replace("alpha=1.0, beta=-0.0",bad)),bad);
        assertThrows(AssertionError.class,()->BudgetedFrontierParity.assertQualityTextPreserved(baseline,baseline.replace("quality=unchanged","quality=changed")));
    }
    @Test void distinctFactualBuildInputAndPolicyBindingsPreserveCompleteNativeSemantics(@TempDir Path directory)throws Exception {
        var pair=nativePair(directory);
        assertNotEquals(pair.oldArchive().buildIdentity(),pair.newArchive().buildIdentity());
        assertNotEquals(pair.oldInput().canonicalHash(),pair.newInput().canonicalHash());
        assertFalse(Arrays.equals(pair.oldArchive().artifact(FinalOutputComponentsCodec.ARTIFACT).orElseThrow().bytes(),
            pair.newArchive().artifact(FinalOutputComponentsCodec.ARTIFACT).orElseThrow().bytes()));
        assertDoesNotThrow(()->BudgetedFrontierParity.assertSemanticOutputPreserved(pair.oldArchive(),pair.oldInput(),pair.newArchive(),pair.newInput(),true));
    }
    private record NativePair(Format15Archive oldArchive,FrozenReplayInput oldInput,Format15Archive newArchive,FrozenReplayInput newInput) { }
    private static NativePair nativePair(Path directory)throws Exception {
        var candidate=V022BudgetedFrontierReplayTest.compactInput(11000,true);
        var r=candidate.request();
        var exact=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
            r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),
            BPolicy.exactParameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
        var original=new FrozenReplayInput(BPolicy.adaptiveRequest(exact,BPolicy.INVENTORY_FRONTIER_V5),candidate.evidence(),candidate.network(),candidate.options());
        return new NativePair(archive(directory.resolve("v5.zip"),"old-build",original),original,
            archive(directory.resolve("v6.zip"),"new-build",candidate),candidate);
    }
    private static Format15Archive archive(Path path,String build,FrozenReplayInput input)throws Exception {
        var actual=Format15ReplayRunner.replay(input,ReplayLevel.FINAL_GEOMETRY,TrackerMode.PROBABILISTIC);
        assertFalse(actual.routes().isEmpty(),actual.inference().explanation());
        var pipeline=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline.Result(actual.inference(),actual.routes());
        var bundle=Format15ProductionBundleFactory.withCurrentNumericalPolicy(
            Format15ProductionBundleFactory.createLive(build,input,pipeline,"review-required","public-parity",0,null,false,false),
            input,TrackerMode.PROBABILISTIC,List.of(actual.inference()));
        Format15BundleWriter.write(bundle,path);return Format15ArchiveReader.read(path);
    }
    @Test void selfConsistentNativeMutationsCannotHideBehindFreshBindings(@TempDir Path directory)throws Exception {
        var pair=nativePair(directory);
        for(String mutation:List.of("raw-one-bit","final-assignment-one-bit","branch","support","provenance","quality","finding","diagnostic","work","explanation")) {
            var changed=mutatedArchive(directory.resolve(mutation+".zip"),pair.newArchive(),pair.newInput(),mutation);
            var expected=FinalReplayExpectation.read(changed).orElseThrow();
            assertDoesNotThrow(()->expected.validateBinding(changed,pair.newInput()),mutation);
            assertDoesNotThrow(()->FinalOutputComponentsCodec.decode(changed.artifact(FinalOutputComponentsCodec.ARTIFACT).orElseThrow().bytes(),expected.componentsBinding()),mutation);
            assertDoesNotThrow(()->Format15NumericalPolicyReceipt.validate(changed,pair.newInput(),pair.newInput().canonicalHash()),mutation);
            assertThrows(AssertionError.class,()->BudgetedFrontierParity.assertSemanticOutputPreserved(
                pair.oldArchive(),pair.oldInput(),changed,pair.newInput(),true),mutation);
        }
    }
    @Test void samePolicyExplanationAndWorkRemainExact(@TempDir Path directory)throws Exception {
        var pair=nativePair(directory);
        assertDoesNotThrow(()->BudgetedFrontierParity.assertSemanticOutputPreserved(pair.newArchive(),pair.newInput(),pair.newArchive(),pair.newInput(),false));
        for(String mutation:List.of("work","explanation")) {
            var changed=mutatedArchive(directory.resolve("same-"+mutation+".zip"),pair.newArchive(),pair.newInput(),mutation);
            assertThrows(AssertionError.class,()->BudgetedFrontierParity.assertSemanticOutputPreserved(
                pair.newArchive(),pair.newInput(),changed,pair.newInput(),false),mutation);
        }
    }
    @Test void missingForgedAndArchiveContradictingComponentsFailClosed(@TempDir Path directory)throws Exception {
        var pair=nativePair(directory);var original=pair.newArchive();
        for(String name:List.of(FinalOutputComponentsCodec.ARTIFACT,FinalReplayExpectation.ARTIFACT_NAME,Format15ExecutionWorkCodec.ARTIFACT)) {
            var artifacts=new LinkedHashMap<>(original.artifacts());artifacts.remove(name);
            var missing=new Format15Archive(original.formatVersion(),original.buildIdentity(),original.sourceIdentityHash(),original.parameterHash(),artifacts,original.capability());
            assertThrows(RuntimeException.class,()->BudgetedFrontierParity.assertSemanticOutputPreserved(pair.oldArchive(),pair.oldInput(),missing,pair.newInput(),true),name);
        }
        var artifacts=new LinkedHashMap<>(original.artifacts());
        byte[] bad=artifacts.get(FinalOutputComponentsCodec.ARTIFACT).bytes();bad[bad.length/2]^=1;
        artifacts.put(FinalOutputComponentsCodec.ARTIFACT,Format15Artifact.binary(FinalOutputComponentsCodec.ARTIFACT,bad));
        var malformed=new Format15Archive(original.formatVersion(),original.buildIdentity(),original.sourceIdentityHash(),original.parameterHash(),artifacts,original.capability());
        assertThrows(RuntimeException.class,()->BudgetedFrontierParity.assertSemanticOutputPreserved(pair.oldArchive(),pair.oldInput(),malformed,pair.newInput(),true));
        // Each inner component/expectation pair is self-consistent, but the archive header is factual authority.
        var foreign=new Format15Archive(original.formatVersion(),"forged-build",original.sourceIdentityHash(),original.parameterHash(),original.artifacts(),original.capability());
        assertEquals("expected-final-output-stale",assertThrows(ReplayMismatchException.class,()->BudgetedFrontierParity.assertSemanticOutputPreserved(
            pair.oldArchive(),pair.oldInput(),foreign,pair.newInput(),true)).getMessage());
        var stale=new Format15Archive(original.formatVersion(),original.buildIdentity(),"f".repeat(64),original.parameterHash(),original.artifacts(),original.capability());
        assertThrows(ReplayMismatchException.class,()->BudgetedFrontierParity.assertSemanticOutputPreserved(pair.oldArchive(),pair.oldInput(),stale,pair.newInput(),true));
    }
    private static TraceHypothesis changedHypothesis(TraceHypothesis h,String mutation) {
        var points=new ArrayList<>(h.points());var support=new ArrayList<>(h.support());var diagnostics=new LinkedHashMap<>(h.diagnostics());
        String branch=h.branchSignature();
        if(mutation.equals("raw-one-bit")||mutation.equals("final-assignment-one-bit")) {
            var p=points.get(1);points.set(1,new MetricPoint(Math.nextUp(p.xMeters()),p.yMeters()));
        } else if(mutation.equals("branch"))branch+="-changed";
        else if(mutation.equals("support"))support.set(1,support.get(1)==ObservationOwnership.DIRECT_TWO_SIDED?ObservationOwnership.INFERRED_GAP:ObservationOwnership.DIRECT_TWO_SIDED);
        else if(mutation.equals("diagnostic")) {
            String key=diagnostics.keySet().iterator().next();diagnostics.put(key,Math.nextUp(diagnostics.get(key)));
        }
        return new TraceHypothesis(h.id(),branch,points,support,h.objective(),h.posteriorProbability(),diagnostics);
    }
    private static Format15Archive mutatedArchive(Path path,Format15Archive archive,FrozenReplayInput input,String mutation)throws Exception {
        var expected=FinalReplayExpectation.read(archive).orElseThrow();
        var saved=FinalOutputComponentsCodec.decode(archive.artifact(FinalOutputComponentsCodec.ARTIFACT).orElseThrow().bytes(),expected.componentsBinding()).output();
        var work=Format15ExecutionWorkCodec.read(archive.artifact(Format15ExecutionWorkCodec.ARTIFACT).orElseThrow()).get(0).orElseThrow();
        if(mutation.equals("work")) {
            var s=work.bStages().get(0);
            work=new ExecutionWorkReceipt(List.of(new InferenceWorkAccounting(s.executionPolicy(),s.forwardBackwardScores(),s.kBestScores(),s.logicalKBestExtensions()+1,s.refusedScoreAttempts())),0);
        }
        var old=saved.inference();
        var inference=new TraceHypothesisSet(old.engine(),old.hypotheses(),old.status(),old.alternativesTruncated(),old.evaluatedStates(),
            old.evaluatedTransitions(),old.explanation()+(mutation.equals("explanation")?"\nchanged-lineage":""),Optional.of(work));
        var routes=new ArrayList<>(saved.routes());var route=routes.get(0);
        var raw=route.rawHypothesis();var finalHypothesis=route.hypothesis();
        var assignments=new LinkedHashMap<>(route.assignments());var ownership=new LinkedHashMap<>(route.sourceOwnership());var quality=route.quality();
        if(List.of("raw-one-bit","branch","support","diagnostic").contains(mutation))raw=changedHypothesis(raw,mutation);
        if(mutation.equals("final-assignment-one-bit")) {
            finalHypothesis=changedHypothesis(finalHypothesis,mutation);assignments.put(route.pointIds().get(1),finalHypothesis.points().get(1));
        }
        if(mutation.equals("provenance")) {
            var id=route.pointIds().get(1);ownership.put(id,ownership.get(id)==ObservationOwnership.DIRECT_TWO_SIDED?ObservationOwnership.INFERRED_GAP:ObservationOwnership.DIRECT_TWO_SIDED);
        }
        if(mutation.equals("quality")||mutation.equals("finding")) {
            var findings=new ArrayList<>(quality.findings());
            if(mutation.equals("finding"))findings.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.SEARCH_TRUNCATED,FinalGeometryEvaluator.Severity.REVIEW,0,1,0));
            quality=new FinalGeometryEvaluator.Result(quality.id(),quality.disposition(),findings,quality.totalLengthMeters(),quality.directlySupportedLengthMeters(),
                quality.worstUnsupportedSpanMeters(),quality.meanImageCenterCost(),mutation.equals("quality")?Math.nextUp(quality.bendPreservingRoughness()):quality.bendPreservingRoughness());
        }
        routes.set(0,new ModernTracePipeline.Route(raw,finalHypothesis,route.pointIds(),assignments,ownership,quality,route.cleanupStatus(),route.geometryChanged()));
        var pipeline=new ModernTracePipeline.Result(inference,routes);
        var bundle=Format15ProductionBundleFactory.withCurrentNumericalPolicy(
            Format15ProductionBundleFactory.createLive(archive.buildIdentity(),input,pipeline,"review-required","public-parity",0,null,false,false),
            input,TrackerMode.PROBABILISTIC,List.of(inference));
        Format15BundleWriter.write(bundle,path);return Format15ArchiveReader.read(path);
    }
    @Test void parameterOnlyNormalizationRestoresEveryFrozenPlanByteWithoutMutatingCandidate() {
        var original=V022FrozenFrameTest.plan(V022BudgetedFrontierReplayTest.input(true));
        var candidate=BudgetedFrontierParity.normalizeParameterForComparison(original,"new-parameter");
        assertNotEquals(original.canonicalHash(),candidate.canonicalHash());
        assertEquals("new-parameter",candidate.parameterHash());
        var normalized=BudgetedFrontierParity.normalizeParameterForComparison(candidate,original.parameterHash());
        assertArrayEquals(FrozenReplayCodec.encodeEditPlan(original),FrozenReplayCodec.encodeEditPlan(normalized));
        assertEquals(original.canonicalHash(),normalized.canonicalHash());
        assertEquals("new-parameter",candidate.parameterHash());
        assertFalse(PreviewReviewState.fromEditPlan("route",original).confirm().matches(
            PreviewReviewState.fromEditPlan("route",candidate)));
    }
    @Test void completeArtifactComparisonRejectsChangedNativeGeometryAndAuthorityBytes() {
        var input=V022BudgetedFrontierReplayTest.compactInput(5096,true);
        var actual=Format15ReplayRunner.replay(input,org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel.FINAL_GEOMETRY,
                TrackerMode.PROBABILISTIC);
        var pipeline=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline.Result(actual.inference(),actual.routes());
        var plan=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter()
                .adaptFrozen(input.request(),input.evidence(),input.network(),input.options(),pipeline,0,input.planSearchRadiusMeters());
        var bundle=Format15ProductionBundleFactory.createLive("public-parity",input,pipeline,"REVIEW_REQUIRED","synthetic-public",0,plan,false,false);
        var baseline=new LinkedHashMap<String,byte[]>();
        for(String key:BudgetedFrontierParity.EXACT_ARTIFACTS)baseline.put(key,bundle.artifact(key).bytes());
        for(String name:BudgetedFrontierParity.EXACT_ARTIFACTS) {
            var candidate=new LinkedHashMap<String,byte[]>(baseline);
            assertDoesNotThrow(()->BudgetedFrontierParity.assertExactArtifacts(baseline,candidate));
            if(name.endsWith(".json")) {
                var points=new ArrayList<>(name.equals("raw-route.json")?actual.routes().get(0).rawHypothesis().points():actual.routes().get(0).hypothesis().points());
                var point=points.get(1);points.set(1,new MetricPoint(Math.nextUp(point.xMeters()),point.yMeters()));
                candidate.put(name,Format15ProductionBundleFactory.metricGeometry(points).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                byte[] changed=baseline.get(name).clone();changed[changed.length/2]^=1;candidate.put(name,changed);
            }
            assertThrows(AssertionError.class,()->BudgetedFrontierParity.assertExactArtifacts(baseline,candidate),name);
            candidate.remove(name);
            assertThrows(AssertionError.class,()->BudgetedFrontierParity.assertExactArtifacts(baseline,candidate),name);
        }
    }
    @Test void samePolicyInputsMustBeByteExactAndTypedTransformCannotHideChangedSettings() {
        var original=V022BudgetedFrontierReplayTest.input(true);
        assertDoesNotThrow(()->BudgetedFrontierParity.assertInputPreserved(original,original,false));
        var r=original.request();
        var changed=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
            r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),"changed-settings",
            r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput());
        var input=new FrozenReplayInput(changed,original.evidence(),original.network(),original.options());
        assertThrows(AssertionError.class,()->BudgetedFrontierParity.assertInputPreserved(original,input,false));
        assertThrows(AssertionError.class,()->BudgetedFrontierParity.assertInputPreserved(original,input,true));
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;

class V022BudgetedFrontierReplayTest {
    static FrozenReplayInput input(boolean managed) {
        var f=V022FrozenFrameTest.input(V022FrozenFrameTest.frame());var r=f.request();
        var exact=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
            r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),
            managed?BPolicy.exactParameterHash():BPolicy.visibleExactParameterHash(),r.samplerId(),r.configuredSampleStepMeters(),
            r.profileChainage(),r.evidenceResolution(),r.corridorInput());
        return new FrozenReplayInput(BPolicy.adaptiveRequest(exact,BPolicy.INVENTORY_FRONTIER_BUDGETED_V6),f.evidence(),f.network(),f.options());
    }
    @Test void denseManagedAndVisibleRoundTripAndActualBinding(@TempDir Path directory)throws Exception {
        for(boolean managed:new boolean[]{true,false}) {
            var input=input(managed);
            var bundle=Format15ProductionBundleFactory.createWithExpectedScalarOutput("budgeted-test",input,TrackerMode.PROBABILISTIC);
            assertTrue(bundle.artifactNames().contains(Format15BRefinementBudgetReceipt.ARTIFACT));
            var path=directory.resolve(managed+".zip");Format15BundleWriter.write(bundle,path);
            var archive=Format15ArchiveReader.read(path);
            var result=Format15ReplayRunner.replay(archive,ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash());
            assertTrue(result.inference().workAccounting().orElseThrow().hasPolicy(BPolicy.BUDGETED_FRONTIER_FULL_VERSION));
            var witness=BRefinementBudget.fromExplanation(result.inference().explanation()).orElseThrow();
            assertEquals(BRefinementBudget.Outcome.DENSE,witness.outcome());
            assertDoesNotThrow(()->Format15BRefinementBudgetReceipt.validateActual(archive,List.of(result.inference())));
            var nativeRun=result.inference();
            var changed=new TraceHypothesisSet(nativeRun.engine(),nativeRun.hypotheses(),nativeRun.status(),nativeRun.alternativesTruncated(),
                nativeRun.evaluatedStates(),nativeRun.evaluatedTransitions(),nativeRun.explanation().replace(witness.originalIndexHash(),"b".repeat(64)),nativeRun.workAccounting());
            assertThrows(ReplayMismatchException.class,()->Format15BRefinementBudgetReceipt.validateActual(archive,List.of(changed)));
            var stage=nativeRun.workAccounting().orElseThrow().bStages().get(0);
            var oldWork=new InferenceWorkAccounting(BPolicy.FRONTIER_FULL_VERSION,stage.forwardBackwardScores(),stage.kBestScores(),stage.logicalKBestExtensions(),stage.refusedScoreAttempts());
            var relabeled=new TraceHypothesisSet(nativeRun.engine(),nativeRun.hypotheses(),nativeRun.status(),nativeRun.alternativesTruncated(),nativeRun.evaluatedStates(),
                nativeRun.evaluatedTransitions(),nativeRun.explanation(),Optional.of(new ExecutionWorkReceipt(List.of(oldWork),0)));
            assertThrows(ReplayMismatchException.class,()->Format15NumericalPolicyReceipt.validateExecuted(relabeled,input.request().parameterHash()));
        }
    }
    @Test void missingTamperedAndSelfConsistentWrongHashFailClosed(@TempDir Path directory)throws Exception {
        var input=input(true);var bundle=Format15ProductionBundleFactory.createWithExpectedScalarOutput("budgeted-tamper",input,TrackerMode.PROBABILISTIC);
        String original=new String(bundle.artifact(Format15BRefinementBudgetReceipt.ARTIFACT).bytes(),StandardCharsets.UTF_8);
        for(String replacement:List.of("",original.replace(input.canonicalHash(),"0".repeat(64)),
                original.replace("outcome=DENSE","outcome=FIRST_GRAPH_PROVEN_UNAFFORDABLE"),original.replace("lowerBoundScores=unavailable","lowerBoundScores=1"))) {
            var artifacts=new LinkedHashMap<>(bundle.artifacts());
            if(replacement.isEmpty())artifacts.remove(Format15BRefinementBudgetReceipt.ARTIFACT);
            else artifacts.put(Format15BRefinementBudgetReceipt.ARTIFACT,Format15Artifact.text(Format15BRefinementBudgetReceipt.ARTIFACT,replacement));
            var path=directory.resolve("tamper.zip");Format15BundleWriter.write(new Format15Bundle(bundle.buildIdentity(),bundle.sourceIdentityHash(),bundle.parameterHash(),artifacts),path);
            var archive=Format15ArchiveReader.read(path);
            assertThrows(ReplayMismatchException.class,()->Format15ReplayRunner.replay(archive,ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash()));
        }
    }
    @Test void parameterOnlyPlanChangeInvalidatesExactReviewConfirmation() {
        var original=V022FrozenFrameTest.plan(input(true));
        var changed=new AlignmentEditPlan(original.selectedWayKey(),original.selectedRange(),original.before(),original.after(),
                original.metricFrame(),original.permissions(),original.settingsHash(),original.evidenceHash(),
                BPolicy.adaptiveParameterHash(BPolicy.INVENTORY_FRONTIER_V5),original.routeIdentity(),
                original.finalPreviewWays(),original.validation(),original.disconnectionSource(),
                original.disconnectedOccurrenceReplacements(),original.disconnectionGeometryMode());
        assertEquals(original.before(),changed.before());assertEquals(original.after(),changed.after());
        assertNotEquals(original.canonicalHash(),changed.canonicalHash());
        var review=org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState.fromEditPlan("route",original).confirm();
        assertFalse(review.matches(org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState.fromEditPlan("route",changed)));
    }
    @Test void explicitV6RejectsNonPreciseOrNonDirectRequests() {
        var input=input(true);var r=input.request();
        for(var mode:List.of(AlignmentMode.MOVE_EXISTING_NODES)) {
            var bad=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),mode,r.permissions(),r.budgets(),
                r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),
                r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput());
            assertThrows(IllegalArgumentException.class,()->BPolicy.recorded(bad));
        }
        var bad=new TraceRequest(r.selectedWayKey(),r.selectedRange(),TrackerMode.CORRIDOR_AWARE,r.geometryMode(),r.permissions(),r.budgets(),
                r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),
                r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput());
        assertThrows(IllegalArgumentException.class,()->BPolicy.recorded(bad));
    }
    static FrozenReplayInput compactInput(long cap,boolean strong) {
        return compactInput(cap,strong,1,32);
    }
    static FrozenReplayInput compactInput(long cap,boolean strong,double radius,int rawLimit) {
        var f=input(true);var r=f.request();var e=f.evidence();
        if(strong) {
            double[] values=new double[16];boolean[] valid=new boolean[16];Arrays.fill(valid,true);
            for(int x=0;x<4;x++)values[4+x]=1;
            var field=new ScalarEvidenceField(4,4,values,valid,e.fields().get("native").lineage());
            e=new EvidenceSnapshot(e.snapshotId(),e.coordinateFrame(),e.transform(),e.resolution(),e.decisionRegion(),
                e.evidenceRegion(),Map.of("native",field),e.sourceIdentity());
        }
        if(radius>1) {
            double[] values=new double[48];boolean[] valid=new boolean[48];Arrays.fill(valid,true);
            for(int x=0;x<4;x++)values[4+x]=1;
            var field=new ScalarEvidenceField(4,12,values,valid,e.fields().get("native").lineage());
            e=new EvidenceSnapshot(e.snapshotId(),e.coordinateFrame(),e.transform(),e.resolution(),
                    MetricRegion.rectangle(0,0,3,11),MetricRegion.rectangle(-.5,-.5,3.5,11.5),
                    Map.of("native",field),e.sourceIdentity());
        }
        var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),RecoveryPermissions.disabled(radius),
            new TraceBudgets(96,100000,cap,rawLimit,Math.min(8,rawLimit)),e.snapshotId(),e.canonicalHash(),r.networkSnapshotId(),r.networkContentHash(),
            r.settingsHash(),r.parameterHash(),r.samplerId(),.125,new ProfileChainage(
                java.util.stream.IntStream.rangeClosed(0,8).mapToObj(i->i*.125).toList(),.125),r.evidenceResolution(),r.corridorInput());
        return new FrozenReplayInput(request,e,f.network(),f.options());
    }
    @Test void publicNativeCompactOutcomesAreAttested(@TempDir Path directory)throws Exception {
        var outcomes=EnumSet.noneOf(BRefinementBudget.Outcome.class);
        int scene=0;
        for(var input:List.of(compactInput(5096,true),compactInput(11000,true),compactInput(50000,true,7,1))) {
            var bundle=Format15ProductionBundleFactory.createWithExpectedScalarOutput("budgeted-small-native",input,TrackerMode.PROBABILISTIC);
            var path=directory.resolve("completed-"+scene+++".zip");Format15BundleWriter.write(bundle,path);
            var archive=Format15ArchiveReader.read(path);
            if(!bundle.artifactNames().contains(Format15BRefinementBudgetReceipt.ARTIFACT)) {
                var unavailable=Format15ReplayRunner.replay(input,ReplayLevel.SCALAR_INFERENCE,TrackerMode.PROBABILISTIC);
                System.out.println("PUBLIC_NATIVE_UNAVAILABLE cap="+input.request().budgets().maximumTransitions()+" status="+unavailable.inference().status()
                    +" work="+unavailable.inference().evaluatedTransitions()+" explanation="+unavailable.inference().explanation());
                continue;
            }
            var actual=Format15ReplayRunner.replay(archive,ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash());
            var witness=BRefinementBudget.fromExplanation(actual.inference().explanation()).orElseThrow();
            assertTrue(DenseBAdmission.validateWitness(DenseBAdmission.witnessFromExplanation(actual.inference().explanation()),
                    input.request().budgets(),input.request().profileChainage().cumulativeGroundMeters().size()).scoresExceeded(),
                    "A conservative upper refusal is not a lower-bound refusal");
            if(witness.outcome()==BRefinementBudget.Outcome.ORIGINAL_REFINEMENT) {
                long first=input.request().budgets().maximumTransitions()-witness.remainingScores().orElseThrow();
                assertTrue(witness.lowerBoundScores().orElseThrow()<=actual.inference().evaluatedTransitions()-first);
                var r=input.request();
                var oldRequest=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
                    r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),
                    BPolicy.newCaptureParameterHash(BPolicy.exactParameterHash(),BPolicy.INVENTORY_FRONTIER_V5),r.samplerId(),
                    r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput());
                var old=Format15ReplayRunner.replay(new FrozenReplayInput(oldRequest,input.evidence(),input.network(),input.options()),
                    ReplayLevel.SCALAR_INFERENCE,TrackerMode.PROBABILISTIC);
                assertEquals(old.inference().hypotheses(),actual.inference().hypotheses());
                assertEquals(old.inference().evaluatedTransitions(),actual.inference().evaluatedTransitions());
            }
            if(witness.outcome()==BRefinementBudget.Outcome.FIRST_GRAPH_PROVEN_UNAFFORDABLE) {
                var forged=new LinkedHashMap<>(bundle.artifacts());
                String json=new String(bundle.artifact(Format15BRefinementBudgetReceipt.ARTIFACT).bytes(),StandardCharsets.UTF_8);
                forged.put(Format15BRefinementBudgetReceipt.ARTIFACT,Format15Artifact.text(Format15BRefinementBudgetReceipt.ARTIFACT,
                    json.replace(witness.originalIndexHash(),"b".repeat(64))));
                var forgedPath=directory.resolve("self-consistent-forged-compact.zip");
                Format15BundleWriter.write(new Format15Bundle(bundle.buildIdentity(),bundle.sourceIdentityHash(),bundle.parameterHash(),forged),forgedPath);
                var forgedArchive=Format15ArchiveReader.read(forgedPath);
                assertDoesNotThrow(()->Format15BRefinementBudgetReceipt.validate(forgedArchive,input,input.canonicalHash()));
                assertEquals("refinement-budget-runtime-witness-mismatch",assertThrows(ReplayMismatchException.class,
                    ()->Format15ReplayRunner.replay(forgedArchive,ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash())).getMessage());
            }
            System.out.println("PUBLIC_NATIVE_OUTCOME cap="+input.request().budgets().maximumTransitions()+" outcome="+witness.outcome()+" work="+actual.inference().evaluatedTransitions()+" "+witness.canonical());
            outcomes.add(witness.outcome());
        }
        assertTrue(outcomes.contains(BRefinementBudget.Outcome.FIRST_GRAPH_PROVEN_UNAFFORDABLE),outcomes.toString());
        assertTrue(outcomes.contains(BRefinementBudget.Outcome.ORIGINAL_REFINEMENT),outcomes.toString());
        assertTrue(outcomes.contains(BRefinementBudget.Outcome.NOT_NEEDED),outcomes.toString());
    }
    @Test void nativeFailureExportsActualWorkWithUnavailableCompletionAttestation(@TempDir Path directory)throws Exception {
        var resource=compactInput(1,false);
        var base=input(true);var e=base.evidence();var r=base.request();
        var region=new MetricRegion(List.of(MetricRegion.rectangle(0,0,1.1,3).polygons().get(0),
                MetricRegion.rectangle(1.9,0,3,3).polygons().get(0)));
        var evidence=new EvidenceSnapshot(e.snapshotId(),e.coordinateFrame(),e.transform(),e.resolution(),region,e.evidenceRegion(),e.fields(),e.sourceIdentity());
        var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
                evidence.snapshotId(),evidence.canonicalHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),
                r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput());
        var noRoute=new FrozenReplayInput(request,evidence,base.network(),base.options());
        int index=0;
        for(var input:List.of(resource,compactInput(10000,true),noRoute)) {
            var nativeRun=Format15ReplayRunner.replay(input,ReplayLevel.SCALAR_INFERENCE,TrackerMode.PROBABILISTIC).inference();
            assertTrue(nativeRun.hypotheses().isEmpty());
            assertEquals(index<2?TraceHypothesisSet.Status.RESOURCE_LIMIT:TraceHypothesisSet.Status.NO_ROUTE,nativeRun.status());
            var bundle=Format15ProductionBundleFactory.createWithExpectedScalarOutput("failed-native-v6",input,TrackerMode.PROBABILISTIC);
            assertFalse(bundle.artifactNames().contains(Format15BRefinementBudgetReceipt.ARTIFACT));
            var declared=Format15ArchiveReader.parseObject(bundle.artifact(Format15NumericalPolicyReceipt.ARTIFACT).bytes(),Format15NumericalPolicyReceipt.ARTIFACT);
            assertEquals("execution-unattested",declared.get("declaration"));
            var work=Format15ExecutionWorkCodec.read(bundle.artifact(Format15ExecutionWorkCodec.ARTIFACT)).get(0).orElseThrow();
            assertEquals(nativeRun.evaluatedTransitions(),work.computedTransitions());
            if(index==0)assertEquals(1,work.computedTransitions());
            if(index==1)assertEquals(10000,work.computedTransitions(),"A refused second pass cannot salvage the completed first route");
            var path=directory.resolve("failed-"+index+++".zip");Format15BundleWriter.write(bundle,path);
            var archive=Format15ArchiveReader.read(path);
            assertEquals("numerical-policy-unattested",assertThrows(ReplayMismatchException.class,()->Format15ReplayRunner.replay(
                    archive,ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash())).getMessage());
        }
    }
    @Test void nativeFallbackFinalizationReplayAndPlanRequireFreshReview(@TempDir Path directory)throws Exception {
        var input=compactInput(5096,true);
        long[] closed={-1};
        var actual=V022BudgetedFrontierCompletionTest.withNativeOwnerRelease(()->Format15ReplayRunner.replay(
                input,ReplayLevel.FINAL_GEOMETRY,TrackerMode.PROBABILISTIC),bytes->closed[0]=bytes);
        assertEquals(0,closed[0]);
        assertFalse(actual.routes().isEmpty());
        var route=actual.routes().get(0);
        assertEquals(1.0,route.rawHypothesis().diagnostics().get("bBudgetedFirstGraphV1"));
        assertTrue(route.quality().has(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.FindingCode.REFINEMENT_NOT_PERFORMED_BUDGET));
        assertNotEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Disposition.HARD_BLOCKED,route.quality().disposition());
        var assessment=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter().adaptFrozen(
                input.request(),input.evidence(),input.network(),input.options(),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline.Result(actual.inference(),actual.routes()),0,input.planSearchRadiusMeters());
        var plan=assessment;
        assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED,plan.validation().disposition());
        var state=org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState.fromEditPlan(route.hypothesis().id(),plan);
        assertFalse(state.canApply());assertTrue(state.confirm().canApply());
        assertFalse(state.confirm().withCandidate("new-attempt:other-route").canApply());
        var changed=BudgetedFrontierParity.normalizeParameterForComparison(plan,"changed-source-parameter");
        assertFalse(state.confirm().matches(org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState.fromEditPlan(route.hypothesis().id(),changed)));
        var source=input.network();
        for(String stale:List.of("coordinate","occurrence-sequence","dataset","generation")) {
            var primitives=new LinkedHashMap<>(source.primitives());
            if(stale.equals("coordinate")) {
                var key=((DetachedWay)primitives.get(input.request().selectedWayKey())).nodeKeys().get(0);
                var node=(DetachedNode)primitives.get(key);
                primitives.put(key,new DetachedNode(key,new GeographicPoint(Math.nextUp(node.coordinate().latitudeDegrees()),
                    node.coordinate().longitudeDegrees()),node.tags(),node.deleted(),node.modified()));
            }
            if(stale.equals("occurrence-sequence")) {
                var way=(DetachedWay)primitives.get(input.request().selectedWayKey());
                var reversed=new ArrayList<>(way.nodeKeys());Collections.reverse(reversed);
                primitives.put(way.key(),new DetachedWay(way.key(),reversed,way.tags(),way.deleted(),way.modified()));
            }
            var altered=new NetworkSnapshot(source.snapshotId(),source.role(),stale.equals("dataset")?"other-dataset":source.datasetIdentity(),
                    source.sourceGeneration()+(stale.equals("generation")?1:0),source.closure(),primitives,
                    source.incomingReferrerWatches(),source.semanticWitness(),source.proposedPortReplacements());
            assertThrows(IllegalArgumentException.class,()->new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter().adaptFrozen(
                    input.request(),input.evidence(),altered,input.options(),
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline.Result(actual.inference(),actual.routes()),0,input.planSearchRadiusMeters()),stale);
        }
        var bundle=Format15ProductionBundleFactory.createWithExpectedFinalOutput("native-budgeted-final",input,TrackerMode.PROBABILISTIC);
        var path=directory.resolve("final.zip");Format15BundleWriter.write(bundle,path);
        var replay=Format15ReplayRunner.replay(Format15ArchiveReader.read(path),ReplayLevel.FINAL_GEOMETRY,input.canonicalHash(),input.request().parameterHash());
        assertEquals(actual.geometry(),replay.geometry());
        assertEquals(actual.routes().get(0).assignments(),replay.routes().get(0).assignments());
        closed[0]=-1;boolean[] reached={false};
        assertThrows(java.util.concurrent.CancellationException.class,()->V022BudgetedFrontierCompletionTest.withNativeOwnerRelease(
            ()->new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline(
                (request,evidence,network,cancellation)->{throw new AssertionError("Direct B must not call A");})
                .run(input.request(),input.evidence(),input.network(),input.options(),()-> {
                boolean inside=Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame->frame.getClassName().endsWith("FinalGeometryEvaluator"));
                reached[0]|=inside;return inside;
            }),bytes->closed[0]=bytes));
        assertTrue(reached[0]);assertEquals(0,closed[0]);
    }
    @Test void cancelledNativeRunExportsNoFabricatedCompletedPass(@TempDir Path directory)throws Exception {
        var input=compactInput(5096,true);var run=new ProbabilisticTraceEngine("native").trace(
                input.request(),input.evidence(),input.network(),()->true);
        assertEquals(TraceHypothesisSet.Status.CANCELLED,run.status());assertTrue(run.hypotheses().isEmpty());
        var bundle=Format15ProductionBundleFactory.withCurrentNumericalPolicy(
                Format15ProductionBundleFactory.createWithExpectedScalarOutput("cancelled-v6",input,TrackerMode.PROBABILISTIC),
                input,TrackerMode.PROBABILISTIC,List.of(run));
        assertFalse(bundle.artifactNames().contains(Format15BRefinementBudgetReceipt.ARTIFACT));
        assertTrue(BRefinementBudget.fromExplanation(run.explanation()).isEmpty());
        var declaration=Format15ArchiveReader.parseObject(bundle.artifact(Format15NumericalPolicyReceipt.ARTIFACT).bytes(),Format15NumericalPolicyReceipt.ARTIFACT);
        assertEquals("execution-unattested",declaration.get("declaration"));
        var path=directory.resolve("cancelled.zip");Format15BundleWriter.write(bundle,path);
        var archive=Format15ArchiveReader.read(path);
        assertEquals("numerical-policy-unattested",assertThrows(ReplayMismatchException.class,()->Format15ReplayRunner.replay(
                archive,ReplayLevel.SCALAR_INFERENCE,input.canonicalHash(),input.request().parameterHash())).getMessage());
    }
}

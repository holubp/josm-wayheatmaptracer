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
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;

class V022BudgetedFrontierParityTest {
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

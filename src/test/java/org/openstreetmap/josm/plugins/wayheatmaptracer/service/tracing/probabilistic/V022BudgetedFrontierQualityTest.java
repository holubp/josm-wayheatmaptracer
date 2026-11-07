package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022BudgetedFrontierQualityTest {
    @Test void dedicatedWorkflowRunsOnlyPinnedPublicV6GateAndAlwaysKeepsEvidence()throws Exception {
        var workflow=Files.readString(Path.of(".github/workflows/wide-budgeted-frontier-v6-retention.yml"));
        assertTrue(workflow.contains("workflow_dispatch:"));
        assertTrue(workflow.contains("branches: [\"codex/drasov-ab-rc16-integration-20261107\"]"));
        assertTrue(workflow.contains("v022WideBudgetedFrontierRetention -PvalidationProfile=public"));
        assertTrue(workflow.contains("timeout 4800s"));assertTrue(workflow.contains("--max-workers=1"));
        assertTrue(workflow.contains("build/reports/wide-budgeted-frontier-v6-retention/"));
        assertTrue(workflow.contains("build/reports/wide-budgeted-frontier-v6-source-evidence/"));
        assertTrue(workflow.contains("WideBudgetedFrontierRetentionMain"));
        assertTrue(workflow.contains("src/test/resources/public-wide-compact-v1.properties"));
        assertTrue(workflow.contains("head == os.environ['GITHUB_SHA']"));
        assertTrue(workflow.contains("host / 'host-runtime.json'"));assertTrue(workflow.contains("if: always()"));
        assertFalse(workflow.contains("B_ROOT"));assertFalse(workflow.contains("private-drasov"));
        assertFalse(workflow.contains("v022WideFrontierRetention -P"));assertFalse(workflow.contains("pull_request:"));
    }
    @Test void publicFirstGraphGeometryAndBranchesRemainExactAcrossSupportedAndCensoredScenes() {
        for(String scene:List.of("split-rejoin","separated-corridors","weak-bend","hairpin","missing","censored","protected-endpoints")) {
            var base=V022BudgetedFrontierCompletionTest.fixture(false);
            var sampled=new ArrayList<ProbabilisticProfile>();var dense=new ArrayList<InferenceProfile>();
            var lattices=new ArrayList<ProbabilisticStateLattice>();
            for(int row=0;row<5;row++) {
                var p=base.sampled().get(row);boolean fixed=row==0||row==4;
                boolean missing=scene.equals("missing")&&row==2,censored=scene.equals("censored")&&row==2;
                double center=scene.equals("weak-bend")?1.5*StrictMath.sin(row*StrictMath.PI/4):0;
                var modes=new ArrayList<ProbabilisticProfile.Mode>();
                double[] centers=scene.equals("separated-corridors")||scene.equals("split-rejoin")&&!fixed?new double[]{-2,2}:new double[]{center};
                if(!missing&&!censored)for(double at:centers)modes.add(new ProbabilisticProfile.Mode("mode"+at,"synthetic",at,at,.5,.8,.8,List.of(at),List.of(at),false));
                var anchor=scene.equals("hairpin")?List.of(new MetricPoint(0,0),new MetricPoint(2,0),new MetricPoint(3,1),new MetricPoint(2,2),new MetricPoint(0,2)).get(row):new MetricPoint(row*2,0);
                double chainage=sampled.isEmpty()?0:sampled.get(row-1).chainageMeters()+StrictMath.hypot(
                    anchor.xMeters()-sampled.get(row-1).anchor().xMeters(),anchor.yMeters()-sampled.get(row-1).anchor().yMeters());
                var next=new ProbabilisticProfile(row,chainage,anchor,p.normalUnit(),-4,4,1,true,p.noiseFloor(),p.samples(),modes,
                    censored?List.of(new ProbabilisticProfile.CensoredMode("clipped","synthetic",ProbabilisticProfile.CensorSide.RIGHT,4,.8,true)):List.of(),
                    fixed?OptionalDouble.of(0):OptionalDouble.empty(),p.orientationSupport());
                var cells=fixed?List.of(new LateralStateCell(0,.5,true,true,"fixed")):base.dense().get(row).cells();
                double[] costs=cells.stream().mapToDouble(c->missing||censored?0:Arrays.stream(centers).map(at->StrictMath.pow(c.offsetMeters()-at,2)).min().orElseThrow()*(scene.equals("weak-bend")?.02:1)).toArray();
                sampled.add(next);dense.add(new InferenceProfile(chainage,anchor,p.normalUnit(),cells,costs,List.of(),0,
                    missing?ObservationOwnership.INFERRED_GAP:censored?ObservationOwnership.CORE_CENSORED:ObservationOwnership.DIRECT_TWO_SIDED,missing));
                lattices.add(new ProbabilisticStateLattice(cells,base.lattices().get(row).components(),.5,false));
            }
            var f=new V022BudgetedFrontierCompletionTest.Fixture(sampled,dense,lattices);
            var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var firstProfiles=new ArrayList<InferenceProfile>();
                for(int row=0;row<5;row++)firstProfiles.add(AdaptiveSolverView.initial(sampled.get(row),dense.get(row),
                    lattices.get(row).components(),Double.NaN,CancellationProbe.NONE,owner,BPolicy.INVENTORY_FRONTIER_V5).profile());
                var first=new ProbabilisticInference().solve(firstProfiles,V022BudgetedFrontierCompletionTest.DATA,TraceBudgets.defaults(),
                    null,CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4);
                var actual=V022BudgetedFrontierCompletionTest.run(f,BPolicy.INVENTORY_FRONTIER_BUDGETED_V6,
                    first.evaluatedTransitions()+1,owner,CancellationProbe.NONE,AdaptiveBObserver.NONE);
                assertEquals(first.rawPaths(),actual.inference().rawPaths(),scene);
                assertEquals(first.distinctPaths(),actual.inference().distinctPaths(),scene);
                assertEquals(first.logPartition(),actual.inference().logPartition(),scene);
                assertFalse(actual.inference().posteriorUsable(),scene);
                for(var path:actual.inference().rawPaths()) {
                    assertEquals(sampled.get(0).anchor(),path.points().get(0),scene);
                    assertEquals(sampled.get(4).anchor(),path.points().get(4),scene);
                }
                assertEquals(0,actual.secondScores(),scene);
            }
            assertEquals(0,ledger.currentBytes(),scene);
        }
    }
    @Test void explicitRunnerBindsAllSixUnchangedScenesAndHasNoCaseOmission()throws Exception {
        var properties=new Properties();properties.load(new StringReader(WideFrontierRetentionMain.frozenManifest()));
        var bindings=WideBudgetedFrontierRetentionMain.preflight(properties);
        assertEquals(WideFrontierRetentionMain.IDS,bindings.stream().map(b->b.scene().id()).toList());
        for(var b:bindings) {
            assertEquals(BPolicy.INVENTORY_FRONTIER_BUDGETED_V6,BPolicy.recorded(b.scene().input().request()));
            assertEquals(properties.getProperty(b.scene().id()),b.frozenV4Hash());
            WideBudgetedFrontierRetentionMain.requirePolicyOnlyScene(WideCompactScenes.scene(b.scene().id()),b.scene());
        }
        assertThrows(IllegalArgumentException.class,()->WideBudgetedFrontierRetentionMain.invocation(new String[]{"--budgeted-frontier-retention","out","--skip=diffuse-three-strand"}));
        properties.remove("diffuse-three-strand");
        assertThrows(AssertionError.class,()->WideBudgetedFrontierRetentionMain.preflight(properties));
        assertTrue(Files.readString(Path.of("build.gradle.kts")).contains("v022WideBudgetedFrontierRetention"));
    }
    @Test void strictBudgetPreservesNativeKOrderingBranchesObjectiveAndConditionalDiversity() {
        var f=V022BudgetedFrontierCompletionTest.fixture(false);
        var ledger=org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var complete=V022BudgetedFrontierCompletionTest.run(f,BPolicy.INVENTORY_FRONTIER_V5,128000000,owner,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,AdaptiveBObserver.NONE);
            var fallback=V022BudgetedFrontierCompletionTest.run(f,BPolicy.INVENTORY_FRONTIER_BUDGETED_V6,complete.firstScores()+1,owner,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,AdaptiveBObserver.NONE);
            assertTrue(fallback.inference().alternativeSearchTruncated());
            assertFalse(fallback.inference().posteriorUsable());
            assertEquals(32,fallback.inference().rawPaths().size());
            assertEquals(0,fallback.refinements());assertEquals(0,fallback.secondScores());
            assertEquals(complete.firstScores(),fallback.inference().evaluatedTransitions());
        }
        assertEquals(0,ledger.currentBytes());
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

/** Unchanged public strong scenes must actually execute the successful native V5 compact path. */
class V022SourceFrontierCompactQualityTest {
    private static WideCompactScenes.Scene currentScene(String id) { return WideCompactScenes.scene(id,BPolicy.INVENTORY_FRONTIER_V5); }

    @Test void typedV5ScenesPreserveOriginalRasterSourceAuthorityAndBudgets() {
        for (String id : List.of("thin-hairpin", "remote-drift", "remote-drift-reversed")) {
            var historical = WideCompactScenes.scene(id); var current = currentScene(id);
            assertEquals(BPolicy.INVENTORY_PREP_V4, BPolicy.recorded(historical.input().request()));
            assertEquals(BPolicy.INVENTORY_FRONTIER_V5, BPolicy.recorded(current.input().request()));
            assertNotEquals(historical.input().canonicalHash(), current.input().canonicalHash());
            assertEquals(historical.input().evidence().canonicalHash(), current.input().evidence().canonicalHash());
            assertEquals(historical.input().network().canonicalHash(), current.input().network().canonicalHash());
            assertEquals(historical.input().request().profileChainage(), current.input().request().profileChainage());
            assertEquals(historical.analytic(), current.analytic());
            assertEquals(TraceBudgets.defaults(), current.input().request().budgets());
            assertEquals(25, current.input().request().permissions().ordinaryRadiusMeters());
        }
    }

    @Test void nativeCompactHairpinDriftAndReversalPassTheUnchangedStrongAssessor() throws Exception {
        var root=Path.of("build/reports/source-frontier-compact-quality").resolve(UUID.randomUUID().toString());
        Files.createDirectories(root);
        System.out.println("V5 public compact evidence: "+root.toAbsolutePath());
        List<MetricPoint> forward=null;
        for(String id:List.of("thin-hairpin","remote-drift","remote-drift-reversed")) {
            var scene=currentScene(id);var out=root.resolve(id);Files.createDirectories(out);
            assertEquals(BPolicy.INVENTORY_FRONTIER_V5,BPolicy.recorded(scene.input().request()));
            assertEquals(TraceBudgets.defaults(),scene.input().request().budgets());
            var candidate=WideCompactQualityMain.execute(scene,scene.input(),false,true,out.resolve("candidate"),BPolicy.INVENTORY_FRONTIER_V5);
            WideCompactQualityMain.candidateProof(candidate,scene,BPolicy.INVENTORY_FRONTIER_V5);
            assertTrue(candidate.pipeline().result().inference().workAccounting().orElseThrow().hasPolicy(BPolicy.FRONTIER_COMPACT_VERSION));
            assertFalse(candidate.capture().bound.admitted());
            assertEquals(0,candidate.capture().closedBytes);
            var reference=WideCompactRetentionMain.executeReference(scene,out.resolve("reference"),BPolicy.INVENTORY_FRONTIER_V5);
            var backend=WideCompactRetentionMain.referenceBackend(scene,reference);
            var referenceInput=reference.pipeline().result().inference().workAccounting().orElseThrow().hasPolicy(BQualityProbe.ORACLE_VERSION)
                    ?scene.input():WideCompactQualityMain.historical(scene.input());
            var pair=WideCompactRetentionMain.measurePair(scene,referenceInput,reference,backend,scene.input(),candidate,
                    new WideCompactRetentionFlow.SceneRecord(id),out.resolve("assessment"),BPolicy.INVENTORY_FRONTIER_V5);
            Files.writeString(out.resolve("retention-assessment.txt"),pair.input()+"\n"+pair.result()+"\nreference="
                    +pair.record().referenceFacts()+"\ncandidate="+pair.record().candidateFacts()+"\n",StandardOpenOption.CREATE_NEW);
            assertEquals(WideCompactRetentionAssessment.Verdict.RETENTION_PASS,pair.result().verdict(),
                    id+": "+pair.result()+"; "+pair.record().firstFailure());
            assertEquals(WideCompactRetentionAssessment.ReferenceDisposition.SUITABLE,pair.result().referenceDisposition());
            if(id.equals("remote-drift"))forward=candidate.pipeline().result().routes().get(0).hypothesis().points();
            if(id.equals("remote-drift-reversed")) {
                assertNotNull(forward);
                var reversed=WideCompactQualityMain.compare(candidate.pipeline().result().routes().get(0).hypothesis().points(),forward,0);
                Files.writeString(out.resolve("physical-reversal.txt"),reversed+"\n",StandardOpenOption.CREATE_NEW);
                assertTrue(reversed.p95()<=.25*WideCompactScenes.P);
                assertTrue(reversed.certifiedMaximum()<=.5*WideCompactScenes.P);
            }
        }
    }
}

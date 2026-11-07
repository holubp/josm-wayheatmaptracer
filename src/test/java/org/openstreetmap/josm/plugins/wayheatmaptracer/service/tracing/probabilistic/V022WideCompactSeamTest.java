package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;

/** Local bounded seam checks only; the six wide numerical cases belong to the dedicated CI task. */
class V022WideCompactSeamTest {
    @Test void namedFullOracleReusesNativePipelineExactlyAndCannotMasqueradeAsCandidate(@TempDir Path out)throws Exception {
        var scene=WideCompactScenes.tiny();
        var normal=WideCompactQualityMain.execute(scene,scene.input(),false,false,out.resolve("normal"));
        assertFalse(normal.pipeline().result().routes().isEmpty(),"Tiny equality must include genuine native finalization/assignments");
        var oracle=WideCompactQualityMain.execute(scene,scene.input(),true,false,out.resolve("oracle"));
        assertTrue(normal.pipeline().result().inference().workAccounting().orElseThrow().hasPolicy(BPolicy.INVENTORY_FULL_VERSION));
        assertTrue(oracle.pipeline().result().inference().workAccounting().orElseThrow().hasPolicy(BQualityProbe.ORACLE_VERSION));
        assertEquals(normal.pipeline().result().inference().hypotheses(),oracle.pipeline().result().inference().hypotheses());
        assertEquals(normal.pipeline().result().routes(),oracle.pipeline().result().routes());
        assertEquals(normal.capture().rawEnergies,oracle.capture().rawEnergies);
        for(int i=0;i<normal.capture().rawStates.size();i++)assertArrayEquals(normal.capture().rawStates.get(i),oracle.capture().rawStates.get(i));
        assertEquals(normal.pipeline().usage(),oracle.pipeline().usage());
        assertEquals(0,normal.capture().closedBytes);assertEquals(0,oracle.capture().closedBytes);
        assertThrows(AssertionError.class,()->WideCompactQualityMain.candidateProof(oracle,scene));
        assertFalse(BQualityProbe.fullOracle());
        assertThrows(IllegalArgumentException.class,()->{try(var ignored=BQualityProbe.install(true,new WideCompactQualityMain.Capture(false))){throw new IllegalArgumentException("synthetic body failure");}});
        assertFalse(BQualityProbe.fullOracle());
    }
    @Test void fixedPublicInputsMatchFrozenManifestWithoutPreparationOrInference()throws Exception {
        var expected=new java.util.Properties();try(var input=getClass().getResourceAsStream(WideCompactQualityMain.MANIFEST)){assertNotNull(input);expected.load(input);}
        assertEquals(WideCompactScenes.SPEC,expected.getProperty("spec"));
        for(String id:WideCompactScenes.IDS)assertEquals(expected.getProperty(id),WideCompactScenes.scene(id).input().canonicalHash(),id);
    }
    @Test void fullOraclePreservesNativeResourceRefusalWithoutBudgetOrOwnershipFabrication(@TempDir Path out)throws Exception {
        var scene=WideCompactScenes.tiny();var f=scene.input();var r=f.request();
        var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),new TraceBudgets(96,8_000_000,1,32,8),
                r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput());
        var input=new FrozenReplayInput(request,f.evidence(),f.network(),f.options(),25,null);
        var oracle=WideCompactQualityMain.execute(scene,input,true,false,out);
        assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT,oracle.pipeline().result().inference().status());
        assertTrue(oracle.pipeline().result().routes().isEmpty());assertEquals(1,oracle.pipeline().usage().transitions());
        var stage=oracle.pipeline().result().inference().workAccounting().orElseThrow().bStages().get(0);
        assertEquals(BQualityProbe.ORACLE_VERSION,stage.executionPolicy());assertEquals(1,stage.refusedScoreAttempts());
        assertEquals(0,oracle.capture().closedBytes);assertFalse(BQualityProbe.fullOracle());
    }
    @Test void scopeRejectsNestingAndForeignCloseAndRestoresAfterFailure()throws Exception {
        var sink=new WideCompactQualityMain.Capture(false);var first=BQualityProbe.install(true,sink);
        try {
            assertTrue(BQualityProbe.fullOracle());assertThrows(IllegalStateException.class,()->BQualityProbe.install(false,sink));
            var error=new AtomicReference<Throwable>();var thread=new Thread(()->{try{first.close();}catch(Throwable e){error.set(e);}});thread.start();thread.join();
            assertInstanceOf(IllegalStateException.class,error.get());assertTrue(BQualityProbe.fullOracle());
        }finally{first.close();}
        assertFalse(BQualityProbe.fullOracle());try(var second=BQualityProbe.install(false,sink)){first.close();assertFalse(BQualityProbe.fullOracle());}
        assertFalse(BQualityProbe.fullOracle());
    }
    @Test void independentOffsetLinesHaveCorrectArcWeightedQuantileAndMaximumCertificate() {
        double p=WideCompactScenes.P;var a=java.util.List.of(new MetricPoint(0,0),new MetricPoint(4*p,0));var b=java.util.List.of(new MetricPoint(0,.25*p),new MetricPoint(4*p,.25*p));
        var d=WideCompactQualityMain.compare(a,b,0);assertEquals(.25*p,d.p95(),1e-12);assertEquals(.25*p,d.maxSample(),1e-12);
        assertTrue(d.certifiedMaximum()>=.25*p&&d.certifiedMaximum()<=.5*p);assertTrue(d.maxGap()<=p/256+1e-12);
    }
}

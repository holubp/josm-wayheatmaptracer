package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

class V022InventoryPreparationReplayTest {
    private static FrozenReplayInput input(BPolicy policy,boolean compact) {
        var f=V022FrozenFrameTest.input(V022FrozenFrameTest.frame());var r=f.request();
        var exact=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),compact?RecoveryPermissions.disabled(1):r.permissions(),
                compact?new TraceBudgets(16,100_000,1,4,4):r.budgets(),r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),
                r.settingsHash(),BPolicy.visibleExactParameterHash(),r.samplerId(),compact?.5:r.configuredSampleStepMeters(),
                compact?new ProfileChainage(List.of(0.0,.5,1.0),.5):r.profileChainage(),r.evidenceResolution(),r.corridorInput());
        return new FrozenReplayInput(BPolicy.adaptiveRequest(exact,policy),f.evidence(),f.network(),f.options());
    }
    @Test void newPreparationIdentityPreservesCapturedV3AndExactNumericalOutputs(@TempDir Path directory)throws Exception {
        var prior=input(BPolicy.DENSE_ADMITTED_V3,false);
        assertEquals("c0698c5ee1a90313e9054ffe4ab47b2f259d79d451445642a85847ea649dcaae",prior.request().parameterHash());
        assertEquals("68d3b706f28674e60cb45646be640e4d83d1c14200c93987b4c0a42dcceb5fda",BPolicy.adaptiveParameterHash(BPolicy.INVENTORY_PREP_V4));
        var old=Format15ReplayRunner.replay(prior,ReplayLevel.SCALAR_INFERENCE,TrackerMode.PROBABILISTIC);
        for(boolean compact:new boolean[]{false,true}) {
            var requested=input(BPolicy.INVENTORY_PREP_V4,compact);var frozen=FrozenReplayCodec.decode(FrozenReplayCodec.encode(requested));
            assertEquals("7b3b1d6108998233eae015e4bcf398376a9d7a37f39393a085178f39ae97aeb6",frozen.request().parameterHash());
            assertEquals(BPolicy.INVENTORY_PREP_V4,BPolicy.recorded(frozen.request()));assertNotEquals(prior.request().parameterHash(),frozen.request().parameterHash());
            Format15Bundle bundle;ModernDiagnosticCounters.begin();
            try {
                bundle=Format15ProductionBundleFactory.createWithExpectedScalarOutput("inventory-preparation-test",frozen,TrackerMode.PROBABILISTIC);
                assertEquals(1,ModernDiagnosticCounters.snapshot().get("b.prep.inventoryVersion").longValue());
            }finally{ModernDiagnosticCounters.end();}
            var path=directory.resolve(compact?"compact.zip":"full.zip");Format15BundleWriter.write(bundle,path);
            var actual=Format15ReplayRunner.replay(Format15ArchiveReader.read(path),ReplayLevel.SCALAR_INFERENCE,frozen.canonicalHash(),frozen.request().parameterHash());
            assertTrue(actual.inference().workAccounting().orElseThrow().hasPolicy(compact?BPolicy.INVENTORY_COMPACT_VERSION:BPolicy.INVENTORY_FULL_VERSION));
            if(!compact){assertEquals(old.inference().hypotheses(),actual.inference().hypotheses());assertEquals(old.inference().status(),actual.inference().status());}
            else{assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT,actual.inference().status());assertEquals(1,actual.inference().evaluatedTransitions());}
        }
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class CorridorExecutionArtifactsTest {
    @Test void completedAndNoHypothesisRunsExportActualBoundWork(@TempDir Path directory)throws Exception{
        for(boolean signal:new boolean[]{true,false}){
            var f=CorridorPolicyFixture.straight(signal,false,Set.of());
            var run=new CorridorEngineAdapter("native").traceWithUsage(f.request(),f.evidence(),f.network(),CancellationProbe.NONE);
            var receipt=run.corridorReceipt().orElseThrow();var input=new FrozenReplayInput(f.request(),f.evidence(),f.network(),f.options());
            var base=Format15ProductionBundleFactory.create("public-test",input);
            var attached=CorridorExecutionArtifacts.attach(base,input,run.result(),receipt);
            assertEquals(base.artifacts().get("frozen-input.bin").sha256(),attached.artifacts().get("frozen-input.bin").sha256());
            Path target=directory.resolve("receipt-"+signal+".zip");Format15BundleWriter.write(attached,target);
            var artifact=Format15ArchiveReader.read(target).artifact("corridor-execution.csv").orElseThrow();
            String csv=new String(artifact.bytes(),StandardCharsets.UTF_8);
            assertTrue(csv.contains(receipt.resultHash()));assertTrue(csv.contains(receipt.requestHash()));
            assertTrue(csv.contains("UNAVAILABLE"));assertEquals(attached.artifacts().get("corridor-execution.csv").sha256(),artifact.sha256());
            var stale=CorridorPolicyFixture.straight(!signal,false,Set.of());
            assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attach(base,
                new FrozenReplayInput(stale.request(),stale.evidence(),stale.network(),stale.options()),run.result(),receipt));
            var compatibility=new TraceEngineRun(run.result(),run.usage());
            assertTrue(compatibility.corridorReceipt().isEmpty());assertSame(run.result(),compatibility.result());
            assertEquals(ScalarReplayFingerprint.sha256(run.result()),ScalarReplayFingerprint.sha256(compatibility.result()));
            assertSame(receipt,run.withPeakAdditionalRetainedBytes(10).corridorReceipt().orElseThrow());
            var pipelineCompatibility=new ModernTracePipeline.PipelineRun(new ModernTracePipeline.Result(run.result(),java.util.List.of()),run.usage());
            assertTrue(pipelineCompatibility.corridorReceipt().isEmpty());
        }
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class CorridorExecutionArtifactsTest {
    private static final class IntervalCases {
        static final IntervalCase A=caseUnchecked(0),B=caseUnchecked(1);
        private static IntervalCase caseUnchecked(int gray){try{return intervalCase(gray);}catch(Exception failure){throw new AssertionError(failure);}}
    }
    @Test void actualIntervalPayloadCannotReceiveAnotherValidNativeBatch()throws Exception{
        var a=IntervalCases.A;var b=IntervalCases.B;
        assertTrue(a.bundle().artifacts().containsKey(FrozenIntervalReplayCodec.ARTIFACT));
        assertTrue(b.bundle().artifacts().containsKey(FrozenIntervalReplayCodec.ARTIFACT));
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attachIntervals(a.bundle(),b.batch()),
            "Valid native batch/receipts must match the actual destination shared payload and authority");
    }
    @Test void actualIntervalAttachmentPreservesNativeBytesAndRejectsHeaderOrOrderCorruption(@TempDir Path directory)throws Exception{
        var a=IntervalCases.A;var attached=CorridorExecutionArtifacts.attachIntervals(a.bundle(),a.batch());
        for(var name:a.bundle().artifactNames())assertArrayEquals(a.bundle().artifact(name).bytes(),attached.artifact(name).bytes());
        var path=directory.resolve("native-interval-a.zip");Format15BundleWriter.write(attached,path);
        var csv=Format15ArchiveReader.read(path).artifact("corridor-execution.csv").orElseThrow();
        assertEquals(attached.artifact("corridor-execution.csv").sha256(),csv.sha256());
        assertTrue(new String(csv.bytes(),StandardCharsets.UTF_8).contains("DIAGNOSTIC_ONLY"));
        assertTrue(new String(csv.bytes(),StandardCharsets.UTF_8).contains("VALIDATED"));
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attachIntervals(
            new Format15Bundle(a.bundle().buildIdentity(),"f".repeat(64),a.bundle().parameterHash(),a.bundle().artifacts()),a.batch()));
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attachIntervals(
            new Format15Bundle(a.bundle().buildIdentity(),a.bundle().sourceIdentityHash(),"e".repeat(64),a.bundle().artifacts()),a.batch()));
        String index=new String(a.bundle().artifact(Format15ProductionBundleFactory.INTERVAL_INDEX_ARTIFACT).bytes(),StandardCharsets.UTF_8);
        String wrong=index.replaceFirst("\\\"intervalIndex\\\":0","\"intervalIndex\":1");assertNotEquals(index,wrong);
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attachIntervals(replace(a.bundle(),
            Format15Artifact.text(Format15ProductionBundleFactory.INTERVAL_INDEX_ARTIFACT,wrong)),a.batch()));
        var reordered=swapFirstTwoRequests(a.bundle().artifact(FrozenIntervalReplayCodec.ARTIFACT).bytes(),a.batch());
        assertDoesNotThrow(()->FrozenIntervalReplayCodec.decode(reordered),"Negative has a valid bounded codec, but wrong original request order");
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attachIntervals(replace(a.bundle(),
            Format15Artifact.binary(FrozenIntervalReplayCodec.ARTIFACT,reordered)),a.batch()));
        var nativeRuns=a.batch().runs().stream().map(run->new IntervalTraceBatch.IntervalRun(run.interval(),run.request(),run.result(),run.usage())).toList();
        var withoutA=new IntervalTraceBatch(a.batch().fullRequest(),a.batch().evidence(),a.batch().network(),a.batch().partition(),nativeRuns,a.batch().options(),a.batch().authoritySpecification());
        assertSame(a.bundle(),CorridorExecutionArtifacts.attachIntervals(a.bundle(),withoutA),"No A runs retain the existing no-op byte/object path");
    }
    @Test void genuinelyUnavailableOptionalIntervalPayloadIsNeverLabeledNativeVerified(){
        var a=IntervalCases.A;var artifacts=new LinkedHashMap<>(a.bundle().artifacts());
        var payload=FrozenIntervalReplayCodec.decode(artifacts.remove(FrozenIntervalReplayCodec.ARTIFACT).bytes());
        artifacts.remove(IntervalFinalOutputComponentsCodec.ARTIFACT);
        artifacts.put("private/interval-output-components-summary.json",Format15Artifact.text(
            "private/interval-output-components-summary.json","{\"schema\":1,\"status\":\"UNAVAILABLE\",\"intervals\":[]}\n"));
        String index=new String(artifacts.get(Format15ProductionBundleFactory.INTERVAL_INDEX_ARTIFACT).bytes(),StandardCharsets.UTF_8)
            .replace("\"sharedInputHash\":\""+payload.sharedInputHash()+"\"","\"sharedInputHash\":null")
            .replace("\"STRICT_INTERVAL_PRODUCTION\":true","\"STRICT_INTERVAL_PRODUCTION\":false");
        artifacts.put(Format15ProductionBundleFactory.INTERVAL_INDEX_ARTIFACT,Format15Artifact.text(Format15ProductionBundleFactory.INTERVAL_INDEX_ARTIFACT,index));
        var optional=new Format15Bundle(a.bundle().buildIdentity(),a.bundle().sourceIdentityHash(),a.bundle().parameterHash(),artifacts);
        String csv=new String(CorridorExecutionArtifacts.attachIntervals(optional,a.batch()).artifact("corridor-execution.csv").bytes(),StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"native_input_binding\",\"UNAVAILABLE\""));assertTrue(csv.contains("\"native_result_binding\",\"UNAVAILABLE\""));
        assertTrue(csv.contains("DIAGNOSTIC_ONLY"));assertFalse(csv.contains("VALIDATED"));
    }
    private static byte[] swapFirstTwoRequests(byte[] bytes,IntervalTraceBatch batch)throws Exception{
        var first=requestBlock(batch.runs().get(0).request());var second=requestBlock(batch.runs().get(1).request());
        int a=indexOf(bytes,first),b=indexOf(bytes,second);assertTrue(a>=0&&b>a+first.length);
        var out=new java.io.ByteArrayOutputStream();out.write(bytes,0,a);out.write(second);
        out.write(bytes,a+first.length,b-a-first.length);out.write(first);out.write(bytes,b+second.length,bytes.length-b-second.length);
        return out.toByteArray();
    }
    private static byte[] requestBlock(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest request){
        byte[] encoded=FrozenReplayCodec.encodeRequestOnly(request);
        return java.nio.ByteBuffer.allocate(4+encoded.length+8).putInt(encoded.length).put(encoded).putDouble(request.profileChainage().sourceOriginGroundMeters()).array();
    }
    private static int indexOf(byte[] whole,byte[] part){
        outer:for(int i=0;i<=whole.length-part.length;i++){for(int j=0;j<part.length;j++)if(whole[i+j]!=part[j])continue outer;return i;}return -1;
    }
    private static Format15Bundle replace(Format15Bundle bundle,Format15Artifact artifact){
        var artifacts=new LinkedHashMap<>(bundle.artifacts());artifacts.put(artifact.name(),artifact);
        return new Format15Bundle(bundle.buildIdentity(),bundle.sourceIdentityHash(),bundle.parameterHash(),artifacts);
    }
    private record IntervalCase(IntervalTraceBatch batch,Format15Bundle bundle){ }
    private static IntervalCase intervalCase(int gray)throws Exception{
        var f=org.openstreetmap.josm.plugins.wayheatmaptracer.util.SharedNodeWorkflowFixture.create(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE,true);
        var r=f.raster();int[] pixels=new int[r.width()*r.height()];Arrays.fill(pixels,0xff000000|gray<<16|gray<<8|gray);
        var raster=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.VisibleRaster(
            r.width(),r.height(),pixels,r.minimumEast(),r.minimumNorth(),r.maximumEast(),r.maximumNorth(),
            r.projectionUnitsPerViewPixel(),r.groundMetersPerViewPixel(),r.nativePitchMeters(),r.sourceIdentity(),r.projectionCode());
        var fixture=new org.openstreetmap.josm.plugins.wayheatmaptracer.util.SharedNodeWorkflowFixture(
            f.data(),f.way(),f.nodes(),f.selection(),f.config(),raster,f.initial());
        var historical=fixture.compute(Map.of()).intervalBatch();assertNotNull(historical);
        var full=historical.fullRequest();var fixed=new TreeSet<Integer>();
        for(var island:historical.partition().fixedIslands())for(int i=island.range().firstIndex();i<=island.range().lastIndex();i++)fixed.add(i);
        var input=full.corridorInput().orElseThrow().withPolicy(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorTraceInput.ExecutionPolicy.R3_OWNED_BOUNDED_BRIDGE_V1,fixed);
        full=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest(full.selectedWayKey(),full.selectedRange(),full.engine(),full.geometryMode(),
            full.permissions(),full.budgets(),full.evidenceSnapshotId(),full.evidenceContentHash(),full.networkSnapshotId(),full.networkContentHash(),
            full.settingsHash(),full.parameterHash(),full.samplerId(),full.configuredSampleStepMeters(),full.profileChainage(),full.evidenceResolution(),Optional.of(input),full.disconnectionSource());
        var runs=new ArrayList<IntervalTraceBatch.IntervalRun>();var factory=new IntervalTraceRequestFactory();
        for(var interval:historical.partition().slideIntervals()){
            var request=factory.createDetached(full,historical.evidence(),historical.network(),interval,full.budgets());
            var actual=new ModernTracePipeline(new CorridorEngineAdapter(historical.options().fieldName())).runWithUsage(
                request,historical.evidence(),historical.network(),historical.options(),CancellationProbe.NONE);
            runs.add(new IntervalTraceBatch.IntervalRun(interval,request,actual.result(),actual.usage(),actual.corridorReceipt()));
        }
        assertTrue(runs.size()>1);var batch=new IntervalTraceBatch(full,historical.evidence(),historical.network(),historical.partition(),runs,historical.options(),historical.authoritySpecification());
        var assessment=new FixedIntervalEditPlanComposer().compose(batch,Map.of());
        var bundle=Format15ProductionBundleFactory.createLiveIntervals("native-a-intervals",batch,assessment,Map.of(),
            new Format15ProductionBundleFactory.VisibleLayerSourceReceipt(null,null,Format15Safety.sha256(r.sourceIdentity())),
            Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW,null,null);
        return new IntervalCase(batch,bundle);
    }
    @Test void validReceiptAndInputCannotBeAttachedToAnotherValidFrozenBundle(){
        var a=CorridorPolicyFixture.straight(true,false,Set.of());var b=CorridorPolicyFixture.straight(false,false,Set.of());
        var runA=new CorridorEngineAdapter("native").traceWithUsage(a.request(),a.evidence(),a.network(),CancellationProbe.NONE);
        var runB=new CorridorEngineAdapter("native").traceWithUsage(b.request(),b.evidence(),b.network(),CancellationProbe.NONE);
        var inputA=new FrozenReplayInput(a.request(),a.evidence(),a.network(),a.options());
        var inputB=new FrozenReplayInput(b.request(),b.evidence(),b.network(),b.options());
        var base=Format15ProductionBundleFactory.create("valid-b",inputB);
        var replay=new Format15ReplayRunner.Result(org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel.SCALAR_INFERENCE,
            inputB.request().engine(),inputB.request().engine(),runB.result(),java.util.List.of(),inputB.canonicalHash());
        var artifacts=new java.util.LinkedHashMap<>(base.artifacts());
        var expected=ScalarReplayExpectation.capture(base.buildIdentity(),inputB,replay);
        artifacts.put(ScalarReplayExpectation.ARTIFACT_NAME,Format15Artifact.binary(ScalarReplayExpectation.ARTIFACT_NAME,expected.bytes()));
        var bundleB=CorridorExecutionArtifacts.attach(new Format15Bundle(base.buildIdentity(),base.sourceIdentityHash(),base.parameterHash(),artifacts),
            inputB,runB.result(),runB.corridorReceipt().orElseThrow());
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attach(
            bundleB,inputA,runA.result(),runA.corridorReceipt().orElseThrow()),"A matching native input/receipt pair still needs the destination bundle identity");
    }
    @Test void singleRunRejectsWrongHeaderFrozenMetadataAndAlreadyPresentNativeResult(){
        var f=CorridorPolicyFixture.straight(true,false,Set.of());
        var run=new CorridorEngineAdapter("native").traceWithUsage(f.request(),f.evidence(),f.network(),CancellationProbe.NONE);
        var input=new FrozenReplayInput(f.request(),f.evidence(),f.network(),f.options());var base=Format15ProductionBundleFactory.create("native",input);
        var receipt=run.corridorReceipt().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attach(
            new Format15Bundle(base.buildIdentity(),"f".repeat(64),base.parameterHash(),base.artifacts()),input,run.result(),receipt));
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attach(
            new Format15Bundle(base.buildIdentity(),base.sourceIdentityHash(),"e".repeat(64),base.artifacts()),input,run.result(),receipt));
        var other=CorridorPolicyFixture.straight(false,false,Set.of());
        var otherInput=new FrozenReplayInput(other.request(),other.evidence(),other.network(),other.options());
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attach(replace(base,
            Format15Artifact.binary("frozen-input.bin",FrozenReplayCodec.encode(otherInput))),input,run.result(),receipt));
        String metadata=new String(base.artifact("frozen-input-identities.json").bytes(),StandardCharsets.UTF_8).replace(input.canonicalHash(),"f".repeat(64));
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attach(replace(base,
            Format15Artifact.text("frozen-input-identities.json",metadata)),input,run.result(),receipt));
        var replay=new Format15ReplayRunner.Result(org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel.SCALAR_INFERENCE,
            input.request().engine(),input.request().engine(),run.result(),List.of(),input.canonicalHash());
        var expectation=ScalarReplayExpectation.capture(base.buildIdentity(),input,replay);
        var valid=replace(base,Format15Artifact.binary(ScalarReplayExpectation.ARTIFACT_NAME,expectation.bytes()));
        assertDoesNotThrow(()->CorridorExecutionArtifacts.attach(valid,input,run.result(),receipt));
        String nativeOutput=new String(expectation.bytes(),StandardCharsets.UTF_8)
            .replace(ScalarReplayFingerprint.sha256(run.result()),"f".repeat(64));
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attach(replace(base,
            Format15Artifact.text(ScalarReplayExpectation.ARTIFACT_NAME,nativeOutput)),input,run.result(),receipt),
            "Valid input binding alone cannot overlook a contradicting already-present native output");
    }
    @Test void absentOptionalFinalComponentsAndCancellationRemainExplicitlyDiagnosticOnly(){
        var f=CorridorPolicyFixture.straight(false,false,Set.of());
        var actual=new ModernTracePipeline(new CorridorEngineAdapter("native")).runWithUsage(f.request(),f.evidence(),f.network(),f.options(),CancellationProbe.NONE);
        var input=new FrozenReplayInput(f.request(),f.evidence(),f.network(),f.options());
        var nativeBundle=Format15ProductionBundleFactory.createLive("native-final",input,actual.result(),"failed","synthetic",-1,null,false,false);
        assertTrue(nativeBundle.artifacts().containsKey(FinalOutputComponentsCodec.ARTIFACT));
        var verified=CorridorExecutionArtifacts.attach(nativeBundle,input,actual.result().inference(),actual.corridorReceipt().orElseThrow());
        assertTrue(new String(verified.artifact("corridor-execution.csv").bytes(),StandardCharsets.UTF_8).contains("\"native_result_binding\",\"VALIDATED\""));
        var artifacts=new LinkedHashMap<>(nativeBundle.artifacts());artifacts.remove(FinalOutputComponentsCodec.ARTIFACT);
        var contradictory=new Format15Bundle(nativeBundle.buildIdentity(),nativeBundle.sourceIdentityHash(),nativeBundle.parameterHash(),artifacts);
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attach(contradictory,input,
            actual.result().inference(),actual.corridorReceipt().orElseThrow()),"A present AVAILABLE summary with absent bytes is a mismatch, not a genuine optional omission");
        artifacts.put("private/final-output-components-summary.json",Format15Artifact.text(
            "private/final-output-components-summary.json","{\"status\":\"UNAVAILABLE_BUDGET\"}\n"));
        var omitted=new Format15Bundle(nativeBundle.buildIdentity(),nativeBundle.sourceIdentityHash(),nativeBundle.parameterHash(),artifacts);
        String csv=new String(CorridorExecutionArtifacts.attach(omitted,input,actual.result().inference(),actual.corridorReceipt().orElseThrow())
            .artifact("corridor-execution.csv").bytes(),StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"native_result_binding\",\"UNAVAILABLE\""));assertTrue(csv.contains("DIAGNOSTIC_ONLY"));
        String wrongEngine=new String(omitted.artifact(FinalReplayExpectation.ARTIFACT_NAME).bytes(),StandardCharsets.UTF_8)
            .replace("\"requestedEngine\":\"CORRIDOR_AWARE\"","\"requestedEngine\":\"PROBABILISTIC\"");
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attach(replace(omitted,
            Format15Artifact.text(FinalReplayExpectation.ARTIFACT_NAME,wrongEngine)),input,actual.result().inference(),actual.corridorReceipt().orElseThrow()),
            "Optional absence cannot hide a present native engine mismatch");
        byte[] corrupt=nativeBundle.artifact(FinalOutputComponentsCodec.ARTIFACT).bytes();corrupt[0]^=1;
        assertThrows(IllegalArgumentException.class,()->CorridorExecutionArtifacts.attach(replace(nativeBundle,
            Format15Artifact.binary(FinalOutputComponentsCodec.ARTIFACT,corrupt)),input,actual.result().inference(),actual.corridorReceipt().orElseThrow()));
        var cancelled=CorridorPolicyFixture.finalizationCancellation();
        var fallback=Format15ProductionBundleFactory.createUnavailableLive("native-cancel","cancelled","synthetic","current-attempt");
        var attached=CorridorExecutionArtifacts.attachCancellation(fallback,cancelled.diagnostics());
        assertFalse(attached.artifacts().containsKey("frozen-input.bin"));
        String cancellation=new String(attached.artifact("corridor-execution.csv").bytes(),StandardCharsets.UTF_8);
        assertTrue(cancellation.contains("DIAGNOSTIC_ONLY"));assertTrue(cancellation.contains("\"native_input_binding\",\"UNAVAILABLE\""));
        for(var name:fallback.artifactNames())assertArrayEquals(fallback.artifact(name).bytes(),attached.artifact(name).bytes());
    }
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

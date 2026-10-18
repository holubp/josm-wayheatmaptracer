package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;

class V022BObservationTrainRuntimeDiagnosticTest {
    @TempDir Path temp;
    @Test void ordinaryPartialPublicationRemainsChargedAndMakesTerminalTotalsConservative() throws Exception {
        for(boolean nearCap:List.of(false,true)) {
            var directory=Files.createDirectory(temp.resolve("ordinary-terminal-"+nearCap));var scratch=Files.createDirectory(temp.resolve("ordinary-partial-"+nearCap));
            var fields=Map.<String,Object>of("status","DUMMY_ORDINARY_RECEIPT");
            int length=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            var owner=new BObservationIdentificationTool.Writer[1];
            try(var writer=new BObservationIdentificationTool.Writer(scratch,64L*1024*1024,(path,payload)->{
                assertEquals(nearCap?BObservationTrainRuntimeDiagnosticTool.DISK_BYTES:length,owner[0].bytes,"ordinary reservation precedes I/O");
                byte[] encoded=(BIdentificationReceipt.canonical(payload)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Files.write(path.resolveSibling(path.getFileName()+".partial"),Arrays.copyOf(encoded,5),StandardOpenOption.CREATE_NEW);
                throw new java.io.IOException("INJECTED_ORDINARY_PARTIAL");
            })) {
                owner[0]=writer;
                if(nearCap)writer.bytes=BObservationTrainRuntimeDiagnosticTool.DISK_BYTES-length;
                long before=writer.bytes;assertThrows(java.io.IOException.class,()->writer.publish("ordinary.json",fields));
                assertEquals(before+length,writer.bytes,"full ordinary payload reserved despite partial failure");
                assertEquals(5,Files.size(scratch.resolve("ordinary.json.partial")));
                var aggregate=new BObservationIdentificationTool.DiagnosticAggregateWriter(directory,writer);
                if(nearCap) {
                    assertThrows(BObservationIdentificationTool.RunLimitException.class,()->aggregate.publish("status.json",Map.of("status","FAILED_NONQUALIFYING")));
                    assertFalse(Files.exists(directory.resolve("status.json")));assertFalse(Files.exists(directory.resolve("status.json.partial")));
                } else {
                    aggregate.publish("status.json",Map.of("status","FAILED_NONQUALIFYING"));
                    var status=BIdentificationReceipt.read(directory.resolve("status.json"));
                    assertEquals("CONSERVATIVE_RESERVED_BYTES",status.get("byteAccounting"));assertFalse(status.containsKey("totalOrdinaryAndTelemetryBytes"));
                    assertEquals(length,((Number)status.get("retainedOrdinaryReservationBytes")).intValue());
                    assertEquals(writer.bytes,((Number)status.get("totalChargedOrdinaryAndTelemetryBytes")).longValue());
                    assertTrue(Files.size(scratch.resolve("ordinary.json.partial"))+Files.size(directory.resolve("status.json"))<=writer.bytes);
                }
            }
        }
    }
    @Test void actualLazyReconstructionIncludesExecutedChildrenAndExcludesBothCallerIdleGaps() throws Exception {
        var clock=new AtomicLong();var telemetry=new BObservationRuntimeTelemetry(clock::incrementAndGet,s->{
            for(String phase:List.of("MAP_SOLVE","ROUTE_CHECK","PAIR_PROFILES","PAIR_CACHE_WRITE"))
                if(((Number)row(s,phase).get("active")).longValue()>0)clock.addAndGet(10_000);
        });
        BObservationIdentificationTool.runTrainFixture(temp.resolve("idle-fixture"),BObservationIdentificationTool.Controls.defaults(),
                BObservationIdentificationTool.TrainFixtureFault.NONE,telemetry,()->clock.addAndGet(1_000_000));
        var parent=row(telemetry.snapshot(),"RECONSTRUCTION");assertEquals(2L,parent.get("attempted"));assertEquals(2L,parent.get("succeeded"));
        assertEquals(0L,parent.get("failed"));assertEquals(0L,parent.get("active"));long children=0;
        for(String phase:List.of("MAP_SOLVE","ROUTE_CHECK","PAIR_PROFILES","PAIR_CACHE_WRITE"))children+=((Number)row(telemetry.snapshot(),phase).get("elapsedNanos")).longValue();
        assertTrue(((Number)parent.get("elapsedNanos")).longValue()>=children,"reconstruction includes geometry/cache children");
        assertTrue(((Number)parent.get("elapsedNanos")).longValue()<1_000_000,"caller idle excluded");
    }
    @Test void abandonedLazyReconstructionClosesFailedWithoutMapOrIdleTime() throws Exception {
        var clock=new AtomicLong();var telemetry=new BObservationRuntimeTelemetry(clock::incrementAndGet,s->{});
        assertThrows(CancellationException.class,()->BObservationIdentificationTool.runTrainFixture(temp.resolve("abandon-fixture"),
                BObservationIdentificationTool.Controls.defaults(),BObservationIdentificationTool.TrainFixtureFault.ABANDON_BEFORE_GEOMETRY,
                telemetry,()->clock.addAndGet(1_000_000)));
        var parent=row(telemetry.snapshot(),"RECONSTRUCTION");assertEquals(1L,parent.get("attempted"));assertEquals(0L,parent.get("succeeded"));
        assertEquals(1L,parent.get("failed"));assertEquals(0L,parent.get("active"));assertEquals(0L,row(telemetry.snapshot(),"MAP_SOLVE").get("attempted"));
        assertTrue(((Number)parent.get("elapsedNanos")).longValue()<1_000_000);
    }
    @Test void failedProgressPrefixIsRolledBackBeforeTruthfulTerminalPublication() throws Exception {
        var directory=Files.createDirectory(temp.resolve("partial-progress"));var scratch=Files.createDirectory(temp.resolve("partial-progress-scratch"));
        var io=new PartialAggregateIO("progress.jsonl",false);
        try(var writer=new BObservationIdentificationTool.Writer(scratch,64L*1024*1024)) {
            var aggregate=new BObservationIdentificationTool.DiagnosticAggregateWriter(directory,writer,io);
            aggregate.progress(Map.of("active",1));byte[] original=Files.readAllBytes(directory.resolve("progress.jsonl"));
            long before=writer.bytes;io.armed=true;
            io.duringFailure=n->{assertEquals(before+n,writer.bytes);assertEquals(before+n,aggregate.bytes);};
            assertThrows(java.io.IOException.class,()->aggregate.progress(Map.of("active",2)));
            assertArrayEquals(original,Files.readAllBytes(directory.resolve("progress.jsonl")));
            assertEquals(before,writer.bytes);assertEquals(before,aggregate.bytes);assertEquals(1,aggregate.snapshots);
            aggregate.publish("status.json",Map.of("status","FAILED_NONQUALIFYING"));
            var status=BIdentificationReceipt.read(directory.resolve("status.json"));
            assertEquals("EXACT_OWNED_PAYLOAD_BYTES",status.get("byteAccounting"));
            assertEquals(writer.bytes,Files.size(directory.resolve("progress.jsonl"))+Files.size(directory.resolve("status.json")));
            assertEquals(writer.bytes,((Number)status.get("totalOrdinaryAndTelemetryBytes")).longValue());
        }
    }
    @Test void failedBindingAndStatusPrefixesRemoveOnlyTheirOwnedPartialFiles() throws Exception {
        for(String name:List.of("binding.json","status.json")) {
            var directory=Files.createDirectory(temp.resolve(name));var scratch=Files.createDirectory(temp.resolve(name+"-scratch"));
            var io=new PartialAggregateIO(name+".partial",false);io.armed=true;
            try(var writer=new BObservationIdentificationTool.Writer(scratch,64L*1024*1024)) {
                var aggregate=new BObservationIdentificationTool.DiagnosticAggregateWriter(directory,writer,io);
                io.duringFailure=n->{assertEquals(n,writer.bytes,"global reservation precedes partial I/O");assertEquals(n,aggregate.bytes,"aggregate reservation precedes partial I/O");};
                assertThrows(java.io.IOException.class,()->aggregate.publish(name,Map.of("status","DUMMY")));
                assertFalse(Files.exists(directory.resolve(name)));assertFalse(Files.exists(directory.resolve(name+".partial")));
                assertEquals(0L,writer.bytes);assertEquals(0L,aggregate.bytes);assertEquals(0,aggregate.publications);
                aggregate.publish("status.json",Map.of("status","FAILED_NONQUALIFYING"));
                assertEquals(writer.bytes,Files.size(directory.resolve("status.json")));
            }
        }
        var directory=Files.createDirectory(temp.resolve("unowned"));var scratch=Files.createDirectory(temp.resolve("unowned-scratch"));
        var unowned=directory.resolve("binding.json.partial");Files.writeString(unowned,"UNRELATED");
        try(var writer=new BObservationIdentificationTool.Writer(scratch,64L*1024*1024)) {
            var aggregate=new BObservationIdentificationTool.DiagnosticAggregateWriter(directory,writer);
            assertThrows(FileAlreadyExistsException.class,()->aggregate.publish("binding.json",Map.of()));
            assertEquals("UNRELATED",Files.readString(unowned));assertEquals(0L,writer.bytes);
        }
    }
    @Test void retainedRollbackFailureStaysChargedAndNeverClaimsExactStoredTotalsOrBypassesCaps() throws Exception {
        for(String fault:List.of("progress.jsonl","binding.json.partial")) {
            var directory=Files.createDirectory(temp.resolve("retained-"+fault));var scratch=Files.createDirectory(temp.resolve("retained-scratch-"+fault));
            var io=new PartialAggregateIO(fault,true);io.armed=true;
            try(var writer=new BObservationIdentificationTool.Writer(scratch,64L*1024*1024)) {
                var aggregate=new BObservationIdentificationTool.DiagnosticAggregateWriter(directory,writer,io);
                assertThrows(java.io.IOException.class,()->{if(fault.startsWith("progress"))aggregate.progress(Map.of("active",1));
                    else aggregate.publish("binding.json",Map.of("schema",1));});
                assertTrue(writer.bytes>=5);assertEquals(writer.bytes,aggregate.bytes);assertEquals(0,aggregate.publications);
                aggregate.publish("status.json",Map.of("status","FAILED_NONQUALIFYING"));
                var status=BIdentificationReceipt.read(directory.resolve("status.json"));
                assertEquals("CONSERVATIVE_RESERVED_BYTES",status.get("byteAccounting"));
                assertFalse(status.containsKey("totalOrdinaryAndTelemetryBytes"));assertFalse(status.containsKey("totalTelemetryBytes"));
                assertEquals(writer.bytes,((Number)status.get("totalChargedOrdinaryAndTelemetryBytes")).longValue());
                long stored;try(var paths=Files.list(directory)){stored=0;for(var p:paths.toList())stored+=Files.size(p);}
                assertTrue(stored<=writer.bytes);assertTrue(((Number)status.get("retainedTelemetryReservationBytes")).longValue()>=5);
                long statusSize=Files.size(directory.resolve("status.json"));writer.bytes=BObservationTrainRuntimeDiagnosticTool.DISK_BYTES;
                assertThrows(BObservationIdentificationTool.RunLimitException.class,()->aggregate.publish("binding.json",Map.of()));
                aggregate.bytes=BObservationTrainRuntimeDiagnosticTool.MAX_TELEMETRY_BYTES;writer.bytes=0;
                assertThrows(BObservationIdentificationTool.RunLimitException.class,()->aggregate.progress(Map.of()));
                assertEquals(statusSize,Files.size(directory.resolve("status.json")));
            }
        }
    }
    private static final class PartialAggregateIO implements BObservationIdentificationTool.AggregateIO {
        final String name;final boolean rollbackFails;boolean armed;java.util.function.IntConsumer duringFailure=n->{};
        PartialAggregateIO(String name,boolean rollbackFails){this.name=name;this.rollbackFails=rollbackFails;}
        public void write(Path path,java.nio.channels.FileChannel channel,byte[] bytes) throws java.io.IOException {
            if(armed&&path.getFileName().toString().equals(name)) {
                armed=false;duringFailure.accept(bytes.length);channel.write(java.nio.ByteBuffer.wrap(bytes,0,5));throw new java.io.IOException("INJECTED_PARTIAL_WRITE");
            }
            BObservationIdentificationTool.AggregateIO.super.write(path,channel,bytes);
        }
        public void restoreProgress(Path path,long length,boolean created) throws java.io.IOException {
            if(rollbackFails)throw new java.io.IOException("INJECTED_ROLLBACK_FAILURE");
            BObservationIdentificationTool.AggregateIO.super.restoreProgress(path,length,created);
        }
        public void deleteOwned(Path path) throws java.io.IOException {
            if(rollbackFails&&path.getFileName().toString().equals(name))throw new java.io.IOException("INJECTED_DELETE_FAILURE");
            BObservationIdentificationTool.AggregateIO.super.deleteOwned(path);
        }
    }
    @Test void returnedResourceLimitFailsItsOwnAndReconstructionPhasesWithoutRouteOrCacheWork() throws Exception {
        assertReturnedIncompleteMap(BObservationIdentificationTool.TrainFixtureFault.RETURNED_RESOURCE_LIMIT);
    }
    @Test void returnedNumericFailureFailsItsOwnAndReconstructionPhasesWithoutRouteOrCacheWork() throws Exception {
        assertReturnedIncompleteMap(BObservationIdentificationTool.TrainFixtureFault.RETURNED_NUMERIC_FAILURE);
    }
    private void assertReturnedIncompleteMap(BObservationIdentificationTool.TrainFixtureFault fault) throws Exception {
            var telemetry=new BObservationRuntimeTelemetry(new AtomicLong()::incrementAndGet,s->{});
            var output=temp.resolve(fault.name());
            assertThrows(BObservationIdentificationTool.RunLimitException.class,()->BObservationIdentificationTool.runTrainFixture(output,
                    BObservationIdentificationTool.Controls.defaults(),fault,telemetry));
            for(String phase:List.of("MAP_SOLVE","RECONSTRUCTION")) {
                var r=row(telemetry.snapshot(),phase);assertEquals(1L,r.get("attempted"));assertEquals(0L,r.get("succeeded"),phase);
                assertEquals(1L,r.get("failed"),phase);assertEquals(0L,r.get("active"));
            }
            assertEquals(17L,((Map<?,?>)telemetry.snapshot().get("work")).get("MAP_TRANSITIONS"));
            for(String phase:List.of("ROUTE_CHECK","PAIR_PROFILES","PAIR_CACHE_WRITE","COUPLED"))assertEquals(0L,row(telemetry.snapshot(),phase).get("attempted"));
            var failure=BIdentificationReceipt.read(output.resolve("failure.json"));assertEquals("NONE",failure.get("selectionAuthority"));
            assertEquals(false,failure.get("holdoutAuthority"));assertFalse(Files.exists(output.resolve("summary.json")));
    }
    @Test void pausedComputationIncludesItsChildrenButExcludesCallerIdleAndLaterClose() throws Exception {
        var clock=new AtomicLong();var telemetry=new BObservationRuntimeTelemetry(clock::get,s->{});
        var computation=telemetry.begin(BObservationRuntimeTelemetry.Phase.RECONSTRUCTION);
        clock.set(5);computation.pause();clock.set(1005);computation.resume();
        try(var child=telemetry.begin(BObservationRuntimeTelemetry.Phase.MAP_SOLVE)){clock.set(1012);child.succeeded();}
        clock.set(1015);computation.succeeded();computation.close();clock.set(9999);computation.close();
        assertEquals(15L,row(telemetry.snapshot(),"RECONSTRUCTION").get("elapsedNanos"));
        assertEquals(7L,row(telemetry.snapshot(),"MAP_SOLVE").get("elapsedNanos"));
        assertEquals(1L,row(telemetry.snapshot(),"RECONSTRUCTION").get("attempted"));
        assertEquals(1L,row(telemetry.snapshot(),"RECONSTRUCTION").get("succeeded"));
        assertEquals(0L,row(telemetry.snapshot(),"RECONSTRUCTION").get("active"));
    }
    @Test void clockStartsBeforeInitialSourceVerificationAndItsTimeCannotBeReset() {
        var clock=new AtomicLong();var controls=new BObservationIdentificationTool.Controls(null,64L*1024*1024,
                BObservationTrainRuntimeDiagnosticTool.WATCHDOG_NANOS,clock::get,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,()->{});
        var failure=assertThrows(BObservationIdentificationTool.RunLimitException.class,
                ()->BObservationIdentificationTool.diagnosticPreflight(controls,()->{
                    clock.set(BObservationTrainRuntimeDiagnosticTool.WATCHDOG_NANOS);return "DUMMY_METADATA_ONLY";
                }));
        assertEquals("TIME",failure.reason);
    }
    @Test void retainedCoarseStartShowsActiveWorkWhenNoTerminalStatusExists() throws Exception {
        var directory=Files.createDirectory(temp.resolve("cutoff-aggregate"));var scratch=Files.createDirectory(temp.resolve("cutoff-scratch"));
        try(var writer=new BObservationIdentificationTool.Writer(scratch,64L*1024*1024)) {
            var aggregate=new BObservationIdentificationTool.DiagnosticAggregateWriter(directory,writer);var clock=new AtomicLong();
            var telemetry=new BObservationRuntimeTelemetry(clock::incrementAndGet,aggregate::progress);
            try(var token=telemetry.begin(BObservationRuntimeTelemetry.Phase.MAP_SOLVE)) {
                var lines=Files.readAllLines(directory.resolve("progress.jsonl"));assertEquals(1,lines.size());
                var retained=BIdentificationReceipt.parse(lines.get(0).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(1,((Number)row(retained,"MAP_SOLVE").get("active")).intValue());
                assertEquals(0,((Number)row(retained,"MAP_SOLVE").get("succeeded")).intValue());
                assertEquals(0,((Number)row(retained,"MAP_SOLVE").get("failed")).intValue());
                assertFalse(Files.exists(directory.resolve("status.json")));
            }
        }
    }
    @Test void fixedRuntimeFreezeCanBeVerifiedAsMetadataWithoutAnyEvaluationAccess() throws Exception {
        var freeze=BObservationTrainRuntimeDiagnosticTool.verifyFreeze();freeze.verifyUnchanged();
        assertEquals(BIdentificationReceipt.sourceHash(),freeze.sourceHash);
        assertEquals(BIdentificationReceipt.hash(Files.readAllBytes(BObservationTrainRuntimeDiagnosticTool.PROTOCOL)),freeze.protocolHash);
    }
    @Test void aggregatePublicationIsBoundedSharesDiskAndLeavesStatusRoomAfterProgressFailure() throws Exception {
        var directory=Files.createDirectory(temp.resolve("aggregate"));var scratch=Files.createDirectory(temp.resolve("scratch"));
        try(var writer=new BObservationIdentificationTool.Writer(scratch,64L*1024*1024)) {
            var aggregate=new BObservationIdentificationTool.DiagnosticAggregateWriter(directory,writer);
            for(int i=0;i<BObservationTrainRuntimeDiagnosticTool.MAX_SNAPSHOTS;i++)aggregate.progress(Map.of("active",1));
            assertThrows(BObservationIdentificationTool.RunLimitException.class,()->aggregate.progress(Map.of("active",1)));
            aggregate.publish("status.json",Map.of("status","FAILED_NONQUALIFYING"));
            assertEquals(129,aggregate.publications);assertEquals(aggregate.bytes,writer.bytes);
            var status=BIdentificationReceipt.read(directory.resolve("status.json"));
            assertEquals(writer.bytes,((Number)status.get("totalOrdinaryAndTelemetryBytes")).longValue());
            assertEquals(aggregate.bytes,((Number)status.get("totalTelemetryBytes")).longValue());
            assertEquals(128,Files.readAllLines(directory.resolve("progress.jsonl")).size());
            assertThrows(IllegalArgumentException.class,()->aggregate.publish("frozen-candidate.json",Map.of()));
            assertThrows(FileAlreadyExistsException.class,()->aggregate.publish("status.json",Map.of()));
        }
    }
    @Test void byteLimitReservesOneTerminalStatusAndOrdinaryWritesUseTheSameCeiling() throws Exception {
        var directory=Files.createDirectory(temp.resolve("aggregate-limit"));var scratch=Files.createDirectory(temp.resolve("scratch-limit"));
        try(var writer=new BObservationIdentificationTool.Writer(scratch,64L*1024*1024)) {
            var aggregate=new BObservationIdentificationTool.DiagnosticAggregateWriter(directory,writer);
            aggregate.bytes=BObservationTrainRuntimeDiagnosticTool.MAX_TELEMETRY_BYTES-65_536;
            assertThrows(BObservationIdentificationTool.RunLimitException.class,()->aggregate.progress(Map.of("active",1)));
            aggregate.publish("status.json",Map.of("status","FAILED_NONQUALIFYING"));
            writer.bytes=BObservationTrainRuntimeDiagnosticTool.DISK_BYTES;
            assertThrows(BObservationIdentificationTool.RunLimitException.class,()->writer.publish("ordinary.json",Map.of()));
            assertFalse(Files.exists(scratch.resolve("ordinary.json")));
        }
    }
    @Test void originalFreezeStaysUnchangedAndOldFitCannotAcceptTheInstrumentedTool() throws Exception {
        assertEquals(BObservationTrainRuntimeDiagnosticTool.OLD_MANIFEST,BIdentificationReceipt.hash(Files.readAllBytes(BObservationTrainRuntimeDiagnosticTool.MANIFEST)));
        assertEquals(BObservationTrainRuntimeDiagnosticTool.OLD_REGISTRY,BIdentificationReceipt.hash(Files.readAllBytes(BIdentificationManifest.REVIEWED_SOURCE_REGISTRY_PATH)));
        var failure=assertThrows(IllegalArgumentException.class,()->BIdentificationManifest.register(BObservationTrainRuntimeDiagnosticTool.MANIFEST,BIdentificationReceipt.sourceHash()));
        assertEquals("Reviewed source member changed: "+BObservationTrainRuntimeDiagnosticTool.TOOL,failure.getMessage());
    }
    @Test void onlyReviewedDiagnosticMembersAndNumerical26CanValidateWithoutMaterializingAnything() throws Exception {
        var current=new TreeMap<String,String>();for(String path:BIdentificationReceipt.SOURCE_FILES)current.put(path,BIdentificationReceipt.sourceMemberHash(path));
        var helpers=new TreeMap<String,String>();for(String path:BObservationTrainRuntimeDiagnosticTool.HELPERS)helpers.put(path,BIdentificationReceipt.hash(Files.readAllBytes(Path.of(path))));
        var fields=new LinkedHashMap<String,Object>();fields.put("contract",BObservationTrainRuntimeDiagnosticTool.fixedContract());
        fields.put("sourceMembers",current);fields.put("sourceSha256",BIdentificationReceipt.hash(BIdentificationReceipt.canonical(current).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        fields.put("diagnosticMembers",helpers);fields.put("trainCatalogSha256",BIdentificationCatalog.trainDiagnosticCatalog().canonicalHash());
        byte[] registry=Files.readAllBytes(BIdentificationManifest.REVIEWED_SOURCE_REGISTRY_PATH),manifest=Files.readAllBytes(BObservationTrainRuntimeDiagnosticTool.MANIFEST);
        BObservationTrainRuntimeDiagnosticTool.validateFreeze(fields,current,helpers,registry,manifest);
        for(String key:fields.keySet()) {var changed=new LinkedHashMap<>(fields);changed.remove(key);
            assertThrows(IllegalArgumentException.class,()->BObservationTrainRuntimeDiagnosticTool.validateFreeze(changed,current,helpers,registry,manifest));}
        var changed=new TreeMap<>(current);changed.put("service/tracing/probabilistic/BIdentificationScenes.java","0".repeat(64));
        assertThrows(IllegalArgumentException.class,()->BObservationTrainRuntimeDiagnosticTool.validateFreeze(fields,changed,helpers,registry,manifest));
        var missingHelper=new TreeMap<>(helpers);missingHelper.remove(missingHelper.firstKey());
        assertThrows(IllegalArgumentException.class,()->BObservationTrainRuntimeDiagnosticTool.validateFreeze(fields,current,missingHelper,registry,manifest));
        var changedTool=new TreeMap<>(current);changedTool.put(BObservationTrainRuntimeDiagnosticTool.TOOL,"0".repeat(64));
        assertThrows(IllegalArgumentException.class,()->BObservationTrainRuntimeDiagnosticTool.validateFreeze(fields,changedTool,helpers,registry,manifest));
    }
    @Test void diagnosticFilesRejectSymlinksOversizeAndConfigurableMainArguments() throws Exception {
        var file=temp.resolve("large");Files.writeString(file,"x".repeat(100));
        assertThrows(IllegalArgumentException.class,()->BObservationTrainRuntimeDiagnosticTool.readBounded(file,99));
        var link=temp.resolve("link");Files.createSymbolicLink(link,file);
        assertThrows(IllegalArgumentException.class,()->BObservationTrainRuntimeDiagnosticTool.readBounded(link,100));
        assertThrows(IllegalArgumentException.class,()->BObservationTrainRuntimeDiagnosticTool.main(new String[]{"--phase","FIT_SELECT"}));
        assertThrows(IllegalArgumentException.class,()->BObservationTrainRuntimeDiagnosticTool.main(new String[]{"--case","anything"}));
    }
    @Test void actualDriverCancellationRecordsFailedPhasesAndClosesBeforeAnyRasterRead() throws Exception {
        var clock=new AtomicLong();var telemetry=new BObservationRuntimeTelemetry(clock::incrementAndGet,s->{});
        var controls=new BObservationIdentificationTool.Controls(null,64L*1024*1024,300_000_000_000L,
                System::nanoTime,org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,
                ()->{throw new CancellationException("dummy-only");});
        var output=temp.resolve("cancelled-fixture");
        assertThrows(CancellationException.class,()->BObservationIdentificationTool.runTrainFixture(output,controls,
                BObservationIdentificationTool.TrainFixtureFault.NONE,telemetry));
        var failure=BIdentificationReceipt.read(output.resolve("failure.json"));
        assertEquals("CANCELLED",failure.get("reason"));assertEquals(0,((Number)failure.get("rasterReads")).intValue());
        assertEquals(0,((Number)failure.get("holdoutReads")).intValue());
        assertEquals(1L,row(telemetry.snapshot(),"SCENE_SCORE").get("failed"));
        for(var phase:BObservationRuntimeTelemetry.Phase.values())assertEquals(0L,row(telemetry.snapshot(),phase.name()).get("active"));
    }
    @Test void oneThreeHundredSecondClockIsNeverResetAtPhaseBoundaries() {
        var clock=new AtomicLong();var controls=new BObservationIdentificationTool.Controls(null,64L*1024*1024,
                BObservationTrainRuntimeDiagnosticTool.WATCHDOG_NANOS,clock::get,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,()->{});
        var probe=BObservationIdentificationTool.guarded(controls,0);
        clock.set(299_999_999_999L);assertDoesNotThrow(probe::checkpoint);
        clock.set(300_000_000_000L);var failure=assertThrows(BObservationIdentificationTool.RunLimitException.class,probe::checkpoint);
        assertEquals("TIME",failure.reason);
        clock.set(301_000_000_000L);assertThrows(BObservationIdentificationTool.RunLimitException.class,probe::checkpoint);
    }
    @Test void extractCapabilityCannotAcquireSelectHoldoutOrRetiredValues() {
        var access=BIdentificationCatalog.EvaluationAccess.preselection(BIdentificationCatalog.Phase.EXTRACT);
        for(var split:List.of(BIdentificationCatalog.Split.SELECT,BIdentificationCatalog.Split.HOLDOUT,BIdentificationCatalog.Split.INELIGIBLE_DIAGNOSTIC)) {
            var key=new BIdentificationCatalog.CaseKey("dummy-id","dummy-family","dummy-geometry","dummy-source",split,"dummy-recipe",0);
            assertThrows(SecurityException.class,()->access.requireKey(key));
        }
        assertEquals(0,access.rasterReads());assertEquals(0,access.labelReads());
    }
    @Test void protocolRejectsEveryAuthorityOrWorkMutationBeforeRendering() throws Exception {
        var fields=BObservationTrainRuntimeDiagnosticTool.fixedContract();
        BObservationTrainRuntimeDiagnosticTool.validateContract(fields);
        for(String key:fields.keySet()) {
            var changed=new LinkedHashMap<>(fields);changed.remove(key);
            assertThrows(IllegalArgumentException.class,()->BObservationTrainRuntimeDiagnosticTool.validateContract(changed),key);
        }
        for(var mutation:Map.<String,Object>of("gridOrdinal",1,"watchdogNanos",301_000_000_000L,
                "selectionAuthority","FULL_PUBLIC_PROTOCOL","caseIds",List.of("forbidden/select/v0/left")).entrySet()) {
            var changed=new LinkedHashMap<>(fields);changed.put(mutation.getKey(),mutation.getValue());
            assertThrows(IllegalArgumentException.class,()->BObservationTrainRuntimeDiagnosticTool.validateContract(changed));
        }
    }

    @Test void diagnosticInputGuardRejectsEveryNonTrainOrUnregisteredCase() {
        var catalog=BIdentificationCatalog.trainDiagnosticCatalog();
        for(var definition:catalog.definitions()) {
            boolean allowed=BObservationTrainRuntimeDiagnosticTool.CASES.contains(definition.key().id());
            if(allowed)assertDoesNotThrow(()->BObservationTrainRuntimeDiagnosticTool.requireCase(definition));
            else assertThrows(SecurityException.class,()->BObservationTrainRuntimeDiagnosticTool.requireCase(definition));
        }
        var original=catalog.definitions().stream().filter(d->BObservationTrainRuntimeDiagnosticTool.CASES.contains(d.key().id())).findFirst().orElseThrow();
        var k=original.key();
        for(var split:BIdentificationCatalog.Split.values())if(split!=BIdentificationCatalog.Split.TRAIN) {
            var changed=new BIdentificationCatalog.CaseDefinition(new BIdentificationCatalog.CaseKey(k.id(),k.generatorFamilyId(),
                    k.geometryGroupId(),k.sourceGroupId(),split,k.recipeId(),k.seed()),original.contrastId(),original.side(),
                    original.variantId(),original.phase(),original.sourcePitchMeters(),original.oversampling(),original.reversed(),original.equation(),original.capabilities());
            assertThrows(SecurityException.class,()->BObservationTrainRuntimeDiagnosticTool.requireCase(changed));
        }
    }
    @Test void fineReceiptCallsDoNotPublishProgressSnapshots() throws Exception {
        var snapshots=new ArrayList<Map<String,Object>>();var clock=new AtomicLong();
        var telemetry=new BObservationRuntimeTelemetry(clock::incrementAndGet,snapshots::add);
        for(int i=0;i<4096;i++)try(var token=telemetry.begin(BObservationRuntimeTelemetry.Phase.RECEIPT_WRITE)){token.succeeded();}
        assertEquals(0,snapshots.size());
        assertEquals(4096L,row(telemetry.snapshot(),"RECEIPT_WRITE").get("succeeded"));
        try(var token=telemetry.begin(BObservationRuntimeTelemetry.Phase.PAIR_CACHE_WRITE)){token.succeeded();}
        assertEquals(1,snapshots.size());
    }

    @Test void originalDriverOutputsStayByteExactAndProfilingMeasuresTheActualRoute() throws Exception {
        var clock=new AtomicLong();var snapshots=new ArrayList<Map<String,Object>>();
        var telemetry=new BObservationRuntimeTelemetry(clock::incrementAndGet,snapshots::add);
        var output=temp.resolve("profiled-fixture");
        BObservationIdentificationTool.runTrainFixture(output,BObservationIdentificationTool.Controls.defaults(),
                BObservationIdentificationTool.TrainFixtureFault.NONE,telemetry);
        var hashes=new TreeMap<String,Object>();String source=BIdentificationReceipt.sourceHash();
        String renderer=BIdentificationReceipt.sourceMemberHash("service/tracing/probabilistic/BObservationIdentificationTool.java");
        try(var paths=Files.list(output)){for(var path:paths.sorted().toList()) {
            var bytes=normalizedFixture(path.getFileName().toString(),Files.readString(path),source,renderer).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            hashes.put(path.getFileName().toString(),BIdentificationReceipt.hash(bytes));
        }}
        assertEquals(18,hashes.size());
        assertEquals("d17fd4ace59b0cd1dabe7d7a1d8c55b0a4c8a6f1fa2bf73d65c3007416a846ab",
                BIdentificationReceipt.hash(BIdentificationReceipt.canonical(hashes).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for(String phase:List.of("SOURCE_VERIFY","RENDER","PROFILE_BUILD","FACT_EXTRACTION","LATTICE_ADMISSION",
                "INPUT_FINGERPRINT","SCENE_SCORE","BASELINE_SCORE","CANDIDATE_PROFILES","PROPER_SCORE",
                "RECONSTRUCTION","MAP_SOLVE","ROUTE_CHECK","PAIR_PROFILES","PAIR_CACHE_WRITE","PAIR_CACHE_READ",
                "PAIR_COMPARE","COUPLED","RECEIPT_WRITE"))
            assertTrue(((Number)row(telemetry.snapshot(),phase).get("succeeded")).longValue()>0,phase);
        assertTrue(snapshots.size()<128,"coarse progress publication remains bounded");
    }
    @Test void fixtureIdentityNormalizationRejectsAnUnexpectedThirdHash() {
        assertThrows(AssertionError.class,()->normalizedFixture("summary.json","{\"sourceHash\":\"third\"}","actual","renderer"));
        assertThrows(AssertionError.class,()->normalizedFixture("scene-000.json",
                "{\"inputBinding\":{\"sourceHash\":\"actual\",\"rendererHash\":\"third\"}}","actual","renderer"));
    }
    private static String normalizedFixture(String file,String text,String source,String renderer) throws Exception {
        var fields=BIdentificationReceipt.parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(file.equals("summary.json"))assertEquals(source,fields.get("sourceHash"));
        else if(file.equals("scene-000.json")||file.equals("scene-001.json")
                ||file.equals("train-geometry-s000-G1-L1-B0.json")||file.equals("train-geometry-s001-G1-L1-B0.json")) {
            var binding=(Map<?,?>)fields.get(file.startsWith("scene-")?"inputBinding":"input");
            assertEquals(source,binding.get("sourceHash"));assertEquals(renderer,binding.get("rendererHash"));
        } else {assertFalse(text.contains("\"sourceHash\""));assertFalse(text.contains("\"rendererHash\""));return text;}
        return text.replace("\"sourceHash\":\""+source+"\"","\"sourceHash\":\"SOURCE_IDENTITY\"")
                .replace("\"rendererHash\":\""+renderer+"\"","\"rendererHash\":\"RENDERER_IDENTITY\"");
    }
    @Test void startsArePublishedBeforeWorkAndExceptionsAreFailedRatherThanSuccessful() throws Exception {
        var clock=new AtomicLong(10);var snapshots=new ArrayList<Map<String,Object>>();
        var telemetry=new BObservationRuntimeTelemetry(clock::get,snapshots::add);
        try(var phase=telemetry.begin(BObservationRuntimeTelemetry.Phase.MAP_SOLVE)) {
            assertEquals(1,snapshots.size());
            assertEquals(1L,row(snapshots.get(0),"MAP_SOLVE").get("active"));
            clock.set(17);
        }
        var failed=row(telemetry.snapshot(),"MAP_SOLVE");
        assertEquals(1L,failed.get("attempted"));assertEquals(0L,failed.get("succeeded"));
        assertEquals(1L,failed.get("failed"));assertEquals(0L,failed.get("active"));assertEquals(7L,failed.get("elapsedNanos"));
    }

    @Test void nestedDurationsAreInclusiveAndSuccessIsExplicit() throws Exception {
        var clock=new AtomicLong(2);var telemetry=new BObservationRuntimeTelemetry(clock::get,s->{});
        try(var outer=telemetry.begin(BObservationRuntimeTelemetry.Phase.SCENE_SCORE)) {
            clock.set(5);
            try(var inner=telemetry.begin(BObservationRuntimeTelemetry.Phase.RENDER)) {clock.set(12);inner.succeeded();}
            clock.set(16);outer.succeeded();
        }
        assertEquals(14L,row(telemetry.snapshot(),"SCENE_SCORE").get("elapsedNanos"));
        assertEquals(7L,row(telemetry.snapshot(),"RENDER").get("elapsedNanos"));
        assertEquals(1L,row(telemetry.snapshot(),"RENDER").get("succeeded"));
    }

    @Test void cancellationAndSnapshotFailureCannotBeReportedAsSuccessfulWork() throws Exception {
        var clock=new AtomicLong();var telemetry=new BObservationRuntimeTelemetry(clock::incrementAndGet,s->{});
        assertThrows(CancellationException.class,()->{
            try(var token=telemetry.begin(BObservationRuntimeTelemetry.Phase.RENDER)) {throw new CancellationException();}
        });
        assertEquals(1L,row(telemetry.snapshot(),"RENDER").get("failed"));
        var failing=new BObservationRuntimeTelemetry(clock::incrementAndGet,s->{throw new IllegalStateException("sink");});
        assertThrows(IllegalStateException.class,()->failing.begin(BObservationRuntimeTelemetry.Phase.RENDER));
        assertEquals(0L,row(failing.snapshot(),"RENDER").get("active"));
        assertEquals(1L,row(failing.snapshot(),"RENDER").get("failed"));
    }

    @Test void disabledTelemetryUsesOneTokenAndNeverReadsAClockOrCreatesSnapshots() throws Exception {
        var first=BObservationRuntimeTelemetry.NONE.begin(BObservationRuntimeTelemetry.Phase.RENDER);
        var second=BObservationRuntimeTelemetry.NONE.begin(BObservationRuntimeTelemetry.Phase.MAP_SOLVE);
        assertSame(first,second);first.pause();first.resume();first.succeeded();first.close();second.close();second.pause();second.resume();
        assertFalse(BObservationRuntimeTelemetry.NONE.enabled());
        assertEquals(Map.of(),BObservationRuntimeTelemetry.NONE.snapshot());
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> row(Map<String,Object> snapshot,String phase) {
        return (Map<String,Object>)((Map<?,?>)snapshot.get("phases")).get(phase);
    }
}

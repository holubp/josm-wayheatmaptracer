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
        assertSame(first,second);first.succeeded();first.close();second.close();
        assertFalse(BObservationRuntimeTelemetry.NONE.enabled());
        assertEquals(Map.of(),BObservationRuntimeTelemetry.NONE.snapshot());
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> row(Map<String,Object> snapshot,String phase) {
        return (Map<String,Object>)((Map<?,?>)snapshot.get("phases")).get(phase);
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V022SingleTrainCostBoundaryTest {
    @TempDir Path temporary;
    static Map<String,String> launch(){return Map.of("GITHUB_ACTIONS","true","GITHUB_EVENT_NAME","push","GITHUB_REF",BSingleTrainCostManifest.BRANCH,
            "GITHUB_RUN_ATTEMPT","1","GITHUB_REPOSITORY","holubp/josm-wayheatmaptracer","GITHUB_RUN_ID","123","GITHUB_SHA","a".repeat(40),
            "GITHUB_WORKFLOW_REF","holubp/josm-wayheatmaptracer/.github/workflows/observation-single-train-cost.yml@"+BSingleTrainCostManifest.BRANCH);}
    @Test void immutablePendingWrongContextAndOtherPurposeRefuseBeforeDummyAcquisition() throws Exception {
        var calls=new AtomicInteger();
        assertThrows(SecurityException.class,()->BSingleTrainCostManifest.authorize(BProspectiveIdentificationManifest.PENDING,launch(),calls::incrementAndGet));
        assertEquals(0,calls.get());
        for(String key:launch().keySet()) {
            var changed=new TreeMap<>(launch());changed.put(key,"unsafe\nvalue");
            assertThrows(SecurityException.class,()->BSingleTrainCostManifest.authorize(BProspectiveIdentificationManifest.APPROVED,changed,calls::incrementAndGet),key);
        }
        assertEquals(0,calls.get());BSingleTrainCostManifest.authorize(BProspectiveIdentificationManifest.APPROVED,launch(),calls::incrementAndGet);assertEquals(1,calls.get());
        var members=new TreeMap<String,String>();for(String path:BSingleTrainCostManifest.memberPaths())members.put(path,"a".repeat(64));
        var fields=new TreeMap<String,Object>(BSingleTrainCostManifest.fixedFields());fields.put("approval",BProspectiveIdentificationManifest.PENDING);fields.put("members",members);
        byte[] bytes=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);String expected=BIdentificationReceipt.hash(bytes);
        assertDoesNotThrow(()->BSingleTrainCostManifest.validateSnapshot(expected,bytes,members));
        var other=new TreeMap<>(members);other.put(other.firstKey(),"b".repeat(64));
        assertThrows(SecurityException.class,()->BSingleTrainCostManifest.validateSnapshot(expected,bytes,other));
        fields.put("members",other);byte[] changed=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,()->BSingleTrainCostManifest.validateSnapshot(expected,changed,other));
        fields.put("members",members);fields.put("approval",BProspectiveIdentificationManifest.APPROVED);
        byte[] signed=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,()->BSingleTrainCostManifest.validateSnapshot(expected,signed,members));
        fields.put("purpose","PROSPECTIVE_FIT");byte[] wrong=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,()->BSingleTrainCostManifest.validateSnapshot(BIdentificationReceipt.hash(wrong),wrong,members));
    }
    @Test void actualPendingMetadataAndOld48ValidateWithoutAnEmpiricalEntrypoint() throws Exception {
        byte[] bytes=Files.readAllBytes(BSingleTrainCostManifest.FREEZE);var seal=BSingleTrainCostManifest.register(BIdentificationReceipt.hash(bytes));seal.verify();
        assertTrue(BSingleTrainCostManifest.memberPaths().containsAll(BSingleTrainDiagnosticManifest.memberPaths()));
        // No requireExecution/main, even if a later reviewed administrative signature approves the actual freeze.
    }
    @Test void boundedTimerArgumentsStoredWitnessAndOutputLeavesRefuse() throws Exception {
        assertThrows(BObservationIdentificationTool.RunLimitException.class,()->BSingleTrainCostDiagnosticTool.deadline(()->300_000_000_000L,0).checkpoint());
        assertDoesNotThrow(()->BSingleTrainCostDiagnosticTool.deadline(()->299_999_999_999L,0).checkpoint());
        assertThrows(BObservationIdentificationTool.RunLimitException.class,()->BSingleTrainCostDiagnosticTool.deadline(()->-1L,0).checkpoint());
        assertThrows(IllegalArgumentException.class,()->BSingleTrainCostDiagnosticTool.main(new String[]{"--case","other"}));
        byte[] witness=BSingleTrainCostDiagnosticTool.witness();assertEquals(BSingleTrainCostManifest.WITNESS,BIdentificationReceipt.hash(witness));
        assertThrows(SecurityException.class,()->BSingleTrainCostDiagnosticTool.decodeWitness("{}".getBytes(StandardCharsets.UTF_8),List.of()));
        var files=BSingleTrainCostDiagnosticTool.outputFiles();assertEquals(32,files.size());assertEquals(32,new HashSet<>(files).size());
        try(var writer=new BObservationIdentificationTool.Writer(temporary,67_108_864)) {
            assertThrows(SecurityException.class,()->BSingleTrainCostDiagnosticTool.publish(writer,"../secret.json",Map.of()));assertEquals(0,writer.bytes);
            var progress=new BSingleTrainCostDiagnosticTool.Progress(writer);progress.begin(BSingleTrainCostDiagnosticTool.Phase.ACQUISITION);
            var parsed=BIdentificationReceipt.parse(Files.readAllBytes(temporary.resolve("progress-00.json")));
            assertTrue(BIdentificationReceipt.canonical(parsed).contains("\"active\":1"));progress.failed();
            assertTrue(BIdentificationReceipt.canonical(progress.snapshot()).contains("\"failed\":1"));
            assertThrows(IllegalStateException.class,()->progress.begin(BSingleTrainCostDiagnosticTool.Phase.ACQUISITION));
            assertThrows(IllegalArgumentException.class,()->BSingleTrainCostDiagnosticTool.publish(writer,"binding.json",Map.of("oversized","x".repeat(65_536))));
            assertEquals(0,writer.retainedOrdinaryReservationBytes,"canonical-size refusal occurs before any publication reservation");
        }
    }
    @Test void failedOwnedPartialRemainsConservativelyChargedAndNoDiskBypass() throws Exception {
        try(var writer=new BObservationIdentificationTool.Writer(temporary,67_108_864,(path,fields)->{
            Files.writeString(path.resolveSibling(path.getFileName()+".partial"),"prefix");throw new java.io.IOException("fixed");
        })) {
            assertThrows(java.io.IOException.class,()->BSingleTrainCostDiagnosticTool.publish(writer,"binding.json",Map.of("schema",1)));
            assertEquals(writer.bytes,writer.retainedOrdinaryReservationBytes);assertTrue(writer.bytes>=Files.size(temporary.resolve("binding.json.partial")));
            writer.bytes=2_147_483_648L;
            assertThrows(BObservationIdentificationTool.RunLimitException.class,()->BSingleTrainCostDiagnosticTool.publish(writer,"status.json",Map.of("status","INCOMPLETE")));
        }
    }
    @SuppressWarnings("unchecked") @Test void exactPublicWitnessDecodesOnlyMatchingAdmittedCoordinatesAndArchivedFullInput() throws Exception {
        byte[] bytes=BSingleTrainCostDiagnosticTool.witness();var fields=BIdentificationReceipt.parse(bytes);
        var indices=(List<Number>)fields.get("states");var points=(List<Map<String,Number>>)fields.get("points");
        var rows=new ArrayList<List<org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint>>();
        for(int i=0;i<81;i++) {
            var point=points.get(i);var row=new ArrayList<org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint>();
            for(int s=0;s<36;s++)row.add(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(-1,-1));
            row.set(indices.get(i).intValue(),new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(point.get("xMeters").doubleValue(),point.get("yMeters").doubleValue()));rows.add(row);
        }
        assertArrayEquals(indices.stream().mapToInt(Number::intValue).toArray(),BSingleTrainCostDiagnosticTool.decodeWitness(bytes,rows));
        rows.get(4).set(indices.get(4).intValue(),new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(1,1));
        assertThrows(SecurityException.class,()->BSingleTrainCostDiagnosticTool.decodeWitness(bytes,rows));
        var archive=BSingleTrainGeometryDiagnosticTool.archived("G1-L1-B0");
        assertThrows(SecurityException.class,()->BSingleTrainGeometryDiagnosticTool.decode("G1-L1-B0",archive,Map.of(),rows));
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022SingleTrainComparatorBoundaryTest {
    @TempDir Path directory;
    static Map<String,String> launch(){var map=new TreeMap<>(V022SingleTrainCostBoundaryTest.launch());map.put("GITHUB_REF",BSingleTrainComparatorManifest.BRANCH);map.put("GITHUB_WORKFLOW_REF","holubp/josm-wayheatmaptracer/.github/workflows/observation-single-train-comparator.yml@"+BSingleTrainComparatorManifest.BRANCH);return map;}
    @Test void immutablePendingAndWrongPurposeContextOrExternalIdentityNeverAcquire() throws Exception {
        var calls=new AtomicInteger();assertThrows(SecurityException.class,()->BSingleTrainComparatorManifest.authorize(BProspectiveIdentificationManifest.PENDING,launch(),calls::incrementAndGet));
        for(String key:launch().keySet()){var changed=new TreeMap<>(launch());changed.put(key,"unsafe\nvalue");assertThrows(SecurityException.class,()->BSingleTrainComparatorManifest.authorize(BProspectiveIdentificationManifest.APPROVED,changed,calls::incrementAndGet));}
        assertEquals(0,calls.get());BSingleTrainComparatorManifest.authorize(BProspectiveIdentificationManifest.APPROVED,launch(),calls::incrementAndGet);assertEquals(1,calls.get());
        var members=new TreeMap<String,String>();for(String path:BSingleTrainComparatorManifest.memberPaths())members.put(path,"a".repeat(64));
        var fields=new TreeMap<String,Object>(BSingleTrainComparatorManifest.fixedFields());fields.put("approval",BProspectiveIdentificationManifest.PENDING);fields.put("members",members);
        byte[] bytes=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);String expected=BIdentificationReceipt.hash(bytes);
        assertDoesNotThrow(()->BSingleTrainComparatorManifest.validateSnapshot(expected,bytes,members));
        var modified=new TreeMap<>(members);modified.put(modified.firstKey(),"b".repeat(64));assertThrows(SecurityException.class,()->BSingleTrainComparatorManifest.validateSnapshot(expected,bytes,modified));
        fields.put("members",modified);byte[] consistent=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,()->BSingleTrainComparatorManifest.validateSnapshot(expected,consistent,modified));
        fields.put("members",members);fields.put("approval",BProspectiveIdentificationManifest.APPROVED);byte[] signed=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,()->BSingleTrainComparatorManifest.validateSnapshot(expected,signed,members));
        fields.put("purpose","PROSPECTIVE_FIT");byte[] other=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,()->BSingleTrainComparatorManifest.validateSnapshot(BIdentificationReceipt.hash(other),other,members));
    }
    @Test void actualCandidateMetadataAndOld63ValidateWithoutEmpiricalEntrypoints() throws Exception {
        byte[] bytes=Files.readAllBytes(BSingleTrainComparatorManifest.FREEZE);var seal=BSingleTrainComparatorManifest.register(BIdentificationReceipt.hash(bytes));seal.verify();
        assertTrue(BSingleTrainComparatorManifest.memberPaths().containsAll(BSingleTrainCostManifest.memberPaths()));
        // Never call actual main/requireExecution/provider, even after any separately approved signature.
    }
    @Test void partialProofIoDoesNotPreventConservativelyChargedTerminalOrBypassDiskCap() throws Exception {
        var records=new ArrayList<Map<String,Object>>();var original=new IllegalStateException("fixed sentinel");
        try(var writer=new BObservationIdentificationTool.Writer(directory,67_108_864,(path,fields)->{
            if(path.getFileName().toString().equals("proof.json")){Files.writeString(directory.resolve("proof.json.partial"),"prefix");throw new java.io.IOException("fixed partial");}records.add(fields);
        })) {
            BSingleTrainComparatorTool.publishFailure(writer,false,Map.of("schema",1),()->Map.of("status","INCOMPLETE","diskChargeBeforeStatusUpperBound",writer.bytes,"uncertainOrdinaryReservationBytes",writer.retainedOrdinaryReservationBytes),original);
            assertEquals(1,records.size());var terminal=records.get(0);assertTrue(((Number)terminal.get("uncertainOrdinaryReservationBytes")).longValue()>=Files.size(directory.resolve("proof.json.partial")));
            assertEquals(terminal.get("uncertainOrdinaryReservationBytes"),terminal.get("diskChargeBeforeStatusUpperBound"));assertEquals(1,original.getSuppressed().length);
            writer.bytes=2_147_483_648L;var cap=new IllegalStateException("fixed cap");
            BSingleTrainComparatorTool.publishFailure(writer,true,Map.of(),()->Map.of("status","INCOMPLETE"),cap);assertEquals(1,cap.getSuppressed().length);assertEquals(1,records.size());
            assertThrows(SecurityException.class,()->BSingleTrainComparatorTool.publish(writer,"../other.json",Map.of()));
        }
    }
    @Test void returnedSolverFailuresAreUnavailableAndNeverSuccessfulPhases() throws Exception {
        var ledger=new AttemptMemoryLedger(1_000_000);
        try(var owner=ledger.rootOwner();var writer=new BObservationIdentificationTool.Writer(directory,67_108_864)) {
            var result=new ProbabilisticInference().solve(V022SingleTrainComparatorSolverTest.normalizedGraph(),EvidenceModelParameters.defaults(),new TraceBudgets(96,1000,1,1,1),V022SingleTrainComparatorRegionTest.hard(),CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,result.status());assertThrows(IllegalStateException.class,()->BSingleTrainComparatorTool.requireUsable(result));
            var progress=new BSingleTrainComparatorTool.Progress(writer);progress.begin(BSingleTrainComparatorTool.Phase.SOLVE);progress.failed();
            assertTrue(BIdentificationReceipt.canonical(progress.fields()).contains("\"failed\":1"));assertFalse(BIdentificationReceipt.canonical(progress.fields()).contains("\"succeeded\":1"));
            assertEquals("RESOURCE_LIMIT",BSingleTrainComparatorTool.solverFields(result).get("status"));
            assertThrows(SecurityException.class,()->BSingleTrainComparatorTool.requireBits(1,Math.nextUp(1d)));
            assertThrows(SecurityException.class,()->BSingleTrainComparatorTool.requireBits(1,Double.POSITIVE_INFINITY));
        }
        assertEquals(0,ledger.currentBytes());assertEquals(32,new HashSet<>(BSingleTrainComparatorTool.outputFiles()).size());
    }
}

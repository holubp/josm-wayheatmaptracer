package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class V022SingleTrainDiagnosticBoundaryTest {
    @Test void pendingAndWrongLaunchNeverReachEvenADummyAcquisition() {
        var calls=new AtomicInteger();
        assertThrows(SecurityException.class,()->BSingleTrainDiagnosticManifest.authorize(
                BProspectiveIdentificationManifest.PENDING,Map.of(),calls::incrementAndGet));
        assertEquals(0,calls.get());
        assertThrows(SecurityException.class,()->BSingleTrainDiagnosticManifest.authorize(
                BProspectiveIdentificationManifest.APPROVED,Map.of(),calls::incrementAndGet));
        assertEquals(0,calls.get());
    }
    @Test void missingFixedPurposeAndInventoryRefuse() {
        assertThrows(IllegalArgumentException.class,()->BSingleTrainDiagnosticManifest.validateFields(Map.of()));
    }
    static Map<String,String> launch() {
        return Map.of("GITHUB_ACTIONS","true","GITHUB_EVENT_NAME","workflow_dispatch","GITHUB_RUN_ATTEMPT","1",
                "GITHUB_REPOSITORY","holubp/josm-wayheatmaptracer","GITHUB_RUN_ID","123","GITHUB_SHA","a".repeat(40),
                "GITHUB_WORKFLOW_REF","holubp/josm-wayheatmaptracer/.github/workflows/build.yml@refs/heads/reviewed");
    }
    @Test void immutableApprovalFixturesExerciseOnlyDummyEndpointsAndRejectEveryUnsafeLaunchField() {
        var calls=new AtomicInteger();
        BSingleTrainDiagnosticManifest.authorize(BProspectiveIdentificationManifest.APPROVED,launch(),calls::incrementAndGet);
        assertEquals(1,calls.get());
        for(String key:launch().keySet()) {
            var changed=new TreeMap<>(launch());changed.put(key,"unsafe\nvalue");
            assertThrows(SecurityException.class,()->BSingleTrainDiagnosticManifest.authorize(BProspectiveIdentificationManifest.APPROVED,changed,calls::incrementAndGet),key);
        }
        assertEquals(1,calls.get());
        var fields=new TreeMap<String,Object>(BSingleTrainDiagnosticManifest.fixedFields());fields.put("approval",BProspectiveIdentificationManifest.PENDING);fields.put("members",Map.of());
        assertDoesNotThrow(()->BSingleTrainDiagnosticManifest.validateFields(fields));
        for(String key:BSingleTrainDiagnosticManifest.fixedFields().keySet()) {
            var changed=new TreeMap<>(fields);changed.put(key,"unreviewed");
            assertThrows(IllegalArgumentException.class,()->BSingleTrainDiagnosticManifest.validateFields(changed),key);
        }
    }
    @Test void timerStartsBeforeMetadataAndExternalDigestAndArgumentsFailClosed() {
        var timer=BSingleTrainGeometryDiagnosticTool.deadline(()->300_000_000_000L,0);
        assertThrows(BObservationIdentificationTool.RunLimitException.class,timer::checkpoint);
        assertDoesNotThrow(()->BSingleTrainGeometryDiagnosticTool.deadline(()->299_999_999_999L,0).checkpoint());
        assertThrows(BObservationIdentificationTool.RunLimitException.class,()->BSingleTrainGeometryDiagnosticTool.deadline(()->-1L,0).checkpoint());
        assertThrows(IllegalArgumentException.class,()->BSingleTrainGeometryDiagnosticTool.main(new String[]{"--case","other","--freeze-sha256","0".repeat(64)}));
        assertThrows(IllegalArgumentException.class,()->BSingleTrainGeometryDiagnosticTool.main(new String[]{"--output","unused","--output","other"}));
    }
    @Test void consistentSourceAndFreezeMutationCannotReplaceTheIndependentExternalDigest() throws Exception {
        var members=new TreeMap<String,String>();for(String path:BSingleTrainDiagnosticManifest.memberPaths())members.put(path,"a".repeat(64));
        var fields=new TreeMap<String,Object>(BSingleTrainDiagnosticManifest.fixedFields());fields.put("approval",BProspectiveIdentificationManifest.PENDING);fields.put("members",members);
        byte[] original=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String expected=BIdentificationReceipt.hash(original);
        assertDoesNotThrow(()->BSingleTrainDiagnosticManifest.validateSnapshot(expected,original,members));
        var changedMembers=new TreeMap<>(members);changedMembers.put(changedMembers.firstKey(),"b".repeat(64));
        assertThrows(SecurityException.class,()->BSingleTrainDiagnosticManifest.validateSnapshot(expected,original,changedMembers));
        fields.put("members",changedMembers);
        byte[] changed=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,()->BSingleTrainDiagnosticManifest.validateSnapshot(expected,changed,changedMembers));
        fields.put("members",members);fields.put("approval",BProspectiveIdentificationManifest.APPROVED);
        byte[] newPins=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,()->BSingleTrainDiagnosticManifest.validateSnapshot(expected,newPins,members),"pin change between workflow and registration must refuse");
    }
    @Test void actualCandidateMetadataValidatesWithoutInvokingAnyEmpiricalEntrypoint() throws Exception {
        byte[] freeze=java.nio.file.Files.readAllBytes(BSingleTrainDiagnosticManifest.FREEZE);
        assertEquals("Diagnostic source inventory changed",assertThrows(SecurityException.class,
                ()->BSingleTrainDiagnosticManifest.register(BIdentificationReceipt.hash(freeze))).getMessage());
        HistoricalObservationMetadataFixture.run("diagnostic",java.nio.file.Files.createTempDirectory("diagnostic-metadata-").resolve("unused"));
    }
}

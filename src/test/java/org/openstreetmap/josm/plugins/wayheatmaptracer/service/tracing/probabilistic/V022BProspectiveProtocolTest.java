package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V022BProspectiveProtocolTest {
    @Test void bothActualPurposeSealsValidateOnlyMetadataWithoutDependingOnApprovalStatus() throws Exception {
        var diagnostic=BProspectiveIdentificationManifest.registerMetadata(
                BProspectiveIdentificationManifest.Purpose.OPTIMIZED_TRAIN_DIAGNOSTIC,ProspectiveTestInputs.expectedPins());
        var fit=BProspectiveIdentificationManifest.registerMetadata(
                BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT,ProspectiveTestInputs.expectedPins());
        diagnostic.verifyUnchanged();fit.verifyUnchanged();
        assertEquals(diagnostic.sourceHash(),fit.sourceHash());assertEquals(diagnostic.registryHash(),fit.registryHash());
        assertNotEquals(diagnostic.manifestHash(),fit.manifestHash());assertNotEquals(diagnostic.catalogHash(),fit.catalogHash());
        assertEquals(BIdentificationCatalog.trainDiagnosticCatalog().canonicalHash(),diagnostic.catalogHash());
        assertEquals(BIdentificationCatalog.publicCatalog().canonicalHash(),fit.catalogHash());
        assertEquals("OPTIMIZED_TRAIN_DIAGNOSTIC",diagnostic.provenance().get("protocolPurpose"));
        assertEquals("PROSPECTIVE_FIT",fit.provenance().get("protocolPurpose"));
    }
    @Test void explicitEntrypointsRejectArbitraryAuthorityPathsOverridesAndHoldoutBeforeRegistration() {
        assertThrows(IllegalArgumentException.class,()->BObservationOptimizedTrainRuntimeDiagnosticTool.main(
                new String[]{"--manifest","arbitrary","--pins-sha256","0".repeat(64)}));
        assertThrows(IllegalArgumentException.class,()->BProspectiveObservationIdentificationTool.main(
                new String[]{"--phase","CATALOG","--output","unused","--registry","arbitrary"}));
        assertThrows(IllegalArgumentException.class,()->BProspectiveObservationIdentificationTool.main(
                new String[]{"--phase","CATALOG","--output","unused","--pins-sha256","0".repeat(64),"--binding","foreign"}));
        assertThrows(SecurityException.class,()->BProspectiveObservationIdentificationTool.main(
                new String[]{"--phase","HOLDOUT","--output","unused","--pins-sha256","0".repeat(64)}));
    }
    @Test void manualCiProvenanceUsesOnlySafeBoundedFieldsAndDoesNotAuthorizeADummyEndpoint() {
        var purpose=BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT;
        var environment=new LinkedHashMap<String,String>(Map.of("GITHUB_ACTIONS","true","GITHUB_EVENT_NAME","workflow_dispatch",
                "GITHUB_REPOSITORY","owner/repository","GITHUB_RUN_ID","123","GITHUB_SHA","a".repeat(40),
                "GITHUB_WORKFLOW_REF","owner/repository/.github/workflows/build.yml@refs/heads/reviewed","GITHUB_RUN_ATTEMPT","1"));
        environment.put("UNSAFE_SERVER_TEXT","private arbitrary text");
        var safe=BProspectiveIdentificationManifest.launchContext(environment,purpose);
        assertEquals(Set.of("repository","runId","commit","workflowRef","runAttempt"),safe.keySet());
        for(String key:List.of("GITHUB_EVENT_NAME","GITHUB_REPOSITORY","GITHUB_RUN_ID","GITHUB_SHA","GITHUB_WORKFLOW_REF","GITHUB_RUN_ATTEMPT")) {
            var bad=new LinkedHashMap<>(environment);bad.put(key,"unsafe\nvalue");
            assertTrue(BProspectiveIdentificationManifest.launchContext(bad,purpose).isEmpty(),key);
        }
        var retry=new LinkedHashMap<>(environment);retry.put("GITHUB_RUN_ATTEMPT","2");
        assertTrue(BProspectiveIdentificationManifest.launchContext(retry,purpose).isEmpty());
        var wrong=new LinkedHashMap<>(environment);wrong.put("GITHUB_WORKFLOW_REF",
                "owner/repository/.github/workflows/observation-train-runtime-optimized.yml@refs/heads/reviewed");
        assertTrue(BProspectiveIdentificationManifest.launchContext(wrong,purpose).isEmpty());
        var endpoint=new java.util.concurrent.atomic.AtomicInteger();
        for(String status:List.of(BProspectiveIdentificationManifest.PENDING,BProspectiveIdentificationManifest.APPROVED)) {
            if(status.equals(BProspectiveIdentificationManifest.PENDING))
                assertThrows(SecurityException.class,()->{BProspectiveIdentificationManifest.requireExecutionApproved(status);endpoint.incrementAndGet();});
            else {BProspectiveIdentificationManifest.requireExecutionApproved(status);endpoint.incrementAndGet();}
        }
        assertEquals(1,endpoint.get()); // Pure dummy endpoint, no source Seal/acquisition authority.
    }
    @Test void purposeAndEveryCapOrPolicyMutationRefuseInsteadOfMintingAuthority() {
        for(var purpose:BProspectiveIdentificationManifest.Purpose.values()) {
            var correct=BProspectiveIdentificationManifest.fixedPolicy(purpose);
            assertDoesNotThrow(()->BProspectiveIdentificationManifest.validatePolicy(purpose,correct));
            var other=purpose==BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT
                    ?BProspectiveIdentificationManifest.Purpose.OPTIMIZED_TRAIN_DIAGNOSTIC
                    :BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT;
            assertThrows(IllegalArgumentException.class,()->BProspectiveIdentificationManifest.validatePolicy(other,correct));
            for(String field:correct.keySet()) {
                var missing=new LinkedHashMap<>(correct);missing.remove(field);
                assertThrows(IllegalArgumentException.class,()->BProspectiveIdentificationManifest.validatePolicy(purpose,missing),field);
                var altered=new LinkedHashMap<>(correct);altered.put(field,"unreviewed");
                assertThrows(IllegalArgumentException.class,()->BProspectiveIdentificationManifest.validatePolicy(purpose,altered),field);
            }
            var extra=new LinkedHashMap<>(correct);extra.put("extra",true);
            assertThrows(IllegalArgumentException.class,()->BProspectiveIdentificationManifest.validatePolicy(purpose,extra));
        }
    }
    @Test void pendingMetadataAndUnknownStatusNeverAuthorizeAnEmpiricalRoute() {
        for(String status:List.of(BProspectiveIdentificationManifest.PENDING,"APPROVED","", "NONQUALIFYING"))
            assertThrows(SecurityException.class,()->BProspectiveIdentificationManifest.requireExecutionApproved(status));
        assertDoesNotThrow(()->BProspectiveIdentificationManifest.requireExecutionApproved(BProspectiveIdentificationManifest.APPROVED));
    }
    @Test void externalPinsBindExactRegistryPolicyAndPendingStatusWithoutInventingReview() {
        var policies=Map.of("OPTIMIZED_TRAIN_DIAGNOSTIC","a".repeat(64),"PROSPECTIVE_FIT","b".repeat(64));
        var pins=new LinkedHashMap<String,Object>(Map.of("schema",1,"approval",BProspectiveIdentificationManifest.PENDING,
                "sourceRegistrySha256","c".repeat(64),"policySha256",policies));
        assertDoesNotThrow(()->BProspectiveIdentificationManifest.validatePins(pins,"c".repeat(64),policies));
        for(String key:pins.keySet()) {
            var changed=new LinkedHashMap<>(pins);changed.put(key,"unreviewed");
            assertThrows(IllegalArgumentException.class,()->BProspectiveIdentificationManifest.validatePins(changed,"c".repeat(64),policies),key);
        }
        assertThrows(IllegalArgumentException.class,()->BProspectiveIdentificationManifest.validatePins(pins,"d".repeat(64),policies));
    }
    @SuppressWarnings("unchecked")
    @Test void pureRegistryValidationBindsCoreAndEveryAddedAuthorityMemberWithoutMintingASeal() throws Exception {
        var old=BIdentificationReceipt.parse(Files.readAllBytes(BIdentificationManifest.REVIEWED_SOURCE_REGISTRY_PATH));
        var core=(Map<String,String>)(Map<?,?>)old.get("sourceMembers");
        var helpers=new TreeMap<String,String>();
        for(String path:BProspectiveIdentificationManifest.PROTOCOL_FILES)helpers.put(path,"a".repeat(64));
        var policies=Map.of("OPTIMIZED_TRAIN_DIAGNOSTIC","b".repeat(64),"PROSPECTIVE_FIT","c".repeat(64));
        String source=BIdentificationReceipt.hash(BIdentificationReceipt.canonical(Map.of("coreSourceSha256",old.get("sourceSha256"),
                "protocolMembers",helpers)).getBytes(StandardCharsets.UTF_8));
        var registry=new LinkedHashMap<String,Object>(Map.of("schema",1,"core",old,"protocolMembers",helpers,
                "sourceSha256",source,"policySha256",policies));
        byte[] base=Files.readAllBytes(BObservationTrainRuntimeDiagnosticTool.MANIFEST),index;
        try(var input=BIdentificationCatalog.class.getResourceAsStream("/b-observation-identification/holdout-index.json")) {
            assertNotNull(input);index=input.readAllBytes();
        }
        BProspectiveIdentificationManifest.validateRegistry(registry,core,helpers,policies,base,index);
        for(String key:registry.keySet()) {
            var changed=new LinkedHashMap<>(registry);changed.put(key,"unreviewed");
            assertThrows(IllegalArgumentException.class,()->BProspectiveIdentificationManifest.validateRegistry(changed,core,helpers,policies,base,index),key);
        }
        var altered=new TreeMap<>(helpers);altered.put(altered.firstKey(),"d".repeat(64));
        assertThrows(IllegalArgumentException.class,()->BProspectiveIdentificationManifest.validateRegistry(registry,core,altered,policies,base,index));
        var missing=new TreeMap<>(helpers);missing.remove(missing.firstKey());
        assertThrows(IllegalArgumentException.class,()->BProspectiveIdentificationManifest.validateRegistry(registry,core,missing,policies,base,index));
    }
}

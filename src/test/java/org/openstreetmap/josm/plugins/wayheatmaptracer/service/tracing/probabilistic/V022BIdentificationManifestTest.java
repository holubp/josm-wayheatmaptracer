package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V022BIdentificationManifestTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String valid() {
        StringBuilder owners = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            if (i > 1) owners.append(',');
            owners.append('"').append("OBS-%02d".formatted(i)).append("\":\"retained-test-").append(i).append('"');
        }
        // Protocol fixture only: deliberately not an approved numerical family.
        return """
            {"schema":1,"familyId":"validator-fixture","catalogHash":"%s",
            "featureVersion":"b-identification-facts-3","features":["PROMINENCE"],
            "equations":{"joint":"fixture only","weakLimit":"zero","zeroSupport":"q0",
              "uniform":"full admitted domain including invalid","censored":"frozen",
              "anchors":"zero unary and log measure","L0":"1 metre"},
            "units":{"joint":"1/metre","mass":"dimensionless"},
            "capabilities":{"native":"measured","unknownNative":"sampling descriptor only"},
            "parameterGrid":[{"gain":1}],"maxScenes":512,"maxFitSeconds":3600,
            "existingAttemptCaps":true,"acceptanceOwners":{%s},
            "resultDirectory":"identification/results"}
            """.formatted("a".repeat(64), owners);
    }
    @Test void manifestRejectsMissingEquationsOrUnits() throws Exception {
        assertFalse(BIdentificationManifest.read(bytes(valid())).fields().isEmpty());
        assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(valid().replace("\"units\"", "\"missingUnits\""))));
        assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(valid().replace("\"weakLimit\"", "\"missingWeakLimit\""))));
    }
    @Test void modelCannotConsumeTruthOrPrivateTargets() {
        for (String feature : new String[]{"Truth", "privateTarget", "MAP_PATH", "unknown"})
            assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(valid().replace("PROMINENCE",feature))));
    }
    @Test void familyCannotChangeAfterFreeze() throws Exception {
        var manifest = BIdentificationManifest.read(bytes(valid()));
        assertThrows(UnsupportedOperationException.class, () -> manifest.fields().put("familyId", "changed"));
        @SuppressWarnings("unchecked") var equations = (java.util.Map<String,Object>) manifest.fields().get("equations");
        assertThrows(UnsupportedOperationException.class, () -> equations.put("joint", "changed"));
    }
    @Test void searchDomainAndWorkBudgetAreFinite() {
        for (String altered : new String[]{valid().replace("3600", "3601"),
                valid().replace("512", "513"),valid().replace("\"existingAttemptCaps\":true", "\"existingAttemptCaps\":false"),
                valid().replace("[{\"gain\":1}]", "[" + "{\"gain\":1},".repeat(32) + "{\"gain\":1}]"),
                valid().replace("{\"gain\":1}", "{\"gain\":1e999}")})
            assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(altered)));
    }
    @Test void unknownSourceCapabilityIsExplicit() {
        assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(valid().replace("unknownNative", "missing"))));
    }
    @Test void allTwentySpecObligationsHaveAnAcceptanceOwner() {
        assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(valid().replace("OBS-20", "OBS-21"))));
    }
    @Test void parserRejectsDuplicateKeysUnsafePathsAndUnboundedInput() {
        for (String altered : new String[]{valid().replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
                valid().replace("identification/results", "../private"), valid().replace("identification/results", "/tmp/out"),
                "[".repeat(17) + "0" + "]".repeat(17)," ".repeat(65_537)})
            assertThrows(Exception.class, () -> BIdentificationManifest.read(bytes(altered)));
    }
    @Test void registeredCandidateUsesCurrentCatalogAndExactThirtyTwoConfigurations() throws Exception {
        var manifest = BIdentificationManifest.read(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "superpowers/docs/plans/2026-10-18-b-observation-feasibility-manifest.json")));
        assertEquals(BIdentificationCatalog.publicCatalog().canonicalHash(),manifest.fields().get("catalogHash"));
        assertEquals("area-presence-core-position-v2",manifest.fields().get("familyId"));
        @SuppressWarnings("unchecked") var grid = (java.util.List<java.util.Map<String,Object>>) manifest.fields().get("parameterGrid");
        var actual = new java.util.HashSet<String>();
        for (var row : grid) actual.add(row.get("G")+":"+row.get("lambda")+":"+row.get("B"));
        assertEquals(32,grid.size());
        for (int gain : new int[]{1,4,16,64}) for (int lambda : new int[]{1,2}) for (int span : new int[]{0,1,3,7})
            assertTrue(actual.contains(gain+":"+lambda+":"+span));
    }

    @Test void prospectiveSchemaTwoDraftBindsReviewedPoliciesAndUnchangedModelInventory() throws Exception {
        byte[] legacyBytes=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "superpowers/docs/plans/2026-10-16-b-observation-identification-manifest.json"));
        assertEquals("6e44cdc2b75d6d960861bea90f0916a809ff10145341c7431dc52fcdedc16003",
                BIdentificationReceipt.hash(legacyBytes));
        var legacy=BIdentificationManifest.read(legacyBytes);
        assertEquals(1,((Number)legacy.fields().get("schema")).intValue());
        assertEquals("joint-core-excess-v1",legacy.fields().get("familyId"));
        assertFalse(legacy.fields().containsKey("selectionProtocol"),
                "historical schema one remains readable without prospective policy authority");

        byte[] draftBytes=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "superpowers/docs/plans/2026-10-18-b-observation-feasibility-manifest.json"));
        var manifest=BIdentificationManifest.read(draftBytes);
        assertThrows(Exception.class,()->BIdentificationManifest.register(java.nio.file.Path.of(
                "superpowers/docs/plans/2026-10-18-b-observation-feasibility-manifest.json"),"0".repeat(64)),
                "a parseable draft must not acquire registration authority");
        assertEquals(2,((Number)manifest.fields().get("schema")).intValue());
        assertEquals("area-presence-core-position-v2",manifest.fields().get("familyId"));
        assertEquals("debec23907badfaa467fac91cb0da5f3d8771e357ea1faa6f618d60c852d7557",
                manifest.fields().get("catalogHash"));
        assertEquals("b-identification-facts-3",manifest.fields().get("featureVersion"));
        assertEquals("train-feasible-top4-score-order-v2",manifest.fields().get("selectionProtocol"));
        assertEquals("select-polynomial-v1",manifest.fields().get("selectCohort"));
        assertEquals("native-observations-production-resample-v6-strict-math",
                manifest.fields().get("rendererRevision"));
        assertEquals("INELIGIBLE_DIAGNOSTIC_NO_SCORE_NO_PAIRS_NO_COVERAGE",
                manifest.fields().get("retiredSelectScope"));
        @SuppressWarnings("unchecked") var training=(java.util.Map<String,Object>)manifest.fields().get("trainingControlOrder");
        assertEquals(java.util.Set.of("scoreAccumulation","configurationGeometry","sceneGeometry","cutoff",
                "encounteredFailure"),training.keySet());
        assertEquals("original-TRAIN-catalog-order",training.get("scoreAccumulation"));
        assertEquals("score-G-lambda-B",training.get("configurationGeometry"));
        assertEquals("selector-first-pitch-desc-variant-contrast-side",training.get("sceneGeometry"));
        assertEquals("four-fully-feasible-then-lower-geometry-untested",training.get("cutoff"));
        assertEquals("abort-entire-run-no-candidate-no-clock-reset",training.get("encounteredFailure"));
        @SuppressWarnings("unchecked") var holdout=(java.util.Map<String,Object>)manifest.fields().get("holdoutPolicy");
        assertEquals(java.util.Set.of("kind","generationId","publicIndexResource","publicIndexSha256",
                "publicIndexBytes","payloadSha256","payloadBytes","generatorSha256","protocolSha256"),holdout.keySet());
        assertEquals("sealed-unseen-v2",holdout.get("kind"));
        assertEquals("sealed-observation-v2-generation-v2",holdout.get("generationId"));
        assertEquals("/b-observation-identification/holdout-index.json",holdout.get("publicIndexResource"));
        assertEquals("97b134e7c983b698679258f244ee8431696bb34257615ce27675e19c21f6b092",
                holdout.get("publicIndexSha256"));
        assertEquals(19960,((Number)holdout.get("publicIndexBytes")).intValue());
        assertEquals("5d6b14c5f35fa6266d05bc11b508d3e0f675ed11b95a5c8b1e533d88f8d5aacd",
                holdout.get("payloadSha256"));
        assertEquals(65493,((Number)holdout.get("payloadBytes")).intValue());
        assertEquals("df94890b9d93dd878e845072e9aa956d95cc602f2fefccb1285c61a95ccbd85a",
                holdout.get("generatorSha256"));
        assertEquals("d78ad0ffd78000a70cd042f21d43f956405b8d334a46960285dd7342412b21f8",
                holdout.get("protocolSha256"));
        assertEquals(legacy.fields().get("features"),manifest.fields().get("features"));
        assertEquals(legacy.fields().get("capabilities"),manifest.fields().get("capabilities"));
        assertEquals(legacy.fields().get("parameterGrid"),manifest.fields().get("parameterGrid"));
        assertEquals(legacy.fields().get("acceptanceOwners"),manifest.fields().get("acceptanceOwners"));
        assertEquals(512,((Number)manifest.fields().get("maxScenes")).intValue());
        assertEquals(3600,((Number)manifest.fields().get("maxFitSeconds")).intValue());
        assertEquals(Boolean.TRUE,manifest.fields().get("existingAttemptCaps"));
        assertTrue(manifest.fields().containsKey("holdoutPolicy"));
    }

    @Test void prospectiveSchemaTwoRejectsMissingUnknownAndAlteredPolicyFields() throws Exception {
        for(String field:new String[]{"selectionProtocol","selectCohort","rendererRevision",
                "retiredSelectScope","trainingControlOrder","holdoutPolicy"})
            assertRejectsProspective(m->m.remove(field));
        assertRejectsProspective(m->m.put("unregisteredPolicy",true));
        assertRejectsProspective(m->m.put("selectionProtocol","train-score-only-v1"));
        assertRejectsProspective(m->m.put("selectCohort","old-select"));
        assertRejectsProspective(m->m.put("rendererRevision","native-observations-production-resample-v5"));
        assertRejectsProspective(m->m.put("retiredSelectScope","diagnostic"));
        assertRejectsProspective(m->object(m.get("trainingControlOrder")).remove("cutoff"));
        assertRejectsProspective(m->object(m.get("trainingControlOrder"))
                .put("configurationGeometry","geometry-first"));
        assertRejectsProspective(m->object(m.get("trainingControlOrder"))
                .put("unknownControl","ignored"));
        assertRejectsProspective(m->object(m.get("holdoutPolicy")).remove("publicIndexSha256"));
        assertRejectsProspective(m->object(m.get("holdoutPolicy"))
                .put("publicIndexResource","/private/holdout-index.json"));
        assertRejectsProspective(m->object(m.get("holdoutPolicy"))
                .put("publicIndexSha256","0".repeat(64)));
        assertRejectsProspective(m->object(m.get("holdoutPolicy"))
                .put("publicIndexBytes",19959));
        assertRejectsProspective(m->object(m.get("holdoutPolicy"))
                .put("payloadSha256","private-payload"));
        assertRejectsProspective(m->object(m.get("holdoutPolicy"))
                .put("payloadBytes",65492));
        assertRejectsProspective(m->object(m.get("holdoutPolicy"))
                .put("unexpectedPayload","seed material is forbidden"));
        assertRejectsProspective(m->m.put("schema",1));
        assertRejectsProspective(m->m.put("familyId","joint-core-excess-v1"));
        assertRejectsProspective(m->m.put("featureVersion","b-identification-facts-2"));
        assertRejectsProspective(m->object(m.get("equations"))
                .put("joint",((String)object(m.get("equations")).get("joint")).replace("c_m=original RAW center","c_m=(a_m+z_m)/2")));
        assertRejectsProspective(m->object(m.get("equations")).put("selection","best4 on SELECT"));
        assertRejectsProspective(m->object(m.get("equations")).put("zeroSupport","zero is missing"));
        assertRejectsProspective(m->object(m.get("units")).put("E","metre"));
        assertRejectsProspective(m->object(m.get("capabilities")).put("native","assumed pitch"));
        assertRejectsProspective(m->object(m.get("acceptanceOwners")).put("OBS-12","unowned"));
    }

    @Test void prospectiveSchemaTwoPinsAreaLawUnitsGridAndExistingCaps() throws Exception {
        var manifest=BIdentificationManifest.read(prospectiveDraftBytes());
        @SuppressWarnings("unchecked") var equations=(java.util.Map<String,Object>)manifest.fields().get("equations");
        String joint=(String)equations.get("joint");
        for(String required:new String[]{"c_m=original RAW center","h_m=(z_m-a_m)/2+v_m+rho_m/2",
                "E_m=sum_s e_ms*Delta_s/L0","Z_m=sum_s V_ms*T_ms*Delta_s",
                "f_ms=V_ms*T_ms/Z_m","H_m=G*(1+B*d_m)*E_m","q_m=R*H_m/D",
                "p_s=q_0*U_s+sum_m q_m*f_ms+sum_c q_c*f_cs"}) assertTrue(joint.contains(required));
        assertFalse(joint.contains("c_m=(a_m+z_m)/2"));
        String zeroSupport=(String)equations.get("zeroSupport");
        assertTrue(zeroSupport.indexOf("Missing required original receipts")
                < zeroSupport.indexOf("zero available own-shoulder cells"));
        assertTrue(zeroSupport.indexOf("zero available own-shoulder cells")
                < zeroSupport.indexOf("Z=0"));
        assertTrue(zeroSupport.indexOf("Z=0") < zeroSupport.indexOf("E=0"));
        assertTrue(((String)equations.get("selection")).contains("all32 configurations in original TRAIN catalog order"));
        assertTrue(((String)equations.get("selection")).contains("Lower geometry after the four-feasible cutoff remains UNTESTED"));
        assertTrue(((String)equations.get("selection")).contains("Any encountered error aborts the entire run"));
        assertTrue(((String)equations.get("selection")).contains("baseline comparison within 1e-12"));
        @SuppressWarnings("unchecked") var units=(java.util.Map<String,Object>)manifest.fields().get("units");
        assertEquals("metre",units.get("L0"));
        assertEquals("metre",units.get("Z"));
        assertEquals("dimensionless",units.get("E"));
        assertEquals("dimensionless",units.get("H,q"));
        assertEquals("1/metre",units.get("A,f,p,U"));
        assertEquals("inverse normalized scalar",units.get("G"));

        @SuppressWarnings("unchecked") var grid=(java.util.List<java.util.Map<String,Object>>)manifest.fields().get("parameterGrid");
        var actual=new java.util.HashSet<String>();
        for(var row:grid) actual.add(row.get("G")+":"+row.get("lambda")+":"+row.get("B"));
        assertEquals(32,grid.size());
        for(int gain:new int[]{1,4,16,64}) for(int lambda:new int[]{1,2})
            for(int span:new int[]{0,1,3,7}) assertTrue(actual.contains(gain+":"+lambda+":"+span));
        assertEquals(512,((Number)manifest.fields().get("maxScenes")).intValue());
        assertEquals(3600,((Number)manifest.fields().get("maxFitSeconds")).intValue());
        assertEquals(Boolean.TRUE,manifest.fields().get("existingAttemptCaps"));
        assertEquals("b-observation-identification/results",manifest.fields().get("resultDirectory"));
    }

    @Test void prospectiveSchemaTwoRejectsChangedGridColumnsAndBoundsAndFreezesNestedPolicies() throws Exception {
        assertRejectsProspective(m->{
            @SuppressWarnings("unchecked") var grid=(java.util.List<java.util.Map<String,Object>>)m.get("parameterGrid");
            grid.set(1,new java.util.LinkedHashMap<>(grid.get(0)));
        });
        assertRejectsProspective(m->{
            @SuppressWarnings("unchecked") var grid=(java.util.List<java.util.Map<String,Object>>)m.get("parameterGrid");
            for(var row:grid) row.put("C",0);
        });
        assertRejectsProspective(m->m.put("maxScenes",513));
        assertRejectsProspective(m->m.put("maxFitSeconds",3601));
        assertRejectsProspective(m->m.put("existingAttemptCaps",false));

        var manifest=BIdentificationManifest.read(prospectiveDraftBytes());
        assertThrows(UnsupportedOperationException.class,()->manifest.fields().put("selectionProtocol","changed"));
        assertThrows(UnsupportedOperationException.class,()->object(manifest.fields().get("trainingControlOrder"))
                .put("cutoff","changed"));
        assertThrows(UnsupportedOperationException.class,()->object(manifest.fields().get("holdoutPolicy"))
                .put("payloadBytes",1));
        assertThrows(UnsupportedOperationException.class,()->object(manifest.fields().get("equations"))
                .put("selection","changed"));
    }

    private static void assertRejectsProspective(java.util.function.Consumer<java.util.Map<String,Object>> mutation)
            throws Exception {
        assertThrows(Exception.class,()->BIdentificationManifest.read(prospectiveDraftBytes(mutation)));
    }

    private static byte[] prospectiveDraftBytes() throws Exception {
        return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                "superpowers/docs/plans/2026-10-18-b-observation-feasibility-manifest.json"));
    }

    private static byte[] prospectiveDraftBytes(
            java.util.function.Consumer<java.util.Map<String,Object>> mutation) throws Exception {
        var values=mutableMap(org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                .ObservationManifestJson.parse(prospectiveDraftBytes()));
        mutation.accept(values);
        return (BIdentificationReceipt.canonical(values)+"\n").getBytes(StandardCharsets.UTF_8);
    }

    private static java.util.Map<String,Object> mutableMap(java.util.Map<String,Object> original) {
        var copy=new java.util.LinkedHashMap<String,Object>();
        original.forEach((key,value)->copy.put(key,deepCopy(value)));
        return copy;
    }

    private static Object deepCopy(Object value) {
        if(value instanceof java.util.Map<?,?> map) {
            var copy=new java.util.LinkedHashMap<String,Object>();
            map.forEach((key,item)->copy.put((String)key,deepCopy(item)));
            return copy;
        }
        if(value instanceof java.util.List<?> list) {
            var copy=new java.util.ArrayList<Object>();
            list.forEach(item->copy.add(deepCopy(item)));
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String,Object> object(Object value) {
        return (java.util.Map<String,Object>)value;
    }
    @Test void candidateSealRejectsChangedEquationsGridAndSourceBeforeEvaluation() throws Exception {
        var input=java.nio.file.Path.of("superpowers/docs/plans/2026-10-18-b-observation-feasibility-manifest.json");
        String current=java.nio.file.Files.readString(input);
        var altered=temporary.resolve("manifest.json");
        java.nio.file.Files.writeString(altered,current.replace("area-presence-core-position-v2","unreviewed-family"));
        var changedFamily=temporary.resolve("changed-family.json");
        java.nio.file.Files.copy(altered,changedFamily);
        java.nio.file.Files.writeString(altered,current);
        // Old authority remains immutable and cannot seal current optimized/instrumented source.
        assertThrows(IllegalArgumentException.class,()->BIdentificationManifest.register(altered,BIdentificationReceipt.sourceHash()));
        java.nio.file.Files.writeString(altered,current+" ");
        assertAll(
                ()->assertThrows(Exception.class,()->BIdentificationManifest.register(changedFamily,BIdentificationReceipt.sourceHash())),
                ()->assertThrows(Exception.class,()->BIdentificationManifest.register(input,"0".repeat(64))),
                ()->assertThrows(Exception.class,()->BIdentificationManifest.register(altered,BIdentificationReceipt.sourceHash())));
    }
    private record RegistryFixture(java.util.Map<String,Object> registry, byte[] registryBytes,
            java.util.Map<String,String> sourceMembers, byte[] manifestBytes, String catalogHash,
            byte[] publicIndexBytes, java.util.Map<String,Object> holdoutPolicy) { }

    private static RegistryFixture registryFixture() throws Exception {
        assertEquals(java.util.Set.of(
                "service/evidence/LocalScalarProfileExtractor.java",
                "service/tracing/probabilistic/ProbabilisticObservationModel.java",
                "service/tracing/probabilistic/ProbabilisticProfileFactory.java",
                "service/tracing/probabilistic/ProbabilisticStateBuilder.java",
                "service/tracing/probabilistic/ProbabilisticLatticeAdmission.java",
                "service/tracing/probabilistic/ProbabilisticContinuationProposals.java",
                "service/tracing/probabilistic/ProbabilisticInference.java",
                "service/tracing/probabilistic/TransitionAdmissionMemo.java",
                "service/tracing/probabilistic/IndexedGeometryAdmissions.java",
                "service/tracing/probabilistic/InferenceProfile.java",
                "service/tracing/probabilistic/ProbabilisticTraceEngine.java",
                "service/tracing/probabilistic/EvidenceModelParameters.java",
                "service/tracing/probabilistic/BIdentificationFacts.java",
                "service/tracing/probabilistic/BIdentificationFactsExtractor.java",
                "service/tracing/probabilistic/BObservationKernel.java",
                "service/tracing/probabilistic/BIdentifiedObservationKernel.java",
                "service/tracing/probabilistic/BObservationMixture.java",
                "service/tracing/probabilistic/BObservationAdapter.java",
                "service/tracing/probabilistic/BObservationGeometry.java",
                "service/tracing/probabilistic/BIdentificationCatalog.java",
                "service/tracing/probabilistic/BIdentificationScenes.java",
                "service/tracing/probabilistic/BIdentificationScore.java",
                "service/tracing/probabilistic/BIdentificationAcceptance.java",
                "service/tracing/probabilistic/BIdentificationPairCache.java",
                "service/tracing/probabilistic/BIdentificationManifest.java",
                "service/tracing/probabilistic/BIdentificationReceipt.java",
                "service/tracing/probabilistic/BObservationIdentificationTool.java"),
                new java.util.HashSet<>(BIdentificationReceipt.SOURCE_FILES));
        var sourceMembers=new java.util.TreeMap<String,String>();
        for(String member:BIdentificationReceipt.SOURCE_FILES) sourceMembers.put(member,"a".repeat(64));
        byte[] manifestBytes=bytes("{\"schema\":2,\"familyId\":\"synthetic-registry-test\"}");
        String catalogHash="b".repeat(64);
        byte[] publicIndexBytes=(BIdentificationReceipt.canonical(java.util.Map.of(
                "entries",java.util.List.of(),"schema",1))+"\n").getBytes(StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked") var holdoutPolicy=new java.util.LinkedHashMap<String,Object>(
                (java.util.Map<String,Object>)BIdentificationManifest.read(java.nio.file.Files.readAllBytes(
                        java.nio.file.Path.of("superpowers/docs/plans/2026-10-18-b-observation-feasibility-manifest.json")))
                        .fields().get("holdoutPolicy"));
        var registry=registryMap(sourceMembers,manifestBytes,catalogHash,publicIndexBytes,holdoutPolicy);
        byte[] registryBytes=(BIdentificationReceipt.canonical(registry)+"\n").getBytes(StandardCharsets.UTF_8);
        return new RegistryFixture(registry,registryBytes,sourceMembers,manifestBytes,catalogHash,
                publicIndexBytes,holdoutPolicy);
    }

    private static java.util.Map<String,Object> registryMap(java.util.Map<String,String> sourceMembers,
            byte[] manifestBytes,String catalogHash,byte[] publicIndexBytes,
            java.util.Map<String,Object> holdoutPolicy) {
        var registry=new java.util.LinkedHashMap<String,Object>();
        registry.put("schema",1);
        registry.put("sourceMembers",new java.util.TreeMap<>(sourceMembers));
        registry.put("sourceSha256",BIdentificationReceipt.hash(BIdentificationReceipt.canonical(sourceMembers)
                .getBytes(StandardCharsets.UTF_8)));
        registry.put("manifestSha256",BIdentificationReceipt.hash(manifestBytes));
        registry.put("catalogHash",catalogHash);
        registry.put("publicIndexSha256",BIdentificationReceipt.hash(publicIndexBytes));
        registry.put("publicIndexBytes",publicIndexBytes.length);
        registry.put("holdoutPolicy",holdoutPolicy);
        return registry;
    }

    private static byte[] registryBytes(RegistryFixture f,java.util.function.Consumer<java.util.Map<String,Object>> change)
            throws Exception {
        var copy=mutableMap(f.registry());
        change.accept(copy);
        return (BIdentificationReceipt.canonical(copy)+"\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void assertRegistryRejected(RegistryFixture f,byte[] registryBytes,
            java.util.Map<String,String> members,byte[] manifest,String catalog,byte[] publicIndex,
            java.util.Map<String,Object> holdout) {
        assertThrows(Exception.class,()->BIdentificationManifest.validateReviewedSourceRegistry(
                registryBytes,members,manifest,catalog,publicIndex,holdout));
    }

    @Test void reviewedRegistryValidatesSyntheticActualBindingsWithoutRegistrationAuthority() throws Exception {
        RegistryFixture f=registryFixture();
        var binding=BIdentificationManifest.validateReviewedSourceRegistry(f.registryBytes(),f.sourceMembers(),
                f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy());
        assertNotNull(binding);
        assertEquals(BIdentificationReceipt.hash(f.registryBytes()),binding.registryHash());
        assertEquals(BIdentificationReceipt.hash(f.manifestBytes()),binding.manifestSha256());
        assertEquals(f.registry().get("sourceSha256"),binding.sourceSha256());
        assertEquals(f.catalogHash(),binding.catalogHash());
        assertEquals(BIdentificationReceipt.hash(f.publicIndexBytes()),binding.publicIndexSha256());
        assertEquals(f.publicIndexBytes().length,binding.publicIndexBytes());
        assertEquals(27,binding.sourceMembers().size());
        assertEquals(f.sourceMembers(),binding.sourceMembers());
        assertEquals(f.holdoutPolicy(),binding.holdoutPolicy());
    }

    @Test void reviewedRegistryRejectsEveryChangedBindingAndMemberInventoryShape() throws Exception {
        RegistryFixture f=registryFixture();
        assertAll(
                ()->assertRegistryRejected(f,registryBytes(f,m->m.put("manifestSha256","0".repeat(64))),
                f.sourceMembers(),f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()),
                ()->assertRegistryRejected(f,registryBytes(f,m->m.put("sourceSha256","0".repeat(64))),
                f.sourceMembers(),f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()),
                ()->assertRegistryRejected(f,registryBytes(f,m->m.put("catalogHash","0".repeat(64))),
                f.sourceMembers(),f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()),
                ()->assertRegistryRejected(f,registryBytes(f,m->m.put("publicIndexSha256","0".repeat(64))),
                f.sourceMembers(),f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()),
                ()->assertRegistryRejected(f,registryBytes(f,m->m.put("publicIndexBytes",f.publicIndexBytes().length+1)),
                f.sourceMembers(),f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()),
                ()->assertRegistryRejected(f,registryBytes(f,m->{
            @SuppressWarnings("unchecked") var policy=(java.util.Map<String,Object>)m.get("holdoutPolicy");
            policy.put("payloadBytes",1);
        }),f.sourceMembers(),f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()),
                ()->assertRegistryRejected(f,registryBytes(f,m->{
                    @SuppressWarnings("unchecked") var members=(java.util.Map<String,String>)m.get("sourceMembers");
                    members.put(BIdentificationReceipt.SOURCE_FILES.get(0),"0".repeat(64));
                }),f.sourceMembers(),f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()),
                ()->assertRegistryRejected(f,registryBytes(f,m->{
                    @SuppressWarnings("unchecked") var members=(java.util.Map<String,String>)m.get("sourceMembers");
                    members.remove(BIdentificationReceipt.SOURCE_FILES.get(0));
                }),f.sourceMembers(),f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()),
                ()->assertRegistryRejected(f,registryBytes(f,m->{
                    @SuppressWarnings("unchecked") var members=(java.util.Map<String,String>)m.get("sourceMembers");
                    members.put("service/tracing/probabilistic/Unexpected.java","0".repeat(64));
                }),f.sourceMembers(),f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()),
                ()->assertRegistryRejected(f,f.registryBytes(),f.sourceMembers(),bytes("changed manifest"),f.catalogHash(),
                        f.publicIndexBytes(),f.holdoutPolicy()),
                ()->assertRegistryRejected(f,f.registryBytes(),f.sourceMembers(),f.manifestBytes(),"c".repeat(64),
                        f.publicIndexBytes(),f.holdoutPolicy()),
                ()->assertRegistryRejected(f,f.registryBytes(),f.sourceMembers(),f.manifestBytes(),f.catalogHash(),
                        bytes("{\"entries\":[],\"schema\":2}\n"),f.holdoutPolicy()),
                ()->{var changedMember=new java.util.TreeMap<>(f.sourceMembers());
                    changedMember.put(BIdentificationReceipt.SOURCE_FILES.get(0),"f".repeat(64));
                    assertRegistryRejected(f,f.registryBytes(),changedMember,f.manifestBytes(),f.catalogHash(),
                            f.publicIndexBytes(),f.holdoutPolicy());},
                ()->{var missingMember=new java.util.TreeMap<>(f.sourceMembers());
                    missingMember.remove(BIdentificationReceipt.SOURCE_FILES.get(0));
                    assertRegistryRejected(f,f.registryBytes(),missingMember,f.manifestBytes(),f.catalogHash(),
                            f.publicIndexBytes(),f.holdoutPolicy());},
                ()->{var extraMember=new java.util.TreeMap<>(f.sourceMembers());
                    extraMember.put("service/tracing/probabilistic/Unregistered.java","0".repeat(64));
                    assertRegistryRejected(f,f.registryBytes(),extraMember,f.manifestBytes(),f.catalogHash(),
                            f.publicIndexBytes(),f.holdoutPolicy());},
                ()->assertRegistryRejected(f,registryBytes(f,m->m.put("unexpected","value")),f.sourceMembers(),
                        f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()),
                ()->assertRegistryRejected(f,registryBytes(f,m->m.remove("sourceMembers")),f.sourceMembers(),
                        f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()));
    }

    @Test void reviewedRegistryRejectsMalformedDuplicateAndOversizedJson() throws Exception {
        RegistryFixture f=registryFixture();
        assertAll(
        ()->assertRegistryRejected(f,new byte[0],f.sourceMembers(),f.manifestBytes(),f.catalogHash(),
                f.publicIndexBytes(),f.holdoutPolicy()),
        ()->assertRegistryRejected(f,bytes("{"),f.sourceMembers(),f.manifestBytes(),f.catalogHash(),
                f.publicIndexBytes(),f.holdoutPolicy()),
        ()->assertRegistryRejected(f,bytes("{\"schema\":1,\"schema\":1}"),f.sourceMembers(),f.manifestBytes(),
                f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()),
        ()->assertRegistryRejected(f,new byte[65_537],f.sourceMembers(),f.manifestBytes(),f.catalogHash(),
                f.publicIndexBytes(),f.holdoutPolicy()),
        ()->assertRegistryRejected(f,registryBytes(f,m->m.put("schema",2)),f.sourceMembers(),f.manifestBytes(),
                f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy()));
    }

    @Test void reviewedRegistryResultDeeplyFreezesMemberAndHoldoutMetadata() throws Exception {
        RegistryFixture f=registryFixture();
        var binding=BIdentificationManifest.validateReviewedSourceRegistry(f.registryBytes(),f.sourceMembers(),
                f.manifestBytes(),f.catalogHash(),f.publicIndexBytes(),f.holdoutPolicy());
        assertThrows(UnsupportedOperationException.class,()->binding.sourceMembers().put("fake","0".repeat(64)));
        assertThrows(UnsupportedOperationException.class,()->binding.holdoutPolicy().put("kind","changed"));
    }

    @Test void registrySealCarriesHashAndRejectsRegistryMutation() throws Exception {
        java.util.Set<String> sealFields=java.util.Arrays.stream(BIdentificationManifest.Seal.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
        assertTrue(sealFields.containsAll(java.util.Set.of("registryPath","registryHash")));
        var registry=temporary.resolve("reviewed-source.json");
        byte[] original=bytes("{\"schema\":1}");
        java.nio.file.Files.write(registry,original);
        String expected=BIdentificationReceipt.hash(original);
        BIdentificationManifest.verifyRegistryUnchanged(registry,expected);
        java.nio.file.Files.write(registry,bytes("{\"schema\":1,\"changed\":true}"));
        assertThrows(Exception.class,()->BIdentificationManifest.verifyRegistryUnchanged(registry,expected));
    }

}

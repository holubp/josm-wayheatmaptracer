package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Purpose-specific prospective authority; old registration and protocols remain unchanged. */
final class BProspectiveIdentificationManifest {
    enum Purpose { OPTIMIZED_TRAIN_DIAGNOSTIC, PROSPECTIVE_FIT }
    static final String PENDING="PENDING_INDEPENDENT_APPROVAL";
    static final String APPROVED="APPROVED_AFTER_INDEPENDENT_REVIEW_AND_CONTROLLER_RULING";
    static final Path ROOT=Path.of("superpowers/docs/plans");
    static final Path REGISTRY=ROOT.resolve("2026-10-18-b-observation-prospective-source.json");
    static final Path PINS=ROOT.resolve("2026-10-18-b-observation-prospective-pins.json");
    static final List<String> PROTOCOL_FILES=List.of(
            "src/test/java/"+BIdentificationReceipt.PACKAGE+"service/tracing/probabilistic/BProspectiveIdentificationManifest.java",
            "src/test/java/"+BIdentificationReceipt.PACKAGE+"service/tracing/probabilistic/BObservationOptimizedTrainRuntimeDiagnosticTool.java",
            "src/test/java/"+BIdentificationReceipt.PACKAGE+"service/tracing/probabilistic/BProspectiveObservationIdentificationTool.java",
            "src/test/java/"+BIdentificationReceipt.PACKAGE+"service/tracing/probabilistic/BObservationTrainRuntimeDiagnosticTool.java",
            "src/test/java/"+BIdentificationReceipt.PACKAGE+"service/tracing/probabilistic/BObservationRuntimeTelemetry.java");
    static final List<String> PREFIX=List.of(
            "sparse-crossing-noisy-strands-vs-persistent-parallel/train/v0/right",
            "sparse-crossing-noisy-strands-vs-persistent-parallel/train/v4/right");
    static final String ORDER="fixed-v0-right-v4-right-then-selector-first-pitch-desc-variant-contrast-side-v1";
    static Path policyPath(Purpose purpose) {
        return ROOT.resolve(purpose==Purpose.OPTIMIZED_TRAIN_DIAGNOSTIC
                ?"2026-10-18-b-observation-optimized-runtime-policy.json":"2026-10-18-b-observation-prospective-fit-policy.json");
    }
    static Map<String,Object> fixedPolicy(Purpose purpose) {
        var fields=new LinkedHashMap<String,Object>();
        fields.put("schema",1);fields.put("purpose",purpose.name());
        fields.put("baseManifestSha256",BObservationTrainRuntimeDiagnosticTool.OLD_MANIFEST);
        fields.put("oldRegistrySha256",BObservationTrainRuntimeDiagnosticTool.OLD_REGISTRY);
        fields.put("oldSourceSha256",BObservationTrainRuntimeDiagnosticTool.OLD_SOURCE);
        fields.put("oldDiagnosticProtocolSha256","3c9e872713736d1f475e3c9983bdf5be01e71dc88e89f24133a8cff241fd171d");
        fields.put("memoryBytes",67_108_864L);fields.put("diskBytes",2_147_483_648L);
        fields.put("attemptCaps","TraceBudgets.defaults-unchanged");fields.put("holdoutAuthority",false);
        fields.put("rendererSha256","a7e754e04b1b6f631b5060cbf07fca0c3615536fe6baaa3b22941bd0e59fc2bc");
        if(purpose==Purpose.OPTIMIZED_TRAIN_DIAGNOSTIC) {
            fields.put("catalogHash","97effd5ff2e8ec9e37359ab3e939fa523556238521cf095a624ad584aef9c72d");
            fields.put("caseIds",BObservationTrainRuntimeDiagnosticTool.CASES);fields.put("gridOrdinal",0);
            fields.put("watchdogNanos",300_000_000_000L);fields.put("selectionAuthority","NONE");
            fields.put("maxSnapshots",128);fields.put("maxTelemetryBytes",1_048_576);fields.put("timingSchema",1);
            fields.put("scratchPolicy","UNCHANGED_ORDINARY_RECEIPTS_AND_PAIR_CACHE_NEVER_UPLOAD");
        } else {
            fields.put("catalogHash","debec23907badfaa467fac91cb0da5f3d8771e357ea1faa6f618d60c852d7557");
            fields.put("watchdogNanos",3_600_000_000_000L);fields.put("geometryPrefix",PREFIX);
            fields.put("trainingControlOrder",ORDER);fields.put("scoreAccumulation","original-TRAIN-catalog-order");
            fields.put("configurationGeometry","score-G-lambda-B");fields.put("configurations",32);
            fields.put("selectionProtocol","train-feasible-fixed-prefix-top4-score-order-v3");
            fields.put("selectCohort","select-polynomial-v1");
            fields.put("skippedErrors","UNVISITED_UNKNOWN_NOT_PROVED_ABSENT");
            fields.put("encounteredFailure","abort-entire-run-no-candidate-no-clock-reset");
        }
        return Collections.unmodifiableMap(fields);
    }
    static void validatePolicy(Purpose purpose,Map<String,Object> fields) {
        if(purpose==null||fields==null||!BIdentificationReceipt.canonical(fixedPolicy(purpose))
                .equals(BIdentificationReceipt.canonical(fields)))
            throw new IllegalArgumentException("Exact fixed prospective purpose/policy required");
    }
    static void requireExecutionApproved(String status) {
        if(!APPROVED.equals(status))throw new SecurityException("Prospective execution is pending independent approval");
    }
    static void validatePins(Map<String,Object> pins,String registryHash,Map<String,String> policyHashes) {
        if(pins==null||!pins.keySet().equals(Set.of("schema","approval","sourceRegistrySha256","policySha256"))
                ||!schemaOne(pins.get("schema"))||!(PENDING.equals(pins.get("approval"))||APPROVED.equals(pins.get("approval")))
                ||!Objects.equals(registryHash,pins.get("sourceRegistrySha256"))||!policyHashes.equals(pins.get("policySha256")))
            throw new IllegalArgumentException("Exact external prospective pins required");
    }
    @SuppressWarnings("unchecked")
    static void validateRegistry(Map<String,Object> registry,Map<String,String> core,Map<String,String> helpers,
            Map<String,String> policyHashes,byte[] baseBytes,byte[] indexBytes) throws Exception {
        if(registry==null||!registry.keySet().equals(Set.of("schema","core","protocolMembers","sourceSha256","policySha256"))
                ||!schemaOne(registry.get("schema"))||!(registry.get("core") instanceof Map<?,?> raw)
                ||!helpers.keySet().equals(new HashSet<>(PROTOCOL_FILES))||!helpers.equals(registry.get("protocolMembers"))
                ||helpers.values().stream().anyMatch(h->h==null||!h.matches("[0-9a-f]{64}"))
                ||!policyHashes.equals(registry.get("policySha256"))
                ||!BObservationTrainRuntimeDiagnosticTool.OLD_MANIFEST.equals(BIdentificationReceipt.hash(baseBytes)))
            throw new IllegalArgumentException("Exact prospective registry inventory required");
        var family=BIdentificationManifest.read(baseBytes);
        var catalog=BIdentificationCatalog.publicCatalog(indexBytes);
        Map<String,Object> coreFields=(Map<String,Object>)raw;
        BIdentificationManifest.validateReviewedSourceRegistry(canonicalBytes(coreFields),core,baseBytes,
                catalog.canonicalHash(),indexBytes,(Map<String,Object>)family.fields().get("holdoutPolicy"));
        String source=effectiveSourceHash((String)coreFields.get("sourceSha256"),helpers);
        if(!source.equals(registry.get("sourceSha256")))throw new IllegalArgumentException("Prospective effective source changed");
    }
    private static boolean schemaOne(Object value) {
        return value instanceof Number n&&n.longValue()==1&&n.doubleValue()==1.0;
    }
    private static byte[] canonicalBytes(Map<String,?> fields) {
        return (BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8);
    }
    private static String effectiveSourceHash(String core,Map<String,String> helpers) {
        return BIdentificationReceipt.hash(BIdentificationReceipt.canonical(Map.of("coreSourceSha256",core,
                "protocolMembers",helpers)).getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String,Object> readCanonical(Path path) throws Exception {
        return parseCanonical(read(path,65_536));
    }
    private static Map<String,Object> parseCanonical(byte[] bytes) throws Exception {
        var fields=BIdentificationReceipt.parse(bytes);
        if(!Arrays.equals(bytes,canonicalBytes(fields)))throw new IllegalArgumentException("Prospective JSON must be canonical");
        return fields;
    }
    private static byte[] read(Path path,int limit) throws Exception {
        return BObservationTrainRuntimeDiagnosticTool.readBounded(path,limit);
    }
    static Map<String,String> currentCoreMembers() throws Exception {
        var result=new TreeMap<String,String>();
        for(String relative:BIdentificationReceipt.SOURCE_FILES) {
            Path main=Path.of("src/main/java",BIdentificationReceipt.PACKAGE+relative);
            Path test=Path.of("src/test/java",BIdentificationReceipt.PACKAGE+relative);
            if(Files.exists(main)&&Files.exists(test))throw new IllegalArgumentException("Duplicate core source path");
            result.put(relative,BIdentificationReceipt.hash(read(Files.exists(main)?main:test,1_048_576)));
        }
        return Collections.unmodifiableMap(result);
    }
    static Map<String,String> currentProtocolMembers() throws Exception {
        var result=new TreeMap<String,String>();
        for(String path:PROTOCOL_FILES)result.put(path,BIdentificationReceipt.hash(read(Path.of(path),1_048_576)));
        return Collections.unmodifiableMap(result);
    }
    private static byte[] publicIndex() throws Exception {
        try(var stream=BIdentificationCatalog.class.getResourceAsStream("/b-observation-identification/holdout-index.json")) {
            if(stream==null)throw new IllegalArgumentException("Prospective public index missing");
            byte[] bytes=stream.readNBytes(65_537);
            if(bytes.length==0||bytes.length>65_536||stream.read()!=-1)throw new IllegalArgumentException("Public index size bound");
            return bytes;
        }
    }
    static final class Seal {
        private final Purpose purpose;
        private final byte[] initialPins;
        private final String expectedPinsHash,protocolHash,sourceHash,catalogHash,registryHash,approval;
        private final BIdentificationManifest family;
        private final Map<String,Object> launchContext;
        private Seal(Purpose purpose,byte[] pins,String expectedPinsHash,String protocolHash,String sourceHash,
                String catalogHash,String registryHash,String approval,BIdentificationManifest family) {
            this.purpose=purpose;this.initialPins=pins.clone();this.expectedPinsHash=expectedPinsHash;this.protocolHash=protocolHash;
            this.sourceHash=sourceHash;this.catalogHash=catalogHash;this.registryHash=registryHash;this.approval=approval;this.family=family;
            this.launchContext=BProspectiveIdentificationManifest.launchContext(System.getenv(),purpose);
        }
        Purpose purpose(){return purpose;} String manifestHash(){return protocolHash;} String sourceHash(){return sourceHash;}
        String catalogHash(){return catalogHash;} String registryHash(){return registryHash;} String pinsHash(){return expectedPinsHash;}
        String approval(){return approval;} BIdentificationManifest family(){return family;}
        Map<String,Object> provenance() {
            return Map.of("protocolPurpose",purpose.name(),"sourceRegistryHash",registryHash,"executionPinsHash",expectedPinsHash,
                    "controllerApproval",approval,"launchContext",launchContext);
        }
        void verifyUnchanged() throws Exception {
            var now=registerMetadata(purpose,expectedPinsHash);
            if(!Arrays.equals(initialPins,now.initialPins)||!protocolHash.equals(now.protocolHash)
                    ||!sourceHash.equals(now.sourceHash)||!catalogHash.equals(now.catalogHash)||!registryHash.equals(now.registryHash)
                    ||!approval.equals(now.approval))throw new IllegalStateException("Prospective binding changed during execution");
        }
        void requireExecutionApproved() throws Exception {
            verifyUnchanged();BProspectiveIdentificationManifest.requireExecutionApproved(approval);
            if(launchContext.isEmpty())throw new SecurityException("Empirical execution requires a bound manual CI launch");
        }
    }
    /** Select only bounded safe CI provenance; environment strings are claims, not review proof. */
    static Map<String,Object> launchContext(Map<String,String> environment,Purpose purpose) {
        if(!"true".equals(environment.get("GITHUB_ACTIONS"))||!"workflow_dispatch".equals(environment.get("GITHUB_EVENT_NAME")))return Map.of();
        String repository=environment.getOrDefault("GITHUB_REPOSITORY",""),run=environment.getOrDefault("GITHUB_RUN_ID","");
        String commit=environment.getOrDefault("GITHUB_SHA",""),workflow=environment.getOrDefault("GITHUB_WORKFLOW_REF","");
        String attempt=environment.getOrDefault("GITHUB_RUN_ATTEMPT","");
        if(!repository.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")||!run.matches("[0-9]{1,20}")
                ||!commit.matches("[0-9a-f]{40}")||!attempt.equals("1"))return Map.of();
        String prefix=repository+"/.github/workflows/";
        if(!workflow.startsWith(prefix))return Map.of();
        String suffix=workflow.substring(prefix.length());
        String own=purpose==Purpose.OPTIMIZED_TRAIN_DIAGNOSTIC?"observation-train-runtime-optimized.yml":"observation-prospective-fit.yml";
        if(!(suffix.startsWith("build.yml@refs/heads/")||suffix.startsWith(own+"@refs/heads/"))
                ||!suffix.matches("[A-Za-z0-9_.-]+@refs/heads/[A-Za-z0-9_./-]{1,200}"))return Map.of();
        return Map.of("repository",repository,"runId",run,"commit",commit,"workflowRef",workflow,"runAttempt",attempt);
    }
    /** Validates actual metadata only. PENDING seals cannot authorize any empirical route. */
    @SuppressWarnings("unchecked")
    static Seal registerMetadata(Purpose purpose,String expectedPinsHash) throws Exception {
        if(purpose==null||expectedPinsHash==null||!expectedPinsHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Independent external pins digest required");
        byte[] pinBytes=read(PINS,65_536);
        if(!expectedPinsHash.equals(BIdentificationReceipt.hash(pinBytes)))throw new IllegalArgumentException("External prospective pins changed");
        var pins=parseCanonical(pinBytes);
        byte[] baseBytes=read(BObservationTrainRuntimeDiagnosticTool.MANIFEST,65_536);
        if(!BObservationTrainRuntimeDiagnosticTool.OLD_MANIFEST.equals(BIdentificationReceipt.hash(baseBytes))
                ||!BObservationTrainRuntimeDiagnosticTool.OLD_REGISTRY.equals(BIdentificationReceipt.hash(read(BIdentificationManifest.REVIEWED_SOURCE_REGISTRY_PATH,65_536)))
                ||!fixedPolicy(purpose).get("oldDiagnosticProtocolSha256").equals(BIdentificationReceipt.hash(read(BObservationTrainRuntimeDiagnosticTool.PROTOCOL,65_536))))
            throw new IllegalArgumentException("Preserved original binding changed");
        var policies=new TreeMap<String,String>();
        for(var p:Purpose.values()) {
            var fields=readCanonical(policyPath(p));validatePolicy(p,fields);
            policies.put(p.name(),BIdentificationReceipt.hash(canonicalBytes(fields)));
        }
        byte[] registryBytes=read(REGISTRY,65_536);String registryHash=BIdentificationReceipt.hash(registryBytes);
        var registry=parseCanonical(registryBytes);validatePins(pins,registryHash,policies);
        var core=currentCoreMembers();var helpers=currentProtocolMembers();byte[] index=publicIndex();
        validateRegistry(registry,core,helpers,policies,baseBytes,index);
        var family=BIdentificationManifest.read(baseBytes);
        String catalog=purpose==Purpose.OPTIMIZED_TRAIN_DIAGNOSTIC?BIdentificationCatalog.trainDiagnosticCatalog().canonicalHash()
                :BIdentificationCatalog.publicCatalog(index).canonicalHash();
        if(!catalog.equals(fixedPolicy(purpose).get("catalogHash"))
                ||!fixedPolicy(purpose).get("rendererSha256").equals(core.get("service/tracing/probabilistic/BIdentificationScenes.java")))
            throw new IllegalArgumentException("Prospective catalog/renderer changed");
        return new Seal(purpose,pinBytes,expectedPinsHash,policies.get(purpose.name()),(String)registry.get("sourceSha256"),catalog,
                registryHash,(String)pins.get("approval"),family);
    }
    private BProspectiveIdentificationManifest() { }
}

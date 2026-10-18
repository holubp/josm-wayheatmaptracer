package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
/** Explicit aggregate-only TRAIN runtime diagnosis; never identification authority. */
public final class BObservationTrainRuntimeDiagnosticTool {
    static final Path PROTOCOL=Path.of("superpowers/docs/plans/2026-10-18-b-observation-runtime-diagnostic-freeze.json");
    static final String OLD_SOURCE="cf7b048bf8483f888cb497e1a795935a262aeb601b09cd432adb4a5c612436b4";
    static final String OLD_REGISTRY="61246d9c65582c990ad52ed581a8de2c13b3a4671c4a3e70a0b1fd38289e17a6";
    static final Path MANIFEST=Path.of("superpowers/docs/plans/2026-10-18-b-observation-feasibility-manifest.json");
    static final String OLD_MANIFEST="8fb47902e402bded101cd239d52b1f67655616f12ba6d0d7a35918b203a0aa77";
    static final String TOOL="service/tracing/probabilistic/BObservationIdentificationTool.java";
    static final List<String> HELPERS=List.of(
            "src/test/java/"+BIdentificationReceipt.PACKAGE+"service/tracing/probabilistic/BObservationRuntimeTelemetry.java",
            "src/test/java/"+BIdentificationReceipt.PACKAGE+"service/tracing/probabilistic/BObservationTrainRuntimeDiagnosticTool.java");
    static final long WATCHDOG_NANOS=300_000_000_000L,MEMORY_BYTES=64L*1024*1024,DISK_BYTES=2L*1024*1024*1024;
    static final int MAX_SNAPSHOTS=128,MAX_TELEMETRY_BYTES=1024*1024;
    static final List<String> CASES=List.of(
        "multiple-physical-pitches-vs-reversed-source-order/train/v0/left",
        "multiple-physical-pitches-vs-reversed-source-order/train/v0/right",
        "multiple-physical-pitches-vs-reversed-source-order/train/v6/left",
        "sparse-crossing-noisy-strands-vs-persistent-parallel/train/v4/right");
    static Map<String,Object> fixedContract(){
        var fields=new LinkedHashMap<String,Object>();fields.put("schema",1);fields.put("purpose","NONQUALIFYING_TRAIN_RUNTIME");
        fields.put("baselineSourceSha256",OLD_SOURCE);fields.put("baselineRegistrySha256",OLD_REGISTRY);fields.put("baselineManifestSha256",OLD_MANIFEST);
        fields.put("gridOrdinal",0);fields.put("watchdogNanos",WATCHDOG_NANOS);fields.put("memoryBytes",MEMORY_BYTES);fields.put("diskBytes",DISK_BYTES);
        fields.put("selectionAuthority","NONE");fields.put("holdoutAuthority",false);fields.put("caseIds",CASES);
        fields.put("maxSnapshots",MAX_SNAPSHOTS);fields.put("maxTelemetryBytes",MAX_TELEMETRY_BYTES);
        fields.put("timingSchema",1);fields.put("attemptCaps","TraceBudgets.defaults-unchanged");
        fields.put("scratchPolicy","UNCHANGED_ORDINARY_RECEIPTS_AND_PAIR_CACHE_NEVER_UPLOAD");
        return Collections.unmodifiableMap(fields);
    }
    static void validateContract(Map<String,Object> fields) {
        if(fields==null||!BIdentificationReceipt.canonical(fields).equals(BIdentificationReceipt.canonical(fixedContract())))
            throw new IllegalArgumentException("Fixed nonqualifying TRAIN diagnostic contract changed");
    }
    static void requireCase(BIdentificationCatalog.CaseDefinition definition) {
        if(definition==null||definition.key().split()!=BIdentificationCatalog.Split.TRAIN||!CASES.contains(definition.key().id())
                ||!BIdentificationCatalog.trainDiagnosticCatalog().definitions().contains(definition))
            throw new SecurityException("Only exact fixed original TRAIN cases are permitted");
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2||!args[0].equals("--output")||args[1].isBlank())throw new IllegalArgumentException("Use --output NEWDIR only");
        BObservationIdentificationTool.runTrainRuntimeDiagnostic(Path.of(args[1]));
    }
    static final class Freeze {
        final String protocolHash,sourceHash,catalogHash;private final byte[] bytes;
        private Freeze(byte[] bytes,String sourceHash,String catalogHash){this.bytes=bytes.clone();this.protocolHash=BIdentificationReceipt.hash(bytes);this.sourceHash=sourceHash;this.catalogHash=catalogHash;}
        void verifyUnchanged() throws Exception {
            var current=verifyFreeze();
            if(!Arrays.equals(bytes,current.bytes))throw new IllegalStateException("Diagnostic freeze changed during execution");
        }
    }
    static Freeze verifyFreeze() throws Exception {
        byte[] bytes=readBounded(PROTOCOL,65_536);var fields=BIdentificationReceipt.parse(bytes);
        byte[] oldRegistry=readBounded(BIdentificationManifest.REVIEWED_SOURCE_REGISTRY_PATH,65_536);
        byte[] oldManifest=readBounded(MANIFEST,65_536);
        var current=new TreeMap<String,String>();for(String path:BIdentificationReceipt.SOURCE_FILES)current.put(path,BIdentificationReceipt.sourceMemberHash(path));
        var helpers=new TreeMap<String,String>();for(String path:HELPERS)helpers.put(path,BIdentificationReceipt.hash(readBounded(Path.of(path),1_048_576)));
        validateFreeze(fields,current,helpers,oldRegistry,oldManifest);
        if(!Arrays.equals(bytes,(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Diagnostic freeze must be canonical JSON");
        return new Freeze(bytes,BIdentificationReceipt.hash(BIdentificationReceipt.canonical(current).getBytes(StandardCharsets.UTF_8)),BIdentificationCatalog.trainDiagnosticCatalog().canonicalHash());
    }
    /** Pure binding validation for dummy metadata tests; never returns an execution capability. */
    static void validateFreeze(Map<String,Object> fields,Map<String,String> current,Map<String,String> helpers,
            byte[] oldRegistryBytes,byte[] oldManifestBytes) throws Exception {
        if(!fields.keySet().equals(Set.of("contract","sourceMembers","sourceSha256","diagnosticMembers","trainCatalogSha256")))
            throw new IllegalArgumentException("Unknown diagnostic freeze field");
        if(!(fields.get("contract") instanceof Map<?,?> raw))throw new IllegalArgumentException("Fixed contract required");
        var contract=new LinkedHashMap<String,Object>();for(var e:raw.entrySet())contract.put((String)e.getKey(),e.getValue());validateContract(contract);
        if(!BIdentificationReceipt.hash(oldRegistryBytes).equals(OLD_REGISTRY)||!BIdentificationReceipt.hash(oldManifestBytes).equals(OLD_MANIFEST))
            throw new IllegalArgumentException("Original experiment freeze changed");
        var old=BIdentificationReceipt.parse(oldRegistryBytes);var oldMembers=(Map<?,?>)old.get("sourceMembers");
        if(!OLD_SOURCE.equals(old.get("sourceSha256"))||!current.keySet().equals(new HashSet<>(BIdentificationReceipt.SOURCE_FILES))
                ||current.size()!=27||!current.keySet().equals(oldMembers.keySet()))throw new IllegalArgumentException("Exact original27 paths required");
        for(var e:current.entrySet()) {
            if(!e.getValue().matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid current source hash");
            if(!e.getKey().equals(TOOL)&&!e.getValue().equals(oldMembers.get(e.getKey())))throw new IllegalArgumentException("Numerical26 source changed");
        }
        if(!fields.get("sourceMembers").equals(current)
                ||!fields.get("sourceSha256").equals(BIdentificationReceipt.hash(BIdentificationReceipt.canonical(current).getBytes(StandardCharsets.UTF_8)))
                ||!helpers.keySet().equals(new HashSet<>(HELPERS))||!fields.get("diagnosticMembers").equals(helpers)
                ||!fields.get("trainCatalogSha256").equals(BIdentificationCatalog.trainDiagnosticCatalog().canonicalHash()))
            throw new IllegalArgumentException("Diagnostic source/catalog binding changed");
        var manifest=BIdentificationReceipt.parse(oldManifestBytes);
        if(!Boolean.TRUE.equals(manifest.get("existingAttemptCaps"))||((Number)manifest.get("maxFitSeconds")).longValue()*1_000_000_000L<WATCHDOG_NANOS
                ||((Number)manifest.get("maxScenes")).intValue()<CASES.size())throw new IllegalArgumentException("Diagnostic exceeds original manifest caps");
        // Memory/disk are the exact original Tool limits, and its unchanged26/sole Tool delta is reviewed before freeze.
    }
    static byte[] readBounded(Path path,int limit) throws Exception {
        for(Path part=path.toAbsolutePath();part!=null;part=part.getParent())if(Files.isSymbolicLink(part))throw new IllegalArgumentException("Symlink diagnostic input");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(limit+1);if(bytes.length==0||bytes.length>limit||input.read()!=-1)throw new IllegalArgumentException("Diagnostic input byte bound");return bytes;
        }
    }
    private BObservationTrainRuntimeDiagnosticTool() { }
}

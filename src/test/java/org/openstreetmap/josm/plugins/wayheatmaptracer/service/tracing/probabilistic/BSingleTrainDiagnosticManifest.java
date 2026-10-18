package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Fixed single-case diagnostic authority, independent of old experiment authority. */
final class BSingleTrainDiagnosticManifest {
    static final String PURPOSE="SINGLE_ORIGINAL_TRAIN_GEOMETRY_DIAGNOSTIC";
    static final String CASE="sparse-crossing-noisy-strands-vs-persistent-parallel/train/v0/right";
    static final String OLD_PINS="7013cf2b3d170c1c8d472f99d0da209ee5eb9e5811703f9407267cb32664d334";
    static final String OLD_SOURCE="94d7ea9a03134cab331b317a1764b0d517bd2dc1dea6d2a78482bcf8313dc6de";
    static final String CATALOG="debec23907badfaa467fac91cb0da5f3d8771e357ea1faa6f618d60c852d7557";
    static final String RENDERER="a7e754e04b1b6f631b5060cbf07fca0c3615536fe6baaa3b22941bd0e59fc2bc";
    static final Path FREEZE=Path.of("superpowers/docs/plans/2026-10-18-b-single-train-geometry-freeze.json");
    static final String PROTOCOL="superpowers/docs/plans/2026-10-18-b-single-train-geometry-protocol.md";
    static final Map<String,String> RECEIPTS=Map.of("G1-L1-B0","05a3daf825e5dad3f8b3a563dcdf2c215b4a72ffedef8a912115371fd60f22f1",
            "G1-L1-B1","fab99377a85e93eafde0a16f99d21b70a89a54b5c142957058e2db6f0bbfdaa3");
    static Map<String,Object> fixedFields() {
        var fields=new LinkedHashMap<String,Object>();fields.put("schema",1);fields.put("purpose",PURPOSE);fields.put("case",CASE);
        fields.put("sourceProofOnly",OLD_SOURCE);fields.put("oldPinsSha256",OLD_PINS);fields.put("catalogHash",CATALOG);
        fields.put("memoryBytes",67_108_864);fields.put("diskBytes",2_147_483_648L);fields.put("watchdogNanos",300_000_000_000L);
        fields.put("maximumPairChecks",101_160);fields.put("maximumProfiles",81);fields.put("maximumStates",36);
        fields.put("materializations",1);fields.put("mapSolves",0);fields.put("selectionAuthority","NONE");
        fields.put("archiveSha256","e78b66bc1c3f52ce260fc8aa7d0f2d91bc8aa0f851a8e6faa42550ae5184b1dd");
        fields.put("archivedReceiptSha256",RECEIPTS);fields.put("outputSchema",1);return Collections.unmodifiableMap(fields);
    }
    static Map<String,String> launch(Map<String,String> environment) {
        if(!"true".equals(environment.get("GITHUB_ACTIONS"))||!"workflow_dispatch".equals(environment.get("GITHUB_EVENT_NAME"))
                ||!"1".equals(environment.get("GITHUB_RUN_ATTEMPT")))throw new SecurityException("First manual public CI attempt required");
        String repository=environment.getOrDefault("GITHUB_REPOSITORY",""),run=environment.getOrDefault("GITHUB_RUN_ID","");
        String commit=environment.getOrDefault("GITHUB_SHA",""),workflow=environment.getOrDefault("GITHUB_WORKFLOW_REF","");
        if(!repository.equals("holubp/josm-wayheatmaptracer")||!run.matches("[0-9]{1,20}")||!commit.matches("[0-9a-f]{40}")
                ||!workflow.matches(java.util.regex.Pattern.quote(repository)+"/\\.github/workflows/(build|observation-single-train-geometry)\\.yml@refs/heads/[A-Za-z0-9_./-]{1,200}"))
            throw new SecurityException("Fixed bounded CI launch required");
        return Map.of("repository",repository,"runId",run,"commit",commit,"workflowRef",workflow,"runAttempt","1");
    }
    static void authorize(String approval,Map<String,String> environment,Runnable acquisition) {
        BProspectiveIdentificationManifest.requireExecutionApproved(approval);launch(environment);acquisition.run();
    }
    static void validateFields(Map<String,Object> fields) {
        var keys=new HashSet<>(fixedFields().keySet());keys.addAll(Set.of("approval","members"));
        if(fields==null||!fields.keySet().equals(keys)||!(BProspectiveIdentificationManifest.PENDING.equals(fields.get("approval"))
                ||BProspectiveIdentificationManifest.APPROVED.equals(fields.get("approval")))||!(fields.get("members") instanceof Map))
            throw new IllegalArgumentException("Exact fixed diagnostic freeze required");
        var policy=new LinkedHashMap<>(fields);policy.remove("approval");policy.remove("members");
        if(!BIdentificationReceipt.canonical(fixedFields()).equals(BIdentificationReceipt.canonical(policy)))
            throw new IllegalArgumentException("Fixed diagnostic policy changed");
    }
    static Set<String> memberPaths() {
        var paths=new TreeSet<String>();
        for(String relative:BIdentificationReceipt.SOURCE_FILES) {
            String main="src/main/java/"+BIdentificationReceipt.PACKAGE+relative;
            paths.add(Files.exists(Path.of(main))?main:"src/test/java/"+BIdentificationReceipt.PACKAGE+relative);
        }
        paths.addAll(BProspectiveIdentificationManifest.PROTOCOL_FILES);
        for(String helper:List.of("BSingleTrainDiagnosticManifest","BSingleTrainDiagnosticBridge","BSingleTrainGeometryReachability","BSingleTrainGeometryDiagnosticTool"))
            paths.add("src/test/java/"+BIdentificationReceipt.PACKAGE+"service/tracing/probabilistic/"+helper+".java");
        for(String relative:List.of("model/MetricRegion.java","model/MetricPoint.java","model/TraceBudgets.java",
                "service/tracing/AttemptMemoryLedger.java","service/tracing/probabilistic/LateralStateCell.java",
                "service/tracing/probabilistic/ProbabilisticProfile.java","service/tracing/probabilistic/ProbabilisticStateLattice.java"))
            paths.add("src/main/java/"+BIdentificationReceipt.PACKAGE+relative);
        for(String id:RECEIPTS.keySet())paths.add("src/test/resources/b-single-train-geometry/"+id+".json");
        paths.addAll(List.of(PROTOCOL,"build.gradle.kts",".github/workflows/build.yml"));
        return Collections.unmodifiableSet(paths);
    }
    static Map<String,String> currentMembers() throws Exception {
        var members=new TreeMap<String,String>();
        for(String path:memberPaths())members.put(path,BIdentificationReceipt.hash(BObservationTrainRuntimeDiagnosticTool.readBounded(Path.of(path),1_048_576)));
        return Collections.unmodifiableMap(members);
    }
    static Seal register(String expected) throws Exception {
        byte[] bytes=BObservationTrainRuntimeDiagnosticTool.readBounded(FREEZE,65_536);
        var fields=validateSnapshot(expected,bytes,currentMembers());
        var old=BProspectiveIdentificationManifest.registerMetadata(BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT,OLD_PINS);
        if(!OLD_SOURCE.equals(old.sourceHash())||!CATALOG.equals(old.catalogHash()))throw new SecurityException("Original source proof changed");
        BSingleTrainDiagnosticBridge.verifySignatures();
        return new Seal(bytes,expected,(String)fields.get("approval"),old);
    }
    static Map<String,Object> validateSnapshot(String expected,byte[] bytes,Map<String,String> members) throws Exception {
        if(expected==null||!expected.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Independent literal freeze digest required");
        if(!expected.equals(BIdentificationReceipt.hash(bytes)))throw new SecurityException("External fixed freeze changed");
        var fields=BIdentificationReceipt.parse(bytes);validateFields(fields);
        if(!Arrays.equals(bytes,(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8))
                ||!members.equals(fields.get("members"))||!memberPaths().equals(members.keySet()))throw new SecurityException("Diagnostic source inventory changed");
        return fields;
    }
    static final class Seal {
        private final byte[] initial;final String digest,approval;private final BProspectiveIdentificationManifest.Seal old;
        private Seal(byte[] bytes,String digest,String approval,BProspectiveIdentificationManifest.Seal old) {
            initial=bytes.clone();this.digest=digest;this.approval=approval;this.old=old;
        }
        void verify() throws Exception {
            if(!Arrays.equals(initial,BObservationTrainRuntimeDiagnosticTool.readBounded(FREEZE,65_536)))
                throw new SecurityException("Diagnostic freeze changed during execution");
            var fields=BIdentificationReceipt.parse(initial);
            if(!currentMembers().equals(fields.get("members")))throw new SecurityException("Diagnostic source changed during execution");
            old.verifyUnchanged();
        }
        void requireExecution(Map<String,String> environment) throws Exception {verify();authorize(approval,environment,()->{});}
    }
    private BSingleTrainDiagnosticManifest() { }
}

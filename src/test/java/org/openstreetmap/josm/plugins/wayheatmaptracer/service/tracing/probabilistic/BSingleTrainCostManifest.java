package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Independent fixed cost-accounting authority; earlier registrations are source proof only. */
final class BSingleTrainCostManifest {
    static final String PURPOSE="SINGLE_ORIGINAL_TRAIN_FIXED_PATH_COST_ACCOUNTING";
    static final String BRANCH="refs/heads/codex/b-observation-cost-20261019";
    static final String OLD_FREEZE="16c9fbcddc89fa53283a704c1f4ba2b25c33b6914409ce06a580827779eb40a9";
    static final String WITNESS="306df0ebe59f052104883c8113823a127176c254510faebf10b32ff896fba5ad";
    static final String PROTOCOL="superpowers/docs/plans/2026-10-19-b-single-train-cost-protocol.md";
    static final Path FREEZE=Path.of("superpowers/docs/plans/2026-10-19-b-single-train-cost-freeze.json");
    static final List<String> HELPERS=List.of("BSingleTrainCostManifest","BSingleTrainObjectiveAccounting","BSingleTrainCostDiagnosticTool");
    static Map<String,Object> fixedFields() {
        var f=new TreeMap<String,Object>();f.put("schema",1);f.put("purpose",PURPOSE);f.put("case",BSingleTrainDiagnosticManifest.CASE);
        f.put("oldSourceFreezeSha256",OLD_FREEZE);f.put("sourceProofOnly",BSingleTrainDiagnosticManifest.OLD_SOURCE);
        f.put("witnessMemberSha256",WITNESS);f.put("witnessRun","39171151346");f.put("witnessArtifact","12151984749");
        f.put("witnessArchiveSha256","136d5ab3aee0f02af5fe36c86a05b74564b34dcbf81c814b166a6b280069f927");
        f.put("archivedReceiptSha256",BSingleTrainDiagnosticManifest.RECEIPTS);
        f.put("fitArchiveSha256","e78b66bc1c3f52ce260fc8aa7d0f2d91bc8aa0f851a8e6faa42550ae5184b1dd");
        f.put("branch",BRANCH);f.put("materializations",1);f.put("parameters",List.of("G1-L1-B0","G1-L1-B1"));
        f.put("profileEvaluations",162);f.put("pathAccounts",4);f.put("mapSolves",0);f.put("selectionAuthority","NONE");
        f.put("memoryBytes",67_108_864);f.put("diskBytes",2_147_483_648L);f.put("watchdogNanos",300_000_000_000L);
        f.put("profileTerms",324);f.put("pairTerms",320);f.put("tripleTerms",316);f.put("primitiveCalls",4096);f.put("componentRecords",972);
        f.put("outputFiles",BSingleTrainCostDiagnosticTool.outputFiles());f.put("maximumFileBytes",65_536);return Collections.unmodifiableMap(f);
    }
    static Set<String> memberPaths() {
        var paths=new TreeSet<>(BSingleTrainDiagnosticManifest.memberPaths());
        paths.add(BSingleTrainDiagnosticManifest.FREEZE.toString());
        paths.add(BProspectiveIdentificationManifest.REGISTRY.toString());paths.add(BProspectiveIdentificationManifest.PINS.toString());
        for(String helper:HELPERS)paths.add("src/test/java/"+BIdentificationReceipt.PACKAGE+"service/tracing/probabilistic/"+helper+".java");
        for(String relative:List.of("model/ImageOrientationSupport.java","model/ObservationOwnership.java",
                "service/tracing/CancellationProbe.java","service/tracing/ModernNumericalPolicy.java",
                "service/tracing/probabilistic/ObservationComponent.java"))paths.add("src/main/java/"+BIdentificationReceipt.PACKAGE+relative);
        paths.add("src/test/java/"+BIdentificationReceipt.PACKAGE+"diagnostics/replay/format15/ObservationManifestJson.java");
        paths.add("src/test/resources/b-single-train-cost/witness.json");paths.add("scripts/single-train-cost.init.gradle.kts");paths.add(PROTOCOL);
        return Collections.unmodifiableSet(paths);
    }
    static Map<String,String> currentMembers() throws Exception {
        var result=new TreeMap<String,String>();for(String path:memberPaths())result.put(path,BIdentificationReceipt.hash(
                BObservationTrainRuntimeDiagnosticTool.readBounded(Path.of(path),1_048_576)));return Collections.unmodifiableMap(result);
    }
    static Map<String,String> launch(Map<String,String> e) {
        String repository="holubp/josm-wayheatmaptracer";
        if(!"true".equals(e.get("GITHUB_ACTIONS"))||!"push".equals(e.get("GITHUB_EVENT_NAME"))||!"1".equals(e.get("GITHUB_RUN_ATTEMPT"))
                ||!repository.equals(e.get("GITHUB_REPOSITORY"))||!BRANCH.equals(e.get("GITHUB_REF"))
                ||!e.getOrDefault("GITHUB_RUN_ID","").matches("[0-9]{1,20}")||!e.getOrDefault("GITHUB_SHA","").matches("[0-9a-f]{40}")
                ||!(repository+"/.github/workflows/observation-single-train-cost.yml@"+BRANCH).equals(e.get("GITHUB_WORKFLOW_REF")))
            throw new SecurityException("Exact first controlled push context required");
        return Map.of("repository",repository,"event","push","ref",BRANCH,"runAttempt","1","runId",e.get("GITHUB_RUN_ID"),
                "commit",e.get("GITHUB_SHA"),"workflowRef",e.get("GITHUB_WORKFLOW_REF"));
    }
    static void authorize(String approval,Map<String,String> environment,Runnable acquisition) {
        BProspectiveIdentificationManifest.requireExecutionApproved(approval);launch(environment);acquisition.run();
    }
    static Map<String,Object> validateSnapshot(String expected,byte[] bytes,Map<String,String> members) throws Exception {
        if(expected==null||!expected.matches("[0-9a-f]{64}")||!expected.equals(BIdentificationReceipt.hash(bytes)))throw new SecurityException("External fixed cost freeze changed");
        var fields=BIdentificationReceipt.parse(bytes);var policy=new TreeMap<>(fields);Object approval=policy.remove("approval"),inventory=policy.remove("members");
        if(!(BProspectiveIdentificationManifest.PENDING.equals(approval)||BProspectiveIdentificationManifest.APPROVED.equals(approval))
                ||!BIdentificationReceipt.canonical(fixedFields()).equals(BIdentificationReceipt.canonical(policy)))throw new SecurityException("Fixed cost policy changed");
        if(!Arrays.equals(bytes,(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8))
                ||!memberPaths().equals(members.keySet())||!members.equals(inventory))throw new SecurityException("Fixed cost source inventory changed");
        return fields;
    }
    static Seal register(String expected) throws Exception {
        byte[] bytes=BObservationTrainRuntimeDiagnosticTool.readBounded(FREEZE,65_536);
        var fields=validateSnapshot(expected,bytes,currentMembers());
        var old=BSingleTrainDiagnosticManifest.register(OLD_FREEZE);old.verify();
        BSingleTrainObjectiveAccounting.verifySignatures();return new Seal(bytes,expected,(String)fields.get("approval"),old);
    }
    static final class Seal {
        final String digest,approval;private final byte[] initial;private final BSingleTrainDiagnosticManifest.Seal old;
        Seal(byte[] bytes,String digest,String approval,BSingleTrainDiagnosticManifest.Seal old){initial=bytes.clone();this.digest=digest;this.approval=approval;this.old=old;}
        void verify() throws Exception {
            if(!Arrays.equals(initial,BObservationTrainRuntimeDiagnosticTool.readBounded(FREEZE,65_536)))throw new SecurityException("Cost freeze changed during execution");
            validateSnapshot(digest,initial,currentMembers());old.verify();
        }
        void requireExecution(Map<String,String> environment) throws Exception {verify();authorize(approval,environment,()->{});runtimeClasses();}
    }
    static Map<String,String> runtimeClasses() throws Exception {
        var hashes=new TreeMap<String,String>();var classes=new ArrayList<Class<?>>();
        for(String helper:HELPERS)classes.add(Class.forName(BSingleTrainCostManifest.class.getPackageName()+"."+helper));
        classes.addAll(List.of(BObservationAdapter.class,BIdentifiedObservationKernel.class,BObservationMixture.class,
                ProbabilisticInference.class,InferenceProfile.class,BSingleTrainDiagnosticBridge.class));
        for(Class<?> type:classes) {
            String source=type==ProbabilisticInference.class||type==InferenceProfile.class?"main":"observationIdentify";
            Path expected=Path.of("build/classes/java/"+source).toRealPath();
            if(!Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected))
                throw new SecurityException("Fixed diagnostic class origin changed");
            try(var stream=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                if(stream==null)throw new SecurityException("Runtime class unavailable");byte[] bytes=stream.readNBytes(1_048_577);
                if(bytes.length>1_048_576)throw new SecurityException("Runtime class limit");hashes.put(type.getName(),BIdentificationReceipt.hash(bytes));
            }
        }
        return Collections.unmodifiableMap(hashes);
    }
    private BSingleTrainCostManifest() { }
}

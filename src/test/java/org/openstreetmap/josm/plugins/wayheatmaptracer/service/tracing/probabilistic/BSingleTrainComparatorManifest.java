package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Independent pending comparator authority; all earlier seals are SOURCE proof only. */
final class BSingleTrainComparatorManifest {
    static final String PURPOSE="SINGLE_ORIGINAL_TRAIN_B0_ACCEPTED_COMPARATOR";
    static final String BRANCH="refs/heads/codex/b-observation-comparator-20261019";
    static final String OLD_COST="2ab4b3e1a139c82b84ffda859e21419d4062c5fc7777c8eb2b16458c56e37da9";
    static final String PROTOCOL="superpowers/docs/plans/2026-10-19-b-single-train-comparator-protocol.md";
    static final Path FREEZE=Path.of("superpowers/docs/plans/2026-10-19-b-single-train-comparator-freeze.json");
    static final List<String> HELPERS=List.of("BSingleTrainComparatorRegion","BSingleTrainComparatorTool","BSingleTrainComparatorManifest");
    static Map<String,Object> fixedFields() {
        var f=new TreeMap<String,Object>();f.put("schema",1);f.put("purpose",PURPOSE);f.put("case",BSingleTrainDiagnosticManifest.CASE);f.put("parameter","G1-L1-B0");
        f.put("oldCostSourceFreezeSha256",OLD_COST);f.put("sourceProofOnly",BSingleTrainDiagnosticManifest.OLD_SOURCE);f.put("branch",BRANCH);
        f.put("archivedReceiptSha256",BSingleTrainDiagnosticManifest.RECEIPTS.get("G1-L1-B0"));f.put("witnessMemberSha256",BSingleTrainCostManifest.WITNESS);
        f.put("materializations",1);f.put("adapterEntries",81);f.put("mapSolves",1);f.put("pathAccounts",2);f.put("maximumProofPairs",101_160);f.put("maximumGeometryCallbackUnits",1_000_000_000L);
        f.put("memoryBytes",67_108_864);f.put("diskBytes",2_147_483_648L);f.put("watchdogNanos",300_000_000_000L);
        f.put("solverBudgets",Map.of("states",96,"pairs",8_000_000,"transitions",128_000_000,"raw",1,"distinct",1));
        f.put("executionPolicy","EXACT_DOMINANCE_V2");f.put("selectionAuthority","NONE");f.put("outputFiles",BSingleTrainComparatorTool.outputFiles());f.put("maximumFileBytes",65_536);return Collections.unmodifiableMap(f);
    }
    static Set<String> memberPaths() {
        var paths=new TreeSet<>(BSingleTrainCostManifest.memberPaths());paths.add(BSingleTrainCostManifest.FREEZE.toString());
        paths.add(".github/workflows/observation-single-train-cost.yml");
        for(String helper:HELPERS)paths.add("src/test/java/"+BIdentificationReceipt.PACKAGE+"service/tracing/probabilistic/"+helper+".java");
        for(String relative:List.of("model/InferenceWorkAccounting.java","service/tracing/TraceMemoryLimitException.java","util/OrderedDoubleSum.java","util/ModernDiagnosticCounters.java",
                "service/tracing/probabilistic/ProbabilisticInferenceResult.java","service/tracing/probabilistic/ProbabilisticPath.java",
                "service/tracing/probabilistic/GapSummary.java","service/tracing/probabilistic/CredibleLateralSet.java","service/tracing/probabilistic/PathAlternativeSelector.java",
                "service/tracing/probabilistic/TransitionAdmissionMemo.java","service/tracing/probabilistic/ProbabilisticExecutionPolicy.java"))
            paths.add("src/main/java/"+BIdentificationReceipt.PACKAGE+relative);
        paths.add("scripts/single-train-comparator.init.gradle.kts");paths.add(PROTOCOL);return Collections.unmodifiableSet(paths);
    }
    static Map<String,String> currentMembers() throws Exception {
        var members=new TreeMap<String,String>();for(String path:memberPaths())members.put(path,BIdentificationReceipt.hash(BObservationTrainRuntimeDiagnosticTool.readBounded(Path.of(path),1_048_576)));return members;
    }
    static Map<String,String> launch(Map<String,String> e) {
        String repo="holubp/josm-wayheatmaptracer";
        if(!"true".equals(e.get("GITHUB_ACTIONS"))||!"push".equals(e.get("GITHUB_EVENT_NAME"))||!"1".equals(e.get("GITHUB_RUN_ATTEMPT"))||!repo.equals(e.get("GITHUB_REPOSITORY"))
                ||!BRANCH.equals(e.get("GITHUB_REF"))||!e.getOrDefault("GITHUB_RUN_ID","").matches("[0-9]{1,20}")||!e.getOrDefault("GITHUB_SHA","").matches("[0-9a-f]{40}")
                ||!(repo+"/.github/workflows/observation-single-train-comparator.yml@"+BRANCH).equals(e.get("GITHUB_WORKFLOW_REF")))throw new SecurityException("Exact first reviewed comparator push required");
        return Map.of("repository",repo,"event","push","ref",BRANCH,"runId",e.get("GITHUB_RUN_ID"),"runAttempt","1","commit",e.get("GITHUB_SHA"),"workflowRef",e.get("GITHUB_WORKFLOW_REF"));
    }
    static void authorize(String approval,Map<String,String> e,Runnable acquisition){BProspectiveIdentificationManifest.requireExecutionApproved(approval);launch(e);acquisition.run();}
    static Map<String,Object> validateSnapshot(String expected,byte[] bytes,Map<String,String> members) throws Exception {
        if(expected==null||!expected.matches("[0-9a-f]{64}")||!expected.equals(BIdentificationReceipt.hash(bytes)))throw new SecurityException("External comparator freeze changed");
        var fields=BIdentificationReceipt.parse(bytes);var policy=new TreeMap<>(fields);Object approval=policy.remove("approval"),inventory=policy.remove("members");
        if(!(BProspectiveIdentificationManifest.PENDING.equals(approval)||BProspectiveIdentificationManifest.APPROVED.equals(approval))
                ||!BIdentificationReceipt.canonical(fixedFields()).equals(BIdentificationReceipt.canonical(policy)))throw new SecurityException("Fixed comparator policy changed");
        if(!Arrays.equals(bytes,(BIdentificationReceipt.canonical(fields)+"\n").getBytes(StandardCharsets.UTF_8))||!memberPaths().equals(members.keySet())||!members.equals(inventory))throw new SecurityException("Comparator inventory changed");return fields;
    }
    static Seal register(String expected) throws Exception {
        byte[] bytes=BObservationTrainRuntimeDiagnosticTool.readBounded(FREEZE,65_536);var fields=validateSnapshot(expected,bytes,currentMembers());
        var old=BSingleTrainCostManifest.register(OLD_COST);old.verify();return new Seal(bytes,expected,(String)fields.get("approval"),old);
    }
    static final class Seal {
        final String digest,approval;private final byte[] initial;private final BSingleTrainCostManifest.Seal old;
        Seal(byte[] initial,String digest,String approval,BSingleTrainCostManifest.Seal old){this.initial=initial.clone();this.digest=digest;this.approval=approval;this.old=old;}
        void verify() throws Exception {if(!Arrays.equals(initial,BObservationTrainRuntimeDiagnosticTool.readBounded(FREEZE,65_536)))throw new SecurityException("Comparator freeze changed during run");validateSnapshot(digest,initial,currentMembers());old.verify();}
        void requireExecution(Map<String,String> environment) throws Exception {verify();authorize(approval,environment,()->{});runtimeClasses();}
    }
    static Map<String,String> runtimeClasses() throws Exception {
        var hashes=new TreeMap<>(BSingleTrainCostManifest.runtimeClasses());var classes=new ArrayList<Class<?>>();
        for(String helper:HELPERS)classes.add(Class.forName(BSingleTrainComparatorManifest.class.getPackageName()+"."+helper));
        classes.addAll(List.of(ProbabilisticInferenceResult.class,ProbabilisticPath.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion.class));
        for(Class<?> type:classes) {
            String source=HELPERS.contains(type.getSimpleName())?"observationIdentify":"main";
            if(!Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of("build/classes/java/"+source).toRealPath()))throw new SecurityException("Comparator class origin changed");
            try(var stream=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                if(stream==null)throw new SecurityException("Comparator runtime class unavailable");byte[] bytes=stream.readNBytes(1_048_577);if(bytes.length>1_048_576)throw new SecurityException("Runtime class limit");hashes.put(type.getName(),BIdentificationReceipt.hash(bytes));
            }
        }
        return hashes;
    }
    private BSingleTrainComparatorManifest() { }
}

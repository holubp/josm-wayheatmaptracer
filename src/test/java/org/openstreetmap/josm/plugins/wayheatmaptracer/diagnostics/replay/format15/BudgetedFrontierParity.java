package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.nio.file.*;
import java.io.IOException;
import java.security.*;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.*;

/** Test-only exact semantic gate. Baseline pins are reviewed facts, never regenerated candidate expectations. */
public final class BudgetedFrontierParity {
    static final List<String> EXACT_ARTIFACTS=List.of("raw-route.json","final-route.json","private/final-output-components.bin");
    private static final Map<String,String> PINNED_FILES=Map.of(
        "geometry.csv","c6346145e69ae3347c0eb4c9041c1da3b71bbf9f35c24467229af1d3f216fa9f",
        "assignments.csv","c36f1666fd1596701eaa26c33dc974b44f756ab3bf7f644705e7660eb98b0ff1",
        "continuous-authority.csv","23f1c9b27ab5a6db3fcfe2878d94514189e301cca1f9c1bad65086aca0349851",
        "native-raw-path-order.csv","1a72d8bd3a1dd6b30caf0d70e34efbf4bdf69c848441a2f37693a010f50136c5",
        "retained-original-indices.csv","fc6ee3d650a9635ef7dba383862c5236219ebc2a902944dad60856ccf8b05527",
        "quality-topology.txt","5b342203dc9ff645f3ca4dfe350eafba76b59fcac0e0c3db108b2f1bcf35b2ef");
    private static final Map<String,String> PINNED_ARTIFACTS=Map.of(
        "raw-route.json","9328f0f086d4ebe2c8a1ba1dc3d3e293f035f183f0d76601749be338eba2321b",
        "final-route.json","10ffe39e7d825611ff70441fe79a07f65d6dd6e328edc5f3b0e8a8ef02ffe688",
        "private/final-output-components.bin","20ca52ed526fae70808129d0bd9f821870eb71ae40291c0a88ba7295d7c523b0",
        "frozen-edit-plan.bin","2878f403b12c8e089d13e86cef9ab9cd07288370cfd9756ec26622f7f93b77e8");
    private BudgetedFrontierParity() { }

    /**
     * Fails closed unless the reviewed baseline pins and every native semantic artifact agree.
     * @param baselineDirectory local reviewed baseline; expected bytes are never regenerated
     * @param candidateDirectory actual newly captured native output
     * @param parameterPolicyChanged whether the sole typed V5-to-V6 policy transformation is allowed
     * @throws IOException when a required local artifact cannot be read
     * @throws Format15ArchiveException when a capture is malformed
     * @throws AssertionError when any pinned input, geometry, work or authority condition differs
     */
    public static void assertPreserved(Path baselineDirectory,Path candidateDirectory,boolean parameterPolicyChanged)throws IOException,Format15ArchiveException {
        for(var pin:PINNED_FILES.entrySet()) {
            require(sha(Files.readAllBytes(baselineDirectory.resolve(pin.getKey()))).equals(pin.getValue()),"baseline pin: "+pin.getKey());
            require(Files.mismatch(baselineDirectory.resolve(pin.getKey()),candidateDirectory.resolve(pin.getKey()))==-1,"semantic file: "+pin.getKey());
        }
        require(sha(Files.readAllBytes(baselineDirectory.resolve("derived-frozen-input.bin"))).equals(
            "4ed55f11d81817cc7e4defdc79549f8ebf3c0628911be328a8d8eb69d9f1f653"),"baseline derived input");
        require(sha(Files.readAllBytes(baselineDirectory.resolve("production-capture.zip"))).equals(
            "c65b66780fe57a814f6ac86892385c5d8664a7104f08182fac178f784e777106"),"baseline capture pin");
        var baseline=Format15ArchiveReader.read(baselineDirectory.resolve("production-capture.zip"));
        var candidate=Format15ArchiveReader.read(candidateDirectory.resolve("production-capture.zip"));
        for(var pin:PINNED_ARTIFACTS.entrySet())require(baseline.artifact(pin.getKey()).orElseThrow().sha256().equals(pin.getValue()),"baseline artifact: "+pin.getKey());
        var oldInput=FrozenReplayCodec.decode(baseline.artifact("frozen-input.bin").orElseThrow().bytes());
        var newInput=FrozenReplayCodec.decode(candidate.artifact("frozen-input.bin").orElseThrow().bytes());
        require(Arrays.equals(Files.readAllBytes(baselineDirectory.resolve("derived-frozen-input.bin")),FrozenReplayCodec.encode(oldInput)),"baseline input/capture binding");
        require(Arrays.equals(Files.readAllBytes(candidateDirectory.resolve("derived-frozen-input.bin")),FrozenReplayCodec.encode(newInput)),"candidate input/capture binding");
        assertInputPreserved(oldInput,newInput,parameterPolicyChanged);
        Format15NumericalPolicyReceipt.validate(baseline,oldInput,oldInput.canonicalHash());
        Format15NumericalPolicyReceipt.validate(candidate,newInput,newInput.canonicalHash());
        Map<String,byte[]> before=new LinkedHashMap<>(),after=new LinkedHashMap<>();
        for(String name:EXACT_ARTIFACTS) {
            before.put(name,baseline.artifact(name).orElseThrow().bytes());
            after.put(name,candidate.artifact(name).orElseThrow().bytes());
        }
        assertExactArtifacts(before,after);
        var oldPlan=FrozenReplayCodec.decodeEditPlan(baseline.artifact("frozen-edit-plan.bin").orElseThrow().bytes());
        var newPlan=FrozenReplayCodec.decodeEditPlan(candidate.artifact("frozen-edit-plan.bin").orElseThrow().bytes());
        require(oldPlan.canonicalHash().equals("f69ae5493fe131d37bb2ab24c2a8e4a00a3af269844e2e4867614fd1a71c6a0a"),"baseline plan");
        require(oldPlan.before().canonicalHash().equals("193108fe8602af3b816e09893d98f8854f5d09d2c92ae8b536c8937c8f6a8589"),"before network");
        require(oldPlan.after().canonicalHash().equals("39a483245d60a51b98f13b8e6ce2a41d0a77c120e89d878d22ab7bd4f9e3a08e"),"after network");
        require(newPlan.parameterHash().equals(newInput.request().parameterHash()),"candidate plan policy");
        var normalized=parameterPolicyChanged?normalizeParameterForComparison(newPlan,oldPlan.parameterHash()):newPlan;
        require(Arrays.equals(FrozenReplayCodec.encodeEditPlan(oldPlan),FrozenReplayCodec.encodeEditPlan(normalized)),"complete normalized frozen plan");
        if(parameterPolicyChanged)require(!newPlan.canonicalHash().equals(oldPlan.canonicalHash()),"new confirmation identity");
        require(Files.readAllLines(candidateDirectory.resolve("native-raw-path-order.csv")).size()==33,"all 32 native raw paths");
        var runs=Format15ExecutionWorkCodec.read(candidate.artifact(Format15ExecutionWorkCodec.ARTIFACT).orElseThrow());
        require(runs.size()==1&&runs.get(0).isPresent()&&runs.get(0).orElseThrow().computedTransitions()==91018982,"native scores");
        String counters=Files.readString(candidateDirectory.resolve("pipeline-native-counters.txt"));
        require(counter(counters,"b.adaptive.refinements",1)&&counter(counters,"b.adaptive.firstScores",30566216)
            &&counter(counters,"b.adaptive.secondScores",60452766)&&counter(counters,"b.cache.pairSlots",639864),"two-pass actual work");
        String result=Files.readString(candidateDirectory.resolve("result.txt"));
        require(result.contains("pairVisits=639864, transitions=91018982, rawAlternatives=32"),"native usage");
        String probe=Files.readString(candidateDirectory.resolve("native-probe.txt"));
        require(probe.contains("engineOwnerCurrentBytes=0\n")&&probe.contains("closedCalls=1\n")
            &&probe.contains("denseCompleted=true\n")&&probe.contains("inferredCompleted=true\n"),"native owner and completion");
        require(!result.contains("FIRST_GRAPH_PROVEN_UNAFFORDABLE")&&!result.contains("REFINEMENT_NOT_PERFORMED_BUDGET")
            &&!result.contains("bBudgetedFirstGraphV1"),"fallback must be absent");
        if(parameterPolicyChanged) {
            var artifact=candidate.artifact(Format15BRefinementBudgetReceipt.ARTIFACT).orElseThrow();
            try {
                var json=Format15ArchiveReader.parseObject(artifact.bytes(),artifact.name());
                var witness=BRefinementBudget.parse((String)json.get("witness"));
                require(witness.outcome()==BRefinementBudget.Outcome.ORIGINAL_REFINEMENT,"original refinement outcome");
                require(witness.originalIndexHash().equals("126e0dfe3d86b9b5bec2a46247a592ae94df242e3f5cd6be8262af4d9b8ce444"),"native retained provenance");
            }catch(RuntimeException malformed){throw new AssertionError("candidate refinement evidence",malformed);}
        }
    }
    static void assertExactArtifacts(Map<String,byte[]> baseline,Map<String,byte[]> candidate) {
        require(baseline.keySet().equals(Set.copyOf(EXACT_ARTIFACTS))&&candidate.keySet().equals(baseline.keySet()),"complete exact artifacts");
        for(String name:EXACT_ARTIFACTS)require(Arrays.equals(baseline.get(name),candidate.get(name)),"exact artifact: "+name);
    }
    static void assertInputPreserved(FrozenReplayInput baseline,FrozenReplayInput candidate,boolean changed) {
        if(!changed) {
            require(Arrays.equals(FrozenReplayCodec.encode(baseline),FrozenReplayCodec.encode(candidate)),"unchanged input bytes");return;
        }
        var r=baseline.request();
        require(BPolicy.recorded(r)==BPolicy.INVENTORY_FRONTIER_V5,"baseline explicit V5");
        String exact=BPolicy.newCaptureParameterHash(BPolicy.exactParameterHash(),BPolicy.INVENTORY_FRONTIER_V5).equals(r.parameterHash())
            ?BPolicy.exactParameterHash():BPolicy.visibleExactParameterHash();
        var original=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
            r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),exact,
            r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
        var expected=new FrozenReplayInput(BPolicy.adaptiveRequest(original,BPolicy.INVENTORY_FRONTIER_BUDGETED_V6),baseline.evidence(),
            baseline.network(),baseline.options(),baseline.planSearchRadiusMeters(),baseline.legacyB());
        require(Arrays.equals(FrozenReplayCodec.encode(expected),FrozenReplayCodec.encode(candidate)),"typed V6 transformation only");
    }
    static AlignmentEditPlan normalizeParameterForComparison(AlignmentEditPlan p,String baselineParameterHash) {
        return new AlignmentEditPlan(p.selectedWayKey(),p.selectedRange(),p.before(),p.after(),p.metricFrame(),p.permissions(),
            p.settingsHash(),p.evidenceHash(),baselineParameterHash,p.routeIdentity(),p.finalPreviewWays(),p.validation(),
            p.disconnectionSource(),p.disconnectedOccurrenceReplacements(),p.disconnectionGeometryMode());
    }
    private static boolean counter(String text,String key,long value) {
        return java.util.regex.Pattern.compile("(?:^|[,{ ]+)"+java.util.regex.Pattern.quote(key)+"="+value+"(?:[, }]|$)").matcher(text).find();
    }
    private static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}

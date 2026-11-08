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

    static void assertQualityTextPreserved(String baseline,String candidate) {
        var before=baseline.split("\n",-1);var after=candidate.split("\n",-1);
        require(before.length==after.length,"quality line count");
        for(int i=0;i<before.length;i++) {
            if(before[i].startsWith("raw=TraceHypothesis[")||before[i].startsWith("final=TraceHypothesis[")) {
                String marker=", diagnostics={";
                int a=before[i].lastIndexOf(marker),b=after[i].lastIndexOf(marker);
                require(a>=0&&b>=0&&before[i].endsWith("}]")&&after[i].endsWith("}]"),"quality diagnostics envelope");
                require(before[i].substring(0,a).equals(after[i].substring(0,b)),"complete non-map quality text");
                require(diagnosticEntries(before[i].substring(a+marker.length(),before[i].length()-2)).equals(
                    diagnosticEntries(after[i].substring(b+marker.length(),after[i].length()-2))),"exact diagnostic keys/value spellings");
            } else require(before[i].equals(after[i]),"exact quality/topology line "+i);
        }
    }
    private static Map<String,String> diagnosticEntries(String text) {
        Map<String,String> entries=new LinkedHashMap<>();
        if(text.isEmpty())return entries;
        for(String entry:text.split(", ",-1)) {
            int separator=entry.indexOf('=');
            require(separator>0&&separator==entry.lastIndexOf('='),"diagnostic entry");
            String key=entry.substring(0,separator),value=entry.substring(separator+1);
            require(key.matches("[A-Za-z][A-Za-z0-9_.-]*")&&!value.isEmpty(),"diagnostic key/value");
            try {require(Double.isFinite(Double.parseDouble(value)),"finite diagnostic value");}
            catch(NumberFormatException malformed){throw new AssertionError("diagnostic value",malformed);}
            require(entries.putIfAbsent(key,value)==null,"duplicate diagnostic key");
        }
        return entries;
    }

    /** Own bindings remain factual; only complete typed semantic components cross the policy boundary. */
    static void assertSemanticOutputPreserved(Format15Archive baseline,FrozenReplayInput oldInput,
            Format15Archive candidate,FrozenReplayInput newInput,boolean changed) {
        assertInputPreserved(oldInput,newInput,changed);
        var before=boundComponents(baseline,oldInput);var after=boundComponents(candidate,newInput);
        require(before.binding().rootFingerprint().equals(after.binding().rootFingerprint()),"complete final root fingerprint");
        var complete=EnumSet.allOf(FinalOutputComponentsCodec.Component.class);
        require(before.digests().keySet().equals(complete)&&after.digests().keySet().equals(complete)
            &&before.digests().equals(after.digests()),"every semantic component");
        require(before.summary().equals(after.summary()),"complete quality summary");
        for(String name:List.of("raw-route.json","final-route.json"))
            require(Arrays.equals(baseline.artifact(name).orElseThrow().bytes(),candidate.artifact(name).orElseThrow().bytes()),"exact artifact: "+name);
        require(Format15NumericalPolicyReceipt.validate(baseline,oldInput,oldInput.canonicalHash())==TrackerMode.PROBABILISTIC
            &&Format15NumericalPolicyReceipt.validate(candidate,newInput,newInput.canonicalHash())==TrackerMode.PROBABILISTIC,"native numerical attestations");
        var oldRuns=Format15ExecutionWorkCodec.read(baseline.artifact(Format15ExecutionWorkCodec.ARTIFACT).orElseThrow());
        var newRuns=Format15ExecutionWorkCodec.read(candidate.artifact(Format15ExecutionWorkCodec.ARTIFACT).orElseThrow());
        require(oldRuns.size()==1&&newRuns.size()==1,"single native work run");
        var oldWork=oldRuns.get(0).orElseThrow();var newWork=newRuns.get(0).orElseThrow();
        require(oldWork.bStages().size()==1&&newWork.bStages().size()==1
            &&oldWork.otherEngineTransitions()==0&&newWork.otherEngineTransitions()==0,"standalone native B work");
        var oldStage=oldWork.bStages().get(0);var newStage=newWork.bStages().get(0);
        if(changed) {
            require(oldStage.executionPolicy().equals(BPolicy.FRONTIER_COMPACT_VERSION),"baseline native compact V5");
            require(new InferenceWorkAccounting(BPolicy.BUDGETED_FRONTIER_COMPACT_VERSION,oldStage.forwardBackwardScores(),
                oldStage.kBestScores(),oldStage.logicalKBestExtensions(),oldStage.refusedScoreAttempts()).equals(newStage),"exact native work with typed V6 lineage");
        } else require(oldWork.equals(newWork),"same-policy native work");
        require(oldWork.computedTransitions()==before.output().inference().evaluatedTransitions()
            &&newWork.computedTransitions()==after.output().inference().evaluatedTransitions(),"components/work binding");
        String oldExplanation=before.output().inference().explanation(),newExplanation=after.output().inference().explanation();
        if(changed) {
            Format15BRefinementBudgetReceipt.validateActual(candidate,List.of(after.output().inference()));
            var witness=BRefinementBudget.fromExplanation(newExplanation).orElseThrow();
            require(witness.outcome()==BRefinementBudget.Outcome.ORIGINAL_REFINEMENT,"preservation requires original refinement");
            String marker="; "+BPolicy.FRONTIER_RETENTION_VERSION+"; posterior=";
            int index=oldExplanation.indexOf(marker);
            require(index>=0&&index==oldExplanation.lastIndexOf(marker),"one exact V5 compact explanation lineage");
            String expected=oldExplanation.substring(0,index)+"; "+BPolicy.BUDGETED_FRONTIER_VERSION+"; posterior="
                +oldExplanation.substring(index+marker.length())+"\n"+witness.canonical();
            require(newExplanation.equals(expected),"exact V6 explanation and additive canonical witness");
            String provenance="originalIndexHash="+witness.originalIndexHash();
            require(oldExplanation.startsWith(DenseBAdmission.witnessFromExplanation(oldExplanation)+"\n"+provenance+"; ")
                &&oldExplanation.contains("; refinements=1; "+provenance+"; "),"unchanged native provenance lineage");
        } else require(oldExplanation.equals(newExplanation),"same-policy explanation");
    }
    private static FinalOutputComponentsCodec.Snapshot boundComponents(Format15Archive archive,FrozenReplayInput input) {
        require(Arrays.equals(archive.artifact("frozen-input.bin").orElseThrow().bytes(),FrozenReplayCodec.encode(input)),"factual frozen archive/input");
        var expectation=FinalReplayExpectation.read(archive).orElseThrow();
        expectation.validateBinding(archive,input);
        require(expectation.capturedEngine()==TrackerMode.PROBABILISTIC&&expectation.requestedEngine()==TrackerMode.PROBABILISTIC,"factual native engine binding");
        var snapshot=FinalOutputComponentsCodec.decode(archive.artifact(FinalOutputComponentsCodec.ARTIFACT).orElseThrow().bytes(),expectation.componentsBinding());
        require(expectation.matches(snapshot.output()),"complete own root/output binding");
        return snapshot;
    }

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
            if(pin.getKey().equals("quality-topology.txt"))assertQualityTextPreserved(
                Files.readString(baselineDirectory.resolve(pin.getKey())),Files.readString(candidateDirectory.resolve(pin.getKey())));
            else require(Files.mismatch(baselineDirectory.resolve(pin.getKey()),candidateDirectory.resolve(pin.getKey()))==-1,"semantic file: "+pin.getKey());
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
        assertSemanticOutputPreserved(baseline,oldInput,candidate,newInput,parameterPolicyChanged);
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

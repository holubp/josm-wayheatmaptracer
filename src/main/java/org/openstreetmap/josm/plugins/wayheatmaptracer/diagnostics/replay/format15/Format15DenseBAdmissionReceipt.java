package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.Map;
import java.util.Set;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.*;

/** Additive witness only for the new dense-admission policy; old captures need no companion. */
final class Format15DenseBAdmissionReceipt {
    static final String ARTIFACT="b-dense-admission.json";
    private static final int MAX_BYTES=8*1024*1024;
    private static final Set<String> KEYS=Set.of("schema","sourceIdentityHash","inputHash","parameterHash","executionWorkHash","witness");
    private Format15DenseBAdmissionReceipt() { }
    static boolean required(String parameterHash){String policy=BPolicy.adaptiveVersion(parameterHash);return BPolicy.DENSE_ADMISSION_VERSION.equals(policy)||BPolicy.INVENTORY_PREP_VERSION.equals(policy)||BPolicy.INVENTORY_FRONTIER_VERSION.equals(policy);}
    static Format15Artifact artifact(Format15Bundle bundle,FrozenReplayInput input,TraceHypothesisSet run) {
        String witness=DenseBAdmission.witnessFromExplanation(run.explanation());
        requireWork(witness,input,run.workAccounting().orElseThrow());
        String json="{\"schema\":1,\"sourceIdentityHash\":\""+bundle.sourceIdentityHash()+"\",\"inputHash\":\""+input.canonicalHash()
                +"\",\"parameterHash\":\""+input.request().parameterHash()+"\",\"executionWorkHash\":\""
                +bundle.artifact(Format15ExecutionWorkCodec.ARTIFACT).sha256()+"\",\"witness\":\""+witness+"\"}\n";
        var artifact=Format15Artifact.text(ARTIFACT,json);
        if(artifact.sizeBytes()>MAX_BYTES)throw new IllegalArgumentException("dense-admission-receipt-budget");
        return artifact;
    }
    static void validate(Format15Archive archive,FrozenReplayInput input,String inputHash) {
        try {
            var declared=read(archive);
            if(!archive.sourceIdentityHash().equals(declared.get("sourceIdentityHash"))
                    ||!inputHash.equals(declared.get("inputHash"))||!input.request().parameterHash().equals(declared.get("parameterHash"))
                    ||!archive.artifact(Format15ExecutionWorkCodec.ARTIFACT).orElseThrow().sha256().equals(declared.get("executionWorkHash")))
                throw new IllegalArgumentException("binding");
            var runs=Format15ExecutionWorkCodec.read(archive.artifact(Format15ExecutionWorkCodec.ARTIFACT).orElseThrow());
            if(runs.size()!=1)throw new IllegalArgumentException("run count");
            requireWork((String)declared.get("witness"),input,runs.get(0).orElseThrow());
        }catch(RuntimeException malformed){throw new ReplayMismatchException("dense-admission-receipt-invalid");}
    }
    static void validateActual(Format15Archive archive,java.util.List<TraceHypothesisSet> actual) {
        if(!required(archive.parameterHash())||actual.isEmpty()||actual.get(0).engine()!=TrackerMode.PROBABILISTIC)return;
        try {
            if(actual.size()!=1||!DenseBAdmission.witnessFromExplanation(actual.get(0).explanation()).equals(read(archive).get("witness")))
                throw new IllegalArgumentException("actual census or decision differs");
        }catch(RuntimeException malformed){throw new ReplayMismatchException("dense-admission-runtime-witness-mismatch");}
    }
    private static Map<String,Object> read(Format15Archive archive) {
        var artifact=archive.artifact(ARTIFACT).orElseThrow();
        if(artifact.sizeBytes()>MAX_BYTES)throw new IllegalArgumentException("budget");
        try {
            var parsed=Format15ArchiveReader.parseObject(artifact.bytes(),ARTIFACT);
            if(!parsed.keySet().equals(KEYS)||!Long.valueOf(1).equals(parsed.get("schema"))||!(parsed.get("witness") instanceof String))
                throw new IllegalArgumentException("schema");
            return parsed;
        }catch(Format15ArchiveException malformed){throw new IllegalArgumentException("schema",malformed);}
    }
    private static void requireWork(String witness,FrozenReplayInput input,ExecutionWorkReceipt work) {
        var bounds=DenseBAdmission.validateWitness(witness,input.request().budgets(),input.request().profileChainage().cumulativeGroundMeters().size());
        var policy=BPolicy.recorded(input.request());
        String expected=bounds.admitted()?policy.fullExecutionVersion():policy.compactExecutionVersion();
        if(work.bStages().size()!=1||work.otherEngineTransitions()!=0||!work.hasPolicy(expected)
                ||bounds.admitted()&&(work.computedTransitions()>bounds.scoreUpper()||work.bStages().get(0).refusedScoreAttempts()!=0))
            throw new IllegalArgumentException("selected backend work differs");
    }
}

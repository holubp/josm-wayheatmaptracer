package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.*;

/** Bounded additive receipt binding the explicit V6 native decision to input and actual work. */
final class Format15BRefinementBudgetReceipt {
    static final String ARTIFACT="b-refinement-budget.json";
    private static final int MAX_BYTES=16*1024;
    private static final Set<String> KEYS=Set.of("schema","sourceIdentityHash","inputHash","parameterHash","executionWorkHash","witness");
    private Format15BRefinementBudgetReceipt() { }
    static boolean required(String parameterHash) { return BPolicy.BUDGETED_FRONTIER_VERSION.equals(BPolicy.adaptiveVersion(parameterHash)); }
    static Format15Artifact artifact(Format15Bundle bundle,FrozenReplayInput input,TraceHypothesisSet run) {
        var witness=BRefinementBudget.fromExplanation(run.explanation()).orElseThrow();
        requireWork(witness,input,run.workAccounting().orElseThrow());
        String json="{\"schema\":1,\"sourceIdentityHash\":\""+bundle.sourceIdentityHash()+"\",\"inputHash\":\""+input.canonicalHash()
            +"\",\"parameterHash\":\""+input.request().parameterHash()+"\",\"executionWorkHash\":\""
            +bundle.artifact(Format15ExecutionWorkCodec.ARTIFACT).sha256()+"\",\"witness\":\""+witness.canonical()+"\"}\n";
        var artifact=Format15Artifact.text(ARTIFACT,json);
        if(artifact.sizeBytes()>MAX_BYTES)throw new IllegalArgumentException("refinement-budget-receipt-budget");
        return artifact;
    }
    static void validate(Format15Archive archive,FrozenReplayInput input,String inputHash) {
        try {
            var declared=read(archive);
            if(!archive.sourceIdentityHash().equals(declared.get("sourceIdentityHash"))||!inputHash.equals(declared.get("inputHash"))
                    ||!input.request().parameterHash().equals(declared.get("parameterHash"))
                    ||!archive.artifact(Format15ExecutionWorkCodec.ARTIFACT).orElseThrow().sha256().equals(declared.get("executionWorkHash")))
                throw new IllegalArgumentException("binding");
            var work=Format15ExecutionWorkCodec.read(archive.artifact(Format15ExecutionWorkCodec.ARTIFACT).orElseThrow());
            if(work.size()!=1)throw new IllegalArgumentException("run count");
            requireWork(BRefinementBudget.parse((String)declared.get("witness")),input,work.get(0).orElseThrow());
        } catch(RuntimeException malformed) { throw new ReplayMismatchException("refinement-budget-receipt-invalid"); }
    }
    static void validateActual(Format15Archive archive,List<TraceHypothesisSet> actual) {
        if(!required(archive.parameterHash()))return;
        try {
            if(actual.size()!=1||actual.get(0).engine()!=TrackerMode.PROBABILISTIC
                    ||!BRefinementBudget.fromExplanation(actual.get(0).explanation()).orElseThrow().canonical().equals(read(archive).get("witness")))
                throw new IllegalArgumentException("actual decision or retained-index binding differs");
        } catch(RuntimeException malformed) { throw new ReplayMismatchException("refinement-budget-runtime-witness-mismatch"); }
    }
    private static void requireWork(BRefinementBudget.Witness witness,FrozenReplayInput input,ExecutionWorkReceipt work) {
        boolean dense=witness.outcome()==BRefinementBudget.Outcome.DENSE;
        String expected=dense?BPolicy.BUDGETED_FRONTIER_FULL_VERSION:BPolicy.BUDGETED_FRONTIER_COMPACT_VERSION;
        if(!required(input.request().parameterHash())||!work.hasPolicy(expected))
            throw new IllegalArgumentException("policy binding");
        BRefinementBudget.validateAccounting(witness,input.request().budgets(),work);
    }
    private static Map<String,Object> read(Format15Archive archive) {
        var artifact=archive.artifact(ARTIFACT).orElseThrow();
        if(artifact.sizeBytes()>MAX_BYTES)throw new IllegalArgumentException("budget");
        try {
            var parsed=Format15ArchiveReader.parseObject(artifact.bytes(),ARTIFACT);
            if(!parsed.keySet().equals(KEYS)||!Long.valueOf(1).equals(parsed.get("schema"))||!(parsed.get("witness") instanceof String))
                throw new IllegalArgumentException("schema");
            BRefinementBudget.parse((String)parsed.get("witness"));
            return parsed;
        } catch(Format15ArchiveException malformed) { throw new IllegalArgumentException("schema",malformed); }
    }
}

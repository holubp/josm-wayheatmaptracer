package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** New recorded admission policy; old adaptive entry points remain unchanged. */
final class AdmittedBInference {
    AdaptiveBInference.Result solve(List<ProbabilisticProfile> sampled,List<InferenceProfile> dense,
            List<ProbabilisticStateLattice> lattices,double[] continuation,EvidenceModelParameters parameters,
            TraceBudgets budgets,MetricRegion region,CancellationProbe cancel,AttemptMemoryLedger.Owner owner,
            AdaptiveBObserver observer,ProbabilisticInference.WorkProgress progress) {
        return solve(sampled,dense,lattices,continuation,parameters,budgets,region,cancel,owner,observer,progress,BPolicy.DENSE_ADMITTED_V3);
    }
    AdaptiveBInference.Result solve(List<ProbabilisticProfile> sampled,List<InferenceProfile> dense,
            List<ProbabilisticStateLattice> lattices,double[] continuation,EvidenceModelParameters parameters,
            TraceBudgets budgets,MetricRegion region,CancellationProbe cancel,AttemptMemoryLedger.Owner owner,
            AdaptiveBObserver observer,ProbabilisticInference.WorkProgress progress,BPolicy policy) {
        String fullVersion=policy.fullExecutionVersion(),compactVersion=policy.compactExecutionVersion();
        Progress actual=null;
        try(var scope=owner.child("dense-admission-policy")) {
            actual=ProbabilisticInference.allocated(scope,AttemptMemoryLedger.objectBytes(32),"admitted work progress",()->new Progress(progress));
            var bound=DenseBAdmission.estimate(dense,budgets,cancel,scope);
            String witness=DenseBAdmission.witness(dense,bound,cancel,scope);
            AdaptiveBInference.Result run;
            if(bound.admitted()) {
                var hash=ProbabilisticInference.allocated(scope,1024,"full dense provenance",AdmittedBInference::digest);
                hash.update(fullVersion.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                for(int row=0;row<dense.size();row++) {
                    integer(hash,row);integer(hash,dense.get(row).cells().size());
                    for(int state=0;state<dense.get(row).cells().size();state++) {
                        cancel.checkpoint();integer(hash,state);observer.retainedCell(0,row,state,state);
                    }
                }
                var inference=new ProbabilisticInference().solveCachedVersion(dense,parameters,budgets,region,cancel,scope,
                        ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,actual);
                String provenance=ProbabilisticInference.allocated(scope,256,
                        "full dense provenance hash",()->HexFormat.of().formatHex(hash.digest()));
                run=ProbabilisticInference.allocated(scope,AttemptMemoryLedger.objectBytes(48),"full dense run result",
                        ()->new AdaptiveBInference.Result(inference,dense,0,inference.evaluatedTransitions(),0,provenance));
            } else {
                run=new AdaptiveBInference().solve(sampled,dense,lattices,continuation,parameters,budgets,region,cancel,scope,
                        observer,actual,BPolicy.ADAPTIVE_MULTIMODAL_V2);
            }
            String explanation=ProbabilisticInference.allocated(scope,
                    AttemptMemoryLedger.objectBytes(24)+AttemptMemoryLedger.arrayBytes(Math.addExact(witness.length()+128,run.inference().explanation().length()),2),
                    "admitted explanation",()->witness+"\noriginalIndexHash="+run.provenanceHash()+"; "+run.inference().explanation());
            var inference=run.inference().withExecutionIdentity(explanation,
                    bound.admitted()?fullVersion:compactVersion,scope);
            var source=run;
            var result=ProbabilisticInference.allocated(scope,AttemptMemoryLedger.objectBytes(48),"admitted run result",
                    ()->new AdaptiveBInference.Result(inference,source.profiles(),source.refinements(),source.firstScores(),source.secondScores(),source.provenanceHash()));
            scope.transferTo(owner);return result;
        } catch(TraceMemoryLimitException failure) {
            throw new TraceMemoryLimitException("dense admission policy",Math.max(failure.pairVisits(),actual==null?0:actual.pairs),
                    Math.max(failure.transitions(),actual==null?0:actual.scores),owner.currentBytes(),owner.peakBytes(),
                    failure.limitBytes(),failure.getCause());
        } catch(ProbabilisticInference.MemoryLimit failure) {
            var refusal=failure.limitCause();
            throw new TraceMemoryLimitException("dense admission policy",actual==null?0:actual.pairs,actual==null?0:actual.scores,
                    owner.currentBytes(),owner.peakBytes(),refusal.limitBytes(),refusal);
        }
    }
    private static final class Progress implements ProbabilisticInference.WorkProgress {
        final ProbabilisticInference.WorkProgress delegate;long pairs,scores;
        Progress(ProbabilisticInference.WorkProgress delegate){this.delegate=delegate;}
        public void computed(long pairs,long scores){this.pairs=pairs;this.scores=scores;delegate.computed(pairs,scores);}
    }
    private static MessageDigest digest() {
        try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static void integer(MessageDigest hash,int n){hash.update((byte)(n>>>24));hash.update((byte)(n>>>16));hash.update((byte)(n>>>8));hash.update((byte)n);}
}

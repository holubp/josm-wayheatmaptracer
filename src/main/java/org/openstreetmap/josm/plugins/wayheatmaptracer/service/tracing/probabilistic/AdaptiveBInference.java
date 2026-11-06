package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

/** One conditional retained-graph solve and at most one all-rival refinement under one attempt budget. */
final class AdaptiveBInference {
    private static final byte[] HASH_DOMAIN = (BPolicy.VERSION + "/original-cell-indices-v1")
            .getBytes(StandardCharsets.UTF_8);
    record Result(ProbabilisticInferenceResult inference, List<InferenceProfile> profiles,
            int refinements, long firstScores, long secondScores, String provenanceHash) { }
    Result solve(List<ProbabilisticProfile> sampled, List<InferenceProfile> dense,
            List<ProbabilisticStateLattice> lattices, double[] continuation,
            EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion region,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        return solve(sampled,dense,lattices,continuation,parameters,budgets,region,cancellation,owner,AdaptiveBObserver.NONE);
    }
    Result solve(List<ProbabilisticProfile> sampled, List<InferenceProfile> dense,
            List<ProbabilisticStateLattice> lattices, double[] continuation,
            EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion region,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner, AdaptiveBObserver observer) {
        return solve(sampled,dense,lattices,continuation,parameters,budgets,region,cancellation,owner,
                observer,ProbabilisticInference.WorkProgress.NONE);
    }
    Result solve(List<ProbabilisticProfile> sampled, List<InferenceProfile> dense,
            List<ProbabilisticStateLattice> lattices, double[] continuation,
            EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion region,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner, AdaptiveBObserver observer,
            ProbabilisticInference.WorkProgress progress) {
        cancellation.checkpoint();
        if (sampled.size() != dense.size() || dense.size() != lattices.size()
                || continuation.length != dense.size() || observer == null)
            throw new IllegalArgumentException("Adaptive evidence rows disagree");
        var views = list(dense.size(), owner);
        for (int row = 0; row < dense.size(); row++) {
            cancellation.checkpoint();
            views.add(AdaptiveSolverView.initial(sampled.get(row),dense.get(row),
                    lattices.get(row).components(),continuation[row],cancellation,owner));
        }
        var profiles = profiles(views,owner);
        MessageDigest hash = ProbabilisticInference.allocated(owner,512,"adaptive provenance",AdaptiveBInference::digest);
        hash.update(HASH_DOMAIN); provenance(hash,views,0,cancellation,observer);
        ModernDiagnosticCounters.record("b.adaptive.denseStates", states(dense));
        ModernDiagnosticCounters.record("b.adaptive.initialStates", states(profiles));
        ModernDiagnosticCounters.record("b.adaptive.activePass",0);
        var first = new ProbabilisticInference().solveCached(profiles,parameters,budgets,region,cancellation,owner,progress);
        long firstScores = first.evaluatedTransitions(), secondScores = 0;
        var work = first.workAccounting().orElseThrow();
        long pairs = first.evaluatedPairVisits();
        int refinements = 0;
        var result = first;
        try (var scratch = owner.child("all-rival-refinement-plan")) {
            int rowCount = views.size();
            boolean[][] centers = ProbabilisticInference.allocated(scratch,
                    AttemptMemoryLedger.referenceArrayBytes(rowCount),"adaptive rival masks",
                    () -> new boolean[rowCount][]);
            boolean refine = false;
            if (usable(first)) {
                for (int row = 0; row < views.size(); row++) {
                    cancellation.checkpoint(); var view = views.get(row); int n = view.size();
                    centers[row] = ProbabilisticInference.allocated(scratch,
                            AttemptMemoryLedger.arrayBytes(n,Byte.BYTES),"adaptive rival masks",() -> new boolean[n]);
                    double[] ordered = ProbabilisticInference.allocated(scratch,
                            AttemptMemoryLedger.arrayBytes(n,Double.BYTES),"adaptive credible threshold",() -> new double[n]);
                    for (int i = 0; i < n; i++) ordered[i] = first.positionMarginalAt(row,i);
                    Arrays.sort(ordered); double mass = 0, threshold = Double.POSITIVE_INFINITY;
                    for (int i = n - 1; i >= 0 && mass < .95; i--) { mass += ordered[i]; threshold = ordered[i]; }
                    for (int i = 0; i < n; i++) if (!view.fine(i)
                            && first.positionMarginalAt(row,i) > 0 && first.positionMarginalAt(row,i) >= threshold)
                        centers[row][i] = true;
                    for (var path : first.rawPaths()) {
                        int state = path.stateIndexAt(row);
                        if (!view.fine(state)) centers[row][state] = true;
                    }
                    for (boolean center : centers[row]) refine |= center;
                }
            }
            if (refine) {
                long remainingScores = budgets.maximumTransitions() - firstScores;
                long remainingPairs = budgets.maximumPairVisits() - pairs;
                if (remainingScores <= 0 || remainingPairs <= 0) {
                    return finish(first,profiles,0,firstScores,0,hash,work,pairs,
                            "refinement-budget-exhausted",owner);
                }
                var refined = list(views.size(),owner);
                for (int row = 0; row < views.size(); row++) {
                    cancellation.checkpoint();
                    refined.add(AdaptiveSolverView.refined(sampled.get(row),dense.get(row),views.get(row),
                            centers[row],cancellation,owner));
                }
                views = refined; profiles = profiles(views,owner); refinements = 1;
                provenance(hash,views,1,cancellation,observer);
                var remaining = new TraceBudgets(budgets.maximumStatesPerProfile(),remainingPairs,
                        remainingScores,budgets.maximumRawAlternatives(),budgets.maximumDistinctAlternatives());
                long completedPairs = pairs;
                var cumulativeProgress = ProbabilisticInference.allocated(owner,AttemptMemoryLedger.objectBytes(24),
                        "adaptive cumulative progress",() -> (ProbabilisticInference.WorkProgress)(nowPairs,nowScores) ->
                            progress.computed(Math.addExact(completedPairs,nowPairs),Math.addExact(firstScores,nowScores)));
                ModernDiagnosticCounters.record("b.adaptive.activePass",1);
                result = new ProbabilisticInference().solveCached(profiles,parameters,remaining,region,cancellation,owner,
                        cumulativeProgress);
                secondScores = result.evaluatedTransitions(); pairs = Math.addExact(pairs,result.evaluatedPairVisits());
                var second = result.workAccounting().orElseThrow();
                work = new InferenceWorkAccounting(BPolicy.VERSION,
                        Math.addExact(work.forwardBackwardScores(),second.forwardBackwardScores()),
                        Math.addExact(work.kBestScores(),second.kBestScores()),
                        Math.addExact(work.logicalKBestExtensions(),second.logicalKBestExtensions()),
                        Math.addExact(work.refusedScoreAttempts(),second.refusedScoreAttempts()));
            }
        }
        return finish(result,profiles,refinements,firstScores,secondScores,hash,work,pairs,
                result.explanation(),owner);
    }

    private static Result finish(ProbabilisticInferenceResult result,List<InferenceProfile> profiles,
            int refinements,long first,long second,MessageDigest digest,InferenceWorkAccounting work,
            long pairs,String reason,AttemptMemoryLedger.Owner owner) {
        String hash = HexFormat.of().formatHex(digest.digest());
        var cumulative = new InferenceWorkAccounting(BPolicy.VERSION,work.forwardBackwardScores(),
                work.kBestScores(),work.logicalKBestExtensions(),work.refusedScoreAttempts());
        var status = "refinement-budget-exhausted".equals(reason) ? ProbabilisticInferenceResult.Status.RESOURCE_LIMIT
                : usable(result) ? ProbabilisticInferenceResult.Status.REVIEW_REQUIRED : result.status();
        String explanation = BPolicy.VERSION + "; posterior=conditional-retained-subgraph; refinements="
                + refinements + "; originalIndexHash=" + hash + "; " + reason;
        var conditional = result.conditionalReview(status,explanation,cumulative,pairs,owner);
        ModernDiagnosticCounters.record("b.adaptive.refinements",refinements);
        ModernDiagnosticCounters.record("b.adaptive.firstScores",first);
        ModernDiagnosticCounters.record("b.adaptive.secondScores",second);
        ModernDiagnosticCounters.record("b.adaptive.finalStates",states(profiles));
        return ProbabilisticInference.allocated(owner,AttemptMemoryLedger.objectBytes(48)
                + AttemptMemoryLedger.objectBytes(24) + AttemptMemoryLedger.arrayBytes(hash.length(),2),
                "adaptive run result",() -> new Result(conditional,profiles,refinements,first,second,hash));
    }
    private static boolean usable(ProbabilisticInferenceResult r) {
        return r.status() == ProbabilisticInferenceResult.Status.COMPLETE
                || r.status() == ProbabilisticInferenceResult.Status.AMBIGUOUS
                || r.status() == ProbabilisticInferenceResult.Status.REVIEW_REQUIRED;
    }
    private static List<AdaptiveSolverView> list(int n,AttemptMemoryLedger.Owner owner) {
        return ProbabilisticInference.allocated(owner,ProbabilisticInference.listBytes(n),
                "adaptive view list",() -> new ArrayList<>(n));
    }
    private static List<InferenceProfile> profiles(List<AdaptiveSolverView> views,AttemptMemoryLedger.Owner owner) {
        var result = ProbabilisticInference.allocated(owner,ProbabilisticInference.listBytes(views.size()),
                "adaptive solver profiles",() -> new ArrayList<InferenceProfile>(views.size()));
        for (var view : views) result.add(view.profile());
        return result;
    }
    private static long states(List<InferenceProfile> profiles) {
        long n = 0; for (var p : profiles) n = Math.addExact(n,p.cells().size()); return n;
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static void integer(MessageDigest hash,int n) {
        hash.update((byte)(n >>> 24)); hash.update((byte)(n >>> 16));
        hash.update((byte)(n >>> 8)); hash.update((byte)n);
    }
    private static void provenance(MessageDigest hash,List<AdaptiveSolverView> views,int pass,
            CancellationProbe cancellation,AdaptiveBObserver observer) {
        integer(hash,pass); integer(hash,views.size());
        for (int row = 0; row < views.size(); row++) {
            var view = views.get(row); integer(hash,view.size());
            for (int i = 0; i < view.size(); i++) {
                cancellation.checkpoint(); int original = view.sourceIndexAt(i); integer(hash,original);
                observer.retainedCell(pass,row,i,original);
            }
        }
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Deterministic transition-budget admission; never executes inference or prunes evidence. */
final class ProbabilisticLatticeAdmission {
    /** A negative bound is unavailable, never measured zero. */
    static final class Plan {
        private final int maximumStates;
        private final int[] minimumStates;
        private final boolean coarsened, admitted;
        private final long nativeForwardBackwardBound, completeScoreBound, pairBound;
        private Plan(int maximumStates, int[] minimumStates, boolean coarsened, boolean admitted,
                long nativeForwardBackwardBound, long completeScoreBound, long pairBound) {
            this.maximumStates = maximumStates; this.minimumStates = minimumStates;
            this.coarsened = coarsened; this.admitted = admitted;
            this.nativeForwardBackwardBound = nativeForwardBackwardBound;
            this.completeScoreBound = completeScoreBound; this.pairBound = pairBound;
        }
        int maximumStates() { return maximumStates; }
        int maximumStates(int index) { return minimumStates == null ? maximumStates
                : Math.max(maximumStates, minimumStates[index]); }
        boolean coarsened() { return coarsened; }
        boolean admitted() { return admitted; }
        long nativeForwardBackwardBound() { return nativeForwardBackwardBound; }
        long completeScoreBound() { return completeScoreBound; }
        long pairBound() { return pairBound; }
    }

    private ProbabilisticLatticeAdmission() { }

    static Plan select(List<ProbabilisticProfile> profiles, double[] proposals, TraceBudgets budgets,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        if (profiles == null || profiles.isEmpty() || proposals == null || proposals.length != profiles.size()
                || budgets == null || cancellation == null)
            throw new IllegalArgumentException("Lattice admission inputs are incomplete");
        var inventory = owner == null ? null : owner.child("transition-lattice-admission");
        try {
            cancellation.checkpoint();
            int[] counts = inventory == null ? new int[profiles.size()]
                    : ProbabilisticInference.allocated(inventory,
                        AttemptMemoryLedger.arrayBytes(profiles.size(), Integer.BYTES), "state admission",
                        () -> new int[profiles.size()]);
            int originalLimit = Math.min(96, budgets.maximumStatesPerProfile());
            var builder = inventory == null ? new ProbabilisticStateBuilder()
                    : ProbabilisticInference.allocated(inventory, AttemptMemoryLedger.objectBytes(8),
                        "state admission", ProbabilisticStateBuilder::new);
            long nativeForwardBackward;
            if (!inventory(profiles, proposals, originalLimit, null, counts, builder, cancellation, inventory, true)) {
                // A proposal-only failure must not erase the original native baseline's work classification.
                if (!inventory(profiles, proposals, originalLimit, null, counts, builder,
                        cancellation, inventory, false)) {
                    return plan(originalLimit, null, false, false, -1, -1, -1, owner);
                }
                nativeForwardBackward = add(initial(counts), multiply(2, triples(counts, cancellation)));
                long nativePairs = pairs(counts, cancellation);
                if (fits(nativeForwardBackward, budgets.maximumTransitions())) {
                    // Preserve the complete native baseline and refuse the proposal; do not coarsen solely for it.
                    return plan(originalLimit, null, false, false, nativeForwardBackward, -1,
                            nativePairs, owner);
                }
            } else {
                nativeForwardBackward = add(initial(counts), multiply(2, triples(counts, cancellation)));
                // Compatibility: a native lattice whose FB upper bound fits keeps its exact old positions.
                // This branch does not promise k-best completion; ordinary runtime guards remain authoritative.
                if (fits(nativeForwardBackward, budgets.maximumTransitions()))
                    return plan(originalLimit, null, false, true, nativeForwardBackward, -1,
                            pairs(counts, cancellation), owner);
            }
            return selectCoarsened(profiles, proposals, budgets, cancellation, owner,
                    inventory, originalLimit, counts, builder, nativeForwardBackward);
        } finally {
            if (inventory != null) inventory.close();
        }
    }

    private static Plan plan(int ceiling, int[] minimum, boolean coarsened, boolean admitted, long nativeBound,
            long complete, long pairs, AttemptMemoryLedger.Owner owner) {
        var resultOwner = owner == null ? null : owner.child("state-admission-result");
        try {
            int[] retained = minimum == null ? null : resultOwner == null ? minimum.clone()
                    : ProbabilisticInference.allocated(resultOwner,
                        AttemptMemoryLedger.arrayBytes(minimum.length, Integer.BYTES), "state admission", minimum::clone);
            Plan result = resultOwner == null ? new Plan(ceiling, retained, coarsened, admitted, nativeBound, complete, pairs)
                    : ProbabilisticInference.allocated(resultOwner, AttemptMemoryLedger.objectBytes(48), "state admission",
                        () -> new Plan(ceiling, retained, coarsened, admitted, nativeBound, complete, pairs));
            if (resultOwner != null) resultOwner.transferTo(owner);
            return result;
        } finally { if (resultOwner != null) resultOwner.close(); }
    }

    private static boolean inventory(List<ProbabilisticProfile> profiles, double[] proposals, int ceiling,
            int[] minimum, int[] counts, ProbabilisticStateBuilder builder,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner, boolean includeProposals) {
        for (int index = 0; index < profiles.size(); index++) {
            cancellation.checkpoint();
            var query = owner == null ? null : owner.child("profile-lattice-inventory");
            try {
                int effective = minimum == null ? ceiling : Math.max(ceiling, minimum[index]);
                var result = minimum != null ? builder.buildWithReservedProposal(profiles.get(index), effective,
                        proposals[index], query) : includeProposals
                                ? builder.buildWithProposal(profiles.get(index), ceiling, proposals[index], query)
                                : builder.build(profiles.get(index), ceiling, query);
                if (result.status() != StateSpaceBuildResult.Status.COMPLETE) return false;
                counts[index] = result.lattice().orElseThrow().cells().size();
            } finally { if (query != null) query.close(); }
        }
        return true;
    }

    private static Plan selectCoarsened(List<ProbabilisticProfile> profiles, double[] proposals,
            TraceBudgets budgets, CancellationProbe cancellation, AttemptMemoryLedger.Owner owner,
            AttemptMemoryLedger.Owner inventory, int originalLimit, int[] counts,
            ProbabilisticStateBuilder builder, long nativeForwardBackward) {
        int[] minimum = inventory == null ? new int[profiles.size()]
                : ProbabilisticInference.allocated(inventory,
                    AttemptMemoryLedger.arrayBytes(profiles.size(), Integer.BYTES), "state admission",
                    () -> new int[profiles.size()]);
        for (int index = 0; index < profiles.size(); index++) {
            cancellation.checkpoint();
            var query = inventory == null ? null : inventory.child("mandatory-profile-inventory");
            try { minimum[index] = builder.minimumReservedStateLimit(profiles.get(index), proposals[index], query); }
            finally { if (query != null) query.close(); }
            if (minimum[index] > originalLimit)
                return plan(originalLimit, null, true, false, nativeForwardBackward, -1, -1, owner);
        }
        for (int ceiling = originalLimit; ceiling >= 2; ceiling--) {
            cancellation.checkpoint();
            if (!inventory(profiles, proposals, ceiling, minimum, counts, builder,
                    cancellation, inventory, true)) continue;
            long complete = add(multiply(2, initial(counts)),
                    multiply(2L + Math.min(32, budgets.maximumRawAlternatives()), triples(counts, cancellation)));
            long pairs = pairs(counts, cancellation);
            if (fits(complete, budgets.maximumTransitions()) && fits(pairs, budgets.maximumPairVisits()))
                return plan(ceiling, minimum, true, true, nativeForwardBackward, complete, pairs, owner);
        }
        return plan(originalLimit, null, true, false, nativeForwardBackward, -1, -1, owner);
    }

    private static long initial(int[] counts) {
        return counts.length == 1 ? counts[0] : multiply(counts[0], counts[1]);
    }

    private static long triples(int[] counts, CancellationProbe cancellation) {
        long result = 0;
        for (int index = 2; index < counts.length; index++) {
            cancellation.checkpoint();
            result = add(result, multiply(multiply(counts[index - 2], counts[index - 1]), counts[index]));
        }
        return result;
    }

    private static long pairs(int[] counts, CancellationProbe cancellation) {
        if (counts.length == 1) return counts[0];
        long result = 0;
        for (int index = 1; index < counts.length; index++) {
            cancellation.checkpoint();
            result = add(result, multiply(counts[index - 1], counts[index]));
        }
        return result;
    }

    private static boolean fits(long value, long limit) { return value < Long.MAX_VALUE && value <= limit; }
    private static long add(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }
    private static long multiply(long left, long right) {
        return left != 0 && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
    }
}

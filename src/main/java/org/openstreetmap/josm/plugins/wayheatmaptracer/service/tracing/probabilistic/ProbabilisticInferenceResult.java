package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Exact finite-graph inference result with explicit completeness and numerical diagnostics. */
public final class ProbabilisticInferenceResult {
    /** Solver completion state; missing evidence is distinct from resource exhaustion. */
    public enum Status { COMPLETE, AMBIGUOUS, REVIEW_REQUIRED, ALL_MISSING, NO_ROUTE, RESOURCE_LIMIT, NUMERIC_FAILURE }

    private final Status status;
    private final Optional<ProbabilisticPath> mapPath;
    private final List<ProbabilisticPath> rawPaths;
    private final List<ProbabilisticPath> distinctPaths;
    private final double logPartition;
    private final List<double[]> positionMarginals;
    private final List<double[]> componentMarginals;
    private final List<double[]> forwardPositionMarginals;
    private final List<double[]> backwardPositionMarginals;
    private final List<CredibleLateralSet> credibleSets;
    private final boolean posteriorUsable;
    private final boolean alternativeSearchTruncated;
    private final Optional<Completion> completion;
    private final long evaluatedPairVisits;
    private final long evaluatedTransitions;
    private final GapSummary gapSummary;
    private final String explanation;

    /** Creates a deeply immutable result. */
    public ProbabilisticInferenceResult(Status status, Optional<ProbabilisticPath> mapPath,
        List<ProbabilisticPath> rawPaths, List<ProbabilisticPath> distinctPaths, double logPartition,
        List<double[]> positionMarginals, List<double[]> componentMarginals, List<double[]> forwardPositionMarginals,
        List<double[]> backwardPositionMarginals, List<CredibleLateralSet> credibleSets,
        boolean posteriorUsable, boolean alternativeSearchTruncated, long evaluatedPairVisits,
        long evaluatedTransitions, GapSummary gapSummary, String explanation) {
        this(status, mapPath, rawPaths, distinctPaths, logPartition, positionMarginals,
            componentMarginals, forwardPositionMarginals, backwardPositionMarginals,
            credibleSets, posteriorUsable, alternativeSearchTruncated, Optional.empty(),
            evaluatedPairVisits, evaluatedTransitions, gapSummary, explanation);
    }

    /** Adds a bounded completed-terminal proof; older constructed results remain unattested. */
    public ProbabilisticInferenceResult(Status status, Optional<ProbabilisticPath> mapPath,
        List<ProbabilisticPath> rawPaths, List<ProbabilisticPath> distinctPaths, double logPartition,
        List<double[]> positionMarginals, List<double[]> componentMarginals, List<double[]> forwardPositionMarginals,
        List<double[]> backwardPositionMarginals, List<CredibleLateralSet> credibleSets,
        boolean posteriorUsable, boolean alternativeSearchTruncated, Optional<Completion> completion,
        long evaluatedPairVisits, long evaluatedTransitions, GapSummary gapSummary, String explanation) {
        if (status == null || mapPath == null || rawPaths == null || distinctPaths == null
            || positionMarginals == null || componentMarginals == null || forwardPositionMarginals == null
            || backwardPositionMarginals == null || credibleSets == null || completion == null || evaluatedPairVisits < 0
            || evaluatedTransitions < 0 || gapSummary == null || explanation == null) {
            throw new IllegalArgumentException("Inference result is incomplete");
        }
        if (completion.isPresent()) {
            Completion proof = completion.orElseThrow();
            int expectedRawPathCount = Math.min(proof.effectiveRawLimit(), proof.completePathsAtSaturation());
            boolean expectedDiversityReached = distinctPaths.size() == proof.effectiveDistinctLimit();
            boolean expectedAlternativeTruncation = proof.rawEnumerationCapped()
                && !proof.requestedDiversityReached();
            if (rawPaths.size() != expectedRawPathCount
                || distinctPaths.size() > proof.effectiveDistinctLimit()
                || proof.requestedDiversityReached() != expectedDiversityReached
                || alternativeSearchTruncated != expectedAlternativeTruncation) {
                throw new IllegalArgumentException("Inference result contradicts its completion proof");
            }
        }
        this.status = status;
        this.mapPath = mapPath;
        this.rawPaths = List.copyOf(rawPaths);
        this.distinctPaths = List.copyOf(distinctPaths);
        this.logPartition = logPartition;
        this.positionMarginals = deepCopy(positionMarginals);
        this.componentMarginals = deepCopy(componentMarginals);
        this.forwardPositionMarginals = deepCopy(forwardPositionMarginals);
        this.backwardPositionMarginals = deepCopy(backwardPositionMarginals);
        this.credibleSets = List.copyOf(credibleSets);
        this.posteriorUsable = posteriorUsable;
        this.alternativeSearchTruncated = alternativeSearchTruncated;
        this.completion = completion;
        this.evaluatedPairVisits = evaluatedPairVisits;
        this.evaluatedTransitions = evaluatedTransitions;
        this.gapSummary = gapSummary;
        this.explanation = explanation;
    }

    /** Returns solver status. */
    public Status status() { return status; }
    /** Returns the exact MAP-density path when available. */
    public Optional<ProbabilisticPath> mapPath() { return mapPath; }
    /** Returns bounded exact raw k-best paths. */
    public List<ProbabilisticPath> rawPaths() { return rawPaths; }
    /** Returns geometrically distinct retained routes. */
    public List<ProbabilisticPath> distinctPaths() { return distinctPaths; }
    /** Returns the log partition function including physical base measure. */
    public double logPartition() { return logPartition; }
    /** Returns defensive copies of posterior state marginals. */
    public List<double[]> positionMarginals() { return deepCopy(positionMarginals); }
    /** Returns posterior local-component marginals for each profile. */
    public List<double[]> componentMarginals() { return deepCopy(componentMarginals); }
    /** Returns forward-derived posterior state marginals. */
    public List<double[]> forwardPositionMarginals() { return deepCopy(forwardPositionMarginals); }
    /** Returns backward-derived posterior state marginals. */
    public List<double[]> backwardPositionMarginals() { return deepCopy(backwardPositionMarginals); }
    /** Returns disjoint 95% lateral credible sets. */
    public List<CredibleLateralSet> credibleSets() { return credibleSets; }
    /** Returns whether posterior claims are usable rather than all-missing or incomplete. */
    public boolean posteriorUsable() { return posteriorUsable; }
    /** Returns whether terminal enumeration exceeded K before requested diversity D was reached. */
    public boolean alternativeSearchTruncated() { return alternativeSearchTruncated; }
    /** Returns a terminal proof only for completed admitted-graph enumeration. */
    public Optional<Completion> completion() { return completion; }
    /** Returns pair-state visit count. */
    public long evaluatedPairVisits() { return evaluatedPairVisits; }
    /** Returns admitted graph transition count. */
    public long evaluatedTransitions() { return evaluatedTransitions; }
    /** Returns physical support/gap summary. */
    public GapSummary gapSummary() { return gapSummary; }
    /** Returns a bounded diagnostic explanation. */
    public String explanation() { return explanation; }

    /** Saturated terminal count and independent raw-cap/diversity facts. */
    public record Completion(int effectiveRawLimit, int effectiveDistinctLimit,
        int completePathsAtSaturation, boolean terminalCountSaturated,
        boolean rawEnumerationCapped, boolean requestedDiversityReached) {
        public Completion {
            if (effectiveRawLimit < 1 || effectiveRawLimit > 32 || effectiveDistinctLimit < 1
                || effectiveDistinctLimit > 8 || effectiveDistinctLimit > effectiveRawLimit
                || completePathsAtSaturation < 1
                || completePathsAtSaturation > effectiveRawLimit + 1
                || terminalCountSaturated != (completePathsAtSaturation == effectiveRawLimit + 1)
                || rawEnumerationCapped != terminalCountSaturated) {
                throw new IllegalArgumentException("Terminal completion proof is invalid");
            }
        }
    }

    private static List<double[]> deepCopy(List<double[]> values) {
        List<double[]> copy = new ArrayList<>(values.size());
        values.forEach(value -> copy.add(value.clone()));
        return List.copyOf(copy);
    }
}

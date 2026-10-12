package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ordered distinct alternatives and truthful completion status from one engine. */
public record TraceHypothesisSet(TrackerMode engine, List<TraceHypothesis> hypotheses,
    Status status, boolean alternativesTruncated, long evaluatedStates, long evaluatedTransitions,
    String explanation, java.util.Optional<ExecutionWorkReceipt> workAccounting) {
    /** Historic results have no attestation of the solver policy they executed. */
    public TraceHypothesisSet(TrackerMode engine, List<TraceHypothesis> hypotheses,
            Status status, boolean alternativesTruncated, long evaluatedStates,
            long evaluatedTransitions, String explanation) {
        this(engine, hypotheses, status, alternativesTruncated, evaluatedStates,
                evaluatedTransitions, explanation, java.util.Optional.empty());
    }
    /** Typed result status; resource exhaustion is never reported as convergence. */
    public enum Status { COMPLETE, AMBIGUOUS, NO_ROUTE, CANCELLED, RESOURCE_LIMIT }

    /** Copies alternatives and rejects contradictory status, route, resource, and identity declarations. */
    public TraceHypothesisSet {
        if (engine == null || hypotheses == null || status == null || evaluatedStates < 0
            || evaluatedTransitions < 0 || explanation == null || explanation.isBlank()
            || workAccounting == null) {
            throw new IllegalArgumentException("Trace hypothesis result is incomplete");
        }
        hypotheses = List.copyOf(hypotheses);
        if (workAccounting.isPresent() && (status == Status.CANCELLED
                || engine != TrackerMode.PROBABILISTIC && engine != TrackerMode.HYBRID)) {
            throw new IllegalArgumentException("This result cannot attest an executed B solver");
        }
        if (workAccounting.isPresent() && engine == TrackerMode.PROBABILISTIC
                && (workAccounting.orElseThrow().bStages().size() != 1
                    || workAccounting.orElseThrow().otherEngineTransitions() != 0L)) {
            throw new IllegalArgumentException("Standalone B must attest exactly one B stage");
        }
        if (workAccounting.isPresent()
                && workAccounting.orElseThrow().computedTransitions() != evaluatedTransitions) {
            throw new IllegalArgumentException("Execution receipt contradicts computed work");
        }
        boolean hasRoutes = !hypotheses.isEmpty();
        if ((status == Status.COMPLETE || status == Status.AMBIGUOUS) && !hasRoutes
            || (status == Status.NO_ROUTE || status == Status.CANCELLED) && hasRoutes) {
            throw new IllegalArgumentException("Trace hypothesis status contradicts retained computation");
        }
        Set<String> ids = new HashSet<>();
        Set<String> branchSignatures = new HashSet<>();
        for (TraceHypothesis hypothesis : hypotheses) {
            if (!ids.add(hypothesis.id()) || !branchSignatures.add(hypothesis.branchSignature())) {
                throw new IllegalArgumentException("Retained alternatives must have unique identities and branches");
            }
        }
        double retainedMass = hypotheses.stream().map(TraceHypothesis::posteriorProbability)
            .filter(java.util.OptionalDouble::isPresent).mapToDouble(java.util.OptionalDouble::getAsDouble).sum();
        if (retainedMass > 1.0 + 1e-9) {
            throw new IllegalArgumentException("Retained hypothesis posterior mass exceeds one");
        }
    }

    /** Compatibility name for callers and format readers predating the explicit alternative label. */
    public boolean truncated() {
        return alternativesTruncated;
    }
}

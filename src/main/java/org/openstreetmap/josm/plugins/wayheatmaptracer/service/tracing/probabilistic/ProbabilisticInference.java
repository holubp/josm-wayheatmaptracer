package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceMemoryLimitException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.OrderedDoubleSum;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceWorkAccounting;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

/** Exact second-order pair-state MAP, posterior and bounded k-best inference. */
public final class ProbabilisticInference {
    private static final double LOG_ZERO = Double.NEGATIVE_INFINITY;
    private static final int ADMISSION_MEMO_CAPACITY = 32_768;

    /**
     * Solves one complete admitted state graph without score-based beam pruning.
     *
     * @param profiles ordered physical profiles with unary mixture costs
     * @param parameters versioned objective parameters
     * @param budgets hard per-run resource limits
     * @return exact result for the retained graph or a typed resource/numerical failure
     */
    public ProbabilisticInferenceResult solve(List<InferenceProfile> profiles,
        EvidenceModelParameters parameters, TraceBudgets budgets) {
        return solve(profiles, parameters, budgets, null);
    }

    public ProbabilisticInferenceResult solve(List<InferenceProfile> profiles,
        EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion decisionRegion) {
        return solve(profiles, parameters, budgets, decisionRegion, CancellationProbe.NONE);
    }

    public ProbabilisticInferenceResult solve(List<InferenceProfile> profiles,
        EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion decisionRegion,
        CancellationProbe cancellation) {
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        try {
            return solve(profiles, parameters, budgets, decisionRegion, cancellation, owner);
        } finally {
            owner.close();
        }
    }

    /** Solves inside the caller's attempt scope and leaves only the returned result charged. */
    public ProbabilisticInferenceResult solve(List<InferenceProfile> profiles,
        EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion decisionRegion,
        CancellationProbe cancellation, AttemptMemoryLedger.Owner resultOwner) {
        return solve(profiles, parameters, budgets, decisionRegion, cancellation, resultOwner,
                ProbabilisticExecutionPolicy.Mode.EAGER_V1);
    }

    /** Explicit execution policy; selecting V2 never changes the graph, objective or raw K. */
    public ProbabilisticInferenceResult solve(List<InferenceProfile> profiles,
        EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion decisionRegion,
        CancellationProbe cancellation, AttemptMemoryLedger.Owner resultOwner,
        ProbabilisticExecutionPolicy.Mode mode) {
        return solve(profiles, parameters, budgets, decisionRegion, cancellation, resultOwner, mode, null);
    }

    /** Explicit optional additive adjacent-state energy capability. */
    public ProbabilisticInferenceResult solve(List<InferenceProfile> profiles,
        EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion decisionRegion,
        CancellationProbe cancellation, AttemptMemoryLedger.Owner resultOwner,
        ProbabilisticExecutionPolicy.Mode mode, AdjacentStatePotential potential) {
        if (cancellation == null) {
            throw new IllegalArgumentException("Cancellation probe is required");
        }
        if (resultOwner == null || mode == null) {
            throw new IllegalArgumentException("Attempt memory owner is required");
        }
        cancellation.checkpoint();
        validate(profiles, parameters, budgets);
        long stateCount = profiles.stream().mapToLong(profile -> profile.cells().size()).sum();
        WorkCounter workCounter = null;
        if (parameters.orientationWeight() > 0.0
            && profiles.stream().anyMatch(InferenceProfile::orientationResourceLimited)) {
            return failureOwned(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, 0, 0,
                "orientation descriptor resource limit", profiles, resultOwner);
        }
        if (profiles.stream().anyMatch(profile -> profile.cells().size() > budgets.maximumStatesPerProfile())) {
            return failureOwned(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, 0, 0,
                "state budget exceeded", profiles, resultOwner);
        }
        AttemptMemoryLedger.Owner workOwner = resultOwner.child("b-exact-inference");
        TransitionAdmission admissions = null;
        try (workOwner) {
            workCounter = allocated(workOwner, AttemptMemoryLedger.objectBytes(80),
                    "execution accounting", () -> new WorkCounter(mode, budgets.maximumTransitions()));
            long inferenceStarted = System.nanoTime();
            AttemptMemoryLedger.Owner admissionOwner = workOwner.child("transition-admission-memo");
            admissions = IndexedGeometryAdmissions.createIfEligible(profiles, decisionRegion,
                    budgets.maximumPairVisits(), admissionOwner);
            if (admissions == null) {
                admissions = new TransitionAdmissionMemo(ADMISSION_MEMO_CAPACITY, admissionOwner);
            }
            AttemptMemoryLedger.Owner messagesOwner = workOwner.child("forward-backward");
            GraphMessages messages = forwardBackward(profiles, parameters, budgets, decisionRegion,
                    cancellation, admissions, messagesOwner, workCounter, potential);
            long forwardBackwardNanos = System.nanoTime() - inferenceStarted;
            if (messages.resourceLimited()) {
                return failureOwnedAfterClose(workOwner, ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,
                    messages.pairVisits(), messages.transitions(), messages.explanation(), profiles, resultOwner, workCounter);
            }
            if (messages.noRoute()) {
                return failureOwnedAfterClose(workOwner, ProbabilisticInferenceResult.Status.NO_ROUTE,
                    messages.pairVisits(), messages.transitions(), "no route inside decision region",
                    profiles, resultOwner, workCounter);
            }
            if (!Double.isFinite(messages.logPartition())) {
                return failureOwnedAfterClose(workOwner, ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,
                    messages.pairVisits(), messages.transitions(), "non-finite partition function",
                    profiles, resultOwner, workCounter);
            }
            long kBestStarted = System.nanoTime();
            AttemptMemoryLedger.Owner kBestOwner = workOwner.child("k-best");
            KBestResult kBest = enumerateKBest(profiles, parameters, budgets,
                decisionRegion, cancellation, admissions, kBestOwner, workCounter, potential);
            long kBestNanos = System.nanoTime() - kBestStarted;
            long allTransitions = messages.transitions() + kBest.transitions();
            if (kBest.resourceLimited()) {
                return failureOwnedAfterClose(workOwner, ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,
                    messages.pairVisits(), allTransitions, kBest.explanation(), profiles, resultOwner, workCounter);
            }
            kBest.releaseFrontier();
            long alternativesStarted = System.nanoTime();
            AttemptMemoryLedger.Owner retainedOwner = workOwner.child("inference-result");
            List<ProbabilisticPath> rawPaths = materializeWithPotential(kBest.paths(), profiles, parameters,
                messages, cancellation, retainedOwner, potential);
            kBest.close();
            double pitch = representativePitch(profiles);
            int distinctCap = Math.min(8, budgets.maximumDistinctAlternatives());
            AttemptMemoryLedger.Owner selectionOwner = workOwner.child("alternative-selection");
            List<ProbabilisticPath> probed = new PathAlternativeSelector().select(rawPaths, profiles,
                pitch, Math.max(2, distinctCap), selectionOwner);
            List<ProbabilisticPath> distinct = accountedCopy(probed.subList(0,
                Math.min(distinctCap, probed.size())), retainedOwner, "alternative selection");
            long alternativesNanos = System.nanoTime() - alternativesStarted;
            int rawCap = Math.min(32, budgets.maximumRawAlternatives());
            boolean rawEnumerationCapped = kBest.terminalCount() > rawCap;
            boolean diversityReached = distinct.size() == distinctCap;
            var completion = allocated(retainedOwner, AttemptMemoryLedger.objectBytes(24),
                    "result materialization", () -> new ProbabilisticInferenceResult.Completion(
                        rawCap, distinctCap, kBest.terminalCount(), rawEnumerationCapped,
                        rawEnumerationCapped, diversityReached));
            boolean alternativeTruncated = rawEnumerationCapped && !diversityReached;
            boolean unresolvedCloseRival = rawEnumerationCapped && !rawPaths.isEmpty()
                && rawPaths.get(rawPaths.size() - 1).energy() - rawPaths.get(0).energy()
                    <= parameters.ambiguityEnergyDelta();
            GapSummary gaps = allocated(retainedOwner, AttemptMemoryLedger.objectBytes(48),
                    "result materialization", () -> gapSummary(profiles));
            boolean allMissing = profiles.stream().allMatch(InferenceProfile::entirelyMissing);
            ProbabilisticInferenceResult.Status status = allMissing
                ? ProbabilisticInferenceResult.Status.ALL_MISSING
                : gaps.longestInternalGapMeters() > 20.0 || gaps.terminalGapMeters() > 0.0
                    ? ProbabilisticInferenceResult.Status.REVIEW_REQUIRED
                    : ambiguous(probed, parameters) ? ProbabilisticInferenceResult.Status.AMBIGUOUS
                        : unresolvedCloseRival ? ProbabilisticInferenceResult.Status.REVIEW_REQUIRED
                            : ProbabilisticInferenceResult.Status.COMPLETE;
            AttemptMemoryLedger.Owner posteriorOwner = workOwner.child("posterior-temporaries");
            List<double[]> marginals = positionalMarginals(profiles, messages, posteriorOwner);
            List<double[]> componentMarginals = componentMarginals(profiles, marginals, posteriorOwner);
            List<CredibleLateralSet> credibleSets = credibleSets(profiles, marginals, 0.95,
                    posteriorOwner, retainedOwner);
            long resultBytes = retainedResultBytes(rawPaths, distinct, marginals,
                    componentMarginals, credibleSets);
            Optional<InferenceWorkAccounting> accounting = accounting(workCounter, retainedOwner);
            ProbabilisticInferenceResult result = allocated(retainedOwner, resultBytes,
                    "result materialization", () -> new ProbabilisticInferenceResult(status,
                rawPaths.isEmpty() ? Optional.empty() : Optional.of(rawPaths.get(0)), rawPaths, distinct,
                messages.logPartition(), marginals, componentMarginals, marginals, marginals, credibleSets, !allMissing,
                alternativeTruncated, Optional.of(completion), messages.pairVisits(), allTransitions, gaps,
                allMissing ? "all profiles lack localized evidence"
                    : alternativeTruncated && unresolvedCloseRival
                        ? "ALTERNATIVE_SEARCH_TRUNCATED; ALTERNATIVE_AMBIGUITY_UNRESOLVED"
                    : alternativeTruncated ? "ALTERNATIVE_SEARCH_TRUNCATED"
                    : unresolvedCloseRival ? "ALTERNATIVE_AMBIGUITY_UNRESOLVED"
                    : "complete retained graph", accounting));
            selectionOwner.close();
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.forwardBackwardMs", nanosToMillis(forwardBackwardNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.kBestMs", nanosToMillis(kBestNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.alternativesMs", nanosToMillis(alternativesNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.profiles", profiles.size());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.states", stateCount);
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.pairVisits", result.evaluatedPairVisits());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.transitions", result.evaluatedTransitions());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.rawPaths", result.rawPaths().size());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.distinctPaths", result.distinctPaths().size());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.effectiveRawLimit", completion.effectiveRawLimit());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.effectiveDistinctLimit", completion.effectiveDistinctLimit());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.terminalCountAtSaturation", completion.completePathsAtSaturation());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.rawEnumerationCapped", completion.rawEnumerationCapped() ? 1 : 0);
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.requestedDiversityReached", completion.requestedDiversityReached() ? 1 : 0);
            retainedOwner.transferTo(resultOwner);
            return result;
        } catch (MemoryLimit exception) {
            workOwner.close();
            return failureOwned(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,
                    workCounter == null ? 0 : workCounter.pairVisits, workCounter == null ? 0 : workCounter.transitions,
                    exception.getMessage(), exception.stage(), exception.limitCause(),
                    profiles, resultOwner, workCounter);
        } catch (ArithmeticException exception) {
            workOwner.close();
            return failureOwned(ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,
                    workCounter == null ? 0 : workCounter.pairVisits,
                    workCounter == null ? 0 : workCounter.transitions, "numeric failure", profiles,
                    resultOwner, workCounter);
        } finally {
            if (admissions != null) admissions.finish();
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.extensionDescriptors", workCounter == null ? 0 : workCounter.extensionDescriptors);
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "inference.ancestryRecordsAllocated", workCounter == null ? 0 : workCounter.ancestryRecordsAllocated);
        }
    }

    private static GraphMessages forwardBackward(List<InferenceProfile> profiles,
        EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion decisionRegion,
        CancellationProbe cancellation, TransitionAdmission admissions,
        AttemptMemoryLedger.Owner owner, WorkCounter workCounter) {
        return forwardBackward(profiles, parameters, budgets, decisionRegion, cancellation,
                admissions, owner, workCounter, null);
    }

    private static GraphMessages forwardBackward(List<InferenceProfile> profiles,
        EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion decisionRegion,
        CancellationProbe cancellation, TransitionAdmission admissions,
        AttemptMemoryLedger.Owner owner, WorkCounter workCounter, AdjacentStatePotential potential) {
        cancellation.checkpoint();
        // The fallback memo grows mandatory payload during admission; optional tables
        // must not occupy bytes needed by that later growth.
        boolean reusableProfileTerms = decisionRegion == null || admissions instanceof IndexedGeometryAdmissions;
        if (profiles.size() == 1) {
            InferenceProfile profile = profiles.get(0);
            if (profile.cells().size() > budgets.maximumPairVisits()) {
                return GraphMessages.limit(profile.cells().size(), 0, "pair-visit budget exceeded");
            }
            workCounter.pairVisits = profile.cells().size();
            double[] alpha = allocated(owner,
                    AttemptMemoryLedger.arrayBytes(profile.cells().size(), Double.BYTES),
                    "forward/backward", () -> new double[profile.cells().size()]);
            for (int state = 0; state < alpha.length; state++) {
                alpha[state] = -profileEnergy(profiles, 0, state, parameters) / parameters.temperature()
                    + logMeasure(profile.cells().get(state));
            }
            return GraphMessages.single(logSumExp(alpha), alpha, profile.cells().size());
        }

        List<double[][]> forward = allocated(owner, listBytes(profiles.size() - 1),
                "forward/backward", () -> new ArrayList<>(profiles.size() - 1));
        int firstStates = profiles.get(0).cells().size();
        int secondStates = profiles.get(1).cells().size();
        long pairVisits = (long) firstStates * secondStates;
        workCounter.pairVisits = pairVisits;
        if (pairVisits > budgets.maximumPairVisits()) {
            return GraphMessages.limit(pairVisits, 0, "pair-visit budget exceeded");
        }
        double[][] initial = matrix(firstStates, secondStates, LOG_ZERO, owner,
                "forward/backward");
        for (int first = 0; first < firstStates; first++) {
            for (int second = 0; second < secondStates; second++) {
                if (!transitionAllowed(profiles, 0, first, second, decisionRegion, admissions)) {
                    continue;
                }
                double energy = profileEnergy(profiles, 0, first, parameters)
                    + profileEnergy(profiles, 1, second, parameters)
                    + pairEnergyWithPotential(profiles, 0, first, second, parameters, potential);
                initial[first][second] = -energy / parameters.temperature()
                    + logMeasure(profiles.get(0).cells().get(first))
                    + logMeasure(profiles.get(1).cells().get(second));
            }
        }
        forward.add(initial);
        long transitions = 0;
        for (int profileIndex = 2; profileIndex < profiles.size(); profileIndex++) {
            cancellation.checkpoint();
            int previousPreviousStates = profiles.get(profileIndex - 2).cells().size();
            int previousStates = profiles.get(profileIndex - 1).cells().size();
            int currentStates = profiles.get(profileIndex).cells().size();
            pairVisits += (long) previousStates * currentStates;
            workCounter.pairVisits = pairVisits;
            if (pairVisits > budgets.maximumPairVisits()) {
                return GraphMessages.limit(pairVisits, transitions, "pair-visit budget exceeded");
            }
            double[][] current = matrix(previousStates, currentStates, LOG_ZERO, owner,
                    "forward/backward");
            double[][] previous = forward.get(profileIndex - 2);
            AttemptMemoryLedger.Owner scalarOwner = previousPreviousStates >= 4
                    && (parameters.orientationWeight() != 0.0 || parameters.turnWeight() != 0.0)
                    ? owner.child("forward-backward-scalar-cache") : null;
            ScalarReuseTable scalarCache = null;
            boolean scalarCacheAttempted = false;
            AttemptMemoryLedger.Owner termsOwner = reusableProfileTerms ? owner.child("profile-terms") : null;
            ProfileTerms terms = null;
            boolean termsAttempted = false;
            try {
            for (int before = 0; before < previousPreviousStates; before++) {
                for (int prior = 0; prior < previousStates; prior++) {
                    double prefix = previous[before][prior];
                    if (!Double.isFinite(prefix)) {
                        continue;
                    }
                    boolean firstHeadingReady = false;
                    double firstHeading = 0.0;
                    for (int state = 0; state < currentStates; state++) {
                        if (!transitionAllowed(profiles, profileIndex - 1, prior, state, decisionRegion, admissions)) {
                            continue;
                        }
                        boolean scored = workCounter.score(true);
                        if (scored || workCounter.mode == ProbabilisticExecutionPolicy.Mode.EAGER_V1) transitions++;
                        if ((transitions & 1023L) == 0L) {
                            cancellation.checkpoint();
                        }
                        if (!scored) {
                            return GraphMessages.limit(pairVisits, transitions,
                                "transition budget exceeded");
                        }
                        if (termsOwner != null && !termsAttempted) {
                            termsAttempted = true;
                            terms = ProfileTerms.create(termsOwner, currentStates);
                        }
                        double unary = terms == null ? profileEnergy(profiles, profileIndex, state, parameters)
                                : terms.unary(profiles, profileIndex, state, parameters);
                        double pair = 0.0;
                        boolean secondHeadingReady = false;
                        double secondHeading = 0.0;
                        if (parameters.orientationWeight() != 0.0) {
                            InferenceProfile start = profiles.get(profileIndex - 1);
                            InferenceProfile end = profiles.get(profileIndex);
                            MetricPoint firstPoint = start.point(prior);
                            MetricPoint secondPoint = end.point(state);
                            if (scalarOwner != null && !scalarCacheAttempted) {
                                scalarCacheAttempted = true;
                                scalarCache = ScalarReuseTable.create(scalarOwner,
                                        previousStates, currentStates, false);
                            }
                            int scalarIndex = prior * currentStates + state;
                            if (scalarCache != null && scalarCache.ready[scalarIndex] != 0) {
                                secondHeading = scalarCache.secondary[scalarIndex];
                                pair = scalarCache.values[scalarIndex];
                                scalarCache.reuses++;
                            } else {
                                secondHeading = StrictMath.atan2(secondPoint.yMeters() - firstPoint.yMeters(),
                                        secondPoint.xMeters() - firstPoint.xMeters());
                                pair = pairEnergyAtHeading(start, end, prior, state, parameters, secondHeading);
                                if (scalarCache != null) {
                                    scalarCache.ready[scalarIndex] = 1;
                                    scalarCache.values[scalarIndex] = pair;
                                    scalarCache.secondary[scalarIndex] = secondHeading;
                                    scalarCache.calculations++;
                                }
                            }
                            secondHeadingReady = true;
                        }
                        pair = addPotential(potential, profileIndex - 1, prior, state, pair);
                        double triple = 0.0;
                        if (parameters.turnWeight() != 0.0) {
                            // Validate every point in the original order, after score admission.
                            MetricPoint beforePoint = profiles.get(profileIndex - 2).point(before);
                            MetricPoint priorPoint = profiles.get(profileIndex - 1).point(prior);
                            MetricPoint currentPoint = profiles.get(profileIndex).point(state);
                            if (scalarOwner != null && !scalarCacheAttempted) {
                                scalarCacheAttempted = true;
                                scalarCache = ScalarReuseTable.create(scalarOwner,
                                        previousStates, currentStates, false);
                            }
                            if (!firstHeadingReady) {
                                firstHeading = StrictMath.atan2(priorPoint.yMeters() - beforePoint.yMeters(),
                                        priorPoint.xMeters() - beforePoint.xMeters());
                                firstHeadingReady = true;
                            }
                            if (!secondHeadingReady) {
                                int scalarIndex = prior * currentStates + state;
                                if (scalarCache != null && scalarCache.ready[scalarIndex] != 0) {
                                    secondHeading = scalarCache.secondary[scalarIndex];
                                    scalarCache.reuses++;
                                } else {
                                    secondHeading = StrictMath.atan2(currentPoint.yMeters() - priorPoint.yMeters(),
                                            currentPoint.xMeters() - priorPoint.xMeters());
                                    if (scalarCache != null) {
                                        scalarCache.ready[scalarIndex] = 1;
                                        scalarCache.secondary[scalarIndex] = secondHeading;
                                        scalarCache.calculations++;
                                    }
                                }
                            }
                            triple = tripleEnergyAtHeadings(profiles, profileIndex, parameters,
                                    firstHeading, secondHeading);
                        }
                        double increment = unary + pair + triple;
                        double term = prefix - increment / parameters.temperature()
                            + (terms == null ? logMeasure(profiles.get(profileIndex).cells().get(state))
                                : terms.measure(profiles.get(profileIndex).cells().get(state), state));
                        current[prior][state] = logAdd(current[prior][state], term);
                    }
                }
            }
            } finally {
                if (termsOwner != null) termsOwner.close();
                if (scalarCache != null) scalarCache.close();
                if (scalarOwner != null) scalarOwner.close();
            }
            forward.add(current);
        }

        List<double[][]> backward = allocated(owner, listBytes(forward.size()),
                "forward/backward", () -> {
                    List<double[][]> values = new ArrayList<>(forward.size());
                    for (int index = 0; index < forward.size(); index++) values.add(null);
                    return values;
                });
        double[][] terminal = matrix(forward.get(forward.size() - 1).length,
            forward.get(forward.size() - 1)[0].length, 0.0, owner, "forward/backward");
        backward.set(backward.size() - 1, terminal);
        for (int stage = backward.size() - 2; stage >= 0; stage--) {
            int currentProfile = stage + 1;
            int nextProfile = currentProfile + 1;
            int beforeStates = profiles.get(currentProfile - 1).cells().size();
            int currentStates = profiles.get(currentProfile).cells().size();
            int nextStates = profiles.get(nextProfile).cells().size();
            double[][] values = matrix(beforeStates, currentStates, LOG_ZERO, owner,
                    "forward/backward");
            double[][] nextBackward = backward.get(stage + 1);
            AttemptMemoryLedger.Owner scalarOwner = beforeStates >= 4
                    && (parameters.orientationWeight() != 0.0 || parameters.turnWeight() != 0.0)
                    ? owner.child("forward-backward-scalar-cache") : null;
            ScalarReuseTable scalarCache = null;
            boolean scalarCacheAttempted = false;
            AttemptMemoryLedger.Owner termsOwner = reusableProfileTerms ? owner.child("profile-terms") : null;
            ProfileTerms terms = null;
            boolean termsAttempted = false;
            try {
            for (int before = 0; before < beforeStates; before++) {
                for (int currentState = 0; currentState < currentStates; currentState++) {
                    double accumulated = LOG_ZERO;
                    boolean firstHeadingReady = false;
                    double firstHeading = 0.0;
                    for (int next = 0; next < nextStates; next++) {
                        if (!transitionAllowed(profiles, currentProfile, currentState, next, decisionRegion, admissions)) {
                            continue;
                        }
                        boolean scored = workCounter.score(true);
                        if (scored || workCounter.mode == ProbabilisticExecutionPolicy.Mode.EAGER_V1) transitions++;
                        if ((transitions & 1023L) == 0L) {
                            cancellation.checkpoint();
                        }
                        if (!scored) {
                            return GraphMessages.limit(pairVisits, transitions,
                                "transition budget exceeded during backward inference");
                        }
                        if (termsOwner != null && !termsAttempted) {
                            termsAttempted = true;
                            terms = ProfileTerms.create(termsOwner, nextStates);
                        }
                        double unary = terms == null ? profileEnergy(profiles, nextProfile, next, parameters)
                                : terms.unary(profiles, nextProfile, next, parameters);
                        double pair = 0.0;
                        boolean secondHeadingReady = false;
                        double secondHeading = 0.0;
                        if (parameters.orientationWeight() != 0.0) {
                            InferenceProfile start = profiles.get(currentProfile);
                            InferenceProfile end = profiles.get(nextProfile);
                            MetricPoint firstPoint = start.point(currentState);
                            MetricPoint secondPoint = end.point(next);
                            if (scalarOwner != null && !scalarCacheAttempted) {
                                scalarCacheAttempted = true;
                                scalarCache = ScalarReuseTable.create(scalarOwner,
                                        currentStates, nextStates, false);
                            }
                            int scalarIndex = currentState * nextStates + next;
                            if (scalarCache != null && scalarCache.ready[scalarIndex] != 0) {
                                secondHeading = scalarCache.secondary[scalarIndex];
                                pair = scalarCache.values[scalarIndex];
                                scalarCache.reuses++;
                            } else {
                                secondHeading = StrictMath.atan2(secondPoint.yMeters() - firstPoint.yMeters(),
                                        secondPoint.xMeters() - firstPoint.xMeters());
                                pair = pairEnergyAtHeading(start, end, currentState, next, parameters,
                                        secondHeading);
                                if (scalarCache != null) {
                                    scalarCache.ready[scalarIndex] = 1;
                                    scalarCache.values[scalarIndex] = pair;
                                    scalarCache.secondary[scalarIndex] = secondHeading;
                                    scalarCache.calculations++;
                                }
                            }
                            secondHeadingReady = true;
                        }
                        pair = addPotential(potential, currentProfile, currentState, next, pair);
                        double triple = 0.0;
                        if (parameters.turnWeight() != 0.0) {
                            // Keep deferred validation even when the fixed first heading is reusable.
                            MetricPoint beforePoint = profiles.get(currentProfile - 1).point(before);
                            MetricPoint priorPoint = profiles.get(currentProfile).point(currentState);
                            MetricPoint currentPoint = profiles.get(nextProfile).point(next);
                            if (scalarOwner != null && !scalarCacheAttempted) {
                                scalarCacheAttempted = true;
                                scalarCache = ScalarReuseTable.create(scalarOwner,
                                        currentStates, nextStates, false);
                            }
                            if (!firstHeadingReady) {
                                firstHeading = StrictMath.atan2(priorPoint.yMeters() - beforePoint.yMeters(),
                                        priorPoint.xMeters() - beforePoint.xMeters());
                                firstHeadingReady = true;
                            }
                            if (!secondHeadingReady) {
                                int scalarIndex = currentState * nextStates + next;
                                if (scalarCache != null && scalarCache.ready[scalarIndex] != 0) {
                                    secondHeading = scalarCache.secondary[scalarIndex];
                                    scalarCache.reuses++;
                                } else {
                                    secondHeading = StrictMath.atan2(currentPoint.yMeters() - priorPoint.yMeters(),
                                            currentPoint.xMeters() - priorPoint.xMeters());
                                    if (scalarCache != null) {
                                        scalarCache.ready[scalarIndex] = 1;
                                        scalarCache.secondary[scalarIndex] = secondHeading;
                                        scalarCache.calculations++;
                                    }
                                }
                            }
                            triple = tripleEnergyAtHeadings(profiles, nextProfile, parameters,
                                    firstHeading, secondHeading);
                        }
                        double increment = unary + pair + triple;
                        double term = -increment / parameters.temperature()
                            + (terms == null ? logMeasure(profiles.get(nextProfile).cells().get(next))
                                : terms.measure(profiles.get(nextProfile).cells().get(next), next))
                            + nextBackward[currentState][next];
                        accumulated = logAdd(accumulated, term);
                    }
                    values[before][currentState] = accumulated;
                }
            }
            } finally {
                if (termsOwner != null) termsOwner.close();
                if (scalarCache != null) scalarCache.close();
                if (scalarOwner != null) scalarOwner.close();
            }
            backward.set(stage, values);
        }
        AttemptMemoryLedger.Owner flattenOwner = owner.child("partition-flatten");
        double logPartition;
        try (flattenOwner) {
            double[] flattened = flatten(forward.get(forward.size() - 1), flattenOwner);
            logPartition = logSumExp(flattened);
        }
        return new GraphMessages(logPartition, forward, backward, null, pairVisits, transitions,
            false, !Double.isFinite(logPartition), "complete");
    }

    private static KBestResult enumerateKBest(List<InferenceProfile> profiles,
        EvidenceModelParameters parameters, TraceBudgets budgets,
        MetricRegion decisionRegion,
        CancellationProbe cancellation, TransitionAdmission admissions,
        AttemptMemoryLedger.Owner owner, WorkCounter workCounter) {
        return enumerateKBest(profiles, parameters, budgets, decisionRegion, cancellation,
                admissions, owner, workCounter, null);
    }

    private static KBestResult enumerateKBest(List<InferenceProfile> profiles,
        EvidenceModelParameters parameters, TraceBudgets budgets,
        MetricRegion decisionRegion,
        CancellationProbe cancellation, TransitionAdmission admissions,
        AttemptMemoryLedger.Owner owner, WorkCounter workCounter, AdjacentStatePotential potential) {
        cancellation.checkpoint();
        int cap = Math.min(32, budgets.maximumRawAlternatives());
        PathArena arena = new PathArena(owner.child("reachable-ancestry"), workCounter);
        if (profiles.size() == 1) {
            AttemptMemoryLedger.Owner frontierOwner = owner.child("single-profile-frontier");
            List<PathRecord> paths = allocated(frontierOwner,
                    listBytes(profiles.get(0).cells().size()), "k-best frontier",
                    () -> new ArrayList<>(profiles.get(0).cells().size()));
            InferenceProfile profile = profiles.get(0);
            for (int state = 0; state < profile.cells().size(); state++) {
                paths.add(arena.start(state, profileEnergy(profiles, 0, state, parameters),
                    logMeasure(profile.cells().get(state))));
            }
            paths.sort(PATH_ORDER);
            int terminalCount = Math.min(cap + 1, paths.size());
            AttemptMemoryLedger.Owner terminalOwner = owner.child("terminal-roots");
            List<PathRecord> selected = accountedCopy(paths.subList(0, Math.min(cap, paths.size())),
                    terminalOwner, "k-best frontier");
            arena.releaseFrontierExcept(paths, selected);
            return new KBestResult(selected, terminalCount, false, 0, "complete",
                    arena, frontierOwner, terminalOwner);
        }

        int firstStates = profiles.get(0).cells().size();
        int secondStates = profiles.get(1).cells().size();
        AttemptMemoryLedger.Owner currentOwner = owner.child("frontier-1");
        List<PathRecord>[][] current = pathGrid(firstStates, secondStates, currentOwner);
        int[][] counts = intGrid(firstStates, secondStates, currentOwner);
        for (int first = 0; first < firstStates; first++) {
            for (int second = 0; second < secondStates; second++) {
                if (!transitionAllowed(profiles, 0, first, second, decisionRegion, admissions)) {
                    current[first][second] = List.of();
                    continue;
                }
                double energy = profileEnergy(profiles, 0, first, parameters)
                    + profileEnergy(profiles, 1, second, parameters)
                    + pairEnergyWithPotential(profiles, 0, first, second, parameters, potential);
                PathRecord record = arena.initial(first, second, energy,
                        logMeasure(profiles.get(0).cells().get(first))
                            + logMeasure(profiles.get(1).cells().get(second)));
                current[first][second] = accountedCopy(List.of(record), currentOwner,
                        "k-best frontier");
                counts[first][second] = 1;
            }
        }
        assignLexicalRanks(current, currentOwner);
        long transitions = 0;
        for (int profileIndex = 2; profileIndex < profiles.size(); profileIndex++) {
            cancellation.checkpoint();
            int previousStates = profiles.get(profileIndex - 1).cells().size();
            int nextStates = profiles.get(profileIndex).cells().size();
            AttemptMemoryLedger.Owner nextOwner = owner.child("frontier-" + profileIndex);
            List<PathRecord>[][] next = pathGrid(previousStates, nextStates, nextOwner);
            int[][] nextCounts = intGrid(previousStates, nextStates, nextOwner);
            int beforeStates = current.length;
            int streamCapacity = Math.multiplyExact(beforeStates, cap);
            AttemptMemoryLedger.Owner scratchOwner = nextOwner.child("extension-merge-scratch");
            boolean exact = workCounter.mode == ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2;
            ScalarReuseTable scalarCache = null;
            boolean scalarCacheAttempted = false;
            DescriptorHeap descriptors = exact ? allocated(scratchOwner, AttemptMemoryLedger.objectBytes(48),
                    "k-best descriptors", () -> new DescriptorHeap(cap, cancellation, scratchOwner)) : null;
            double[] extensionEnergy = exact ? null : allocated(scratchOwner,
                AttemptMemoryLedger.arrayBytes(streamCapacity, Double.BYTES), "k-best descriptors",
                () -> new double[streamCapacity]);
            int[] streamOrder = exact ? null : allocated(scratchOwner,
                AttemptMemoryLedger.arrayBytes(streamCapacity, Integer.BYTES), "k-best descriptors",
                () -> new int[streamCapacity]);
            int[] streamLengths = exact ? null : allocated(scratchOwner,
                AttemptMemoryLedger.arrayBytes(beforeStates, Integer.BYTES), "k-best descriptors",
                () -> new int[beforeStates]);
            int[] heapBefore = exact ? null : allocated(scratchOwner,
                AttemptMemoryLedger.arrayBytes(beforeStates, Integer.BYTES), "k-best descriptors",
                () -> new int[beforeStates]);
            int[] heapPosition = exact ? null : allocated(scratchOwner,
                AttemptMemoryLedger.arrayBytes(beforeStates, Integer.BYTES), "k-best descriptors",
                () -> new int[beforeStates]);
            try {
            for (int prior = 0; prior < previousStates; prior++) {
                for (int state = 0; state < nextStates; state++) {
                    if (exact) {
                        descriptors.reset(state);
                        boolean pairReady = false;
                        double unaryPair = 0.0;
                        boolean headingReady = false;
                        double secondHeading = 0.0;
                        for (int before = 0; before < current.length; before++) {
                            if (!transitionAllowed(profiles, profileIndex - 1, prior, state,
                                    decisionRegion, admissions)) continue;
                            nextCounts[prior][state] = Math.min(cap + 1,
                                    nextCounts[prior][state] + counts[before][prior]);
                            List<PathRecord> prefixes = current[before][prior];
                            if (prefixes == null || prefixes.isEmpty()) continue;
                            workCounter.recordLogicalKBestExtensions(prefixes.size());
                            // First use stays after admission and logical-extension accounting.
                            // These immutable pair terms keep their original rounded sum.
                            if (!pairReady) {
                                double unary = profileEnergy(profiles, profileIndex, state, parameters);
                                double pair = 0.0;
                                if (parameters.orientationWeight() != 0.0) {
                                    InferenceProfile start = profiles.get(profileIndex - 1);
                                    InferenceProfile end = profiles.get(profileIndex);
                                    MetricPoint first = start.point(prior);
                                    MetricPoint second = end.point(state);
                                    secondHeading = StrictMath.atan2(second.yMeters() - first.yMeters(),
                                            second.xMeters() - first.xMeters());
                                    headingReady = true;
                                    pair = pairEnergyAtHeading(start, end, prior, state, parameters, secondHeading);
                                }
                                pair = addPotential(potential, profileIndex - 1, prior, state, pair);
                                unaryPair = unary + pair;
                                pairReady = true;
                            }
                            double triple = 0.0;
                            if (parameters.turnWeight() != 0.0) {
                                // Keep all three point validations and their original order,
                                // even when the second heading has already been evaluated.
                                MetricPoint beforePoint = profiles.get(profileIndex - 2).point(before);
                                MetricPoint priorPoint = profiles.get(profileIndex - 1).point(prior);
                                MetricPoint currentPoint = profiles.get(profileIndex).point(state);
                                if (parameters.turnWeight() != 0.0 && nextStates >= 4
                                        && !scalarCacheAttempted) {
                                    scalarCacheAttempted = true;
                                    scalarCache = ScalarReuseTable.create(scratchOwner,
                                            beforeStates, previousStates, true);
                                }
                                int scalarIndex = before * previousStates + prior;
                                double firstHeading;
                                if (scalarCache != null && scalarCache.ready[scalarIndex] != 0) {
                                    firstHeading = scalarCache.values[scalarIndex];
                                    scalarCache.reuses++;
                                } else {
                                    firstHeading = StrictMath.atan2(
                                            priorPoint.yMeters() - beforePoint.yMeters(),
                                            priorPoint.xMeters() - beforePoint.xMeters());
                                    if (scalarCache != null) {
                                        scalarCache.values[scalarIndex] = firstHeading;
                                        scalarCache.ready[scalarIndex] = 1;
                                        scalarCache.calculations++;
                                    }
                                }
                                if (!headingReady) {
                                    secondHeading = StrictMath.atan2(
                                            currentPoint.yMeters() - priorPoint.yMeters(),
                                            currentPoint.xMeters() - priorPoint.xMeters());
                                    headingReady = true;
                                }
                                triple = tripleEnergyAtHeadings(profiles, profileIndex, parameters,
                                        firstHeading, secondHeading);
                            }
                            double increment = unaryPair + triple;
                            for (int index = 0; index < prefixes.size(); index++) {
                                cancellation.checkpoint();
                                if (!workCounter.score(false)) {
                                    return new KBestResult(List.of(), 0, true, transitions,
                                            "transition budget exceeded during k-best");
                                }
                                transitions++;
                                PathRecord prefix = prefixes.get(index);
                                double energy = prefix.energy() + increment;
                                workCounter.extensionDescriptors++;
                                if (descriptors.strictlyDominated(energy, increment)) break;
                                descriptors.offer(before, index, energy, prefix.lexicalRank);
                            }
                        }
                        descriptors.sortWinners();
                        List<PathRecord> selected = allocated(nextOwner, listBytes(cap),
                                "k-best frontier", () -> new ArrayList<>(cap));
                        double measure = logMeasure(profiles.get(profileIndex).cells().get(state));
                        for (int index = 0; index < descriptors.size; index++) {
                            cancellation.checkpoint();
                            PathRecord prefix = current[descriptors.before[index]][prior]
                                    .get(descriptors.prefix[index]);
                            selected.add(arena.extendRounded(prefix, state, descriptors.energy[index], measure));
                        }
                        next[prior][state] = selected;
                        continue;
                    }
                    Arrays.fill(streamLengths, 0);
                    int heapSize = 0;
                    int candidateCount = 0;
                    for (int before = 0; before < current.length; before++) {
                        if (!transitionAllowed(profiles, profileIndex - 1, prior, state, decisionRegion, admissions)) {
                            continue;
                        }
                        nextCounts[prior][state] = Math.min(cap + 1,
                            nextCounts[prior][state] + counts[before][prior]);
                        List<PathRecord> prefixes = current[before][prior];
                        if (prefixes == null || prefixes.isEmpty()) {
                            continue;
                        }
                        workCounter.recordLogicalKBestExtensions(prefixes.size());
                        double pair = pairEnergyWithPotential(profiles, profileIndex - 1, prior,
                                state, parameters, potential);
                        double increment = profileEnergy(profiles, profileIndex, state, parameters)
                            + pair + tripleEnergy(profiles, profileIndex, before, prior, state, parameters);
                        int base = before * cap;
                        for (int index = 0; index < prefixes.size(); index++) {
                            boolean scored = workCounter.score(false);
                            if (scored || workCounter.mode == ProbabilisticExecutionPolicy.Mode.EAGER_V1) transitions++;
                            if ((transitions & 1023L) == 0L) {
                                cancellation.checkpoint();
                            }
                            if (!scored) {
                                return new KBestResult(List.of(), 0, true, transitions,
                                    "transition budget exceeded during k-best");
                            }
                            extensionEnergy[base + index] = prefixes.get(index).energy() + increment;
                            streamOrder[base + index] = index;
                            workCounter.extensionDescriptors++;
                        }
                        streamLengths[before] = prefixes.size();
                        candidateCount += prefixes.size();
                        // A rounded addition can collapse distinct prefix energies. Sort on
                        // the completed binary64 value before merging this retained stream.
                        for (int index = 1; index < prefixes.size(); index++) {
                            int candidate = streamOrder[base + index];
                            int at = index;
                            while (at > 0 && compareExtension(
                                    extensionEnergy[base + candidate], prefixes.get(candidate).lexicalRank,
                                    state, extensionEnergy[base + streamOrder[base + at - 1]],
                                    prefixes.get(streamOrder[base + at - 1]).lexicalRank, state) < 0) {
                                streamOrder[base + at] = streamOrder[base + at - 1];
                                at--;
                            }
                            streamOrder[base + at] = candidate;
                        }
                        heapBefore[heapSize] = before;
                        heapPosition[heapSize] = 0;
                        int at = heapSize++;
                        while (at > 0) {
                            int parent = (at - 1) >>> 1;
                            if (compareStreamHead(current, prior, state, cap, extensionEnergy,
                                    streamOrder, heapBefore[at], heapPosition[at],
                                    heapBefore[parent], heapPosition[parent]) >= 0) break;
                            swap(heapBefore, at, parent);
                            swap(heapPosition, at, parent);
                            at = parent;
                        }
                    }
                    int selectedCount = Math.min(cap, candidateCount);
                    List<PathRecord> selected = allocated(nextOwner, listBytes(cap),
                        "k-best frontier", () -> new ArrayList<>(cap));
                    double measure = logMeasure(profiles.get(profileIndex).cells().get(state));
                    for (int selectedIndex = 0; selectedIndex < selectedCount; selectedIndex++) {
                        int before = heapBefore[0];
                        int position = heapPosition[0];
                        int orderedIndex = streamOrder[before * cap + position];
                        PathRecord prefix = current[before][prior].get(orderedIndex);
                        selected.add(arena.extendRounded(prefix, state,
                            extensionEnergy[before * cap + orderedIndex], measure));
                        if (position + 1 < streamLengths[before]) {
                            heapPosition[0] = position + 1;
                        } else {
                            heapSize--;
                            heapBefore[0] = heapBefore[heapSize];
                            heapPosition[0] = heapPosition[heapSize];
                        }
                        int at = 0;
                        while (at < heapSize) {
                            int left = 2 * at + 1;
                            if (left >= heapSize) break;
                            int right = left + 1;
                            int child = right < heapSize && compareStreamHead(current, prior, state,
                                cap, extensionEnergy, streamOrder, heapBefore[right], heapPosition[right],
                                heapBefore[left], heapPosition[left]) < 0 ? right : left;
                            if (compareStreamHead(current, prior, state, cap, extensionEnergy,
                                    streamOrder, heapBefore[at], heapPosition[at],
                                    heapBefore[child], heapPosition[child]) <= 0) break;
                            swap(heapBefore, at, child);
                            swap(heapPosition, at, child);
                            at = child;
                        }
                    }
                    next[prior][state] = selected;
                }
            }
            } finally {
                if (scalarCache != null) scalarCache.close();
            }
            scratchOwner.close();
            assignLexicalRanks(next, nextOwner);
            arena.releaseFrontier(current);
            currentOwner.close();
            current = next;
            counts = nextCounts;
            currentOwner = nextOwner;
        }
        int terminalCount = 0;
        for (int[] row : counts) for (int value : row) {
            terminalCount = Math.min(cap + 1, terminalCount + value);
        }
        int terminalRoots = 0;
        for (List<PathRecord>[] row : current) for (List<PathRecord> paths : row) {
            if (paths != null) terminalRoots = Math.addExact(terminalRoots, paths.size());
        }
        int terminalCapacity = terminalRoots;
        AttemptMemoryLedger.Owner terminalBuilder = currentOwner.child("terminal-builder");
        try {
            List<PathRecord> result = allocated(terminalBuilder, listBytes(terminalCapacity),
                    "k-best frontier", () -> new ArrayList<>(terminalCapacity));
            for (List<PathRecord>[] row : current) {
                for (List<PathRecord> paths : row) {
                    if (paths != null) {
                        result.addAll(paths);
                    }
                }
            }
            result.sort(PATH_ORDER);
            if (result.size() > cap) {
                List<PathRecord> oversized = result;
                result = allocated(terminalBuilder, listBytes(cap), "k-best frontier",
                        () -> new ArrayList<>(oversized.subList(0, cap)));
            }
            AttemptMemoryLedger.Owner terminalOwner = owner.child("terminal-roots");
            List<PathRecord> selected = accountedCopy(result, terminalOwner, "k-best frontier");
            arena.releaseFrontierExcept(current, selected);
            return new KBestResult(selected, terminalCount, false, transitions, "complete",
                    arena, currentOwner, terminalOwner);
        } finally {
            terminalBuilder.close();
        }
    }

    private static List<ProbabilisticPath> materialize(List<PathRecord> paths,
        List<InferenceProfile> profiles, EvidenceModelParameters parameters, GraphMessages messages,
        CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        return materializeWithPotential(paths, profiles, parameters, messages, cancellation, owner, null);
    }

    private static List<ProbabilisticPath> materializeWithPotential(List<PathRecord> paths,
        List<InferenceProfile> profiles, EvidenceModelParameters parameters, GraphMessages messages,
        CancellationProbe cancellation, AttemptMemoryLedger.Owner owner, AdjacentStatePotential potential) {
        AttemptMemoryLedger.Owner builder = owner.child("path-list-builder");
        try {
            List<ProbabilisticPath> result = allocated(builder, listBytes(paths.size()),
                    "materialization", () -> new ArrayList<>(paths.size()));
            for (PathRecord record : paths) {
                AttemptMemoryLedger.Owner temporary = owner.child("path-materialization");
                AttemptMemoryLedger.Reservation pathShell;
                AttemptMemoryLedger.Reservation retainedStates;
                AttemptMemoryLedger.Reservation retainedPoints;
                try {
                    pathShell = owner.reserve(AttemptMemoryLedger.objectBytes(48));
                    retainedStates = owner.reserve(AttemptMemoryLedger.arrayBytes(
                            record.length(), Integer.BYTES));
                    retainedPoints = owner.reserve(listBytes(record.length()));
                } catch (AttemptMemoryLedger.ResourceLimitException exception) {
                    temporary.close();
                    throw new MemoryLimit("materialization", exception);
                }
                try {
                int[] states = allocated(temporary,
                        AttemptMemoryLedger.arrayBytes(record.length(), Integer.BYTES),
                        "materialization", record::states);
                List<MetricPoint> points = allocated(temporary, listBytes(record.length()),
                        "materialization", () -> new ArrayList<>(record.length()));
                for (int index = 0; index < record.length(); index++) {
                    int profileIndex = index;
                    points.add(allocated(owner, AttemptMemoryLedger.objectBytes(16),
                            "materialization",
                            () -> profiles.get(profileIndex).point(states[profileIndex])));
                }
                double mass = materializedPosteriorMassWithPotential(record, states, profiles, parameters,
                        messages, cancellation, potential);
                ProbabilisticPath path = new ProbabilisticPath(states, points,
                        branchSignature(states, profiles, temporary), record.energy(),
                        record.logMeasure(), mass);
                pathShell.adopt(path);
                retainedStates.adopt(path.ownedStateIndicesIdentity());
                retainedPoints.adopt(path.points());
                result.add(path);
                } catch (RuntimeException | Error exception) {
                    pathShell.close();
                    retainedStates.close();
                    retainedPoints.close();
                    throw exception;
                }
                temporary.close();
            }
            return accountedCopy(result, owner, "materialization");
        } finally {
            builder.close();
        }
    }

    // Exceptional O(K*n) materialization replay is outside scored FB/K work.
    // Normal masses retain their exact bits and original checkpoint sequence.
    private static double materializedPosteriorMass(PathRecord record, int[] states,
            List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            GraphMessages messages, CancellationProbe cancellation) {
        return materializedPosteriorMassWithPotential(record, states, profiles, parameters,
                messages, cancellation, null);
    }

    private static double materializedPosteriorMassWithPotential(PathRecord record, int[] states,
            List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            GraphMessages messages, CancellationProbe cancellation, AdjacentStatePotential potential) {
        double mass = StrictMath.exp(-record.energy() / parameters.temperature()
                + record.logMeasure() - messages.logPartition());
        if (!Double.isFinite(mass) || mass <= 1.0) return mass;
        if (states.length != profiles.size() || record.length() != states.length) return mass;

        // Only an invalid finite mass is replayed. Match the existing forward
        // recurrence, certifying each prefix against its already-computed cell.
        // Any inconsistency leaves the strict ProbabilisticPath guard in force.
        cancellation.checkpoint();
        double energy;
        double measure;
        double logWeight;
        double bound;
        if (states.length == 1) {
            energy = profileEnergy(profiles, 0, states[0], parameters);
            measure = logMeasure(profiles.get(0).cells().get(states[0]));
            logWeight = -energy / parameters.temperature() + measure;
            bound = messages.singleAlpha()[states[0]];
        } else {
            energy = profileEnergy(profiles, 0, states[0], parameters)
                    + profileEnergy(profiles, 1, states[1], parameters)
                    + pairEnergyWithPotential(profiles, 0, states[0], states[1], parameters, potential);
            double firstMeasure = logMeasure(profiles.get(0).cells().get(states[0]));
            double secondMeasure = logMeasure(profiles.get(1).cells().get(states[1]));
            measure = firstMeasure + secondMeasure;
            logWeight = -energy / parameters.temperature() + firstMeasure + secondMeasure;
            bound = messages.forward().get(0)[states[0]][states[1]];
        }
        if (!Double.isFinite(logWeight) || !Double.isFinite(bound) || logWeight > bound) return mass;
        for (int index = 2; index < states.length; index++) {
            cancellation.checkpoint();
            double pair = pairEnergyWithPotential(profiles, index - 1, states[index - 1],
                    states[index], parameters, potential);
            double increment = profileEnergy(profiles, index, states[index], parameters)
                    + pair
                    + tripleEnergy(profiles, index, states[index - 2], states[index - 1], states[index], parameters);
            double nextMeasure = logMeasure(profiles.get(index).cells().get(states[index]));
            energy = energy + increment;
            measure = measure + nextMeasure;
            logWeight = logWeight - increment / parameters.temperature() + nextMeasure;
            bound = messages.forward().get(index - 1)[states[index - 1]][states[index]];
            if (!Double.isFinite(logWeight) || !Double.isFinite(bound) || logWeight > bound) return mass;
        }
        if (Double.doubleToRawLongBits(energy) != Double.doubleToRawLongBits(record.energy())
                || Double.doubleToRawLongBits(measure) != Double.doubleToRawLongBits(record.logMeasure())
                || !Double.isFinite(messages.logPartition()) || logWeight > messages.logPartition()) return mass;
        return StrictMath.exp(logWeight - messages.logPartition());
    }

    private static List<double[]> positionalMarginals(List<InferenceProfile> profiles,
        GraphMessages messages, AttemptMemoryLedger.Owner owner) {
        AttemptMemoryLedger.Owner builder = owner.child("position-marginal-builder");
        List<double[]> result = allocated(builder, listBytes(profiles.size()),
                "marginal materialization", () -> new ArrayList<>(profiles.size()));
        try {
        if (profiles.size() == 1) {
            double[] marginal = allocated(owner,
                    AttemptMemoryLedger.arrayBytes(profiles.get(0).cells().size(), Double.BYTES),
                    "marginal materialization",
                    () -> new double[profiles.get(0).cells().size()]);
            for (int state = 0; state < marginal.length; state++) {
                marginal[state] = StrictMath.exp(messages.singleAlpha()[state] - messages.logPartition());
            }
            return accountedCopy(List.of(marginal), owner, "marginal materialization");
        }
        for (int profile = 0; profile < profiles.size(); profile++) {
            int profileIndex = profile;
            AttemptMemoryLedger.Owner profileOwner = owner.child("position-marginal-" + profile);
            double[] logValues = allocated(profileOwner,
                    AttemptMemoryLedger.arrayBytes(profiles.get(profile).cells().size(), Double.BYTES),
                    "marginal materialization",
                    () -> new double[profiles.get(profileIndex).cells().size()]);
            Arrays.fill(logValues, LOG_ZERO);
            int stage = profile == 0 ? 0 : profile - 1;
            double[][] alpha = messages.forward().get(stage);
            double[][] beta = messages.backward().get(stage);
            for (int first = 0; first < alpha.length; first++) {
                for (int second = 0; second < alpha[first].length; second++) {
                    int state = profile == 0 ? first : second;
                    logValues[state] = logAdd(logValues[state], alpha[first][second] + beta[first][second]);
                }
            }
            double[] marginal = allocated(owner,
                    AttemptMemoryLedger.arrayBytes(logValues.length, Double.BYTES),
                    "marginal materialization", () -> new double[logValues.length]);
            for (int state = 0; state < marginal.length; state++) {
                marginal[state] = StrictMath.exp(logValues[state] - messages.logPartition());
            }
            normalize(marginal);
            result.add(marginal);
            profileOwner.close();
        }
        return accountedCopy(result, owner, "marginal materialization");
        } finally {
            builder.close();
        }
    }

    private static List<double[]> componentMarginals(List<InferenceProfile> profiles,
        List<double[]> positionMarginals, AttemptMemoryLedger.Owner owner) {
        AttemptMemoryLedger.Owner builder = owner.child("component-marginal-builder");
        List<double[]> result = allocated(builder, listBytes(profiles.size()),
                "marginal materialization", () -> new ArrayList<>(profiles.size()));
        try {
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            InferenceProfile profile = profiles.get(profileIndex);
            int components = profile.componentCount();
            double[] marginal = allocated(owner,
                    AttemptMemoryLedger.arrayBytes(components, Double.BYTES),
                    "marginal materialization", () -> new double[components]);
            for (int state = 0; state < profile.cells().size(); state++) {
                for (int component = 0; component < components; component++) {
                    marginal[component] += positionMarginals.get(profileIndex)[state]
                        * profile.componentResponsibility(state, component);
                }
            }
            result.add(marginal);
        }
        return accountedCopy(result, owner, "marginal materialization");
        } finally {
            builder.close();
        }
    }

    private static List<CredibleLateralSet> credibleSets(List<InferenceProfile> profiles,
        List<double[]> marginals, double targetMass, AttemptMemoryLedger.Owner temporaryOwner,
        AttemptMemoryLedger.Owner retainedOwner) {
        AttemptMemoryLedger.Owner builder = retainedOwner.child("credible-set-list-builder");
        List<CredibleLateralSet> result = allocated(builder, listBytes(profiles.size()),
                "result materialization", () -> new ArrayList<>(profiles.size()));
        try {
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            AttemptMemoryLedger.Owner profileOwner = temporaryOwner.child(
                    "credible-set-" + profileIndex);
            InferenceProfile profile = profiles.get(profileIndex);
            double[] marginal = marginals.get(profileIndex);
            List<Integer> order = allocated(profileOwner, listBytes(marginal.length),
                    "marginal materialization", () -> new ArrayList<>(marginal.length));
            for (int state = 0; state < marginal.length; state++) {
                order.add(state);
            }
            order.sort(Comparator.comparingDouble((Integer state) -> marginal[state]).reversed()
                .thenComparingInt(Integer::intValue));
            boolean[] retained = allocated(profileOwner,
                    AttemptMemoryLedger.arrayBytes(marginal.length, 1),
                    "marginal materialization", () -> new boolean[marginal.length]);
            double mass = 0.0;
            for (int state : order) {
                retained[state] = true;
                mass += marginal[state];
                if (mass >= targetMass) {
                    break;
                }
            }
            List<CredibleLateralSet.Interval> intervals = allocated(profileOwner,
                    listBytes(marginal.length), "marginal materialization",
                    () -> new ArrayList<>(marginal.length));
            int start = -1;
            for (int state = 0; state <= retained.length; state++) {
                if (state < retained.length && retained[state] && start < 0) {
                    start = state;
                } else if ((state == retained.length || !retained[state]) && start >= 0) {
                    int end = state - 1;
                    LateralStateCell first = profile.cells().get(start);
                    LateralStateCell last = profile.cells().get(end);
                    intervals.add(allocated(retainedOwner,
                            AttemptMemoryLedger.objectBytes(16), "result materialization",
                            () -> new CredibleLateralSet.Interval(
                                first.offsetMeters() - 0.5 * first.quadratureWidthMeters(),
                                last.offsetMeters() + 0.5 * last.quadratureWidthMeters())));
                    start = -1;
                }
            }
            double retainedMass = Math.min(1.0, mass);
            long setBytes = AttemptMemoryLedger.objectBytes(16) + listBytes(intervals.size());
            result.add(allocated(retainedOwner, setBytes, "result materialization",
                    () -> new CredibleLateralSet(intervals, retainedMass)));
            profileOwner.close();
        }
        return accountedCopy(result, retainedOwner, "result materialization");
        } finally {
            builder.close();
        }
    }

    private static GapSummary gapSummary(List<InferenceProfile> profiles) {
        double measured = 0.0;
        double ambiguous = 0.0;
        double censored = 0.0;
        double absent = 0.0;
        for (int index = 0; index < profiles.size(); index++) {
            double weight = profileQuadrature(profiles, index);
            switch (profiles.get(index).ownership()) {
                case DIRECT_TWO_SIDED -> measured += weight;
                case DIRECT_AMBIGUOUS -> ambiguous += weight;
                case SHOULDER_CENSORED, CORE_CENSORED -> censored += weight;
                default -> absent += weight;
            }
        }
        double longestInternal = 0.0;
        double runStart = Double.NaN;
        for (int index = 1; index < profiles.size() - 1; index++) {
            if (profiles.get(index).entirelyMissing()) {
                if (!Double.isFinite(runStart)) {
                    runStart = profiles.get(index - 1).chainageMeters();
                }
                longestInternal = Math.max(longestInternal,
                    profiles.get(index + 1).chainageMeters() - runStart);
            } else {
                runStart = Double.NaN;
            }
        }
        double terminal = terminalGap(profiles, true) + terminalGap(profiles, false);
        return new GapSummary(measured, ambiguous, censored, absent, longestInternal, terminal);
    }

    private static double terminalGap(List<InferenceProfile> profiles, boolean fromStart) {
        if (profiles.size() < 2) {
            return profiles.get(0).entirelyMissing() ? 1.0 : 0.0;
        }
        int index = fromStart ? 0 : profiles.size() - 1;
        int direction = fromStart ? 1 : -1;
        if (!profiles.get(index).entirelyMissing()) {
            return 0.0;
        }
        double boundary = profiles.get(index).chainageMeters();
        while (index >= 0 && index < profiles.size() && profiles.get(index).entirelyMissing()) {
            boundary = profiles.get(index).chainageMeters();
            index += direction;
        }
        return fromStart ? boundary - profiles.get(0).chainageMeters()
            : profiles.get(profiles.size() - 1).chainageMeters() - boundary;
    }

    private static boolean ambiguous(List<ProbabilisticPath> distinct,
        EvidenceModelParameters parameters) {
        return distinct.size() > 1
            && distinct.get(1).energy() - distinct.get(0).energy() <= parameters.ambiguityEnergyDelta();
    }

    private static double profileEnergy(List<InferenceProfile> profiles, int profileIndex, int state,
        EvidenceModelParameters parameters) {
        InferenceProfile profile = profiles.get(profileIndex);
        return profileQuadrature(profiles, profileIndex) * (parameters.dataWeight()
            * profile.unaryCost(state) + parameters.guideWeight() * profile.structuralGuideCost(state));
    }

    private static double pairEnergy(List<InferenceProfile> profiles, int startProfile, int startState,
        int endState, EvidenceModelParameters parameters) {
        if (parameters.orientationWeight() == 0.0) {
            return 0.0;
        }
        InferenceProfile start = profiles.get(startProfile);
        InferenceProfile end = profiles.get(startProfile + 1);
        MetricPoint first = start.point(startState);
        MetricPoint second = end.point(endState);
        double heading = StrictMath.atan2(second.yMeters() - first.yMeters(), second.xMeters() - first.xMeters());
        double span = end.chainageMeters() - start.chainageMeters();
        // Integrate direct branch-owned evidence at both ends of the physical segment.
        // A weak arrival must not discard a reliable departure, and reversing the trace
        // must preserve this objective. Missing endpoint evidence contributes exactly zero.
        return parameters.orientationWeight() * span * 0.5
            * (orientationMismatch(start, startState, heading)
                + orientationMismatch(end, endState, heading));
    }

    private static double pairEnergyWithPotential(List<InferenceProfile> profiles, int stage,
            int leftState, int rightState, EvidenceModelParameters parameters,
            AdjacentStatePotential potential) {
        double original = pairEnergy(profiles, stage, leftState, rightState, parameters);
        return addPotential(potential, stage, leftState, rightState, original);
    }

    private static double addPotential(AdjacentStatePotential potential, int stage,
            int leftState, int rightState, double originalPairEnergy) {
        if (potential == null) return originalPairEnergy;
        double additional = potential.energy(stage, leftState, rightState);
        if (!Double.isFinite(additional) || additional < 0.0) {
            throw new ArithmeticException("invalid adjacent-state potential");
        }
        double combined = originalPairEnergy + additional;
        if (!Double.isFinite(combined)) {
            throw new ArithmeticException("non-finite adjacent-state energy");
        }
        return combined;
    }

    private static double orientationMismatch(InferenceProfile profile, int state, double heading) {
        var support = profile.orientationSupport(state);
        if (support.modes().isEmpty() || support.certainty() == 0.0) return 0.0;
        return support.certainty() * profile.orientationReliability(state)
                * support.mismatchSquared(heading);
    }

    private static double pairEnergyAtHeading(InferenceProfile start, InferenceProfile end,
            int startState, int endState, EvidenceModelParameters parameters, double heading) {
        double span = end.chainageMeters() - start.chainageMeters();
        return parameters.orientationWeight() * span * 0.5
            * (orientationMismatch(start, startState, heading)
                + orientationMismatch(end, endState, heading));
    }

    private static double tripleEnergyAtHeadings(List<InferenceProfile> profiles, int profileIndex,
            EvidenceModelParameters parameters, double firstHeading, double secondHeading) {
        double normalizedTurn = wrap(secondHeading - firstHeading) / parameters.turnScaleRadians();
        double middleSpan = Math.max(1e-6, 0.5 * (profiles.get(profileIndex).chainageMeters()
            - profiles.get(profileIndex - 2).chainageMeters()));
        return parameters.turnWeight() * EvidenceModelParameters.huber(normalizedTurn) / middleSpan;
    }

    private static double tripleEnergy(List<InferenceProfile> profiles, int profileIndex,
        int beforeState, int priorState, int state, EvidenceModelParameters parameters) {
        if (parameters.turnWeight() == 0.0) {
            return 0.0;
        }
        MetricPoint before = profiles.get(profileIndex - 2).point(beforeState);
        MetricPoint prior = profiles.get(profileIndex - 1).point(priorState);
        MetricPoint current = profiles.get(profileIndex).point(state);
        double firstHeading = StrictMath.atan2(prior.yMeters() - before.yMeters(), prior.xMeters() - before.xMeters());
        double secondHeading = StrictMath.atan2(current.yMeters() - prior.yMeters(), current.xMeters() - prior.xMeters());
        double normalizedTurn = wrap(secondHeading - firstHeading) / parameters.turnScaleRadians();
        double middleSpan = Math.max(1e-6, 0.5 * (profiles.get(profileIndex).chainageMeters()
            - profiles.get(profileIndex - 2).chainageMeters()));
        return parameters.turnWeight() * EvidenceModelParameters.huber(normalizedTurn) / middleSpan;
    }

    private static double profileQuadrature(List<InferenceProfile> profiles, int index) {
        if (profiles.size() == 1) {
            return 1.0;
        }
        if (index == 0) {
            return 0.5 * (profiles.get(1).chainageMeters() - profiles.get(0).chainageMeters());
        }
        if (index == profiles.size() - 1) {
            return 0.5 * (profiles.get(index).chainageMeters() - profiles.get(index - 1).chainageMeters());
        }
        return 0.5 * (profiles.get(index + 1).chainageMeters()
            - profiles.get(index - 1).chainageMeters());
    }

    private static String branchSignature(int[] states, List<InferenceProfile> profiles,
            AttemptMemoryLedger.Owner owner) {
        long mapBytes = AttemptMemoryLedger.objectBytes(48)
                + AttemptMemoryLedger.referenceArrayBytes(Math.max(16L, states.length * 2L))
                + Math.multiplyExact(states.length,
                    AttemptMemoryLedger.objectBytes(40) + AttemptMemoryLedger.objectBytes(8));
        Map<String, Double> spans = allocated(owner, mapBytes, "materialization",
                () -> new LinkedHashMap<>(Math.max(16, states.length * 2)));
        for (int index = 0; index < states.length; index++) {
            String label = profiles.get(index).cells().get(states[index]).branchLabel();
            spans.merge(label, profileQuadrature(profiles, index), Double::sum);
        }
        return spans.entrySet().stream().max(Map.Entry.<String, Double>comparingByValue()
            .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
            .map(Map.Entry::getKey).orElse("unlocalized");
    }

    private static double representativePitch(List<InferenceProfile> profiles) {
        return profiles.stream().flatMap(profile -> profile.cells().stream())
            .mapToDouble(LateralStateCell::quadratureWidthMeters).sorted().skip(
                Math.max(0, profiles.stream().mapToInt(profile -> profile.cells().size()).sum() / 2))
            .findFirst().orElse(1.0);
    }

    private static boolean transitionAllowed(List<InferenceProfile> profiles, int startProfile,
        int startState, int endState, MetricRegion decisionRegion, TransitionAdmission admissions) {
        return admissions.allowed(startProfile, startState, endState, profiles, decisionRegion);
    }

    private static ProbabilisticInferenceResult failureOwnedAfterClose(
            AttemptMemoryLedger.Owner workOwner, ProbabilisticInferenceResult.Status status,
            long pairVisits, long transitions, String explanation, List<InferenceProfile> profiles,
            AttemptMemoryLedger.Owner resultOwner, WorkCounter workCounter) {
        workOwner.close();
        return failureOwned(status, pairVisits, transitions, explanation, profiles, resultOwner, workCounter);
    }

    private static ProbabilisticInferenceResult failureOwned(
            ProbabilisticInferenceResult.Status status, long pairVisits, long transitions,
            String explanation, List<InferenceProfile> profiles,
            AttemptMemoryLedger.Owner resultOwner) {
        return failureOwned(status, pairVisits, transitions, explanation, profiles, resultOwner, null);
    }

    private static ProbabilisticInferenceResult failureOwned(
            ProbabilisticInferenceResult.Status status, long pairVisits, long transitions,
            String explanation, List<InferenceProfile> profiles,
            AttemptMemoryLedger.Owner resultOwner, WorkCounter workCounter) {
        return failureOwned(status, pairVisits, transitions, explanation, explanation, null,
                profiles, resultOwner, workCounter);
    }

    private static ProbabilisticInferenceResult failureOwned(
            ProbabilisticInferenceResult.Status status, long pairVisits, long transitions,
            String explanation, String abortStage,
            AttemptMemoryLedger.ResourceLimitException originalLimit,
            List<InferenceProfile> profiles, AttemptMemoryLedger.Owner resultOwner, WorkCounter workCounter) {
        try {
            // Reserve the complete failure graph atomically: a diagnostic refusal must
            // leave neither its accounting witness nor any other result fragment owned.
            long accountingBytes = workCounter == null ? 0L
                    : AttemptMemoryLedger.objectBytes(40) + AttemptMemoryLedger.objectBytes(8);
            return allocated(resultOwner,
                    AttemptMemoryLedger.objectBytes(128) + AttemptMemoryLedger.objectBytes(48) + accountingBytes,
                    "failure result materialization",
                    () -> {
                        GapSummary gaps = gapSummary(profiles);
                        Optional<InferenceWorkAccounting> accounting = accountingValue(workCounter);
                        return new ProbabilisticInferenceResult(status, Optional.empty(), List.of(),
                            List.of(), Double.NaN, List.of(), List.of(), List.of(), List.of(),
                            List.of(), false, false, Optional.empty(), pairVisits, transitions, gaps, explanation, accounting);
                    });
        } catch (MemoryLimit diagnosticFailure) {
            AttemptMemoryLedger.ResourceLimitException refusal = diagnosticFailure.limitCause();
            Throwable cause = originalLimit == null ? refusal : originalLimit;
            if (originalLimit != null) cause.addSuppressed(refusal);
            throw new TraceMemoryLimitException(abortStage, pairVisits, transitions,
                    resultOwner.currentBytes(), resultOwner.peakBytes(), refusal.limitBytes(), cause);
        }
    }

    private static Optional<InferenceWorkAccounting> accounting(WorkCounter work,
            AttemptMemoryLedger.Owner owner) {
        if (work == null) return Optional.empty();
        return allocated(owner, AttemptMemoryLedger.objectBytes(40) + AttemptMemoryLedger.objectBytes(8),
                "execution accounting", () -> accountingValue(work));
    }

    private static Optional<InferenceWorkAccounting> accountingValue(WorkCounter work) {
        return work == null ? Optional.empty() : Optional.of(new InferenceWorkAccounting(work.mode.version(),
                work.forwardBackwardScores, work.kBestScores, work.logicalKBestExtensions, work.refusedScoreAttempts));
    }

    private static void validate(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
        TraceBudgets budgets) {
        if (profiles == null || profiles.isEmpty() || parameters == null || budgets == null) {
            throw new IllegalArgumentException("Inference requires profiles, parameters and budgets");
        }
        for (int index = 1; index < profiles.size(); index++) {
            if (profiles.get(index).chainageMeters() <= profiles.get(index - 1).chainageMeters()) {
                throw new IllegalArgumentException("Profile chainage must increase strictly");
            }
        }
    }

    private static long nanosToMillis(long nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static double logMeasure(LateralStateCell cell) {
        return cell.exactAnchor() ? 0.0 : StrictMath.log(cell.quadratureWidthMeters());
    }

    private static double logAdd(double first, double second) {
        if (first == LOG_ZERO) {
            return second;
        }
        if (second == LOG_ZERO) {
            return first;
        }
        double maximum = Math.max(first, second);
        return maximum + StrictMath.log(StrictMath.exp(first - maximum) + StrictMath.exp(second - maximum));
    }

    private static double logSumExp(double[] values) {
        double maximum = Arrays.stream(values).max().orElse(LOG_ZERO);
        if (!Double.isFinite(maximum)) {
            return maximum;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += StrictMath.exp(value - maximum);
        }
        return maximum + StrictMath.log(sum);
    }

    private static double[] flatten(double[][] values, AttemptMemoryLedger.Owner owner) {
        long length = 0L;
        for (double[] row : values) length = Math.addExact(length, row.length);
        int arrayLength = Math.toIntExact(length);
        double[] result = allocated(owner,
                AttemptMemoryLedger.arrayBytes(arrayLength, Double.BYTES),
                "forward/backward", () -> new double[arrayLength]);
        int offset = 0;
        for (double[] row : values) {
            System.arraycopy(row, 0, result, offset, row.length);
            offset += row.length;
        }
        return result;
    }

    private static double[][] matrix(int rows, int columns, double initial,
            AttemptMemoryLedger.Owner owner, String stage) {
        double[][] result = allocated(owner, deepArrayBytes(rows, columns, Double.BYTES),
                stage, () -> new double[rows][columns]);
        for (double[] row : result) {
            Arrays.fill(row, initial);
        }
        return result;
    }

    private static void normalize(double[] values) {
        OrderedDoubleSum reduction = new OrderedDoubleSum();
        for (double value : values) reduction.add(value);
        double sum = reduction.value();
        if (sum > 0.0) {
            for (int index = 0; index < values.length; index++) {
                values[index] /= sum;
            }
        }
    }

    private static double wrap(double angle) {
        return StrictMath.atan2(StrictMath.sin(angle), StrictMath.cos(angle));
    }

    private static final Comparator<PathRecord> PATH_ORDER = (first, second) -> {
        int energy = Double.compare(first.energy(), second.energy());
        return energy != 0 ? energy : compareLexical(first, second);
    };

    private static int compareLexical(PathRecord first, PathRecord second) {
        if (first == second) return 0;
        int parent = Long.compare(first.parentRank(), second.parentRank());
        return parent != 0 ? parent : Integer.compare(first.state(), second.state());
    }

    static int compareExtension(double firstEnergy, long firstParentRank, int firstState,
            double secondEnergy, long secondParentRank, int secondState) {
        int energy = Double.compare(firstEnergy, secondEnergy);
        if (energy != 0) return energy;
        int parent = Long.compare(firstParentRank, secondParentRank);
        return parent != 0 ? parent : Integer.compare(firstState, secondState);
    }

    private static int compareStreamHead(List<PathRecord>[][] current, int prior, int state,
            int cap, double[] energies, int[] order, int firstBefore, int firstPosition,
            int secondBefore, int secondPosition) {
        int firstIndex = order[firstBefore * cap + firstPosition];
        int secondIndex = order[secondBefore * cap + secondPosition];
        return compareExtension(energies[firstBefore * cap + firstIndex],
            current[firstBefore][prior].get(firstIndex).lexicalRank, state,
            energies[secondBefore * cap + secondIndex],
            current[secondBefore][prior].get(secondIndex).lexicalRank, state);
    }

    private static void swap(int[] values, int first, int second) {
        int value = values[first];
        values[first] = values[second];
        values[second] = value;
    }

    private static void assignLexicalRanks(List<PathRecord>[][] paths,
            AttemptMemoryLedger.Owner owner) {
        int count = 0;
        for (List<PathRecord>[] row : paths) for (List<PathRecord> cell : row) {
            if (cell != null) count = Math.addExact(count, cell.size());
        }
        int orderedCount = count;
        AttemptMemoryLedger.Owner temporary = owner.child("lexical-order");
        List<PathRecord> ordered = allocated(temporary, listBytes(count),
                "k-best frontier", () -> new ArrayList<>(orderedCount));
        for (List<PathRecord>[] row : paths) for (List<PathRecord> cell : row) if (cell != null) ordered.addAll(cell);
        ordered.sort(ProbabilisticInference::compareLexical);
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).lexicalRank = index;
        temporary.close();
    }

    static final class PathRecord {
        private final long arenaId;
        private AttemptMemoryLedger.MemoryLease memoryLease;
        private final PathRecord parent;
        private final int state;
        private final int length;
        private long lexicalRank;
        private final double energy;
        private final double logMeasure;
        private int externalReferences = 1;
        private int children;
        private PathRecord(long arenaId, PathRecord parent, int state, int length, double energy, double logMeasure) {
            this.arenaId = arenaId;
            this.parent = parent; this.state = state; this.length = length; this.energy = energy; this.logMeasure = logMeasure;
        }
        int[] states() { int[] result = new int[length]; for (PathRecord value = this; value != null; value = value.parent) result[value.length - 1] = value.state; return result; }
        int state() { return state; }
        int length() { return length; }
        long parentRank() { return parent == null ? -1L : parent.lexicalRank; }
        double energy() { return energy; }
        double logMeasure() { return logMeasure; }
    }

    private record KBestResult(List<PathRecord> paths, int terminalCount, boolean resourceLimited,
        long transitions, String explanation, PathArena arena,
        AttemptMemoryLedger.Owner frontierOwner, AttemptMemoryLedger.Owner terminalOwner) {
        KBestResult(List<PathRecord> paths, int terminalCount, boolean resourceLimited,
                long transitions, String explanation) {
            this(paths, terminalCount, resourceLimited, transitions, explanation, null, null, null);
        }

        void releaseFrontier() {
            if (frontierOwner != null) frontierOwner.close();
        }

        void close() {
            if (arena != null) arena.close();
            if (frontierOwner != null) frontierOwner.close();
            if (terminalOwner != null) terminalOwner.close();
        }
    }

    private record GraphMessages(double logPartition, List<double[][]> forward,
        List<double[][]> backward, double[] singleAlpha, long pairVisits, long transitions,
        boolean resourceLimited, boolean noRoute, String explanation) {
        static GraphMessages single(double logPartition, double[] alpha, long pairVisits) {
            return new GraphMessages(logPartition, List.of(), List.of(), alpha, pairVisits, 0,
                false, false, "complete");
        }

        static GraphMessages limit(long pairVisits, long transitions, String explanation) {
            return new GraphMessages(Double.NaN, List.of(), List.of(), null, pairVisits, transitions,
                true, false, explanation);
        }
    }

    static final class PathArena implements AutoCloseable {
        private static final AtomicLong NEXT_ARENA_ID = new AtomicLong();
        private static final long ARENA_BYTES = AttemptMemoryLedger.objectBytes(40);
        private static final long PATH_RECORD_BYTES = AttemptMemoryLedger.objectBytes(64);
        private final long arenaId;
        private final AttemptMemoryLedger.Owner owner;
        private final WorkCounter counter;
        private long releasedRecordCount;
        private boolean closed;

        PathArena(AttemptMemoryLedger.Owner owner) {
            this(owner, null);
        }

        PathArena(AttemptMemoryLedger.Owner owner, WorkCounter counter) {
            this.owner = owner;
            this.counter = counter;
            this.arenaId = NEXT_ARENA_ID.updateAndGet(Math::incrementExact);
            try (var reservation = owner.reserve(ARENA_BYTES)) {
                reservation.adopt(this);
            } catch (AttemptMemoryLedger.ResourceLimitException exception) {
                throw new MemoryLimit("k-best ancestry", exception);
            }
        }

        PathRecord start(int state, double energy, double logMeasure) {
            return create(null, state, 1, energy, logMeasure);
        }

        PathRecord initial(int first, int second, double energy, double logMeasure) {
            PathRecord parent = start(first, 0.0, 0.0);
            parent.lexicalRank = first;
            PathRecord result = create(parent, second, 2, energy, logMeasure);
            release(parent);
            return result;
        }

        PathRecord extend(PathRecord parent, int state, double energyIncrement,
                double logMeasureIncrement) {
            return create(parent, state, parent.length + 1, parent.energy + energyIncrement,
                    parent.logMeasure + logMeasureIncrement);
        }

        PathRecord extendRounded(PathRecord parent, int state, double roundedEnergy,
                double logMeasureIncrement) {
            return create(parent, state, parent.length + 1, roundedEnergy,
                    parent.logMeasure + logMeasureIncrement);
        }

        private PathRecord create(PathRecord parent, int state, int length, double energy,
                double logMeasure) {
            if (closed) throw new IllegalStateException("path arena is closed");
            if (parent != null) {
                requireOwned(parent);
                if (parent.memoryLease == null) throw new IllegalStateException("parent path record is no longer retained");
            }
            int nextChildren = parent == null ? 0 : Math.addExact(parent.children, 1);
            AttemptMemoryLedger.Reservation reservation;
            try {
                reservation = owner.reserve(PATH_RECORD_BYTES);
            } catch (AttemptMemoryLedger.ResourceLimitException exception) {
                throw new MemoryLimit("k-best ancestry", exception);
            }
            AttemptMemoryLedger.MemoryLease adopted = null;
            try {
                PathRecord record = new PathRecord(arenaId, parent, state, length, energy, logMeasure);
                adopted = reservation.adopt(record);
                record.memoryLease = adopted;
                if (counter != null) counter.ancestryRecordsAllocated++;
                if (parent != null) parent.children = nextChildren;
                return record;
            } catch (RuntimeException | Error exception) {
                if (adopted != null) adopted.close();
                else reservation.close();
                throw exception;
            }
        }

        private void requireOwned(PathRecord record) {
            if (record.arenaId != arenaId) throw new IllegalArgumentException("path record belongs to another arena");
        }

        void release(PathRecord record) {
            requireOwned(record);
            if (record.externalReferences <= 0) {
                throw new IllegalStateException("path record has no frontier ownership");
            }
            record.externalReferences--;
            releaseIfUnreferenced(record);
        }

        void releaseFrontier(List<PathRecord> roots) {
            for (PathRecord root : roots) release(root);
        }

        void releaseFrontier(List<PathRecord>[][] roots) {
            for (List<PathRecord>[] row : roots) for (List<PathRecord> cell : row) {
                if (cell != null) for (PathRecord root : cell) release(root);
            }
        }

        void releaseFrontierExcept(List<PathRecord> roots, List<PathRecord> retained) {
            for (PathRecord root : roots) if (!containsIdentity(retained, root)) release(root);
        }

        void releaseFrontierExcept(List<PathRecord>[][] roots, List<PathRecord> retained) {
            for (List<PathRecord>[] row : roots) for (List<PathRecord> cell : row) {
                if (cell == null) continue;
                for (PathRecord root : cell) if (!containsIdentity(retained, root)) release(root);
            }
        }

        private static boolean containsIdentity(List<PathRecord> records, PathRecord target) {
            for (PathRecord record : records) if (record == target) return true;
            return false;
        }

        private void releaseIfUnreferenced(PathRecord record) {
            if (closed) return;
            PathRecord current = record;
            while (current != null && current.externalReferences == 0 && current.children == 0) {
                requireOwned(current);
                AttemptMemoryLedger.MemoryLease lease = current.memoryLease;
                if (lease == null) return;
                current.memoryLease = null;
                lease.close();
                releasedRecordCount++;
                PathRecord parent = current.parent;
                if (parent != null) parent.children--;
                current = parent;
            }
        }

        long releasedRecordCount() {
            return releasedRecordCount;
        }

        @Override
        public void close() {
            owner.close();
            closed = true;
        }
    }

    /** At most K rounded descriptors; stream-tail cutoff is strict finite-energy dominance only. */
    private static final class DescriptorHeap {
        private final int[] before;
        private final int[] prefix;
        private final double[] energy;
        private final long[] rank;
        private final CancellationProbe cancellation;
        private int size;
        private int state;

        DescriptorHeap(int cap, CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
            this.cancellation = cancellation;
            cancellation.checkpoint();
            before = allocated(owner, AttemptMemoryLedger.arrayBytes(cap, Integer.BYTES),
                    "k-best descriptors", () -> new int[cap]);
            prefix = allocated(owner, AttemptMemoryLedger.arrayBytes(cap, Integer.BYTES),
                    "k-best descriptors", () -> new int[cap]);
            energy = allocated(owner, AttemptMemoryLedger.arrayBytes(cap, Double.BYTES),
                    "k-best descriptors", () -> new double[cap]);
            rank = allocated(owner, AttemptMemoryLedger.arrayBytes(cap, Long.BYTES),
                    "k-best descriptors", () -> new long[cap]);
        }

        void reset(int state) { size = 0; this.state = state; }

        boolean strictlyDominated(double candidate, double increment) {
            cancellation.checkpoint();
            return size == energy.length && Double.isFinite(increment) && Double.isFinite(candidate)
                    && Double.isFinite(energy[0]) && candidate > energy[0];
        }

        void offer(int beforeIndex, int prefixIndex, double roundedEnergy, long lexicalRank) {
            cancellation.checkpoint();
            if (size < energy.length) {
                int at = size++;
                set(at, beforeIndex, prefixIndex, roundedEnergy, lexicalRank);
                while (at > 0) {
                    cancellation.checkpoint();
                    int parent = (at - 1) >>> 1;
                    if (compare(at, parent) <= 0) break;
                    swapDescriptor(at, parent); at = parent;
                }
            } else if (compareExtension(roundedEnergy, lexicalRank, state, energy[0], rank[0], state) < 0) {
                replaceWorst(beforeIndex, prefixIndex, roundedEnergy, lexicalRank);
            }
        }

        private void replaceWorst(int beforeIndex, int prefixIndex, double roundedEnergy, long lexicalRank) {
            cancellation.checkpoint();
            set(0, beforeIndex, prefixIndex, roundedEnergy, lexicalRank);
            int at = 0;
            while (true) {
                cancellation.checkpoint();
                int left = 2 * at + 1;
                if (left >= size) break;
                int right = left + 1;
                int worst = right < size && compare(right, left) > 0 ? right : left;
                if (compare(at, worst) >= 0) break;
                swapDescriptor(at, worst); at = worst;
            }
        }

        void sortWinners() {
            cancellation.checkpoint();
            for (int index = 1; index < size; index++) {
                int at = index;
                while (at > 0) {
                    cancellation.checkpoint();
                    if (compare(at, at - 1) >= 0) break;
                    swapDescriptor(at, at - 1); at--;
                }
            }
        }

        private int compare(int first, int second) {
            return compareExtension(energy[first], rank[first], state, energy[second], rank[second], state);
        }

        private void set(int index, int beforeIndex, int prefixIndex, double roundedEnergy, long lexicalRank) {
            before[index] = beforeIndex; prefix[index] = prefixIndex;
            energy[index] = roundedEnergy; rank[index] = lexicalRank;
        }

        private void swapDescriptor(int first, int second) {
            swap(before, first, second); swap(prefix, first, second);
            double oldEnergy = energy[first]; energy[first] = energy[second]; energy[second] = oldEnergy;
            long oldRank = rank[first]; rank[first] = rank[second]; rank[second] = oldRank;
        }
    }

    private static final class WorkCounter {
        private final ProbabilisticExecutionPolicy.Mode mode;
        private final long maximumTransitions;
        private long pairVisits;
        private long transitions;
        private long extensionDescriptors;
        private long ancestryRecordsAllocated;
        private long forwardBackwardScores;
        private long kBestScores;
        private long logicalKBestExtensions;
        private long refusedScoreAttempts;

        private WorkCounter(ProbabilisticExecutionPolicy.Mode mode, long maximumTransitions) {
            this.mode = mode; this.maximumTransitions = maximumTransitions;
        }

        boolean score(boolean forwardBackward) {
            if (mode == ProbabilisticExecutionPolicy.Mode.EAGER_V1) transitions = Math.addExact(transitions, 1L);
            if (Math.addExact(forwardBackwardScores, kBestScores) == maximumTransitions) {
                refusedScoreAttempts = Math.addExact(refusedScoreAttempts, 1L);
                return false;
            }
            if (forwardBackward) forwardBackwardScores = Math.addExact(forwardBackwardScores, 1L);
            else kBestScores = Math.addExact(kBestScores, 1L);
            if (mode == ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2) transitions = Math.addExact(transitions, 1L);
            return true;
        }

        void recordLogicalKBestExtensions(long count) {
            logicalKBestExtensions = Math.addExact(logicalKBestExtensions, count);
        }
    }

    /** One stage's immutable per-state terms, first evaluated at their original use sites. */
    private static final class ProfileTerms {
        private final double[] energies;
        private final double[] measures;
        private final byte[] ready;
        private long energyCalculations;
        private long energyReuses;
        private long measureCalculations;
        private long measureReuses;

        private ProfileTerms(double[] energies, double[] measures, byte[] ready) {
            this.energies = energies; this.measures = measures; this.ready = ready;
        }

        static ProfileTerms create(AttemptMemoryLedger.Owner owner, int states) {
            long bytes = AttemptMemoryLedger.objectBytes(56)
                    + 2L * AttemptMemoryLedger.arrayBytes(states, Double.BYTES)
                    + AttemptMemoryLedger.arrayBytes(states, Byte.BYTES);
            AttemptMemoryLedger.Reservation reservation;
            try {
                reservation = owner.reserve(bytes);
            } catch (AttemptMemoryLedger.ResourceLimitException refused) {
                return null;
            }
            try {
                ProfileTerms terms = new ProfileTerms(new double[states], new double[states], new byte[states]);
                reservation.adopt(terms);
                return terms;
            } catch (RuntimeException | Error failure) {
                reservation.close();
                throw failure;
            }
        }

        double unary(List<InferenceProfile> profiles, int profile, int state, EvidenceModelParameters parameters) {
            if ((ready[state] & 1) != 0) {
                energyReuses++;
                return energies[state];
            }
            double value = profileEnergy(profiles, profile, state, parameters);
            energies[state] = value; ready[state] |= 1; energyCalculations++;
            return value;
        }

        double measure(LateralStateCell cell, int state) {
            if ((ready[state] & 2) != 0) {
                measureReuses++;
                return measures[state];
            }
            double value = logMeasure(cell);
            measures[state] = value; ready[state] |= 2; measureCalculations++;
            return value;
        }
    }

    /** One inference stage's lazy scalar table. Its arrays are reserved before allocation. */
    private static final class ScalarReuseTable {
        private final double[] values;
        private final double[] secondary;
        private final byte[] ready;
        private long calculations;
        private long reuses;

        private ScalarReuseTable(double[] values, double[] secondary, byte[] ready) {
            this.values = values;
            this.secondary = secondary;
            this.ready = ready;
        }

        static ScalarReuseTable create(AttemptMemoryLedger.Owner owner, int rows, int columns,
                boolean kBest) {
            long cellCount = (long) rows * columns;
            if (cellCount <= 0L || cellCount > Integer.MAX_VALUE) return null;
            int cells = (int) cellCount;
            long bytes = AttemptMemoryLedger.objectBytes(40)
                    + AttemptMemoryLedger.arrayBytes(cells, Double.BYTES)
                    + AttemptMemoryLedger.arrayBytes(cells, Byte.BYTES);
            if (!kBest) {
                bytes = Math.addExact(bytes, AttemptMemoryLedger.arrayBytes(cells, Double.BYTES));
            }
            AttemptMemoryLedger.Reservation reservation;
            try {
                reservation = owner.reserve(bytes);
            } catch (AttemptMemoryLedger.ResourceLimitException refused) {
                return null;
            }
            try {
                double[] values = new double[cells];
                double[] secondary = kBest ? null : new double[cells];
                byte[] ready = new byte[cells];
                ScalarReuseTable table = new ScalarReuseTable(values, secondary, ready);
                reservation.adopt(table);
                return table;
            } catch (RuntimeException | Error failure) {
                reservation.close();
                throw failure;
            }
        }

        void close() {
            if (secondary == null) {
                ModernDiagnosticCounters.add("optimization.scalarCache.kBestFirstHeadingCalculations",
                        calculations);
                ModernDiagnosticCounters.add("optimization.scalarCache.kBestFirstHeadingReuses", reuses);
            } else {
                ModernDiagnosticCounters.add("optimization.scalarCache.forwardBackwardPairCalculations",
                        calculations);
                ModernDiagnosticCounters.add("optimization.scalarCache.forwardBackwardPairReuses", reuses);
            }
        }
    }

    static final class MemoryLimit extends RuntimeException {
        private final String stage;

        MemoryLimit(String stage, AttemptMemoryLedger.ResourceLimitException cause) {
            super(stage + ": " + cause.getMessage(), cause);
            this.stage = stage;
        }

        String stage() { return stage; }

        AttemptMemoryLedger.ResourceLimitException limitCause() {
            return (AttemptMemoryLedger.ResourceLimitException) getCause();
        }
    }

    static <T> T allocated(AttemptMemoryLedger.Owner owner, long bytes, String stage,
            Supplier<T> allocation) {
        AttemptMemoryLedger.Reservation reservation;
        try {
            reservation = owner.reserve(bytes);
        } catch (AttemptMemoryLedger.ResourceLimitException exception) {
            throw new MemoryLimit(stage, exception);
        }
        try {
            T value = allocation.get();
            reservation.adopt(value);
            return value;
        } catch (RuntimeException | Error exception) {
            reservation.close();
            throw exception;
        }
    }

    static long listBytes(long size) {
        return Math.addExact(AttemptMemoryLedger.objectBytes(16),
                AttemptMemoryLedger.referenceArrayBytes(size));
    }

    static long deepArrayBytes(long rows, long columns, long elementBytes) {
        return Math.addExact(AttemptMemoryLedger.referenceArrayBytes(rows),
                Math.multiplyExact(rows, AttemptMemoryLedger.arrayBytes(columns, elementBytes)));
    }

    @SuppressWarnings("unchecked")
    private static List<PathRecord>[][] pathGrid(int rows, int columns,
            AttemptMemoryLedger.Owner owner) {
        return allocated(owner, deepArrayBytes(rows, columns, Long.BYTES),
                "k-best frontier", () -> new List[rows][columns]);
    }

    private static int[][] intGrid(int rows, int columns, AttemptMemoryLedger.Owner owner) {
        return allocated(owner, deepArrayBytes(rows, columns, Integer.BYTES),
                "k-best frontier", () -> new int[rows][columns]);
    }

    private static <T> List<T> accountedCopy(List<T> source,
            AttemptMemoryLedger.Owner owner, String stage) {
        if (source.isEmpty()) return List.of();
        return allocated(owner, listBytes(source.size()), stage, () -> List.copyOf(source));
    }

    private static long retainedResultBytes(List<ProbabilisticPath> rawPaths,
            List<ProbabilisticPath> distinctPaths, List<double[]> marginals,
            List<double[]> componentMarginals, List<CredibleLateralSet> credibleSets) {
        long bytes = AttemptMemoryLedger.objectBytes(128);
        bytes = Math.addExact(bytes, listBytes(rawPaths.size()));
        bytes = Math.addExact(bytes, listBytes(distinctPaths.size()));
        bytes = Math.addExact(bytes, listBytes(credibleSets.size()));
        bytes = Math.addExact(bytes, deepCopiedArraysBytes(marginals));
        bytes = Math.addExact(bytes, deepCopiedArraysBytes(componentMarginals));
        bytes = Math.addExact(bytes, deepCopiedArraysBytes(marginals));
        return Math.addExact(bytes, deepCopiedArraysBytes(marginals));
    }

    private static long deepCopiedArraysBytes(List<double[]> arrays) {
        long bytes = listBytes(arrays.size());
        for (double[] array : arrays) {
            bytes = Math.addExact(bytes,
                    AttemptMemoryLedger.arrayBytes(array.length, Double.BYTES));
        }
        return bytes;
    }
}

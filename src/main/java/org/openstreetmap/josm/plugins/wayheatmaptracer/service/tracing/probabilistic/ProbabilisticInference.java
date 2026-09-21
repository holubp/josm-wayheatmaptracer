package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Exact second-order pair-state MAP, posterior and bounded k-best inference. */
public final class ProbabilisticInference {
    private static final double LOG_ZERO = Double.NEGATIVE_INFINITY;

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
        if (cancellation == null) {
            throw new IllegalArgumentException("Cancellation probe is required");
        }
        cancellation.checkpoint();
        validate(profiles, parameters, budgets);
        long stateCount = profiles.stream().mapToLong(profile -> profile.cells().size()).sum();
        if (parameters.orientationWeight() > 0.0
            && profiles.stream().anyMatch(InferenceProfile::orientationResourceLimited)) {
            return failure(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, stateCount, 0,
                "orientation descriptor resource limit", profiles);
        }
        if (profiles.stream().anyMatch(profile -> profile.cells().size() > budgets.maximumStatesPerProfile())) {
            return failure(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, stateCount, 0,
                "state budget exceeded", profiles);
        }
        try {
            GraphMessages messages = forwardBackward(profiles, parameters, budgets, decisionRegion, cancellation);
            if (messages.resourceLimited()) {
                return failure(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,
                    messages.pairVisits(), messages.transitions(), messages.explanation(), profiles);
            }
            if (messages.noRoute()) {
                return failure(ProbabilisticInferenceResult.Status.NO_ROUTE, messages.pairVisits(),
                    messages.transitions(), "no route inside decision region", profiles);
            }
            if (!Double.isFinite(messages.logPartition())) {
                return failure(ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,
                    messages.pairVisits(), messages.transitions(), "non-finite partition function", profiles);
            }
            KBestResult kBest = enumerateKBest(profiles, parameters, budgets, decisionRegion, cancellation);
            if (kBest.resourceLimited()) {
                return failure(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,
                    messages.pairVisits(), kBest.transitions(), kBest.explanation(), profiles);
            }
            List<ProbabilisticPath> rawPaths = materialize(kBest.paths(), profiles, parameters,
                messages.logPartition());
            double pitch = representativePitch(profiles);
            int distinctCap = Math.min(8, budgets.maximumDistinctAlternatives());
            List<ProbabilisticPath> distinct = new PathAlternativeSelector().select(rawPaths, profiles,
                pitch, distinctCap);
            boolean alternativeTruncated = kBest.truncated()
                || distinct.size() == distinctCap
                    && rawPaths.stream().anyMatch(path -> distinct.stream().noneMatch(path::equals));
            GapSummary gaps = gapSummary(profiles);
            boolean allMissing = profiles.stream().allMatch(InferenceProfile::entirelyMissing);
            ProbabilisticInferenceResult.Status status = allMissing
                ? ProbabilisticInferenceResult.Status.ALL_MISSING
                : gaps.longestInternalGapMeters() > 20.0 || gaps.terminalGapMeters() > 0.0
                    ? ProbabilisticInferenceResult.Status.REVIEW_REQUIRED
                    : ambiguous(distinct, parameters) ? ProbabilisticInferenceResult.Status.AMBIGUOUS
                        : ProbabilisticInferenceResult.Status.COMPLETE;
            List<double[]> marginals = positionalMarginals(profiles, messages);
            List<double[]> componentMarginals = componentMarginals(profiles, marginals);
            List<CredibleLateralSet> credibleSets = credibleSets(profiles, marginals, 0.95);
            return new ProbabilisticInferenceResult(status,
                rawPaths.isEmpty() ? Optional.empty() : Optional.of(rawPaths.get(0)), rawPaths, distinct,
                messages.logPartition(), marginals, componentMarginals, marginals, marginals, credibleSets, !allMissing,
                alternativeTruncated, messages.pairVisits(), messages.transitions(), gaps,
                allMissing ? "all profiles lack localized evidence" : alternativeTruncated
                    ? "ALTERNATIVE_SEARCH_TRUNCATED" : "complete retained graph");
        } catch (ArithmeticException exception) {
            return failure(ProbabilisticInferenceResult.Status.NUMERIC_FAILURE, stateCount, 0,
                "numeric failure", profiles);
        }
    }

    private static GraphMessages forwardBackward(List<InferenceProfile> profiles,
        EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion decisionRegion,
        CancellationProbe cancellation) {
        cancellation.checkpoint();
        if (profiles.size() == 1) {
            InferenceProfile profile = profiles.get(0);
            if (profile.cells().size() > budgets.maximumPairVisits()) {
                return GraphMessages.limit(profile.cells().size(), 0, "pair-visit budget exceeded");
            }
            double[] alpha = new double[profile.cells().size()];
            for (int state = 0; state < alpha.length; state++) {
                alpha[state] = -profileEnergy(profiles, 0, state, parameters) / parameters.temperature()
                    + logMeasure(profile.cells().get(state));
            }
            return GraphMessages.single(logSumExp(alpha), alpha, profile.cells().size());
        }

        List<double[][]> forward = new ArrayList<>(profiles.size() - 1);
        int firstStates = profiles.get(0).cells().size();
        int secondStates = profiles.get(1).cells().size();
        long pairVisits = (long) firstStates * secondStates;
        if (pairVisits > budgets.maximumPairVisits()) {
            return GraphMessages.limit(pairVisits, 0, "pair-visit budget exceeded");
        }
        double[][] initial = matrix(firstStates, secondStates, LOG_ZERO);
        for (int first = 0; first < firstStates; first++) {
            for (int second = 0; second < secondStates; second++) {
                if (!transitionAllowed(profiles, 0, first, second, decisionRegion)) {
                    continue;
                }
                double energy = profileEnergy(profiles, 0, first, parameters)
                    + profileEnergy(profiles, 1, second, parameters)
                    + pairEnergy(profiles, 0, first, second, parameters);
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
            if (pairVisits > budgets.maximumPairVisits()) {
                return GraphMessages.limit(pairVisits, transitions, "pair-visit budget exceeded");
            }
            double[][] current = matrix(previousStates, currentStates, LOG_ZERO);
            double[][] previous = forward.get(profileIndex - 2);
            for (int before = 0; before < previousPreviousStates; before++) {
                for (int prior = 0; prior < previousStates; prior++) {
                    double prefix = previous[before][prior];
                    if (!Double.isFinite(prefix)) {
                        continue;
                    }
                    for (int state = 0; state < currentStates; state++) {
                        if (!transitionAllowed(profiles, profileIndex - 1, prior, state, decisionRegion)) {
                            continue;
                        }
                        transitions++;
                        if ((transitions & 1023L) == 0L) {
                            cancellation.checkpoint();
                        }
                        if (transitions > budgets.maximumTransitions()) {
                            return GraphMessages.limit(pairVisits, transitions,
                                "transition budget exceeded");
                        }
                        double increment = profileEnergy(profiles, profileIndex, state, parameters)
                            + pairEnergy(profiles, profileIndex - 1, prior, state, parameters)
                            + tripleEnergy(profiles, profileIndex, before, prior, state, parameters);
                        double term = prefix - increment / parameters.temperature()
                            + logMeasure(profiles.get(profileIndex).cells().get(state));
                        current[prior][state] = logAdd(current[prior][state], term);
                    }
                }
            }
            forward.add(current);
        }

        List<double[][]> backward = new ArrayList<>(java.util.Collections.nCopies(forward.size(), null));
        double[][] terminal = matrix(forward.get(forward.size() - 1).length,
            forward.get(forward.size() - 1)[0].length, 0.0);
        backward.set(backward.size() - 1, terminal);
        for (int stage = backward.size() - 2; stage >= 0; stage--) {
            int currentProfile = stage + 1;
            int nextProfile = currentProfile + 1;
            int beforeStates = profiles.get(currentProfile - 1).cells().size();
            int currentStates = profiles.get(currentProfile).cells().size();
            int nextStates = profiles.get(nextProfile).cells().size();
            double[][] values = matrix(beforeStates, currentStates, LOG_ZERO);
            double[][] nextBackward = backward.get(stage + 1);
            for (int before = 0; before < beforeStates; before++) {
                for (int currentState = 0; currentState < currentStates; currentState++) {
                    double accumulated = LOG_ZERO;
                    for (int next = 0; next < nextStates; next++) {
                        if (!transitionAllowed(profiles, currentProfile, currentState, next, decisionRegion)) {
                            continue;
                        }
                        transitions++;
                        if ((transitions & 1023L) == 0L) {
                            cancellation.checkpoint();
                        }
                        if (transitions > budgets.maximumTransitions()) {
                            return GraphMessages.limit(pairVisits, transitions,
                                "transition budget exceeded during backward inference");
                        }
                        double increment = profileEnergy(profiles, nextProfile, next, parameters)
                            + pairEnergy(profiles, currentProfile, currentState, next, parameters)
                            + tripleEnergy(profiles, nextProfile, before, currentState, next, parameters);
                        double term = -increment / parameters.temperature()
                            + logMeasure(profiles.get(nextProfile).cells().get(next))
                            + nextBackward[currentState][next];
                        accumulated = logAdd(accumulated, term);
                    }
                    values[before][currentState] = accumulated;
                }
            }
            backward.set(stage, values);
        }
        double logPartition = logSumExp(flatten(forward.get(forward.size() - 1)));
        return new GraphMessages(logPartition, forward, backward, null, pairVisits, transitions,
            false, !Double.isFinite(logPartition), "complete");
    }

    private static KBestResult enumerateKBest(List<InferenceProfile> profiles,
        EvidenceModelParameters parameters, TraceBudgets budgets, MetricRegion decisionRegion,
        CancellationProbe cancellation) {
        cancellation.checkpoint();
        int cap = Math.min(32, budgets.maximumRawAlternatives());
        if (profiles.size() == 1) {
            List<PathRecord> paths = new ArrayList<>();
            InferenceProfile profile = profiles.get(0);
            for (int state = 0; state < profile.cells().size(); state++) {
                paths.add(new PathRecord(new int[] {state}, profileEnergy(profiles, 0, state, parameters),
                    logMeasure(profile.cells().get(state))));
            }
            paths.sort(PATH_ORDER);
            boolean truncated = paths.size() > cap;
            return new KBestResult(List.copyOf(paths.subList(0, Math.min(cap, paths.size()))),
                truncated, false, 0, "complete");
        }

        int firstStates = profiles.get(0).cells().size();
        int secondStates = profiles.get(1).cells().size();
        @SuppressWarnings("unchecked")
        List<PathRecord>[][] current = new List[firstStates][secondStates];
        for (int first = 0; first < firstStates; first++) {
            for (int second = 0; second < secondStates; second++) {
                if (!transitionAllowed(profiles, 0, first, second, decisionRegion)) {
                    current[first][second] = List.of();
                    continue;
                }
                double energy = profileEnergy(profiles, 0, first, parameters)
                    + profileEnergy(profiles, 1, second, parameters)
                    + pairEnergy(profiles, 0, first, second, parameters);
                current[first][second] = List.of(new PathRecord(new int[] {first, second}, energy,
                    logMeasure(profiles.get(0).cells().get(first))
                        + logMeasure(profiles.get(1).cells().get(second))));
            }
        }
        long transitions = 0;
        boolean truncated = false;
        for (int profileIndex = 2; profileIndex < profiles.size(); profileIndex++) {
            cancellation.checkpoint();
            int previousStates = profiles.get(profileIndex - 1).cells().size();
            int nextStates = profiles.get(profileIndex).cells().size();
            @SuppressWarnings("unchecked")
            List<PathRecord>[][] next = new List[previousStates][nextStates];
            for (int prior = 0; prior < previousStates; prior++) {
                for (int state = 0; state < nextStates; state++) {
                    List<PathRecord> candidates = new ArrayList<>();
                    for (int before = 0; before < current.length; before++) {
                        if (!transitionAllowed(profiles, profileIndex - 1, prior, state, decisionRegion)) {
                            continue;
                        }
                        List<PathRecord> prefixes = current[before][prior];
                        if (prefixes == null || prefixes.isEmpty()) {
                            continue;
                        }
                        double increment = profileEnergy(profiles, profileIndex, state, parameters)
                            + pairEnergy(profiles, profileIndex - 1, prior, state, parameters)
                            + tripleEnergy(profiles, profileIndex, before, prior, state, parameters);
                        double measure = logMeasure(profiles.get(profileIndex).cells().get(state));
                        for (PathRecord prefix : prefixes) {
                            transitions++;
                            if ((transitions & 1023L) == 0L) {
                                cancellation.checkpoint();
                            }
                            if (transitions > budgets.maximumTransitions()) {
                                return new KBestResult(List.of(), true, true, transitions,
                                    "transition budget exceeded during k-best");
                            }
                            candidates.add(prefix.extend(state, increment, measure));
                        }
                    }
                    candidates.sort(PATH_ORDER);
                    if (candidates.size() > cap) {
                        truncated = true;
                        candidates = new ArrayList<>(candidates.subList(0, cap));
                    }
                    next[prior][state] = List.copyOf(candidates);
                }
            }
            current = next;
        }
        List<PathRecord> result = new ArrayList<>();
        for (List<PathRecord>[] row : current) {
            for (List<PathRecord> paths : row) {
                if (paths != null) {
                    result.addAll(paths);
                }
            }
        }
        result.sort(PATH_ORDER);
        if (result.size() > cap) {
            truncated = true;
            result = new ArrayList<>(result.subList(0, cap));
        }
        return new KBestResult(List.copyOf(result), truncated, false, transitions, "complete");
    }

    private static List<ProbabilisticPath> materialize(List<PathRecord> paths,
        List<InferenceProfile> profiles, EvidenceModelParameters parameters, double logPartition) {
        List<ProbabilisticPath> result = new ArrayList<>(paths.size());
        for (PathRecord record : paths) {
            List<MetricPoint> points = new ArrayList<>(record.states().length);
            for (int index = 0; index < record.states().length; index++) {
                points.add(profiles.get(index).point(record.states()[index]));
            }
            double mass = Math.exp(-record.energy() / parameters.temperature()
                + record.logMeasure() - logPartition);
            result.add(new ProbabilisticPath(record.states(), points,
                branchSignature(record.states(), profiles), record.energy(), record.logMeasure(), mass));
        }
        return List.copyOf(result);
    }

    private static List<double[]> positionalMarginals(List<InferenceProfile> profiles,
        GraphMessages messages) {
        List<double[]> result = new ArrayList<>(profiles.size());
        if (profiles.size() == 1) {
            double[] marginal = new double[profiles.get(0).cells().size()];
            for (int state = 0; state < marginal.length; state++) {
                marginal[state] = Math.exp(messages.singleAlpha()[state] - messages.logPartition());
            }
            return List.of(marginal);
        }
        for (int profile = 0; profile < profiles.size(); profile++) {
            double[] logValues = new double[profiles.get(profile).cells().size()];
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
            double[] marginal = new double[logValues.length];
            for (int state = 0; state < marginal.length; state++) {
                marginal[state] = Math.exp(logValues[state] - messages.logPartition());
            }
            normalize(marginal);
            result.add(marginal);
        }
        return List.copyOf(result);
    }

    private static List<double[]> componentMarginals(List<InferenceProfile> profiles,
        List<double[]> positionMarginals) {
        List<double[]> result = new ArrayList<>(profiles.size());
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            double[][] responsibilities = profiles.get(profileIndex).componentResponsibilities();
            int components = responsibilities.length == 0 ? 0 : responsibilities[0].length;
            double[] marginal = new double[components];
            for (int state = 0; state < responsibilities.length; state++) {
                for (int component = 0; component < components; component++) {
                    marginal[component] += positionMarginals.get(profileIndex)[state]
                        * responsibilities[state][component];
                }
            }
            result.add(marginal);
        }
        return List.copyOf(result);
    }

    private static List<CredibleLateralSet> credibleSets(List<InferenceProfile> profiles,
        List<double[]> marginals, double targetMass) {
        List<CredibleLateralSet> result = new ArrayList<>();
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            InferenceProfile profile = profiles.get(profileIndex);
            double[] marginal = marginals.get(profileIndex);
            List<Integer> order = new ArrayList<>();
            for (int state = 0; state < marginal.length; state++) {
                order.add(state);
            }
            order.sort(Comparator.comparingDouble((Integer state) -> marginal[state]).reversed()
                .thenComparingInt(Integer::intValue));
            boolean[] retained = new boolean[marginal.length];
            double mass = 0.0;
            for (int state : order) {
                retained[state] = true;
                mass += marginal[state];
                if (mass >= targetMass) {
                    break;
                }
            }
            List<CredibleLateralSet.Interval> intervals = new ArrayList<>();
            int start = -1;
            for (int state = 0; state <= retained.length; state++) {
                if (state < retained.length && retained[state] && start < 0) {
                    start = state;
                } else if ((state == retained.length || !retained[state]) && start >= 0) {
                    int end = state - 1;
                    LateralStateCell first = profile.cells().get(start);
                    LateralStateCell last = profile.cells().get(end);
                    intervals.add(new CredibleLateralSet.Interval(
                        first.offsetMeters() - 0.5 * first.quadratureWidthMeters(),
                        last.offsetMeters() + 0.5 * last.quadratureWidthMeters()));
                    start = -1;
                }
            }
            result.add(new CredibleLateralSet(intervals, Math.min(1.0, mass)));
        }
        return List.copyOf(result);
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
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport support =
            end.orientationSupport(endState);
        if (support.modes().isEmpty() || support.certainty() == 0.0) {
            return 0.0;
        }
        MetricPoint first = start.point(startState);
        MetricPoint second = end.point(endState);
        double heading = Math.atan2(second.yMeters() - first.yMeters(), second.xMeters() - first.xMeters());
        double span = end.chainageMeters() - start.chainageMeters();
        return parameters.orientationWeight() * span * support.certainty()
            * support.mismatchSquared(heading);
    }

    private static double tripleEnergy(List<InferenceProfile> profiles, int profileIndex,
        int beforeState, int priorState, int state, EvidenceModelParameters parameters) {
        if (parameters.turnWeight() == 0.0) {
            return 0.0;
        }
        MetricPoint before = profiles.get(profileIndex - 2).point(beforeState);
        MetricPoint prior = profiles.get(profileIndex - 1).point(priorState);
        MetricPoint current = profiles.get(profileIndex).point(state);
        double firstHeading = Math.atan2(prior.yMeters() - before.yMeters(), prior.xMeters() - before.xMeters());
        double secondHeading = Math.atan2(current.yMeters() - prior.yMeters(), current.xMeters() - prior.xMeters());
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

    private static String branchSignature(int[] states, List<InferenceProfile> profiles) {
        Map<String, Double> spans = new LinkedHashMap<>();
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
        int startState, int endState, MetricRegion decisionRegion) {
        if (decisionRegion == null) {
            return true;
        }
        MetricPoint start = profiles.get(startProfile).point(startState);
        MetricPoint end = profiles.get(startProfile + 1).point(endState);
        return decisionRegion.contains(start) && decisionRegion.contains(end)
            && decisionRegion.containsSegment(start, end);
    }

    private static ProbabilisticInferenceResult failure(ProbabilisticInferenceResult.Status status,
        long pairVisits, long transitions, String explanation, List<InferenceProfile> profiles) {
        return new ProbabilisticInferenceResult(status, Optional.empty(), List.of(), List.of(), Double.NaN,
            List.of(), List.of(), List.of(), List.of(), List.of(), false, status == ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,
            pairVisits, transitions, gapSummary(profiles), explanation);
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

    private static double logMeasure(LateralStateCell cell) {
        return cell.exactAnchor() ? 0.0 : Math.log(cell.quadratureWidthMeters());
    }

    private static double logAdd(double first, double second) {
        if (first == LOG_ZERO) {
            return second;
        }
        if (second == LOG_ZERO) {
            return first;
        }
        double maximum = Math.max(first, second);
        return maximum + Math.log(Math.exp(first - maximum) + Math.exp(second - maximum));
    }

    private static double logSumExp(double[] values) {
        double maximum = Arrays.stream(values).max().orElse(LOG_ZERO);
        if (!Double.isFinite(maximum)) {
            return maximum;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += Math.exp(value - maximum);
        }
        return maximum + Math.log(sum);
    }

    private static double[] flatten(double[][] values) {
        return Arrays.stream(values).flatMapToDouble(Arrays::stream).toArray();
    }

    private static double[][] matrix(int rows, int columns, double initial) {
        double[][] result = new double[rows][columns];
        for (double[] row : result) {
            Arrays.fill(row, initial);
        }
        return result;
    }

    private static void normalize(double[] values) {
        double sum = Arrays.stream(values).sum();
        if (sum > 0.0) {
            for (int index = 0; index < values.length; index++) {
                values[index] /= sum;
            }
        }
    }

    private static double wrap(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    private static final Comparator<PathRecord> PATH_ORDER = (first, second) -> {
        int energy = Double.compare(first.energy(), second.energy());
        if (energy != 0) {
            return energy;
        }
        int[] left = first.states();
        int[] right = second.states();
        for (int index = 0; index < Math.min(left.length, right.length); index++) {
            int comparison = Integer.compare(left[index], right[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    };

    private record PathRecord(int[] states, double energy, double logMeasure) {
        PathRecord extend(int state, double energyIncrement, double logMeasureIncrement) {
            int[] extended = Arrays.copyOf(states, states.length + 1);
            extended[states.length] = state;
            return new PathRecord(extended, energy + energyIncrement, logMeasure + logMeasureIncrement);
        }
    }

    private record KBestResult(List<PathRecord> paths, boolean truncated, boolean resourceLimited,
        long transitions, String explanation) { }

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
}

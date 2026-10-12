package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceWorkAccounting;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Independent retained-prefix oracle: no production scoring, heap, ordering, or arena helpers. */
class V022ExactKBestExecutionTest {
    private static final EvidenceModelParameters DATA = new EvidenceModelParameters(
            "exact-kbest-reference", 1, 0, 0, 0, 0, 1, 1, 0);

    @Test
    void eagerMatchesIndependentRetainedPrefixesAndExhaustivePosteriorAtAllRawCaps() {
        var profiles = List.of(profile(0, new double[] {0.07, 0.17}, new double[] {1, 2}),
                profile(2, new double[] {0.23, -0.11, 0.41}, new double[] {0.5, 1, 1.5}),
                profile(5, new double[] {0.09}, new double[] {2}),
                profile(9, new double[] {-0.13, 0.29}, new double[] {1.25, 0.75}));
        var exhaustive = reference(profiles, Integer.MAX_VALUE, false);
        for (int cap : new int[] {1, 2, 32}) {
            var expected = reference(profiles, cap, false);
            var actual = new ProbabilisticInference().solve(profiles, DATA, budgets(cap, 1_000_000),
                    MetricRegion.rectangle(-1, -1, 10, 1));
            assertPaths(expected.paths(), actual.rawPaths());
            assertPaths(expected.paths().subList(0, 1), List.of(actual.mapPath().orElseThrow()));
            assertEquals(cap == 1 ? ProbabilisticInferenceResult.Status.REVIEW_REQUIRED
                    : ProbabilisticInferenceResult.Status.COMPLETE, actual.status(),
                    "one capped retained path cannot establish that a close rival is absent");
            assertEquals(Math.min(cap + 1, 12), actual.completion().orElseThrow().completePathsAtSaturation());
            assertEquals(cap < 12, actual.completion().orElseThrow().terminalCountSaturated());
            assertEquals(cap > 1 && cap < 12, actual.alternativeSearchTruncated());
            assertEquals(expected.logicalExtensions() + forwardBackwardCount(profiles), actual.evaluatedTransitions());
            assertPosterior(exhaustive.paths(), profiles, actual);
        }
    }

    @Test
    void independentDominanceWitnessHasTheSamePathsButFewerScoredExtensions() {
        var profiles = dominanceFixture();
        var eager = reference(profiles, 32, false);
        var selective = reference(profiles, 32, true);
        assertReferencePaths(eager.paths(), selective.paths());
        assertEquals(2080L, forwardBackwardCount(profiles));
        assertEquals(1536L, eager.logicalExtensions());
        assertEquals(1536L, eager.scoredExtensions());
        assertEquals(1536L, selective.logicalExtensions());
        assertEquals(1071L, selective.scoredExtensions(), "strictly dominated tails retain their logical witness");
        var production = new ProbabilisticInference().solve(profiles, DATA, budgets(32, 3616));
        assertEquals(3616L, production.evaluatedTransitions());
        assertPaths(eager.paths(), production.rawPaths());
        assertEquals(33, production.completion().orElseThrow().completePathsAtSaturation());
        assertTrue(production.alternativeSearchTruncated());
        assertEquals(ProbabilisticInferenceResult.Status.COMPLETE, production.status());
    }

    @Test
    void explicitExactExecutionAdmitsTheUnchangedGraphWithin3300Scores() {
        var profiles = dominanceFixture();
        var actual = optimized(profiles, budgets(32, 3300));
        assertEquals(ProbabilisticInferenceResult.Status.COMPLETE, actual.status(),
                "the independently proved3151-score execution fits without changingK32 or the graph");
        assertPaths(reference(profiles, 32, false).paths(), actual.rawPaths());
        assertEquals(33, actual.completion().orElseThrow().completePathsAtSaturation());
        assertTrue(actual.alternativeSearchTruncated());
        var work = accounting(actual);
        assertEquals("terminal-path-count-scored-work-v2", work.policy());
        assertEquals(new Accounting(work.policy(), 2080, 1071, 1536, 0), work);
        assertEquals(3151L, actual.evaluatedTransitions());
    }

    @Test
    void exactCapAcceptsTheLastPeekAndRefusesAnUncomputedNextScoreWithoutPartialOutput() {
        var profiles = dominanceFixture();
        var admitted = optimized(profiles, budgets(32, 3151));
        assertEquals(ProbabilisticInferenceResult.Status.COMPLETE, admitted.status());
        assertEquals(3151, admitted.evaluatedTransitions());
        assertEquals(0, accounting(admitted).refused());
        var refused = optimized(profiles, budgets(32, 3150));
        assertNoResult(refused);
        assertEquals(3150, refused.evaluatedTransitions());
        assertEquals(new Accounting("terminal-path-count-scored-work-v2", 2080, 1070, 1536, 1), accounting(refused));
        var duringPrimaryMessages = optimized(profiles, budgets(32, 31));
        assertNoResult(duringPrimaryMessages);
        assertEquals(new Accounting("terminal-path-count-scored-work-v2", 31, 0, 0, 1), accounting(duringPrimaryMessages));
    }

    @Test
    void oldEntryPointPreservesCapPlusOneButAttestsComputedAndRefusedWorkSeparately() {
        var binary = List.of(profile(0, new double[] {0, 0}, ones(2)),
                profile(1, new double[] {0, 0}, ones(2)), profile(2, new double[] {0, 0}, ones(2)),
                profile(3, new double[] {0, 0}, ones(2)));
        var old = new ProbabilisticInference().solve(binary, DATA, budgets(32, 40));
        assertNoResult(old);
        assertEquals(41L, old.evaluatedTransitions(), "old callers retain the historical refusedattempt counter");
        assertEquals(new Accounting("terminal-path-count-cumulative-work-v1", 32, 8, 10, 1), accounting(old));
    }

    @Test
    void compatibilityConstructorsAndPreEntryRefusalCannotAttestAnExecutedPolicy() {
        var baseline = optimized(dominanceFixture(), budgets(32, 3300));
        var compatibility = new ProbabilisticInferenceResult(baseline.status(), baseline.mapPath(),
                baseline.rawPaths(), baseline.distinctPaths(), baseline.logPartition(), baseline.positionMarginals(),
                baseline.componentMarginals(), baseline.forwardPositionMarginals(), baseline.backwardPositionMarginals(),
                baseline.credibleSets(), baseline.posteriorUsable(), baseline.alternativeSearchTruncated(),
                baseline.completion(), baseline.evaluatedPairVisits(), baseline.evaluatedTransitions(),
                baseline.gapSummary(), baseline.explanation());
        assertTrue(compatibility.workAccounting().isEmpty());
        var oversized = List.of(profile(0, new double[97], ones(97)));
        var refused = optimized(oversized, budgets(32, 3300));
        assertNoResult(refused);
        assertEquals(0L, refused.evaluatedTransitions());
        assertTrue(refused.workAccounting().isEmpty(), "a state-budget refusal precedes solver entry");
    }

    private static void assertNoResult(ProbabilisticInferenceResult result) {
        assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, result.status());
        assertTrue(result.mapPath().isEmpty()); assertTrue(result.rawPaths().isEmpty());
        assertTrue(result.distinctPaths().isEmpty()); assertTrue(result.completion().isEmpty());
        assertFalse(result.posteriorUsable()); assertFalse(result.alternativeSearchTruncated());
    }

    private static ProbabilisticInferenceResult optimized(List<InferenceProfile> profiles, TraceBudgets budgets) {
        var ledger = AttemptMemoryLedger.production(); var owner = ledger.rootOwner();
        try {
            return new ProbabilisticInference().solve(profiles, DATA, budgets, null,
                    CancellationProbe.NONE, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
        }
        finally { owner.close(); }
    }

    private static Accounting accounting(ProbabilisticInferenceResult result) {
        var work = result.workAccounting().orElseThrow();
        return new Accounting(work.executionPolicy(), work.forwardBackwardScores(), work.kBestScores(),
                work.logicalKBestExtensions(), work.refusedScoreAttempts());
    }

    record Accounting(String policy, long forwardBackward, long kBest, long logical, long refused) { }

    @Test
    void nonzeroOrientationTurnAndGuideMatchIndependentGroupedArithmetic() {
        var parameters = new EvidenceModelParameters("all-terms-reference", 0.7, 1, 1.3, 0.4, 0.6, 0.3, 1, 0.1);
        var profiles = new ArrayList<InferenceProfile>();
        for (int index = 0; index < 5; index++) {
            var base = profile(index * 2, new double[] {-0.2 + index * 0.01, 0.3, 0.1}, ones(3));
            profiles.add(new InferenceProfile(base.chainageMeters(),
                    new MetricPoint(index * 2, index + 0.15 * index * index), base.normalUnit(), base.cells(),
                    base.unaryCosts(), List.of(0.0), 0.8, ObservationOwnership.DIRECT_TWO_SIDED, false)
                    .withStructuralGuideCosts(new double[] {0.0, 0.2, 0.1}));
        }
        for (int cap : new int[] {1, 2, 32}) {
            var eager = execute(profiles, parameters, budgets(cap, 1_000_000), null,
                    ProbabilisticExecutionPolicy.Mode.EAGER_V1);
            var exact = execute(profiles, parameters, budgets(cap, 1_000_000), null,
                    ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            assertPaths(reference(profiles, parameters, cap, false).paths(), eager.rawPaths());
            assertResultParity(eager, exact);
        }
    }

    @Test
    void roundedCollapseSignedZeroNegativeAndExtremeFiniteScoresKeepTheSameRetainedPrefixes() {
        var fixtures = List.of(
                List.of(profile(0, new double[] {0.5, 0}, ones(2)), profile(1, new double[] {0}, ones(1)),
                    profile(2, new double[] {0}, ones(1)), profile(3, new double[] {2e16}, ones(1))),
                List.of(profile(0, new double[] {-0.0, 0.0}, ones(2)),
                    profile(1, new double[] {0.0, -0.0}, ones(2)), profile(2, new double[] {-0.0, 0.0}, ones(2)),
                    profile(3, new double[] {0}, ones(1))),
                List.of(profile(0, new double[] {-3, -2}, ones(2)), profile(1, new double[] {0.1, -0.1}, ones(2)),
                    profile(2, new double[] {-10, -11}, ones(2)), profile(3, new double[] {2, 1}, ones(2))),
                List.of(profile(0, new double[] {1e300, -1e300}, ones(2)), profile(1, new double[] {1e300}, ones(1)),
                    profile(2, new double[] {0.25, 0}, ones(2)), profile(3, new double[] {2e16}, ones(1))));
        for (var profiles : fixtures) for (int cap : new int[] {1, 2, 32}) {
            var eager = execute(profiles, DATA, budgets(cap, 1_000_000), null, ProbabilisticExecutionPolicy.Mode.EAGER_V1);
            var exact = execute(profiles, DATA, budgets(cap, 1_000_000), null, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            assertPaths(reference(profiles, cap, false).paths(), eager.rawPaths());
            assertResultParity(eager, exact);
        }
        var collapsed = optimized(fixtures.get(0), budgets(2, 1_000_000));
        assertArrayEquals(new int[] {0, 0, 0, 0}, collapsed.rawPaths().get(0).stateIndices(),
                "later rounded equality must reverse the opposing earlier energy/lexical preference");
        assertBits(collapsed.rawPaths().get(0).energy(), collapsed.rawPaths().get(1).energy());
    }

    @Test
    void nonfiniteIncrementOrComparedScoreDisablesTailCutoffWithoutInventingAPath() {
        var profiles = List.of(profile(0, new double[] {0, 0}, ones(2)),
                profile(1, new double[] {0, 0}, ones(2)), profile(2, new double[] {0, Double.MAX_VALUE}, ones(2)),
                profile(6, new double[] {0}, ones(1)), profile(7, new double[] {0}, ones(1)));
        var eager = execute(profiles, DATA, budgets(2, 1_000_000), null, ProbabilisticExecutionPolicy.Mode.EAGER_V1);
        var exact = optimized(profiles, budgets(2, 1_000_000));
        assertResultParity(eager, exact);
        assertTrue(exact.rawPaths().stream().allMatch(path -> Double.isFinite(path.energy())));
        assertEquals(accounting(exact).logical(), accounting(exact).kBest(),
                "ties and overflowed nonfinite streams are all examined, not cut by NaN/Infinity comparison");
    }

    @Test
    void denseEqualEnergyPlateausCannotBeCutOrReportedAsSavedWork() {
        var profiles = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> profile(index, new double[] {0, 0, 0}, ones(3))).toList();
        var eager = execute(profiles, DATA, budgets(32, 513), null, ProbabilisticExecutionPolicy.Mode.EAGER_V1);
        var exact = optimized(profiles, budgets(32, 513));
        assertResultParity(eager, exact);
        assertEquals(new Accounting("terminal-path-count-scored-work-v2", 162, 351, 351, 0), accounting(exact));
        var refused = optimized(profiles, budgets(32, 512));
        assertNoResult(refused); assertEquals(512L, refused.evaluatedTransitions());
        assertEquals(1L, accounting(refused).refused());
    }

    @Test
    void deterministicUnequalGraphsMatchIndependentPrefixesAndEveryEagerOutput() {
        var random = new java.util.Random(0x221012L);
        for (int fixture = 0; fixture < 16; fixture++) {
            var profiles = new ArrayList<InferenceProfile>();
            for (int index = 0; index < 4 + fixture % 3; index++) {
                int count = 1 + random.nextInt(4);
                double[] costs = new double[count], widths = new double[count];
                for (int state = 0; state < count; state++) {
                    costs[state] = (random.nextInt(11) - 5) * 0.125;
                    widths[state] = Math.scalb(1.0, random.nextInt(3) - 1);
                }
                profiles.add(profile(index, costs, widths));
            }
            for (int cap : new int[] {1, 2, 32}) {
                var eager = execute(profiles, DATA, budgets(cap, 1_000_000), null, ProbabilisticExecutionPolicy.Mode.EAGER_V1);
                var exact = optimized(profiles, budgets(cap, 1_000_000));
                assertPaths(reference(profiles, cap, false).paths(), eager.rawPaths());
                assertResultParity(eager, exact);
                assertEquals(accounting(eager).logical(), accounting(exact).logical());
                assertTrue(accounting(exact).kBest() <= accounting(exact).logical());
            }
        }
    }

    @Test
    void exactDescriptorSelectionRetainsAllGraphAdmissionsAndRejectsIllegalEdges() {
        var profiles = List.of(profile(0, new double[] {0}, ones(1)),
                profile(1, new double[] {0, -1}, ones(2)), profile(2, new double[] {0}, ones(1)));
        var decision = new MetricRegion(List.of(MetricRegion.rectangle(-1, -1, 3, 0.005).polygons().get(0),
                MetricRegion.rectangle(-1, 0.015, 3, 1).polygons().get(0)));
        var eager = execute(profiles, DATA, budgets(32, 1_000_000), decision, ProbabilisticExecutionPolicy.Mode.EAGER_V1);
        var exact = execute(profiles, DATA, budgets(32, 1_000_000), decision, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
        assertResultParity(eager, exact);
        assertEquals(1, exact.rawPaths().size());
        assertArrayEquals(new int[] {0, 0, 0}, exact.rawPaths().get(0).stateIndices());
    }

    @Test
    void checkedLogicalCounterRejectsOverflowBeforeChangingItsWitness() throws Exception {
        var type = Class.forName(ProbabilisticInference.class.getName() + "$WorkCounter");
        var constructor = type.getDeclaredConstructor(ProbabilisticExecutionPolicy.Mode.class, long.class);
        constructor.setAccessible(true);
        Object work = constructor.newInstance(ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2, Long.MAX_VALUE);
        var field = type.getDeclaredField("logicalKBestExtensions"); field.setAccessible(true); field.setLong(work, Long.MAX_VALUE);
        var add = type.getDeclaredMethod("recordLogicalKBestExtensions", long.class); add.setAccessible(true);
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> add.invoke(work, 1L));
        assertTrue(failure.getCause() instanceof ArithmeticException);
        assertEquals(Long.MAX_VALUE, field.getLong(work));
        assertThrows(ArithmeticException.class, () -> new InferenceWorkAccounting("test", Long.MAX_VALUE, 1, Long.MAX_VALUE, 0));
    }

    @Test
    void cancellationInsideCutoffHeapReplacementAndWinnerSortingPublishesNothingAndClosesScratch() {
        for (String phase : List.of("strictlyDominated", "replaceWorst", "sortWinners")) {
            var profiles = dominanceFixture(); int cap = 32, threshold = phase.equals("sortWinners") ? 514 : 1057;
            if (phase.equals("replaceWorst")) {
                profiles = new ArrayList<>(profiles);
                profiles.set(1, profile(1, java.util.stream.IntStream.range(0, 32)
                        .mapToDouble(j -> -j * 0.001).toArray(), ones(32)));
                cap = 2; threshold = 1;
            }
            var input = profiles; int rawCap = cap, cancelAt = threshold; int[] visits = {0};
            var ledger = AttemptMemoryLedger.production(); var owner = ledger.rootOwner();
            assertThrows(java.util.concurrent.CancellationException.class, () -> new ProbabilisticInference().solve(
                    input, DATA, budgets(rawCap, 1_000_000), null, () -> {
                        boolean inside = Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame ->
                                frame.getClassName().endsWith("$DescriptorHeap") && frame.getMethodName().equals(phase));
                        return inside && ++visits[0] == cancelAt;
                    }, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2));
            assertEquals(cancelAt, visits[0], phase); assertTrue(ledger.peakBytes() > 0);
            assertEquals(0L, ledger.currentBytes(), phase); owner.close();
        }
    }

    @Test
    void refusalDuringActualHeapArrayAdmissionLeavesNoPartialRouteAndReleasesOwnedScratch() {
        var ledger = AttemptMemoryLedger.production(); var owner = ledger.rootOwner();
        AttemptMemoryLedger.Reservation[] held = {null};
        var result = new ProbabilisticInference().solve(dominanceFixture(), DATA, budgets(32, 1_000_000), null, () -> {
            boolean constructing = Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame ->
                    frame.getClassName().endsWith("$DescriptorHeap") && frame.getMethodName().equals("<init>"));
            if (constructing && held[0] == null) held[0] = owner.reserve(ledger.limitBytes() - ledger.currentBytes() - 64L);
            return false;
        }, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
        assertNoResult(result); assertTrue(result.explanation().contains("k-best descriptors"));
        assertNotNull(held[0]); assertEquals(2080L, accounting(result).forwardBackward());
        assertEquals(0L, accounting(result).kBest()); held[0].close();
        assertTrue(ledger.currentBytes() < 65536L, "only bounded failure metadata remains, no heap/frontier/ancestry");
        owner.close(); assertEquals(0L, ledger.currentBytes());
    }

    private static ProbabilisticInferenceResult execute(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            TraceBudgets budgets, MetricRegion decision, ProbabilisticExecutionPolicy.Mode mode) {
        var ledger = AttemptMemoryLedger.production(); var owner = ledger.rootOwner();
        try { return new ProbabilisticInference().solve(profiles, parameters, budgets, decision, CancellationProbe.NONE, owner, mode); }
        finally { owner.close(); }
    }

    private static void assertResultParity(ProbabilisticInferenceResult eager, ProbabilisticInferenceResult exact) {
        assertEquals(eager.status(), exact.status()); assertEquals(eager.mapPath(), exact.mapPath());
        assertEquals(eager.rawPaths(), exact.rawPaths()); assertEquals(eager.distinctPaths(), exact.distinctPaths());
        assertBits(eager.logPartition(), exact.logPartition()); assertEquals(eager.completion(), exact.completion());
        assertEquals(eager.credibleSets(), exact.credibleSets()); assertEquals(eager.gapSummary(), exact.gapSummary());
        assertEquals(eager.posteriorUsable(), exact.posteriorUsable());
        assertEquals(eager.alternativeSearchTruncated(), exact.alternativeSearchTruncated());
        assertEquals(eager.evaluatedPairVisits(), exact.evaluatedPairVisits()); assertEquals(eager.explanation(), exact.explanation());
        for (var pair : List.of(List.of(eager.positionMarginals(), exact.positionMarginals()),
                List.of(eager.componentMarginals(), exact.componentMarginals()),
                List.of(eager.forwardPositionMarginals(), exact.forwardPositionMarginals()),
                List.of(eager.backwardPositionMarginals(), exact.backwardPositionMarginals()))) {
            assertEquals(pair.get(0).size(), pair.get(1).size());
            for (int index = 0; index < pair.get(0).size(); index++) {
                assertEquals(pair.get(0).get(index).length, pair.get(1).get(index).length);
                for (int state = 0; state < pair.get(0).get(index).length; state++)
                    assertBits(pair.get(0).get(index)[state], pair.get(1).get(index)[state]);
            }
        }
    }

    static List<InferenceProfile> dominanceFixture() {
        return List.of(profile(0, new double[] {0}, new double[] {1}),
                profile(1, java.util.stream.IntStream.range(0, 32).mapToDouble(j -> j * 0.001).toArray(), ones(32)),
                profile(2, java.util.stream.IntStream.range(0, 16).mapToDouble(b -> b * 10.0).toArray(), ones(16)),
                profile(3, new double[] {0}, new double[] {1}), profile(4, new double[] {0}, new double[] {1}));
    }

    static InferenceProfile profile(double chainage, double[] costs, double[] widths) {
        double[] offsets = java.util.stream.IntStream.range(0, costs.length).mapToDouble(i -> i * 0.02).toArray();
        String[] branches = new String[costs.length]; Arrays.fill(branches, "same");
        return V022ProbabilisticInferenceTest.profile(chainage, offsets, widths, costs, branches);
    }

    static double[] ones(int count) { double[] result = new double[count]; Arrays.fill(result, 1); return result; }

    static TraceBudgets budgets(int cap, long transitions) {
        return new TraceBudgets(96, 8_000_000, transitions, cap, Math.min(8, cap));
    }

    /** Explicit array exhaustion of each pair's retained prefixes, independently capped by sorting. */
    static Reference reference(List<InferenceProfile> profiles, int cap, boolean dominance) {
        return reference(profiles, DATA, cap, dominance);
    }

    private static Reference reference(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            int cap, boolean dominance) {
        var retained = new ArrayList<ReferencePath>();
        for (int first = 0; first < profiles.get(0).cells().size(); first++) {
            if (profiles.size() == 1) {
                retained.add(new ReferencePath(new int[] {first}, unary(profiles, parameters, 0, first), measure(profiles, 0, first)));
            } else for (int second = 0; second < profiles.get(1).cells().size(); second++) {
                retained.add(new ReferencePath(new int[] {first, second},
                        unary(profiles, parameters, 0, first) + unary(profiles, parameters, 1, second)
                            + pair(profiles, parameters, 0, first, second),
                        measure(profiles, 0, first) + measure(profiles, 1, second)));
            }
        }
        long logical = 0, scored = 0;
        for (int layer = 2; layer < profiles.size(); layer++) {
            var next = new ArrayList<ReferencePath>();
            for (int prior = 0; prior < profiles.get(layer - 1).cells().size(); prior++) {
                for (int state = 0; state < profiles.get(layer).cells().size(); state++) {
                    var candidates = new ArrayList<ReferencePath>();
                    for (int before = 0; before < profiles.get(layer - 2).cells().size(); before++) {
                        var stream = new ArrayList<ReferencePath>();
                        for (var prefix : retained) if (prefix.states()[layer - 2] == before
                                && prefix.states()[layer - 1] == prior) stream.add(prefix);
                        stream.sort(ORDER);
                        logical = Math.addExact(logical, stream.size());
                        double increment = unary(profiles, parameters, layer, state)
                                + pair(profiles, parameters, layer - 1, prior, state)
                                + turn(profiles, parameters, layer, before, prior, state);
                        for (var prefix : stream) {
                            double energy = prefix.energy() + increment; scored++;
                            if (dominance && candidates.size() >= cap && Double.isFinite(increment)
                                    && Double.isFinite(energy) && Double.isFinite(candidates.get(cap - 1).energy())
                                    && energy > candidates.get(cap - 1).energy()) break;
                            int[] states = Arrays.copyOf(prefix.states(), layer + 1); states[layer] = state;
                            candidates.add(new ReferencePath(states, energy, prefix.logMeasure() + measure(profiles, layer, state)));
                            candidates.sort(ORDER);
                            if (dominance && candidates.size() > cap) candidates.remove(cap);
                        }
                    }
                    candidates.sort(ORDER);
                    next.addAll(candidates.subList(0, Math.min(cap, candidates.size())));
                }
            }
            retained = next;
        }
        retained.sort(ORDER);
        return new Reference(List.copyOf(retained.subList(0, Math.min(cap, retained.size()))), logical, scored);
    }

    private static double unary(List<InferenceProfile> profiles, EvidenceModelParameters parameters, int index, int state) {
        double weight = profiles.size() == 1 ? 1.0 : index == 0
                ? 0.5 * (profiles.get(1).chainageMeters() - profiles.get(0).chainageMeters())
                : index == profiles.size() - 1
                    ? 0.5 * (profiles.get(index).chainageMeters() - profiles.get(index - 1).chainageMeters())
                    : 0.5 * (profiles.get(index + 1).chainageMeters() - profiles.get(index - 1).chainageMeters());
        return weight * (parameters.dataWeight() * profiles.get(index).unaryCost(state)
                + parameters.guideWeight() * profiles.get(index).structuralGuideCost(state));
    }

    private static double pair(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            int index, int first, int second) {
        if (parameters.orientationWeight() == 0.0) return 0.0;
        double heading = heading(point(profiles.get(index), first), point(profiles.get(index + 1), second));
        assertTrue(heading > 0 && heading < Math.PI / 2, "analytic reference uses the unique zero-bearing mode");
        double sine = StrictMath.sin(heading);
        double start = profiles.get(index).orientationSupport(first).certainty()
                * profiles.get(index).orientationReliability(first) * (sine * sine);
        double end = profiles.get(index + 1).orientationSupport(second).certainty()
                * profiles.get(index + 1).orientationReliability(second) * (sine * sine);
        return parameters.orientationWeight() * (profiles.get(index + 1).chainageMeters()
                - profiles.get(index).chainageMeters()) * 0.5 * (start + end);
    }

    private static double turn(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            int index, int before, int prior, int state) {
        if (parameters.turnWeight() == 0.0) return 0.0;
        double delta = heading(point(profiles.get(index - 1), prior), point(profiles.get(index), state))
                - heading(point(profiles.get(index - 2), before), point(profiles.get(index - 1), prior));
        double normalized = StrictMath.atan2(StrictMath.sin(delta), StrictMath.cos(delta)) / parameters.turnScaleRadians();
        double absolute = Math.abs(normalized);
        double loss = absolute <= 1 ? 0.5 * normalized * normalized : absolute - 0.5;
        return parameters.turnWeight() * loss / Math.max(1e-6, 0.5 * (profiles.get(index).chainageMeters()
                - profiles.get(index - 2).chainageMeters()));
    }

    private static MetricPoint point(InferenceProfile profile, int state) {
        double offset = profile.cells().get(state).offsetMeters();
        return new MetricPoint(profile.anchor().xMeters() + profile.normalUnit().xMeters() * offset,
                profile.anchor().yMeters() + profile.normalUnit().yMeters() * offset);
    }

    private static double heading(MetricPoint a, MetricPoint b) {
        return StrictMath.atan2(b.yMeters() - a.yMeters(), b.xMeters() - a.xMeters());
    }

    private static double measure(List<InferenceProfile> profiles, int index, int state) {
        var cell = profiles.get(index).cells().get(state);
        return cell.exactAnchor() ? 0.0 : StrictMath.log(cell.quadratureWidthMeters());
    }

    static long forwardBackwardCount(List<InferenceProfile> profiles) {
        long total = 0;
        for (int index = 2; index < profiles.size(); index++) total += 2L
                * profiles.get(index - 2).cells().size() * profiles.get(index - 1).cells().size()
                * profiles.get(index).cells().size();
        return total;
    }

    static void assertPaths(List<ReferencePath> expected, List<ProbabilisticPath> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index).states(), actual.get(index).stateIndices());
            assertBits(expected.get(index).energy(), actual.get(index).energy());
            assertBits(expected.get(index).logMeasure(), actual.get(index).logBaseMeasure());
        }
    }

    private static void assertReferencePaths(List<ReferencePath> expected, List<ReferencePath> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i).states(), actual.get(i).states());
            assertBits(expected.get(i).energy(), actual.get(i).energy());
            assertBits(expected.get(i).logMeasure(), actual.get(i).logMeasure());
        }
    }

    static void assertBits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    private static void assertPosterior(List<ReferencePath> paths, List<InferenceProfile> profiles,
            ProbabilisticInferenceResult actual) {
        double maximum = paths.stream().mapToDouble(p -> -p.energy() + p.logMeasure()).max().orElseThrow();
        double sum = paths.stream().mapToDouble(p -> StrictMath.exp(-p.energy() + p.logMeasure() - maximum)).sum();
        double partition = maximum + StrictMath.log(sum);
        assertEquals(partition, actual.logPartition(), 1e-12);
        for (int layer = 0; layer < profiles.size(); layer++) {
            double[] marginal = new double[profiles.get(layer).cells().size()];
            for (var path : paths) marginal[path.states()[layer]] += StrictMath.exp(-path.energy() + path.logMeasure() - partition);
            assertArrayEquals(marginal, actual.positionMarginals().get(layer), 1e-12);
        }
    }

    private static final Comparator<ReferencePath> ORDER = (a, b) -> {
        int energy = Double.compare(a.energy(), b.energy()); if (energy != 0) return energy;
        for (int index = 0; index < a.states().length; index++) {
            int state = Integer.compare(a.states()[index], b.states()[index]); if (state != 0) return state;
        }
        return 0;
    };

    record ReferencePath(int[] states, double energy, double logMeasure) { }
    record Reference(List<ReferencePath> paths, long logicalExtensions, long scoredExtensions) { }
}

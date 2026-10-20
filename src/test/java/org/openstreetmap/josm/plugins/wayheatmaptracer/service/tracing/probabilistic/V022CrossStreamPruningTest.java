package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Frozen tiny graphs only; output equivalence excludes factual policy/work/elapsed metadata. */
class V022CrossStreamPruningTest {
    private static final EvidenceModelParameters TURN = new EvidenceModelParameters(
            "cross-stream-strict-witness", 1, 0, 1, 0, 0, 1, 1, 0);

    @Test
    void strictWitnessAvoidsActualTripleWorkNotOnlyScoreUnits() {
        // Frozen before V3 evaluation: eight routes, 50-unit initial energy separation;
        // unequal offsets keep the actual turn geometry nontrivial.
        var profiles = List.of(profile(0, new double[] {0, 100}),
                profile(1, new double[] {0, 0}), profile(2, new double[] {0, 0}));
        var ledger = new AttemptMemoryLedger(1_000_000);
        try (var owner = ledger.rootOwner()) {
            var baseline = new ProbabilisticInference().solve(profiles, TURN, budgets(1), null,
                    CancellationProbe.NONE, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            var value = new ProbabilisticInference().solveCrossStreamRaw1(profiles, TURN, budgets(1), null,
                    CancellationProbe.NONE, owner, null);
            assertEquals(baseline.rawPaths(), value.result().rawPaths());
            assertEquals(baseline.completion(), value.result().completion());
            var old = baseline.workAccounting().orElseThrow();
            var now = value.result().workAccounting().orElseThrow();
            assertEquals(old.forwardBackwardScores(), now.forwardBackwardScores());
            assertEquals(old.logicalKBestExtensions(), now.logicalKBestExtensions());
            assertEquals(4, value.boundWork().skippedStreams(), "strict pre-triple omission, not score renaming");
            assertEquals(4, value.boundWork().tripleEvaluations());
            assertEquals(4, value.boundWork().firstHeadingEvaluations());
            assertEquals(4, value.boundWork().secondHeadingEvaluations());
            assertEquals(8, old.kBestScores());
            assertEquals(4, now.kBestScores());
            assertEquals(4, value.boundWork().wrapEvaluations());
            assertEquals(4, value.boundWork().huberEvaluations());
            assertEquals(8, value.boundWork().boundQueries());
            assertEquals(1, value.boundWork().stageCertificates());
            assertEquals(648, value.boundWork().boundUnits(), "8 setup +128 stage +8*64 queries");
            assertEquals(4, value.boundWork().boundFallbacks(), "the four initial non-full heap queries");
            assertEquals(0, value.boundWork().certificateFallbacks());
            assertEquals(21, value.boundWork().finiteTests(), "nine linear-stage checks plus four*three query checks");
            assertEquals(22, value.boundWork().magnitudeTests(), "ten linear-stage checks plus four*three query guards");
            assertEquals(20, value.boundWork().arithmeticOps(), "one setup +seven linear-stage +four*(abs,abs,add)");
            assertEquals(5, value.boundWork().outwardSteps(), "one setup plus four linear-stage enclosures");
            assertEquals(4, value.boundWork().partialAdditions());
            assertEquals(4, value.boundWork().strictComparisons());
            assertEquals(13, value.boundWork().boundCheckpoints(), "one stage +eight query +four accepted-skip polls");
            assertEquals(9, value.boundWork().ceilingChecks());
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void rawOneMatchesIndependentRetainedPrefixesAndExhaustivePosterior() {
        var parameters = new EvidenceModelParameters("independent-cross-stream", 0.7, 1, 1.3, 0.4, 0.6, 0.3, 1.7, 0.1);
        var profiles = new ArrayList<InferenceProfile>();
        double[] chainages = {0, 2, 5, 9, 14};
        int[] sizes = {1, 3, 2, 3, 1};
        for (int row = 0; row < sizes.length; row++) {
            var cells = new ArrayList<LateralStateCell>();
            double[] unary = new double[sizes[row]], guide = new double[sizes[row]];
            for (int state = 0; state < sizes[row]; state++) {
                cells.add(new LateralStateCell(state * 0.02, 0.5 + state * 0.5,
                        row == 0 || row == sizes.length - 1, true, "same"));
                unary[state] = (state - 1) * 0.13 + row * 0.01;
                guide[state] = state * 0.07;
            }
            profiles.add(new InferenceProfile(chainages[row], new MetricPoint(chainages[row], row + 0.15 * row * row),
                    new MetricPoint(0, 1), cells, unary, List.of(0.0), 0.8,
                    ObservationOwnership.DIRECT_TWO_SIDED, false).withStructuralGuideCosts(guide));
        }
        var all = reference(profiles, parameters, Integer.MAX_VALUE);
        assertEquals(18, all.size());
        for (int cap : new int[] {1, 2, 32}) {
            var value = execute(profiles, parameters, cap);
            assertPaths(reference(profiles, parameters, cap), value.result().rawPaths());
            assertPosterior(all, profiles, parameters.temperature(), value.result());
            assertParity(baseline(profiles, parameters, cap), value.result());
            if (cap > 1) assertEquals(0, value.boundWork().boundQueries());
            else {
                assertEquals(2, value.boundWork().stageCertificates(), "stage spans differ; the first stage never has a full heap");
                assertEquals(30, value.boundWork().boundQueries(), "1*3*2 +3*2*3 +2*3*1 nonempty streams");
                assertEquals(8 + 3*128 + 30*64, value.boundWork().boundUnits());
            }
        }
    }

    @Test
    void singleBeforeStateNeverBuildsALazyCertificate() {
        var graph = List.of(flat(0, new double[] {0}), flat(1, new double[] {0, 1}), flat(2, new double[] {0, 1}));
        var value = execute(graph, TURN, 1);
        assertParity(baseline(graph, TURN, 1), value.result());
        assertEquals(4, value.boundWork().boundQueries());
        assertEquals(4, value.boundWork().boundFallbacks());
        assertEquals(0, value.boundWork().stageCertificates(), "the first and only before-stream cannot see a full heap");
        assertEquals(0, value.boundWork().finiteTests(), "no finite tests occur without a certificate/full heap");
        assertEquals(8 + 128 + 4*64, value.boundWork().boundUnits());
    }

    @Test
    void roundedCollapseUsesEachPairsRetainedPrefixesRatherThanGlobalFinalSort() {
        var parameters = new EvidenceModelParameters("rounded-retention", 1, 0, Double.MIN_VALUE, 0, 0, 1, 1, 0);
        var profiles = List.of(flat(0, new double[] {0.5, 0}), flat(1, new double[] {0}),
                flat(2, new double[] {0}), flat(3, new double[] {2e16}));
        var retained = reference(profiles, parameters, 1);
        var exhaustive = reference(profiles, parameters, Integer.MAX_VALUE);
        assertArrayEquals(new int[] {1, 0, 0, 0}, retained.get(0).states());
        assertArrayEquals(new int[] {0, 0, 0, 0}, exhaustive.get(0).states());
        var actual = execute(profiles, parameters, 1);
        assertPaths(retained, actual.result().rawPaths());
        assertParity(baseline(profiles, parameters, 1), actual.result());
    }

    @Test
    void tiesSignedZeroSubnormalAndLargeRoundedIncrementsDoNotCreateFalseSkips() {
        var fixtures = List.of(
                List.of(flat(0, new double[] {-0.0, 0.0}), flat(1, new double[] {0, -0.0}), flat(2, new double[] {0, 0})),
                List.of(flat(0, new double[] {Double.MIN_VALUE, 0}), flat(1, new double[] {-Double.MIN_VALUE, 0}),
                        flat(2, new double[] {0, Double.MIN_VALUE})),
                List.of(flat(0, new double[] {0, 0}), flat(1, new double[] {0, 0}), flat(2, new double[] {2e16, 2e16})));
        for (var profiles : fixtures) {
            var value = execute(profiles, TURN, 1);
            assertParity(baseline(profiles, TURN, 1), value.result());
            assertPaths(reference(profiles, TURN, 1), value.result().rawPaths());
            assertEquals(0, value.boundWork().skippedStreams(), "equal partial bounds cannot suppress lexical ties");
            assertEquals(8, value.boundWork().tripleEvaluations());
        }
        double huge = Math.scalb(Double.MAX_VALUE, -2);
        var extreme = List.of(flat(0, new double[] {huge, -huge}), flat(1, new double[] {huge, 0}),
                flat(2, new double[] {huge, -huge}));
        assertParity(baseline(extreme, TURN, 1), execute(extreme, TURN, 1).result());
    }

    @Test
    void singleTwoAndZeroTurnGraphsKeepActualV2OutputsWithoutBoundQueries() {
        var profiles = List.of(profile(0, new double[] {0, 100}), profile(1, new double[] {-0.1, 0.2}));
        for (int rows : new int[] {1, 2}) for (int cap : new int[] {1, 2, 32}) {
            var graph = profiles.subList(0, rows);
            var value = execute(graph, TURN, cap);
            assertParity(baseline(graph, TURN, cap), value.result());
            assertEquals(0, value.boundWork().boundQueries());
            assertEquals(0, value.boundWork().stageCertificates());
        }
        var zeroTurn = new EvidenceModelParameters("zero-turn", 1, 1, 0, 0, 0, 1, 1, 0);
        var graph = List.of(profiles.get(0), profiles.get(1), profile(2, new double[] {0.1, -0.1}));
        var value = execute(graph, zeroTurn, 1);
        assertParity(baseline(graph, zeroTurn, 1), value.result());
        assertEquals(0, value.boundWork().boundUnits());
        assertEquals(ProbabilisticExecutionPolicy.CURRENT_VERSION, value.result().workAccounting().orElseThrow().executionPolicy());
    }

    @Test
    void fundedEntryRetainsActualSoleRouteRoundoffAndSignedZeroEndpoints() throws Exception {
        // Reuse the genuine tiny historical NUM01 fixture, never copy its scoring implementation.
        var fixture = V022SingleRoutePosteriorRoundoffTest.class.getDeclaredMethod("originalProfiles");
        fixture.setAccessible(true);
        @SuppressWarnings("unchecked") var profiles = (List<InferenceProfile>) fixture.invoke(null);
        var parameters = new EvidenceModelParameters("p6-independent", 0, 0.5, 0, 0, 0.75, 0.5, 1, 0.01);
        var ledger = new AttemptMemoryLedger(1_000_000);
        try (var owner = ledger.rootOwner()) {
            var value = new ProbabilisticInference().solveCrossStreamRaw1(profiles, parameters, budgets(1),
                    MetricRegion.rectangle(-1, 0.5, 5, 2), CancellationProbe.NONE, owner, null);
            assertArrayEquals(new int[] {2, 2, 2, 2}, value.result().rawPaths().get(0).stateIndices());
            assertEquals(0x4000333333333334L, Double.doubleToRawLongBits(value.result().rawPaths().get(0).energy()));
            assertEquals(1.0, value.result().rawPaths().get(0).conditionalPosteriorMass());
        }
        assertEquals(0, ledger.currentBytes());
        var graph = List.of(new InferenceProfile(0, new MetricPoint(-0.0, -0.0), new MetricPoint(0, 1),
                        List.of(new LateralStateCell(0, 1, true, true, "anchor")), new double[] {0}, List.of(), 0,
                        ObservationOwnership.FIXED_TOPOLOGY_ONLY, false),
                new InferenceProfile(2, new MetricPoint(2, -0.0), new MetricPoint(0, 1),
                        List.of(new LateralStateCell(-0.0, 1, true, true, "anchor")), new double[] {0}, List.of(), 0,
                        ObservationOwnership.FIXED_TOPOLOGY_ONLY, false));
        var actual = execute(graph, TURN, 1).result();
        assertParity(baseline(graph, TURN, 1), actual);
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(actual.rawPaths().get(0).points().get(0).xMeters()));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(actual.rawPaths().get(0).points().get(1).yMeters()));
    }

    @Test
    void fundedPotentialUsesActualInitialAndInteriorTermsWithoutLosingPosterior() {
        var graph = List.of(flat(0, new double[] {0, 0}), flat(1, new double[] {0, 0}), flat(2, new double[] {0, 0}));
        AdjacentStatePotential potential = (stage, left, right) -> left == 1 && right == 1 ? 0 : 10;
        var ledger = new AttemptMemoryLedger(1_000_000);
        try (var owner = ledger.rootOwner()) {
            var old = new ProbabilisticInference().solve(graph, TURN, budgets(1), null, CancellationProbe.NONE, owner,
                    ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2, potential);
            var value = new ProbabilisticInference().solveCrossStreamRaw1(graph, TURN, budgets(1), null,
                    CancellationProbe.NONE, owner, potential);
            assertParity(old, value.result());
            assertArrayEquals(new int[] {1, 1, 1}, value.result().rawPaths().get(0).stateIndices());
            assertEquals(0.0, value.result().rawPaths().get(0).energy());
            // 111 has zero cost; 011/110 have one positive edge; the other five have two.
            assertEquals(StrictMath.log(1 + 2 * StrictMath.exp(-10) + 5 * StrictMath.exp(-20)),
                    value.result().logPartition(), 1e-12);
        }
        assertEquals(0, ledger.currentBytes());
    }

    private static CrossStreamInferenceResult execute(List<InferenceProfile> graph, EvidenceModelParameters parameters, int cap) {
        var ledger = new AttemptMemoryLedger(1_000_000);
        try (var owner = ledger.rootOwner()) {
            return new ProbabilisticInference().solveCrossStreamRaw1(graph, parameters, budgets(cap), null,
                    CancellationProbe.NONE, owner, null);
        } finally { assertEquals(0, ledger.currentBytes()); }
    }

    private static ProbabilisticInferenceResult baseline(List<InferenceProfile> graph, EvidenceModelParameters parameters, int cap) {
        var ledger = new AttemptMemoryLedger(1_000_000);
        try (var owner = ledger.rootOwner()) {
            return new ProbabilisticInference().solve(graph, parameters, budgets(cap), null,
                    CancellationProbe.NONE, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
        } finally { assertEquals(0, ledger.currentBytes()); }
    }

    private static void assertParity(ProbabilisticInferenceResult a, ProbabilisticInferenceResult b) {
        assertEquals(a.status(), b.status()); assertEquals(a.mapPath(), b.mapPath());
        assertEquals(a.rawPaths(), b.rawPaths()); assertEquals(a.distinctPaths(), b.distinctPaths());
        bits(a.logPartition(), b.logPartition()); assertEquals(a.completion(), b.completion());
        assertEquals(a.credibleSets(), b.credibleSets()); assertEquals(a.gapSummary(), b.gapSummary());
        assertEquals(a.posteriorUsable(), b.posteriorUsable()); assertEquals(a.explanation(), b.explanation());
        assertEquals(a.alternativeSearchTruncated(), b.alternativeSearchTruncated());
        assertEquals(a.evaluatedPairVisits(), b.evaluatedPairVisits());
        var aw = a.workAccounting().orElseThrow(); var bw = b.workAccounting().orElseThrow();
        assertEquals(aw.forwardBackwardScores(), bw.forwardBackwardScores());
        assertEquals(aw.logicalKBestExtensions(), bw.logicalKBestExtensions());
        assertEquals(aw.refusedScoreAttempts(), bw.refusedScoreAttempts());
        for (var pair : List.of(List.of(a.positionMarginals(), b.positionMarginals()),
                List.of(a.componentMarginals(), b.componentMarginals()),
                List.of(a.forwardPositionMarginals(), b.forwardPositionMarginals()),
                List.of(a.backwardPositionMarginals(), b.backwardPositionMarginals()))) {
            assertEquals(pair.get(0).size(), pair.get(1).size());
            for (int row = 0; row < pair.get(0).size(); row++) {
                assertEquals(pair.get(0).get(row).length, pair.get(1).get(row).length);
                for (int state = 0; state < pair.get(0).get(row).length; state++) bits(pair.get(0).get(row)[state], pair.get(1).get(row)[state]);
            }
        }
    }

    private record Path(int[] states, double energy, double measure) { }
    private static final Comparator<Path> ORDER = (a, b) -> {
        int order = Double.compare(a.energy(), b.energy());
        if (order != 0) return order;
        return Arrays.compare(a.states(), b.states());
    };

    /** Exhaust each target pair, then retain its cap; no production cost/heap/order calls. */
    private static List<Path> reference(List<InferenceProfile> graph, EvidenceModelParameters parameters, int cap) {
        var retained = new ArrayList<Path>();
        for (int a = 0; a < graph.get(0).cells().size(); a++) for (int b = 0; b < graph.get(1).cells().size(); b++)
            retained.add(new Path(new int[] {a, b}, unary(graph, parameters, 0, a) + unary(graph, parameters, 1, b)
                    + pair(graph, parameters, 0, a, b), measure(graph.get(0), a) + measure(graph.get(1), b)));
        for (int row = 2; row < graph.size(); row++) {
            var next = new ArrayList<Path>();
            for (int prior = 0; prior < graph.get(row - 1).cells().size(); prior++)
                for (int state = 0; state < graph.get(row).cells().size(); state++) {
                    var candidates = new ArrayList<Path>();
                    for (var prefix : retained) if (prefix.states()[row - 1] == prior) {
                        int before = prefix.states()[row - 2];
                        double increment = unary(graph, parameters, row, state) + pair(graph, parameters, row - 1, prior, state)
                                + triple(graph, parameters, row, before, prior, state);
                        int[] indices = Arrays.copyOf(prefix.states(), row + 1); indices[row] = state;
                        candidates.add(new Path(indices, prefix.energy() + increment, prefix.measure() + measure(graph.get(row), state)));
                    }
                    candidates.sort(ORDER);
                    next.addAll(candidates.subList(0, Math.min(cap, candidates.size())));
                }
            retained = next;
        }
        retained.sort(ORDER);
        return List.copyOf(retained.subList(0, Math.min(cap, retained.size())));
    }

    private static double unary(List<InferenceProfile> graph, EvidenceModelParameters p, int row, int state) {
        double span = row == 0 ? 0.5 * (graph.get(1).chainageMeters() - graph.get(0).chainageMeters())
                : row == graph.size() - 1 ? 0.5 * (graph.get(row).chainageMeters() - graph.get(row - 1).chainageMeters())
                : 0.5 * (graph.get(row + 1).chainageMeters() - graph.get(row - 1).chainageMeters());
        return span * (p.dataWeight() * graph.get(row).unaryCost(state) + p.guideWeight() * graph.get(row).structuralGuideCost(state));
    }

    private static double pair(List<InferenceProfile> graph, EvidenceModelParameters p, int row, int a, int b) {
        if (p.orientationWeight() == 0) return 0;
        double heading = heading(point(graph.get(row), a), point(graph.get(row + 1), b));
        assertTrue(heading > 0 && heading < Math.PI / 2, "independent fixture has the unique zero-bearing mode");
        double sine = StrictMath.sin(heading);
        double left = 0.8 * (sine * sine), right = 0.8 * (sine * sine);
        return p.orientationWeight() * (graph.get(row + 1).chainageMeters() - graph.get(row).chainageMeters()) * 0.5 * (left + right);
    }

    private static double triple(List<InferenceProfile> graph, EvidenceModelParameters p, int row, int a, int b, int c) {
        double change = heading(point(graph.get(row - 1), b), point(graph.get(row), c))
                - heading(point(graph.get(row - 2), a), point(graph.get(row - 1), b));
        double z = StrictMath.atan2(StrictMath.sin(change), StrictMath.cos(change)) / p.turnScaleRadians();
        double magnitude = Math.abs(z);
        double huber = magnitude <= 1 ? 0.5 * z * z : magnitude - 0.5;
        return p.turnWeight() * huber / Math.max(1e-6, 0.5 * (graph.get(row).chainageMeters() - graph.get(row - 2).chainageMeters()));
    }

    private static MetricPoint point(InferenceProfile profile, int state) {
        double offset = profile.cells().get(state).offsetMeters();
        return offset == 0 ? new MetricPoint(profile.anchor().xMeters(), profile.anchor().yMeters())
                : new MetricPoint(profile.anchor().xMeters() + profile.normalUnit().xMeters() * offset,
                        profile.anchor().yMeters() + profile.normalUnit().yMeters() * offset);
    }
    private static double heading(MetricPoint a, MetricPoint b) { return StrictMath.atan2(b.yMeters() - a.yMeters(), b.xMeters() - a.xMeters()); }
    private static double measure(InferenceProfile profile, int state) {
        var cell = profile.cells().get(state); return cell.exactAnchor() ? 0 : StrictMath.log(cell.quadratureWidthMeters());
    }
    private static void bits(double a, double b) { assertEquals(Double.doubleToRawLongBits(a), Double.doubleToRawLongBits(b)); }
    private static void assertPaths(List<Path> expected, List<ProbabilisticPath> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i).states(), actual.get(i).stateIndices());
            bits(expected.get(i).energy(), actual.get(i).energy()); bits(expected.get(i).measure(), actual.get(i).logBaseMeasure());
        }
    }
    private static void assertPosterior(List<Path> all, List<InferenceProfile> graph, double temperature, ProbabilisticInferenceResult actual) {
        double maximum = all.stream().mapToDouble(p -> -p.energy() / temperature + p.measure()).max().orElseThrow();
        double sum = all.stream().mapToDouble(p -> StrictMath.exp(-p.energy() / temperature + p.measure() - maximum)).sum();
        double partition = maximum + StrictMath.log(sum);
        assertEquals(partition, actual.logPartition(), 1e-12);
        for (int row = 0; row < graph.size(); row++) {
            double[] mass = new double[graph.get(row).cells().size()];
            for (var path : all) mass[path.states()[row]] += StrictMath.exp(-path.energy() / temperature + path.measure() - partition);
            assertArrayEquals(mass, actual.positionMarginals().get(row), 1e-12);
        }
    }

    private static InferenceProfile flat(double chainage, double[] costs) {
        var cells = new ArrayList<LateralStateCell>();
        for (int state = 0; state < costs.length; state++) cells.add(new LateralStateCell(0, 1, false, true, "same"));
        return new InferenceProfile(chainage, new MetricPoint(chainage, 0), new MetricPoint(0, 1), cells, costs,
                List.of(), 0, ObservationOwnership.DIRECT_TWO_SIDED, false);
    }

    private static InferenceProfile profile(double chainage, double[] costs) {
        return V022ProbabilisticInferenceTest.profile(chainage, new double[] {0, 0.1},
                new double[] {1, 1}, costs, new String[] {"same", "same"});
    }

    private static TraceBudgets budgets(int cap) {
        return new TraceBudgets(96, 8_000_000, 128_000_000, cap, Math.min(8, cap));
    }
}

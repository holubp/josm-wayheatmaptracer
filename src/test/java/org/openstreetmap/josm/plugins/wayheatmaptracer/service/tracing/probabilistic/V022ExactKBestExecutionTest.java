package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;

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
        var retained = new ArrayList<ReferencePath>();
        for (int first = 0; first < profiles.get(0).cells().size(); first++) {
            if (profiles.size() == 1) {
                retained.add(new ReferencePath(new int[] {first}, unary(profiles, 0, first), measure(profiles, 0, first)));
            } else for (int second = 0; second < profiles.get(1).cells().size(); second++) {
                retained.add(new ReferencePath(new int[] {first, second},
                        unary(profiles, 0, first) + unary(profiles, 1, second) + 0.0,
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
                        double increment = unary(profiles, layer, state) + 0.0 + 0.0;
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

    private static double unary(List<InferenceProfile> profiles, int index, int state) {
        double weight = profiles.size() == 1 ? 1.0 : index == 0
                ? 0.5 * (profiles.get(1).chainageMeters() - profiles.get(0).chainageMeters())
                : index == profiles.size() - 1
                    ? 0.5 * (profiles.get(index).chainageMeters() - profiles.get(index - 1).chainageMeters())
                    : 0.5 * (profiles.get(index + 1).chainageMeters() - profiles.get(index - 1).chainageMeters());
        return weight * (profiles.get(index).unaryCost(state) + 0.0);
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

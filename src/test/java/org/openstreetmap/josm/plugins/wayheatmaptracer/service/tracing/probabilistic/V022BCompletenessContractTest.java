package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;

/** Small admitted graphs with counts and work known without solver internals. */
class V022BCompletenessContractTest {
    @Test
    void requestedLimitsCannotRaiseHardKOrDAndTerminalCountSaturates() {
        double[] offsets = new double[40];
        String[] labels = new String[40];
        for (int state = 0; state < 40; state++) {
            offsets[state] = state * 0.1;
            labels[state] = "one physical branch";
        }
        var result = solve(List.of(profile(0, offsets, new double[40], labels)),
            new TraceBudgets(96, 8_000_000, 128, 64, 16));

        assertEquals(32, result.rawPaths().size());
        assertEquals(1, result.distinctPaths().size());
        assertEquals(32, result.completion().orElseThrow().effectiveRawLimit());
        assertEquals(8, result.completion().orElseThrow().effectiveDistinctLimit());
        assertEquals(33, result.completion().orElseThrow().completePathsAtSaturation());
        assertTrue(result.completion().orElseThrow().rawEnumerationCapped());
        assertTrue(result.alternativeSearchTruncated());
    }

    @Test
    void kthEnergyBeyondExistingAmbiguityDeltaExcludesUnseenCloseRival() {
        var result = solve(List.of(profile(0, new double[] {0, 3, 6},
            new double[] {0, 10, 20}, new String[] {"a", "b", "c"})), budget(2, 1, 128));

        assertEquals(ProbabilisticInferenceResult.Status.COMPLETE, result.status());
        assertEquals(3, result.completion().orElseThrow().completePathsAtSaturation());
        assertTrue(result.completion().orElseThrow().rawEnumerationCapped());
        assertTrue(result.completion().orElseThrow().requestedDiversityReached());
        assertFalse(result.alternativeSearchTruncated());
    }

    @Test
    void statePairForwardBackwardAndKBestLimitsRetainTheirOwnWorkUnits() {
        List<InferenceProfile> graph = binaryProfiles(4);
        var state = solve(graph, new TraceBudgets(1, 8_000_000, 128, 32, 8));
        var pair = solve(graph, new TraceBudgets(96, 3, 128, 32, 8));
        var forwardBackward = solve(graph, budget(32, 8, 7));
        var kBest = solve(graph, budget(32, 8, 32));

        assertAll("independent resource-stop units",
            () -> {
                assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, state.status());
                assertEquals(0, state.evaluatedPairVisits());
                assertEquals(0, state.evaluatedTransitions());
                assertNoUsableResult(state);
            },
            () -> {
                assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, pair.status());
                assertEquals(4, pair.evaluatedPairVisits());
                assertEquals(0, pair.evaluatedTransitions());
                assertNoUsableResult(pair);
            },
            () -> {
                assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, forwardBackward.status());
                assertEquals(8, forwardBackward.evaluatedPairVisits());
                assertEquals(8, forwardBackward.evaluatedTransitions());
                assertNoUsableResult(forwardBackward);
            },
            () -> {
                assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, kBest.status());
                assertEquals(12, kBest.evaluatedPairVisits());
                assertEquals(33, kBest.evaluatedTransitions());
                assertNoUsableResult(kBest);
            });
    }

    @Test
    void firstEightEquivalentRoutesDoNotHideTheNinthDistinctRouteUnderOrdinaryLimits() {
        double[] offsets = new double[9];
        double[] costs = new double[9];
        String[] labels = new String[9];
        for (int state = 0; state < 8; state++) {
            offsets[state] = state * 0.1;
            labels[state] = "a";
        }
        offsets[8] = 4.0;
        costs[8] = 1.0;
        labels[8] = "b";
        List<InferenceProfile> graph = List.of(profile(0, offsets, costs, labels),
            profile(30, new double[] {4}, new double[] {0}, new String[] {"b"}));

        var ordinary = solve(graph, TraceBudgets.defaults());
        var experimental = solve(graph, TraceBudgets.experimentalProbabilisticPreview8x4());

        assertEquals(32, TraceBudgets.defaults().maximumRawAlternatives());
        assertEquals(8, TraceBudgets.defaults().maximumDistinctAlternatives());
        assertEquals(TraceBudgets.experimentalProbabilisticPreview8x4(),
            TraceBudgets.interactiveProbabilisticPreview(),
            "the old API remains an explicit alias rather than an ordinary default");
        assertEquals(8, TraceBudgets.experimentalProbabilisticPreview8x4().maximumRawAlternatives());
        assertEquals(4, TraceBudgets.experimentalProbabilisticPreview8x4().maximumDistinctAlternatives());
        assertEquals(8, experimental.rawPaths().size());
        assertEquals(1, experimental.distinctPaths().size());
        assertTrue(experimental.alternativeSearchTruncated());
        assertEquals(9, ordinary.rawPaths().size());
        assertEquals(2, ordinary.distinctPaths().size());
        assertFalse(ordinary.alternativeSearchTruncated());
        assertEquals(8, ordinary.rawPaths().get(8).stateIndices()[0]);
        assertEquals(9, ordinary.completion().orElseThrow().completePathsAtSaturation());
        assertFalse(ordinary.completion().orElseThrow().rawEnumerationCapped());
        assertEquals(9, experimental.completion().orElseThrow().completePathsAtSaturation());
        assertTrue(experimental.completion().orElseThrow().rawEnumerationCapped());
    }

    @Test
    void saturatedDeadEndDoesNotInflateTerminalCount() {
        double[] offsets = {0, 0.1, 0.2, 0.3, 5};
        String[] labels = {"a", "a", "a", "a", "b"};
        List<InferenceProfile> graph = List.of(profile(0, offsets, new double[5], labels),
            profile(10, new double[] {0, 5}, new double[2], new String[] {"a", "b"}),
            profile(20, new double[] {0, 5}, new double[2], new String[] {"a", "b"}),
            profile(40, new double[] {5}, new double[1], new String[] {"b"}));
        MetricRegion region = new MetricRegion(List.of(
            List.of(new MetricPoint(-1, -1), new MetricPoint(30.5, -1),
                new MetricPoint(30.5, 1), new MetricPoint(-1, 1)),
            List.of(new MetricPoint(-1, 4), new MetricPoint(41, 4),
                new MetricPoint(41, 6), new MetricPoint(-1, 6))));

        var result = new ProbabilisticInference().solve(graph,
            EvidenceModelParameters.withoutShapeTerms(), budget(2, 2, 128), region);

        assertEquals(1, result.rawPaths().size());
        assertEquals("b", result.rawPaths().get(0).branchSignature());
        assertFalse(result.alternativeSearchTruncated());
        assertEquals(1, result.completion().orElseThrow().completePathsAtSaturation());
        assertFalse(result.completion().orElseThrow().terminalCountSaturated());
    }

    @Test
    void retainedStagePrefixTieRemainsTheDeterministicRepresentative() {
        List<InferenceProfile> graph = List.of(
            profile(0, new double[] {0, 0.1}, new double[] {Math.nextUp(4.0), 4.0},
                new String[] {"one", "one"}),
            profile(1, new double[] {0}, new double[] {0}, new String[] {"one"}),
            profile(2, new double[] {0}, new double[] {0}, new String[] {"one"}),
            profile(3, new double[] {0}, new double[] {4}, new String[] {"one"}));

        for (int repeat = 0; repeat < 3; repeat++) {
            var result = solve(graph, budget(1, 1, 128));
            assertEquals(4.0, result.mapPath().orElseThrow().energy());
            assertEquals(1, result.mapPath().orElseThrow().stateIndices()[0]);
        }
    }

    @Test
    void requestedOneRouteDoesNotHideObservedEqualCostBranches() {
        InferenceProfile profile = profile(0, new double[] {-3, 3}, new double[] {0, 0},
            new String[] {"left", "right"});
        var completeRaw = solve(List.of(profile), budget(2, 1, 128));

        assertEquals(ProbabilisticInferenceResult.Status.AMBIGUOUS, completeRaw.status());
        assertEquals(1, completeRaw.distinctPaths().size());
        assertFalse(completeRaw.alternativeSearchTruncated());
    }

    @Test
    void unseenCloseRivalRequiresReviewWhenOnlyOneRawPathIsRetained() {
        InferenceProfile profile = profile(0, new double[] {-3, 3}, new double[] {0, 0},
            new String[] {"left", "right"});
        var cappedRaw = solve(List.of(profile), budget(1, 1, 128));

        assertEquals(ProbabilisticInferenceResult.Status.REVIEW_REQUIRED, cappedRaw.status());
        assertFalse(cappedRaw.alternativeSearchTruncated());
    }

    @Test
    void equivalentGridVariantsDoNotCreateObservedBranchAmbiguity() {
        var result = solve(List.of(profile(0, new double[] {0, 0.2}, new double[] {0, 0},
                new String[] {"same", "same"})), budget(2, 1, 128));

        assertEquals(ProbabilisticInferenceResult.Status.COMPLETE, result.status());
        assertFalse(result.alternativeSearchTruncated());
    }

    @Test
    void terminalCountBelowAtAndAboveRawLimit() {
        TraceBudgets limits = budget(4, 2, 128);
        for (int paths : List.of(3, 4, 5)) {
            double[] offsets = new double[paths];
            String[] labels = new String[paths];
            for (int state = 0; state < paths; state++) {
                offsets[state] = state * 0.1;
                labels[state] = "one physical branch";
            }
            var result = solve(List.of(profile(0, offsets, new double[paths], labels)), limits);
            assertEquals(Math.min(paths, 4), result.rawPaths().size());
            assertEquals(1, result.distinctPaths().size());
            assertEquals(paths > 4, result.alternativeSearchTruncated(), "terminal paths=" + paths);
            var proof = result.completion().orElseThrow();
            assertEquals(4, proof.effectiveRawLimit());
            assertEquals(2, proof.effectiveDistinctLimit());
            assertEquals(paths, proof.completePathsAtSaturation());
            assertEquals(paths > 4, proof.terminalCountSaturated());
            assertEquals(paths > 4, proof.rawEnumerationCapped());
            assertFalse(proof.requestedDiversityReached());
        }
    }

    @Test
    void internalQueueEvictionWithRequestedDiversityAttainedIsNotTruncation() {
        var result = solve(binaryProfiles(4), budget(1, 1, 128));

        assertEquals(1, result.rawPaths().size());
        assertEquals(1, result.distinctPaths().size());
        assertFalse(result.alternativeSearchTruncated());
        assertEquals(2, result.completion().orElseThrow().completePathsAtSaturation());
        assertTrue(result.completion().orElseThrow().requestedDiversityReached());
        assertEquals(ProbabilisticInferenceResult.Status.REVIEW_REQUIRED, result.status(),
            "one retained path cannot exclude an unseen equal-cost branch");
    }

    @Test
    void orientationAdmissionFailureConsumesNoPairOrTransitionWork() {
        InferenceProfile blocked = new InferenceProfile(0, new MetricPoint(0, 0),
            new MetricPoint(0, 1), List.of(new LateralStateCell(0, 1, false, false, "one")),
            new double[] {0}, ImageOrientationSupport.unknown(ImageOrientationSupport.Status.RESOURCE_LIMIT),
            ObservationOwnership.DIRECT_TWO_SIDED, false);
        var result = new ProbabilisticInference().solve(List.of(blocked),
            EvidenceModelParameters.defaults(), TraceBudgets.defaults());

        assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, result.status());
        assertEquals(0, result.evaluatedPairVisits());
        assertEquals(0, result.evaluatedTransitions());
        assertFalse(result.alternativeSearchTruncated());
        assertTrue(result.completion().isEmpty());
    }

    @Test
    void forwardBackwardAndKBestShareTheExactFiftySixTransitionBudget() {
        List<InferenceProfile> profiles = binaryProfiles(4);
        var admitted = solve(profiles, budget(32, 8, 56));
        var blocked = solve(profiles, budget(32, 8, 40));

        assertEquals(ProbabilisticInferenceResult.Status.AMBIGUOUS, admitted.status());
        assertEquals(12, admitted.evaluatedPairVisits());
        assertEquals(56, admitted.evaluatedTransitions());
        assertEquals(16, admitted.completion().orElseThrow().completePathsAtSaturation());
        assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, blocked.status());
        assertEquals(12, blocked.evaluatedPairVisits());
        assertEquals(41, blocked.evaluatedTransitions());
        assertTrue(blocked.mapPath().isEmpty());
        assertTrue(blocked.rawPaths().isEmpty());
        assertFalse(blocked.posteriorUsable());
        assertFalse(blocked.alternativeSearchTruncated());
        assertTrue(blocked.completion().isEmpty());
    }

    private static ProbabilisticInferenceResult solve(List<InferenceProfile> profiles, TraceBudgets budget) {
        return new ProbabilisticInference().solve(profiles, EvidenceModelParameters.withoutShapeTerms(), budget);
    }

    private static void assertNoUsableResult(ProbabilisticInferenceResult aborted) {
        assertTrue(aborted.mapPath().isEmpty());
        assertTrue(aborted.rawPaths().isEmpty());
        assertFalse(aborted.posteriorUsable());
        assertFalse(aborted.alternativeSearchTruncated());
        assertTrue(aborted.completion().isEmpty());
    }

    private static TraceBudgets budget(int raw, int distinct, long transitions) {
        return new TraceBudgets(96, 8_000_000, transitions, raw, distinct);
    }

    private static List<InferenceProfile> binaryProfiles(int count) {
        List<InferenceProfile> profiles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            profiles.add(profile(index * 10.0, new double[] {-3, 3}, new double[] {0, 0},
                new String[] {"left", "right"}));
        }
        return profiles;
    }

    private static InferenceProfile profile(double chainage, double[] offsets, double[] costs,
            String[] labels) {
        double[] widths = new double[offsets.length];
        Arrays.fill(widths, 1.0);
        return V022ProbabilisticInferenceTest.profile(chainage, offsets, widths, costs, labels);
    }
}

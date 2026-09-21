package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;

class V022PathAlternativeTest {
    @Test
    void t027PersistentDistinctBranchesAreBothRetained() {
        ProbabilisticInferenceResult result = solveParallel(40, 0.0, 0.1, TraceBudgets.defaults());

        assertEquals(2, result.distinctPaths().stream().map(ProbabilisticPath::branchSignature).distinct().count());
    }

    @Test
    void t028TinyGridVariantsAreDeduplicated() {
        List<InferenceProfile> profiles = profiles(30, new double[] {-3, -2.8, 3},
            new double[] {0, 0.001, 0.1}, new String[] {"left", "left", "right"});

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults());

        assertTrue(result.rawPaths().size() > result.distinctPaths().size());
        assertEquals(1, result.distinctPaths().stream()
            .filter(path -> path.branchSignature().equals("left")).count());
    }

    @Test
    void t029RawAndDistinctCapsProduceVisibleTruncation() {
        TraceBudgets budgets = new TraceBudgets(96, 8_000_000, 128_000_000, 3, 2);
        List<InferenceProfile> profiles = profiles(20, new double[] {-4, -2, 0, 2, 4},
            new double[] {0, 0, 0, 0, 0}, new String[] {"a", "b", "c", "d", "e"});

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
            EvidenceModelParameters.defaults(), budgets);

        assertTrue(result.alternativeSearchTruncated());
        assertEquals(3, result.rawPaths().size());
        assertTrue(result.distinctPaths().size() <= 2);
    }

    @Test
    void t030AlternativesAreObservedPathsNeverMeanOfParallelModes() {
        ProbabilisticInferenceResult result = solveParallel(40, 0.0, 0.0, TraceBudgets.defaults());

        assertTrue(result.distinctPaths().stream().flatMap(path -> path.points().stream())
            .allMatch(point -> Math.abs(point.yMeters()) == 3.0));
        assertFalse(result.distinctPaths().stream().flatMap(path -> path.points().stream())
            .anyMatch(point -> Math.abs(point.yMeters()) < 1e-12));
    }

    @Test
    void t031IdentifiableCrossingKeepsCorrectBranchAsTopOne() {
        List<InferenceProfile> profiles = profiles(40, new double[] {-3, 3},
            new double[] {0.0, 1.5}, new String[] {"through", "crossing"});

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults());

        assertEquals("through", result.mapPath().orElseThrow().branchSignature());
        assertEquals(ProbabilisticInferenceResult.Status.COMPLETE, result.status());
    }

    @Test
    void t032AmbiguousCrossingReturnsReviewableDistinctAlternatives() {
        ProbabilisticInferenceResult result = solveParallel(40, 0.0, 0.0, TraceBudgets.defaults());

        assertEquals(ProbabilisticInferenceResult.Status.AMBIGUOUS, result.status());
        assertTrue(result.distinctPaths().size() >= 2);
        assertTrue(result.distinctPaths().get(0).conditionalPosteriorMass() < 0.95);
    }

    @Test
    void s06IsolatedBrightCrossingDoesNotPullTheLongitudinalRouteOffItsWeakCorridor() {
        List<InferenceProfile> profiles = sceneProfiles(41, new double[] {0, 5},
            (profile, state) -> state == 0 ? 0.02 : profile == 20 ? 0.0 : 3.0,
            new String[] {"target", "crossing"}, profile -> false, profile -> false);
        ProbabilisticInferenceResult result = solve(profiles);
        assertEquals("target", result.mapPath().orElseThrow().branchSignature());
        assertTrue(result.mapPath().orElseThrow().points().stream().allMatch(point -> point.yMeters() == 0.0));
    }

    @Test
    void s07SymmetricBranchesRemainDistinctAndReviewable() {
        ProbabilisticInferenceResult result = solveParallel(41, 0.0, 0.0, TraceBudgets.defaults());
        assertEquals(ProbabilisticInferenceResult.Status.AMBIGUOUS, result.status());
        assertEquals(2, result.distinctPaths().stream().map(ProbabilisticPath::branchSignature).distinct().count());
    }

    @Test
    void s08ExplicitPortsPreventCaptureByABrighterParallelRoute() {
        List<InferenceProfile> profiles = sceneProfiles(41, new double[] {0, 8},
            (profile, state) -> state == 0 ? 0.25 : 0.0, new String[] {"selected", "brighter"},
            profile -> false, profile -> profile == 0 || profile == 20 || profile == 40);
        ProbabilisticPath path = solve(profiles).mapPath().orElseThrow();
        assertEquals(0.0, path.points().get(0).yMeters());
        assertEquals(0.0, path.points().get(20).yMeters());
        assertEquals(0.0, path.points().get(40).yMeters());
    }

    @Test
    void s09IntermittentNearbyStrandsRemainOneBoundedCorridor() {
        List<InferenceProfile> profiles = sceneProfiles(41, new double[] {-1, 0, 1},
            (profile, state) -> state == profile % 3 ? 0.0 : 0.35, new String[] {"union", "union", "union"},
            profile -> profile % 11 == 5, profile -> false);
        ProbabilisticInferenceResult result = solve(profiles);
        assertTrue(result.mapPath().orElseThrow().points().stream().allMatch(point -> Math.abs(point.yMeters()) <= 1.0));
        assertTrue(result.gapSummary().longestInternalGapMeters() <= 20.0);
    }

    @Test
    void s10PersistentParallelRoadsAreNotAveragedToTheirMidpoint() {
        ProbabilisticInferenceResult result = solve(sceneProfiles(41, new double[] {-5, 5},
            (profile, state) -> 0.0, new String[] {"south", "north"},
            profile -> false, profile -> false));
        assertEquals(2, result.distinctPaths().stream().map(ProbabilisticPath::branchSignature).distinct().count());
        assertTrue(result.distinctPaths().stream().flatMap(path -> path.points().stream())
            .noneMatch(point -> Math.abs(point.yMeters()) < 4.9));
    }

    @Test
    void s11ObservedTailWithShortHolesIsRecoveredAsMeasuredRoute() {
        ProbabilisticInferenceResult result = solve(sineTailProfiles(false));
        assertEquals(ProbabilisticInferenceResult.Status.COMPLETE, result.status());
        for (int index = 0; index < result.mapPath().orElseThrow().points().size(); index++) {
            assertEquals(3.0 * Math.sin(2.0 * Math.PI * index * 10.0 / 240.0),
                result.mapPath().orElseThrow().points().get(index).yMeters(), 1e-9);
        }
    }

    @Test
    void s12UnobservedLongTailIsExplicitlyReviewRequired() {
        ProbabilisticInferenceResult result = solve(sineTailProfiles(true));
        assertEquals(ProbabilisticInferenceResult.Status.REVIEW_REQUIRED, result.status());
        assertTrue(result.gapSummary().terminalGapMeters() > 300.0);
    }

    @Test
    void kBestChargesEveryRetainedPrefixExtensionAgainstTheTransitionBudget() {
        TraceBudgets budget = new TraceBudgets(96, 8_000_000, 190, 32, 8);
        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(
            profiles(5, new double[] {-2, 0, 2}, new double[] {0, 0, 0},
                new String[] {"left", "center", "right"}),
            EvidenceModelParameters.defaults(), budget);

        assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, result.status());
        assertTrue(result.explanation().contains("k-best"));
    }

    @Test
    void hardAlternativeCeilingsCannotBeRaisedByCallerBudget() {
        double[] offsets = java.util.stream.IntStream.range(0, 16).mapToDouble(index -> index * 2.0).toArray();
        double[] costs = new double[16];
        String[] branches = java.util.stream.IntStream.range(0, 16).mapToObj(index -> "b" + index)
            .toArray(String[]::new);
        TraceBudgets raised = new TraceBudgets(96, 8_000_000, 128_000_000, 64, 16);

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(
            profiles(20, offsets, costs, branches), EvidenceModelParameters.defaults(), raised);

        assertEquals(32, result.rawPaths().size());
        assertEquals(8, result.distinctPaths().size());
        assertTrue(result.alternativeSearchTruncated());
    }

    private static ProbabilisticInferenceResult solve(List<InferenceProfile> profiles) {
        return new ProbabilisticInference().solve(profiles, EvidenceModelParameters.defaults(),
            TraceBudgets.defaults());
    }

    private static List<InferenceProfile> sineTailProfiles(boolean missingTail) {
        List<InferenceProfile> result = new ArrayList<>();
        for (int index = 0; index <= 60; index++) {
            double x = index * 10.0;
            double truth = 3.0 * Math.sin(2.0 * Math.PI * x / 240.0);
            boolean missing = missingTail ? x > 200.0 : index > 20 && index % 4 == 0;
            result.add(sceneProfile(x, new double[] {truth, truth + 6.0},
                missing ? new double[] {0, 0} : new double[] {0, 2},
                new String[] {"truth", "parallel"}, missing));
        }
        return result;
    }

    private static List<InferenceProfile> sceneProfiles(int count, double[] offsets,
        java.util.function.BiFunction<Integer, Integer, Double> cost, String[] branches,
        java.util.function.IntPredicate missing, java.util.function.IntPredicate fixedToFirst) {
        List<InferenceProfile> result = new ArrayList<>();
        for (int profile = 0; profile < count; profile++) {
            if (fixedToFirst.test(profile)) {
                result.add(sceneProfile(profile * 10.0, new double[] {offsets[0]}, new double[] {0},
                    new String[] {branches[0]}, false));
                continue;
            }
            double[] costs = new double[offsets.length];
            for (int state = 0; state < offsets.length; state++) {
                costs[state] = missing.test(profile) ? 0.0 : cost.apply(profile, state);
            }
            result.add(sceneProfile(profile * 10.0, offsets, costs, branches, missing.test(profile)));
        }
        return result;
    }

    private static InferenceProfile sceneProfile(double chainage, double[] offsets, double[] costs,
        String[] branches, boolean missing) {
        double[] widths = java.util.stream.DoubleStream.generate(() -> 1.0).limit(offsets.length).toArray();
        InferenceProfile base = V022ProbabilisticInferenceTest.profile(chainage, offsets, widths, costs, branches);
        return missing ? new InferenceProfile(base.chainageMeters(), base.anchor(), base.normalUnit(), base.cells(),
            costs, List.of(), 0.0,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership.NO_RASTER, true) : base;
    }

    private static ProbabilisticInferenceResult solveParallel(int profiles, double leftCost, double rightCost,
        TraceBudgets budgets) {
        return new ProbabilisticInference().solve(profiles(profiles, new double[] {-3, 3},
            new double[] {leftCost, rightCost}, new String[] {"left", "right"}),
            EvidenceModelParameters.defaults(), budgets);
    }

    private static List<InferenceProfile> profiles(int count, double[] offsets, double[] costs, String[] branches) {
        List<InferenceProfile> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(V022ProbabilisticInferenceTest.profile(index, offsets,
                java.util.stream.DoubleStream.generate(() -> 1.0).limit(offsets.length).toArray(),
                costs, branches));
        }
        return result;
    }
}

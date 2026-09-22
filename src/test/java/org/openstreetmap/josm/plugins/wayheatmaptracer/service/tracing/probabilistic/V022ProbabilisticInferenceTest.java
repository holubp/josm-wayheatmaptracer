package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class V022ProbabilisticInferenceTest {
    @Test
    void t019MapEnergyAndPathEqualIndependentBruteForce() {
        List<InferenceProfile> profiles = tinyProfiles();
        EvidenceModelParameters parameters = EvidenceModelParameters.defaults();
        OracleResult oracle = bruteForce(profiles, parameters);

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles, parameters,
            TraceBudgets.defaults());

        assertArrayEquals(oracle.paths().get(0).states(), result.mapPath().orElseThrow().stateIndices());
        assertEquals(oracle.paths().get(0).energy(), result.mapPath().orElseThrow().energy(), 1e-12);
    }

    @Test
    void t020PartitionFunctionAndMarginalsEqualIndependentEnumeration() {
        List<InferenceProfile> profiles = tinyProfiles();
        EvidenceModelParameters parameters = EvidenceModelParameters.defaults();
        OracleResult oracle = bruteForce(profiles, parameters);

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles, parameters,
            TraceBudgets.defaults());

        assertEquals(oracle.logPartition(), result.logPartition(), 1e-12);
        for (int profile = 0; profile < profiles.size(); profile++) {
            assertArrayEquals(oracle.marginals().get(profile), result.positionMarginals().get(profile), 1e-12);
        }
    }

    @Test
    void t021ForwardAndBackwardMarginalReconstructionsAgree() {
        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(tinyProfiles(),
            EvidenceModelParameters.defaults(), TraceBudgets.defaults());

        for (int profile = 0; profile < result.positionMarginals().size(); profile++) {
            assertArrayEquals(result.forwardPositionMarginals().get(profile),
                result.backwardPositionMarginals().get(profile), 1e-12);
        }
    }

    @Test
    void t022QuadraturePreventsDenseSamplingFromCreatingProbabilityMass() {
        InferenceProfile coarse = profile(0, new double[] {-1, 0, 1}, new double[] {0.5, 1, 0.5},
            new double[] {0, 0, 0}, new String[] {"u", "u", "u"});
        InferenceProfile dense = profile(0, new double[] {-1, -0.5, 0, 0.5, 1},
            new double[] {0.25, 0.5, 0.5, 0.5, 0.25}, new double[] {0, 0, 0, 0, 0},
            new String[] {"u", "u", "u", "u", "u"});

        ProbabilisticInference solver = new ProbabilisticInference();
        assertEquals(solver.solve(List.of(coarse), EvidenceModelParameters.defaults(), TraceBudgets.defaults())
                .logPartition(),
            solver.solve(List.of(dense), EvidenceModelParameters.defaults(), TraceBudgets.defaults())
                .logPartition(), 1e-12);
    }

    @Test
    void t023ExactTiesUseStableLexicographicStateOrder() {
        List<InferenceProfile> profiles = List.of(
            profile(0, new double[] {-1, 1}, new double[] {1, 1}, new double[] {0, 0}, new String[] {"l", "r"}),
            profile(1, new double[] {-1, 1}, new double[] {1, 1}, new double[] {0, 0}, new String[] {"l", "r"}));

        ProbabilisticPath path = new ProbabilisticInference().solve(profiles,
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults()).mapPath().orElseThrow();

        assertArrayEquals(new int[] {0, 0}, path.stateIndices());
    }

    @Test
    void t024EntirelyMissingProfilesAreTypedAndNotClaimedAsObserved() {
        List<InferenceProfile> profiles = List.of(missingProfile(0), missingProfile(10), missingProfile(20));

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
            EvidenceModelParameters.defaults(), TraceBudgets.defaults());

        assertEquals(ProbabilisticInferenceResult.Status.ALL_MISSING, result.status());
        assertFalse(result.posteriorUsable());
        assertTrue(result.gapSummary().absentMeters() > 0.0);
    }

    @Test
    void t025CredibleLateralSetPreservesSeparatedModes() {
        InferenceProfile bimodal = profile(0, new double[] {-3, 0, 3}, new double[] {1, 1, 1},
            new double[] {0, 20, 0}, new String[] {"left", "valley", "right"});

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(List.of(bimodal),
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults());

        assertEquals(2, result.credibleSets().get(0).intervals().size());
        assertFalse(result.credibleSets().get(0).intervals().stream()
            .anyMatch(interval -> interval.minimumOffsetMeters() < 0 && interval.maximumOffsetMeters() > 0));
    }

    @Test
    void t026LogDomainMessagesRemainFiniteForLongLowLikelihoodRoute() {
        List<InferenceProfile> profiles = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            profiles.add(profile(index, new double[] {-1, 1}, new double[] {1, 1},
                new double[] {900, 901}, new String[] {"left", "right"}));
        }

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults());

        assertTrue(Double.isFinite(result.logPartition()));
        assertTrue(result.positionMarginals().stream().flatMapToDouble(Arrays::stream).allMatch(Double::isFinite));
        assertEquals(ProbabilisticInferenceResult.Status.COMPLETE, result.status());
    }


    @Test
    void t075StructuralGuideRemainsASeparateCappedTermInExactMapAndMarginals() {
        InferenceProfile profile = profile(0, new double[] {-1, 1}, new double[] {1, 1},
            new double[] {0, 0}, new String[] {"left", "right"})
            .withStructuralGuideCosts(new double[] {4, 0});

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(List.of(profile),
            EvidenceModelParameters.defaults(), TraceBudgets.defaults());

        assertArrayEquals(new int[] {1}, result.mapPath().orElseThrow().stateIndices());
        double left = Math.exp(-EvidenceModelParameters.defaults().guideWeight() * 4.0);
        assertArrayEquals(new double[] {left / (1.0 + left), 1.0 / (1.0 + left)},
            result.positionMarginals().get(0), 1.0e-12);
        assertEquals(0.0, result.mapPath().orElseThrow().energy(), 1.0e-12);
    }

    @Test
    void firstUseMemoPreservesTheOriginal1024TransitionCancellationCheckpoint() {
        List<InferenceProfile> profiles = denseProfiles(3, 12);
        int[] checkpoints = {0};
        CancellationProbe cancellation = () -> ++checkpoints[0] >= 3;

        assertThrows(CancellationException.class, () -> new ProbabilisticInference().solve(profiles,
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults(), null, cancellation));
        assertEquals(3, checkpoints[0]);
    }

    @Test
    void firstUseMemoPreservesPairVisitBoundaryStatusAndCounters() {
        List<InferenceProfile> profiles = denseProfiles(3, 2);
        EvidenceModelParameters parameters = EvidenceModelParameters.withoutShapeTerms();
        TraceBudgets justBelow = new TraceBudgets(96, 7, 1_000, 32, 8);
        TraceBudgets atBoundary = new TraceBudgets(96, 8, 1_000, 32, 8);

        ProbabilisticInferenceResult blocked = new ProbabilisticInference().solve(profiles, parameters,
            justBelow);
        ProbabilisticInferenceResult admitted = new ProbabilisticInference().solve(profiles, parameters,
            atBoundary);

        assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, blocked.status());
        assertEquals(8, blocked.evaluatedPairVisits());
        assertEquals("pair-visit budget exceeded", blocked.explanation());
        assertEquals(ProbabilisticInferenceResult.Status.COMPLETE, admitted.status());
        assertEquals(8, admitted.evaluatedPairVisits());
    }

    @Test
    void segmentInteriorCannotJumpAcrossAProhibitedDecisionRegionGap() {
        List<InferenceProfile> profiles = List.of(
            profile(0, new double[] {0}, new double[] {1}, new double[] {0}, new String[] {"route"}),
            profile(2, new double[] {0}, new double[] {1}, new double[] {0}, new String[] {"route"}));
        MetricRegion disconnected = new MetricRegion(List.of(
            List.of(new MetricPoint(-1, -1), new MetricPoint(0.5, -1),
                new MetricPoint(0.5, 1), new MetricPoint(-1, 1)),
            List.of(new MetricPoint(1.5, -1), new MetricPoint(3, -1),
                new MetricPoint(3, 1), new MetricPoint(1.5, 1))));

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults(), disconnected);

        assertEquals(ProbabilisticInferenceResult.Status.NO_ROUTE, result.status());
        assertTrue(result.mapPath().isEmpty());
    }

    private static List<InferenceProfile> denseProfiles(int profileCount, int states) {
        List<InferenceProfile> profiles = new ArrayList<>();
        double[] offsets = new double[states];
        double[] widths = new double[states];
        double[] unaries = new double[states];
        String[] branches = new String[states];
        for (int state = 0; state < states; state++) {
            offsets[state] = state - 0.5 * (states - 1);
            widths[state] = 1.0;
            branches[state] = "dense";
        }
        for (int index = 0; index < profileCount; index++) {
            profiles.add(profile(2.0 * index, offsets, widths, unaries, branches));
        }
        return List.copyOf(profiles);
    }

    static List<InferenceProfile> tinyProfiles() {
        return List.of(
            profile(0, new double[] {-1, 1}, new double[] {0.7, 1.3}, new double[] {0.2, 0.8},
                new String[] {"left", "right"}),
            profile(2, new double[] {-1, 1}, new double[] {1.0, 1.0}, new double[] {0.4, 0.1},
                new String[] {"left", "right"}),
            profile(5, new double[] {-1, 1}, new double[] {1.2, 0.8}, new double[] {0.3, 0.6},
                new String[] {"left", "right"}));
    }

    static InferenceProfile profile(double chainage, double[] offsets, double[] widths, double[] unaries,
        String[] branches) {
        List<LateralStateCell> cells = new ArrayList<>();
        for (int index = 0; index < offsets.length; index++) {
            cells.add(new LateralStateCell(offsets[index], widths[index], false, true, branches[index]));
        }
        return new InferenceProfile(chainage, new MetricPoint(chainage, 0), new MetricPoint(0, 1), cells,
            unaries, List.of(), 0.0, ObservationOwnership.DIRECT_TWO_SIDED, false);
    }

    static InferenceProfile missingProfile(double chainage) {
        return new InferenceProfile(chainage, new MetricPoint(chainage, 0), new MetricPoint(0, 1),
            List.of(new LateralStateCell(-1, 1, false, false, "missing"),
                new LateralStateCell(1, 1, false, false, "missing")),
            new double[] {0, 0}, List.of(), 0.0, ObservationOwnership.NO_RASTER, true);
    }

    /** Independent exhaustive oracle; it shares only immutable input values with production. */
    static OracleResult bruteForce(List<InferenceProfile> profiles, EvidenceModelParameters parameters) {
        List<OraclePath> paths = new ArrayList<>();
        enumerate(profiles, parameters, new int[profiles.size()], 0, paths);
        paths.sort(Comparator.comparingDouble(OraclePath::energy).thenComparing(OraclePath::lexicographicKey));
        double maxLogWeight = paths.stream().mapToDouble(path -> path.logWeight(parameters.temperature())).max().orElseThrow();
        double scaledSum = paths.stream().mapToDouble(path -> Math.exp(path.logWeight(parameters.temperature()) - maxLogWeight)).sum();
        double logPartition = maxLogWeight + Math.log(scaledSum);
        List<double[]> marginals = new ArrayList<>();
        for (int profile = 0; profile < profiles.size(); profile++) {
            double[] values = new double[profiles.get(profile).cells().size()];
            for (OraclePath path : paths) {
                values[path.states()[profile]] += Math.exp(path.logWeight(parameters.temperature()) - logPartition);
            }
            marginals.add(values);
        }
        return new OracleResult(paths, logPartition, marginals);
    }

    private static void enumerate(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
        int[] states, int depth, List<OraclePath> output) {
        if (depth == states.length) {
            double energy = 0.0;
            double logMeasure = 0.0;
            for (int index = 0; index < states.length; index++) {
                InferenceProfile profile = profiles.get(index);
                int state = states[index];
                double quadrature = profileQuadrature(profiles, index);
                energy += quadrature * parameters.dataWeight() * profile.unaryCosts()[state];
                logMeasure += Math.log(profile.cells().get(state).quadratureWidthMeters());
                if (index >= 2) {
                    MetricPoint p0 = profiles.get(index - 2).point(states[index - 2]);
                    MetricPoint p1 = profiles.get(index - 1).point(states[index - 1]);
                    MetricPoint p2 = profile.point(state);
                    double first = Math.atan2(p1.yMeters() - p0.yMeters(), p1.xMeters() - p0.xMeters());
                    double second = Math.atan2(p2.yMeters() - p1.yMeters(), p2.xMeters() - p1.xMeters());
                    double turn = wrap(second - first) / parameters.turnScaleRadians();
                    double middleSpan = Math.max(1e-6,
                        0.5 * (profile.chainageMeters() - profiles.get(index - 2).chainageMeters()));
                    energy += parameters.turnWeight() * huber(turn) / middleSpan;
                }
            }
            output.add(new OraclePath(states.clone(), energy, logMeasure));
            return;
        }
        for (int state = 0; state < profiles.get(depth).cells().size(); state++) {
            states[depth] = state;
            enumerate(profiles, parameters, states, depth + 1, output);
        }
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
        return 0.5 * (profiles.get(index + 1).chainageMeters() - profiles.get(index - 1).chainageMeters());
    }

    private static double wrap(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    private static double huber(double value) {
        double absolute = Math.abs(value);
        return absolute <= 1.0 ? 0.5 * value * value : absolute - 0.5;
    }

    record OraclePath(int[] states, double energy, double logMeasure) {
        double logWeight(double temperature) {
            return -energy / temperature + logMeasure;
        }

        String lexicographicKey() {
            return Arrays.toString(states);
        }
    }

    record OracleResult(List<OraclePath> paths, double logPartition, List<double[]> marginals) { }
}

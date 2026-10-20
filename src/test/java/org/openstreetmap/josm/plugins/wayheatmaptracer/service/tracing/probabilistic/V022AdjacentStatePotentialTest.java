package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class V022AdjacentStatePotentialTest {
    @Test
    void twoProfilePotentialHasIndependentPartitionMarginalsAndMapOracle() {
        List<InferenceProfile> profiles = List.of(
            V022ProbabilisticInferenceTest.profile(0, new double[] {0, 1}, new double[] {1, 2},
                new double[] {0, 0}, new String[] {"a", "b"}),
            V022ProbabilisticInferenceTest.profile(1, new double[] {0, 1}, new double[] {3, 4},
                new double[] {0, 0}, new String[] {"a", "b"}));
        double[][] energy = {{0, StrictMath.log(2)}, {StrictMath.log(4), StrictMath.log(8)}};

        ProbabilisticInferenceResult result = solve(profiles,
            (stage, left, right) -> energy[left][right]);

        assertEquals(StrictMath.log(7.5), result.logPartition(), 1e-12);
        assertArrayEquals(new double[] {2.0 / 3.0, 1.0 / 3.0},
            result.positionMarginals().get(0), 1e-12);
        assertArrayEquals(new double[] {0.6, 0.4}, result.positionMarginals().get(1), 1e-12);
        assertArrayEquals(new int[] {0, 0}, result.mapPath().orElseThrow().stateIndices());
    }

    @Test
    void initialPairCanSetAUniqueMapDifferentFromTheBaselineLexicalWinner() {
        List<InferenceProfile> profiles = twoStatePair();
        double[][] energy = {{3, 2}, {1, 0}};
        AdjacentStatePotential potential = (stage, left, right) -> energy[left][right];

        for (ProbabilisticExecutionPolicy.Mode mode : new ProbabilisticExecutionPolicy.Mode[] {
                ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2,
                ProbabilisticExecutionPolicy.Mode.EAGER_V1}) {
            ProbabilisticInferenceResult baseline = solve(profiles,
                (stage, left, right) -> 0.0, mode, 1);
            ProbabilisticInferenceResult changed = solve(profiles, potential, mode, 1);

            assertArrayEquals(new int[] {0, 0}, baseline.mapPath().orElseThrow().stateIndices());
            assertArrayEquals(new int[] {1, 1}, changed.mapPath().orElseThrow().stateIndices());
            assertEquals(0.0, changed.mapPath().orElseThrow().energy(), 0.0);
        }
    }

    @Test
    void exhaustiveNonzeroShapeOracleMatchesBothExecutionModesAndRawCaps() {
        List<InferenceProfile> profiles = shapeOracleProfiles();
        EvidenceModelParameters parameters = new EvidenceModelParameters("test-gap-shape-oracle",
            1.2, 1.0, 0.9, 0.7, 0.0, 0.65, 1.7, 0.0);
        double[][][] feature = {
            {{0.4, 1.1}, {0.2, 0.8}},
            {{0.7, 0.3}, {1.4, 0.1}}
        };
        List<OraclePath> oracle = enumerateOracle(profiles, parameters, feature);
        double logPartition = oracleLogPartition(oracle, parameters.temperature());

        for (ProbabilisticExecutionPolicy.Mode mode : List.of(ProbabilisticExecutionPolicy.Mode.EAGER_V1,
                ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2)) {
            for (int rawCap : new int[] {1, 2, 32}) {
                ProbabilisticInferenceResult result = solve(profiles,
                    (stage, left, right) -> feature[stage][left][right], mode, rawCap, parameters);

                assertEquals(logPartition, result.logPartition(), 1e-12,
                    "partition in " + mode + " with raw cap " + rawCap);
                for (int profile = 0; profile < profiles.size(); profile++) {
                    assertArrayEquals(oracleMarginal(oracle, parameters.temperature(), logPartition,
                            profiles.size(), profile), result.positionMarginals().get(profile), 1e-12,
                        "marginal profile " + profile + " in " + mode + " with raw cap " + rawCap);
                }
                OraclePath map = oracle.get(0);
                assertArrayEquals(map.states(), result.mapPath().orElseThrow().stateIndices());
                assertEquals(map.energy(), result.mapPath().orElseThrow().energy(), 1e-12);
                assertEquals(Math.min(rawCap, oracle.size()), result.rawPaths().size());
                for (int index = 0; index < result.rawPaths().size(); index++) {
                    OraclePath expected = oracle.get(index);
                    var actual = result.rawPaths().get(index);
                    assertArrayEquals(expected.states(), actual.stateIndices());
                    assertEquals(expected.energy(), actual.energy(), 1e-12);
                    assertEquals(expected.logMeasure(), actual.logBaseMeasure(), 1e-12);
                    double expectedMass = Math.exp(-expected.energy() / parameters.temperature()
                            + expected.logMeasure() - logPartition);
                    assertEquals(expectedMass, actual.conditionalPosteriorMass(), 1e-12);
                }
            }
        }
    }

    @Test
    void invalidInteriorPotentialInExactModeReturnsFailureAndReleasesOwner() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(1_000_000);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        EvidenceModelParameters parameters = new EvidenceModelParameters("test-gap-v2-interior-refusal",
            1.0, 1.0, 0.4, 0.0, 0.0, 0.6, 1.3, 0.0);

        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(shapeOracleProfiles(),
            parameters, new TraceBudgets(8, 1_000, 100_000, 1, 1), null, CancellationProbe.NONE,
            owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2,
            (stage, left, right) -> stage == 1 ? -0.25 : 0.1);

        assertEquals(ProbabilisticInferenceResult.Status.NUMERIC_FAILURE, result.status());
        assertTrue(result.mapPath().isEmpty());
        owner.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void cellWidthsChangePosteriorMeasureButCannotChangeEnergyMap() {
        List<InferenceProfile> profiles = List.of(
            V022ProbabilisticInferenceTest.profile(0, new double[] {0, 1}, new double[] {1, 100},
                new double[] {0, 0}, new String[] {"a", "b"}),
            V022ProbabilisticInferenceTest.profile(1, new double[] {0, 1}, new double[] {1, 100},
                new double[] {0, 0}, new String[] {"a", "b"}));
        double[][] energy = {{0, 5}, {5, 5}};

        ProbabilisticInferenceResult result = solve(profiles, (stage, left, right) -> energy[left][right]);

        assertArrayEquals(new int[] {0, 0}, result.mapPath().orElseThrow().stateIndices());
        double expectedStateOne = 10_100.0 / (StrictMath.exp(5.0) + 10_200.0);
        assertEquals(expectedStateOne, result.positionMarginals().get(0)[1], 1e-12);
        assertTrue(result.positionMarginals().get(0)[1] > 0.95);
    }

    @Test
    void stageSpecificPotentialContributesAtInitialAndInteriorTransitions() {
        List<InferenceProfile> profiles = List.of(
            V022ProbabilisticInferenceTest.profile(0, new double[] {0, 1}, new double[] {1, 1},
                new double[] {0, 0}, new String[] {"a", "b"}),
            V022ProbabilisticInferenceTest.profile(1, new double[] {0, 1}, new double[] {1, 1},
                new double[] {0, 0}, new String[] {"a", "b"}),
            V022ProbabilisticInferenceTest.profile(2, new double[] {0, 1}, new double[] {1, 1},
                new double[] {0, 0}, new String[] {"a", "b"}));
        double[][] first = {{0, 1}, {2, 3}};
        double[][] second = {{4, 0}, {1, 2}};
        double partition = 0.0;
        double[] firstMarginal = new double[2];
        double[] middleMarginal = new double[2];
        double[] lastMarginal = new double[2];
        double bestEnergy = Double.POSITIVE_INFINITY;
        int[] best = null;
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int c = 0; c < 2; c++) {
            double weight = StrictMath.exp(-first[a][b] - second[b][c]);
            partition += weight;
            firstMarginal[a] += weight;
            middleMarginal[b] += weight;
            lastMarginal[c] += weight;
            double totalEnergy = first[a][b] + second[b][c];
            if (totalEnergy < bestEnergy) {
                bestEnergy = totalEnergy;
                best = new int[] {a, b, c};
            }
        }

        ProbabilisticInferenceResult result = solve(profiles,
            (stage, left, right) -> (stage == 0 ? first : second)[left][right]);

        assertEquals(StrictMath.log(partition), result.logPartition(), 1e-12);
        assertArrayEquals(new double[] {firstMarginal[0] / partition, firstMarginal[1] / partition},
            result.positionMarginals().get(0), 1e-12);
        assertArrayEquals(new double[] {middleMarginal[0] / partition, middleMarginal[1] / partition},
            result.positionMarginals().get(1), 1e-12);
        assertArrayEquals(new double[] {lastMarginal[0] / partition, lastMarginal[1] / partition},
            result.positionMarginals().get(2), 1e-12);
        assertArrayEquals(best, result.mapPath().orElseThrow().stateIndices());
    }

    @Test
    void orientationWeightZeroStillConsumesPotentialInTheProductionSolver() {
        AtomicInteger calls = new AtomicInteger();
        List<InferenceProfile> profiles = twoStatePair();

        ProbabilisticInferenceResult result = solve(profiles, (stage, left, right) -> {
            calls.incrementAndGet();
            return left == right ? 0.0 : 10.0;
        });

        assertTrue(calls.get() > 0);
        assertArrayEquals(new int[] {0, 0}, result.mapPath().orElseThrow().stateIndices());
    }

    @Test
    void nullCapabilityMatchesTheOriginalOverloadBitForBitAndInWorkAccounting() {
        List<InferenceProfile> profiles = V022ProbabilisticInferenceTest.tinyProfiles();
        EvidenceModelParameters parameters = EvidenceModelParameters.withoutShapeTerms();
        TraceBudgets budgets = TraceBudgets.defaults();
        ProbabilisticInference solver = new ProbabilisticInference();
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        ProbabilisticInferenceResult original;
        try {
            original = solver.solve(profiles, parameters, budgets, null, CancellationProbe.NONE,
                    owner, ProbabilisticExecutionPolicy.Mode.EAGER_V1);
        } finally {
            owner.close();
        }
        ProbabilisticInferenceResult explicitNull = solve(profiles, null);

        assertEquals(original.status(), explicitNull.status());
        assertEquals(original.evaluatedPairVisits(), explicitNull.evaluatedPairVisits());
        assertEquals(original.evaluatedTransitions(), explicitNull.evaluatedTransitions());
        assertEquals(original.workAccounting(), explicitNull.workAccounting());
        assertEquals(Double.doubleToRawLongBits(original.logPartition()),
            Double.doubleToRawLongBits(explicitNull.logPartition()));
        assertEquals(Double.doubleToRawLongBits(original.mapPath().orElseThrow().energy()),
            Double.doubleToRawLongBits(explicitNull.mapPath().orElseThrow().energy()));
        assertArrayEquals(original.mapPath().orElseThrow().stateIndices(),
            explicitNull.mapPath().orElseThrow().stateIndices());
        for (int profile = 0; profile < profiles.size(); profile++) {
            assertArrayEquals(original.positionMarginals().get(profile),
                explicitNull.positionMarginals().get(profile));
        }
    }

    @Test
    void malformedPotentialRefusesTheWholeAttempt() {
        for (double invalid : new double[] {-1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            ProbabilisticInferenceResult result = solve(twoStatePair(),
                (stage, left, right) -> invalid);
            assertEquals(ProbabilisticInferenceResult.Status.NUMERIC_FAILURE, result.status());
            assertTrue(result.mapPath().isEmpty());
            assertFalse(result.posteriorUsable());
        }
    }

    @Test
    void thrownPotentialFailureIsObservedByActualSolverAndClosesAttemptOwner() {
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> new ProbabilisticInference().solve(twoStatePair(),
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults(), null,
            CancellationProbe.NONE, owner, ProbabilisticExecutionPolicy.Mode.EAGER_V1,
            (stage, left, right) -> {
                calls.incrementAndGet();
                throw new IllegalStateException("sentinel");
            }));

        assertTrue(calls.get() > 0);
        assertEquals(0, ledger.currentBytes(), "solver failure must close its attempt work owner");
        owner.close();
        assertEquals(0, ledger.currentBytes());
    }

    private static List<InferenceProfile> twoStatePair() {
        return List.of(
            V022ProbabilisticInferenceTest.profile(0, new double[] {0, 1}, new double[] {1, 1},
                new double[] {0, 0}, new String[] {"a", "b"}),
            V022ProbabilisticInferenceTest.profile(1, new double[] {0, 1}, new double[] {1, 1},
                new double[] {0, 0}, new String[] {"a", "b"}));
    }

    private static List<InferenceProfile> shapeOracleProfiles() {
        double[] chainages = {0.0, 2.0, 5.0};
        double[][] offsets = {{-1.5, 1.2}, {-0.8, 1.7}, {-1.1, 0.9}};
        double[][] widths = {{0.6, 1.4}, {1.1, 0.9}, {0.7, 1.3}};
        double[][] unaries = {{0.1, 0.6}, {0.4, 0.05}, {0.2, 0.7}};
        double[][] orientationTargets = {{0.2, 1.2}, {0.4, 2.7}, {0.1, 2.5}};
        double[][] certainty = {{0.85, 0.65}, {0.75, 0.9}, {0.8, 0.7}};
        List<InferenceProfile> profiles = new ArrayList<>();
        for (int profile = 0; profile < chainages.length; profile++) {
            List<LateralStateCell> cells = List.of(
                new LateralStateCell(offsets[profile][0], widths[profile][0], false, true, "left"),
                new LateralStateCell(offsets[profile][1], widths[profile][1], false, true, "right"));
            Map<String, ImageOrientationSupport> orientation = Map.of(
                "left", ImageOrientationSupport.legacy(List.of(orientationTargets[profile][0]),
                    certainty[profile][0]),
                "right", ImageOrientationSupport.legacy(List.of(orientationTargets[profile][1]),
                    certainty[profile][1]));
            profiles.add(new InferenceProfile(chainages[profile],
                new MetricPoint(chainages[profile], 0.0), new MetricPoint(0.0, 1.0), cells,
                unaries[profile], orientation, ObservationOwnership.DIRECT_TWO_SIDED, false,
                new double[][] {{}, {}}));
        }
        return List.copyOf(profiles);
    }

    private static List<OraclePath> enumerateOracle(List<InferenceProfile> profiles,
            EvidenceModelParameters parameters, double[][][] feature) {
        List<OraclePath> paths = new ArrayList<>();
        for (int first = 0; first < 2; first++) {
            for (int middle = 0; middle < 2; middle++) {
                for (int last = 0; last < 2; last++) {
                    int[] states = {first, middle, last};
                    double energy = 0.0;
                    double logMeasure = 0.0;
                    for (int profile = 0; profile < profiles.size(); profile++) {
                        InferenceProfile input = profiles.get(profile);
                        int state = states[profile];
                        energy += profileQuadrature(profiles, profile)
                            * (parameters.dataWeight() * input.unaryCost(state)
                                + parameters.guideWeight() * input.structuralGuideCost(state));
                        logMeasure += Math.log(input.cells().get(state).quadratureWidthMeters());
                    }
                    for (int stage = 0; stage < profiles.size() - 1; stage++) {
                        InferenceProfile start = profiles.get(stage);
                        InferenceProfile end = profiles.get(stage + 1);
                        MetricPoint from = start.point(states[stage]);
                        MetricPoint to = end.point(states[stage + 1]);
                        double heading = Math.atan2(to.yMeters() - from.yMeters(),
                            to.xMeters() - from.xMeters());
                        double mismatchStart = independentOrientationMismatch(start, states[stage], heading);
                        double mismatchEnd = independentOrientationMismatch(end, states[stage + 1], heading);
                        double span = end.chainageMeters() - start.chainageMeters();
                        double originalPair = parameters.orientationWeight() * span * 0.5
                            * (mismatchStart + mismatchEnd);
                        energy += originalPair + feature[stage][states[stage]][states[stage + 1]];
                    }
                    for (int profile = 2; profile < profiles.size(); profile++) {
                        MetricPoint before = profiles.get(profile - 2).point(states[profile - 2]);
                        MetricPoint prior = profiles.get(profile - 1).point(states[profile - 1]);
                        MetricPoint current = profiles.get(profile).point(states[profile]);
                        double firstHeading = Math.atan2(prior.yMeters() - before.yMeters(),
                            prior.xMeters() - before.xMeters());
                        double secondHeading = Math.atan2(current.yMeters() - prior.yMeters(),
                            current.xMeters() - prior.xMeters());
                        double normalizedTurn = wrap(secondHeading - firstHeading)
                            / parameters.turnScaleRadians();
                        double middleSpan = Math.max(1e-6, 0.5 * (profiles.get(profile).chainageMeters()
                            - profiles.get(profile - 2).chainageMeters()));
                        energy += parameters.turnWeight() * huber(normalizedTurn) / middleSpan;
                    }
                    paths.add(new OraclePath(states, energy, logMeasure));
                }
            }
        }
        paths.sort(Comparator.comparingDouble(OraclePath::energy)
            .thenComparing(path -> Arrays.toString(path.states())));
        return paths;
    }

    private static double independentOrientationMismatch(InferenceProfile profile, int state,
            double heading) {
        ImageOrientationSupport support = profile.orientationSupport(state);
        double normalizedHeading = heading % Math.PI;
        if (normalizedHeading < 0.0) normalizedHeading += Math.PI;
        double distance = Math.abs(normalizedHeading - support.modes().get(0).peakBearingRadians());
        distance = Math.min(distance, Math.PI - distance);
        double sine = Math.sin(distance);
        return support.certainty() * profile.orientationReliability(state) * sine * sine;
    }

    private static double oracleLogPartition(List<OraclePath> paths, double temperature) {
        double sum = 0.0;
        for (OraclePath path : paths) {
            sum += Math.exp(-path.energy() / temperature + path.logMeasure());
        }
        return Math.log(sum);
    }

    private static double[] oracleMarginal(List<OraclePath> paths, double temperature,
            double logPartition, int profileCount, int requestedProfile) {
        double[] values = new double[2];
        for (OraclePath path : paths) {
            values[path.states()[requestedProfile]] += Math.exp(-path.energy() / temperature
                + path.logMeasure() - logPartition);
        }
        return values;
    }

    private static double profileQuadrature(List<InferenceProfile> profiles, int index) {
        if (index == 0) return 0.5 * (profiles.get(1).chainageMeters() - profiles.get(0).chainageMeters());
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

    private record OraclePath(int[] states, double energy, double logMeasure) { }

    private static ProbabilisticInferenceResult solve(List<InferenceProfile> profiles,
            AdjacentStatePotential potential) {
        return solve(profiles, potential, ProbabilisticExecutionPolicy.Mode.EAGER_V1, 32);
    }

    private static ProbabilisticInferenceResult solve(List<InferenceProfile> profiles,
            AdjacentStatePotential potential, ProbabilisticExecutionPolicy.Mode mode, int rawCap) {
        return solve(profiles, potential, mode, rawCap, EvidenceModelParameters.withoutShapeTerms());
    }

    private static ProbabilisticInferenceResult solve(List<InferenceProfile> profiles,
            AdjacentStatePotential potential, ProbabilisticExecutionPolicy.Mode mode, int rawCap,
            EvidenceModelParameters parameters) {
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        try {
            return new ProbabilisticInference().solve(profiles, parameters,
                new TraceBudgets(8, 1_000, 100_000, rawCap, rawCap), null,
                CancellationProbe.NONE, owner, mode, potential);
        } finally {
            owner.close();
        }
    }
}

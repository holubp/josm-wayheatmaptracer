package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleSupplier;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

class V022PairLocalEnergyReuseTest {
    // Populated only from retained pre-edit execution census, before changing production.
    private static final Map<String, String> BASELINE = Map.of(
            "complete", "Receipt[fingerprint=ce16b15ed5459d228ca97b26a72249888e0b14fd5bc2198fbf636479050edb2b, work=Optional[InferenceWorkAccounting[executionPolicy=terminal-path-count-scored-work-v2, forwardBackwardScores=108, kBestScores=108, logicalKBestExtensions=108, refusedScoreAttempts=0]], failure=none, checkpoints=915, current=12280, peak=18424, counters={inference.ancestryRecordsAllocated=126, inference.distinctPaths=1, inference.effectiveDistinctLimit=1, inference.effectiveRawLimit=32, inference.extensionDescriptors=108, inference.pairVisits=27, inference.profiles=4, inference.rawEnumerationCapped=1, inference.rawPaths=32, inference.requestedDiversityReached=1, inference.states=12, inference.terminalCountAtSaturation=33, inference.transitions=216}]",
            "late-cancel", "Receipt[fingerprint=unavailable, work=unavailable, failure=CancellationException:Alignment computation was cancelled, checkpoints=914, current=0, peak=18264, counters={inference.ancestryRecordsAllocated=124, inference.extensionDescriptors=108}]",
            "near-memory-cap", "Receipt[fingerprint=a5446e1b084c6978f384f2e8131cd58cc684d223d9e6120258dab2115c4c9ebc, work=Optional[InferenceWorkAccounting[executionPolicy=terminal-path-count-scored-work-v2, forwardBackwardScores=108, kBestScores=108, logicalKBestExtensions=108, refusedScoreAttempts=0]], failure=none, checkpoints=915, current=288, peak=18344, counters={inference.ancestryRecordsAllocated=125, inference.extensionDescriptors=108}]",
            "refused-kbest", "Receipt[fingerprint=973791dc7bc6468c712172f45306c62e5a1a7acf59b5249ea39fbb44b198eb03, work=Optional[InferenceWorkAccounting[executionPolicy=terminal-path-count-scored-work-v2, forwardBackwardScores=108, kBestScores=42, logicalKBestExtensions=45, refusedScoreAttempts=1]], failure=none, checkpoints=284, current=288, peak=10232, counters={inference.ancestryRecordsAllocated=54, inference.extensionDescriptors=42}]",
            "refused-primary", "Receipt[fingerprint=4246a0c082f4ddb8363c0aeb017cde2739fb907ee4588c3c64c9e2d0e76cb390, work=Optional[InferenceWorkAccounting[executionPolicy=terminal-path-count-scored-work-v2, forwardBackwardScores=10, kBestScores=0, logicalKBestExtensions=0, refusedScoreAttempts=1]], failure=none, checkpoints=3, current=288, peak=616, counters={inference.ancestryRecordsAllocated=0, inference.extensionDescriptors=0}]");

    @Test
    void originalGroupedExpressionsMatchFrozenBodiesAcrossWeightAndGeometryBranches() {
        for (var parameters : weights()) for (var profiles : fixtures()) {
            for (int profile = 2; profile < profiles.size(); profile++) {
                int at = profile;
                for (int before = 0; before < 3; before++) for (int prior = 0; prior < 3; prior++) {
                    for (int state = 0; state < 3; state++) {
                        int a = before, b = prior, c = state;
                        assertEquals(outcome(() -> FrozenPairLocalEnergyReference.increment(
                                        profiles, at, a, b, c, parameters)),
                                outcome(() -> productionIncrement(profiles, at, a, b, c, parameters)));
                    }
                }
            }
        }
    }

    @Test
    void headingConsumingHelpersMatchFrozenArithmeticIncludingOverflow() {
        List<List<InferenceProfile>> inputs = new ArrayList<>(fixtures());
        inputs.add(List.of(profile(0, -Double.MAX_VALUE, 0, 0, 1, false, 1),
                profile(Double.MIN_VALUE, 0, 0, 0, 1, false, 1),
                profile(2 * Double.MIN_VALUE, Double.MAX_VALUE, 0, 0, 1, false, 1)));
        for (var parameters : weights()) for (var profiles : inputs) {
            for (int before = 0; before < 3; before++) for (int prior = 0; prior < 3; prior++) {
                for (int state = 0; state < 3; state++) {
                    int a = before, b = prior, c = state;
                    MetricPoint first = profiles.get(0).point(a);
                    MetricPoint middle = profiles.get(1).point(b);
                    MetricPoint last = profiles.get(2).point(c);
                    double firstHeading = StrictMath.atan2(middle.yMeters() - first.yMeters(),
                            middle.xMeters() - first.xMeters());
                    double secondHeading = StrictMath.atan2(last.yMeters() - middle.yMeters(),
                            last.xMeters() - middle.xMeters());
                    if (parameters.orientationWeight() != 0.0) {
                        assertEquals(outcome(() -> FrozenPairLocalEnergyReference.pairEnergy(
                                        profiles, 1, b, c, parameters)),
                                outcome(() -> production("pairEnergyAtHeading",
                                        new Class<?>[] {InferenceProfile.class, InferenceProfile.class,
                                                int.class, int.class, EvidenceModelParameters.class, double.class},
                                        profiles.get(1), profiles.get(2), b, c, parameters, secondHeading)));
                    }
                    if (parameters.turnWeight() != 0.0) {
                        assertEquals(outcome(() -> FrozenPairLocalEnergyReference.tripleEnergy(
                                        profiles, 2, a, b, c, parameters)),
                                outcome(() -> production("tripleEnergyAtHeadings",
                                        new Class<?>[] {List.class, int.class, EvidenceModelParameters.class,
                                                double.class, double.class}, profiles, 2, parameters,
                                        firstHeading, secondHeading)));
                    }
                }
            }
        }
    }

    @Test
    void completeExactGraphsMatchUnchangedEagerAndFrozenGroupedPathEnergies() {
        for (var parameters : weights()) for (var profiles : fixtures()) {
            assertGraph(profiles, parameters, null);
        }
    }

    @Test
    void rejectedPairsEmptyPrefixesGapAndLateFirstUseKeepTheSameGraph() {
        var profiles = fixture(0, true);
        var region = MetricRegion.rectangle(-1, -0.5, 5, 2);
        for (var parameters : weights()) assertGraph(profiles, parameters, region);
        // Original state0 has no prefix: first cost use must wait for a live predecessor.
        var onlyUpper = fixture(0, false);
        assertGraph(onlyUpper, parameters(1, 1, 1, 1), region);
    }

    @Test
    void finiteOverflowCostsAndPointValidationKeepFrozenOutcomesWithoutFallbacks() {
        var overflow = List.of(
                profile(0, Double.MAX_VALUE, 0, 1, 0, false, Double.MAX_VALUE),
                profile(1, 1, 0, 0, 1, false, 1),
                profile(2, 2, 0, 0, 1, false, 1));
        var tiny = List.of(
                profile(0, 0, 0, 0, 1, false, 1),
                profile(Double.MIN_VALUE, 1, 0, 0, 1, false, 1),
                profile(2 * Double.MIN_VALUE, 2, 0, 0, 1, false, 1)
                        .withStructuralGuideCosts(new double[] {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}));
        for (var parameters : weights()) for (var profiles : List.of(overflow, tiny)) {
            for (int state = 0; state < 3; state++) {
                int index = state;
                assertEquals(outcome(() -> FrozenPairLocalEnergyReference.increment(
                                profiles, 2, index, 1, 1, parameters)),
                        outcome(() -> productionIncrement(profiles, 2, index, 1, 1, parameters)));
            }
        }
        var huge = parameters(Double.MAX_VALUE, Double.MAX_VALUE, 0, 0);
        assertEquals(outcome(() -> FrozenPairLocalEnergyReference.increment(tiny, 2, 0, 1, 1, huge)),
                outcome(() -> productionIncrement(tiny, 2, 0, 1, 1, huge)));
    }

    @Test
    void executionCheckpointsRefusalsAndBytesMatchFrozenPrechangeReceipts() {
        var profiles = fixture(0, false);
        var parameters = parameters(1, 1, 1, 1);
        Receipt complete = receipt(profiles, parameters, 1_000_000, 1_000_000, Integer.MAX_VALUE);
        Map<String, Receipt> actual = new TreeMap<>();
        actual.put("complete", complete);
        actual.put("refused-primary", receipt(profiles, parameters, 10, 1_000_000, Integer.MAX_VALUE));
        actual.put("refused-kbest", receipt(profiles, parameters, 150, 1_000_000, Integer.MAX_VALUE));
        actual.put("near-memory-cap", receipt(profiles, parameters, 1_000_000, complete.peak() - 1, Integer.MAX_VALUE));
        actual.put("late-cancel", receipt(profiles, parameters, 1_000_000, 1_000_000, complete.checkpoints() - 1));
        for (var entry : actual.entrySet()) {
            assertEquals(BASELINE.get(entry.getKey()), entry.getValue().toString(), entry.getKey());
        }
    }

    @Test
    void laterOverflowPointRemainsDeferredUntilItsOwnScoreAdmission() {
        var profiles = List.of(profile(0, 0, 0, 0, 1, false, 1),
                profile(1, 1, 0, 0, 1, false, 1),
                profile(2, Double.MAX_VALUE, 0, 1, 0, false, Double.MAX_VALUE));
        for (var parameters : weights()) {
            var refused = receipt(profiles, parameters, 2, 1_000_000, Integer.MAX_VALUE);
            assertEquals("none", refused.failure(), "an unadmitted later state must not validate its point");
            assertTrue(refused.work().contains("forwardBackwardScores=2"));
            assertTrue(refused.work().contains("refusedScoreAttempts=1"));
            var admitted = receipt(profiles, parameters, 3, 1_000_000, Integer.MAX_VALUE);
            if (parameters.orientationWeight() != 0.0 || parameters.turnWeight() != 0.0)
                assertEquals("IllegalArgumentException:Metric coordinates must be finite", admitted.failure());
            else
                assertEquals("none", admitted.failure(), "zero-weight shape terms must not validate points");
        }
    }

    private static void assertGraph(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            MetricRegion region) {
        ProbabilisticInferenceResult exact = solve(profiles, parameters, region, true);
        ProbabilisticInferenceResult eager = solve(profiles, parameters, region, false);
        assertEquals(ProbabilisticInferenceFingerprint.capture(eager), ProbabilisticInferenceFingerprint.capture(exact));
        List<ExpectedPath> expected = new ArrayList<>();
        enumerate(profiles, parameters, region, new int[profiles.size()], 0, expected);
        expected.sort((a, b) -> {
            int energy = Double.compare(a.energy(), b.energy());
            if (energy != 0) return energy;
            for (int index = 0; index < a.states().length; index++) {
                int state = Integer.compare(a.states()[index], b.states()[index]);
                if (state != 0) return state;
            }
            return 0;
        });
        assertEquals(Math.min(32, expected.size()), exact.rawPaths().size());
        for (int index = 0; index < exact.rawPaths().size(); index++) {
            assertArrayEquals(expected.get(index).states(), exact.rawPaths().get(index).stateIndices());
            assertEquals(Double.doubleToRawLongBits(expected.get(index).energy()),
                    Double.doubleToRawLongBits(exact.rawPaths().get(index).energy()));
        }
    }

    private static void enumerate(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            MetricRegion region, int[] states, int depth, List<ExpectedPath> result) {
        if (depth < states.length) {
            for (int state = 0; state < profiles.get(depth).cells().size(); state++) {
                states[depth] = state;
                if (region != null && depth > 0) {
                    MetricPoint a = profiles.get(depth - 1).point(states[depth - 1]);
                    MetricPoint b = profiles.get(depth).point(state);
                    if (!region.contains(a) || !region.contains(b) || !region.containsSegment(a, b)) continue;
                }
                enumerate(profiles, parameters, region, states, depth + 1, result);
            }
            return;
        }
        double energy = FrozenPairLocalEnergyReference.profileEnergy(profiles, 0, states[0], parameters)
                + FrozenPairLocalEnergyReference.profileEnergy(profiles, 1, states[1], parameters)
                + FrozenPairLocalEnergyReference.pairEnergy(profiles, 0, states[0], states[1], parameters);
        for (int index = 2; index < states.length; index++) {
            energy = energy + FrozenPairLocalEnergyReference.increment(profiles, index,
                    states[index - 2], states[index - 1], states[index], parameters);
        }
        result.add(new ExpectedPath(states.clone(), energy));
    }

    private static ProbabilisticInferenceResult solve(List<InferenceProfile> profiles,
            EvidenceModelParameters parameters, MetricRegion region, boolean exact) {
        var ledger = new AttemptMemoryLedger(1_000_000);
        var owner = ledger.rootOwner();
        try {
            return new ProbabilisticInference().solve(profiles, parameters,
                    new TraceBudgets(96, 100_000, 1_000_000, 32, 1), region, CancellationProbe.NONE,
                    owner, exact ? ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2
                            : ProbabilisticExecutionPolicy.Mode.EAGER_V1);
        } finally { owner.close(); assertEquals(0, ledger.currentBytes()); }
    }

    private static Receipt receipt(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            long scores, long bytes, int cancelAt) {
        var ledger = new AttemptMemoryLedger(bytes);
        var owner = ledger.rootOwner();
        int[] calls = {0};
        String fingerprint = "unavailable", work = "unavailable", failure = "none";
        ModernDiagnosticCounters.begin();
        try {
            var result = new ProbabilisticInference().solve(profiles, parameters,
                    new TraceBudgets(96, 100_000, scores, 32, 1), null,
                    () -> ++calls[0] >= cancelAt, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            fingerprint = sha(ProbabilisticInferenceFingerprint.capture(result));
            work = result.workAccounting().toString();
        } catch (RuntimeException exception) {
            failure = exception.getClass().getSimpleName() + ":" + exception.getMessage();
        }
        Map<String, Number> counters = new TreeMap<>(ModernDiagnosticCounters.snapshot());
        counters.keySet().removeIf(name -> name.endsWith("Ms"));
        ModernDiagnosticCounters.end();
        Receipt receipt = new Receipt(fingerprint, work, failure, calls[0], ledger.currentBytes(), ledger.peakBytes(), counters.toString());
        owner.close();
        assertEquals(0, ledger.currentBytes());
        return receipt;
    }

    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static double productionIncrement(List<InferenceProfile> profiles, int index,
            int before, int prior, int state, EvidenceModelParameters parameters) {
        return production("profileEnergy", new Class<?>[] {List.class, int.class, int.class, EvidenceModelParameters.class},
                    profiles, index, state, parameters)
                + production("pairEnergy", new Class<?>[] {List.class, int.class, int.class, int.class, EvidenceModelParameters.class},
                    profiles, index - 1, prior, state, parameters)
                + production("tripleEnergy", new Class<?>[] {List.class, int.class, int.class, int.class, int.class, EvidenceModelParameters.class},
                    profiles, index, before, prior, state, parameters);
    }

    private static double production(String name, Class<?>[] types, Object... arguments) {
        try {
            Method method = ProbabilisticInference.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return (Double) method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String outcome(DoubleSupplier call) {
        try { return "bits=" + Long.toHexString(Double.doubleToRawLongBits(call.getAsDouble())); }
        catch (RuntimeException failure) { return failure.getClass().getName() + ":" + failure.getMessage(); }
    }

    private static List<EvidenceModelParameters> weights() {
        List<EvidenceModelParameters> result = new ArrayList<>();
        for (double data : new double[] {0, 1.25}) for (double guide : new double[] {0, 0.75})
            for (double turn : new double[] {0, 1.5}) for (double orientation : new double[] {0, 0.625})
                result.add(parameters(data, guide, turn, orientation));
        result.add(parameters(-0.0, -0.0, -0.0, -0.0));
        result.add(parameters(1.0, 0.75, Double.MIN_VALUE, Double.MIN_VALUE));
        return result;
    }

    private static EvidenceModelParameters parameters(double data, double guide, double turn, double orientation) {
        return new EvidenceModelParameters("p6-independent", data, 0.5, turn, orientation, guide, 0.5, 1, 0.01);
    }

    private static List<List<InferenceProfile>> fixtures() {
        return List.of(fixture(0, false), fixture(1, false), fixture(2, false));
    }

    private static List<InferenceProfile> fixture(int kind, boolean gap) {
        List<InferenceProfile> result = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            double x = kind == 2 ? -index : kind == 1 ? 0.0 : index;
            double y = kind == 1 ? index : kind == 2 ? (index % 2 == 0 ? -0.0 : 0.0) : 0.0;
            result.add(profile(index, x, y, kind == 1 ? 1 : 0, kind == 1 ? 0 : 1, gap && index == 2, 1));
        }
        return List.copyOf(result);
    }

    private static InferenceProfile profile(double chainage, double x, double y, double nx, double ny,
            boolean gap, double offsetScale) {
        var cells = List.of(new LateralStateCell(-offsetScale, 1, false, true, "a"),
                new LateralStateCell(-0.0, 0.75, false, true, "b"),
                new LateralStateCell(offsetScale, 1.25, false, true, "c"));
        var measured = new ImageOrientationSupport(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                List.of(new ImageOrientationSupport.AngularMode(3 * Math.PI / 4, Math.PI / 4, 0, 1)), 0.75);
        var unknown = ImageOrientationSupport.unknown(ImageOrientationSupport.Status.UNKNOWN_FLAT);
        return new InferenceProfile(chainage, new MetricPoint(x, y), new MetricPoint(nx, ny), cells,
                new double[] {0.07, -0.11, 0.29}, Map.of("a", gap ? unknown : measured, "b", gap ? unknown : measured, "c", unknown),
                Map.of("a", 1.0, "b", 0.5, "c", 0.0),
                gap ? ObservationOwnership.NO_RASTER : ObservationOwnership.DIRECT_TWO_SIDED,
                gap, new double[3][0]).withStructuralGuideCosts(new double[] {0.2, 0.0, 0.9});
    }

    private record ExpectedPath(int[] states, double energy) { }
    private record Receipt(String fingerprint, String work, String failure, int checkpoints,
            long current, long peak, String counters) { }
}

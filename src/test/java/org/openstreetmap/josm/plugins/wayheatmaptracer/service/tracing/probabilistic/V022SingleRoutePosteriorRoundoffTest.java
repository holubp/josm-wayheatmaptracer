package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Valid original one-route fixture from NUM01, independent of the P6 energy tests. */
class V022SingleRoutePosteriorRoundoffTest {
    private static final EvidenceModelParameters ORIGINAL = new EvidenceModelParameters("p6-independent",
            0, 0.5, 0, 0, 0.75, 0.5, 1, 0.01);
    private static final TraceBudgets BUDGETS = new TraceBudgets(96, 100_000, 1_000_000, 32, 1);

    @ParameterizedTest
    @EnumSource(ProbabilisticExecutionPolicy.Mode.class)
    void soleAdmittedRouteHasUnitPosteriorDespiteFiniteLogGroupingRoundoff(
            ProbabilisticExecutionPolicy.Mode mode) {
        var profiles = originalProfiles();
        var parameters = ORIGINAL;
        var region = MetricRegion.rectangle(-1, 0.5, 5, 2);
        // State c is the sole admitted state at EVERY profile. No rival exists.
        for (var profile : profiles) {
            assertFalse(region.contains(profile.point(0)));
            assertFalse(region.contains(profile.point(1)));
            assertTrue(region.contains(profile.point(2)));
        }
        var ledger = new AttemptMemoryLedger(1_000_000);
        var owner = ledger.rootOwner();
        try {
            var result = new ProbabilisticInference().solve(profiles, parameters,
                    new TraceBudgets(96, 100_000, 1_000_000, 32, 1), region,
                    CancellationProbe.NONE, owner, mode);
            assertEquals(ProbabilisticInferenceResult.Status.COMPLETE, result.status());
            assertEquals(1, result.rawPaths().size());
            var path = result.rawPaths().get(0);
            assertArrayEquals(new int[] {2, 2, 2, 2}, path.stateIndices());
            assertEquals(0x4000333333333334L, Double.doubleToRawLongBits(path.energy()));
            assertEquals(0x3fec8ff7c79a9a22L, Double.doubleToRawLongBits(path.logBaseMeasure()));
            assertEquals(1.0, path.conditionalPosteriorMass());
            // Before correction, actual logZ=-1.1324257947431615 (bff21e6a82991958)
            // makes exponent=2^-52 and mass=1+1ULP (3ff0000000000001), rejected
            // by the unchanged strict ProbabilisticPath constructor in both modes.
        } finally {
            owner.close();
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void validOldMassBitsIncludingTinyPositiveExponentAndNonfiniteFailuresAreUntouched() throws Exception {
        try (var prepared = new Prepared()) {
            Object messages = withComponent(prepared.messages, "logPartition", 0.0);
            for (double[] values : new double[][] {
                    {0, Double.MIN_VALUE}, {0, -Double.MIN_VALUE}, {0, -StrictMath.log(2)},
                    {1000, 0}, {Double.NaN, 0}, {Double.NEGATIVE_INFINITY, 0},
                    {Double.POSITIVE_INFINITY, 0}}) {
                var record = prepared.arena.start(0, values[0], values[1]);
                double oldMass = StrictMath.exp(-values[0] / ORIGINAL.temperature() + values[1]);
                double actual = mass(record, new int[] {0}, List.of(), ORIGINAL, messages,
                        () -> { fail("valid or nonfinite old mass must not replay/checkpoint"); return true; });
                assertEquals(Double.doubleToRawLongBits(oldMass), Double.doubleToRawLongBits(actual));
            }
            assertEquals(1.0, StrictMath.exp(Double.MIN_VALUE),
                    "positive exponent alone must not enter the repair path");
        }
    }

    @Test
    void inconsistentStoredScoresAndEveryForwardCertificateFailClosed() throws Exception {
        for (String corruption : List.of("energy", "logMeasure", "initial", "later", "nan", "terminal")) {
            try (var prepared = new Prepared()) {
                Object messages = prepared.messages;
                if (corruption.equals("energy") || corruption.equals("logMeasure")) {
                    var field = ProbabilisticInference.PathRecord.class.getDeclaredField(corruption);
                    field.setAccessible(true);
                    double old = field.getDouble(prepared.record);
                    field.setDouble(prepared.record, corruption.equals("energy")
                            ? Math.nextDown(old) : Math.nextUp(old));
                } else if (corruption.equals("terminal")) {
                    messages = withComponent(messages, "logPartition",
                            Math.nextDown((Double) invoke(messages, "logPartition")));
                } else {
                    @SuppressWarnings("unchecked")
                    var forward = (List<double[][]>) invoke(messages, "forward");
                    int stage = corruption.equals("initial") ? 0 : 1;
                    double old = forward.get(stage)[2][2];
                    forward.get(stage)[2][2] = corruption.equals("nan") ? Double.NaN : Math.nextDown(old);
                }
                double oldMass = StrictMath.exp(-prepared.record.energy() / ORIGINAL.temperature()
                        + prepared.record.logMeasure() - (Double) invoke(messages, "logPartition"));
                assertTrue(Double.isFinite(oldMass) && oldMass > 1.0, corruption);
                double actual = mass(prepared.record, prepared.record.states(), prepared.profiles,
                        ORIGINAL, messages, CancellationProbe.NONE);
                assertEquals(Double.doubleToRawLongBits(oldMass), Double.doubleToRawLongBits(actual), corruption);
                assertThrows(IllegalArgumentException.class, () -> new ProbabilisticPath(
                        new int[] {0}, List.of(new MetricPoint(0, 0)), "strict-guard", 0, 0, actual));
            }
        }
    }

    @Test
    void exceptionalReplayChecksEachStageAndCancellationReleasesAllPublicSolveOwners() throws Exception {
        try (var prepared = new Prepared()) {
            AtomicInteger replayCalls = new AtomicInteger();
            assertEquals(1.0, mass(prepared.record, prepared.record.states(), prepared.profiles,
                    ORIGINAL, prepared.messages, () -> { replayCalls.incrementAndGet(); return false; }));
            assertEquals(3, replayCalls.get(), "initial pair plus two later forward stages");
        }
        AtomicInteger completeCalls = new AtomicInteger();
        solve(originalProfiles(), ORIGINAL, MetricRegion.rectangle(-1, 0.5, 5, 2),
                () -> { completeCalls.incrementAndGet(); return false; }, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
        // Materialization is the final cancellation-bearing phase of this solve.
        for (int stage = 0; stage < 3; stage++) {
            int cancelAt = completeCalls.get() - 2 + stage;
            AtomicInteger calls = new AtomicInteger();
            var ledger = new AttemptMemoryLedger(1_000_000);
            var owner = ledger.rootOwner();
            try {
                assertThrows(CancellationException.class, () -> new ProbabilisticInference().solve(
                        originalProfiles(), ORIGINAL, BUDGETS, MetricRegion.rectangle(-1, 0.5, 5, 2),
                        () -> calls.incrementAndGet() >= cancelAt, owner,
                        ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2));
                assertEquals(cancelAt, calls.get());
                assertEquals(0, ledger.currentBytes(), "cancelled replay releases partial materialization");
            } finally { owner.close(); assertEquals(0, ledger.currentBytes()); }
        }
    }

    @Test
    void constructorStillRejectsInvalidPosteriorRatherThanClamping() {
        for (double invalid : new double[] {Math.nextUp(1.0), 2, -Double.MIN_VALUE,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new ProbabilisticPath(new int[] {0},
                    List.of(new MetricPoint(0, 0)), "strict-guard", 0, 0, invalid));
        }
    }

    @Test
    void singlePairAndLongerGraphsKeepIndependentUnequalMeasureMultiRoutePosteriors() {
        var parameters = new EvidenceModelParameters("independent-posterior", 1, 0, 0, 0, 0, 1, 1, 0);
        for (var mode : ProbabilisticExecutionPolicy.Mode.values()) for (int count : new int[] {1, 2, 4}) {
            for (double shift : new double[] {-200, 0, 200}) for (boolean dominant : List.of(false, true)) {
                List<InferenceProfile> profiles = new ArrayList<>();
                for (int index = 0; index < count; index++) {
                    profiles.add(new InferenceProfile(index, new MetricPoint(index, 0), new MetricPoint(0, 1),
                            List.of(new LateralStateCell(-1, 0.75, false, true, "left"),
                                    new LateralStateCell(1, 1.25, false, true, "right")),
                            new double[] {shift + 0.11, shift + (dominant ? 100 : 0.37) + index * 0.03},
                            List.of(), 0, ObservationOwnership.DIRECT_TWO_SIDED, false, new double[2][0]));
                }
                var result = solve(profiles, parameters, null, CancellationProbe.NONE, mode);
                double[] weights = new double[1 << count];
                double total = 0;
                for (int path = 0; path < weights.length; path++) {
                    double weight = 1;
                    for (int index = 0; index < count; index++) {
                        int state = (path >>> index) & 1;
                        double q = count == 1 ? 1 : index == 0 || index == count - 1 ? 0.5 : 1;
                        double cost = shift + (state == 0 ? 0.11 : (dominant ? 100 : 0.37) + index * 0.03);
                        weight *= (state == 0 ? 0.75 : 1.25) * StrictMath.exp(-q * cost);
                    }
                    weights[path] = weight; total += weight;
                }
                assertEquals(weights.length, result.rawPaths().size());
                double sum = 0;
                for (var path : result.rawPaths()) {
                    int code = 0;
                    for (int index = 0; index < count; index++) code |= path.stateIndices()[index] << index;
                    double expected = weights[code] / total;
                    assertEquals(expected, path.conditionalPosteriorMass(), 1e-12);
                    assertTrue(path.conditionalPosteriorMass() >= 0 && path.conditionalPosteriorMass() <= 1);
                    sum += path.conditionalPosteriorMass();
                }
                assertEquals(1, sum, 1e-12);
                for (int index = 0; index < count; index++) {
                    double expected = 0;
                    for (int path = 0; path < weights.length; path++) {
                        if (((path >>> index) & 1) == 0) expected += weights[path] / total;
                    }
                    assertEquals(expected, result.positionMarginals().get(index)[0], 1e-12);
                }
            }
        }
    }

    private static ProbabilisticInferenceResult solve(List<InferenceProfile> profiles,
            EvidenceModelParameters parameters, MetricRegion region, CancellationProbe cancellation,
            ProbabilisticExecutionPolicy.Mode mode) {
        var ledger = new AttemptMemoryLedger(1_000_000);
        var owner = ledger.rootOwner();
        try { return new ProbabilisticInference().solve(profiles, parameters, BUDGETS, region, cancellation, owner, mode); }
        finally { owner.close(); assertEquals(0, ledger.currentBytes()); }
    }

    private static double mass(ProbabilisticInference.PathRecord record, int[] states,
            List<InferenceProfile> profiles, EvidenceModelParameters parameters, Object messages,
            CancellationProbe cancellation) throws Exception {
        return (Double) invoke(ProbabilisticInference.class, "materializedPosteriorMass",
                record, states, profiles, parameters, messages, cancellation);
    }

    private static Object invoke(Object receiver, String name, Object... arguments) throws Exception {
        Class<?> type = receiver instanceof Class<?> cls ? cls : receiver.getClass();
        var method = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow();
        method.setAccessible(true);
        try { return method.invoke(receiver instanceof Class<?> ? null : receiver, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }

    private static Object withComponent(Object record, String name, Object replacement) throws Exception {
        var components = record.getClass().getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int index = 0; index < components.length; index++) {
            values[index] = components[index].getName().equals(name) ? replacement
                    : invoke(record, components[index].getName());
            types[index] = components[index].getType();
        }
        var constructor = record.getClass().getDeclaredConstructor(types); constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    /** Actual native private stages, without materialization, for narrow corruption tests. */
    private static final class Prepared implements AutoCloseable {
        final AttemptMemoryLedger ledger = new AttemptMemoryLedger(1_000_000);
        final AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        final List<InferenceProfile> profiles = originalProfiles();
        final ProbabilisticInference.PathArena arena;
        final Object messages;
        final Object kbest;
        final ProbabilisticInference.PathRecord record;

        Prepared() throws Exception {
            Class<?> type = Class.forName(ProbabilisticInference.class.getName() + "$WorkCounter");
            var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            Object counter = constructor.newInstance(ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2,
                    BUDGETS.maximumTransitions());
            var admissions = new TransitionAdmissionMemo(32768, owner.child("admissions"));
            var region = MetricRegion.rectangle(-1, 0.5, 5, 2);
            messages = invoke(ProbabilisticInference.class, "forwardBackward", profiles, ORIGINAL,
                    BUDGETS, region, CancellationProbe.NONE, admissions, owner.child("messages"), counter);
            kbest = invoke(ProbabilisticInference.class, "enumerateKBest", profiles, ORIGINAL,
                    BUDGETS, region, CancellationProbe.NONE, admissions, owner.child("kbest"), counter);
            invoke(kbest, "releaseFrontier");
            record = (ProbabilisticInference.PathRecord) ((List<?>) invoke(kbest, "paths")).get(0);
            arena = new ProbabilisticInference.PathArena(owner.child("synthetic-old-records"));
        }

        public void close() throws Exception {
            invoke(kbest, "close"); arena.close(); owner.close(); assertEquals(0, ledger.currentBytes());
        }
    }

    private static List<InferenceProfile> originalProfiles() {
        var measured = new ImageOrientationSupport(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                List.of(new ImageOrientationSupport.AngularMode(3 * Math.PI / 4, Math.PI / 4, 0, 1)), 0.75);
        var unknown = ImageOrientationSupport.unknown(ImageOrientationSupport.Status.UNKNOWN_FLAT);
        List<InferenceProfile> result = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            boolean gap = index == 2;
            var cells = List.of(new LateralStateCell(-1, 1, false, true, "a"),
                    new LateralStateCell(-0.0, 0.75, false, true, "b"),
                    new LateralStateCell(1, 1.25, false, true, "c"));
            result.add(new InferenceProfile(index, new MetricPoint(index, 0), new MetricPoint(0, 1),
                    cells, new double[] {0.07, -0.11, 0.29},
                    Map.of("a", gap ? unknown : measured, "b", gap ? unknown : measured, "c", unknown),
                    Map.of("a", 1.0, "b", 0.5, "c", 0.0),
                    gap ? ObservationOwnership.NO_RASTER : ObservationOwnership.DIRECT_TWO_SIDED,
                    gap, new double[3][0]).withStructuralGuideCosts(new double[] {0.2, 0.0, 0.9}));
        }
        return List.copyOf(result);
    }
}

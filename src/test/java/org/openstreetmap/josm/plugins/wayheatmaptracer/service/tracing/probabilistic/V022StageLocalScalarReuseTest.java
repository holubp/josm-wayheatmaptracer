package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

class V022StageLocalScalarReuseTest {
    private static final int PROFILES = 5;
    private static final int STATES = 4;
    private static final TraceBudgets BUDGETS = new TraceBudgets(96, 1_000_000L, 1_000_000L, 32, 1);
    private static final ImageOrientationSupport ORIENTATION = new ImageOrientationSupport(
            ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
            List.of(new ImageOrientationSupport.AngularMode(0.0, 0.0, 0.0, 1.0)), 0.8);

    @Test
    void fourStateStagesReportRepeatedPairAndFirstHeadingCalculations() {
        Execution run = execute(profiles(PROFILES, STATES, false), EvidenceModelParameters.defaults(),
                MetricRegion.rectangle(-20.0, -20.0, 30.0, 30.0), BUDGETS,
                AttemptMemoryLedger.MAX_BYTES, CancellationProbe.NONE);

        assertTrue(run.result().workAccounting().isPresent());
        assertEquals(run.result().evaluatedTransitions(),
                run.result().workAccounting().orElseThrow().computedScores());
        assertTrue(count(run, "optimization.scalarCache.forwardBackwardPairCalculations") > 0,
                "the activated forward/backward cache reports actual pair calculations");
        assertTrue(count(run, "optimization.scalarCache.forwardBackwardPairReuses") >= 4,
                "four before-states provide at least four potential pair reuse opportunities");
        assertTrue(count(run, "optimization.scalarCache.kBestFirstHeadingCalculations") > 0,
                "the activated K-best cache reports actual first-heading calculations");
        assertTrue(count(run, "optimization.scalarCache.kBestFirstHeadingReuses") >= 4,
                "four destination states provide at least four potential heading reuse opportunities");
        assertEquals(0L, run.retainedBytesAfterClose());
    }

    @Test
    void zeroShapeWeightsDoNotCreateOrReportScalarCaches() {
        Execution run = execute(profiles(PROFILES, STATES, false), EvidenceModelParameters.withoutShapeTerms(),
                null, BUDGETS, AttemptMemoryLedger.MAX_BYTES, CancellationProbe.NONE);

        assertTrue(run.result().workAccounting().isPresent());
        assertFalse(run.counters().keySet().stream().anyMatch(key -> key.startsWith("optimization.scalarCache.")));
        assertEquals(0L, run.retainedBytesAfterClose());
    }

    @Test
    void orientationOnlyAndTurnOnlyStagesUseTheirOwnScalarCache() {
        List<InferenceProfile> profiles = profiles(PROFILES, STATES, false);
        Execution orientationOnly = execute(profiles, parameters(1.0, 0.0), null, BUDGETS,
                AttemptMemoryLedger.MAX_BYTES, CancellationProbe.NONE);
        assertTrue(count(orientationOnly, "optimization.scalarCache.forwardBackwardPairCalculations") > 0);
        assertEquals(0L, count(orientationOnly, "optimization.scalarCache.kBestFirstHeadingCalculations"));

        Execution turnOnly = execute(profiles, parameters(0.0, 1.0), null, BUDGETS,
                AttemptMemoryLedger.MAX_BYTES, CancellationProbe.NONE);
        assertTrue(count(turnOnly, "optimization.scalarCache.forwardBackwardPairCalculations") > 0,
                "turn-only forward/backward caches still calculate admitted second headings");
        assertTrue(count(turnOnly, "optimization.scalarCache.kBestFirstHeadingCalculations") > 0);
    }

    @Test
    void scalarTableReservationRefusalFallsBackWithoutAllocationAndClosedOwnerIsNotFallback() {
        long forwardBytes = forwardPairTableBytes(4, 4);
        AttemptMemoryLedger refusedLedger = new AttemptMemoryLedger(forwardBytes - 1L);
        AttemptMemoryLedger.Owner refusedOwner = refusedLedger.rootOwner().child("refused-cache");
        assertTrue(createScalarTable(refusedOwner, 4, 4, false) == null,
                "a byte-short reservation disables this cache without allocating its arrays");
        assertEquals(0L, refusedLedger.currentBytes());
        refusedOwner.close();
        assertEquals(0L, refusedLedger.currentBytes());

        AttemptMemoryLedger exactLedger = new AttemptMemoryLedger(forwardBytes);
        AttemptMemoryLedger.Owner exactOwner = exactLedger.rootOwner().child("exact-cache");
        Object exactTable = createScalarTable(exactOwner, 4, 4, false);
        assertTrue(exactTable != null);
        assertEquals(forwardBytes, exactLedger.currentBytes());
        closeScalarTable(exactTable);
        exactOwner.close();
        assertEquals(0L, exactLedger.currentBytes());
        assertThrows(IllegalStateException.class, () -> createScalarTable(exactOwner, 4, 4, false),
                "a closed owner must fail closed instead of silently disabling the cache");
    }

    @Test
    void smallFourStateGraphMatchesIndependentFrozenEnergyEnumeration() {
        List<InferenceProfile> profiles = profiles(PROFILES, STATES, false);
        for (EvidenceModelParameters parameters : List.of(
                parameters(1.0, 0.0), parameters(0.0, 1.0), EvidenceModelParameters.defaults())) {
            Execution run = execute(profiles, parameters, null, BUDGETS,
                    AttemptMemoryLedger.MAX_BYTES, CancellationProbe.NONE);
            List<ExpectedPath> expected = enumerate(profiles, parameters);
            expected.sort(Comparator.comparingDouble(ExpectedPath::energy)
                    .thenComparing(ExpectedPath::states, V022StageLocalScalarReuseTest::lexicographic));
            ProbabilisticPath map = run.result().mapPath().orElseThrow();
            assertArrayEquals(expected.get(0).states(), map.stateIndices());
            assertEquals(Double.doubleToRawLongBits(expected.get(0).energy()),
                    Double.doubleToRawLongBits(map.energy()));
            assertEquals(expectedLogPartition(expected, parameters.temperature()),
                    run.result().logPartition(), 1e-11);
            assertEquals(run.result().evaluatedTransitions(),
                    run.result().workAccounting().orElseThrow().computedScores());
        }
    }

    @Test
    void cancellationDuringForwardAndKBestReleasesEveryStageOwner() {
        AtomicReference<Map<String, Number>> forwardCounters = new AtomicReference<>();
        assertThrows(CancellationException.class, () -> execute(profiles(4, STATES, false),
                EvidenceModelParameters.defaults(), null, BUDGETS, AttemptMemoryLedger.MAX_BYTES,
                cancellationAt(4), forwardCounters));
        assertTrue(count(forwardCounters.get(), "optimization.scalarCache.forwardBackwardPairCalculations") > 0,
                "forward cancellation happens after an activated stage cache was used");
        AtomicReference<Map<String, Number>> kBestCounters = new AtomicReference<>();
        assertThrows(CancellationException.class, () -> execute(profiles(4, STATES, false),
                EvidenceModelParameters.defaults(), null, BUDGETS, AttemptMemoryLedger.MAX_BYTES,
                cancellationAt(8), kBestCounters));
        assertTrue(count(kBestCounters.get(), "optimization.scalarCache.kBestFirstHeadingCalculations") > 0,
                "K-best cancellation happens after an activated stage cache was used");
    }

    @Test
    void cancellationReleasesExactlyTheForwardAndKBestPrimitiveCachePayloads() {
        AtomicLong activeForwardBytes = new AtomicLong();
        AtomicLong noCacheForwardBytes = new AtomicLong();
        assertThrows(CancellationException.class, () -> execute(profiles(4, 16, false),
                parameters(1.0, 1.0), null, BUDGETS, AttemptMemoryLedger.MAX_BYTES,
                cancellationAt(4), null, activeForwardBytes));
        assertThrows(CancellationException.class, () -> execute(profiles(4, 16, false),
                EvidenceModelParameters.withoutShapeTerms(), null, BUDGETS, AttemptMemoryLedger.MAX_BYTES,
                cancellationAt(4), null, noCacheForwardBytes));
        assertEquals(forwardPairTableBytes(16, 16), activeForwardBytes.get() - noCacheForwardBytes.get());

        AtomicLong activeKBestBytes = new AtomicLong();
        AtomicLong noCacheKBestBytes = new AtomicLong();
        AtomicReference<Map<String, Number>> activeKBestCounters = new AtomicReference<>();
        AtomicBoolean activeAtKBestOffer = new AtomicBoolean();
        AtomicBoolean noCacheAtKBestOffer = new AtomicBoolean();
        assertThrows(CancellationException.class, () -> execute(profiles(4, 16, false),
                parameters(1.0, 1.0), null, BUDGETS, AttemptMemoryLedger.MAX_BYTES,
                cancellationAt(24, activeAtKBestOffer), activeKBestCounters, activeKBestBytes));
        assertThrows(CancellationException.class, () -> execute(profiles(4, 16, false),
                EvidenceModelParameters.withoutShapeTerms(), null, BUDGETS, AttemptMemoryLedger.MAX_BYTES,
                cancellationAt(24, noCacheAtKBestOffer), null, noCacheKBestBytes));
        assertTrue(activeAtKBestOffer.get(), "active-cache cancellation reaches the first K-best offer");
        assertTrue(noCacheAtKBestOffer.get(), "comparison cancellation reaches the same K-best offer phase");
        assertTrue(count(activeKBestCounters.get(),
                "optimization.scalarCache.kBestFirstHeadingCalculations") > 0,
                "the K-best table performed scalar work before cancellation");
        assertEquals(kBestHeadingTableBytes(16, 16), activeKBestBytes.get() - noCacheKBestBytes.get());
    }

    @Test
    void transitionRefusalAndLazyInvalidPointValidationKeepTheirBoundaries() {
        TraceBudgets tinyTransitionBudget = new TraceBudgets(96, 1_000_000L, 1L, 32, 1);
        Execution refused = execute(profiles(PROFILES, STATES, false), EvidenceModelParameters.defaults(),
                null, new TraceBudgets(96, 1_000_000L, 15L, 32, 1),
                AttemptMemoryLedger.MAX_BYTES, CancellationProbe.NONE);
        assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, refused.result().status());
        assertTrue(refused.result().explanation().contains("transition budget exceeded"));
        assertTrue(count(refused, "optimization.scalarCache.forwardBackwardPairCalculations") > 0,
                "the refusal follows actual forward/backward cache use");
        assertEquals(0L, refused.retainedBytesAfterClose());

        Execution kBestRefused = execute(profiles(PROFILES, STATES, false), EvidenceModelParameters.defaults(),
                null, new TraceBudgets(96, 1_000_000L, 385L, 32, 1),
                AttemptMemoryLedger.MAX_BYTES, CancellationProbe.NONE);
        assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, kBestRefused.result().status());
        assertTrue(kBestRefused.result().explanation().contains("transition budget exceeded during k-best"));
        assertTrue(count(kBestRefused, "optimization.scalarCache.kBestFirstHeadingCalculations") > 0,
                "the refusal follows actual K-best cache use");
        assertEquals(0L, kBestRefused.retainedBytesAfterClose());

        assertThrows(IllegalArgumentException.class, () -> execute(profiles(PROFILES, STATES, true),
                EvidenceModelParameters.defaults(), null, BUDGETS, AttemptMemoryLedger.MAX_BYTES,
                CancellationProbe.NONE));

        assertThrows(IllegalArgumentException.class, () -> execute(profiles(PROFILES, STATES, true),
                EvidenceModelParameters.defaults(), MetricRegion.rectangle(100.0, 100.0, 110.0, 110.0),
                BUDGETS, AttemptMemoryLedger.MAX_BYTES, CancellationProbe.NONE));
        Execution unusedPoint = execute(profiles(PROFILES, STATES, true),
                EvidenceModelParameters.withoutShapeTerms(), null, BUDGETS,
                AttemptMemoryLedger.MAX_BYTES, CancellationProbe.NONE);
        assertTrue(unusedPoint.result().workAccounting().isPresent());
        assertFalse(unusedPoint.counters().keySet().stream().anyMatch(
                key -> key.startsWith("optimization.scalarCache.")));
    }

    private static Execution execute(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            MetricRegion region, TraceBudgets budgets, long limit, CancellationProbe cancellation) {
        return execute(profiles, parameters, region, budgets, limit, cancellation, null, null);
    }

    private static Execution execute(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            MetricRegion region, TraceBudgets budgets, long limit, CancellationProbe cancellation,
            AtomicReference<Map<String, Number>> countersAfterExit) {
        return execute(profiles, parameters, region, budgets, limit, cancellation, countersAfterExit, null);
    }

    private static Execution execute(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            MetricRegion region, TraceBudgets budgets, long limit, CancellationProbe cancellation,
            AtomicReference<Map<String, Number>> countersAfterExit, AtomicLong bytesAtCancellation) {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(limit);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        ModernDiagnosticCounters.begin();
        CancellationProbe observedCancellation = () -> {
            boolean cancel = cancellation.cancelled();
            if (cancel && bytesAtCancellation != null) bytesAtCancellation.set(ledger.currentBytes());
            return cancel;
        };
        try {
            ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles, parameters,
                    budgets, region, observedCancellation, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            return new Execution(result, ModernDiagnosticCounters.snapshot(), ledger.peakBytes(),
                    ledger.currentBytes(), ledger);
        } finally {
            owner.close();
            if (countersAfterExit != null) countersAfterExit.set(ModernDiagnosticCounters.snapshot());
            ModernDiagnosticCounters.end();
            assertEquals(0L, ledger.currentBytes(), "cancellation, refusal and normal exit release all owners");
        }
    }

    private static List<InferenceProfile> profiles(int profileCount, int stateCount, boolean invalidLastState) {
        List<InferenceProfile> profiles = new ArrayList<>(profileCount);
        for (int profile = 0; profile < profileCount; profile++) {
            double chainage = profile * 2.0;
            List<LateralStateCell> cells = new ArrayList<>(stateCount);
            double[] unary = new double[stateCount];
            for (int state = 0; state < stateCount; state++) {
                double offset = state - (stateCount - 1) * 0.5;
                if (invalidLastState && profile == 2 && state == stateCount - 1) {
                    offset = 1e308;
                }
                cells.add(new LateralStateCell(offset, 1.0, false, true, "main"));
                double centered = state - (stateCount - 1) * 0.5;
                unary[state] = invalidLastState && profile == 2 && state == stateCount - 1
                        ? 1000.0 : 0.01 * centered * centered + 0.0001 * ((profile * 7 + state * 3) % 5);
            }
            MetricPoint anchor = invalidLastState && profile == 2
                    ? new MetricPoint(chainage, 1e308)
                    : new MetricPoint(chainage, 0.2 * profile * profile);
            profiles.add(new InferenceProfile(chainage, anchor, new MetricPoint(0.0, 1.0), cells, unary,
                    Map.of("main", ORIENTATION), Map.of("main", 0.75),
                    ObservationOwnership.DIRECT_TWO_SIDED, false, new double[stateCount][0]));
        }
        return List.copyOf(profiles);
    }

    private static CancellationProbe cancellationAt(int target) {
        AtomicInteger calls = new AtomicInteger();
        return () -> calls.incrementAndGet() == target;
    }

    private static CancellationProbe cancellationAt(int target, AtomicBoolean inKBest) {
        AtomicInteger calls = new AtomicInteger();
        return () -> {
            if (calls.incrementAndGet() != target) return false;
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (frame.getMethodName().equals("enumerateKBest")) {
                    inKBest.set(true);
                    break;
                }
            }
            return true;
        };
    }

    private static long count(Execution run, String key) {
        return count(run.counters(), key);
    }

    private static long count(Map<String, Number> counters, String key) {
        return counters.getOrDefault(key, 0L).longValue();
    }

    private static EvidenceModelParameters parameters(double orientation, double turn) {
        return new EvidenceModelParameters("stage-local-scalar-cache-test", 1.0, 1.0,
                turn, orientation, 0.0, 0.5, 1.0, 0.0);
    }

    private static List<ExpectedPath> enumerate(List<InferenceProfile> profiles,
            EvidenceModelParameters parameters) {
        List<ExpectedPath> paths = new ArrayList<>();
        enumerate(profiles, parameters, new int[profiles.size()], 0, paths);
        return paths;
    }

    private static void enumerate(List<InferenceProfile> profiles, EvidenceModelParameters parameters,
            int[] states, int depth, List<ExpectedPath> paths) {
        if (depth < states.length) {
            for (int state = 0; state < profiles.get(depth).cells().size(); state++) {
                states[depth] = state;
                enumerate(profiles, parameters, states, depth + 1, paths);
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
        paths.add(new ExpectedPath(states.clone(), energy));
    }

    private static double expectedLogPartition(List<ExpectedPath> paths, double temperature) {
        double maximum = paths.stream().mapToDouble(path -> -path.energy() / temperature)
                .max().orElseThrow();
        double total = 0.0;
        for (ExpectedPath path : paths) total += StrictMath.exp(-path.energy() / temperature - maximum);
        return maximum + StrictMath.log(total);
    }

    private static int lexicographic(int[] first, int[] second) {
        for (int index = 0; index < first.length; index++) {
            int compared = Integer.compare(first[index], second[index]);
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static long forwardPairTableBytes(int priorStates, int destinationStates) {
        long cells = (long) priorStates * destinationStates;
        return AttemptMemoryLedger.objectBytes(40)
                + 2L * AttemptMemoryLedger.arrayBytes(cells, Double.BYTES)
                + AttemptMemoryLedger.arrayBytes(cells, Byte.BYTES);
    }

    private static long kBestHeadingTableBytes(int beforeStates, int priorStates) {
        long cells = (long) beforeStates * priorStates;
        return AttemptMemoryLedger.objectBytes(40)
                + AttemptMemoryLedger.arrayBytes(cells, Double.BYTES)
                + AttemptMemoryLedger.arrayBytes(cells, Byte.BYTES);
    }

    private static Object createScalarTable(AttemptMemoryLedger.Owner owner, int rows, int columns,
            boolean kBest) {
        try {
            Class<?> type = Class.forName(ProbabilisticInference.class.getName() + "$ScalarReuseTable");
            Method create = type.getDeclaredMethod("create", AttemptMemoryLedger.Owner.class,
                    int.class, int.class, boolean.class);
            create.setAccessible(true);
            return create.invoke(null, owner, rows, columns, kBest);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void closeScalarTable(Object table) {
        try {
            Method close = table.getClass().getDeclaredMethod("close");
            close.setAccessible(true);
            close.invoke(table);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private record ExpectedPath(int[] states, double energy) { }

    private record Execution(ProbabilisticInferenceResult result, Map<String, Number> counters,
            long peakBytes, long retainedBytes, AttemptMemoryLedger ledger) {
        private long retainedBytesAfterClose() {
            return ledger.currentBytes();
        }
    }
}

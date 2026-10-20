package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

/** Deterministic resource/failure controls; the only four-state row is the approved 2x1x4 exception. */
class V022CrossStreamFailureTest {
    private static final EvidenceModelParameters TURN = new EvidenceModelParameters(
            "cross-stream-failure", 1, 0, 1, 0, 0, 1, 1, 0);
    private static final String INFERENCE = ProbabilisticInference.class.getName();
    private static final String BOUND = CrossStreamDominance.class.getName();

    @Test
    void optionalCacheAdmissionPrecedesBoundsAndRefusalAllowsUncachedContinuation() {
        // Independent actual ScalarReuseTable layout: object(40) + double[2] + byte[2].
        long cacheBytes = AttemptMemoryLedger.objectBytes(40)
                + AttemptMemoryLedger.arrayBytes(2, Double.BYTES) + AttemptMemoryLedger.arrayBytes(2, Byte.BYTES);
        assertEquals(112, cacheBytes);
        CacheReceipt admitted = cacheAttempt(false, cacheBytes);
        CacheReceipt refused = cacheAttempt(true, cacheBytes);
        assertEquals(admitted.result().result().rawPaths(), refused.result().result().rawPaths());
        assertEquals(admitted.result().result().completion(), refused.result().result().completion());
        assertEquals(Double.doubleToRawLongBits(admitted.result().result().logPartition()),
                Double.doubleToRawLongBits(refused.result().result().logPartition()));
        assertEquals(112, admitted.queryBytes() - admitted.pairBytes(), "cache is already owned at the first bound poll");
        assertEquals(0, refused.queryBytes() - refused.pairBytes(), "optional refusal is not a cache allocation");
        assertTrue(refused.heldAndReleased());
        assertEquals(4, admitted.result().boundWork().skippedStreams());
        assertEquals(4, refused.result().boundWork().skippedStreams(), "refused optional cache still permits a valid skip");
        assertEquals(1, admitted.result().boundWork().firstHeadingEvaluations());
        assertEquals(4, refused.result().boundWork().firstHeadingEvaluations());
        assertEquals(1, admitted.counters().get("optimization.scalarCache.kBestFirstHeadingCalculations").longValue());
        assertEquals(3, admitted.counters().get("optimization.scalarCache.kBestFirstHeadingReuses").longValue());
        assertFalse(refused.counters().containsKey("optimization.scalarCache.kBestFirstHeadingCalculations"));
    }

    private record CacheReceipt(CrossStreamInferenceResult result, long pairBytes, long queryBytes,
            boolean heldAndReleased, Map<String, Number> counters) { }

    private static CacheReceipt cacheAttempt(boolean refuse, long cacheBytes) {
        var ledger = new AttemptMemoryLedger(1_000_000);
        var graph = cacheGraph();
        var held = new AttemptMemoryLedger.Reservation[1];
        long[] pairBytes = {-1}, queryBytes = {-1};
        boolean[] released = {false};
        ModernDiagnosticCounters.begin();
        try (var owner = ledger.rootOwner(); var caller = owner.reserve(7)) {
            CancellationProbe probe = () -> {
                // Exact named source seam: after three points and the cache attempt,
                // before any certificate. No wall clock, sleep or ordinal guess.
                if (queryBytes[0] < 0 && in(BOUND, "strictlyDominatedRaw1")) {
                    if (held[0] != null) { held[0].close(); held[0] = null; released[0] = true; }
                    queryBytes[0] = owner.currentBytes();
                }
                return false;
            };
            AdjacentStatePotential potential = (stage, left, right) -> {
                if (pairBytes[0] < 0 && stage == 1 && in(INFERENCE, "enumerateKBest")) {
                    pairBytes[0] = owner.currentBytes();
                    if (refuse) held[0] = owner.reserve(ledger.limitBytes() - owner.currentBytes() - (cacheBytes - 1));
                }
                return 0;
            };
            try {
                var result = new ProbabilisticInference().solveCrossStreamRaw1(graph, TURN, budgets(128_000_000),
                        null, probe, owner, potential);
                assertEquals(0, result.result().workAccounting().orElseThrow().refusedScoreAttempts());
                assertTrue(pairBytes[0] >= 7 && queryBytes[0] >= 7);
                assertTrue(owner.currentBytes() < 20_000, "temporary pressure and inference workspace are closed");
                return new CacheReceipt(result, pairBytes[0], queryBytes[0], released[0], ModernDiagnosticCounters.snapshot());
            } finally { if (held[0] != null) held[0].close(); }
        } finally {
            ModernDiagnosticCounters.end();
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void allThreeLazyPointValidationsPrecedeCertificateOrSkip() throws Throwable {
        // FB would reject an overflowing materialized point before K-best. Invoke the
        // exact shared private K-best seam to discriminate its own lazy-use ordering.
        for (int invalidRow = 0; invalidRow < 3; invalidRow++) {
            var graph = new ArrayList<>(cacheGraph());
            var cells = new ArrayList<LateralStateCell>(graph.get(invalidRow).cells());
            int invalidState = invalidRow == 0 ? 1 : 0;
            var cell = cells.get(invalidState);
            cells.set(invalidState, new LateralStateCell(Double.MAX_VALUE, 1, false, true, cell.branchLabel()));
            double[] costs = invalidRow == 0 ? new double[] {0, 100} : new double[cells.size()];
            graph.set(invalidRow, new InferenceProfile(invalidRow, new MetricPoint(invalidRow, Double.MAX_VALUE),
                    new MetricPoint(0, 1), cells, costs, List.of(), 0, ObservationOwnership.DIRECT_TWO_SIDED, false));
            var ledger = new AttemptMemoryLedger(1_000_000);
            try (var owner = ledger.rootOwner(); var caller = owner.reserve(7); var work = owner.child("test-kbest")) {
                var state = CrossStreamDominance.State.allocate(work);
                assertThrows(IllegalArgumentException.class, () -> invokeKBest(graph, work, state));
                assertEquals(invalidRow == 0 ? 1 : 0, state.boundQueries,
                        "a losing invalid before-point must fail before its second query can be admitted");
                assertEquals(0, state.stageCertificates);
                assertEquals(0, state.skippedStreams);
                work.close(); assertEquals(7, owner.currentBytes());
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    private static void invokeKBest(List<InferenceProfile> graph, AttemptMemoryLedger.Owner owner,
            CrossStreamDominance.State state) throws Throwable {
        Class<?> counterType = Class.forName(INFERENCE + "$WorkCounter");
        var constructor = counterType.getDeclaredConstructor(ProbabilisticExecutionPolicy.Mode.class, long.class);
        constructor.setAccessible(true);
        try (var reservation = owner.reserve(AttemptMemoryLedger.objectBytes(80))) {
            Object counter = constructor.newInstance(ProbabilisticExecutionPolicy.Mode.EXACT_CROSS_STREAM_RAW1_V3, 128_000_000L);
            reservation.adopt(counter);
            var method = ProbabilisticInference.class.getDeclaredMethod("enumerateKBest", List.class,
                    EvidenceModelParameters.class, TraceBudgets.class,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion.class,
                    CancellationProbe.class, TransitionAdmission.class, AttemptMemoryLedger.Owner.class,
                    counterType, AdjacentStatePotential.class, CrossStreamDominance.State.class);
            method.setAccessible(true);
            TransitionAdmission allowed = (row, left, right, profiles, region) -> true;
            try { method.invoke(null, graph, TURN, budgets(128_000_000), null, CancellationProbe.NONE, allowed,
                    owner, counter, null, state); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    @Test
    void potentialFailureRemainsTerminalBeforeAnyBoundQuery() {
        var ledger = new AttemptMemoryLedger(1_000_000);
        var failure = new IllegalStateException("test-only controlled capability failure");
        boolean[] boundSeen = {false}, thrown = {false};
        try (var owner = ledger.rootOwner(); var caller = owner.reserve(7)) {
            var actual = assertThrows(IllegalStateException.class, () -> new ProbabilisticInference().solveCrossStreamRaw1(
                    cacheGraph(), TURN, budgets(128_000_000), null,
                    () -> { if (in(BOUND, "strictlyDominatedRaw1")) boundSeen[0] = true; return false; }, owner,
                    (stage, left, right) -> {
                        if (stage == 1 && in(INFERENCE, "enumerateKBest")) { thrown[0] = true; throw failure; }
                        return 0;
                    }));
            assertSame(failure, actual); assertTrue(thrown[0]); assertFalse(boundSeen[0]);
            assertEquals(7, owner.currentBytes());
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void cancelAtBoundEntryAndAcceptedSkipPublishesNothing() {
        for (String method : new String[] {"strictlyDominatedRaw1", "strictlyDominatedAdmitted"}) {
            var ledger = new AttemptMemoryLedger(1_000_000);
            boolean[] targetedPollSeen = {false};
            try (var owner = ledger.rootOwner(); var caller = owner.reserve(7)) {
                assertThrows(CancellationException.class, () -> new ProbabilisticInference().solveCrossStreamRaw1(
                        cacheGraph(), TURN, budgets(128_000_000), null, () -> {
                            if (in(BOUND, method)) { targetedPollSeen[0] = true; return true; }
                            return false;
                        }, owner, null));
                assertTrue(targetedPollSeen[0], "actual named query/accepted-skip checkpoint reached");
                assertEquals(7, owner.currentBytes(), "no result/sidecar survives, caller reservation remains");
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void unsafeCertificateFallsBackWithoutInventingOrHidingNumericalFailure() {
        var parameters = new EvidenceModelParameters("unsafe-upper", 1, 0, 1, 0, 0, Double.MIN_VALUE, 1, 0);
        var graph = List.of(row(0, 0, 0, new double[] {0, 0}, new double[] {0, 100}),
                row(1, 1, 0, new double[] {0}, new double[] {0}),
                row(2, 2, 0, new double[] {0, 0}, new double[] {0, 0}));
        var ledger = new AttemptMemoryLedger(1_000_000);
        try (var owner = ledger.rootOwner()) {
            var old = new ProbabilisticInference().solve(graph, parameters, budgets(128_000_000), null,
                    CancellationProbe.NONE, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            var value = new ProbabilisticInference().solveCrossStreamRaw1(graph, parameters, budgets(128_000_000), null,
                    CancellationProbe.NONE, owner, null);
            assertEquals(old.rawPaths(), value.result().rawPaths());
            assertEquals(old.status(), value.result().status());
            assertEquals(1, value.boundWork().certificateFallbacks());
            assertEquals(1, value.boundWork().stageCertificates(), "unsafe stage is not recomputed");
            assertEquals(4, value.boundWork().boundFallbacks());
            assertEquals(0, value.boundWork().skippedStreams());
            assertEquals(4, value.boundWork().tripleEvaluations(), "actual straight triples stay zero even when their upper overflows");
            assertEquals(old.workAccounting().orElseThrow().computedScores(), value.result().workAccounting().orElseThrow().computedScores());
        }
        assertEquals(0, ledger.currentBytes());
        var bent = List.of(row(0, 0, 0, new double[] {0}, new double[] {0}),
                row(1, 1, 0, new double[] {0}, new double[] {0}), row(2, 1, 1, new double[] {0}, new double[] {0}));
        var failedLedger = new AttemptMemoryLedger(1_000_000);
        try (var owner = failedLedger.rootOwner()) {
            var old = new ProbabilisticInference().solve(bent, parameters, budgets(128_000_000), null,
                    CancellationProbe.NONE, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            var value = new ProbabilisticInference().solveCrossStreamRaw1(bent, parameters, budgets(128_000_000), null,
                    CancellationProbe.NONE, owner, null);
            // Original multirow GraphMessages marks a nonfinite partition as noRoute.
            // Preserve that factual disposition; this optimization does not repair it.
            assertEquals(ProbabilisticInferenceResult.Status.NO_ROUTE, old.status());
            assertEquals(old.status(), value.result().status()); assertEquals(old.explanation(), value.result().explanation());
            assertTrue(value.result().rawPaths().isEmpty()); assertFalse(value.result().posteriorUsable());
            assertEquals(0, value.boundWork().boundQueries(), "original FB numerical failure happens before K-best");
        }
        assertEquals(0, failedLedger.currentBytes());
    }

    @Test
    void originalArithmeticFailureRetainsNumericStatusAndActualWork() {
        for (boolean useBound : new boolean[] {false, true}) {
            var ledger = new AttemptMemoryLedger(1_000_000);
            boolean[] reached = {false};
            try (var owner = ledger.rootOwner(); var caller = owner.reserve(7)) {
                AdjacentStatePotential potential = (stage, left, right) -> {
                    if (stage == 1 && in(INFERENCE, "enumerateKBest")) {
                        reached[0] = true;
                        throw new ArithmeticException("test-only original arithmetic failure");
                    }
                    return 0;
                };
                CrossStreamInferenceResult wrapped = useBound ? new ProbabilisticInference().solveCrossStreamRaw1(
                        cacheGraph(), TURN, budgets(128_000_000), null, CancellationProbe.NONE, owner, potential) : null;
                var result = useBound ? wrapped.result() : new ProbabilisticInference().solve(cacheGraph(), TURN,
                        budgets(128_000_000), null, CancellationProbe.NONE, owner,
                        ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2, potential);
                assertTrue(reached[0]); assertEquals(ProbabilisticInferenceResult.Status.NUMERIC_FAILURE, result.status());
                assertEquals("numeric failure", result.explanation());
                assertEquals(16, result.workAccounting().orElseThrow().forwardBackwardScores());
                assertEquals(0, result.workAccounting().orElseThrow().kBestScores());
                assertTrue(result.rawPaths().isEmpty()); assertFalse(result.posteriorUsable());
                if (useBound) assertEquals(0, wrapped.boundWork().boundQueries());
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void finitePointsWithInfiniteDifferencesStillAdmitASafeCertificate() {
        var graph = List.of(row(0, -Double.MAX_VALUE, 0, new double[] {0, 0.1}, new double[] {0, 100}),
                row(1, Double.MAX_VALUE, 0, new double[] {0}, new double[] {0}),
                row(2, -Double.MAX_VALUE, 1, new double[] {0}, new double[] {0}));
        assertEquals(Double.POSITIVE_INFINITY, graph.get(1).point(0).xMeters() - graph.get(0).point(0).xMeters());
        assertEquals(Double.NEGATIVE_INFINITY, graph.get(2).point(0).xMeters() - graph.get(1).point(0).xMeters());
        var ledger = new AttemptMemoryLedger(1_000_000);
        try (var owner = ledger.rootOwner()) {
            var old = new ProbabilisticInference().solve(graph, TURN, budgets(128_000_000), null,
                    CancellationProbe.NONE, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            var value = new ProbabilisticInference().solveCrossStreamRaw1(graph, TURN, budgets(128_000_000), null,
                    CancellationProbe.NONE, owner, null);
            assertFalse(old.rawPaths().isEmpty()); assertEquals(old.rawPaths(), value.result().rawPaths());
            assertEquals(old.completion(), value.result().completion());
            assertEquals(1, value.boundWork().stageCertificates()); assertEquals(0, value.boundWork().certificateFallbacks());
            assertEquals(1, value.boundWork().skippedStreams()); assertEquals(1, value.boundWork().tripleEvaluations());
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void budgetRefusalKeepsActualScoresAndNoPartialOutput() {
        for (long limit : new long[] {20, 19}) {
            var ledger = new AttemptMemoryLedger(1_000_000);
            try (var owner = ledger.rootOwner(); var caller = owner.reserve(7)) {
                var value = new ProbabilisticInference().solveCrossStreamRaw1(cacheGraph(), TURN, budgets(limit), null,
                        CancellationProbe.NONE, owner, null);
                var result = value.result(); var work = result.workAccounting().orElseThrow();
                assertEquals(16, work.forwardBackwardScores());
                assertEquals(limit, work.computedScores()); assertEquals(limit, result.evaluatedTransitions());
                if (limit == 20) {
                    assertFalse(result.rawPaths().isEmpty()); assertTrue(result.completion().isPresent());
                    assertEquals(0, work.refusedScoreAttempts()); assertEquals(4, value.boundWork().skippedStreams());
                } else {
                    assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, result.status());
                    assertEquals(3, work.kBestScores()); assertEquals(1, work.refusedScoreAttempts());
                    assertTrue(result.rawPaths().isEmpty()); assertTrue(result.distinctPaths().isEmpty());
                    assertTrue(result.mapPath().isEmpty()); assertTrue(result.completion().isEmpty());
                    assertFalse(result.posteriorUsable()); assertTrue(result.positionMarginals().isEmpty());
                    assertEquals(7, work.logicalKBestExtensions());
                    assertEquals(3, value.boundWork().skippedStreams());
                }
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    private static boolean in(String type, String method) {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getClassName().equals(type)
                && frame.getMethodName().equals(method)));
    }

    private static List<InferenceProfile> cacheGraph() {
        return List.of(row(0, 0, 0, new double[] {0, 0.1}, new double[] {0, 100}),
                row(1, 1, 0, new double[] {0}, new double[] {0}),
                row(2, 2, 0, new double[] {0, 0.1, 0.2, 0.3}, new double[] {0, 0, 0, 0}));
    }

    private static InferenceProfile row(double chainage, double x, double y, double[] offsets, double[] costs) {
        var cells = new ArrayList<LateralStateCell>();
        for (double offset : offsets) cells.add(new LateralStateCell(offset, 1, false, true, "same"));
        return new InferenceProfile(chainage, new MetricPoint(x, y), new MetricPoint(0, 1), cells, costs,
                List.of(), 0, ObservationOwnership.DIRECT_TWO_SIDED, false);
    }

    private static TraceBudgets budgets(long limit) { return new TraceBudgets(96, 8_000_000, limit, 1, 1); }
}

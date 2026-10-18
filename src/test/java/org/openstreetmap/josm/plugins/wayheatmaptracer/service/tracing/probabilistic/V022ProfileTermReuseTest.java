package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

class V022ProfileTermReuseTest {
    private static final TraceBudgets BUDGETS = new TraceBudgets(96, 1_000_000, 1_000_000, 1, 1);

    @Test
    void everyRetainedFieldMatchesTheFrozen043SyntheticRawBitOracle() throws Exception {
        try (var input = getClass().getResourceAsStream(Frozen043InferenceOracle.RESOURCE)) {
            assertNotNull(input, "the pre-edit oracle must exist, never auto-generate it");
            assertEquals(new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList(),
                    Frozen043InferenceOracle.snapshot(false));
        }
    }

    @Test
    void unchangedOldDiagnosticFreezeRejectsTheOptimizedNumericalSourceBeforeAcquisition() {
        var failure = assertThrows(IllegalArgumentException.class,
                BObservationTrainRuntimeDiagnosticTool::verifyFreeze);
        assertEquals("Numerical26 source changed", failure.getMessage());
    }

    @Test
    void realForwardAndBackwardStagesReuseEachStateWithoutSkippingScoredWork() throws Exception {
        var graph = Frozen043InferenceOracle.graph("wide", new int[] {16, 16, 16, 16, 16}, 2);
        for (var region : java.util.Arrays.asList(null, MetricRegion.rectangle(-20, -20, 30, 30))) {
            Set<Object> tables = Collections.newSetFromMap(new IdentityHashMap<>());
            var result = execute(graph, region, BUDGETS, tables, false);
            assertEquals(6, tables.size(), "three forward and three backward stage tables must execute");
            for (Object table : tables) {
                assertEquals(16L, number(table, "energyCalculations"));
                assertEquals(16L, number(table, "measureCalculations"));
                assertEquals(4080L, number(table, "energyReuses"));
                assertEquals(4080L, number(table, "measureReuses"));
            }
            assertEquals(24_576L, result.workAccounting().orElseThrow().forwardBackwardScores());
            assertEquals(result.evaluatedTransitions(), result.workAccounting().orElseThrow().computedScores());
        }
    }

    @Test
    void dynamicAdmissionMemoNeverCompetesWithTheOptionalTable() throws Exception {
        int[] widths = new int[44]; java.util.Arrays.fill(widths, 3);
        var graph = Frozen043InferenceOracle.graph("narrow", widths, 2);
        Set<Object> tables = Collections.newSetFromMap(new IdentityHashMap<>());
        execute(graph, MetricRegion.rectangle(-100, -100, 200, 200), BUDGETS, tables, false);
        assertTrue(tables.isEmpty(), "three-state region graphs use the mandatory growing admission memo");
        execute(graph, null, BUDGETS, tables, false);
        assertFalse(tables.isEmpty(), "the same graph without region has allocation-free admissions");
    }

    @Test
    void cancellationWithAnAdoptedTableReleasesTheExactOptionalPayload() throws Exception {
        var graph = Frozen043InferenceOracle.graph("cancel", new int[] {16, 16, 16, 16}, 2);
        Set<Object> tables = Collections.newSetFromMap(new IdentityHashMap<>());
        assertThrows(CancellationException.class, () -> execute(graph, null, BUDGETS, tables, true));
        assertEquals(1, tables.size());
        assertTrue(number(tables.iterator().next(), "energyReuses") > 0);
    }

    @Test
    void refusedStageStaysUncachedAfterHeadroomReturnsAndLaterStagesCanReuse() throws Exception {
        var graph = Frozen043InferenceOracle.graph("forced-refusal", new int[] {16, 16, 16}, 6);
        Set<Object> normalTables = Collections.newSetFromMap(new IdentityHashMap<>());
        String expected = Frozen043InferenceOracle.canonical(execute(graph, null, BUDGETS, normalTables, false));
        var ledger = AttemptMemoryLedger.production(); var owner = ledger.rootOwner();
        var filler = new AttemptMemoryLedger.Reservation[1]; int[] checkpoints = {0};
        Set<Object> tables = Collections.newSetFromMap(new IdentityHashMap<>());
        CancellationProbe probe = () -> {
            if (StackWalker.getInstance().walk(frames -> frames.noneMatch(frame ->
                    frame.getMethodName().equals("forwardBackward")))) return false;
            int at = ++checkpoints[0];
            try {
                if (at == 2) {
                    // Leave exactly the next mandatory matrix, and zero optional bytes.
                    filler[0] = owner.reserve(ledger.limitBytes() - ledger.currentBytes()
                            - ProbabilisticInference.deepArrayBytes(16, 16, Double.BYTES));
                }
                var current = adoptedTables(ledger);
                if (at == 3) {
                    assertTrue(current.isEmpty());
                    assertEquals(ledger.limitBytes(), ledger.currentBytes());
                    filler[0].close(); filler[0] = null;
                }
                if (at >= 4 && at <= 6) assertTrue(current.isEmpty(),
                        "a refused forward table must not retry when headroom returns mid-stage");
                tables.addAll(current);
                return false;
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        };
        try {
            var result = new ProbabilisticInference().solve(graph.profiles(), graph.parameters(), BUDGETS,
                    null, probe, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            assertEquals(expected, Frozen043InferenceOracle.canonical(result));
            assertEquals(1, tables.size(), "only the later backward stage may allocate a table");
        } finally { owner.close(); }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void geometryFailureConsumesUnaryButNeverItsLaterMeasureAndClosesTheStage() throws Exception {
        var graph = Frozen043InferenceOracle.graph("late-invalid", new int[] {16, 16, 16}, 2);
        var profiles = new ArrayList<>(graph.profiles());
        var original = profiles.get(0); var cells = new ArrayList<>(original.cells());
        cells.set(5, new LateralStateCell(1e308, 1.0, false, true, "left"));
        profiles.set(0, new InferenceProfile(0, new MetricPoint(0, 1e308), new MetricPoint(0, 1),
                cells, original.unaryCosts(), List.of(0.0), 0.8,
                ObservationOwnership.DIRECT_TWO_SIDED, false));
        var turnOnly = new EvidenceModelParameters("late-invalid-turn", 1, 0, 1, 0, 0, 1, 1, 0);
        var invalid = new Frozen043InferenceOracle.Graph("late-invalid", profiles, turnOnly);
        Set<Object> tables = Collections.newSetFromMap(new IdentityHashMap<>());
        assertThrows(IllegalArgumentException.class, () -> execute(invalid, null, BUDGETS, tables, false));
        assertEquals(1, tables.size());
        Object table = tables.iterator().next();
        assertEquals(1281L, number(table, "energyCalculations") + number(table, "energyReuses"));
        assertEquals(1280L, number(table, "measureCalculations") + number(table, "measureReuses"));
    }

    @Test
    void independentReadinessPreservesSignedZeroAndDoesNotEvaluateUnusedMeasure() throws Exception {
        var graph = Frozen043InferenceOracle.graph("signed", new int[] {3}, 6);
        var ledger = new AttemptMemoryLedger(4096); var owner = ledger.rootOwner();
        try {
            Object table = create(owner, 3);
            assertNotNull(table);
            double first = unary(table, graph, 0, 0);
            assertEquals(Double.doubleToRawLongBits(first), Double.doubleToRawLongBits(unary(table, graph, 0, 0)));
            assertEquals(1L, number(table, "energyCalculations"));
            assertEquals(1L, number(table, "energyReuses"));
            assertEquals(0L, number(table, "measureCalculations"), "an intervening geometry failure must leave measure untouched");
            assertEquals(1, ((byte[]) field(table, "ready"))[0]);
            double measure = measure(table, graph.profiles().get(0).cells().get(0), 0);
            assertEquals(Double.doubleToRawLongBits(StrictMath.log(0.25)), Double.doubleToRawLongBits(measure));
            assertEquals(3, ((byte[]) field(table, "ready"))[0]);
            assertEquals(Double.doubleToRawLongBits(measure), Double.doubleToRawLongBits(
                    measure(table, graph.profiles().get(0).cells().get(0), 0)));
            assertEquals(1L, number(table, "measureCalculations"));
            assertEquals(1L, number(table, "measureReuses"));
        } finally { owner.close(); }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void byteShortReservationFallsBackAndExactReservationOwnsOnlyItsConcretePayload() throws Exception {
        long bytes = tableBytes(96);
        assertEquals(1752L, bytes);
        var refusedLedger = new AttemptMemoryLedger(bytes - 1); var refused = refusedLedger.rootOwner();
        assertNull(create(refused, 96));
        assertEquals(0, refusedLedger.currentBytes()); refused.close();
        var ledger = new AttemptMemoryLedger(bytes); var owner = ledger.rootOwner();
        assertNotNull(create(owner, 96));
        assertEquals(bytes, ledger.currentBytes()); owner.close();
        assertEquals(0, ledger.currentBytes());
        assertThrows(IllegalStateException.class, () -> create(owner, 96));
    }

    @Test
    void saturatedExistingCounterInventoryCannotFailBecauseOfNewCacheMetrics() throws Exception {
        var graph = Frozen043InferenceOracle.graph("counter-cap", new int[] {16, 16, 16, 16}, 6);
        ModernDiagnosticCounters.begin();
        try {
            // Pre-create every existing solve/admission key, then fill the remaining slots.
            execute(graph, null, BUDGETS, Collections.newSetFromMap(new IdentityHashMap<>()), false);
            int i = 0;
            while (ModernDiagnosticCounters.snapshot().size() < 128) ModernDiagnosticCounters.record("fixture.slot" + i++, 0);
            Set<String> before = ModernDiagnosticCounters.snapshot().keySet();
            var tables = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            execute(graph, null, BUDGETS, tables, false);
            assertFalse(tables.isEmpty());
            assertEquals(before, ModernDiagnosticCounters.snapshot().keySet());
        } finally { ModernDiagnosticCounters.end(); }
    }

    private static ProbabilisticInferenceResult execute(Frozen043InferenceOracle.Graph graph, MetricRegion region,
            TraceBudgets budgets, Set<Object> tables, boolean cancelAtTable) throws Exception {
        var ledger = AttemptMemoryLedger.production(); var owner = ledger.rootOwner();
        boolean[] checkedKBest = {false};
        CancellationProbe probe = () -> {
            try {
                if (StackWalker.getInstance().walk(frames -> frames.noneMatch(frame ->
                        frame.getMethodName().equals("forwardBackward")))) {
                    if (!checkedKBest[0] && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                            frame.getMethodName().equals("enumerateKBest")))) {
                        checkedKBest[0] = true;
                        assertTrue(adoptedTables(ledger).isEmpty(), "close optional stage payload before k-best allocation");
                    }
                    return false;
                }
                for (Object identity : adoptedTables(ledger)) {
                        tables.add(identity);
                        assertTrue(number(identity, "energyCalculations") <= graph.profiles().stream()
                                .mapToInt(p -> p.cells().size()).max().orElseThrow());
                }
                return cancelAtTable && !tables.isEmpty();
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        };
        try {
            return new ProbabilisticInference().solve(graph.profiles(), graph.parameters(), budgets,
                    region, probe, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
        } finally { owner.close(); assertEquals(0, ledger.currentBytes(), "every exit closes the stage owner"); }
    }

    private static List<Object> adoptedTables(AttemptMemoryLedger ledger) throws ReflectiveOperationException {
        var tables = new ArrayList<Object>();
        for (Object charge : ((Map<?, ?>) field(ledger, "charges")).keySet()) {
            Object identity = field(charge, "identity");
            if (identity.getClass().getSimpleName().equals("ProfileTerms")) tables.add(identity);
        }
        return tables;
    }

    private static long tableBytes(int states) {
        return AttemptMemoryLedger.objectBytes(56) + 2 * AttemptMemoryLedger.arrayBytes(states, Double.BYTES)
                + AttemptMemoryLedger.arrayBytes(states, Byte.BYTES);
    }

    private static Object create(AttemptMemoryLedger.Owner owner, int states) throws Exception {
        return invoke(null, "create", new Class<?>[] {AttemptMemoryLedger.Owner.class, int.class}, owner, states);
    }

    private static double unary(Object table, Frozen043InferenceOracle.Graph graph, int profile, int state) throws Exception {
        return (double) invoke(table, "unary", new Class<?>[] {List.class, int.class, int.class, EvidenceModelParameters.class},
                graph.profiles(), profile, state, graph.parameters());
    }

    private static double measure(Object table, LateralStateCell cell, int state) throws Exception {
        return (double) invoke(table, "measure", new Class<?>[] {LateralStateCell.class, int.class}, cell, state);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameters, Object... args) throws Exception {
        var type = Class.forName(ProbabilisticInference.class.getName() + "$ProfileTerms");
        var method = type.getDeclaredMethod(name, parameters); method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private static long number(Object target, String name) throws ReflectiveOperationException {
        return ((Number) field(target, name)).longValue();
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException next) { /* identity lives in the charge superclass */ }
        }
        throw new NoSuchFieldException(name);
    }
}

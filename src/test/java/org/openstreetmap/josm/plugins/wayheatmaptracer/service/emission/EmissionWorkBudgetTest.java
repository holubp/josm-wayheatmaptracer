package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class EmissionWorkBudgetTest {
    private static final String SAFE_MESSAGE = "Emission work limit exceeded";

    @Test
    void chargesMixedOperationsAcrossPhasesAndSnapshotsAllOperations() {
        EmissionWorkBudget budget = budget(8, 4);
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 2);
        budget.charge(EmissionWorkBudget.Operation.PAIR_INITIALIZATION, 1);
        budget.charge(EmissionWorkBudget.Operation.REGION_QUERY, 3);
        budget.charge(EmissionWorkBudget.Operation.TRANSITION, 2);

        var snapshot = budget.snapshot();
        assertEquals(8, snapshot.workUsed());
        assertEquals(3, snapshot.pairVisits());
        assertEquals(2, snapshot.operationUnits().get(EmissionWorkBudget.Operation.PREPARATION));
        assertEquals(2, snapshot.operationUnits().get(EmissionWorkBudget.Operation.TRANSITION));
        assertEquals(EmissionWorkBudget.Operation.values().length, snapshot.operationUnits().size());
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.operationUnits().put(EmissionWorkBudget.Operation.ALLOCATION, 1L));
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 0);
        assertEquals(8, budget.snapshot().workUsed());
    }

    @Test
    void admitsExactLimitAndFailsOnNextUnit() {
        EmissionWorkBudget budget = budget(2, 2);
        budget.charge(EmissionWorkBudget.Operation.CELL_QUERY, 2);
        var failure = assertThrows(EmissionWorkBudget.LimitExceededException.class,
            () -> budget.charge(EmissionWorkBudget.Operation.NODE_READ, 1));
        assertEquals(EmissionWorkBudget.LimitKind.TOTAL_WORK, failure.kind());
        assertEquals(1, failure.requestedUnits());
        assertEquals(2, failure.used());
        assertEquals(2, failure.limit());
        assertEquals(SAFE_MESSAGE, failure.getMessage());
    }

    @Test
    void honorsStricterCallerLimitsAndHardCaps() {
        EmissionWorkBudget strict = new EmissionWorkBudget(
            new TraceBudgets(1, 3, 5, 1, 1), CancellationProbe.NONE);
        assertEquals(5, strict.snapshot().workLimit());
        assertEquals(3, strict.snapshot().pairLimit());
        EmissionWorkBudget capped = new EmissionWorkBudget(
            new TraceBudgets(1, Long.MAX_VALUE, Long.MAX_VALUE, 1, 1), CancellationProbe.NONE);
        assertEquals(8_000_000, capped.snapshot().pairLimit());
        assertEquals(128_000_000, capped.snapshot().workLimit());
    }

    @Test
    void pairFailureDoesNotPartiallyChargeWorkAndLatchesFirstFailure() {
        EmissionWorkBudget budget = budget(10, 1);
        budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
        var failure = assertThrows(EmissionWorkBudget.LimitExceededException.class,
            () -> budget.charge(EmissionWorkBudget.Operation.TRANSPORT, 1));
        assertEquals(EmissionWorkBudget.LimitKind.PAIR_VISITS, failure.kind());
        assertEquals(1, budget.snapshot().workUsed());
        assertEquals(1, budget.snapshot().pairVisits());
        assertSame(failure, assertThrows(EmissionWorkBudget.LimitExceededException.class,
            () -> budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1)));
        assertEquals(0, budget.snapshot().operationUnits().get(EmissionWorkBudget.Operation.PREPARATION));
    }

    @Test
    void totalFailureTakesPrecedenceAndDoesNotPartiallyChargePairVisits() {
        EmissionWorkBudget budget = budget(1, 1);
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
        var failure = assertThrows(EmissionWorkBudget.LimitExceededException.class,
            () -> budget.charge(EmissionWorkBudget.Operation.TRANSITION, 1));
        assertEquals(EmissionWorkBudget.LimitKind.TOTAL_WORK, failure.kind());
        assertEquals(0, budget.snapshot().pairVisits());
    }

    @Test
    void longMaxChargeIsRefusedWithoutOverflow() {
        EmissionWorkBudget budget = budget(3, 2);
        var failure = assertThrows(EmissionWorkBudget.LimitExceededException.class,
            () -> budget.charge(EmissionWorkBudget.Operation.PAIR_INITIALIZATION, Long.MAX_VALUE));
        assertEquals(EmissionWorkBudget.LimitKind.TOTAL_WORK, failure.kind());
        assertEquals(0, budget.snapshot().workUsed());
        assertEquals(0, budget.snapshot().pairVisits());
    }

    @Test
    void cancellationBeforeAndAfterWorkIsLatchedPermanently() {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        EmissionWorkBudget before = new EmissionWorkBudget(tinyLimits(4, 4), cancelled::get);
        CancellationException first = assertThrows(CancellationException.class,
            () -> before.charge(EmissionWorkBudget.Operation.PREPARATION, 1));
        cancelled.set(false);
        assertSame(first, assertThrows(CancellationException.class,
            () -> before.charge(EmissionWorkBudget.Operation.PREPARATION, 1)));
        assertEquals(0, before.snapshot().workUsed());

        cancelled.set(false);
        EmissionWorkBudget after = new EmissionWorkBudget(tinyLimits(4, 4), cancelled::get);
        after.charge(EmissionWorkBudget.Operation.PREPARATION, 2);
        cancelled.set(true);
        CancellationException afterWork = assertThrows(CancellationException.class,
            () -> after.charge(EmissionWorkBudget.Operation.ALLOCATION, 0));
        cancelled.set(false);
        assertSame(afterWork, assertThrows(CancellationException.class,
            () -> after.charge(EmissionWorkBudget.Operation.PREPARATION, 0)));
        assertEquals(2, after.snapshot().workUsed());
    }

    @Test
    void invalidArgumentsDoNotMutateCountersOrLatch() {
        EmissionWorkBudget budget = budget(4, 4);
        assertThrows(NullPointerException.class, () -> budget.charge(null, 1));
        assertThrows(IllegalArgumentException.class,
            () -> budget.charge(EmissionWorkBudget.Operation.PREPARATION, -1));
        assertEquals(0, budget.snapshot().workUsed());
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 2);
        assertEquals(2, budget.snapshot().workUsed());
    }

    @Test
    void pairClassificationIsExplicitAndAllocationIsWorkOnly() {
        for (EmissionWorkBudget.Operation operation : EmissionWorkBudget.Operation.values()) {
            assertEquals(operation.name().startsWith("PAIR_")
                || operation == EmissionWorkBudget.Operation.TRANSITION
                || operation == EmissionWorkBudget.Operation.TRANSPORT
                || operation == EmissionWorkBudget.Operation.TIE_COMPARISON
                || operation == EmissionWorkBudget.Operation.BACKPOINTER_VISIT,
                operation.chargesPairVisits());
        }
        EmissionWorkBudget budget = budget(3, 1);
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        assertEquals(0, budget.snapshot().pairVisits());
    }

    @Test
    void concurrentAdmissionsAreSerializedWithoutExceedingTheLimit() throws Exception {
        EmissionWorkBudget budget = budget(1, 1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> left = executor.submit(() -> attemptOne(budget, ready, start));
            Future<Boolean> right = executor.submit(() -> attemptOne(budget, ready, start));
            ready.await();
            start.countDown();
            assertNotSame(left.get(), right.get());
            assertEquals(1, budget.snapshot().workUsed());
            assertEquals(0, budget.snapshot().pairVisits());
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean attemptOne(EmissionWorkBudget budget, CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            return true;
        } catch (EmissionWorkBudget.LimitExceededException expected) {
            return false;
        }
    }

    private static EmissionWorkBudget budget(long work, long pairs) {
        return new EmissionWorkBudget(tinyLimits(work, pairs), CancellationProbe.NONE);
    }

    private static TraceBudgets tinyLimits(long work, long pairs) {
        return new TraceBudgets(1, pairs, work, 1, 1);
    }
}

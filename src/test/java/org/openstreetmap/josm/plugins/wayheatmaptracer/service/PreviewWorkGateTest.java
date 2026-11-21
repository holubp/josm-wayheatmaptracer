package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PreviewWorkGateTest {
    @Test void transferredRootWindowClosesOnceWhenAnotherOwnerOpens() {
        var closed=new AtomicInteger();
        try(var session=new PreviewSessionController<String>(Runnable::run)) {
            var owner=session.open(closed::incrementAndGet);
            session.workGate().release(owner);session.workGate().adopt(new Object(),closed::incrementAndGet);
            session.open(()->{});assertEquals(1,closed.get());
        }
    }
    @Test void latestSameSourceIntentWaitsForPhysicalWorkerAndQueuedResult() {
        var dispatch=new ArrayDeque<Runnable>();var gate=new PreviewWorkGate(dispatch::add);
        var closed=new AtomicInteger();var oldOwner=new Object();gate.adopt(oldOwner,closed::incrementAndGet);
        var worker=gate.beginWork(oldOwner);var modern=new CompletableFuture<Void>();
        var normal=new AtomicInteger();var precise=new AtomicInteger();var move=new AtomicInteger();var abandoned=new AtomicInteger();
        assertFalse(gate.requestInvocation("normal-same-way-config",normal::incrementAndGet,abandoned::incrementAndGet,()->modern));
        assertEquals(1,closed.get());assertFalse(gate.isCurrent(oldOwner));
        assertFalse(gate.requestInvocation("precise-same-way-config",precise::incrementAndGet,abandoned::incrementAndGet,()->modern));
        assertFalse(gate.requestInvocation("move-same-way-config",move::incrementAndGet,abandoned::incrementAndGet,()->modern));
        assertEquals(2,abandoned.get());
        modern.complete(null);assertTrue(dispatch.isEmpty(),"Root input/result still pinned");
        gate.workerSettled(worker);assertEquals(0,move.get(),"Queued publication dispatch must still occur");
        while(!dispatch.isEmpty())dispatch.remove().run();
        assertEquals(0,normal.get());assertEquals(0,precise.get());assertEquals(1,move.get());
    }
    @Test void idleLegacyInvocationStartsImmediatelyWithoutSchedulingWork() {
        var dispatch=new ArrayDeque<Runnable>();var gate=new PreviewWorkGate(dispatch::add);var resumed=new AtomicInteger();
        assertTrue(gate.requestInvocation("normal",resumed::incrementAndGet,()->{},()->CompletableFuture.completedFuture(null)));
        assertEquals(0,resumed.get());assertTrue(dispatch.isEmpty());assertTrue(gate.settlement().toCompletableFuture().isDone());
    }
    @Test void cancelledDeferredIntentNeverStarts() {
        var dispatch=new ArrayDeque<Runnable>();var gate=new PreviewWorkGate(dispatch::add);var called=new AtomicInteger();var abandoned=new AtomicInteger();var owner=new Object();
        gate.adopt(owner,()->{});var work=gate.beginWork(owner);
        assertFalse(gate.requestInvocation("same-way-config",called::incrementAndGet,abandoned::incrementAndGet,()->CompletableFuture.completedFuture(null)));
        gate.cancelPending("same-way-config");gate.workerSettled(work);while(!dispatch.isEmpty())dispatch.remove().run();
        assertEquals(0,called.get());assertEquals(1,abandoned.get());
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob.Attempt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob.AttemptSnapshot;

class V022AlignmentJobCancellationRegressionTest {
    private final List<AlignmentJob<String>> jobs = new ArrayList<>();

    @AfterEach
    void closeJobs() {
        jobs.forEach(AlignmentJob::close);
    }

    @Test
    void g606RepeatedStartOnlyCancelsOldAttemptThenAllowsRestartOnThirdInvocation() {
        DeterministicExecutorService executor = new DeterministicExecutorService();
        DeterministicEventDispatcher dispatcher = new DeterministicEventDispatcher();
        AlignmentJob<String> job = job(dispatcher, executor);
        List<String> published = new ArrayList<>();

        job.start(() -> snapshot("attempt-1"),
            (ignored, context) -> {
                context.transition(AlignmentJob.State.INFERRING);
                return "one";
            }, attempt -> published.add(attempt.result()));
        var second = job.start(() -> snapshot("attempt-2"),
            (ignored, context) -> "two", attempt -> published.add(attempt.result()));

        assertFalse(second.started());
        assertTrue(second.cancelledExisting());
        assertEquals(AlignmentJob.State.CANCELLED, job.currentAttempt().state());

        var third = job.start(() -> snapshot("attempt-3"),
            (ignored, context) -> "three", attempt -> published.add(attempt.result()));
        assertTrue(third.started());

        executor.runAll();
        dispatcher.runAll();

        Attempt<String> terminal = job.currentAttempt();
        assertEquals(AlignmentJob.State.PREVIEW_READY, terminal.state());
        assertEquals(snapshot("attempt-3"), terminal.snapshot());
        assertEquals("three", terminal.result());
        assertEquals(List.of("three"), published);
    }

    @Test
    void g606QueuedOldPublicationIsDiscardedAfterExplicitCancelThenRestartedBeforeDispatch() {
        DeterministicExecutorService executor = new DeterministicExecutorService();
        DeterministicEventDispatcher dispatcher = new DeterministicEventDispatcher();
        AlignmentJob<String> job = job(dispatcher, executor);
        List<String> published = new ArrayList<>();

        job.start(() -> snapshot("old"),
            (ignored, context) -> {
                context.transition(AlignmentJob.State.INFERRING);
                return "old";
            }, attempt -> published.add(attempt.result()));
        executor.runAll();
        assertEquals(1, dispatcher.queueSize());
        assertEquals(AlignmentJob.State.VALIDATING, job.currentAttempt().state());

        job.cancel();
        var restart = job.start(() -> snapshot("new"),
            (ignored, context) -> {
                context.transition(AlignmentJob.State.INFERRING);
                return "new";
            }, attempt -> published.add(attempt.result()));
        assertTrue(restart.started());
        executor.runAll();
        dispatcher.runAll();

        Attempt<String> terminal = job.currentAttempt();
        assertEquals(AlignmentJob.State.PREVIEW_READY, terminal.state());
        assertEquals(snapshot("new"), terminal.snapshot());
        assertEquals("new", terminal.result());
        assertEquals(List.of("new"), published);
    }

    @Test
    void g606CancellationExceptionDuringInferringCancelsAttemptAndSuppressesPublication() {
        DeterministicExecutorService executor = new DeterministicExecutorService();
        DeterministicEventDispatcher dispatcher = new DeterministicEventDispatcher();
        AlignmentJob<String> job = job(dispatcher, executor);
        List<String> published = new ArrayList<>();

        job.start(() -> snapshot("inferring"), (ignored, context) -> {
            context.transition(AlignmentJob.State.INFERRING);
            throw new CancellationException("phase");
        }, attempt -> published.add(attempt.result()));
        executor.runAll();
        dispatcher.runAll();

        Attempt<String> terminal = job.currentAttempt();
        assertEquals(AlignmentJob.State.CANCELLED, terminal.state());
        assertEquals(1L, terminal.sequence());
        assertEquals(snapshot("inferring"), terminal.snapshot());
        assertEquals("cancelled", terminal.failureReason());
        assertTrue(published.isEmpty());
    }

    @Test
    void g606CancellationExceptionDuringRefiningCancelsAttemptAndSuppressesPublication() {
        DeterministicExecutorService executor = new DeterministicExecutorService();
        DeterministicEventDispatcher dispatcher = new DeterministicEventDispatcher();
        AlignmentJob<String> job = job(dispatcher, executor);
        List<String> published = new ArrayList<>();

        job.start(() -> snapshot("refining"), (ignored, context) -> {
            context.transition(AlignmentJob.State.INFERRING);
            context.transition(AlignmentJob.State.REFINING);
            throw new CancellationException("phase");
        }, attempt -> published.add(attempt.result()));
        executor.runAll();
        dispatcher.runAll();

        Attempt<String> terminal = job.currentAttempt();
        assertEquals(AlignmentJob.State.CANCELLED, terminal.state());
        assertEquals(1L, terminal.sequence());
        assertEquals(snapshot("refining"), terminal.snapshot());
        assertEquals("cancelled", terminal.failureReason());
        assertTrue(published.isEmpty());
    }

    @Test
    void g606InterruptedExceptionCancelsAttemptAndPreservesInterruptStatus() {
        DeterministicExecutorService executor = new DeterministicExecutorService();
        DeterministicEventDispatcher dispatcher = new DeterministicEventDispatcher();
        AlignmentJob<String> job = job(dispatcher, executor);

        try {
            job.start(() -> snapshot("interrupted"), (ignored, context) -> {
                context.transition(AlignmentJob.State.INFERRING);
                context.transition(AlignmentJob.State.REFINING);
                throw new InterruptedException("interrupted");
            }, attempt -> { });
            executor.runAll();

            Attempt<String> terminal = job.currentAttempt();
            assertEquals(AlignmentJob.State.CANCELLED, terminal.state());
            assertEquals(1L, terminal.sequence());
            assertEquals(snapshot("interrupted"), terminal.snapshot());
            assertEquals("interrupted", terminal.failureReason());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void g606RestartAfterCancellationAllowsFreshSequenceByExplicitCancelThenStart() {
        DeterministicExecutorService executor = new DeterministicExecutorService();
        DeterministicEventDispatcher dispatcher = new DeterministicEventDispatcher();
        AlignmentJob<String> job = job(dispatcher, executor);
        List<String> published = new ArrayList<>();

        job.start(() -> snapshot("first"), (ignored, context) -> {
            context.transition(AlignmentJob.State.INFERRING);
            return "first";
        }, attempt -> published.add(attempt.result()));
        executor.runAll();
        job.cancel();
        var restart = job.start(() -> snapshot("second"),
            (ignored, context) -> "second", attempt -> published.add(attempt.result()));
        assertTrue(restart.started());

        executor.runAll();
        dispatcher.runAll();

        Attempt<String> terminal = job.currentAttempt();
        assertEquals(AlignmentJob.State.PREVIEW_READY, terminal.state());
        assertEquals(snapshot("second"), terminal.snapshot());
        assertEquals("second", terminal.result());
        assertEquals(List.of("second"), published);
    }

    @Test
    void g606OldWorkerCanRequestCancelStartNewSequenceThenThrowWithoutBlockingLaterWork() {
        DeterministicExecutorService executor = new DeterministicExecutorService();
        DeterministicEventDispatcher dispatcher = new DeterministicEventDispatcher();
        AlignmentJob<String> job = job(dispatcher, executor);
        List<String> published = new ArrayList<>();
        AtomicBoolean restartStarted = new AtomicBoolean(false);
        AtomicReference<AlignmentJob.StartResult<String>> replacementAttempt = new AtomicReference<>();

        job.start(() -> snapshot("superseded"), (ignored, context) -> {
            context.transition(AlignmentJob.State.INFERRING);
            job.cancel();
            var restart = job.start(() -> snapshot("replacement"), (ignored2, context2) -> {
                context2.transition(AlignmentJob.State.INFERRING);
                return "replacement";
            }, attempt -> published.add(attempt.result()));
            replacementAttempt.set(restart);
            restartStarted.set(restart.started());
            throw new CancellationException("forced old-worker failure");
        }, attempt -> published.add(attempt.result()));
        executor.runAll();
        dispatcher.runAll();

        Attempt<String> terminal = job.currentAttempt();
        var replacement = replacementAttempt.get();
        assertTrue(restartStarted.get());
        assertEquals(AlignmentJob.State.PREVIEW_READY, terminal.state());
        assertEquals(replacement.attempt().sequence(), terminal.sequence());
        assertEquals(snapshot("replacement"), terminal.snapshot());
        assertEquals(replacement.attempt().snapshot(), terminal.snapshot());
        assertEquals("replacement", terminal.result());
        assertEquals(List.of("replacement"), published);
    }

    @Test
    void g606DirectDispatcherCancellationInPublisherDoesNotOverwritePreviewReady() {
        DeterministicExecutorService executor = new DeterministicExecutorService();
        AlignmentJob<String> job = job(AlignmentJob.EventDispatcher.direct(), executor);
        AtomicBoolean publisherInvoked = new AtomicBoolean();

        var result = job.start(() -> snapshot("terminal-preserve"),
            (ignored, context) -> "preview", attempt -> {
                publisherInvoked.set(true);
                throw new CancellationException("publisher");
            });
        assertTrue(result.started());
        executor.runAll();

        Attempt<String> terminal = job.currentAttempt();
        assertEquals(AlignmentJob.State.PREVIEW_READY, terminal.state());
        assertTrue(publisherInvoked.get());
        assertEquals("preview", terminal.result());
    }

    private AlignmentJob<String> job(AlignmentJob.EventDispatcher dispatcher,
            DeterministicExecutorService executor) {
        AlignmentJob<String> job = new AlignmentJob<>(dispatcher, executor);
        jobs.add(job);
        return job;
    }

    private static AttemptSnapshot snapshot(String attemptId) {
        return new AttemptSnapshot(attemptId, "source-" + attemptId, "settings-" + attemptId,
            "content-" + attemptId);
    }

    static final class DeterministicExecutorService extends AbstractExecutorService {
        private final Deque<Runnable> queue = new ArrayDeque<>();
        private volatile boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (isShutdown()) {
                throw new RejectedExecutionException();
            }
            queue.add(command);
        }

        void runAll() {
            while (!queue.isEmpty()) {
                queue.removeFirst().run();
            }
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = new ArrayList<>(queue);
            queue.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return isShutdown();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }

    static final class DeterministicEventDispatcher implements AlignmentJob.EventDispatcher {
        private final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void dispatch(Runnable task) {
            queue.add(task);
        }

        int queueSize() {
            return queue.size();
        }

        void runAll() {
            while (!queue.isEmpty()) {
                queue.removeFirst().run();
            }
        }
    }
}

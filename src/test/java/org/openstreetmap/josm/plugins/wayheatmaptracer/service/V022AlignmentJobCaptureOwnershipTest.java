package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class V022AlignmentJobCaptureOwnershipTest {
    @Test
    void detachedPayloadCapturedOnCallerIsTheOnlyWorkerInput() {
        QueueExecutor executor = new QueueExecutor();
        List<String> published = new ArrayList<>();
        try (AlignmentJob<String> job = new AlignmentJob<>(Runnable::run, executor)) {
            var started = job.startDetached(
                () -> new AlignmentJob.CapturedAttempt<>(snapshot("typed"), List.of("frozen")),
                (captured, context) -> captured.get(0) + ":" + Thread.currentThread().getName(),
                result -> published.add(result.result()));

            assertTrue(started.started());
            assertTrue(published.isEmpty());
            executor.queue.remove().run();
            assertEquals(1, published.size());
            assertTrue(published.get(0).startsWith("frozen:"));
        }
    }

    @Test
    void cancellationDuringCaptureRemainsTerminalWithoutSubmittingWork() {
        QueueExecutor executor = new QueueExecutor();
        try (AlignmentJob<String> job = new AlignmentJob<>(Runnable::run, executor)) {
            var started = job.start(() -> {
                job.cancel();
                return snapshot("cancelled-capture");
            }, (snapshot, context) -> fail("Cancelled capture submitted computation"),
                result -> fail("Cancelled capture published a result"));
            assertTrue(started.started());
            assertEquals(AlignmentJob.State.CANCELLED, job.currentAttempt().state());
            assertEquals(job.currentAttempt(), started.attempt());
            assertTrue(executor.queue.isEmpty());
        }
    }

    @Test
    void closingDuringCaptureDoesNotSubmitToTheClosedExecutor() {
        QueueExecutor executor = new QueueExecutor();
        try (AlignmentJob<String> job = new AlignmentJob<>(Runnable::run, executor)) {
            var started = assertDoesNotThrow(() -> job.start(() -> {
                job.close();
                return snapshot("closed-capture");
            }, (snapshot, context) -> fail("Closed capture submitted computation"),
                result -> fail("Closed capture published a result")));
            assertEquals(AlignmentJob.State.CANCELLED, started.attempt().state());
            assertEquals(started.attempt(), job.currentAttempt());
            assertTrue(executor.isShutdown());
            assertTrue(executor.queue.isEmpty());
        }
    }

    @Test
    void replacementStartedDuringCaptureRetainsItsIdentityAndOnlyPublication() {
        QueueExecutor executor = new QueueExecutor();
        List<String> published = new ArrayList<>();
        try (AlignmentJob<String> job = new AlignmentJob<>(Runnable::run, executor)) {
            var original = job.start(() -> {
                job.cancel();
                job.start(() -> snapshot("replacement"), (snapshot, context) -> "replacement",
                    result -> published.add(result.result()));
                return snapshot("original");
            }, (snapshot, context) -> fail("Superseded capture submitted computation"),
                result -> fail("Superseded capture published a result"));
            assertEquals(1L, original.attempt().sequence());
            assertEquals(AlignmentJob.State.CANCELLED, original.attempt().state());
            assertEquals(2L, job.currentAttempt().sequence());
            assertEquals(snapshot("replacement"), job.currentAttempt().snapshot());
            assertEquals(1, executor.queue.size());
            executor.queue.remove().run();
            assertEquals(AlignmentJob.State.PREVIEW_READY, job.currentAttempt().state());
            assertEquals(List.of("replacement"), published);
        }
    }

    @Test
    void rejectedSubmissionBecomesFailedAndCanBeRestarted() {
        QueueExecutor executor = new QueueExecutor();
        List<String> published = new ArrayList<>();
        try (AlignmentJob<String> job = new AlignmentJob<>(Runnable::run, executor)) {
            executor.reject = true;
            assertThrows(RejectedExecutionException.class, () -> job.start(() -> snapshot("rejected"),
                (snapshot, context) -> "rejected", result -> published.add(result.result())));
            assertEquals(AlignmentJob.State.FAILED, job.currentAttempt().state());
            assertEquals(snapshot("rejected"), job.currentAttempt().snapshot());
            assertEquals("RejectedExecutionException", job.currentAttempt().failureReason());
            executor.reject = false;
            var restart = job.start(() -> snapshot("restart"), (snapshot, context) -> "restart",
                result -> published.add(result.result()));
            assertTrue(restart.started());
            executor.queue.remove().run();
            assertEquals(List.of("restart"), published);
        }
    }

    @Test
    void failureAfterCaptureCancellationDoesNotReplaceTheCancelledOutcome() {
        QueueExecutor executor = new QueueExecutor();
        try (AlignmentJob<String> job = new AlignmentJob<>(Runnable::run, executor)) {
            assertThrows(IllegalArgumentException.class, () -> job.start(() -> {
                job.cancel();
                throw new IllegalArgumentException("capture failed after cancellation");
            }, (snapshot, context) -> fail("Failed capture submitted work"),
                result -> fail("Failed capture published")));
            assertEquals(AlignmentJob.State.CANCELLED, job.currentAttempt().state());
            assertTrue(executor.queue.isEmpty());
        }
    }

    private static AlignmentJob.AttemptSnapshot snapshot(String id) {
        return new AlignmentJob.AttemptSnapshot(id, "source", "settings", "content");
    }

    private static final class QueueExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;
        private boolean reject;

        @Override public void execute(Runnable command) {
            if (shutdown || reject) throw new RejectedExecutionException("executor unavailable");
            queue.add(command);
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown();
            List<Runnable> pending = List.copyOf(queue);
            queue.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && queue.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}

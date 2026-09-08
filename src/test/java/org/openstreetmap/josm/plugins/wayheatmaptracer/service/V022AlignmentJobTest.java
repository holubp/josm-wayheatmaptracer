package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob.AttemptSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob.EventDispatcher;

/** CP12 asynchronous ownership contracts T123-T130. */
class V022AlignmentJobTest {
    private final List<AlignmentJob<String>> jobs = new ArrayList<>();

    @AfterEach
    void closeJobs() {
        jobs.forEach(AlignmentJob::close);
    }

    @Test
    void t123CaptureRunsBeforeWorkerAndWorkerReceivesOnlyDetachedAttemptSnapshot() throws Exception {
        AtomicBoolean captured = new AtomicBoolean();
        AtomicBoolean sawDetachedSnapshot = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        AlignmentJob<String> job = job(EventDispatcher.direct());

        job.start(() -> {
            captured.set(true);
            return snapshot("one");
        }, (input, context) -> {
            sawDetachedSnapshot.set(captured.get() && input.attemptId().equals("one"));
            return "ok";
        }, result -> done.countDown());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(sawDetachedSnapshot.get());
    }

    @Test
    void t124HeavyWorkDoesNotRunInCaptureOrPublicationCallback() throws Exception {
        AtomicReference<String> captureThread = new AtomicReference<>();
        AtomicReference<String> workerThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AlignmentJob<String> job = job(EventDispatcher.direct());

        job.start(() -> {
            captureThread.set(Thread.currentThread().getName());
            return snapshot("two");
        }, (input, context) -> {
            workerThread.set(Thread.currentThread().getName());
            return "ok";
        }, result -> done.countDown());

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(captureThread.get().equals(workerThread.get()));
    }

    @Test
    void t125ExplicitCancellationPreventsPreviewPublication() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean();
        AlignmentJob<String> job = job(EventDispatcher.direct());

        job.start(() -> snapshot("three"), (input, context) -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            context.checkpoint();
            return "late";
        }, result -> published.set(true));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        job.cancel();
        release.countDown();
        awaitTerminal(job);

        assertEquals(AlignmentJob.State.CANCELLED, job.currentAttempt().state());
        assertFalse(published.get());
    }

    @Test
    void t126LateResultFromSupersededAttemptIsDiscarded() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        List<String> published = new ArrayList<>();
        AlignmentJob<String> job = job(EventDispatcher.direct());

        job.start(() -> snapshot("first"), (input, context) -> {
            firstStarted.countDown();
            firstRelease.await(5, TimeUnit.SECONDS);
            return "first";
        }, attempt -> published.add(attempt.result()));
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        assertTrue(job.start(() -> snapshot("ignored"), (input, context) -> "ignored", attempt -> published.add(attempt.result()))
            .cancelledExisting());
        firstRelease.countDown();
        awaitTerminal(job);

        assertTrue(published.isEmpty());
    }

    @Test
    void t127ConcurrentInvocationCancelsCurrentInsteadOfSpawningCompetingAttempt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AlignmentJob<String> job = job(EventDispatcher.direct());
        job.start(() -> snapshot("first"), (input, context) -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            context.checkpoint();
            return "first";
        }, ignored -> { });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        AlignmentJob.StartResult<String> result = job.start(() -> snapshot("second"),
            (input, context) -> "second", ignored -> { });
        release.countDown();

        assertTrue(result.cancelledExisting());
        assertEquals("first", result.attempt().snapshot().attemptId());
    }

    @Test
    void t128LayerDestructionCancelsOutstandingJobAndRejectsFurtherStarts() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AlignmentJob<String> job = job(EventDispatcher.direct());
        job.start(() -> snapshot("destroy"), (input, context) -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            context.checkpoint();
            return "never";
        }, ignored -> { });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        job.close();
        release.countDown();

        assertThrows(IllegalStateException.class,
            () -> job.start(() -> snapshot("after-close"), (input, context) -> "no", ignored -> { }));
    }

    @Test
    void t129CaptureFailureRestoresStateWithoutPublication() {
        AtomicBoolean published = new AtomicBoolean();
        AlignmentJob<String> job = job(EventDispatcher.direct());

        assertThrows(IllegalStateException.class,
            () -> job.start(() -> { throw new IllegalStateException("capture failed"); },
                (input, context) -> "never", ignored -> published.set(true)));

        assertEquals(AlignmentJob.State.FAILED, job.currentAttempt().state());
        assertFalse(published.get());
    }

    @Test
    void t130PreviewAndSettingsStateHaveNoApplicationCallback() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AlignmentJob.Attempt<String>> attempt = new AtomicReference<>();
        AlignmentJob<String> job = job(EventDispatcher.direct());
        job.start(() -> snapshot("preview"), (input, context) -> "proposal", result -> {
            attempt.set(result);
            done.countDown();
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(AlignmentJob.State.PREVIEW_READY, attempt.get().state());
        assertEquals("proposal", attempt.get().result());
    }

    private AlignmentJob<String> job(EventDispatcher dispatcher) {
        AlignmentJob<String> job = new AlignmentJob<>(dispatcher);
        jobs.add(job);
        return job;
    }

    private static AttemptSnapshot snapshot(String id) {
        return new AttemptSnapshot(id, "source-" + id, "settings-" + id, "content-" + id);
    }

    private static void awaitTerminal(AlignmentJob<?> job) throws InterruptedException {
        for (int index = 0; index < 100 && !job.currentAttempt().state().terminal(); index++) {
            Thread.sleep(10);
        }
    }
}

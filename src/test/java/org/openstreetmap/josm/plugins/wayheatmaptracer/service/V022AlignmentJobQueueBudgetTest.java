package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class V022AlignmentJobQueueBudgetTest {
    @Test
    void defaultExecutorBoundsPendingAttemptsAndReportsRejectionAsFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AlignmentJob<String> job = new AlignmentJob<>(Runnable::run);
        try {
            job.start(() -> snapshot("running"), (snapshot, context) -> {
                entered.countDown();
                release.await();
                return "cancelled-running";
            }, result -> { });
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Worker did not reach the controlled barrier");
            job.cancel();
            job.start(() -> snapshot("pending"), (snapshot, context) -> "cancelled-pending", result -> { });
            job.cancel();
            assertThrows(RejectedExecutionException.class, () -> job.start(() -> snapshot("excess"),
                (snapshot, context) -> "excess", result -> { }));
            assertEquals(AlignmentJob.State.FAILED, job.currentAttempt().state());
            assertEquals(snapshot("excess"), job.currentAttempt().snapshot());
            assertEquals("RejectedExecutionException", job.currentAttempt().failureReason());
        } finally {
            job.close();
            release.countDown();
        }
    }

    private static AlignmentJob.AttemptSnapshot snapshot(String id) {
        return new AlignmentJob.AttemptSnapshot(id, "source", "settings", "content");
    }
}

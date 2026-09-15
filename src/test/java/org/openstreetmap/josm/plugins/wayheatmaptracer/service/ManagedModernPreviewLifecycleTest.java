package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileTransport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TransportResponse;

/** Deterministic production ownership regressions for the managed acquire/infer lifecycle. */
class ManagedModernPreviewLifecycleTest {
    @TempDir java.nio.file.Path temporary;

    @Test
    void twoExperimentalActionOwnersPublishSwitchAndHideOnlyThroughNewestSharedOwner() {
        QueuedExecutor executor = new QueuedExecutor();
        QueuedDispatcher dispatcher = new QueuedDispatcher();
        AtomicReference<String> overlay = new AtomicReference<>();
        AtomicReference<PreviewSessionController.Owner> firstOwner = new AtomicReference<>();
        AtomicInteger staleCloseAttempts = new AtomicInteger();
        try (PreviewSessionController<String> session =
                new PreviewSessionController<>(dispatcher, executor)) {
            firstOwner.set(session.open(() -> {
                staleCloseAttempts.incrementAndGet();
                if (session.close(firstOwner.get())) {
                    overlay.set(null);
                }
            }));
            session.start(firstOwner.get(), () -> snapshot("visible-a"),
                    (snapshot, context) -> "old-visible-a",
                    attempt -> overlay.set(attempt.result()));
            executor.runNext();
            assertEquals(1, dispatcher.size());

            PreviewSessionController.Owner second = session.open(() -> { });
            session.start(second, () -> snapshot("managed-b"),
                    (snapshot, context) -> "new-managed-b",
                    attempt -> overlay.set(attempt.result()));
            executor.runNext();
            assertEquals(2, dispatcher.size());

            dispatcher.runLast();
            assertEquals("new-managed-b", overlay.get());
            dispatcher.runLast();
            assertEquals("new-managed-b", overlay.get());
            assertEquals(1, staleCloseAttempts.get());
            assertFalse(session.isCurrent(firstOwner.get()));
            assertTrue(session.isCurrent(second));
            assertFalse(session.close(firstOwner.get()));
            assertEquals("new-managed-b", overlay.get());
            assertTrue(session.close(second));
        }
    }

    @Test
    void staleQueuedManagedAcquisitionRunsNoTransportAttachInferenceOrPublication() {
        QueuedExecutor executor = new QueuedExecutor();
        AtomicInteger transports = new AtomicInteger();
        AtomicInteger attachments = new AtomicInteger();
        AtomicInteger inference = new AtomicInteger();
        List<String> published = new ArrayList<>();
        ManagedTileTransport transport = (request, credentials) -> {
            transports.incrementAndGet();
            return new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null, null,
                    Duration.ZERO, "http-no-tile");
        };
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator(transport,
                new ManagedTileCache(temporary, decoder), decoder, TileReliabilityPolicy.defaults());
                PreviewSessionController<String> session =
                        new PreviewSessionController<>(Runnable::run, executor)) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(2));
            ManagedModernPreviewSource source = new ManagedModernPreviewSource(coordinator);
            ManagedModernPreviewSource.Request request = request(1, "old-config");
            PreviewSessionController.Owner owner = session.open(() -> { });
            session.start(owner, () -> snapshot("managed-old"), (snapshot, context) -> {
                source.acquire(request, CredentialSnapshot.fromConfig(null), context);
                attachments.incrementAndGet();
                inference.incrementAndGet();
                return "old";
            }, attempt -> published.add(attempt.result()));

            executor.runNext();

            assertEquals(0, transports.get());
            assertEquals(0, attachments.get());
            assertEquals(0, inference.get());
            assertTrue(published.isEmpty());
            assertTrue(coordinator.diagnosticsJson().contains("\"activeGeneration\":2"));
        }
    }

    @Test
    void cancellingOwnerWhileWaitingOnTileJoinFreesActionWorkerBeforeTransportRelease()
            throws Exception {
        CountDownLatch transportEntered = new CountDownLatch(1);
        CountDownLatch releaseTransport = new CountDownLatch(1);
        CountDownLatch replacementRan = new CountDownLatch(1);
        CountDownLatch replacementPublished = new CountDownLatch(1);
        CountDownLatch oldWindowClosed = new CountDownLatch(1);
        AtomicInteger attachments = new AtomicInteger();
        List<String> published = java.util.Collections.synchronizedList(new ArrayList<>());
        ManagedTileTransport transport = (request, credentials) -> {
            transportEntered.countDown();
            try {
                assertTrue(releaseTransport.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Transport interrupted", exception);
            }
            return new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null, null,
                    Duration.ZERO, "http-no-tile");
        };
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        var executor = Executors.newSingleThreadExecutor();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator(transport,
                new ManagedTileCache(temporary, decoder), decoder, TileReliabilityPolicy.defaults());
                PreviewSessionController<String> session =
                        new PreviewSessionController<>(Runnable::run, executor)) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(8));
            ManagedModernPreviewSource source = new ManagedModernPreviewSource(coordinator);
            PreviewSessionController.Owner old = session.open(oldWindowClosed::countDown);
            session.start(old, () -> snapshot("managed-old"), (snapshot, context) -> {
                source.acquire(request(8, "old-source"), CredentialSnapshot.fromConfig(null), context);
                attachments.incrementAndGet();
                return "old";
            }, attempt -> published.add(attempt.result()));
            assertTrue(transportEntered.await(5, TimeUnit.SECONDS));

            PreviewSessionController.Owner replacement = session.open(() -> { });
            session.start(replacement, () -> snapshot("managed-new"), (snapshot, context) -> {
                replacementRan.countDown();
                return "new";
            }, attempt -> {
                published.add(attempt.result());
                replacementPublished.countDown();
            });

            assertTrue(replacementRan.await(5, TimeUnit.SECONDS),
                    "cancelled tile subscriber must promptly release the action worker");
            assertTrue(replacementPublished.await(5, TimeUnit.SECONDS));
            assertTrue(oldWindowClosed.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("new"), published);
            assertEquals(0, attachments.get());
            releaseTransport.countDown();
        } finally {
            releaseTransport.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void pluginDestroyCancelsSharedOwnerBeforeCapturedCoordinatorCloseWithoutRecreation()
            throws Exception {
        CountDownLatch workerPausedBeforeCoordinator = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerExited = new CountDownLatch(1);
        AtomicInteger transports = new AtomicInteger();
        AtomicInteger attachments = new AtomicInteger();
        AtomicInteger inference = new AtomicInteger();
        List<String> published = java.util.Collections.synchronizedList(new ArrayList<>());
        ManagedTileTransport transport = (request, credentials) -> {
            transports.incrementAndGet();
            return new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null, null,
                    Duration.ZERO, "http-no-tile");
        };
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        var executor = Executors.newSingleThreadExecutor();
        TileFetchCoordinator coordinator = new TileFetchCoordinator(transport,
                new ManagedTileCache(temporary, decoder), decoder, TileReliabilityPolicy.defaults());
        AtomicReference<TileFetchCoordinator> runtime = new AtomicReference<>(coordinator);
        PreviewSessionController<String> session =
                new PreviewSessionController<>(Runnable::run, executor);
        try {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(8));
            TileFetchCoordinator capturedCoordinator = runtime.get();
            PreviewSessionController.Owner owner = session.open(() -> { });
            session.start(owner, () -> snapshot("managed-destroy"), (snapshot, context) -> {
                workerPausedBeforeCoordinator.countDown();
                awaitUninterruptibly(releaseWorker);
                try {
                    ManagedModernPreviewSource source =
                            new ManagedModernPreviewSource(capturedCoordinator);
                    source.acquire(request(8, "managed-destroy"),
                            CredentialSnapshot.fromConfig(null), context);
                    attachments.incrementAndGet();
                    inference.incrementAndGet();
                    return "destroyed";
                } finally {
                    workerExited.countDown();
                }
            }, attempt -> published.add(attempt.result()));
            assertTrue(workerPausedBeforeCoordinator.await(5, TimeUnit.SECONDS));

            Runnable closeRuntime = () -> {
                assertEquals(AlignmentJob.State.CANCELLED, session.currentAttempt().state(),
                        "shared preview must be cancelled before runtime close");
                capturedCoordinator.close();
                runtime.compareAndSet(capturedCoordinator, null);
            };
            session.closeThen(closeRuntime);
            session.closeThen(closeRuntime);
            releaseWorker.countDown();

            assertTrue(workerExited.await(5, TimeUnit.SECONDS));
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(null, runtime.get(), "teardown must not recreate the runtime");
            assertEquals(0, transports.get());
            assertEquals(0, attachments.get());
            assertEquals(0, inference.get());
            assertTrue(published.isEmpty());
            try (var paths = Files.walk(temporary)) {
                assertEquals(0L, paths.filter(Files::isRegularFile).count(),
                        "teardown must not publish cache files");
            }
        } finally {
            releaseWorker.countDown();
            session.close();
            coordinator.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static ManagedModernPreviewSource.Request request(long generation, String identity) {
        return new ManagedModernPreviewSource.Request(
                List.of(new GeographicPoint(10.0, 10.0),
                        new GeographicPoint(10.00001, 10.0)),
                "all", "hot", 15, new ManagedTileGeneration(generation),
                7.01, 1.56, identity);
    }

    private static AlignmentJob.AttemptSnapshot snapshot(String id) {
        return new AlignmentJob.AttemptSnapshot(id, "managed-source", "settings", "network");
    }

    private static final class QueuedDispatcher implements AlignmentJob.EventDispatcher {
        private final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void dispatch(Runnable task) {
            tasks.addLast(task);
        }

        int size() {
            return tasks.size();
        }

        void runLast() {
            tasks.removeLast().run();
        }
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException();
            }
            tasks.addLast(command);
        }

        void runNext() {
            tasks.removeFirst().run();
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = List.copyOf(tasks);
            tasks.clear();
            return remaining;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}

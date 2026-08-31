package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileTransport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TransportResponse;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class AggregateIntensityLayerTest {
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void repeatedSchedulingAfterIncompleteCompositeDoesNotRetryBeforeEligibility(@TempDir java.nio.file.Path temp)
        throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ManagedTileTransport transport = (request, credentials) -> {
            executions.incrementAndGet();
            return new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null, null,
                Duration.ZERO, "http-no-tile");
        };
        try (TileFetchCoordinator coordinator = coordinator(temp, transport)) {
            AggregateIntensityLayer layer = new AggregateIntensityLayer(config(), coordinator);
            layer.requestTileForTesting(100, 200);
            await(() -> "FAILED_UNTIL".equals(layer.compositeStatusForTesting(100, 200)));

            for (int index = 0; index < 1_000; index++) {
                layer.requestTileForTesting(100, 200);
            }

            assertEquals(5, executions.get());
            assertEquals(1, layer.compositeCountForTesting());
            assertTrue(layer.getToolTipText().contains("Incomplete aggregate 0/5"));
            layer.destroy();
        }
    }

    @Test
    void destroyPreventsLateCompositePublication(@TempDir java.nio.file.Path temp) throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ManagedTileTransport transport = (request, credentials) -> {
            entered.countDown();
            awaitLatch(release);
            return new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null, null,
                Duration.ZERO, "http-no-tile");
        };
        try (TileFetchCoordinator coordinator = coordinator(temp, transport)) {
            AggregateIntensityLayer layer = new AggregateIntensityLayer(config(), coordinator);
            layer.requestTileForTesting(101, 201);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            layer.destroy();
            release.countDown();
            TimeUnit.MILLISECONDS.sleep(100);

            assertEquals(0, layer.compositeCountForTesting());
            assertEquals("ABSENT", layer.compositeStatusForTesting(101, 201));
        }
    }

    private TileFetchCoordinator coordinator(java.nio.file.Path temp, ManagedTileTransport transport) {
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        return new TileFetchCoordinator(transport, new ManagedTileCache(temp, decoder), decoder,
            TileReliabilityPolicy.defaults(), Clock.systemUTC());
    }

    private ManagedHeatmapConfig config() {
        return new ManagedHeatmapConfig("key", "policy", "signature", "session", "all", "hot", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, TrackerMode.CORRIDOR_AWARE, false, false, false,
            false, true, false, false, false, false, false, 18, 4, 2.0,
            InferenceMode.STABLE_FIXED_SCALE, 15, 13, 7.0, 1.56,
            IntensitySamplingMode.COLOR_MAPPING, 20L);
    }

    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private void awaitLatch(java.util.concurrent.CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}

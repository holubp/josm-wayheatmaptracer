package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

import com.sun.net.httpserver.HttpServer;

class TileAcquisitionFoundationTest {
    private static final String SENTINEL = "sentinel-cookie-secret-9f47";

    @TempDir
    java.nio.file.Path temporary;

    @Test
    void addressAndUrlContainOnlySafeNormalizedIdentity() {
        ManagedTileAddress address = new ManagedTileAddress(" ALL ", "BlueRed", 15, 123, 456);
        URI uri = new ManagedTileUrlBuilder().build(address, new ManagedTileGeneration(77));

        assertEquals("all", address.activity());
        assertEquals("bluered", address.color());
        assertEquals("https", uri.getScheme());
        assertEquals("content-a.strava.com", uri.getHost());
        assertEquals("whtr-cache=77", uri.getQuery());
        assertFalse(uri.toString().contains(SENTINEL));
        assertTrue(new ManagedTileUrlBuilder().buildJosmTemplate("all", "bluered",
            new ManagedTileGeneration(77)).endsWith("?whtr-cache=77"));
    }

    @Test
    void decoderPreservesValidEmptyPlaceholderAndDimensionSemantics() throws Exception {
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        ManagedTileAddress address = address("hot", 1);
        byte[] valid = png(tile(new Color(255, 100, 0, 255)));
        byte[] empty = png(tile(new Color(0, 0, 0, 0)));
        byte[] placeholder = png(tile(new Color(0, 0, 0, 255)));
        byte[] wrong = png(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB));

        DecodedTile validResult = decoder.decodeAndClassify(address, "image/png", valid);
        assertTrue(validResult.usable());
        assertEquals("valid", validResult.quality().label());
        assertEquals("empty-valid", decoder.decodeAndClassify(address, null, empty).quality().label());
        assertEquals(TileFetchStatus.PLACEHOLDER_SUSPECTED,
            decoder.decodeAndClassify(address, "application/octet-stream", placeholder).status());
        assertEquals(TileFetchStatus.BAD_DIMENSIONS,
            decoder.decodeAndClassify(address, "image/png", wrong).status());
        assertEquals(TileFetchStatus.CONTENT_TYPE_ERROR,
            decoder.decodeAndClassify(address, "text/html", "<html>no</html>".getBytes()).status());
    }

    @Test
    void legacyCacheLayoutIsReadAndCorruptEntryIsRemoved() throws Exception {
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        ManagedTileCache cache = new ManagedTileCache(temporary, decoder);
        TileRequest request = request(address("hot", 2), 9, TilePurpose.ALIGNMENT_REQUIRED);
        byte[] bytes = png(tile(new Color(255, 80, 0, 255)));
        java.nio.file.Path path = cache.path(request.generation(), request.address());
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.write(path, bytes);

        TileFetchResult result = cache.read(request).orElseThrow();
        assertEquals(TileFetchStatus.SUCCESS_DISK_CACHE, result.status());
        assertArrayEquals(bytes, result.encodedBytes());

        java.nio.file.Files.write(path, new byte[] {1, 2, 3});
        assertTrue(cache.read(request).isEmpty());
        assertFalse(java.nio.file.Files.exists(path));

        ManagedTileCache limited = new ManagedTileCache(temporary, decoder, 1024);
        TileRequest oversized = request(address("hot", 20), 9, TilePurpose.ALIGNMENT_REQUIRED);
        java.nio.file.Path oversizedPath = limited.path(oversized.generation(), oversized.address());
        java.nio.file.Files.createDirectories(oversizedPath.getParent());
        java.nio.file.Files.write(oversizedPath, new byte[1025]);
        assertTrue(limited.read(oversized).isEmpty());
        assertFalse(java.nio.file.Files.exists(oversizedPath));
    }

    @Test
    void concurrentIdenticalRequestsUseOneTransportExecution() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        byte[] body = png(tile(new Color(255, 120, 0, 255)));
        ManagedTileTransport transport = (request, credentials) -> {
            executions.incrementAndGet();
            entered.countDown();
            await(release);
            return success(body);
        };
        try (TileFetchCoordinator coordinator = coordinator(transport, Clock.systemUTC())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(3));
            List<CompletableFuture<TileFetchResult>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                futures.add(coordinator.fetch(request(address("hot", 3), 3,
                    TilePurpose.ALIGNMENT_REQUIRED), credentials()).toCompletableFuture());
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            release.countDown();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            assertEquals(1, executions.get());
            assertTrue(futures.stream().allMatch(future -> future.join().usable()));
            assertEquals(99, coordinator.stats().singleFlightJoins());
        }
    }

    @Test
    void repeatedFailedRequestsStaySuppressedUntilFakeClockPassesTtl() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T00:00:00Z"));
        AtomicInteger executions = new AtomicInteger();
        ManagedTileTransport transport = (request, credentials) -> {
            executions.incrementAndGet();
            return new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null, null,
                Duration.ZERO, "http-no-tile");
        };
        try (TileFetchCoordinator coordinator = coordinator(transport, clock)) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(4));
            TileRequest request = request(address("hot", 4), 4, TilePurpose.LIVE_AGGREGATE_VISUALIZATION);
            assertEquals(TileFetchStatus.NO_TILE, coordinator.fetch(request, credentials()).toCompletableFuture()
                .get(2, TimeUnit.SECONDS).status());
            for (int i = 0; i < 1_000; i++) {
                coordinator.fetch(request, credentials()).toCompletableFuture().join();
            }
            assertEquals(1, executions.get());
            clock.advance(Duration.ofMinutes(6));
            coordinator.fetch(request, credentials()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(2, executions.get());
        }
    }

    @Test
    void authAndRateLimitCircuitsPreventRequestMultiplication() throws Exception {
        AtomicInteger authExecutions = new AtomicInteger();
        ManagedTileTransport auth = (request, credentials) -> {
            authExecutions.incrementAndGet();
            return new TransportResponse(TileFetchStatus.AUTH_FAILURE, 403, "text/html", null, null,
                Duration.ZERO, "http-auth");
        };
        try (TileFetchCoordinator coordinator = coordinator(auth, Clock.systemUTC())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(5));
            coordinator.fetch(request(address("hot", 5), 5, TilePurpose.ALIGNMENT_REQUIRED), credentials())
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            for (int i = 6; i < 50; i++) {
                TileFetchResult result = coordinator.fetch(request(address("blue", i), 5,
                    TilePurpose.LIVE_AGGREGATE_VISUALIZATION), credentials()).toCompletableFuture().join();
                assertEquals(TileFetchStatus.AUTH_FAILURE, result.status());
            }
            assertEquals(1, authExecutions.get());
        }

        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T00:00:00Z"));
        AtomicInteger rateExecutions = new AtomicInteger();
        ManagedTileTransport rate = (request, credentials) -> {
            rateExecutions.incrementAndGet();
            return new TransportResponse(TileFetchStatus.RATE_LIMITED, 429, "", null,
                clock.instant().plusSeconds(60), Duration.ZERO, "http-rate-limit");
        };
        try (TileFetchCoordinator coordinator = coordinator(rate, clock)) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(6));
            coordinator.fetch(request(address("hot", 6), 6, TilePurpose.ALIGNMENT_REQUIRED), credentials())
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(TileFetchStatus.RATE_LIMITED, coordinator.fetch(request(address("gray", 7), 6,
                TilePurpose.LIVE_AGGREGATE_VISUALIZATION), credentials()).toCompletableFuture().join().status());
            assertEquals(1, rateExecutions.get());
        }
    }

    @Test
    void generationChangeDiscardsOldInFlightCompletionBeforeCachePublication() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        byte[] body = png(tile(new Color(255, 120, 0, 255)));
        ManagedTileTransport transport = (request, credentials) -> {
            entered.countDown();
            await(release);
            return success(body);
        };
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        ManagedTileCache cache = new ManagedTileCache(temporary, decoder);
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator(transport, cache, decoder,
            TileReliabilityPolicy.defaults(), Clock.systemUTC())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(10));
            TileRequest oldRequest = request(address("hot", 10), 10, TilePurpose.ALIGNMENT_REQUIRED);
            CompletableFuture<TileFetchResult> old = coordinator.fetch(oldRequest, credentials()).toCompletableFuture();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            coordinator.updateActiveGeneration(new ManagedTileGeneration(11));
            release.countDown();

            assertEquals(TileFetchStatus.STALE_GENERATION, old.get(2, TimeUnit.SECONDS).status());
            assertFalse(java.nio.file.Files.exists(cache.path(oldRequest.generation(), oldRequest.address())));
        }
    }

    @Test
    void requiredTransientFailureRetriesOnceButLiveFailureDoesNot() throws Exception {
        AtomicInteger requiredExecutions = new AtomicInteger();
        byte[] body = png(tile(new Color(255, 120, 0, 255)));
        ManagedTileTransport required = (request, credentials) -> requiredExecutions.incrementAndGet() == 1
            ? new TransportResponse(TileFetchStatus.HTTP_SERVER_ERROR, 503, "", null, null,
                Duration.ZERO, "http-server")
            : success(body);
        try (TileFetchCoordinator coordinator = coordinator(required, Clock.systemUTC())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(12));
            TileFetchResult result = coordinator.fetch(request(address("hot", 12), 12,
                TilePurpose.ALIGNMENT_REQUIRED), credentials()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(result.usable());
            assertEquals(2, result.attemptCount());
            assertEquals(2, requiredExecutions.get());
        }

        AtomicInteger liveExecutions = new AtomicInteger();
        ManagedTileTransport live = (request, credentials) -> {
            liveExecutions.incrementAndGet();
            return new TransportResponse(TileFetchStatus.HTTP_SERVER_ERROR, 503, "", null, null,
                Duration.ZERO, "http-server");
        };
        try (TileFetchCoordinator coordinator = coordinator(live, Clock.systemUTC())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(13));
            TileFetchResult result = coordinator.fetch(request(address("hot", 13), 13,
                TilePurpose.LIVE_AGGREGATE_VISUALIZATION), credentials()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(TileFetchStatus.HTTP_SERVER_ERROR, result.status());
            assertEquals(1, liveExecutions.get());
        }
    }

    @Test
    void expiredDeadlineDoesNotStartTransport() {
        AtomicInteger executions = new AtomicInteger();
        ManagedTileTransport transport = (request, credentials) -> {
            executions.incrementAndGet();
            return new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null, null,
                Duration.ZERO, "http-no-tile");
        };
        try (TileFetchCoordinator coordinator = coordinator(transport, Clock.systemUTC())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(14));
            TileRequest expired = new TileRequest(address("hot", 14), new ManagedTileGeneration(14),
                TilePurpose.ALIGNMENT_REQUIRED, TileCachePolicy.USE_CACHE, Instant.now().minusSeconds(1),
                new CancellationToken());

            assertEquals(TileFetchStatus.OFFLINE,
                coordinator.fetch(expired, credentials()).toCompletableFuture().join().status());
            assertEquals(0, executions.get());
        }
    }

    @Test
    void cancellingOneSubscriberDoesNotCancelSharedTransportForAnother() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        byte[] body = png(tile(new Color(255, 120, 0, 255)));
        ManagedTileTransport transport = (request, credentials) -> {
            entered.countDown();
            await(release);
            return success(body);
        };
        try (TileFetchCoordinator coordinator = coordinator(transport, Clock.systemUTC())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(15));
            CancellationToken firstToken = new CancellationToken();
            TileRequest firstRequest = new TileRequest(address("hot", 15), new ManagedTileGeneration(15),
                TilePurpose.ALIGNMENT_REQUIRED, TileCachePolicy.USE_CACHE, Instant.now().plusSeconds(30), firstToken);
            TileRequest secondRequest = new TileRequest(address("hot", 15), new ManagedTileGeneration(15),
                TilePurpose.ALIGNMENT_REQUIRED, TileCachePolicy.USE_CACHE, Instant.now().plusSeconds(30),
                new CancellationToken());
            CompletableFuture<TileFetchResult> first = coordinator.fetch(firstRequest, credentials()).toCompletableFuture();
            CompletableFuture<TileFetchResult> second = coordinator.fetch(secondRequest, credentials()).toCompletableFuture();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            firstToken.cancel();
            release.countDown();

            assertEquals(TileFetchStatus.CANCELLED, first.get(2, TimeUnit.SECONDS).status());
            assertTrue(second.get(2, TimeUnit.SECONDS).usable());
        }
    }

    @Test
    void boundedDispatchNeverExecutesCallbackInline() throws Exception {
        ManagedTileTransport transport = (request, credentials) -> new TransportResponse(
            TileFetchStatus.NO_TILE, 404, "", null, null, Duration.ZERO, "http-no-tile");
        try (TileFetchCoordinator coordinator = coordinator(transport, Clock.systemUTC())) {
            String caller = Thread.currentThread().getName();
            CompletableFuture<String> callbackThread = new CompletableFuture<>();

            assertTrue(coordinator.dispatch(TilePurpose.LIVE_AGGREGATE_VISUALIZATION,
                () -> callbackThread.complete(Thread.currentThread().getName())));

            assertFalse(caller.equals(callbackThread.get(2, TimeUnit.SECONDS)));
        }
    }

    @Test
    void unsafeReasonTextCannotEnterResultOrDiagnostics() throws Exception {
        ManagedTileTransport transport = (request, credentials) -> new TransportResponse(
            TileFetchStatus.NETWORK_ERROR, -1, "", null, null, Duration.ZERO, SENTINEL);
        try (TileFetchCoordinator coordinator = coordinator(transport, Clock.systemUTC())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(21));
            TileFetchResult result = coordinator.fetch(request(address("hot", 21), 21,
                TilePurpose.LIVE_AGGREGATE_VISUALIZATION), credentials()).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals("unclassified", result.safeReasonCode());
            assertFalse(result.toString().contains(SENTINEL));
            assertFalse(coordinator.diagnosticsJson().contains(SENTINEL));
        }
    }

    @Test
    void loopbackTransportForwardsCookieButNeverReturnsOrPrintsIt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<String> receivedCookies = new ArrayList<>();
        byte[] png = png(tile(new Color(255, 120, 0, 255)));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/identified/globalheat/all/hot/15/1/1.png", exchange -> {
            requests.incrementAndGet();
            receivedCookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        server.start();
        try {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            TileReliabilityPolicy policy = TileReliabilityPolicy.defaults();
            HttpUrlConnectionTileTransport transport = new HttpUrlConnectionTileTransport(
                new ManagedTileUrlBuilder(origin, true), policy);
            TileRequest request = request(new ManagedTileAddress("all", "hot", 15, 1, 1), 8,
                TilePurpose.ACCESS_PROBE);
            TransportResponse response = transport.execute(request, newCredential(SENTINEL));
            assertEquals(TileFetchStatus.SUCCESS_NETWORK, response.status());
            assertEquals(1, requests.get());
            assertEquals(SENTINEL, receivedCookies.get(0));
            assertFalse(response.toString().contains(SENTINEL));
            assertFalse(newCredential(SENTINEL).toString().contains(SENTINEL));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transportRejectsRedirectAndOversizedBodyWithoutFollowingOrRetainingIt() throws Exception {
        AtomicInteger redirectTargetHits = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/target", exchange -> {
            redirectTargetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        target.start();
        HttpServer source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext("/identified/globalheat/all/hot/15/1/1.png", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        source.start();
        try {
            TileReliabilityPolicy policy = TileReliabilityPolicy.defaults();
            URI origin = URI.create("http://127.0.0.1:" + source.getAddress().getPort());
            HttpUrlConnectionTileTransport transport = new HttpUrlConnectionTileTransport(
                new ManagedTileUrlBuilder(origin, true), policy);
            TransportResponse response = transport.execute(request(new ManagedTileAddress("all", "hot", 15, 1, 1),
                9, TilePurpose.ACCESS_PROBE), newCredential(SENTINEL));
            assertEquals(TileFetchStatus.UNSAFE_REDIRECT, response.status());
            assertEquals(0, redirectTargetHits.get());
            assertEquals(0, response.body().length);
        } finally {
            source.stop(0);
            target.stop(0);
        }
    }

    @Test
    void selectedSourceProbeUsesOnlySelectedPaletteAndAcceptsTransparentTile() throws Exception {
        List<ManagedTileAddress> addresses = new ArrayList<>();
        List<Boolean> edtExecutions = new ArrayList<>();
        byte[] transparent = png(tile(new Color(0, 0, 0, 0)));
        ManagedTileTransport transport = (request, credentials) -> {
            addresses.add(request.address());
            edtExecutions.add(javax.swing.SwingUtilities.isEventDispatchThread());
            return success(transparent);
        };
        try (TileFetchCoordinator coordinator = coordinator(transport, Clock.systemUTC())) {
            ManagedHeatmapConfig config = config("purple", 16L);
            SelectedSourceProbeResult result = new SelectedSourceHealthProbe(coordinator)
                .probe(config, new LatLon(50.0, 14.0), TileCachePolicy.NETWORK_ONLY_NO_WRITE,
                    new CancellationToken())
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertTrue(result.available());
            assertEquals(TileFetchStatus.SUCCESS_NETWORK, result.status());
            assertEquals(1, addresses.size());
            assertEquals("purple", addresses.get(0).color());
            assertEquals(15, addresses.get(0).zoom());
            assertEquals(List.of(false), edtExecutions);
            assertFalse(result.message().contains(SENTINEL));
        }
    }

    @Test
    void retryAfterSupportsDeltaDateAndBoundedFallback() {
        TileReliabilityPolicy policy = TileReliabilityPolicy.defaults();
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        assertEquals(now.plusSeconds(30), HttpUrlConnectionTileTransport.parseRetryAfter("30", now, policy));
        assertEquals(now.plusSeconds(60), HttpUrlConnectionTileTransport.parseRetryAfter("invalid", now, policy));
        assertEquals(now.plus(Duration.ofMinutes(15)),
            HttpUrlConnectionTileTransport.parseRetryAfter("999999", now, policy));
        assertEquals(now.plusSeconds(120), HttpUrlConnectionTileTransport.parseRetryAfter(
            "Mon, 31 Aug 2026 00:02:00 GMT", now, policy));
    }

    private TileFetchCoordinator coordinator(ManagedTileTransport transport, Clock clock) {
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        return new TileFetchCoordinator(transport, new ManagedTileCache(temporary, decoder), decoder,
            TileReliabilityPolicy.defaults(), clock);
    }

    private TileRequest request(ManagedTileAddress address, long generation, TilePurpose purpose) {
        return new TileRequest(address, new ManagedTileGeneration(generation), purpose, TileCachePolicy.USE_CACHE,
            Instant.now().plusSeconds(30), new CancellationToken());
    }

    private ManagedTileAddress address(String color, int x) {
        return new ManagedTileAddress("all", color, 15, x, 1);
    }

    private CredentialSnapshot credentials() {
        return newCredential("test-cookie");
    }

    private CredentialSnapshot newCredential(String value) {
        return CredentialSnapshot.forTesting(value);
    }

    private ManagedHeatmapConfig config(String color, long generation) {
        return new ManagedHeatmapConfig("key", "policy", "signature", "session", "all", color, "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, TrackerMode.CORRIDOR_AWARE, false, false, false,
            false, true, false, false, false, false, false, 18, 4, 2.0,
            InferenceMode.STABLE_FIXED_SCALE, 15, 13, 7.0, 1.56,
            IntensitySamplingMode.COLOR_MAPPING, generation);
    }

    private TransportResponse success(byte[] body) {
        return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png", body, null,
            Duration.ZERO, "");
    }

    private BufferedImage tile(Color color) {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        return image;
    }

    private byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test release");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

class SelectedSourceHealthProbeTest {
    private static final String SENTINEL = "credential-sentinel";

    @TempDir
    java.nio.file.Path temporary;

    @Test
    void boundsStencilToFiveUniqueTilesAndUsesFreshNetworkPolicy() throws Exception {
        List<ManagedTileAddress> addresses = new ArrayList<>();
        List<TileCachePolicy> policies = new ArrayList<>();
        ManagedTileTransport transport = (request, credentials) -> {
            addresses.add(request.address());
            policies.add(request.cachePolicy());
            return noTile();
        };
        try (TileFetchCoordinator coordinator = coordinator(transport)) {
            SelectedSourceProbeResult result = new SelectedSourceHealthProbe(coordinator)
                .probe(config(), sixDistinctLocations(), TileCachePolicy.BYPASS_READ_ALLOW_WRITE,
                    new CancellationToken())
                .toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(5, addresses.size());
            assertEquals(5, result.sampledTileCount());
            assertTrue(result.freshNetworkAttempted());
            assertTrue(policies.stream().allMatch(policy -> policy == TileCachePolicy.BYPASS_READ_ALLOW_WRITE));
            assertEquals(TileFetchStatus.NO_TILE, result.status());
            assertFalse(result.available());
            assertTrue(result.message().contains("sampled area"));
            assertTrue(result.message().contains("access was not verified"));
            assertFalse(result.message().contains(SENTINEL));
        }
    }

    @Test
    void stopsAfterFirstValidatedNetworkTile() throws Exception {
        List<ManagedTileAddress> addresses = new ArrayList<>();
        byte[] valid = png(new Color(255, 40, 0, 255));
        ManagedTileTransport transport = (request, credentials) -> {
            addresses.add(request.address());
            return addresses.size() == 1 ? noTile() : success(valid);
        };
        try (TileFetchCoordinator coordinator = coordinator(transport)) {
            SelectedSourceProbeResult result = new SelectedSourceHealthProbe(coordinator)
                .probe(config(), sixDistinctLocations(), TileCachePolicy.BYPASS_READ_ALLOW_WRITE,
                    new CancellationToken())
                .toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(2, addresses.size());
            assertEquals(TileFetchStatus.SUCCESS_NETWORK, result.status());
            assertTrue(result.available());
            assertTrue(result.message().contains("fresh network"));
        }
    }

    @Test
    void authenticationAndRateLimitMessagesAreTerminalAndTruthful() throws Exception {
        List<ManagedTileAddress> authAddresses = new ArrayList<>();
        ManagedTileTransport auth = (request, credentials) -> {
            authAddresses.add(request.address());
            return new TransportResponse(TileFetchStatus.AUTH_FAILURE, 403, "text/html", null, null,
                Duration.ZERO, "http-auth");
        };
        try (TileFetchCoordinator coordinator = coordinator(auth)) {
            SelectedSourceProbeResult result = new SelectedSourceHealthProbe(coordinator)
                .probe(config(), sixDistinctLocations(), TileCachePolicy.BYPASS_READ_ALLOW_WRITE,
                    new CancellationToken())
                .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(1, authAddresses.size());
            assertEquals(TileFetchStatus.AUTH_FAILURE, result.status());
            assertTrue(result.message().contains("authentication failed"));
        }

        List<ManagedTileAddress> rateAddresses = new ArrayList<>();
        ManagedTileTransport rate = (request, credentials) -> {
            rateAddresses.add(request.address());
            return new TransportResponse(TileFetchStatus.RATE_LIMITED, 429, "", null,
                java.time.Instant.now().plusSeconds(60), Duration.ZERO, "http-rate-limit");
        };
        try (TileFetchCoordinator coordinator = coordinator(rate)) {
            SelectedSourceProbeResult result = new SelectedSourceHealthProbe(coordinator)
                .probe(config(), sixDistinctLocations(), TileCachePolicy.BYPASS_READ_ALLOW_WRITE,
                    new CancellationToken())
                .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(1, rateAddresses.size());
            assertEquals(TileFetchStatus.RATE_LIMITED, result.status());
            assertTrue(result.message().contains("rate limited"));
        }
    }

    @Test
    void cacheOnlyResultDoesNotClaimFreshNetworkHealth() throws Exception {
        ManagedTileAddress address = new ManagedTileAddress("all", "hot", 15, 1, 1);
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        TileFetchResult cached = new TileFetchResult(address, new ManagedTileGeneration(1),
            TilePurpose.ACCESS_PROBE, TileFetchStatus.SUCCESS_MEMORY_CACHE, image, new byte[0], -1,
            "image/png", Duration.ZERO, 0, null, TileQuality.unavailable("cached"), "", "");

        SelectedSourceProbeResult result = SelectedSourceProbeResult.from(cached);

        assertTrue(result.available());
        assertFalse(result.freshNetworkAttempted());
        assertTrue(result.message().contains("plugin cache"));
        assertTrue(result.message().contains("fresh network access was not tested"));
    }

    private TileFetchCoordinator coordinator(ManagedTileTransport transport) {
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        return new TileFetchCoordinator(transport, new ManagedTileCache(temporary, decoder), decoder,
            TileReliabilityPolicy.defaults(), Clock.systemUTC());
    }

    private ManagedHeatmapConfig config() {
        return new ManagedHeatmapConfig("key", "policy", "signature", "session", "all", "hot", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, TrackerMode.CORRIDOR_AWARE, false, false, false,
            false, true, false, false, false, false, false, 18, 4, 2.0,
            InferenceMode.STABLE_FIXED_SCALE, 15, 13, 7.0, 1.56,
            IntensitySamplingMode.COLOR_MAPPING, 31L);
    }

    private List<LatLon> sixDistinctLocations() {
        return List.of(
            new LatLon(50.0, 14.000),
            new LatLon(50.0, 14.020),
            new LatLon(50.0, 14.040),
            new LatLon(50.0, 14.060),
            new LatLon(50.0, 14.080),
            new LatLon(50.0, 14.100)
        );
    }

    private TransportResponse noTile() {
        return new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null, null,
            Duration.ZERO, "http-no-tile");
    }

    private TransportResponse success(byte[] body) {
        return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png", body, null,
            Duration.ZERO, "");
    }

    private byte[] png(Color color) throws Exception {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}

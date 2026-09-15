package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

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

class ManagedModernPreviewSourceTest {
    @TempDir java.nio.file.Path temporary;

    @Test
    void selectedNativeTileUsesBoundaryCenterTransformAndPreservesMissingSupport() throws Exception {
        byte[] green = png(new Color(0, 255, 0, 255));
        ManagedTileTransport transport = (request, credentials) -> request.address().x() == 0
            ? new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png", green, null,
                Duration.ZERO, "")
            : new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null, null, Duration.ZERO, "http-no-tile");
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator(transport,
                new ManagedTileCache(temporary, decoder), decoder, TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(5));
            ManagedModernPreviewSource source = new ManagedModernPreviewSource(coordinator);
            ManagedModernPreviewSource.Request request = new ManagedModernPreviewSource.Request(
                    List.of(new GeographicPoint(0.0, -0.1), new GeographicPoint(0.0, 0.1)),
                    "all", "hot", 1, new ManagedTileGeneration(5), 4.0, 1.0, "managed-test");
            ManagedModernPreviewSource.Raster raster = source.acquire(request,
                    CredentialSnapshot.fromConfig(null), () -> false);

            assertEquals("hot", raster.palette());
            assertEquals(1, raster.zoom());
            assertEquals(1024, raster.image().getHeight());
            assertEquals(1024, raster.image().getWidth());
            assertTrue(raster.validity()[0]);
            assertFalse(raster.validity()[600]);
            assertEquals(-0.5, raster.transform().toRasterCenter(
                    new GeographicPoint(0.0, -180.0)).x(), 1.0e-12);
        }
    }

    @Test
    void staleQueuedAcquisitionCannotReactivateAnOlderRuntimeGeneration() {
        AtomicInteger transports = new AtomicInteger();
        ManagedTileTransport transport = (request, credentials) -> {
            transports.incrementAndGet();
            return new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null, null,
                    Duration.ZERO, "http-no-tile");
        };
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator(transport,
                new ManagedTileCache(temporary, decoder), decoder, TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(2));
            ManagedModernPreviewSource.Request stale = new ManagedModernPreviewSource.Request(
                    List.of(new GeographicPoint(10.0, 10.0), new GeographicPoint(10.001, 10.0)),
                    "all", "hot", 15, new ManagedTileGeneration(1), 7.01, 1.56, "managed-old");

            assertThrows(IllegalStateException.class, () -> new ManagedModernPreviewSource(coordinator)
                    .acquire(stale, CredentialSnapshot.fromConfig(null), () -> false));

            assertEquals(0, transports.get());
            assertTrue(coordinator.diagnosticsJson().contains("\"activeGeneration\":2"));
        }
    }

    @Test
    void decisionAndInterpolationHaloAcquireExactNeighborsAcrossXYTileBoundaries() throws Exception {
        byte[] green = png(new Color(0, 255, 0, 255));
        Set<String> requested = java.util.Collections.synchronizedSet(new HashSet<>());
        ManagedTileTransport transport = (request, credentials) -> {
            requested.add(request.address().x() + "/" + request.address().y());
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png", green,
                    null, Duration.ZERO, "");
        };
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator(transport,
                new ManagedTileCache(temporary, decoder), decoder, TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(3));
            double eastMeters = 1.1;
            double longitude = Math.toDegrees(eastMeters / 6_378_137.0);
            ManagedModernPreviewSource.Request xBoundary = new ManagedModernPreviewSource.Request(
                    List.of(new GeographicPoint(9.99999, longitude),
                            new GeographicPoint(10.00001, longitude)),
                    "all", "hot", 15, new ManagedTileGeneration(3), 7.01, 1.56, "managed-x");
            new ManagedModernPreviewSource(coordinator).acquire(xBoundary,
                    CredentialSnapshot.fromConfig(null), () -> false);
            int xY = tileY(10.0, 15);
            assertEquals(Set.of("16383/" + xY, "16384/" + xY), Set.copyOf(requested));

            requested.clear();
            int boundaryY = 15_000;
            double boundaryLatitude = tileBoundaryLatitude(boundaryY, 15);
            double southLatitude = boundaryLatitude - Math.toDegrees(1.1 / 6_378_137.0);
            double centerLongitude = 10.0;
            double deltaLongitude = Math.toDegrees(1.0 / (6_378_137.0
                    * Math.cos(Math.toRadians(southLatitude))));
            ManagedModernPreviewSource.Request yBoundary = new ManagedModernPreviewSource.Request(
                    List.of(new GeographicPoint(southLatitude, centerLongitude - deltaLongitude),
                            new GeographicPoint(southLatitude, centerLongitude + deltaLongitude)),
                    "all", "hot", 15, new ManagedTileGeneration(3), 7.01, 1.56, "managed-y");
            new ManagedModernPreviewSource(coordinator).acquire(yBoundary,
                    CredentialSnapshot.fromConfig(null), () -> false);
            int tileX = (int) Math.floor((centerLongitude + 180.0) / 360.0 * (1 << 15));
            assertEquals(Set.of(tileX + "/14999", tileX + "/15000"), Set.copyOf(requested));
        }
    }

    @Test
    void changedGenerationCannotBecomeValidManagedEvidence() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        byte[] green = png(new Color(0, 255, 0, 255));
        ManagedTileTransport transport = (request, credentials) -> {
            entered.countDown();
            assertTrue(await(release));
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png", green,
                    null, Duration.ZERO, "");
        };
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator(transport,
                new ManagedTileCache(temporary, decoder), decoder, TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(9));
            ManagedModernPreviewSource source = new ManagedModernPreviewSource(coordinator);
            ManagedModernPreviewSource.Request request = new ManagedModernPreviewSource.Request(
                    List.of(new GeographicPoint(0.0, -0.1), new GeographicPoint(0.0, 0.1)),
                    "all", "hot", 1, new ManagedTileGeneration(9), 4.0, 1.0, "managed-generation");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    source.acquire(request, CredentialSnapshot.fromConfig(null), () -> false);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            worker.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            coordinator.updateActiveGeneration(new ManagedTileGeneration(10));
            release.countDown();
            worker.join(2000);
            assertFalse(worker.isAlive());
            assertTrue(failure.get() instanceof IllegalStateException,
                    "stale generation must fail before raster attachment");
        }
    }

    @Test
    void cancelledManagedAcquisitionReturnsNoRasterAfterItsInFlightTileCompletes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        byte[] green = png(new Color(0, 255, 0, 255));
        ManagedTileTransport transport = (request, credentials) -> {
            entered.countDown();
            assertTrue(await(release));
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png", green,
                    null, Duration.ZERO, "");
        };
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator(transport,
                new ManagedTileCache(temporary, decoder), decoder, TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(8));
            ManagedModernPreviewSource source = new ManagedModernPreviewSource(coordinator);
            ManagedModernPreviewSource.Request request = new ManagedModernPreviewSource.Request(
                    List.of(new GeographicPoint(0.0, -0.1), new GeographicPoint(0.0, 0.1)),
                    "all", "hot", 1, new ManagedTileGeneration(8), 4.0, 1.0, "managed-cancel");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    source.acquire(request, CredentialSnapshot.fromConfig(null), cancelled::get);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            worker.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            release.countDown();
            worker.join(2000);
            assertFalse(worker.isAlive());
            assertTrue(failure.get() instanceof java.util.concurrent.CancellationException);
        }
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int tileY(double latitude, int zoom) {
        double radians = Math.toRadians(latitude);
        return (int) Math.floor((1.0 - Math.log(Math.tan(radians) + 1.0 / Math.cos(radians))
                / Math.PI) * 0.5 * (1 << zoom));
    }

    private static double tileBoundaryLatitude(int y, int zoom) {
        double mercator = Math.PI * (1.0 - 2.0 * y / (double) (1 << zoom));
        return Math.toDegrees(Math.atan(Math.sinh(mercator)));
    }

    private static byte[] png(Color color) throws Exception {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 512, 512);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}

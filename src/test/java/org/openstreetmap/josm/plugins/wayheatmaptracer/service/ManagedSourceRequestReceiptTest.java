package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.AbstractList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TransportResponse;

class ManagedSourceRequestReceiptTest {
    @TempDir Path cache;

    @Test void originalSearchReceiptRefusesAnAcquiredButUnauthorizedConnector() {
        var owner = AttemptMemoryLedger.production().rootOwner();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try {
            var projection = Projections.getProjectionByCode("EPSG:3857");
            var parameters = new TileHeatmapSampler.SamplingParameters(15, 49, 3, 5, 1, 2, 1);
            var source = List.of(new EastNorth(0, 0), new EastNorth(50, 0));
            double virtualScale = 40.0;
            var receipt = ManagedSourceRequestReceipt.captureBeforeFetch(source, "hot", 15,
                    parameters, virtualScale, projection,
                    point -> new Point2D.Double(150.0 + point.east(), 150.0 + point.north()),
                    budget, owner);
            assertEquals(0, receipt.minTileX());
            assertEquals(0, receipt.maxTileX());
            assertEquals(0, receipt.minTileY());
            assertEquals(0, receipt.maxTileY());
            assertTrue(receipt.containsOriginalWorldPixel(new RasterPoint(175, 150)));
            assertFalse(receipt.containsOriginalWorldPixel(new RasterPoint(175, 160)));
            var image = new BufferedImage(TileHeatmapSampler.TILE_SIZE, TileHeatmapSampler.TILE_SIZE,
                    BufferedImage.TYPE_INT_ARGB);
            var mosaic = new TileHeatmapSampler.TileMosaic("hot", 15, 0.0, 0.0, image,
                    List.of(), Map.of(), parameters, true, virtualScale);
            var scratch = owner.child("search-query");
            assertTrue(receipt.authorizesVirtualRasterSegment(mosaic,
                    virtual(160, 150, virtualScale), virtual(190, 150, virtualScale), budget, scratch));
            assertFalse(receipt.authorizesVirtualRasterSegment(mosaic,
                    virtual(160, 160, virtualScale), virtual(190, 160, virtualScale), budget, scratch));
            assertFalse(receipt.authorizesVirtualRasterSegment(mosaic,
                    new RasterPoint(1e150, 1e150), virtual(190, 150, virtualScale), budget, scratch));
        } finally {
            owner.close();
        }
    }

    @Test void originalTurnCapIncludesLegacyCenterSecantBandWithoutExpandingRadius() {
        var owner = AttemptMemoryLedger.production().rootOwner();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try {
            var projection = Projections.getProjectionByCode("EPSG:3857");
            var parameters = new TileHeatmapSampler.SamplingParameters(15, 49, 3, 5, 1, 2, 1);
            var source = List.of(new EastNorth(0, 0), new EastNorth(4, 0), new EastNorth(4, 4));
            var shape = TileHeatmapSampler.legacySamplingShape(PolylineMath.length(source),
                    source.size(), parameters);
            assertEquals(3, shape.profiles());
            assertEquals(source.get(1), PolylineMath.resampleByCount(source, shape.profiles()).get(1));
            double virtualScale = 40.0;
            var receipt = ManagedSourceRequestReceipt.captureBeforeFetch(source, "hot", 15,
                    parameters, virtualScale, projection,
                    point -> new Point2D.Double(150.0 + point.east(), 150.0 + point.north()),
                    budget, owner);
            double radius = receipt.nativeSearchHalfWidth();
            double diagonal = Math.sqrt(0.5);
            RasterPoint measuredOutsideTurn = new RasterPoint(154 + 0.9 * radius * diagonal,
                    150 - 0.9 * radius * diagonal);
            RasterPoint beyondOriginalSearch = new RasterPoint(154 + 1.1 * radius * diagonal,
                    150 - 1.1 * radius * diagonal);
            assertTrue(receipt.containsOriginalWorldPixel(measuredOutsideTurn));
            assertFalse(receipt.containsOriginalWorldPixel(beyondOriginalSearch));
            var image = new BufferedImage(TileHeatmapSampler.TILE_SIZE, TileHeatmapSampler.TILE_SIZE,
                    BufferedImage.TYPE_INT_ARGB);
            var mosaic = new TileHeatmapSampler.TileMosaic("hot", 15, 0.0, 0.0, image,
                    List.of(), Map.of(), parameters, true, virtualScale);
            var scratch = owner.child("turn-query");
            assertTrue(receipt.authorizesVirtualRasterSegment(mosaic,
                    virtual(154, 150, virtualScale),
                    virtual(measuredOutsideTurn.x(), measuredOutsideTurn.y(), virtualScale),
                    budget, scratch));
            assertFalse(receipt.authorizesVirtualRasterSegment(mosaic,
                    virtual(154, 150, virtualScale),
                    virtual(beyondOriginalSearch.x(), beyondOriginalSearch.y(), virtualScale),
                    budget, scratch));
        } finally {
            owner.close();
        }
    }

    @Test void wholeConnectorCannotCrossAnUnmeasuredHoleBetweenSourceArms() {
        var region = new WorldPixelCapsuleRegion(List.of(new RasterPoint(10, 10),
                new RasterPoint(10, 20), new RasterPoint(20, 20),
                new RasterPoint(20, 10)), 1.0);
        var owner = AttemptMemoryLedger.production().rootOwner();
        try {
            var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
            assertTrue(region.contains(new RasterPoint(10, 10)));
            assertTrue(region.contains(new RasterPoint(20, 10)));
            assertFalse(region.containsSegment(new RasterPoint(10, 10),
                    new RasterPoint(20, 10), budget, owner));
        } finally {
            owner.close();
        }
    }

    @Test void oversizedAndCancelledSourceAreRejectedBeforeProjectionPass() {
        var projection = Projections.getProjectionByCode("EPSG:3857");
        var settings = managedSettings();
        var decoder = new TileDecoderClassifier();
        try (var coordinator = new TileFetchCoordinator((request, credentials) -> {
                    throw new AssertionError("No tile fetch is allowed");
                }, new ManagedTileCache(cache, decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            var sampler = new TileHeatmapSampler(coordinator);
            List<EastNorth> neverRead = new AbstractList<>() {
                @Override public int size() { return 4098; }
                @Override public EastNorth get(int index) {
                    throw new AssertionError("Oversized source was projected");
                }
            };
            var oversizedOwner = AttemptMemoryLedger.production().rootOwner();
            try {
                assertThrows(IllegalArgumentException.class,
                        () -> sampler.prepareForEmission(settings, neverRead, List.of("hot"),
                                "hot", false, projection,
                                new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE),
                                oversizedOwner));
            } finally { oversizedOwner.close(); }
            List<EastNorth> cancelledSource = new AbstractList<>() {
                @Override public int size() { return 2; }
                @Override public EastNorth get(int index) {
                    throw new AssertionError("Cancelled source was projected");
                }
            };
            var cancelledOwner = AttemptMemoryLedger.production().rootOwner();
            try {
                assertThrows(CancellationException.class,
                        () -> sampler.prepareForEmission(settings, cancelledSource, List.of("hot"),
                                "hot", false, projection,
                                new EmissionWorkBudget(TraceBudgets.defaults(), () -> true),
                                cancelledOwner));
            } finally { cancelledOwner.close(); }
        }
    }

    @Test void emissionPreparationRefusesMemoryBeforeAnyTileFetch() {
        var projection = Projections.getProjectionByCode("EPSG:3857");
        var source = List.of(projection.latlon2eastNorth(new LatLon(49.44, 14.0)),
                projection.latlon2eastNorth(new LatLon(49.44, 14.0001)));
        var settings = new ManagedHeatmapConfig("key", "policy", "signature", "token",
                "all", "hot", "", ".*", AlignmentMode.MOVE_EXISTING_NODES,
                TrackerMode.LEGACY_V02, false, false, false, false, false, false,
                true, false, false, false, 18, 4, 3.0, InferenceMode.STABLE_FIXED_SCALE,
                15, 15, 28.0, 6.0, IntensitySamplingMode.COLOR_MAPPING, 0L);
        var calls = new AtomicInteger();
        var decoder = new TileDecoderClassifier();
        var ledger = new AttemptMemoryLedger(64 * 1024L);
        var owner = ledger.rootOwner();
        try (var coordinator = new TileFetchCoordinator((request, credentials) -> {
                    calls.incrementAndGet();
                    throw new AssertionError("Tile fetch preceded memory admission");
                }, new ManagedTileCache(cache, decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            var sampler = new TileHeatmapSampler(coordinator);
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> sampler.prepareForEmission(settings, source, List.of("hot"), "hot",
                            false, projection, new EmissionWorkBudget(TraceBudgets.defaults(),
                                    CancellationProbe.NONE), owner));
            assertEquals(0, calls.get());
            assertEquals(0, ledger.currentBytes());
        } finally {
            owner.close();
        }
    }

    @Test void acquiredMosaicAndRequestStayChargedUntilEveryRetainedOwnerCloses() throws Exception {
        var projection = Projections.getProjectionByCode("EPSG:3857");
        var source = List.of(projection.latlon2eastNorth(new LatLon(49.44, 14.0)),
                projection.latlon2eastNorth(new LatLon(49.44, 14.0001)));
        byte[] png = colorRoutePng();
        var decoder = new TileDecoderClassifier();
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var coordinator = new TileFetchCoordinator((request, credentials) ->
                    new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                            png, null, Duration.ZERO, ""),
                new ManagedTileCache(cache, decoder), decoder, TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(0L));
            var sampler = new TileHeatmapSampler(coordinator);
            var capture = sampler.prepareForEmission(managedSettings(), source,
                    List.of("hot"), "hot", false, projection, budget, owner);
            var retained = sampler.sampleProfilesRetainingL0(capture, "hot",
                    IntensitySamplingMode.COLOR_MAPPING, budget, owner, CancellationProbe.NONE);
            long jointlyCharged = ledger.currentBytes();
            assertTrue(jointlyCharged > retained.grid().retainedBytes());
            assertThrows(IllegalStateException.class,
                    () -> sampler.sampleProfilesRetainingL0(capture, "hot",
                            IntensitySamplingMode.COLOR_MAPPING, budget, owner, CancellationProbe.NONE));
            assertEquals(jointlyCharged, ledger.currentBytes());
            capture.close();
            assertEquals(jointlyCharged, ledger.currentBytes());
            assertFalse(retained.profiles().isEmpty());
            assertSame(capture, retained.sourceCapture().orElseThrow());
            assertSame(capture.selectedMosaic(), retained.sourceMosaic());
            assertThrows(IllegalStateException.class,
                    () -> sampler.sampleProfilesRetainingL0(capture, "hot",
                            IntensitySamplingMode.COLOR_MAPPING, budget, owner, CancellationProbe.NONE));
            retained.close();
            assertEquals(0L, ledger.currentBytes());
            assertThrows(IllegalArgumentException.class, capture::selectedMosaic);

            var second = sampler.prepareForEmission(managedSettings(), source,
                    List.of("hot"), "hot", false, projection, budget, owner);
            var secondRetained = sampler.sampleProfilesRetainingL0(second, "hot",
                    IntensitySamplingMode.COLOR_MAPPING, budget, owner, CancellationProbe.NONE);
            secondRetained.close();
            assertTrue(ledger.currentBytes() > 0);
            assertSame(second.selectedMosaic(), second.mosaics().require("hot"));
            second.close();
            assertEquals(0L, ledger.currentBytes());
        } finally {
            owner.close();
        }
    }

    private static ManagedHeatmapConfig managedSettings() {
        return new ManagedHeatmapConfig("key", "policy", "signature", "token",
                "all", "hot", "", ".*", AlignmentMode.MOVE_EXISTING_NODES,
                TrackerMode.LEGACY_V02, false, false, false, false, false, false,
                true, false, false, false, 18, 4, 3.0, InferenceMode.STABLE_FIXED_SCALE,
                15, 15, 28.0, 6.0, IntensitySamplingMode.COLOR_MAPPING, 0L);
    }

    private static byte[] colorRoutePng() throws Exception {
        var image = new BufferedImage(TileHeatmapSampler.TILE_SIZE,
                TileHeatmapSampler.TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            int distance = Math.abs(y - image.getHeight() / 2);
            int pixel = distance <= 2 ? 0xFFFFFFFF : distance <= 5 ? 0xFFFF6600 : 0;
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, pixel);
        }
        var bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static RasterPoint virtual(double worldX, double worldY, double scale) {
        return new RasterPoint((worldX - 0.5) * scale, (worldY - 0.5) * scale);
    }
}

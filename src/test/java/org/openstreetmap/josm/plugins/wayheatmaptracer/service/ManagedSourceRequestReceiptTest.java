package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy;

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
            assertTrue(receipt.originalWorldPixelDecisionRegion().contains(new MetricPoint(175, 150)));
            assertFalse(receipt.originalWorldPixelDecisionRegion().contains(new MetricPoint(175, 160)));
            var image = new BufferedImage(TileHeatmapSampler.TILE_SIZE, TileHeatmapSampler.TILE_SIZE,
                    BufferedImage.TYPE_INT_ARGB);
            var mosaic = new TileHeatmapSampler.TileMosaic("hot", 15, 0.0, 0.0, image,
                    List.of(), Map.of(), parameters, true, virtualScale);
            var scratch = owner.child("search-query");
            assertTrue(receipt.authorizesVirtualRasterSegment(mosaic,
                    virtual(160, 150, virtualScale), virtual(190, 150, virtualScale), budget, scratch));
            assertFalse(receipt.authorizesVirtualRasterSegment(mosaic,
                    virtual(160, 160, virtualScale), virtual(190, 160, virtualScale), budget, scratch));
        } finally {
            owner.close();
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

    private static RasterPoint virtual(double worldX, double worldY, double scale) {
        return new RasterPoint((worldX - 0.5) * scale, (worldY - 0.5) * scale);
    }
}

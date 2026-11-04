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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.ManagedSelectionWitness;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.OptionalEmissionContextFactory;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileCachePolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TilePurpose;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CancellationToken;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TransportResponse;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class ManagedSourceRequestReceiptTest {
    @TempDir Path cache;

    @Test void actualManagedFactoryRetainsSourceSExactRowsAndCapturedNormalization() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        Config.setBaseDirectoriesProvider(new org.openstreetmap.josm.spi.preferences.IBaseDirectories() {
            public java.io.File getPreferencesDirectory(boolean create) { return cache.toFile(); }
            public java.io.File getUserDataDirectory(boolean create) { return cache.toFile(); }
            public java.io.File getCacheDirectory(boolean create) { return cache.toFile(); }
        });
        var projection = Projections.getProjectionByCode("EPSG:3857");
        ProjectionRegistry.setProjection(projection);
        var dataSet = new DataSet();
        Node a = new Node(new LatLon(49.44, 14.0));
        Node corner = new Node(new LatLon(49.44013, 14.0));
        Node b = new Node(new LatLon(49.44, 14.0001));
        dataSet.addPrimitive(a); dataSet.addPrimitive(corner); dataSet.addPrimitive(b);
        Way way = new Way(); way.setNodes(List.of(a, corner, b)); dataSet.addPrimitive(way);
        dataSet.setSelected(way);
        var layer = new OsmDataLayer(dataSet, "managed factory synthetic transport", null);
        MainApplication.getLayerManager().addLayer(layer);
        var selection = SelectionResolver.resolve(dataSet, false);
        var source = selection.segmentNodes().stream().map(n -> n.getEastNorth(projection)).toList();
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        var settings = new AtomicReference<>(managedSettings());
        var decoder = new TileDecoderClassifier(); byte[] png = colorRoutePng();
        var fetches = new AtomicInteger();
        try (var owner = ledger.rootOwner(); var coordinator = new TileFetchCoordinator((request, credentials) -> {
                    fetches.incrementAndGet();
                    return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                            png, null, Duration.ZERO, "");
                }, new ManagedTileCache(cache, decoder), decoder, TileReliabilityPolicy.defaults())) {
            var sampler = new TileHeatmapSampler(coordinator);
            var witness = ManagedSelectionWitness.capture(dataSet, selection, projection, source, budget, owner);
            try (var capture = sampler.prepareForEmission(settings.get(), witness, List.of("hot"), "hot", false, budget, owner);
                 var binding = OptionalEmissionContextFactory.bindManagedCapture(capture, settings::get, budget, owner)) {
                int alreadyFetched = fetches.get();
                var nativeProfiles = sampler.sampleProfiles(capture.selectedMosaic(), source, "hot",
                        IntensitySamplingMode.COLOR_MAPPING, projection, CancellationProbe.NONE);
                try (var input = sampler.sampleProfilesForEmission(binding, CancellationProbe.NONE)) {
                    assertEquals(alreadyFetched, fetches.get(), "Publishing must add no source acquisition");
                    assertTrue(input.context().productionEligible());
                    assertTrue(input.legacyBinding().orElseThrow().productionEligible());
                    assertEquals(capture.trackerNormalizationRasterPx(),
                            input.legacyBinding().orElseThrow().trackerNormalizationRasterPx());
                    for (int row = 0; row < nativeProfiles.size(); row++) {
                        var actual = input.profiles().levelZeroProfiles().get(row);
                        var expected = nativeProfiles.get(row);
                        assertEquals(expected.samplingAnchor(), actual.samplingAnchor());
                        assertEquals(expected.normalScreen(), actual.normalScreen());
                        assertEquals(expected.peaks(), actual.peaks());
                        assertEquals(expected.intensitySamples(), actual.intensitySamples());
                    }
                    int last = nativeProfiles.size() - 1;
                    assertTrue(input.context().sourceChainageMeters(last)
                            > nativeProfiles.get(last).cumulativeGroundDistanceMeters());
                    var events = input.legacyBinding().orElseThrow().protectedOccurrences();
                    assertEquals(input.context().sourceChainageMeters(last), events.get(events.size() - 1).sourceChainageMeters());
                    assertThrows(IllegalStateException.class, () -> sampler.sampleProfilesForEmission(binding, CancellationProbe.NONE));
                    a.setCoor(new LatLon(49.440001, 14.0));
                    assertThrows(IllegalStateException.class, input::requireOpenAndUnchanged);
                }
            }
        } finally { MainApplication.getLayerManager().removeLayer(layer); }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void cancellationDetachesOnlyManagedSubscriberDuringBlockedSharedFetch() throws Exception {
        var projection = Projections.getProjectionByCode("EPSG:3857");
        var source = List.of(projection.latlon2eastNorth(new LatLon(49.44, 14.0)),
                projection.latlon2eastNorth(new LatLon(49.44, 14.0001)));
        var settings = managedSettings();
        var decoder = new TileDecoderClassifier();
        byte[] png = colorRoutePng();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<TileRequest> firstRequest = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), cancelled::get);
        var executor = Executors.newSingleThreadExecutor();
        try (var coordinator = new TileFetchCoordinator((request, credentials) -> {
                    firstRequest.set(request);
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                            png, null, Duration.ZERO, "");
                }, new ManagedTileCache(cache, decoder), decoder, TileReliabilityPolicy.defaults())) {
            var sampler = new TileHeatmapSampler(coordinator);
            var first = executor.submit(() -> sampler.prepareForEmission(settings, source,
                    List.of("hot"), "hot", false, projection, budget, owner));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            TileRequest original = firstRequest.get();
            var other = coordinator.fetch(new TileRequest(original.address(), original.generation(),
                    TilePurpose.ALIGNMENT_REQUIRED, TileCachePolicy.USE_CACHE,
                    java.time.Instant.now().plusSeconds(30), new CancellationToken()),
                    CredentialSnapshot.fromConfig(settings)).toCompletableFuture();
            cancelled.set(true);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> first.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException);
            assertFalse(other.isDone());
            release.countDown();
            assertTrue(other.get(2, TimeUnit.SECONDS).usable());
            assertEquals(0L, ledger.currentBytes());
        } finally {
            release.countDown();
            executor.shutdownNow();
            owner.close();
        }
    }

    @Test void selectedWitnessSurvivesCaptureCloseOnlyWhileRetainedProducerOwnsIt() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        var projection = Projections.getProjectionByCode("EPSG:3857");
        ProjectionRegistry.setProjection(projection);
        DataSet dataSet = new DataSet();
        Node first = new Node(new LatLon(49.44, 14.0));
        Node corner = new Node(new LatLon(49.44013, 14.0));
        Node last = new Node(new LatLon(49.44, 14.0001));
        dataSet.addPrimitive(first);
        dataSet.addPrimitive(corner);
        dataSet.addPrimitive(last);
        Way way = new Way();
        way.setNodes(List.of(first, corner, last));
        dataSet.addPrimitive(way);
        dataSet.setSelected(List.of(way));
        var selection = SelectionResolver.resolve(dataSet, false);
        var source = selection.segmentNodes().stream().map(node -> node.getEastNorth(projection)).toList();
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        byte[] png = colorRoutePng();
        var decoder = new TileDecoderClassifier();
        try (var coordinator = new TileFetchCoordinator((request, credentials) ->
                    new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                            png, null, Duration.ZERO, ""),
                new ManagedTileCache(cache, decoder), decoder, TileReliabilityPolicy.defaults())) {
            var sampler = new TileHeatmapSampler(coordinator);
            var witness = ManagedSelectionWitness.capture(dataSet, selection, projection, source,
                    budget, owner);
            assertThrows(IllegalArgumentException.class,
                    () -> sampler.prepareForEmission(managedSettings(), witness,
                            List.of("blue"), "blue", false, budget, owner));
            var capture = sampler.prepareForEmission(managedSettings(), witness,
                    List.of("hot"), "hot", false, budget, owner);
            assertSame(witness, capture.selectionWitness().orElseThrow());
            assertThrows(IllegalArgumentException.class,
                    () -> sampler.sampleProfilesRetainingL0(capture, "blue",
                            IntensitySamplingMode.COLOR_MAPPING, budget, owner, CancellationProbe.NONE));
            var retained = sampler.sampleProfilesRetainingL0(capture, "hot",
                    IntensitySamplingMode.COLOR_MAPPING, budget, owner, CancellationProbe.NONE);
            var parameters = retained.sourceParameters().orElseThrow();
            assertFalse(parameters.aliases().isEmpty());
            for (int row = 0; row < parameters.rowCount(); row++) {
                assertTrue(parameters.rowArc(row).lower() <= parameters.sourceChainageMeters(row));
                assertTrue(parameters.rowArc(row).upper() >= parameters.sourceChainageMeters(row));
            }
            assertEquals(0, parameters.aliases().get(0).originalVertex());
            assertEquals(source.size() - 1,
                    parameters.aliases().get(parameters.aliases().size() - 1).originalVertex());
            assertEquals(retained.profiles().size(), parameters.rowCount());
            assertEquals(source.size(), parameters.sourceVertexCount());
            assertEquals(source.size(), parameters.originalOccurrences().size());
            assertTrue(parameters.originalOccurrences().get(0).protectedOccurrence());
            assertTrue(parameters.originalOccurrences().get(source.size() - 1).protectedOccurrence());
            for (int vertex = 0; vertex < source.size(); vertex++) {
                var occurrence = parameters.originalOccurrences().get(vertex);
                assertEquals(selection.startIndex() + vertex, occurrence.fullWayIndex());
                assertEquals(parameters.sourceVertexChainageMeters(vertex), occurrence.sourceChainageMeters());
                assertTrue(occurrence.leftRow() >= 0);
                assertEquals(occurrence.leftRow() + 1, occurrence.rightRow());
            }
            assertEquals(0.0, parameters.sourceChainageMeters(0));
            assertEquals(parameters.sourceVertexChainageMeters(source.size() - 1),
                    parameters.sourceChainageMeters(parameters.rowCount() - 1), 1e-8);
            assertTrue(parameters.sourceSpanUpperMeters(0, parameters.rowCount() - 1)
                    >= parameters.sourceChainageMeters(parameters.rowCount() - 1));
            var ground = retained.groundTransform().orElseThrow();
            var firstAnchor = retained.profiles().get(0).anchorScreen();
            var lastAnchor = retained.profiles().get(retained.profiles().size() - 1).anchorScreen();
            var firstMetric = ground.metricAt(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(
                    firstAnchor.x, firstAnchor.y));
            var lastMetric = ground.metricAt(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(
                    lastAnchor.x, lastAnchor.y));
            assertTrue(parameters.sourceChainageMeters(parameters.rowCount() - 1)
                    > Math.hypot(lastMetric.xMeters() - firstMetric.xMeters(),
                            lastMetric.yMeters() - firstMetric.yMeters()) + 1.0);
            assertTrue(parameters.sourceChainageMeters(parameters.rowCount() - 1)
                    > retained.profiles().get(retained.profiles().size() - 1)
                            .cumulativeGroundDistanceMeters() + 0.001);
            capture.close();
            witness.requireCurrent(budget, owner);
            assertTrue(ledger.currentBytes() > 0);
            retained.close();
            assertEquals(0L, ledger.currentBytes());
            assertThrows(IllegalStateException.class, () -> witness.requireCurrent(budget, owner));
            assertThrows(IllegalStateException.class, witness::frozenSelectedFacts);
        } finally {
            owner.close();
        }
    }

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
            capture.requireSourceCurrent(managedSettings(), budget);
            assertThrows(IllegalStateException.class,
                    () -> capture.requireSourceCurrent(managedSettings().withSearchHalfWidthMeters(29.0), budget));
            var retained = sampler.sampleProfilesRetainingL0(capture, "hot",
                    IntensitySamplingMode.COLOR_MAPPING, budget, owner, CancellationProbe.NONE);
            coordinator.updateActiveGeneration(new ManagedTileGeneration(1L));
            assertThrows(IllegalStateException.class,
                    () -> capture.requireSourceCurrent(managedSettings(), budget));
            coordinator.updateActiveGeneration(new ManagedTileGeneration(0L));
            assertThrows(IllegalStateException.class,
                    () -> capture.requireSourceCurrent(managedSettings(), budget));
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

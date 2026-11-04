package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Real managed capture/factory inputs with synthetic transport, never a fabricated context. */
class LegacyEmissionManagedTransportTest {
    @TempDir Path cache;

    @Test void p0CensusUsesActualManagedNativeGridScaleAndCapturedPixelCenterFrame() throws Exception {
        var projection = Projections.getProjectionByCode("EPSG:3857");
        var start = projection.latlon2eastNorth(new LatLon(49.44, 14.0));
        withManaged(List.of(start, new EastNorth(start.east() + 150, start.north() + 150)), 35, fixture -> {
            var input = fixture.input(); var context = input.context(); var grid = context.nativeGrid();
            double scale = fixture.capture().selectedMosaic().virtualRasterScale();
            assertNotEquals(1, scale);
            long modes = input.workBudget().snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION);
            var stationCount = new AtomicInteger(); var observed = new AtomicInteger();
            try (var result = CurvedChartCensus.scan(input, new CurvedChartCensus.Metadata("synthetic-managed", "synthetic-managed", List.of()), new CurvedChartCensus.Sink() {
                public void pair(CurvedChartCensus.Pair pair) {
                    assertSame(context, pair.source().contextOwner()); assertSame(grid, pair.source().gridOwner());
                    assertEquals(scale, pair.source().producerUnitsPerGridPixel().orElseThrow(), 0);
                    assertEquals(CurvedChartCensus.Reason.NONE, pair.chartReason());
                    for (var row : List.of(pair.left(), pair.right())) for (var port : row.producerPorts()) {
                        observed.incrementAndGet();
                        assertEquals(new RasterPoint(port.actualRaster().x() / scale, port.actualRaster().y() / scale), port.actualNativeGrid());
                        assertMetricClose(expectedMetric(fixture, port.actualRaster()), context.metricAt(port.actualRaster()));
                    }
                    assertEquals(CurvedChartCensus.Availability.UNAVAILABLE, pair.fullFutureDomain());
                }
                public void station(CurvedChartCensus.Station station) {
                    stationCount.incrementAndGet();
                    assertEquals(grid.toGrid(station.actualRasterFrom()), station.actualNativeFrom());
                    assertEquals(grid.toGrid(station.actualRasterTo()), station.actualNativeTo());
                }
            })) { assertEquals(fixture.nativeProfiles().size() - 1, result.visitedPairs()); }
            assertTrue(observed.get() > 0); assertTrue(stationCount.get() > 0);
            assertEquals(modes, input.workBudget().snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION));
        });
    }

    @Test void affineAffinityRejectsManagedChartWithoutInventingMeasuredOwners() throws Exception {
        withManaged(List.of(new EastNorth(500, 500), new EastNorth(650, 500)), 30, fixture -> {
            var input = fixture.input();
            var binding = input.legacyBinding().orElseThrow();
            assertTrue(fixture.nativeProfiles().size() >= 5,
                "Keep at least three interior rows after protected endpoints split the smoothing run");
            assertThrows(IllegalStateException.class, () -> input.context().rasterTransform());
            var measured = input.experimentalMeasuredModes();
            for (int row = 0; row < binding.observations().size(); row++) {
                assertTrue(measured.ownersAt(row).isEmpty());
                assertNull(measured.extractionAt(row));
                assertTrue(measured.samplesAt(row).isEmpty());
            }
            var domain = new LegacyEmissionDomain(binding, fixture.nativeProfiles(), List.of(0.0),
                input.workBudget(), fixture.owner(), measured);
            var from = state(domain, 0, input.workBudget());
            var to = state(domain, 1, input.workBudget());
            var classifier = new OrdinaryAffinityClassifier(input, domain, fixture.owner());
            var receipt = classifier.classify(from, to, domain.metricPoint(from, input.context()),
                domain.metricPoint(to, input.context()));
            assertEquals(OrdinaryAffinityClassifier.Status.UNKNOWN, receipt.status());
            assertEquals(OrdinaryAffinityClassifier.Reason.UNSUPPORTED_CHART, receipt.reason());
            assertTrue(receipt.ordinaryEligible());
            assertEquals(0, classifier.stationExtractions());
            assertTrue(receipt.slab().stations().isEmpty());
            assertTrue(receipt.slab().plan().isEmpty());
            try (var result = new LegacyEmissionKernel().solveMeasuredAffinity(input, fixture.nativeProfiles(),
                    List.of(0.0), List.of(), (profiles, offsets, intensities) -> {
                        var moved = new ArrayList<>(offsets);
                        for (int row = 1; row + 1 < moved.size(); row++) moved.set(row, moved.get(row) + 2);
                        return List.copyOf(moved);
                    }, CancellationProbe.NONE)) {
                var affinity = result.affinity().orElseThrow();
                assertFalse(affinity.fullyCertified());
                assertEquals(0, affinity.stationExtractions());
                assertTrue(affinity.measuredStates().isEmpty());
                assertFalse(affinity.inference().isEmpty());
                assertTrue(affinity.inference().stream().allMatch(arc ->
                    arc.status() == OrdinaryAffinityClassifier.Status.UNKNOWN));
                assertTrue(affinity.inference().stream().anyMatch(arc -> arc.receipt().isPresent()));
                for (var arc : affinity.inference()) {
                    assertEquals(arc.receipt().isPresent() ? "UNSUPPORTED_CHART" : "BOUNDARY_ONLY_OWNER",
                        arc.reasonCode());
                }
                assertEquals(result.inferenceVertices(), result.smoothedVertices());
                assertTrue(affinity.smoothingOutcomes().stream().anyMatch(outcome ->
                    outcome.disposition() == EmissionResult.SmoothingDisposition.RAW_RETAINED
                        && outcome.reason() == EmissionResult.SmoothingReason.FINAL_EVIDENCE_UNAVAILABLE));
            }
        });
    }

    @Test void acuteSourceArcKeepsItsMassWhenNativeDenseChordsAreBelowTwentyMeters() throws Exception {
        withManaged(List.of(new EastNorth(500, 500), new EastNorth(515, 500),
                new EastNorth(508, 500.5)), 11, fixture -> {
            var input = fixture.input();
            var context = input.context();
            var binding = input.legacyBinding().orElseThrow();
            assertEquals(3, fixture.nativeProfiles().size());
            double nativeSpan = fixture.nativeProfiles().get(2).cumulativeGroundDistanceMeters();
            double sourceSpan = context.sourceChainageMeters(2) - context.sourceChainageMeters(0);
            assertTrue(nativeSpan < 20, "The original three-row native shortcut is below the bound");
            assertTrue(sourceSpan > 20, "The actual captured source curve crosses the bound");
            assertTrue(context.sourceSpanUpperMeters(0, 2) >= sourceSpan);

            var domain = new LegacyEmissionDomain(binding, fixture.nativeProfiles(), List.of(0.0),
                input.workBudget(), fixture.owner());
            var states = new ArrayList<NativeConnectorEvaluator.RowState>();
            for (int row = 0; row < 3; row++)
                states.add(state(domain, row, input.workBudget()).evaluatorState());
            try (var evaluator = new NativeConnectorEvaluator(input,
                    new NativeConnectorEvaluator.Domain(states, List.of(0, 2)))) {
                var refused = evaluator.evaluate(states.get(0), states.get(2));
                assertEquals(NativeConnectorEvaluator.Reason.SPAN, refused.reason());
            }

            try (var result = new LegacyEmissionKernel().solve(input, fixture.nativeProfiles(),
                    List.of(0.0), List.of(), (profiles, offsets, intensities) -> offsets,
                    CancellationProbe.NONE)) {
                assertEquals(sourceSpan, result.sourceIntervalMassMeters(), 1e-10);
                assertEquals(sourceSpan, result.originalObservations().get(2).sourceChainageMeters(), 0);
                assertTrue(result.mapping().observationOwners().stream()
                    .allMatch(owner -> owner instanceof EmissionMapping.Emitted));
                assertTrue(result.inferenceArcs().stream()
                    .noneMatch(arc -> arc.kind() == EmissionResult.ArcKind.OPTIONAL_ADMITTED));
                double constant = context.nativeGrid().nodeValue(0, 0);
                assertTrue(constant > 0 && constant < 1, "Q must be a nonzero discriminating oracle");
                double expectedQ = sourceSpan / context.nativePitchMeters().orElseThrow() * (1 - constant);
                assertEquals(expectedQ, result.stitched().q(), 1e-10);
                assertEquals(expectedQ, result.postSmoothing().q(), 1e-10);
                for (var event : binding.protectedOccurrences()) {
                    int vertex = result.mapping().vertexOwners().indexOf(
                        new EmissionMapping.BoundaryVertex(event.occurrenceId()));
                    assertTrue(vertex >= 0);
                    assertEquals(event.exactPosition(), result.inferenceVertices().get(vertex).position());
                }
            }
        });
    }

    @Test void nonhorizontalManagedVerticesAndSmoothingUseCapturedNonlinearGroundMapping() throws Exception {
        var projection = Projections.getProjectionByCode("EPSG:3857");
        var start = projection.latlon2eastNorth(new LatLon(49.44, 14.0));
        withManaged(List.of(start, new EastNorth(start.east() + 150, start.north() + 150)),
            35, fixture -> {
                var input = fixture.input();
                var context = input.context();
                var binding = input.legacyBinding().orElseThrow();
                assertEquals(7, fixture.nativeProfiles().size());
                assertTrue(context.sourceSpanUpperMeters(0, 2) > 20,
                    "All optional skips must be excluded so the middle native run reaches the smoother");
                var smoothCalls = new AtomicInteger();
                try (var result = new LegacyEmissionKernel().solve(input, fixture.nativeProfiles(),
                        List.of(0.0), List.of(), (profiles, offsets, intensities) -> {
                            smoothCalls.incrementAndGet();
                            var moved = new ArrayList<>(offsets);
                            for (int row = 1; row + 1 < moved.size(); row++) moved.set(row, moved.get(row) + 2);
                            return List.copyOf(moved);
                        }, CancellationProbe.NONE)) {
                    assertEquals(1, smoothCalls.get());
                    boolean changed = false;
                    for (int index = 0; index < result.inferenceVertices().size(); index++) {
                        var raw = result.inferenceVertices().get(index);
                        var smoothed = result.smoothedVertices().get(index);
                        if (raw.owner() instanceof EmissionMapping.ObservationVertex observed) {
                            var profile = binding.observations().get(observed.observationIndex()).measurementProfile();
                            assertMetricClose(expectedMetric(fixture, raster(profile, raw.offsetRasterPixels().orElseThrow())),
                                raw.position());
                            assertMetricClose(expectedMetric(fixture, raster(profile, smoothed.offsetRasterPixels().orElseThrow())),
                                smoothed.position());
                            changed |= !raw.position().equals(smoothed.position());
                        } else assertEquals(raw.position(), smoothed.position());
                    }
                    assertTrue(changed);
                    for (var original : result.originalObservations()) {
                        var profile = binding.observations().get(original.originalRow()).measurementProfile();
                        assertMetricClose(expectedMetric(fixture, profile.samplingAnchor().rasterPoint()),
                            original.metricAnchor());
                    }
                    double firstNorth = result.originalObservations().get(0).metricAnchor().yMeters();
                    double lastNorth = result.originalObservations().get(6).metricAnchor().yMeters();
                    double middleNorth = result.originalObservations().get(3).metricAnchor().yMeters();
                    assertTrue(Math.abs(middleNorth - .5 * (firstNorth + lastNorth)) > 1e-6,
                        "This diagonal capture must distinguish nonlinear mapping from endpoint interpolation");
                    double constant = context.nativeGrid().nodeValue(0, 0);
                    assertEquals(result.sourceIntervalMassMeters() / context.nativePitchMeters().orElseThrow()
                        * (1 - constant), result.postSmoothing().q(), 1e-9);
                }
            });
    }

    private static LegacyEmissionDomain.State state(LegacyEmissionDomain domain, int row,
            EmissionWorkBudget budget) {
        return domain.unsupported(row) ? domain.carry(row, domain.seeds().get(0).id(), 0, budget)
            : domain.observedAt(row).get(0);
    }
    private static RasterPoint raster(CrossSectionProfile profile, double offset) {
        return new RasterPoint(profile.anchorScreen().x + profile.normalScreen().x * offset,
            profile.anchorScreen().y + profile.normalScreen().y * offset);
    }
    private static MetricPoint expectedMetric(Fixture fixture, RasterPoint raster) {
        var mosaic = fixture.capture().selectedMosaic();
        double size = TileHeatmapSampler.TILE_SIZE * Math.scalb(1.0, mosaic.zoom());
        double worldX = mosaic.originWorldPxX() + raster.x() / mosaic.virtualRasterScale() + .5;
        double worldY = mosaic.originWorldPxY() + raster.y() / mosaic.virtualRasterScale() + .5;
        double circumference = 2 * Math.PI * 6378137.0;
        var geographic = fixture.projection().eastNorth2latlon(new EastNorth(
            (worldX / size - .5) * circumference, (.5 - worldY / size) * circumference));
        return fixture.input().context().coordinateFrame().toMetric(new GeographicPoint(geographic.lat(), geographic.lon()));
    }
    private static void assertMetricClose(MetricPoint expected, MetricPoint actual) {
        assertEquals(expected.xMeters(), actual.xMeters(), 1e-7);
        assertEquals(expected.yMeters(), actual.yMeters(), 1e-7);
    }
    private record Fixture(SampledEmissionInput input, TileHeatmapSampler.PreparedManagedCapture capture,
            Projection projection, List<CrossSectionProfile> nativeProfiles, AttemptMemoryLedger.Owner owner) { }
    @FunctionalInterface private interface Check { void accept(Fixture fixture) throws Exception; }

    private void withManaged(List<EastNorth> source, double step, Check check) throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        Config.setBaseDirectoriesProvider(new org.openstreetmap.josm.spi.preferences.IBaseDirectories() {
            public java.io.File getPreferencesDirectory(boolean create) { return cache.toFile(); }
            public java.io.File getUserDataDirectory(boolean create) { return cache.toFile(); }
            public java.io.File getCacheDirectory(boolean create) { return cache.toFile(); }
        });
        var projection = Projections.getProjectionByCode("EPSG:3857");
        ProjectionRegistry.setProjection(projection);
        var dataSet = new DataSet();
        var nodes = source.stream().map(point -> new Node(projection.eastNorth2latlon(point))).toList();
        nodes.forEach(dataSet::addPrimitive);
        var way = new Way(); way.setNodes(nodes); dataSet.addPrimitive(way); dataSet.setSelected(way);
        var layer = new OsmDataLayer(dataSet, "kernel transport synthetic tiles", null);
        MainApplication.getLayerManager().addLayer(layer);
        var selection = SelectionResolver.resolve(dataSet, false);
        var projected = nodes.stream().map(node -> node.getEastNorth(projection)).toList();
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        var settings = new ManagedHeatmapConfig("key", "policy", "signature", "token", "all", "hot", "", ".*",
            AlignmentMode.MOVE_EXISTING_NODES, TrackerMode.LEGACY_V02, false, false, false, false, false, false,
            true, false, false, false, 18, 4, 3, InferenceMode.STABLE_FIXED_SCALE, 15, 15, 28, step,
            IntensitySamplingMode.COLOR_MAPPING, 0);
        var image = new BufferedImage(TileHeatmapSampler.TILE_SIZE, TileHeatmapSampler.TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, 0xFF808080);
        var bytes = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", bytes);
        var decoder = new TileDecoderClassifier();
        try (var owner = ledger.rootOwner(); var coordinator = new TileFetchCoordinator((request, credentials) ->
                new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png", bytes.toByteArray(), null,
                    Duration.ZERO, ""), new ManagedTileCache(cache, decoder), decoder, TileReliabilityPolicy.defaults())) {
            var sampler = new TileHeatmapSampler(coordinator);
            var witness = ManagedSelectionWitness.capture(dataSet, selection, projection, projected, budget, owner);
            try (var capture = sampler.prepareForEmission(settings, witness, List.of("hot"), "hot", false, budget, owner);
                 var permit = OptionalEmissionContextFactory.bindManagedCapture(capture, () -> settings, budget, owner)) {
                var nativeProfiles = sampler.sampleProfiles(capture.selectedMosaic(), projected, "hot",
                    IntensitySamplingMode.COLOR_MAPPING, projection, CancellationProbe.NONE);
                try (var input = sampler.sampleProfilesForEmission(permit, CancellationProbe.NONE)) {
                    var produced = input.profiles().levelZeroProfiles();
                    for (int row = 0; row < nativeProfiles.size(); row++) {
                        assertEquals(nativeProfiles.get(row).samplingAnchor(), produced.get(row).samplingAnchor());
                        assertEquals(nativeProfiles.get(row).normalScreen(), produced.get(row).normalScreen());
                        assertEquals(nativeProfiles.get(row).peaks(), produced.get(row).peaks());
                        assertEquals(nativeProfiles.get(row).intensitySamples(), produced.get(row).intensitySamples());
                    }
                    check.accept(new Fixture(input, capture, projection, produced, owner));
                }
            }
        } finally { MainApplication.getLayerManager().removeLayer(layer); }
        assertEquals(0, ledger.currentBytes());
    }
}

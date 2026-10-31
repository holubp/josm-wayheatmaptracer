package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.OptionalDouble;
import java.time.Duration;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

class LegacyBSourceCaptureTest {
    private LegacyBSourceCapture.Settings settings() {
        return new LegacyBSourceCapture.Settings(12, 2, InferenceMode.STABLE_FIXED_SCALE,
                15, 13, 7.01, 2, IntensitySamplingMode.COLOR_MAPPING);
    }

    @Test void managedCaptureSharesOneImmutableMosaicAcrossDetectorsAndAttempts() {
        var fixture = managedSources(false, null);
        var ledger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        var source = List.of(new ProjectedPoint(-40, 0), new ProjectedPoint(40, 0));
        LegacyManagedProfileSampler.Mosaic firstMosaic;
        try (var capture = new LegacyBSourceCapture.ManagedCapture(settings(), fixture.sources(), source,
                "EPSG:3857", () -> false, owner)) {
            var hot = capture.input("hot");
            var hotCorridor = capture.input("hot-corridor");
            firstMosaic = hot.mosaics().get("hot@15");
            assertSame(hot.mosaics().get("hot@15"), hotCorridor.mosaics().get("hot@15"));
            assertEquals(List.of("hot@15"), List.copyOf(hot.mosaics().keySet()));
            var copy = hot.mosaics().get("hot@15").argb();
            copy[0] ^= 0x00ffffff;
            assertNotEquals(copy[0], hot.mosaics().get("hot@15").argb()[0]);
            assertTrue(ledger.currentBytes() > 0);
        }
        assertEquals(0, ledger.currentBytes(), "Closing the capture releases its attempt-owned mosaic pool");
        owner.close();

        var otherLedger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
        try (var otherOwner = otherLedger.rootOwner();
                var other = new LegacyBSourceCapture.ManagedCapture(settings(), fixture.sources(), source,
                        "EPSG:3857", () -> false, otherOwner)) {
            assertNotSame(firstMosaic, other.input("hot").mosaics().get("hot@15"));
            other.close();
            other.close();
            assertThrows(IllegalStateException.class, () -> other.input("hot"));
        }
    }

    @Test void managedCaptureAggregatePreservesAcquiredPaletteOrderAndSharesSelectedMosaic(@TempDir Path temp)
            throws Exception {
        var fixture = managedSources(true, temp);
        var ledger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner();
                var capture = new LegacyBSourceCapture.ManagedCapture(settings(), fixture.sources(),
                        List.of(new ProjectedPoint(-40, 0), new ProjectedPoint(40, 0)),
                        "EPSG:3857", () -> false, owner)) {
            var selected = capture.input("blue");
            var aggregate = capture.input("all-colors-combined");
            assertEquals(List.of("blue@15", "hot@15", "bluered@15", "purple@15", "gray@15"),
                    List.copyOf(aggregate.mosaics().keySet()));
            assertSame(selected.mosaics().get("blue@15"), aggregate.mosaics().get("blue@15"));
            assertEquals(List.of("blue"), selected.detectors());
            assertEquals(List.of("all-colors-combined"), aggregate.detectors());
        }
        assertEquals(0, ledger.currentBytes());

        var selectedRaster = fixture.sources().selectedRaster();
        long pixels = (long) selectedRaster.image().getWidth() * selectedRaster.image().getHeight();
        long retained = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger
                .arrayBytes(pixels, Integer.BYTES)
                + org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.objectBytes(80);
        long oneMosaicPeak = retained
                + org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.arrayBytes(pixels, 1)
                + org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger
                        .arrayBytes(pixels, Integer.BYTES);
        var bounded = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger(
                2 * oneMosaicPeak + retained);
        try (var owner = bounded.rootOwner();
                var capture = new LegacyBSourceCapture.ManagedCapture(settings(), fixture.sources(),
                        List.of(new ProjectedPoint(-40, 0), new ProjectedPoint(40, 0)),
                        "EPSG:3857", () -> false, owner)) {
            var selected = capture.input("blue");
            assertEquals(retained, bounded.currentBytes(), "Only immutable selected mosaic remains charged");
            assertThrows(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.ResourceLimitException.class,
                    () -> capture.input("all-colors-combined"));
            assertSame(selected.mosaics().get("blue@15"), capture.input("blue").mosaics().get("blue@15"));
            assertEquals(retained, bounded.currentBytes(), "Aggregate refusal preserves earlier selected input");
        }
        assertEquals(0, bounded.currentBytes());
    }

    @Test void managedCaptureRefusalAndCancellationReleasePartialPoolReservations() {
        var fixture = managedSources(false, null);
        var source = List.of(new ProjectedPoint(-40, 0), new ProjectedPoint(40, 0));
        var tooSmall = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger(64);
        try (var owner = tooSmall.rootOwner();
                var capture = new LegacyBSourceCapture.ManagedCapture(settings(), fixture.sources(), source,
                        "EPSG:3857", () -> false, owner)) {
            assertThrows(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.ResourceLimitException.class,
                    () -> capture.input("hot"));
            assertEquals(0, tooSmall.currentBytes());
        }
        var cancelled = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger(1_000_000);
        var cancellationChecks = new java.util.concurrent.atomic.AtomicInteger();
        try (var owner = cancelled.rootOwner();
                var capture = new LegacyBSourceCapture.ManagedCapture(settings(), fixture.sources(), source,
                        "EPSG:3857", () -> cancellationChecks.incrementAndGet() >= 3, owner)) {
            assertThrows(java.util.concurrent.CancellationException.class, () -> capture.input("hot"));
            assertEquals(0, cancelled.currentBytes());
        }
        var incomplete = managedSources(false, null);
        var aggregateLedger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
        try (var owner = aggregateLedger.rootOwner();
                var capture = new LegacyBSourceCapture.ManagedCapture(settings(), incomplete.sources(), source,
                        "EPSG:3857", () -> false, owner)) {
            assertThrows(IllegalArgumentException.class, () -> capture.input("all-colors-combined"));
            assertEquals(0, aggregateLedger.currentBytes(), "Incomplete aggregate fails before mosaic expansion");
        }
    }

    @Test void stableZ14CaptureMatchesNativeDilationPixelsAndSampledProfiles() {
        var settings = new LegacyBSourceCapture.Settings(12, 2, InferenceMode.STABLE_FIXED_SCALE,
                14, 13, 7.01, 2, IntensitySamplingMode.COLOR_MAPPING);
        assertEquals(1, TileHeatmapSampler.stableInferenceDilationRadius(14));
        var image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int red = (x * 3 + y) & 0xff;
                int green = (y * 2 + x) & 0xff;
                int blue = (x + y * 4) & 0xff;
                image.setRGB(x, y, 0xff000000 | red << 16 | green << 8 | blue);
            }
        }
        image.setRGB(64, 64, 0xfffff2d0);
        image.setRGB(63, 64, 0xff010101);
        image.setRGB(65, 64, 0xff020202);
        image.setRGB(64, 63, 0xff030303);
        image.setRGB(64, 65, 0xff040404);
        var validity = new boolean[128 * 128];
        Arrays.fill(validity, true);
        var transform = org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence
                .SupportedInputRasterTransform.webMercator(14, 2_097_120, 2_097_120, 2);
        var raster = new ManagedModernPreviewSource.Raster(image, validity, transform, "hot", 14,
                "synthetic-z14", new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(4));
        var sources = singleSource(raster);
        var source = List.of(new ProjectedPoint(-40, 0), new ProjectedPoint(40, 0));
        var nativeImage = TileHeatmapSampler.stableInferenceImage(image, "hot", 14);
        int[] nativePixels = nativeImage.getRGB(0, 0, nativeImage.getWidth(), nativeImage.getHeight(), null, 0,
                nativeImage.getWidth());
        var projection = Projections.getProjectionByCode("EPSG:3857");
        double latitude = (projection.eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth(-40, 0)).lat()
                + projection.eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth(40, 0)).lat()) / 2;
        var inference = TileHeatmapSampler.legacySamplingParameters(14, latitude, 7.01, 2);
        var validation = TileHeatmapSampler.legacySamplingParameters(13, latitude, 7.01, 2);
        double scale = inference.metersPerPixel() / TileHeatmapSampler.REFERENCE_VIEW_METERS_PER_PIXEL
                * RenderedHeatmapSampler.RASTER_SCALE;
        var nativeMosaic = new LegacyManagedProfileSampler.Mosaic("hot", 14,
                ((org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.WebMercator)
                        transform).inputWorldPixelBoundaryX() * 2,
                ((org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.WebMercator)
                        transform).inputWorldPixelBoundaryY() * 2,
                128, 128, nativePixels, inference, true, scale);
        var nativeInput = new LegacyManagedProfileSampler.Input(InferenceMode.STABLE_FIXED_SCALE,
                14, 13, 14, 13, TileHeatmapSampler.TILE_SIZE, Map.of("hot@14", nativeMosaic),
                inference, validation, source, "EPSG:3857", "hot", List.of("hot"),
                IntensitySamplingMode.COLOR_MAPPING, RenderedHeatmapSampler.RASTER_SCALE);
        var ledger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner();
                var capture = new LegacyBSourceCapture.ManagedCapture(settings, sources, source,
                        "EPSG:3857", () -> false, owner)) {
            var captured = capture.input("hot");
            assertArrayEquals(nativePixels, captured.mosaics().get("hot@14").argb());
            var sampler = new LegacyManagedProfileSampler();
            var expectedProfiles = sampler.sample(nativeInput, () -> false).get(0).profiles();
            var capturedProfiles = sampler.sample(captured, () -> false).get(0).profiles();
            assertTrue(capturedProfiles.stream().anyMatch(profile -> profile.anchorWithinRaster()
                    && profile.intensitySamples().stream().anyMatch(sample -> sample.nativeIntensity() > 0)),
                    "Expected profiles must contain in-raster nonzero heatmap evidence");
            assertEquals(expectedProfiles, capturedProfiles);
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void z14WorkingReservationRefusesBeforeDilationAndPixelReads() {
        var image = new TrackingImage(64, 64);
        var validity = new boolean[64 * 64];
        Arrays.fill(validity, true);
        var raster = new ManagedModernPreviewSource.Raster(image, validity,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence
                        .SupportedInputRasterTransform.webMercator(14, 2_097_120, 2_097_120, 2),
                "hot", 14, "synthetic-z14-tracking",
                new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(5));
        var sources = singleSource(raster);
        var settings = new LegacyBSourceCapture.Settings(12, 2, InferenceMode.STABLE_FIXED_SCALE,
                14, 13, 7.01, 2, IntensitySamplingMode.COLOR_MAPPING);
        long pixels = 64L * 64;
        long retained = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger
                .arrayBytes(pixels, Integer.BYTES)
                + org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.objectBytes(80);
        long cap = retained + org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger
                .arrayBytes(pixels, 1);
        var ledger = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger(cap);
        try (var owner = ledger.rootOwner();
                var capture = new LegacyBSourceCapture.ManagedCapture(settings, sources,
                        List.of(new ProjectedPoint(-40, 0), new ProjectedPoint(40, 0)),
                        "EPSG:3857", () -> false, owner)) {
            assertThrows(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.ResourceLimitException.class,
                    () -> capture.input("hot"));
            assertEquals(0, image.pixelReads.get(), "Working-copy refusal precedes dilation and getRGB allocation");
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test void cancellationAfterZ14DilationReleasesWorkingAndRetainedReservations() {
        var image = new TrackingImage(64, 64);
        image.setRGB(32, 32, 0xfffff2d0);
        var validity = new boolean[64 * 64];
        Arrays.fill(validity, true);
        var raster = new ManagedModernPreviewSource.Raster(image, validity,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence
                        .SupportedInputRasterTransform.webMercator(14, 2_097_120, 2_097_120, 2),
                "hot", 14, "synthetic-z14-cancel",
                new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(6));
        var sources = singleSource(raster);
        var settings = new LegacyBSourceCapture.Settings(12, 2, InferenceMode.STABLE_FIXED_SCALE,
                14, 13, 7.01, 2, IntensitySamplingMode.COLOR_MAPPING);
        var ledger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner();
                var capture = new LegacyBSourceCapture.ManagedCapture(settings, sources,
                        List.of(new ProjectedPoint(-40, 0), new ProjectedPoint(40, 0)),
                        "EPSG:3857", () -> image.pixelReads.get() > 0, owner)) {
            assertThrows(java.util.concurrent.CancellationException.class, () -> capture.input("hot"));
            assertTrue(image.pixelReads.get() > 0, "Cancellation must follow nonzero-radius stable dilation work");
            assertEquals(0, ledger.currentBytes(), "Dilation cancellation releases transient and retained charges");
        }
    }

    private static ManagedModernPreviewSource.SourceRasters singleSource(ManagedModernPreviewSource.Raster raster) {
        return new ManagedModernPreviewSource.SourceRasters(Map.of(raster.palette(), raster),
                new AlignmentTileSourcePlan(raster.palette(), Set.of(raster.palette()), Set.of(),
                        Set.of(raster.zoom()), false));
    }

    private static final class TrackingImage extends BufferedImage {
        private final java.util.concurrent.atomic.AtomicInteger pixelReads = new java.util.concurrent.atomic.AtomicInteger();
        private TrackingImage(int width, int height) { super(width, height, BufferedImage.TYPE_INT_ARGB); }
        @Override public int getRGB(int x, int y) {
            pixelReads.incrementAndGet();
            return super.getRGB(x, y);
        }
        @Override public int[] getRGB(int startX, int startY, int width, int height,
                int[] rgbArray, int offset, int scansize) {
            pixelReads.incrementAndGet();
            return super.getRGB(startX, startY, width, height, rgbArray, offset, scansize);
        }
    }

    private record ManagedFixture(ManagedModernPreviewSource.SourceRasters sources) { }

    private static ManagedFixture managedSources(boolean aggregate, Path temp) {
        if (aggregate) {
            var decoder = new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier();
            var config = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig(
                    "key", "policy", "signature", "session", "all", "blue", "", ".*",
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.PROBABILISTIC,
                    false, false, false, true, false, false, false, false, false, false,
                    7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15, 7.01, 1.56,
                    IntensitySamplingMode.COLOR_MAPPING, 17L);
            try (var coordinator = new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator(
                    (request, credentials) -> new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TransportResponse(
                            org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus.SUCCESS_NETWORK,
                            200, "image/png", png(0xffeeaaff), null, Duration.ZERO, ""),
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache(temp, decoder), decoder,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy.defaults())) {
                coordinator.updateActiveGeneration(new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(17));
                return new ManagedFixture(new ManagedModernPreviewSource(coordinator).acquireSources(
                        List.of(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint(0, 0),
                                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint(0, .001)),
                        config, "managed-capture-test",
                        org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot.fromConfig(null),
                        () -> false));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffeeffff);
        var validity = new boolean[64 * 64];
        Arrays.fill(validity, true);
        var transform = org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence
                .SupportedInputRasterTransform.webMercator(15, 4_194_272, 4_194_272, 2);
        var raster = new ManagedModernPreviewSource.Raster(image, validity, transform, "hot", 15,
                "synthetic", new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(1));
        var sources = new ManagedModernPreviewSource.SourceRasters(Map.of("hot", raster),
                new AlignmentTileSourcePlan("hot", Set.of("hot"), Set.of(), Set.of(15), false));
        return new ManagedFixture(sources);
    }

    private static byte[] png(int argb) {
        try {
            var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            graphics.setColor(new Color(argb, true));
            graphics.fillRect(0, 0, 512, 512);
            graphics.dispose();
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test void visibleCaptureUsesOriginalAnchorsAndNativeSixPixelNormalization() {
        var raster = new LiveBPreviewService.VisibleRaster(120, 120, new int[14400],
                0, 0, 20, 20, 1, 1, OptionalDouble.of(2), "synthetic", "EPSG:3857");
        var source = List.of(new ProjectedPoint(3, 10), new ProjectedPoint(17, 10));
        var input = LegacyBSourceCapture.visible(settings(), raster, source, "hot");
        assertEquals(2, input.anchors().size());
        assertEquals(source.get(0), input.anchors().get(0).source());
        assertEquals(18, input.anchors().get(0).raster().x());
        assertEquals(60, input.anchors().get(0).raster().y());
        assertEquals(6, input.options().trackerNormalizationRasterPx());
        assertEquals(12, input.options().halfWidthPx());
        assertEquals(2, input.options().stepPx());
        var projection = Projections.getProjectionByCode("EPSG:3857");
        double distance = projection.eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth(3, 10))
                .greatCircleDistance(projection.eastNorth2latlon(
                    new org.openstreetmap.josm.data.coor.EastNorth(17, 10)));
        assertEquals(distance, input.anchors().get(1).cumulativeGroundDistanceMeters(), 0);
        assertEquals(List.of("hot"), input.detectors());
    }

    @Test void visibleSamplingKeepsNativeClampsAndDirectDetectorIdentity() {
        var options = new LegacyBSourceCapture.Settings(1, 80, InferenceMode.STABLE_FIXED_SCALE,
                15, 13, 7.01, 2, IntensitySamplingMode.DIRECT_VALUE);
        var raster = new LiveBPreviewService.VisibleRaster(120, 120, new int[14400],
                0, 0, 20, 20, 1, 1, OptionalDouble.empty(), "synthetic", "EPSG:3857");
        var input = LegacyBSourceCapture.visible(options, raster,
                List.of(new ProjectedPoint(3, 10), new ProjectedPoint(17, 10)),
                IntensitySamplingMode.DIRECT_VALUE.detectorName());
        assertEquals(6, input.options().halfWidthPx());
        assertEquals(6, input.options().stepPx());
        assertEquals(IntensitySamplingMode.DIRECT_VALUE, input.options().intensityMode());
        assertThrows(IllegalArgumentException.class, () -> LegacyBSourceCapture.visible(options,
                raster, List.of(new ProjectedPoint(3, 10), new ProjectedPoint(17, 10)), "hot"));
    }

    @Test void numericalSettingsRejectNonfiniteAndInvalidWork() {
        assertThrows(IllegalArgumentException.class, () -> new LegacyBSourceCapture.Settings(
                12, 2, InferenceMode.STABLE_FIXED_SCALE, 15, 13, Double.NaN, 2,
                IntensitySamplingMode.COLOR_MAPPING));
        assertThrows(IllegalArgumentException.class, () -> new LegacyBSourceCapture.Settings(
                12, 2, InferenceMode.STABLE_FIXED_SCALE, 15, 13, 7.01, 0,
                IntensitySamplingMode.COLOR_MAPPING));
    }

    @Test void captureGeometryCopiesExactLegacyOccurrencePolicyOnEdt() throws Exception {
        org.openstreetmap.josm.data.projection.ProjectionRegistry.setProjection(
                Projections.getProjectionByCode("EPSG:3857"));
        var first = new org.openstreetmap.josm.data.osm.Node(new org.openstreetmap.josm.data.coor.LatLon(0, 0));
        var middle = new org.openstreetmap.josm.data.osm.Node(new org.openstreetmap.josm.data.coor.LatLon(0, .001));
        var last = new org.openstreetmap.josm.data.osm.Node(new org.openstreetmap.josm.data.coor.LatLon(0, .002));
        var way = new org.openstreetmap.josm.data.osm.Way();
        way.setNodes(List.of(first, middle, last));
        var other = new org.openstreetmap.josm.data.osm.Way();
        other.setNodes(List.of(middle, new org.openstreetmap.josm.data.osm.Node(
                new org.openstreetmap.josm.data.coor.LatLon(.001, .001))));
        var dataSet = new org.openstreetmap.josm.data.osm.DataSet();
        for (var node : List.of(first, middle, last, other.getNode(1))) { dataSet.addPrimitive(node); }
        dataSet.addPrimitive(way);
        dataSet.addPrimitive(other);
        var selection = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext(
                way, 0, 2, List.of(first, middle, last), Set.of(first, last));
        var result = new java.util.concurrent.atomic.AtomicReference<LegacyBSourceCapture.Geometry>();
        javax.swing.SwingUtilities.invokeAndWait(() -> result.set(LegacyBSourceCapture.geometry(selection, true, 1.5)));
        assertEquals(List.of(true, false, true), result.get().occurrences().stream().map(
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyTopologyPlanner.Occurrence::fixed).toList());
        assertTrue(result.get().occurrences().get(1).topologyAnchor());
        var original = result.get().originalProjected().get(1);
        middle.setCoor(new org.openstreetmap.josm.data.coor.LatLon(0, .003));
        assertEquals(original, result.get().originalProjected().get(1));
        assertTrue(result.get().simplify());
        assertEquals(1.5, result.get().projectedTolerance());
        assertThrows(IllegalStateException.class, () -> LegacyBSourceCapture.geometry(selection, false, 0));
    }

    @Test void acquiredManagedRasterKeepsNativePhaseScaleAndSamplingClamps() {
        var image = new java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(30, 60, 0xFFEEFFFF);
        var valid = new boolean[128 * 128];
        Arrays.fill(valid, true);
        var transform = org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence
                .SupportedInputRasterTransform.webMercator(15, 4_194_272, 4_194_272, 2);
        var raster = new ManagedModernPreviewSource.Raster(image, valid, transform, "hot", 15,
                "synthetic", new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(1));
        var sources = new ManagedModernPreviewSource.SourceRasters(Map.of("hot", raster),
                new AlignmentTileSourcePlan("hot", Set.of("hot"), Set.of(), Set.of(15, 13), false));
        var source = List.of(new ProjectedPoint(-40, 0), new ProjectedPoint(40, 0));
        var input = LegacyBSourceCapture.managed(settings(), sources, source, "EPSG:3857", "hot");
        assertEquals(List.of("hot@15"), List.copyOf(input.mosaics().keySet()));
        var mosaic = input.mosaics().get("hot@15");
        assertEquals(8_388_544, mosaic.originWorldPxX());
        assertEquals(8_388_544, mosaic.originWorldPxY());
        assertEquals(6, mosaic.parameters().halfWidthPx());
        assertEquals(1, mosaic.parameters().stepPx());
        assertEquals(mosaic.parameters().metersPerPixel() / .389 * 6,
                mosaic.virtualRasterScale(), 0);
        assertEquals(0xFFEEFFFF, mosaic.argb()[60 * 128 + 30]);
        image.setRGB(30, 60, 0);
        assertEquals(0xFFEEFFFF, mosaic.argb()[60 * 128 + 30]);
        assertEquals(6, input.trackerNormalizationRasterPx());
        var projection = Projections.getProjectionByCode("EPSG:3857");
        var sampler = new LegacyManagedProfileSampler();
        for (var point : List.of(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(0, 0),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint(600, 800))) {
            var projected = sampler.project(input, "hot", List.of(point)).get(0);
            var ll = projection.eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth(
                    projected.east(), projected.north()));
            var rasterPoint = transform.toRasterCenter(new org.openstreetmap.josm.plugins.wayheatmaptracer.model
                    .GeographicPoint(ll.lat(), ll.lon()));
            assertEquals(point.x() / mosaic.virtualRasterScale(), rasterPoint.x(), 1e-8);
            assertEquals(point.y() / mosaic.virtualRasterScale(), rasterPoint.y(), 1e-8);
        }
        var wrong = new ManagedModernPreviewSource.Raster(image, valid,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform
                    .webMercator(15, 4_194_272, 4_194_272, 1), "hot", 15, "synthetic", raster.generation());
        var wrongSources = new ManagedModernPreviewSource.SourceRasters(Map.of("hot", wrong), sources.plan());
        assertThrows(IllegalArgumentException.class, () -> LegacyBSourceCapture.managed(settings(),
                wrongSources, source, "EPSG:3857", "hot"));
        assertThrows(IllegalArgumentException.class, () -> LegacyBSourceCapture.managed(settings(),
                sources, source, "EPSG:3857", "all-colors-combined"));
    }
}

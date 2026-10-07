package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRasterGrid;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.MetricCorridorRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileTransport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TilePurpose;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TransportResponse;

class ManagedModernPreviewSourceTest {
    @TempDir java.nio.file.Path temporary;

    @Test
    void alternativesAcquireOnlyTheSelectedPalette() {
        ManagedHeatmapConfig config = config(true, false, IntensitySamplingMode.COLOR_MAPPING);
        Set<String> palettes = java.util.Collections.synchronizedSet(new HashSet<>());
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) -> {
            palettes.add(request.address().color());
            return new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null, null,
                    Duration.ZERO, "http-no-tile");
        }, new ManagedTileCache(temporary, decoder), decoder, TileReliabilityPolicy.defaults())) {
            ManagedTileGeneration generation = new ManagedTileGeneration(config.cacheBuster());
            coordinator.updateActiveGeneration(generation);

            ManagedModernPreviewSource.SourceRasters sources = new ManagedModernPreviewSource(coordinator)
                    .acquireSources(List.of(new GeographicPoint(0.0, 0.0),
                            new GeographicPoint(0.0, 0.001)), config, "alternatives",
                            CredentialSnapshot.fromConfig(null), () -> false);

            assertEquals(Set.of("blue"), sources.palettes().keySet());
            assertEquals(Set.of("blue"), palettes);
            assertFalse(sources.plan().aggregateDetectorRequested());
        }
    }

    @Test
    void aggregateAcquisitionRequestsExactlyTheFiveNativePalettes() {
        ManagedHeatmapConfig config = config(false, true, IntensitySamplingMode.COLOR_MAPPING);
        Set<String> palettes = java.util.Collections.synchronizedSet(new HashSet<>());
        Map<String, TilePurpose> purposes = new java.util.concurrent.ConcurrentHashMap<>();
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) -> {
            palettes.add(request.address().color());
            purposes.put(request.address().color(), request.purpose());
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                    sparsePng(new Color(0, 255, 0)), null, Duration.ZERO, "");
        }, new ManagedTileCache(temporary, decoder), decoder, TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster()));

            ManagedModernPreviewSource.SourceRasters sources = new ManagedModernPreviewSource(coordinator)
                    .acquireSources(List.of(new GeographicPoint(0.0, 0.0),
                            new GeographicPoint(0.0, 0.001)), config, "aggregate",
                            CredentialSnapshot.fromConfig(null), () -> false);

            Set<String> expected = Set.of("hot", "blue", "bluered", "purple", "gray");
            assertEquals(expected, sources.palettes().keySet(), sources.aggregateAvailability()
                    + " failure=" + sources.aggregateFailure() + " requested=" + palettes);
            assertEquals(expected, palettes);
            assertTrue(sources.plan().aggregateDetectorRequested());
            assertEquals(TilePurpose.ALIGNMENT_REQUIRED, purposes.get("blue"));
            for (String palette : List.of("hot", "bluered", "purple", "gray")) {
                assertEquals(TilePurpose.ALIGNMENT_OPTIONAL_AGGREGATE, purposes.get(palette));
            }
            var selected = sources.selectedRaster();
            assertTrue(sources.palettes().values().stream().allMatch(raster ->
                    raster.transform().equals(selected.transform())
                            && raster.zoom() == selected.zoom()
                            && raster.generation().equals(selected.generation())
                            && raster.sourceIdentity().equals(selected.sourceIdentity())
                            && raster.image().getWidth() == selected.image().getWidth()
                            && raster.image().getHeight() == selected.image().getHeight()));
        }
    }

    @Test
    void aggregateCompletenessRequiresEveryPaletteAtEachRequiredZoom() {
        ManagedHeatmapConfig config = config(false, true,
                IntensitySamplingMode.COLOR_MAPPING, 15, 14);
        Set<String> paletteZoomPairs = java.util.Collections.synchronizedSet(new HashSet<>());
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) -> {
            paletteZoomPairs.add(request.address().color() + "/" + request.address().zoom());
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                    sparsePng(new Color(0, 255, 0)), null, Duration.ZERO, "");
        }, new ManagedTileCache(temporary.resolve("dual-zoom-complete"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster()));
            var sources = new ManagedModernPreviewSource(coordinator).acquireSources(
                    List.of(new GeographicPoint(0.0, 0.0), new GeographicPoint(0.0, 0.001)),
                    config, "dual-zoom", CredentialSnapshot.fromConfig(null), () -> false);

            Set<String> expected = new HashSet<>();
            for (String palette : List.of("hot", "blue", "bluered", "purple", "gray")) {
                expected.add(palette + "/15");
                expected.add(palette + "/14");
            }
            assertEquals(expected, paletteZoomPairs);
            assertEquals(Set.of(15, 14), sources.plan().requiredZooms());
            assertTrue(sources.allRequiredZoomsWereAvailableAtAcquisition());
            assertEquals(Set.of(15, 14), sources.zoomReceipts().keySet());
            assertEquals(ManagedModernPreviewSource.ZoomAvailability.COMPLETE,
                    sources.zoomReceipts().get(14).availability());
            assertEquals(Set.of("hot", "blue", "bluered", "purple", "gray"),
                    sources.zoomReceipts().get(14).acquiredPalettes());
            assertEquals(64, sources.zoomReceipts().get(14).contentSupportDigest().length());
            assertThrows(UnsupportedOperationException.class, () -> sources.zoomReceipts().clear());
            assertEquals(ManagedModernPreviewSource.AggregateAvailability.COMPLETE,
                    sources.aggregateAvailability());
            assertEquals(Set.of("hot", "blue", "bluered", "purple", "gray"),
                    sources.palettes().keySet(), "primary detector rasters remain inference-zoom only");
            assertTrue(sources.palettes().values().stream().allMatch(raster -> raster.zoom() == 15));
        }
    }

    @Test
    void inferenceReceiptDigestBindsSafeTileFootprintForIdenticalRasters() {
        ManagedHeatmapConfig config = config(false, true,
                IntensitySamplingMode.COLOR_MAPPING, 15, 15);
        Set<String> tileFootprints = java.util.Collections.synchronizedSet(new HashSet<>());
        AtomicBoolean secondCapture = new AtomicBoolean();
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) -> {
            if (request.address().zoom() == 15) {
                tileFootprints.add((secondCapture.get() ? "second/" : "first/")
                        + request.address().x() + "/" + request.address().y());
            }
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                    sparsePng(new Color(0, 255, 0)), null, Duration.ZERO, "");
        }, new ManagedTileCache(temporary.resolve("footprint-digest"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster()));
            var first = new ManagedModernPreviewSource(coordinator).acquireSources(
                    List.of(new GeographicPoint(0.0, 0.0), new GeographicPoint(0.0, 0.001)),
                    config, "same-safe-source-id", CredentialSnapshot.fromConfig(null), () -> false);
            secondCapture.set(true);
            var second = new ManagedModernPreviewSource(coordinator).acquireSources(
                    List.of(new GeographicPoint(0.0, 0.0439453125),
                            new GeographicPoint(0.0, 0.0449453125)),
                    config, "same-safe-source-id", CredentialSnapshot.fromConfig(null), () -> false);

            var firstRaster = first.selectedRaster();
            var secondRaster = second.selectedRaster();
            assertEquals(firstRaster.image().getWidth(), secondRaster.image().getWidth());
            assertEquals(firstRaster.image().getHeight(), secondRaster.image().getHeight());
            assertEquals(firstRaster.image().getRGB(200, 200), secondRaster.image().getRGB(200, 200));
            assertNotEquals(tileFootprints.stream().filter(value -> value.startsWith("first/"))
                    .map(value -> value.substring("first/".length()))
                    .collect(java.util.stream.Collectors.toSet()),
                    tileFootprints.stream().filter(value -> value.startsWith("second/"))
                            .map(value -> value.substring("second/".length()))
                            .collect(java.util.stream.Collectors.toSet()));
            assertNotEquals(first.zoomReceipts().get(15).contentSupportDigest(),
                    second.zoomReceipts().get(15).contentSupportDigest());
        }
    }

    @Test
    void aggregateCompletenessFailsWhenRequiredLowerZoomPaletteIsMissing() {
        ManagedHeatmapConfig config = config(false, true,
                IntensitySamplingMode.COLOR_MAPPING, 15, 14);
        Set<String> paletteZoomPairs = java.util.Collections.synchronizedSet(new HashSet<>());
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) -> {
            paletteZoomPairs.add(request.address().color() + "/" + request.address().zoom());
            return request.address().zoom() == 14 && "purple".equals(request.address().color())
                    ? new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null,
                            null, Duration.ZERO, "safe-no-tile")
                    : new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                            sparsePng(new Color(0, 255, 0)), null, Duration.ZERO, "");
        }, new ManagedTileCache(temporary.resolve("dual-zoom-missing"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster()));
            var sources = new ManagedModernPreviewSource(coordinator).acquireSources(
                    List.of(new GeographicPoint(0.0, 0.0), new GeographicPoint(0.0, 0.001)),
                    config, "missing-lower-zoom", CredentialSnapshot.fromConfig(null), () -> false);

            assertTrue(paletteZoomPairs.contains("purple/14"));
            assertEquals(ManagedModernPreviewSource.AggregateAvailability.PALETTE_UNAVAILABLE,
                    sources.aggregateAvailability());
            assertFalse(sources.allRequiredZoomsWereAvailableAtAcquisition());
            assertEquals(ManagedModernPreviewSource.ZoomAvailability.PALETTE_UNAVAILABLE,
                    sources.zoomReceipts().get(14).availability());
            assertEquals("purple", sources.zoomReceipts().get(14).failedPalette());
            assertEquals(TileFetchStatus.NO_TILE, sources.zoomReceipts().get(14).failureStatus());
            assertEquals(14, sources.aggregateFailure().zoom());
            assertEquals(Set.of("blue"), sources.palettes().keySet());
            assertThrows(IllegalStateException.class,
                    () -> sources.completeAggregateScalars(() -> false));
        }
    }

    @Test
    void aggregateCompletenessFailsWhenRequiredLowerZoomIsUnauthorized() {
        ManagedHeatmapConfig config = config(false, true,
                IntensitySamplingMode.COLOR_MAPPING, 15, 14);
        Set<String> paletteZoomPairs = java.util.Collections.synchronizedSet(new HashSet<>());
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) -> {
            paletteZoomPairs.add(request.address().color() + "/" + request.address().zoom());
            return request.address().zoom() == 14 && "blue".equals(request.address().color())
                    ? new TransportResponse(TileFetchStatus.AUTH_FAILURE, 401, "", null,
                            null, Duration.ZERO, "private-response-body")
                    : new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                            sparsePng(new Color(0, 255, 0)), null, Duration.ZERO, "");
        }, new ManagedTileCache(temporary.resolve("dual-zoom-auth"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster()));
            var sources = new ManagedModernPreviewSource(coordinator).acquireSources(
                    List.of(new GeographicPoint(0.0, 0.0), new GeographicPoint(0.0, 0.001)),
                    config, "unauthorized-lower-zoom", CredentialSnapshot.fromConfig(null), () -> false);

            assertTrue(paletteZoomPairs.contains("blue/14"));
            assertEquals(ManagedModernPreviewSource.AggregateAvailability.PALETTE_UNAVAILABLE,
                    sources.aggregateAvailability());
            assertFalse(sources.allRequiredZoomsWereAvailableAtAcquisition());
            assertEquals(TileFetchStatus.AUTH_FAILURE,
                    sources.zoomReceipts().get(14).failureStatus());
            assertEquals("blue", sources.zoomReceipts().get(14).failedPalette());
            assertEquals(14, sources.aggregateFailure().zoom());
            assertEquals(Set.of("blue"), sources.palettes().keySet());
            assertFalse(sources.toString().contains("private-response-body"));
        }
    }

    @Test
    void staleRequiredLowerZoomCannotCompleteAggregate() {
        ManagedHeatmapConfig config = config(false, true,
                IntensitySamplingMode.COLOR_MAPPING, 15, 14);
        Set<String> paletteZoomPairs = java.util.Collections.synchronizedSet(new HashSet<>());
        java.util.concurrent.atomic.AtomicReference<TileFetchCoordinator> owner =
                new java.util.concurrent.atomic.AtomicReference<>();
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) -> {
            paletteZoomPairs.add(request.address().color() + "/" + request.address().zoom());
            if (request.address().zoom() == 14) {
                owner.get().updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster() + 1));
            }
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                    sparsePng(new Color(0, 255, 0)), null, Duration.ZERO, "");
        }, new ManagedTileCache(temporary.resolve("dual-zoom-stale"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            owner.set(coordinator);
            coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster()));
            assertThrows(IllegalStateException.class, () ->
                    new ManagedModernPreviewSource(coordinator).acquireSources(
                            List.of(new GeographicPoint(0.0, 0.0), new GeographicPoint(0.0, 0.001)),
                            config, "stale-lower-zoom", CredentialSnapshot.fromConfig(null), () -> false));
            assertTrue(paletteZoomPairs.stream().anyMatch(pair -> pair.endsWith("/14")));
        }
    }

    @Test
    void cancellationDuringRequiredLowerZoomReturnsNoPartialSourceSet() {
        ManagedHeatmapConfig config = config(false, true,
                IntensitySamplingMode.COLOR_MAPPING, 15, 14);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean enteredLowerZoom = new AtomicBoolean();
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) -> {
            if (request.address().zoom() == 14) {
                enteredLowerZoom.set(true);
                cancelled.set(true);
            }
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                    sparsePng(new Color(0, 255, 0)), null, Duration.ZERO, "");
        }, new ManagedTileCache(temporary.resolve("cancel-lower-zoom"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster()));
            assertThrows(java.util.concurrent.CancellationException.class, () ->
                    new ManagedModernPreviewSource(coordinator).acquireSources(
                            List.of(new GeographicPoint(0.0, 0.0), new GeographicPoint(0.0, 0.001)),
                            config, "cancel-lower-zoom", CredentialSnapshot.fromConfig(null), cancelled::get));
            assertTrue(enteredLowerZoom.get());
        }
    }

    @Test
    void staleCoordinatorGenerationInvalidatesPreviouslyCompleteZoomProof() {
        ManagedHeatmapConfig config = config(false, true, IntensitySamplingMode.COLOR_MAPPING, 15, 14);
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) ->
                new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                        sparsePng(new Color(0, 255, 0)), null, Duration.ZERO, ""),
                new ManagedTileCache(temporary.resolve("stale-after-acquisition"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster()));
            var sources = new ManagedModernPreviewSource(coordinator).acquireSources(
                    List.of(new GeographicPoint(0.0, 0.0), new GeographicPoint(0.0, 0.001)),
                    config, "stale-after-acquisition", CredentialSnapshot.fromConfig(null), () -> false);
            assertTrue(sources.provenCompleteAggregate(coordinator));
            coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster() + 1));
            assertTrue(sources.allRequiredZoomsWereAvailableAtAcquisition(),
                    "receipt completeness is historical and does not establish current freshness");
            assertFalse(sources.provenCompleteAggregate(coordinator));
            assertThrows(IllegalStateException.class, () -> sources.completeAggregateScalars(() -> false));
        }
    }

    @Test
    void missingAggregatePaletteRetainsOnlyNativeRasterWithTypedFailure() {
        ManagedHeatmapConfig config = config(false, true, IntensitySamplingMode.COLOR_MAPPING);
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) ->
                "purple".equals(request.address().color())
                        ? new TransportResponse(TileFetchStatus.NO_TILE, 404, "", null,
                                null, Duration.ZERO, "safe-status")
                        : new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                                sparsePng(new Color(0, 255, 0)), null, Duration.ZERO, ""),
                new ManagedTileCache(temporary.resolve("missing"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster()));
            var sources = new ManagedModernPreviewSource(coordinator).acquireSources(
                    List.of(new GeographicPoint(0.0, 0.0), new GeographicPoint(0.0, 0.001)),
                    config, "missing-purple", CredentialSnapshot.fromConfig(null), () -> false);

            assertEquals(ManagedModernPreviewSource.AggregateAvailability.PALETTE_UNAVAILABLE,
                    sources.aggregateAvailability());
            assertEquals("purple", sources.aggregateFailure().palette());
            assertEquals(TileFetchStatus.NO_TILE, sources.aggregateFailure().status());
            assertEquals(Set.of("blue"), sources.palettes().keySet());
            assertThrows(IllegalStateException.class, () -> sources.completeAggregateScalars(() -> false));
        }
    }

    @Test
    void optionalAuthenticationFailureKeepsSelectedSourceAndSafeFailureStatus() {
        ManagedHeatmapConfig config = config(false, true, IntensitySamplingMode.COLOR_MAPPING);
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) ->
                "gray".equals(request.address().color())
                        ? new TransportResponse(TileFetchStatus.AUTH_FAILURE, 401, "", null,
                                null, Duration.ZERO, "credential-derived-body")
                        : new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                                sparsePng(new Color(0, 255, 0)), null, Duration.ZERO, ""),
                new ManagedTileCache(temporary.resolve("auth"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster()));
            var sources = new ManagedModernPreviewSource(coordinator).acquireSources(
                    List.of(new GeographicPoint(0.0, 0.0), new GeographicPoint(0.0, 0.001)),
                    config, "auth-gray", CredentialSnapshot.fromConfig(null), () -> false);

            assertEquals(ManagedModernPreviewSource.AggregateAvailability.PALETTE_UNAVAILABLE,
                    sources.aggregateAvailability());
            assertEquals(TileFetchStatus.AUTH_FAILURE, sources.aggregateFailure().status());
            assertEquals(Set.of("blue"), sources.palettes().keySet());
            assertFalse(sources.toString().contains("credential-derived-body"));
        }
    }

    @Test
    void completeAggregateUsesTheSharedCalibratedScalarFusion() {
        ManagedHeatmapConfig config = config(false, true, IntensitySamplingMode.COLOR_MAPPING);
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        Map<String, BufferedImage> acquiredImages = new java.util.concurrent.ConcurrentHashMap<>();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) -> {
            Color color = switch (request.address().color()) {
                case "hot" -> new Color(255, 96, 0);
                case "blue" -> new Color(32, 64, 255);
                case "bluered" -> new Color(255, 32, 64);
                case "purple" -> new Color(224, 176, 255);
                default -> new Color(96, 96, 96);
            };
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                    sparsePng(color), null, Duration.ZERO, "");
        }, new ManagedTileCache(temporary.resolve("scalar"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster()));
            var sources = new ManagedModernPreviewSource(coordinator).acquireSources(
                    List.of(new GeographicPoint(0.0, 0.0), new GeographicPoint(0.0, 0.001)),
                    config, "scalar-fusion", CredentialSnapshot.fromConfig(null), () -> false);
            sources.palettes().forEach((palette, raster) -> acquiredImages.put(palette, raster.image()));

            assertTrue(sources.provenCompleteAggregate(coordinator));
            double[] scalars = sources.completeAggregateScalars(() -> false);
            var selected = sources.selectedRaster();
            int width = selected.image().getWidth();
            assertEquals(RenderedHeatmapSampler.aggregatedSourceIntensityAt(acquiredImages, 10, 10),
                    scalars[10 * width + 10], 1.0e-12);
            assertThrows(UnsupportedOperationException.class, () -> sources.palettes().clear());
            sources.selectedRaster().image().setRGB(10, 10, 0xffff0000);
            assertFalse(sources.provenCompleteAggregate(coordinator),
                    "a caller-mutated source image must invalidate the acquisition proof");
            assertThrows(IllegalStateException.class, () -> sources.completeAggregateScalars(() -> false));
        }
    }

    @Test
    void oneNativeRasterSupportsNamedAlternativesAndDirectScalarMappings() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        image.setRGB(1, 0, 0x80ffffff);
        image.setRGB(0, 1, 0x0000ff00);
        boolean[] valid = {true, true, true, false};
        ManagedModernPreviewSource.Raster raster = new ManagedModernPreviewSource.Raster(image, valid,
                SupportedInputRasterTransform.webMercator(1, 0.0, 0.0, 2.0),
                "blue", 1, "scalar-mapping", new ManagedTileGeneration(3));

        double hot = raster.scalarValues("hot", IntensitySamplingMode.COLOR_MAPPING, () -> false)[0];
        double blue = raster.scalarValues("blue", IntensitySamplingMode.COLOR_MAPPING, () -> false)[0];
        double[] luminance = raster.scalarValues("ignored", IntensitySamplingMode.DIRECT_LUMINANCE,
                () -> false);
        double[] maximum = raster.scalarValues("ignored", IntensitySamplingMode.DIRECT_VALUE, () -> false);
        double[] alpha = raster.scalarValues("ignored", IntensitySamplingMode.DIRECT_ALPHA, () -> false);

        assertTrue(hot > blue, "named alternatives map the same acquired raster independently");
        assertEquals((0.2126 * 255.0) / 255.0, luminance[0], 1.0e-12);
        assertEquals(1.0, maximum[0], 1.0e-12);
        assertEquals(1.0, alpha[0], 1.0e-12);
        assertEquals(128.0 / 255.0, alpha[1], 1.0e-12);
        assertEquals(0.0, luminance[2], 1.0e-12);
        assertFalse(raster.validity()[3]);
        assertEquals(0.0, alpha[3], 1.0e-12);
    }

    @Test
    void callerConstructedSourceSetCannotClaimAggregateAndRejectsMismatchedGrid() {
        ManagedHeatmapConfig config = config(false, true, IntensitySamplingMode.COLOR_MAPPING);
        AlignmentTileSourcePlan plan = AlignmentTileSourcePlan.from(config);
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        boolean[] valid = {true, true, true, true};
        var transform = SupportedInputRasterTransform.webMercator(1, 0.0, 0.0, 2.0);
        Map<String, ManagedModernPreviewSource.Raster> matching = new java.util.LinkedHashMap<>();
        for (String palette : plan.orderedColors()) {
            matching.put(palette, new ManagedModernPreviewSource.Raster(image, valid,
                    transform, palette, 1, "constructed", new ManagedTileGeneration(17)));
        }

        var unproven = new ManagedModernPreviewSource.SourceRasters(matching, plan);
        assertEquals(ManagedModernPreviewSource.AggregateAvailability.NOT_REQUESTED,
                unproven.aggregateAvailability());
        assertFalse(unproven.allRequiredZoomsWereAvailableAtAcquisition());
        assertFalse(unproven.provenCompleteAggregate(null));
        assertThrows(IllegalStateException.class,
                () -> unproven.completeAggregateScalars(() -> false));

        Map<String, ManagedModernPreviewSource.Raster> mismatched = new java.util.LinkedHashMap<>(matching);
        mismatched.put("gray", new ManagedModernPreviewSource.Raster(image, valid,
                SupportedInputRasterTransform.webMercator(1, 1.0, 0.0, 2.0),
                "gray", 1, "constructed", new ManagedTileGeneration(17)));
        assertThrows(IllegalArgumentException.class,
                () -> new ManagedModernPreviewSource.SourceRasters(mismatched, plan));
        assertThrows(UnsupportedOperationException.class, () -> unproven.palettes().clear());
    }

    @Test
    void directScalarModeAcquiresOnePaletteEvenWhenColorFlagsAreEnabled() {
        for (IntensitySamplingMode mode : List.of(IntensitySamplingMode.DIRECT_LUMINANCE,
                IntensitySamplingMode.DIRECT_VALUE, IntensitySamplingMode.DIRECT_ALPHA)) {
            ManagedHeatmapConfig config = config(true, true, mode);
            Set<String> requested = java.util.Collections.synchronizedSet(new HashSet<>());
            TileDecoderClassifier decoder = new TileDecoderClassifier();
            try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) -> {
                requested.add(request.address().color());
                return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                        sparsePng(new Color(0, 255, 0)), null, Duration.ZERO, "");
            }, new ManagedTileCache(temporary.resolve(mode.name()), decoder), decoder,
                    TileReliabilityPolicy.defaults())) {
                coordinator.updateActiveGeneration(new ManagedTileGeneration(config.cacheBuster()));
                var sources = new ManagedModernPreviewSource(coordinator).acquireSources(
                        List.of(new GeographicPoint(0.0, 0.0), new GeographicPoint(0.0, 0.001)),
                        config, "direct-" + mode.detectorName(),
                        CredentialSnapshot.fromConfig(null), () -> false);
                assertEquals(Set.of("blue"), sources.palettes().keySet());
                assertEquals(Set.of("blue"), requested);
                assertFalse(sources.plan().aggregateDetectorRequested());
            }
        }
    }

    private ManagedHeatmapConfig config(boolean alternatives, boolean aggregate,
            IntensitySamplingMode intensityMode) {
        return config(alternatives, aggregate, intensityMode, 15, 15);
    }

    private ManagedHeatmapConfig config(boolean alternatives, boolean aggregate,
            IntensitySamplingMode intensityMode, int inferenceZoom, int validationZoom) {
        return new ManagedHeatmapConfig("key", "policy", "signature", "session", "all", "blue", "",
                ".*", AlignmentMode.PRECISE_SHAPE, TrackerMode.PROBABILISTIC, false, false,
                alternatives, aggregate, false, false, false, false, false, false,
                7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION, inferenceZoom, validationZoom, 7.01, 1.56,
                intensityMode, 17L);
    }

    private static byte[] uncheckedPng(Color color) {
        try {
            return png(color);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] sparsePng(Color color) {
        try {
            BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            graphics.setColor(color);
            graphics.fillRect(0, 0, 64, 64);
            graphics.dispose();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void directIntensityIgnoresColorDetectorFlagsWhenPlanningOneSelectedSource() {
        ManagedHeatmapConfig config = new ManagedHeatmapConfig("key", "policy", "signature", "session",
                "all", "blue", "", ".*", AlignmentMode.PRECISE_SHAPE,
                TrackerMode.PROBABILISTIC, false, false, true, true, false, false,
                false, false, false, false, 7, 4, 3.0,
                InferenceMode.RAW_HIGH_RESOLUTION, 15, 15, 7.01, 1.56,
                IntensitySamplingMode.DIRECT_LUMINANCE, 0L);

        ManagedModernPreviewSource.Request request = ManagedModernPreviewSource.selectedOnly(
                List.of(new GeographicPoint(0.0, 0.0), new GeographicPoint(0.0, 0.001)),
                config, "direct-blue");

        assertEquals("blue", request.palette());
        assertEquals("direct-blue", request.sourceIdentity());
    }

    @Test
    void selectedOnlyRequestRemainsCompatibleWhenColorFeaturesAreEnabled() {
        ManagedHeatmapConfig config = new ManagedHeatmapConfig("key", "policy", "signature", "session",
                "all", "blue", "", ".*", AlignmentMode.PRECISE_SHAPE,
                TrackerMode.PROBABILISTIC, false, false, true, true, false, false,
                false, false, false, false, 7, 4, 3.0,
                InferenceMode.RAW_HIGH_RESOLUTION, 15, 15, 7.01, 1.56,
                IntensitySamplingMode.COLOR_MAPPING, 0L);

        ManagedModernPreviewSource.Request request = ManagedModernPreviewSource.selectedOnly(
                List.of(new GeographicPoint(0.0, 0.0),
                        new GeographicPoint(0.0, 0.001)), config, "selected-only");
        assertEquals("blue", request.palette());
        assertEquals(List.of("blue"), request.requiredPalettes());
    }

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
    void managedGridContainsDiagonalDecisionCorridorCorners() {
        List<GeographicPoint> source = List.of(
                new GeographicPoint(42.7234, 19.2914),
                new GeographicPoint(42.7238, 19.2920));
        double radius = 7.01;
        LocalMetricFrame frame = ManagedModernPreviewSource.managedFrame(source, 15, radius);
        List<MetricPoint> metric = source.stream().map(frame::toMetric).toList();
        MetricRasterGrid grid = ManagedModernPreviewSource.managedOutputGrid(frame, metric, 15, radius);

        assertTrue(grid.footprint().containsRegion(
                MetricCorridorRegion.aroundPolyline(metric, radius)));
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

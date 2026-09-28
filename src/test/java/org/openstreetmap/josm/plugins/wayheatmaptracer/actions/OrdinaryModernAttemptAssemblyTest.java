package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentSourceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoverySettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TracingSettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewSessionController;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class OrdinaryModernAttemptAssemblyTest {
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void productionAssemblyCapturesComputesAndPublishesAllOrdinaryModernRoutes(@TempDir Path directory)
            throws Exception {
        List<RouteCase> cases = List.of(
                new RouteCase(TrackerMode.CORRIDOR_AWARE, AlignmentSourceMode.VISIBLE_LAYER, false),
                new RouteCase(TrackerMode.CORRIDOR_AWARE, AlignmentSourceMode.MANAGED_TILES, true),
                new RouteCase(TrackerMode.PROBABILISTIC, AlignmentSourceMode.VISIBLE_LAYER, false),
                new RouteCase(TrackerMode.PROBABILISTIC, AlignmentSourceMode.MANAGED_TILES, true),
                new RouteCase(TrackerMode.HYBRID, AlignmentSourceMode.VISIBLE_LAYER, false),
                new RouteCase(TrackerMode.DIRECTIONAL_IMAGE, AlignmentSourceMode.VISIBLE_LAYER, false));

        for (RouteCase routeCase : cases) {
            Fixture fixture = fixture();
            AlignmentConfig config = config(routeCase.engine());
            RecoverySettings recovery = RecoverySettings.defaults(7.01);
            TracingSettings tracing = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                    routeCase.engine(), recovery, false, routeCase.sourceMode());
            AlignWayAction.OrdinaryActionRouting<String> routing = AlignWayAction.resolveOrdinaryAction(
                    tracing, config, () -> "selected-visible", () -> "legacy-visible");
            AtomicInteger visibleCaptures = new AtomicInteger();
            AtomicInteger managedAcquisitions = new AtomicInteger();
            AtomicReference<LiveBPreviewService.Computed> published = new AtomicReference<>();
            CountDownLatch ready = new CountDownLatch(1);
            try (PreviewSessionController<LiveBPreviewService.Computed> session =
                    new PreviewSessionController<>(Runnable::run)) {
                PreviewSessionController.Owner owner = session.open(() -> { });
                SwingUtilities.invokeAndWait(() ->
                        new AlignWayAction.OrdinaryModernAttemptAssembly().start(
                                session, owner, routing, fixture.dataSet(), fixture.selection(),
                                "ordinary-" + routeCase.engine().name()
                                        .toLowerCase(java.util.Locale.ROOT),
                                (visibleSource, invocation, permissions) -> {
                                    visibleCaptures.incrementAndGet();
                                    assertEquals("selected-visible", visibleSource);
                                    assertEquals(recovery, invocation.recovery());
                                    assertEquals(recovery.toPermissions(), permissions);
                                    return visibleRaster();
                                }, (seed, invocation, context) -> {
                                    managedAcquisitions.incrementAndGet();
                                    assertEquals(recovery, invocation.recovery());
                                    assertEquals(recovery.toPermissions(), seed.specification().permissions());
                                    return managedRaster(seed.sourceIdentity());
                                }, attempt -> {
                                    published.set(attempt.result());
                                    ready.countDown();
                                }));

                assertTrue(ready.await(60, TimeUnit.SECONDS), routeCase.toString());
            }

            LiveBPreviewService.Computed computed = published.get();
            assertNotNull(computed, routeCase.toString());
            assertEquals(routeCase.engine(), computed.request().engine());
            assertEquals(recovery.toPermissions(), computed.request().permissions());
            assertEquals(routeCase.engine(), computed.pipeline().inference().engine());
            assertEquals(routeCase.managed() ? 0 : 1, visibleCaptures.get());
            assertEquals(routeCase.managed() ? 1 : 0, managedAcquisitions.get());
            if (routeCase.managed()) {
                assertNotNull(computed.captured().managedRaster());
                assertNull(computed.captured().raster());
            } else {
                assertNotNull(computed.captured().raster());
                assertNull(computed.captured().managedRaster());
            }
            if (routeCase.engine() == TrackerMode.PROBABILISTIC && !routeCase.managed()) {
                for (String status : List.of("preview-open", "failed", "cancelled")) {
                    AlignWayAction.recordModernDiagnostics(computed, status, 0, null,
                        false, false, "ordinary-attempt");
                    Path path = directory.resolve(status + ".zip");
                    DiagnosticsRegistry.writeLatest(path.toFile());
                    var archive = Format15ArchiveReader.read(path);
                    assertTrue(new String(archive.artifact("attempt-status.json")
                        .orElseThrow().bytes(), StandardCharsets.UTF_8).contains(status));
                    assertTrue(archive.artifact("frozen-input.bin").isPresent());
                    String counters = new String(archive.artifact("performance-counters.json")
                        .orElseThrow().bytes(), StandardCharsets.UTF_8);
                    assertTrue(counters.contains("inference.pairVisits"));
                    assertTrue(counters.contains("evaluatedTransitions"));
                }
                AlignWayAction.recordModernUnavailable("resource-limited", "visible-layer",
                    "ordinary-attempt");
                Path path = directory.resolve("resource-limited.zip");
                DiagnosticsRegistry.writeLatest(path.toFile());
                assertTrue(Format15ArchiveReader.read(path).artifact("frozen-input.bin").isEmpty());

                // The real export boundary must identify known faults without logging arbitrary text.
                PluginLog.beginSlideSession();
                try {
                    LiveBPreviewService.Computed invalidCounters = new LiveBPreviewService.Computed(
                            computed.captured(), computed.evidence(), computed.request(),
                            computed.pipeline(), computed.options(), Map.of("synthetic", Double.NaN));
                    AlignWayAction.recordModernDiagnostics(invalidCounters, "preview-open", 0,
                            null, false, false, "ordinary-attempt");
                    assertTrue(PluginLog.currentSlideLog().contains("cause=counter-invalid"));
                    AlignWayAction.recordModernDiagnostics(computed,
                            "synthetic?Signature=private-test-value", 0, null, false, false,
                            "ordinary-attempt");
                    String log = PluginLog.currentSlideLog();
                    assertTrue(log.contains("cause=export-failed"));
                    assertFalse(log.contains("private-test-value"));
                    Path rejected = directory.resolve("rejected-metadata.zip");
                    DiagnosticsRegistry.writeLatest(rejected.toFile());
                    assertTrue(Format15ArchiveReader.read(rejected)
                            .artifact("frozen-input.bin").isEmpty());
                } finally {
                    PluginLog.endSlideSession();
                }
            }
        }
    }

    @Test
    void productionAssemblyRejectsLateManagedResultAfterOrdinaryOwnerIsSuperseded()
            throws Exception {
        Fixture firstFixture = fixture();
        Fixture secondFixture = fixture(20);
        CountDownLatch firstAcquisitionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondPublished = new CountDownLatch(1);
        List<TrackerMode> published = java.util.Collections.synchronizedList(new ArrayList<>());
        AlignWayAction.OrdinaryModernAttemptAssembly assembly =
                new AlignWayAction.OrdinaryModernAttemptAssembly();
        try (PreviewSessionController<LiveBPreviewService.Computed> session =
                new PreviewSessionController<>(Runnable::run)) {
            var firstRouting = routing(TrackerMode.CORRIDOR_AWARE,
                    AlignmentSourceMode.MANAGED_TILES);
            var firstOwner = session.open(() -> { });
            SwingUtilities.invokeAndWait(() -> assembly.start(session, firstOwner, firstRouting,
                    firstFixture.dataSet(), firstFixture.selection(), "managed-old",
                    (source, invocation, permissions) -> visibleRaster(),
                    (seed, invocation, context) -> {
                        firstAcquisitionStarted.countDown();
                        releaseFirst.await(5, TimeUnit.SECONDS);
                        return managedRaster(seed.sourceIdentity());
                    }, attempt -> published.add(attempt.result().request().engine())));
            assertTrue(firstAcquisitionStarted.await(5, TimeUnit.SECONDS));

            var secondRouting = routing(TrackerMode.PROBABILISTIC,
                    AlignmentSourceMode.VISIBLE_LAYER);
            var secondOwner = session.open(() -> { });
            SwingUtilities.invokeAndWait(() -> assembly.start(session, secondOwner, secondRouting,
                    secondFixture.dataSet(), secondFixture.selection(), "visible-new",
                    (source, invocation, permissions) -> visibleRaster(),
                    (seed, invocation, context) -> managedRaster(seed.sourceIdentity()),
                    attempt -> {
                        published.add(attempt.result().request().engine());
                        secondPublished.countDown();
                    }));
            releaseFirst.countDown();

            assertTrue(secondPublished.await(15, TimeUnit.SECONDS));
            assertEquals(List.of(TrackerMode.PROBABILISTIC), published);
        } finally {
            releaseFirst.countDown();
        }
    }

    private static AlignWayAction.OrdinaryActionRouting<String> routing(
            TrackerMode engine, AlignmentSourceMode sourceMode) {
        RecoverySettings recovery = RecoverySettings.defaults(7.01);
        return AlignWayAction.resolveOrdinaryAction(new TracingSettings(
                TracingSettings.CURRENT_SCHEMA_VERSION, engine, recovery, false, sourceMode),
                config(engine), () -> "selected-visible", () -> "legacy-visible");
    }

    private static LiveBPreviewService.VisibleRaster visibleRaster() {
        int width = 600;
        int height = 600;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            double distance = (y - 288.0) / RenderedHeatmapSampler.RASTER_SCALE;
            double intensity = 0.02 + 0.80 * Math.exp(-0.5 * distance * distance / (1.2 * 1.2));
            int gray = (int) Math.round(255.0 * intensity);
            int pixel = 0xff000000 | gray << 16 | gray << 8 | gray;
            java.util.Arrays.fill(argb, y * width, (y + 1) * width, pixel);
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb,
                -50.0, -50.0, 50.0, 50.0, 1.0, 1.0,
                OptionalDouble.of(1.0), "visible-production", "EPSG:3857");
    }

    private static ManagedModernPreviewSource.Raster managedRaster(String sourceIdentity) {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                image.setRGB(x, y, 0xffffffff);
            }
        }
        return new ManagedModernPreviewSource.Raster(image,
                new boolean[] {true, true, true, true},
                SupportedInputRasterTransform.webMercator(15, 0.0, 0.0, 2.0),
                "hot", 15, sourceIdentity,
                new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(0L));
    }

    private static AlignmentConfig config(TrackerMode engine) {
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig(
                "key", "policy", "signature", "session", "all", "hot", "", ".*",
                AlignmentMode.PRECISE_SHAPE, engine, false, false,
                false, false, false, false, false, false, false, false,
                7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15,
                7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    private static Fixture fixture() {
        return fixture(0);
    }

    private static Fixture fixture(long idOffset) {
        DataSet dataSet = new DataSet();
        Node a = loadedNode(idOffset + 1, longitude(-8));
        Node b = loadedNode(idOffset + 2, longitude(8));
        Way way = new Way();
        way.setNodes(List.of(a, b));
        way.setOsmId(idOffset + 10, 1);
        way.setModified(false);
        dataSet.addPrimitive(a);
        dataSet.addPrimitive(b);
        dataSet.addPrimitive(way);
        return new Fixture(dataSet,
                new SelectionContext(way, 0, 1, List.of(a, b), Set.of(a, b)));
    }

    private static Node loadedNode(long id, double longitude) {
        Node node = new Node(new LatLon(0.0, longitude));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static double longitude(double meters) {
        return Math.toDegrees(meters / 6_378_137.0);
    }

    private record Fixture(DataSet dataSet, SelectionContext selection) { }

    private record RouteCase(TrackerMode engine, AlignmentSourceMode sourceMode,
            boolean managed) { }
}

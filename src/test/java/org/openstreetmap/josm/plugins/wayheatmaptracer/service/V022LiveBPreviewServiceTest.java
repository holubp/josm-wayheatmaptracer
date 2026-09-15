package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class V022LiveBPreviewServiceTest {
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void realProductionBReturnsFinalReadOnlyGeometryAndPreservesDataSet() throws Exception {
        Fixture fixture = fixture();
        List<String> before = state(fixture.dataSet());
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(fixture.dataSet(),
                fixture.selection(), raster(), config()));

        var evidence = service.captureEvidence(captured[0], CancellationProbe.NONE);
        var profiles = new ProbabilisticProfileFactory().create(captured[0].sourceMetric(),
                captured[0].sampleStepMeters(), captured[0].searchRadiusMeters(), true,
                evidence, evidence.fields().get("selected-visible-source"));
        assertTrue(profiles.stream().anyMatch(profile -> !profile.modes().isEmpty()),
                "synthetic visible raster must contain localized B evidence");
        LiveBPreviewService.Computed result = service.compute(captured[0], CancellationProbe.NONE);

        assertFalse(result.pipeline().routes().isEmpty(),
                "supported visible evidence must produce a final B route");
        var route = result.pipeline().routes().get(0);
        assertEquals(1.0, route.hypothesis().diagnostics().get("commonFinalProcessing"));
        assertEquals(2, route.existingAssignments().size());
        assertTrue(route.pointIds().get(0) instanceof ExistingWayNodeOccurrence);
        assertTrue(route.pointIds().get(route.pointIds().size() - 1)
                instanceof ExistingWayNodeOccurrence);
        assertEquals(before, state(fixture.dataSet()));
        SwingUtilities.invokeAndWait(() -> service.requireCurrent(fixture.dataSet(), captured[0]));
    }

    @Test
    void realProductionAUsesFactualDetachedLocationsAndPreservesDataSet() throws Exception {
        Fixture fixture = fixture();
        List<String> before = state(fixture.dataSet());
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(fixture.dataSet(),
                fixture.selection(), raster(), config(TrackerMode.CORRIDOR_AWARE)));

        LiveBPreviewService.Computed result = service.compute(captured[0], CancellationProbe.NONE);

        assertEquals(TrackerMode.CORRIDOR_AWARE, result.pipeline().inference().engine());
        var locations = result.request().corridorInput().orElseThrow().profileLocations();
        assertEquals(result.request().profileChainage().cumulativeGroundMeters(), locations.stream()
                .map(location -> location.cumulativeGroundDistanceMeters()).toList());
        assertEquals(captured[0].sourceMetric().get(0), locations.get(0).metricPoint());
        assertEquals(captured[0].sourceMetric().get(captured[0].sourceMetric().size() - 1),
                locations.get(locations.size() - 1).metricPoint());
        double sourceLength = captured[0].sourceMetric().get(0).distanceTo(
                captured[0].sourceMetric().get(captured[0].sourceMetric().size() - 1));
        for (int index = 1; index < locations.size() - 1; index++) {
            double fraction = result.request().profileChainage().cumulativeGroundMeters().get(index)
                    / sourceLength;
            MetricPoint start = captured[0].sourceMetric().get(0);
            MetricPoint end = captured[0].sourceMetric().get(captured[0].sourceMetric().size() - 1);
            MetricPoint expected = new MetricPoint(start.xMeters() + fraction * (end.xMeters() - start.xMeters()),
                    start.yMeters() + fraction * (end.yMeters() - start.yMeters()));
            assertEquals(expected.xMeters(), locations.get(index).metricPoint().xMeters(), 1.0e-12);
            assertEquals(expected.yMeters(), locations.get(index).metricPoint().yMeters(), 1.0e-12);
            var expectedGeographic = result.evidence().coordinateFrame().toGeographic(expected);
            assertEquals(expectedGeographic.latitudeDegrees(),
                    locations.get(index).geographicPoint().latitudeDegrees(), 1.0e-12);
            assertEquals(expectedGeographic.longitudeDegrees(),
                    locations.get(index).geographicPoint().longitudeDegrees(), 1.0e-12);
            var expectedRaster = result.evidence().transform().metricToPixelCenter(expected);
            assertEquals(expectedRaster.x(), locations.get(index).rasterPoint().x(), 1.0e-12);
            assertEquals(expectedRaster.y(), locations.get(index).rasterPoint().y(), 1.0e-12);
        }
        assertEquals(result.evidence().resolution().outputRasterPitchMeters(),
                result.request().corridorInput().orElseThrow().lateralStepMeters());
        assertFalse(result.pipeline().routes().isEmpty(),
                "supported visible evidence must produce a final A route");
        var route = result.pipeline().routes().get(0);
        assertEquals(1.0, route.hypothesis().diagnostics().get("commonFinalProcessing"));
        assertEquals(2, route.existingAssignments().size());
        assertTrue(route.pointIds().get(0) instanceof ExistingWayNodeOccurrence);
        assertTrue(route.pointIds().get(route.pointIds().size() - 1)
                instanceof ExistingWayNodeOccurrence);
        assertEquals(before, state(fixture.dataSet()));
        SwingUtilities.invokeAndWait(() -> service.requireCurrent(fixture.dataSet(), captured[0]));
    }

    @Test
    void publicationRejectsChangedSourceProjectionAndNewRelevantReferrer() throws Exception {
        Fixture fixture = fixture();
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(fixture.dataSet(),
                fixture.selection(), raster(), config()));

        SwingUtilities.invokeAndWait(() -> {
            assertThrows(IllegalStateException.class, () -> service.requireCurrent(
                    fixture.dataSet(), captured[0], "different-layer", "EPSG:3857"));
            assertThrows(IllegalStateException.class, () -> service.requireCurrent(
                    fixture.dataSet(), captured[0], "visible-test", "EPSG:4326"));
            Node outside = loadedNode(3, 0.0, longitude(12));
            Way referrer = new Way();
            referrer.setNodes(List.of(fixture.selection().segmentNodes().get(1), outside));
            referrer.setOsmId(11, 1);
            fixture.dataSet().addPrimitive(outside);
            fixture.dataSet().addPrimitive(referrer);
            assertThrows(RuntimeException.class,
                    () -> service.requireCurrent(fixture.dataSet(), captured[0]));
        });
    }

    @Test
    void managedCandidateSwitchRejectsProjectionChangedAfterCapture() throws Exception {
        Fixture fixture = fixture();
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.ManagedCaptureSeed[] seed = new LiveBPreviewService.ManagedCaptureSeed[1];
        SwingUtilities.invokeAndWait(() -> seed[0] = service.captureManagedSeed(fixture.dataSet(),
                fixture.selection(), managedConfig(), "managed-test"));
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        LiveBPreviewService.Captured captured = service.attachManagedRaster(seed[0],
                new ManagedModernPreviewSource.Raster(image,
                        new boolean[] {true, true, true, true},
                        SupportedInputRasterTransform.webMercator(15, 0.0, 0.0, 2.0),
                        "hot", 15, "managed-test"));

        assertEquals("EPSG:3857", captured.projectionCode());
        try {
            SwingUtilities.invokeAndWait(() -> {
                ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:4326"));
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> service.requireCurrent(fixture.dataSet(), captured));
                assertTrue(failure.getMessage().contains("projection"));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> ProjectionRegistry.setProjection(
                    Projections.getProjectionByCode("EPSG:3857")));
        }
    }

    @Test
    void unsupportedControlsFailExplicitlyBeforeRasterOrNetworkWork() throws Exception {
        Fixture fixture = fixture();
        ManagedHeatmapConfig unsupported = config().heatmap().withAlignmentMode(AlignmentMode.MOVE_EXISTING_NODES);
        IllegalArgumentException[] failure = new IllegalArgumentException[1];
        SwingUtilities.invokeAndWait(() -> failure[0] = assertThrows(IllegalArgumentException.class,
                () -> new LiveBPreviewService().capture(fixture.dataSet(), fixture.selection(), raster(),
                        new AlignmentConfig(unsupported, GeometryCleanupConfig.disabled()))));
        assertTrue(failure[0].getMessage().contains("Precise Shape"));
    }

    @Test
    void explicitVisibleSourceSessionAcceptsStoredCredentialsWithoutPassingThemToWorkerInput()
            throws Exception {
        Fixture fixture = fixture();
        ManagedHeatmapConfig storedCredentials = new ManagedHeatmapConfig("key-secret", "policy-secret",
                "signature-secret", "session-secret", "all", "hot", ".", ".*",
                AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE, false, false,
                false, false, false, false, false, false, false, false,
                7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15,
                7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        AlignmentConfig config = new AlignmentConfig(storedCredentials, GeometryCleanupConfig.disabled());
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        LiveBPreviewService.Captured[] capturedB = new LiveBPreviewService.Captured[1];
        AlignmentConfig probabilistic = new AlignmentConfig(storedCredentials.withTrackerMode(
                TrackerMode.PROBABILISTIC), GeometryCleanupConfig.disabled());

        SwingUtilities.invokeAndWait(() -> {
            assertThrows(IllegalArgumentException.class, () -> service.capture(fixture.dataSet(),
                    fixture.selection(), raster(), config));
            captured[0] = service.capture(fixture.dataSet(), fixture.selection(), raster(), config, true);
            capturedB[0] = service.capture(fixture.dataSet(), fixture.selection(), raster(), probabilistic, true);
        });

        assertFalse(captured[0].toString().contains("secret"));
        assertFalse(capturedB[0].toString().contains("secret"));
        assertEquals(TrackerMode.CORRIDOR_AWARE, captured[0].engine());
        assertEquals(TrackerMode.PROBABILISTIC, capturedB[0].engine());
    }

    @Test
    void visibleRasterRejectsBoundsThatDoNotMatchOversampledDimensions() {
        int[] pixels = new int[200 * 200];
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new LiveBPreviewService.VisibleRaster(200, 200, pixels,
                        -50.0, -50.0, 50.0, 50.0, 1.0, 1.0,
                        OptionalDouble.of(1.0), "visible-test", "EPSG:3857"));
        assertTrue(failure.getMessage().contains("dimensions"));
    }

    private static Fixture fixture() {
        DataSet dataSet = new DataSet();
        Node a = loadedNode(1, 0.0, longitude(-8));
        Node b = loadedNode(2, 0.0, longitude(8));
        Way way = new Way();
        way.setNodes(List.of(a, b));
        way.setOsmId(10, 1);
        way.setModified(false);
        dataSet.addPrimitive(a);
        dataSet.addPrimitive(b);
        dataSet.addPrimitive(way);
        return new Fixture(dataSet, new SelectionContext(way, 0, 1, List.of(a, b), Set.of(a, b)));
    }

    private static LiveBPreviewService.VisibleRaster raster() {
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
                OptionalDouble.of(1.0), "visible-test", "EPSG:3857");
    }

    private static AlignmentConfig config() {
        return config(TrackerMode.PROBABILISTIC);
    }

    private static AlignmentConfig config(TrackerMode trackerMode) {
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig("", "", "", "", "all", "hot", "", ".*",
                AlignmentMode.PRECISE_SHAPE, trackerMode, false, false,
                false, false, false, false, false, false, false, false,
                7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15,
                7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    private static AlignmentConfig managedConfig() {
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig("key", "policy", "signature", "session",
                "all", "hot", "", ".*", AlignmentMode.PRECISE_SHAPE,
                TrackerMode.CORRIDOR_AWARE, false, false, false, false, false, false,
                false, false, false, false, 7, 4, 3.0,
                InferenceMode.RAW_HIGH_RESOLUTION, 15, 15, 7.01, 1.56,
                IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    private static double longitude(double meters) {
        return Math.toDegrees(meters / 6_378_137.0);
    }

    private static Node loadedNode(long id, double lat, double lon) {
        Node node = new Node(new LatLon(lat, lon));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static List<String> state(DataSet dataSet) {
        return dataSet.allPrimitives().stream().map(primitive -> primitiveState(dataSet, primitive))
                .sorted().toList();
    }

    private static String primitiveState(DataSet dataSet, OsmPrimitive primitive) {
        String payload = primitive instanceof Node node
                ? "coord=" + node.lat() + "," + node.lon()
                : primitive instanceof Way way
                    ? "nodes=" + way.getNodes().stream()
                            .map(node -> Long.toString(node.getUniqueId())).toList()
                    : "members=" + primitive.getReferrers().stream()
                            .map(referrer -> Long.toString(referrer.getUniqueId())).sorted().toList();
        return primitive.getType() + ":" + primitive.getUniqueId()
                + ":member=" + (primitive.getDataSet() == dataSet)
                + ":modified=" + primitive.isModified()
                + ":deleted=" + primitive.isDeleted()
                + ":visible=" + primitive.isVisible()
                + ":incomplete=" + primitive.isIncomplete()
                + ":disabled=" + primitive.isDisabled()
                + ":tags=" + primitive.getKeys() + ":" + payload;
    }

    private record Fixture(DataSet dataSet, SelectionContext selection) { }
}

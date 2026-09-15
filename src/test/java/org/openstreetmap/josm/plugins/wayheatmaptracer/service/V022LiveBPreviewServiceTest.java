package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
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
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig("", "", "", "", "all", "hot", "", ".*",
                AlignmentMode.PRECISE_SHAPE, TrackerMode.PROBABILISTIC, false, false,
                false, false, false, false, false, false, false, false,
                7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15,
                7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
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

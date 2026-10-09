package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ApplyAlignmentEditPlanCommand;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** End-to-end authority check for a partial live selection under legacy bounded movement. */
class V022PartialSelectionBoundaryWorkflowTest {
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @BeforeEach
    void clearUndoStack() {
        UndoRedoHandler.getInstance().clean();
    }

    @AfterEach
    void leaveUndoStackClean() {
        UndoRedoHandler.getInstance().clean();
    }

    @Test
    void partialSelectionApplyUndoRedoPreservesBothUnselectedContinuations() throws Exception {
        Fixture fixture = fixture();
        List<NodeState> original = state(fixture.way());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.LEGACY_BOUNDED_MOVE, false);
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(fixture.dataSet(),
                fixture.selection(), raster(), config(), true, permissions));

        PrimitiveKey prefix = key(fixture.way().getNode(0));
        PrimitiveKey suffix = key(fixture.way().getNode(4));
        assertFalse(captured[0].network().closure().movableExistingNodeKeys().contains(prefix));
        assertFalse(captured[0].network().closure().movableExistingNodeKeys().contains(suffix));
        assertTrue(captured[0].network().closure().protectedExistingNodeKeys().containsAll(
                Set.of(key(fixture.way().getNode(1)), key(fixture.way().getNode(3)))));

        LiveBPreviewService.Computed computed = service.compute(captured[0], CancellationProbe.NONE);
        ModernSingleWayEditPlanAdapter.Assessment assessment =
                new ModernSingleWayEditPlanAdapter().assess(computed, 0);
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE,
                assessment.availability(), assessment.detail());
        AlignmentEditPlan plan = assessment.plan().orElseThrow();
        assertEquals(plan.before().primitives().get(prefix), plan.after().primitives().get(prefix));
        assertEquals(plan.before().primitives().get(suffix), plan.after().primitives().get(suffix));
        PrimitiveKey selectedInterior = key(fixture.way().getNode(2));
        assertNotEquals(plan.before().primitives().get(selectedInterior),
                plan.after().primitives().get(selectedInterior),
                "the selected ordinary interior must move in this heatmap fixture");
        List<GeographicPoint> expectedGeometry = plan.finalPreviewWays()
                .get(plan.selectedWayKey());
        assertTrue(expectedGeometry.size() >= 3);
        PreviewReviewState review = PreviewReviewState.fromEditPlan("partial-selection", plan);
        assertTrue(review.confirm().confirmed());

        NetworkSnapshotCapture.CapturedSnapshot[] receipt =
                new NetworkSnapshotCapture.CapturedSnapshot[1];
        SwingUtilities.invokeAndWait(() -> receipt[0] = NetworkSnapshotCapture.captureBound(
                fixture.dataSet(), captured[0].specification()));
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), plan, new LiveNetworkSnapshotValidator(receipt[0], plan,
                        () -> plan.before().sourceGeneration()), "Apply reviewed partial selection");
        SwingUtilities.invokeAndWait(() -> UndoRedoHandler.getInstance().add(command));
        List<NodeState> applied = state(fixture.way());
        assertEquals(expectedGeometry, applied.stream().map(node ->
                new GeographicPoint(node.coordinate().lat(), node.coordinate().lon())).toList());
        assertEquals(original.get(0), applied.get(0));
        assertEquals(original.get(4), applied.get(applied.size() - 1));

        SwingUtilities.invokeAndWait(() -> UndoRedoHandler.getInstance().undo());
        assertEquals(original, state(fixture.way()));
        SwingUtilities.invokeAndWait(() -> UndoRedoHandler.getInstance().redo());
        assertEquals(applied, state(fixture.way()));
        assertEquals(expectedGeometry, state(fixture.way()).stream().map(node ->
                new GeographicPoint(node.coordinate().lat(), node.coordinate().lon())).toList());
        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
    }

    private static Fixture fixture() {
        DataSet dataSet = new DataSet();
        List<Node> nodes = List.of(node(401, -8.0), node(402, -4.0), node(403, 0.0),
                node(404, 4.0), node(405, 8.0));
        Way way = new Way();
        way.setNodes(nodes);
        way.setOsmId(410, 1);
        way.setModified(false);
        nodes.forEach(dataSet::addPrimitive);
        dataSet.addPrimitive(way);
        List<Node> selected = nodes.subList(1, 4);
        return new Fixture(dataSet, way, new SelectionContext(way, 1, 3, selected,
                Set.of(selected.get(0), selected.get(2))));
    }

    private static AlignmentConfig config() {
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig("", "", "", "", "all", "hot",
                "", ".*", AlignmentMode.PRECISE_SHAPE,
                TrackerMode.PROBABILISTIC, false, false, false, false, false, false,
                false, false, false, false, 7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION,
                15, 15, 7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
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
                OptionalDouble.of(1.0), "partial-workflow", "EPSG:3857");
    }

    private static Node node(long id, double eastMeters) {
        double longitude = Math.toDegrees(eastMeters / 6_378_137.0);
        Node node = new Node(new LatLon(0.0, longitude));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static PrimitiveKey key(Node node) {
        return PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId());
    }

    private static List<NodeState> state(Way way) {
        return way.getNodes().stream().map(node -> new NodeState(node,
                new LatLon(node.lat(), node.lon()), node.isModified(), node.isDeleted())).toList();
    }

    private record Fixture(DataSet dataSet, Way way, SelectionContext selection) { }

    private record NodeState(Node node, LatLon coordinate, boolean modified, boolean deleted) { }
}

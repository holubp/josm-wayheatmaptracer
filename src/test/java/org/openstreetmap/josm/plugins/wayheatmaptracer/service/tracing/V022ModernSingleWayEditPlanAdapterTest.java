package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ApplyAlignmentEditPlanCommand;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Detached Route-to-plan and real command boundary regressions for the supported single-way slice. */
class V022ModernSingleWayEditPlanAdapterTest {
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

    @ParameterizedTest
    @EnumSource(value = TrackerMode.class, names = {"PROBABILISTIC", "CORRIDOR_AWARE"})
    void T115_realProductionFinalRouteBuildsOneDeterministicDetachedPlanWithoutMutation(
            TrackerMode mode) throws Exception {
        Fixture fixture = fixture();
        List<String> beforeLive = state(fixture);
        LiveBPreviewService.Computed computed = compute(fixture, mode);
        ModernTracePipeline.Route route = computed.pipeline().routes().get(0);
        ModernSingleWayEditPlanAdapter adapter = new ModernSingleWayEditPlanAdapter();

        AlignmentEditPlan first = adapter.adapt(computed, 0);
        AlignmentEditPlan repeat = adapter.adapt(computed, 0);

        assertEquals(first.canonicalHash(), repeat.canonicalHash());
        assertEquals(computed.captured().network(), first.before());
        assertEquals(computed.captured().network().sourceGeneration(),
            first.after().sourceGeneration());
        assertEquals(route.hypothesis().points().stream()
                .map(computed.evidence().coordinateFrame()::toGeographic).toList(),
            first.finalPreviewWays().get(computed.request().selectedWayKey()));
        assertEquals(Set.of(computed.request().selectedWayKey()), first.affectedWayKeys());
        assertTrue(first.createdPrimitives().keySet().stream().allMatch(key ->
            key.type() == PrimitiveKey.Type.NODE
                && key.identityKind() == PrimitiveKey.IdentityKind.PLAN_LOCAL));
        assertTrue(first.createdPrimitives().values().stream().allMatch(value ->
            value.tags().isEmpty() && value.modified() && !value.deleted()));
        assertTrue(first.writePrimitiveKeys().stream().allMatch(key ->
            key.equals(computed.request().selectedWayKey())
                || key.identityKind() == PrimitiveKey.IdentityKind.PLAN_LOCAL));
        assertEquals(beforeLive, state(fixture));
    }

    @Test
    void T115b_selectedSubrangeRetainsExactPrefixSuffixInFinalPreview() throws Exception {
        Fixture fixture = fixtureWithPrefixAndSuffix();
        LiveBPreviewService.Computed computed = compute(fixture, TrackerMode.PROBABILISTIC);
        ModernTracePipeline.Route route = computed.pipeline().routes().get(0);

        AlignmentEditPlan plan = plan(computed);
        List<LatLon> preview = plan.finalPreviewWays().get(
            computed.request().selectedWayKey()).stream()
            .map(point -> new LatLon(point.latitudeDegrees(), point.longitudeDegrees())).toList();

        assertEquals(fixture.way().getNodesCount() + route.pointIds().size() - 2,
            preview.size());
        assertEquals(new LatLon(fixture.way().firstNode().lat(), fixture.way().firstNode().lon()),
            preview.get(0));
        assertEquals(new LatLon(fixture.way().lastNode().lat(), fixture.way().lastNode().lon()),
            preview.get(preview.size() - 1));
        assertEquals(route.hypothesis().points().stream()
                .map(computed.evidence().coordinateFrame()::toGeographic)
                .map(point -> new LatLon(point.latitudeDegrees(), point.longitudeDegrees())).toList(),
            preview.subList(1, preview.size() - 1));
    }

    @Test
    void T116_realProductionPlanAppliesAndReplaysExactSingleWayStateTwentyTimes()
            throws Exception {
        Fixture fixture = fixture();
        LiveBPreviewService.Computed computed = compute(fixture, TrackerMode.PROBABILISTIC);
        ModernTracePipeline.Route route = computed.pipeline().routes().get(0);
        AlignmentEditPlan plan = plan(computed);
        Node first = fixture.way().getNode(0);
        Node last = fixture.way().getNode(1);
        List<Node> original = List.of(first, last);
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
            fixture.dataSet(), plan, plan.before().datasetIdentity(),
            () -> plan.before().sourceGeneration(), "Apply modern single-way alignment");

        onEdt(() -> UndoRedoHandler.getInstance().add(command));
        List<Node> applied = List.copyOf(fixture.way().getNodes());
        List<LatLon> appliedCoordinates = applied.stream()
            .map(node -> new LatLon(node.lat(), node.lon())).toList();

        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
        assertSame(first, applied.get(0));
        assertSame(last, applied.get(applied.size() - 1));
        assertEquals(route.hypothesis().points().size(), applied.size());
        assertTrue(applied.subList(1, applied.size() - 1).stream().allMatch(node ->
            node.isNew() && node.isModified() && !node.isDeleted()
                && node.getKeys().isEmpty() && node.getDataSet() == fixture.dataSet()));
        assertTrue(original.stream().allMatch(node -> !node.isDeleted()));

        for (int cycle = 0; cycle < 20; cycle++) {
            onEdt(() -> UndoRedoHandler.getInstance().undo());
            assertEquals(original, fixture.way().getNodes());
            assertFalse(fixture.way().isModified());
            assertTrue(original.stream().allMatch(node ->
                !node.isModified() && !node.isDeleted() && node.getDataSet() == fixture.dataSet()));
            assertTrue(applied.subList(1, applied.size() - 1).stream()
                .allMatch(node -> node.getDataSet() == null));

            onEdt(() -> UndoRedoHandler.getInstance().redo());
            assertEquals(applied, fixture.way().getNodes());
            for (int index = 0; index < applied.size(); index++) {
                assertSame(applied.get(index), fixture.way().getNode(index));
                assertEquals(appliedCoordinates.get(index).lat(), fixture.way().getNode(index).lat());
                assertEquals(appliedCoordinates.get(index).lon(), fixture.way().getNode(index).lon());
            }
        }
        assertEquals(List.of(command), UndoRedoHandler.getInstance().getUndoCommands());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void T117_malformedOccurrenceAssignmentsAndBlockedQualityFailClosed() throws Exception {
        Fixture fixture = fixture();
        LiveBPreviewService.Computed computed = compute(fixture, TrackerMode.PROBABILISTIC);
        ModernTracePipeline.Route route = computed.pipeline().routes().get(0);
        ModernSingleWayEditPlanAdapter adapter = new ModernSingleWayEditPlanAdapter();
        ExistingWayNodeOccurrence first = (ExistingWayNodeOccurrence) route.pointIds().get(0);
        ExistingWayNodeOccurrence last = (ExistingWayNodeOccurrence)
            route.pointIds().get(route.pointIds().size() - 1);

        List<FinalRoutePointId> wrongIds = new ArrayList<>(route.pointIds());
        ExistingWayNodeOccurrence wrong = new ExistingWayNodeOccurrence(first.wayKey(),
            last.nodeKey(), first.originalOccurrenceIndex());
        wrongIds.set(0, wrong);
        Map<FinalRoutePointId, MetricPoint> wrongAssignments =
            replaceKey(route.assignments(), first, wrong);
        Map<FinalRoutePointId, ObservationOwnership> wrongOwnership =
            replaceKey(route.sourceOwnership(), first, wrong);
        ModernTracePipeline.Route wrongOccurrence = copy(
            route, wrongIds, wrongAssignments, wrongOwnership, route.quality());
        assertThrows(IllegalArgumentException.class,
            () -> adapter.adapt(withRoute(computed, wrongOccurrence), 0));

        Map<FinalRoutePointId, MetricPoint> missing = new LinkedHashMap<>(route.assignments());
        missing.remove(first);
        assertThrows(IllegalArgumentException.class, () -> copy(route, route.pointIds(),
            missing, route.sourceOwnership(), route.quality()));

        List<FinalRoutePointId> duplicate = new ArrayList<>(route.pointIds());
        duplicate.set(1, duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> copy(route, duplicate,
            route.assignments(), route.sourceOwnership(), route.quality()));

        FinalGeometryEvaluator.Finding finding = new FinalGeometryEvaluator.Finding(
            FinalGeometryEvaluator.FindingCode.PROTECTED_ASSIGNMENT_MISMATCH,
            FinalGeometryEvaluator.Severity.HARD_BLOCK, 0, 0, 1.0);
        FinalGeometryEvaluator.Result blocked = new FinalGeometryEvaluator.Result(
            route.quality().id(), FinalGeometryEvaluator.Disposition.HARD_BLOCKED,
            List.of(finding), route.quality().totalLengthMeters(),
            route.quality().directlySupportedLengthMeters(),
            route.quality().worstUnsupportedSpanMeters(),
            route.quality().meanImageCenterCost(), route.quality().bendPreservingRoughness());
        ModernTracePipeline.Route blockedRoute = copy(route, route.pointIds(),
            route.assignments(), route.sourceOwnership(), blocked);
        assertThrows(IllegalArgumentException.class,
            () -> adapter.adapt(withRoute(computed, blockedRoute), 0));
        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(computed, -1));
        assertThrows(IllegalArgumentException.class,
            () -> adapter.adapt(computed, computed.pipeline().routes().size()));
        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(
            withCapturedIdentities(computed, "stale-settings",
                computed.captured().parameterHash()), 0));
        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(
            withCapturedIdentities(computed, computed.captured().settingsHash(),
                "stale-parameters"), 0));
        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(
            withRequestIdentities(computed, "stale-evidence",
                computed.request().networkContentHash()), 0));
        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(
            withRequestIdentities(computed, computed.request().evidenceContentHash(),
                "stale-network"), 0));
        assertEquals(List.of(first.nodeKey(), last.nodeKey()), fixture.way().getNodes().stream()
            .map(node -> PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId())).toList());
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
    }

    private static AlignmentEditPlan plan(LiveBPreviewService.Computed computed) {
        return new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
    }

    private static LiveBPreviewService.Computed withRoute(LiveBPreviewService.Computed computed,
            ModernTracePipeline.Route route) {
        return new LiveBPreviewService.Computed(computed.captured(), computed.evidence(),
            computed.request(), new ModernTracePipeline.Result(
                computed.pipeline().inference(), List.of(route)));
    }

    private static LiveBPreviewService.Computed withCapturedIdentities(
            LiveBPreviewService.Computed computed, String settingsHash, String parameterHash) {
        LiveBPreviewService.Captured source = computed.captured();
        LiveBPreviewService.Captured captured = new LiveBPreviewService.Captured(
            source.raster(), source.managedRaster(), source.specification(), source.network(),
            source.sourceGeographic(), source.sourceMetric(), source.outputGrid(), source.palette(),
            source.searchRadiusMeters(), source.sampleStepMeters(), settingsHash, parameterHash,
            source.engine(), source.projectionCode());
        return new LiveBPreviewService.Computed(captured, computed.evidence(),
            computed.request(), computed.pipeline());
    }

    private static LiveBPreviewService.Computed withRequestIdentities(
            LiveBPreviewService.Computed computed, String evidenceHash, String networkHash) {
        var source = computed.request();
        var request = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest(
            source.selectedWayKey(), source.selectedRange(), source.engine(),
            source.geometryMode(), source.permissions(), source.budgets(),
            source.evidenceSnapshotId(), evidenceHash, source.networkSnapshotId(), networkHash,
            source.settingsHash(), source.parameterHash(), source.samplerId(),
            source.configuredSampleStepMeters(), source.profileChainage(),
            source.evidenceResolution(), source.corridorInput());
        return new LiveBPreviewService.Computed(computed.captured(), computed.evidence(),
            request, computed.pipeline());
    }

    private static ModernTracePipeline.Route copy(ModernTracePipeline.Route route,
            List<FinalRoutePointId> ids, Map<FinalRoutePointId, MetricPoint> assignments,
            Map<FinalRoutePointId, ObservationOwnership> ownership,
            FinalGeometryEvaluator.Result quality) {
        return new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(),
            ids, assignments, ownership, quality, route.cleanupStatus(), route.geometryChanged());
    }

    private static <V> Map<FinalRoutePointId, V> replaceKey(Map<FinalRoutePointId, V> source,
            FinalRoutePointId oldKey, FinalRoutePointId newKey) {
        Map<FinalRoutePointId, V> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key.equals(oldKey) ? newKey : key, value));
        return Map.copyOf(result);
    }

    private static LiveBPreviewService.Computed compute(Fixture fixture, TrackerMode mode)
            throws Exception {
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(
            fixture.dataSet(), fixture.selection(), raster(), config(mode)));
        LiveBPreviewService.Computed computed = service.compute(captured[0], CancellationProbe.NONE);
        assertFalse(computed.pipeline().routes().isEmpty(),
            "supported production fixture must produce a final route");
        return computed;
    }

    private static Fixture fixture() {
        DataSet dataSet = new DataSet();
        Node first = loadedNode(1, 0.0, longitude(-8));
        Node last = loadedNode(2, 0.0, longitude(8));
        Way way = new Way();
        way.setNodes(List.of(first, last));
        way.setOsmId(10, 1);
        way.setModified(false);
        dataSet.addPrimitive(first);
        dataSet.addPrimitive(last);
        dataSet.addPrimitive(way);
        return new Fixture(dataSet, way,
            new SelectionContext(way, 0, 1, List.of(first, last), Set.of(first, last)));
    }

    private static Fixture fixtureWithPrefixAndSuffix() {
        DataSet dataSet = new DataSet();
        Node prefix = loadedNode(3, 0.0, longitude(-12));
        Node first = loadedNode(1, 0.0, longitude(-8));
        Node last = loadedNode(2, 0.0, longitude(8));
        Node suffix = loadedNode(4, 0.0, longitude(12));
        Way way = new Way();
        way.setNodes(List.of(prefix, first, last, suffix));
        way.setOsmId(10, 1);
        way.setModified(false);
        dataSet.addPrimitive(prefix);
        dataSet.addPrimitive(first);
        dataSet.addPrimitive(last);
        dataSet.addPrimitive(suffix);
        dataSet.addPrimitive(way);
        return new Fixture(dataSet, way,
            new SelectionContext(way, 1, 2, List.of(first, last), Set.of(first, last)));
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

    private static AlignmentConfig config(TrackerMode mode) {
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig("", "", "", "", "all", "hot", "", ".*",
            AlignmentMode.PRECISE_SHAPE, mode, false, false,
            false, false, false, false, false, false, false, false,
            7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15,
            7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    private static Node loadedNode(long id, double latitude, double longitude) {
        Node node = new Node(new LatLon(latitude, longitude));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static double longitude(double meters) {
        return Math.toDegrees(meters / 6_378_137.0);
    }

    private static List<String> state(Fixture fixture) {
        return fixture.dataSet().allPrimitives().stream().map(primitive ->
            primitive.getType() + ":" + primitive.getUniqueId() + ":" + primitive.isModified()
                + ":" + primitive.isDeleted() + ":" + primitive.getKeys()
                + (primitive instanceof Node node ? ":" + node.lat() + ":" + node.lon()
                    : primitive instanceof Way way ? ":" + way.getNodeIds() : ""))
            .sorted().toList();
    }

    private static void onEdt(Runnable operation) throws Exception {
        SwingUtilities.invokeAndWait(operation);
    }

    private record Fixture(DataSet dataSet, Way way, SelectionContext selection) {
    }
}

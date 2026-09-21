package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** End-to-end production-source assertions required by the v0.22 release plan. */
class V022EndToEndTest {
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void T166_managedAndVisibleSourcesKeepDistinctDetachedEvidenceLineage() throws Exception {
        Fixture fixture = fixture();
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] visible = new LiveBPreviewService.Captured[1];
        LiveBPreviewService.ManagedCaptureSeed[] managedSeed = new LiveBPreviewService.ManagedCaptureSeed[1];
        SwingUtilities.invokeAndWait(() -> {
            visible[0] = service.capture(fixture.dataSet(), fixture.selection(), visibleRaster(), visibleConfig());
            managedSeed[0] = service.captureManagedSeed(fixture.dataSet(), fixture.selection(), managedConfig(),
                    "managed-test");
        });

        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        LiveBPreviewService.Captured managed = service.attachManagedRaster(managedSeed[0],
                new ManagedModernPreviewSource.Raster(image, new boolean[] {true, true, true, true},
                        SupportedInputRasterTransform.webMercator(15, 0.0, 0.0, 2.0), "hot", 15,
                        "managed-test"));

        assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,
                service.captureEvidence(visible[0], CancellationProbe.NONE).fields()
                        .get("selected-visible-source").lineage().acquisitionKind());
        assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE,
                service.captureEvidence(managed, CancellationProbe.NONE).fields().get("selected-visible-source").lineage()
                        .acquisitionKind());
        assertFalse(visible[0].managedRaster() != null);
        assertFalse(managed.raster() != null);
    }

    @Test
    void T167_previewCarriesRequestedCleanupModeIntoDetachedPipelineInput() throws Exception {
        Fixture fixture = fixture();
        GeometryCleanupConfig cleanup = GeometryCleanupPreset.BALANCED
                .apply(GeometryCleanupMode.REDUCE_POINTS_ONLY);
        AlignmentConfig config = new AlignmentConfig(visibleConfig().heatmap(), cleanup);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];

        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), visibleRaster(), config));

        assertEquals(cleanup, captured[0].cleanup());
        GeometryCleanupConfig smooth = GeometryCleanupPreset.BALANCED
                .apply(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE);
        LiveBPreviewService.Captured[] smoothed = new LiveBPreviewService.Captured[1];
        LiveBPreviewService.ManagedCaptureSeed[] managedSeed = new LiveBPreviewService.ManagedCaptureSeed[1];
        SwingUtilities.invokeAndWait(() -> {
            smoothed[0] = new LiveBPreviewService().capture(fixture.dataSet(), fixture.selection(),
                    visibleRaster(), new AlignmentConfig(visibleConfig().heatmap(), smooth));
            managedSeed[0] = new LiveBPreviewService().captureManagedSeed(fixture.dataSet(), fixture.selection(),
                    new AlignmentConfig(managedConfig().heatmap(), cleanup), "managed-cleanup");
        });
        LiveBPreviewService.Captured managed = new LiveBPreviewService().attachManagedRaster(managedSeed[0],
                new ManagedModernPreviewSource.Raster(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB),
                        new boolean[] {true, true, true, true},
                        SupportedInputRasterTransform.webMercator(15, 0.0, 0.0, 2.0), "hot", 15,
                        "managed-cleanup"));
        assertEquals(cleanup, managedSeed[0].cleanup());
        assertEquals(cleanup, managed.cleanup());
        LiveBPreviewService.Captured[] off = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> off[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), visibleRaster(), visibleConfig()));
        var offResult = new LiveBPreviewService().compute(off[0], CancellationProbe.NONE);
        var reducedResult = new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);
        var smoothResult = new LiveBPreviewService().compute(smoothed[0], CancellationProbe.NONE);
        assertFalse(offResult.pipeline().routes().isEmpty());
        assertFalse(reducedResult.pipeline().routes().isEmpty());
        assertFalse(smoothResult.pipeline().routes().isEmpty());
        assertEquals(ImageSupportedLocalCleanup.Status.SKIPPED,
                offResult.pipeline().routes().get(0).cleanupStatus());
        assertFalse(ImageSupportedLocalCleanup.Status.SKIPPED ==
                reducedResult.pipeline().routes().get(0).cleanupStatus());
        assertFalse(ImageSupportedLocalCleanup.Status.SKIPPED ==
                smoothResult.pipeline().routes().get(0).cleanupStatus());

        var cleanedRoute = reducedResult.pipeline().routes().get(0);
        assertTrue(cleanedRoute.geometryChanged(),
                "T167 requires a live fixture whose final geometry was changed by cleanup");
        assertNotEquals(cleanedRoute.rawHypothesis().points(), cleanedRoute.hypothesis().points());
        var expectedGeographic = cleanedRoute.hypothesis().points().stream()
                .map(reducedResult.evidence().coordinateFrame()::toGeographic).toList();
        var expectedProjected = expectedGeographic.stream()
                .map(point -> ProjectionRegistry.getProjection().latlon2eastNorth(
                        new LatLon(point.latitudeDegrees(), point.longitudeDegrees())))
                .toList();
        var displayed = new LiveBPreviewService().adapt(reducedResult,
                point -> ProjectionRegistry.getProjection().latlon2eastNorth(
                        new LatLon(point.latitudeDegrees(), point.longitudeDegrees())))
                .get(0);
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(reducedResult, 0);

        assertEquals(expectedProjected, displayed.finalPreviewPoints());
        assertEquals(expectedGeographic,
                plan.finalPreviewWays().get(reducedResult.request().selectedWayKey()));
    }

    @Test
    void T168_fixedMoveAndReattachProduceDistinctExactJunctionPlans() throws Exception {
        JunctionFixture fixture = junctionFixture(0.0, false);
        Map<JunctionPolicy, AlignmentEditPlan> plans = new java.util.EnumMap<>(JunctionPolicy.class);
        Map<JunctionPolicy, LiveBPreviewService.Computed> results =
                new java.util.EnumMap<>(JunctionPolicy.class);
        for (JunctionPolicy policy : JunctionPolicy.values()) {
            RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                    policy, false);
            LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
            SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                    fixture.dataSet(), fixture.selection(), junctionRaster(), visibleConfig(),
                    false, permissions));
            LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                    captured[0], CancellationProbe.NONE);
            assertFalse(computed.pipeline().routes().isEmpty());
            assertEquals(policy, computed.request().permissions().junctionPolicy());
            results.put(policy, computed);
            try {
                plans.put(policy, new ModernSingleWayEditPlanAdapter().adapt(computed, 0));
            } catch (IllegalArgumentException failure) {
                throw new AssertionError(policy + " plan failed: " + failure.getMessage(), failure);
            }
        }
        assertEquals(3, results.values().stream()
                .map(result -> result.captured().settingsHash()).distinct().count());

        PrimitiveKey junction = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                fixture.junction().getUniqueId());
        PrimitiveKey west = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                fixture.west().getUniqueId());
        PrimitiveKey receiver = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                fixture.receiver().getUniqueId());
        PrimitiveKey south = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                fixture.south().getUniqueId());
        PrimitiveKey middle = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                fixture.middle().getUniqueId());
        PrimitiveKey north = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                fixture.north().getUniqueId());
        var original = ((DetachedNode) plans.get(JunctionPolicy.FIXED).before()
                .primitives().get(junction)).coordinate();
        var originalWest = ((DetachedNode) plans.get(JunctionPolicy.FIXED).before()
                .primitives().get(west)).coordinate();
        assertEquals(original, ((DetachedNode) plans.get(JunctionPolicy.FIXED).after()
                .primitives().get(junction)).coordinate());
        assertTrue(results.get(JunctionPolicy.LEGACY_BOUNDED_MOVE).captured().network()
                .closure().movableExistingNodeKeys().contains(west));
        assertFalse(results.get(JunctionPolicy.REATTACH).captured().network()
                .closure().movableExistingNodeKeys().contains(west));
        assertEquals(originalWest, ((DetachedNode) plans.get(JunctionPolicy.REATTACH).after()
                .primitives().get(west)).coordinate());
        assertEquals(List.of(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange(1, 6)),
                results.get(JunctionPolicy.REATTACH).captured().network().closure()
                        .editableWayOccurrences().get(receiver));

        for (JunctionPolicy policy : List.of(JunctionPolicy.LEGACY_BOUNDED_MOVE,
                JunctionPolicy.REATTACH)) {
            var route = results.get(policy).pipeline().routes().get(0);
            var expectedSelected = route.hypothesis().points().stream()
                    .map(results.get(policy).evidence().coordinateFrame()::toGeographic).toList();
            assertEquals(expectedSelected, plans.get(policy).finalPreviewWays()
                    .get(results.get(policy).request().selectedWayKey()));
            var endpointId = route.pointIds().stream()
                    .filter(id -> id instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence occurrence
                            && occurrence.nodeKey().equals(junction))
                    .findFirst().orElseThrow();
            var expected = results.get(policy).evidence().coordinateFrame()
                    .toGeographic(route.assignments().get(endpointId));
            assertNotEquals(original, expected);
            assertEquals(expected, ((DetachedNode) plans.get(policy).after()
                    .primitives().get(junction)).coordinate());
            assertTrue(plans.get(policy).finalPreviewWays().containsKey(receiver));
            assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED,
                    plans.get(policy).validation().disposition());
            assertTrue(plans.get(policy).validation().findingCodes()
                    .contains("network-review-required"));
        }

        List<PrimitiveKey> receiverBefore = fixture.receiver().getNodes().stream()
                .map(node -> PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId())).toList();
        assertEquals(receiverBefore,
                ((DetachedWay) plans.get(JunctionPolicy.LEGACY_BOUNDED_MOVE).after()
                        .primitives().get(receiver)).nodeKeys());
        assertEquals(List.of(receiverBefore.get(0), receiverBefore.get(1), south, middle,
                        junction, north, receiverBefore.get(6), receiverBefore.get(7)),
                ((DetachedWay) plans.get(JunctionPolicy.REATTACH).after()
                        .primitives().get(receiver)).nodeKeys());
        assertEquals(receiverBefore, fixture.receiver().getNodes().stream()
                .map(node -> PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId())).toList());
    }

    @Test
    void T168_offLocusFrozenReceiverCannotDivergeFromReviewedRoute() throws Exception {
        LiveBPreviewService.Computed computed = compute(junctionFixture(0.10, false),
                JunctionPolicy.REATTACH);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(computed, 0));
        assertTrue(failure.getMessage().contains("differ from the reviewed route"),
                failure::getMessage);
    }

    @Test
    void T168_newSelectedRouteCrossingIsRejectedAgainstTrueBeforeTopology() throws Exception {
        LiveBPreviewService.Computed computed = compute(junctionFixture(0.0, true),
                JunctionPolicy.REATTACH);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(computed, 0));
        assertTrue(failure.getMessage().contains("UNCONNECTED_AT_GRADE_CROSSING"));
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

    private static LiveBPreviewService.Computed compute(JunctionFixture fixture,
            JunctionPolicy policy) throws Exception {
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                policy, false);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionRaster(), visibleConfig(),
                false, permissions));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        assertFalse(computed.pipeline().routes().isEmpty());
        return computed;
    }

    private static JunctionFixture junctionFixture(double receiverEastOffset, boolean crossing) {
        DataSet dataSet = new DataSet();
        Node west = loadedNode(1, 0.0, longitude(-8));
        Node junction = loadedNode(2, 0.0, longitude(8));
        double southEast = 8.0 - 8.0 * receiverEastOffset;
        double northEast = 8.0 + receiverEastOffset;
        Node farSouth = loadedNode(3, latitude(-49), longitude(southEast));
        Node southPort = loadedNode(4, latitude(-31), longitude(southEast));
        Node south = loadedNode(5, latitude(-8), longitude(southEast));
        Node middle = loadedNode(6, latitude(1), longitude(northEast));
        Node north = loadedNode(7, latitude(8), longitude(northEast));
        Node northPort = loadedNode(8, latitude(31), longitude(northEast));
        Node farNorth = loadedNode(9, latitude(49), longitude(northEast));
        Way selected = loadedWay(10, west, junction);
        Way receiver = loadedWay(11, farSouth, southPort, south, junction, middle, north,
                northPort, farNorth);
        for (Node node : List.of(west, junction, farSouth, southPort, south, middle, north,
                northPort, farNorth)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        if (crossing) {
            Node crossingSouth = loadedNode(20, latitude(1), longitude(0));
            Node crossingNorth = loadedNode(21, latitude(3), longitude(0));
            dataSet.addPrimitive(crossingSouth);
            dataSet.addPrimitive(crossingNorth);
            dataSet.addPrimitive(loadedWay(12, crossingSouth, crossingNorth));
        }
        return new JunctionFixture(dataSet,
                new SelectionContext(selected, 0, 1, List.of(west, junction), Set.of()),
                receiver, west, junction, south, middle, north);
    }

    private static LiveBPreviewService.VisibleRaster visibleRaster() {
        return visibleRaster(600, 50.0, 288.0);
    }

    private static LiveBPreviewService.VisibleRaster junctionRaster() {
        return visibleRaster(720, 60.0, 348.0);
    }

    private static LiveBPreviewService.VisibleRaster visibleRaster(int size, double extent,
            double ridgeRow) {
        int width = size;
        int height = size;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            double distance = (y - ridgeRow) / RenderedHeatmapSampler.RASTER_SCALE;
            int gray = (int) Math.round(255.0 * (0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44)));
            int pixel = 0xff000000 | gray << 16 | gray << 8 | gray;
            java.util.Arrays.fill(argb, y * width, (y + 1) * width, pixel);
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb, -extent, -extent,
                extent, extent, 1.0, 1.0, OptionalDouble.of(1.0), "visible-test", "EPSG:3857");
    }

    private static AlignmentConfig visibleConfig() {
        return config("", TrackerMode.PROBABILISTIC);
    }

    private static AlignmentConfig managedConfig() {
        return config("key", TrackerMode.CORRIDOR_AWARE);
    }

    private static AlignmentConfig config(String accessKey, TrackerMode trackerMode) {
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig(accessKey, "policy", "signature", "session",
                "all", "hot", "", ".*", AlignmentMode.PRECISE_SHAPE, trackerMode, false, false,
                false, false, false, false, false, false, false, false, 7, 4, 3.0,
                InferenceMode.RAW_HIGH_RESOLUTION, 15, 15, 7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    private static double longitude(double meters) {
        return Math.toDegrees(meters / 6_378_137.0);
    }

    private static double latitude(double meters) {
        return Math.toDegrees(meters / 6_378_137.0);
    }

    private static Node loadedNode(long id, double lat, double lon) {
        Node node = new Node(new LatLon(lat, lon));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static Way loadedWay(long id, Node... nodes) {
        Way way = new Way();
        way.setNodes(List.of(nodes));
        way.setOsmId(id, 1);
        way.setModified(false);
        return way;
    }

    private record Fixture(DataSet dataSet, SelectionContext selection) {
    }

    private record JunctionFixture(DataSet dataSet, SelectionContext selection, Way receiver,
            Node west, Node junction, Node south, Node middle, Node north) {
    }
}

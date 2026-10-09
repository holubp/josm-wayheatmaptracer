package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ProductionBundleFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.ManualJunctionEligibility;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ApplyAlignmentEditPlanCommand;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ManagedSourceLockedApplyValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.VisibleSourceLockedApplyValidator;
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
                        "managed-test",
                        new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(0L)));

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
    void T167_previewCarriesRequestedCleanupWhileDirectBSuppressesIncompatibleRefit() throws Exception {
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
                        "managed-cleanup",
                        new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(0L)));
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
        assertEquals(ImageSupportedLocalCleanup.Status.SKIPPED,
                reducedResult.pipeline().routes().get(0).cleanupStatus());
        assertEquals(ImageSupportedLocalCleanup.Status.SKIPPED,
                smoothResult.pipeline().routes().get(0).cleanupStatus());

        var cleanedRoute = reducedResult.pipeline().routes().get(0);
        assertFalse(cleanedRoute.geometryChanged());
        assertEquals(cleanedRoute.rawHypothesis().points(), cleanedRoute.hypothesis().points());
        assertEquals(1.0, cleanedRoute.hypothesis().diagnostics()
                .get("cleanupSuppressedForDirectReliability"));
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
    void eligibleReattachmentKeepsUnrelatedFarContextReadOnly() throws Exception {
        JunctionFixture fixture = junctionFixture(0.0, false);
        Node near = loadedNode(20, latitude(5), longitude(0));
        Node far = loadedNode(21, latitude(1_000), longitude(0));
        Way context = loadedWay(22, near, far);
        fixture.dataSet().addPrimitive(near);
        fixture.dataSet().addPrimitive(far);
        fixture.dataSet().addPrimitive(context);
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, false);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionRaster(), visibleConfig(),
                false, permissions));
        var computed = new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);
        PrimitiveKey contextKey = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                context.getUniqueId());
        assertTrue(captured[0].network().primitives().containsKey(contextKey));
        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                captured[0].junctionDecision().reason());

        var assessment = new ModernSingleWayEditPlanAdapter().assess(computed, 0);
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE,
                assessment.availability(), assessment.detail());
        var plan = assessment.plan().orElseThrow();
        for (Node node : List.of(near, far)) {
            PrimitiveKey key = PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId());
            assertEquals(plan.before().primitives().get(key), plan.after().primitives().get(key));
        }
        assertEquals(plan.before().primitives().get(contextKey),
                plan.after().primitives().get(contextKey));

        near.setCoor(new LatLon(latitude(-1_000), longitude(0)));
        LiveBPreviewService.Captured[] crossingCapture = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> crossingCapture[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionRaster(), visibleConfig(),
                false, permissions));
        var crossing = new LiveBPreviewService().compute(crossingCapture[0],
                CancellationProbe.NONE);
        var refused = new ModernSingleWayEditPlanAdapter().assess(crossing, 0);
        assertTrue(refused.availability()
                == ModernSingleWayEditPlanAdapter.ApplyAvailability.FINAL_TOPOLOGY_CROSSING
                || refused.availability()
                    == ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION
                    && refused.manualReason()
                        == ManualJunctionEligibility.Reason.AMBIGUOUS_CROSSING,
                refused.detail());
        assertFalse(refused.applyAvailable());
    }

    @Test
    void explicitReattachmentWithOutOfFrameContextUsesManualPath() throws Exception {
        JunctionFixture fixture = junctionFixture(0.0, false);
        fixture.receiver().getNode(0).setCoor(new LatLon(latitude(-1_000), longitude(8)));
        fixture.receiver().getNode(fixture.receiver().getNodesCount() - 1)
                .setCoor(new LatLon(latitude(1_000), longitude(8)));
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, false);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionRaster(), visibleConfig(),
                false, permissions));
        var computed = new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);

        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                captured[0].junctionDecision().reason());
        var assessment = new ModernSingleWayEditPlanAdapter().assess(computed, 0);
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION,
                assessment.availability(), assessment.detail());
        assertFalse(assessment.applyAvailable());
        assertTrue(assessment.plan().isEmpty());
    }

    @Test
    void reconstructingOutOfFrameComponentUsesManualPathBeforeProjection() throws Exception {
        JunctionFixture fixture = junctionFixture(0.0, false);
        fixture.receiver().getNode(0).setCoor(new LatLon(latitude(-1_000), longitude(8)));
        fixture.receiver().getNode(fixture.receiver().getNodesCount() - 1)
                .setCoor(new LatLon(latitude(1_000), longitude(8)));
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionRaster(), visibleConfig(),
                false, permissions));
        var computed = new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);

        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                captured[0].junctionDecision().reason());
        var assessment = new ModernSingleWayEditPlanAdapter().assess(computed, 0);
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION,
                assessment.availability(), assessment.detail());
        assertTrue(assessment.plan().isEmpty());
        assertFalse(assessment.applyAvailable());
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
            if (policy == JunctionPolicy.LEGACY_BOUNDED_MOVE) {
                assertManualJunctionWithoutMutation(fixture.dataSet(), computed,
                        ManualJunctionEligibility.Reason.LEGACY_POLICY);
                continue;
            }
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
        assertFalse(results.get(JunctionPolicy.LEGACY_BOUNDED_MOVE).captured().network()
                .closure().movableExistingNodeKeys().contains(west));
        assertFalse(results.get(JunctionPolicy.REATTACH).captured().network()
                .closure().movableExistingNodeKeys().contains(west));
        assertEquals(originalWest, ((DetachedNode) plans.get(JunctionPolicy.REATTACH).after()
                .primitives().get(west)).coordinate());
        assertEquals(List.of(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange(1, 6)),
                results.get(JunctionPolicy.REATTACH).captured().network().closure()
                        .editableWayOccurrences().get(receiver));

        for (JunctionPolicy policy : List.of(JunctionPolicy.REATTACH)) {
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
                    plans.get(policy).validation().disposition(),
                    () -> policy + ": " + plans.get(policy).validation().findingCodes());
            assertTrue(plans.get(policy).validation().findingCodes()
                    .contains("network-review-required"));
        }

        List<PrimitiveKey> receiverBefore = fixture.receiver().getNodes().stream()
                .map(node -> PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId())).toList();
        assertEquals(List.of(receiverBefore.get(0), receiverBefore.get(1), south, middle,
                        junction, north, receiverBefore.get(6), receiverBefore.get(7)),
                ((DetachedWay) plans.get(JunctionPolicy.REATTACH).after()
                        .primitives().get(receiver)).nodeKeys());
        ModernSingleWayEditPlanAdapter.Assessment reattachAssessment =
                new ModernSingleWayEditPlanAdapter().assess(
                        results.get(JunctionPolicy.REATTACH), 0);
        AlignmentEditPlan exactReattachPlan = reattachAssessment.plan().orElseThrow();
        Map<PrimitiveKey, List<EastNorth>> projected =
                reattachAssessment.projectFinalPreviewWays(point ->
                        new EastNorth(point.longitudeDegrees(), point.latitudeDegrees()));
        assertEquals(Set.of(results.get(JunctionPolicy.REATTACH).request().selectedWayKey(), receiver),
                exactReattachPlan.finalPreviewWays().keySet());
        assertEquals(exactReattachPlan.finalPreviewWays().keySet(), projected.keySet());
        exactReattachPlan.finalPreviewWays().forEach((way, points) -> assertEquals(
                points.stream().map(point -> new EastNorth(point.longitudeDegrees(),
                        point.latitudeDegrees())).toList(), projected.get(way)));
        assertEquals(exactReattachPlan.canonicalHash(),
                PreviewReviewState.fromEditPlan("reattach", exactReattachPlan)
                        .exactEditPlanHash());
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
        JunctionFixture fixture = junctionFixture(0.0, true);
        LiveBPreviewService.Computed computed = compute(fixture, JunctionPolicy.REATTACH);
        assertManualJunctionWithoutMutation(fixture.dataSet(), computed,
                ManualJunctionEligibility.Reason.AMBIGUOUS_CROSSING);
    }

    @Test
    void fixedPolicyFinalPreviewIsBlockedByCapturedSurroundingCrossing() throws Exception {
        LiveBPreviewService.Computed computed = compute(junctionFixture(0.0, true),
                JunctionPolicy.FIXED);

        ModernSingleWayEditPlanAdapter.Assessment assessment =
                new ModernSingleWayEditPlanAdapter().assess(computed, 0);

        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.FINAL_TOPOLOGY_CROSSING,
                assessment.availability(), () -> assessment.plan()
                        .map(plan -> plan.validation().findingCodes().toString())
                        .orElse(assessment.detail()));
        assertTrue(assessment.plan().isPresent(),
                "blocked final geometry must remain inspectable as an exact immutable plan");
        assertEquals(ValidationReport.Disposition.HARD_BLOCKED,
                assessment.plan().orElseThrow().validation().disposition());
        assertTrue(assessment.plan().orElseThrow().validation().findingCodes()
                .contains("final-topology:CROSSING"));
    }

    @Test
    void partialSelectionKeepsBoundariesAndOutsideContinuationExactWhileInteriorApplies()
            throws Exception {
        DataSet dataSet = new DataSet();
        Node prefix = loadedNode(401, 0.0, longitude(-8));
        Node westBoundary = loadedNode(402, 0.0, longitude(-4));
        Node interior = loadedNode(403, 0.0, longitude(0));
        Node eastBoundary = loadedNode(404, 0.0, longitude(4));
        Node continuation = loadedNode(405, 0.0, longitude(8));
        Way selected = loadedWay(410, prefix, westBoundary, interior, eastBoundary,
                continuation);
        for (Node node : List.of(prefix, westBoundary, interior, eastBoundary, continuation)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        SelectionContext selection = new SelectionContext(selected, 1, 3,
                List.of(westBoundary, interior, eastBoundary), Set.of());
        LatLon originalPrefix = prefix.getCoor();
        LatLon originalWestBoundary = westBoundary.getCoor();
        LatLon originalInterior = interior.getCoor();
        LatLon originalEastBoundary = eastBoundary.getCoor();
        LatLon originalContinuation = continuation.getCoor();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, visibleRaster(), visibleConfig(), false,
                new RecoveryPermissions(false, 7.01, 7.01,
                        JunctionPolicy.LEGACY_BOUNDED_MOVE, false)));
        PrimitiveKey prefixKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                prefix.getUniqueId());
        PrimitiveKey westBoundaryKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                westBoundary.getUniqueId());
        PrimitiveKey interiorKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                interior.getUniqueId());
        PrimitiveKey eastBoundaryKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                eastBoundary.getUniqueId());
        PrimitiveKey continuationKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                continuation.getUniqueId());
        var closure = captured[0].network().closure();
        assertTrue(closure.protectedExistingNodeKeys().containsAll(
                Set.of(westBoundaryKey, eastBoundaryKey)));
        assertFalse(closure.movableExistingNodeKeys().contains(westBoundaryKey));
        assertFalse(closure.movableExistingNodeKeys().contains(eastBoundaryKey));
        assertTrue(closure.movableExistingNodeKeys().contains(interiorKey));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        ModernSingleWayEditPlanAdapter.Assessment assessment =
                new ModernSingleWayEditPlanAdapter().assess(computed, 0);
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE,
                assessment.availability(), assessment.detail());
        AlignmentEditPlan plan = assessment.plan().orElseThrow();
        for (PrimitiveKey fixed : List.of(prefixKey, westBoundaryKey, eastBoundaryKey,
                continuationKey)) {
            assertEquals(plan.before().primitives().get(fixed), plan.after().primitives().get(fixed));
        }
        assertNotEquals(((DetachedNode) plan.before().primitives().get(interiorKey)).coordinate(),
                ((DetachedNode) plan.after().primitives().get(interiorKey)).coordinate());
        List<GeographicPoint> preview = plan.finalPreviewWays().get(plan.selectedWayKey());
        assertEquals(new GeographicPoint(originalPrefix.lat(), originalPrefix.lon()),
                preview.get(0));
        assertEquals(new GeographicPoint(originalContinuation.lat(), originalContinuation.lon()),
                preview.get(preview.size() - 1));
        assertTrue(preview.contains(new GeographicPoint(originalWestBoundary.lat(),
                originalWestBoundary.lon())));
        assertTrue(preview.contains(new GeographicPoint(originalEastBoundary.lat(),
                originalEastBoundary.lon())));

        UndoRedoHandler.getInstance().clean();
        try {
            assertTrue(PreviewReviewState.fromEditPlan("partial-boundary", plan)
                    .confirm().confirmed());
            ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(dataSet,
                    plan, plan.before().datasetIdentity(),
                    () -> plan.before().sourceGeneration(), "Apply protected partial selection");
            SwingUtilities.invokeAndWait(() -> UndoRedoHandler.getInstance().add(command));
            assertEquals(originalPrefix, prefix.getCoor());
            assertEquals(originalWestBoundary, westBoundary.getCoor());
            assertNotEquals(originalInterior, interior.getCoor());
            assertEquals(originalEastBoundary, eastBoundary.getCoor());
            assertEquals(originalContinuation, continuation.getCoor());
        } finally {
            SwingUtilities.invokeAndWait(() -> UndoRedoHandler.getInstance().clean());
        }
    }

    @Test
    void movedIncidentWayFoldbackIsHardBlockedBeforeReviewConfirmation() throws Exception {
        JunctionFixture fixture = incidentFoldbackFixture();
        LiveBPreviewService.Computed computed = compute(fixture, JunctionPolicy.LEGACY_BOUNDED_MOVE);
        assertManualJunctionWithoutMutation(fixture.dataSet(), computed,
                ManualJunctionEligibility.Reason.LEGACY_POLICY);
        PrimitiveKey junction = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                fixture.junction().getUniqueId());
        NetworkSnapshot before = computed.captured().network();
        Map<PrimitiveKey, DetachedPrimitive> after = new LinkedHashMap<>(before.primitives());
        DetachedNode old = (DetachedNode) after.get(junction);
        after.put(junction, new DetachedNode(junction,
                new GeographicPoint(latitude(6), longitude(8)), old.tags(), false, true));
        assertTrue(finalTopologyFindings(computed, after).contains(
                "final-topology:CONTINUATION"),
                "a moved incident receiver must still reject its short foldback");
    }

    @Test
    void T169_incidentReconstructionUsesEachReceiverArmEvidence() throws Exception {
        JunctionFixture fixture = reconstructionFixture();
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionReconstructionRaster(true),
                visibleConfig(), false, permissions));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);

        PrimitiveKey receiver = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                fixture.receiver().getUniqueId());
        PrimitiveKey junction = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                fixture.junction().getUniqueId());
        List<PrimitiveKey> reconstructedNodes = List.of(fixture.south(), fixture.middle(), fixture.north())
                .stream().map(node -> PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                        node.getUniqueId())).toList();
        var hiddenReceiver = assertThrows(IllegalStateException.class, () ->
                org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction
                        .benchmarkSelectedPlanGeometry(plan, computed, 0));
        assertEquals("Benchmark selected receipt would hide another affected way", hiddenReceiver.getMessage());
        assertTrue(computed.captured().network().closure().movableExistingNodeKeys()
                .containsAll(reconstructedNodes));
        var beforeJunction = computed.evidence().coordinateFrame().toMetric(
                ((DetachedNode) plan.before().primitives().get(junction)).coordinate());
        var afterJunction = computed.evidence().coordinateFrame().toMetric(
                ((DetachedNode) plan.after().primitives().get(junction)).coordinate());
        for (PrimitiveKey node : reconstructedNodes) {
            var before = computed.evidence().coordinateFrame().toMetric(
                    ((DetachedNode) plan.before().primitives().get(node)).coordinate());
            var after = computed.evidence().coordinateFrame().toMetric(
                    ((DetachedNode) plan.after().primitives().get(node)).coordinate());
            assertNotEquals(before, after, "Each incident arm must be fit from its own evidence");
            assertTrue(Math.abs(after.xMeters() - afterJunction.xMeters())
                    < Math.abs(before.xMeters() - beforeJunction.xMeters()));
        }
        DetachedWay afterReceiver = (DetachedWay) plan.after().primitives().get(receiver);
        assertEquals(afterReceiver.nodeKeys().stream()
                        .map(key -> ((DetachedNode) plan.after().primitives().get(key)).coordinate())
                        .toList(),
                plan.finalPreviewWays().get(receiver));
        var afterSouth = computed.evidence().coordinateFrame().toMetric(
                ((DetachedNode) plan.after().primitives().get(reconstructedNodes.get(0))).coordinate());
        assertEquals(afterSouth.xMeters(), afterJunction.xMeters(), 0.5,
                "the joint junction must meet the measured receiver core");
        DetachedWay afterSelected = (DetachedWay) plan.after().primitives().get(
                computed.request().selectedWayKey());
        assertEquals(afterSelected.nodeKeys().stream()
                .map(key -> ((DetachedNode) plan.after().primitives().get(key)).coordinate())
                .toList(), plan.finalPreviewWays().get(computed.request().selectedWayKey()));
        for (var port : computed.captured().network().closure().externalPorts().stream()
                .filter(value -> value.wayKey().equals(receiver)).toList()) {
            assertEquals(plan.before().primitives().get(port.boundaryNodeKey()),
                    plan.after().primitives().get(port.boundaryNodeKey()));
        }
        assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED,
                plan.validation().disposition());
        var confirmed = Format15ProductionBundleFactory.createLive("test",
                new FrozenReplayInput(computed.request(), computed.evidence(),
                        computed.captured().network(), computed.options()), computed.pipeline(),
                "confirmed", "visible-layer", 0, plan, true, false);
        assertEquals(new String(confirmed.artifact("planned-geometry.json").bytes(),
                        StandardCharsets.UTF_8),
                new String(confirmed.artifact("reviewed-route.json").bytes(), StandardCharsets.UTF_8));
        assertTrue(new String(confirmed.artifact("reviewed-route.json").bytes(),
                StandardCharsets.UTF_8).contains(receiver.toString()));
        String reviewedIdentity = new String(confirmed.artifact("reviewed-route-identity.json").bytes(),
                StandardCharsets.UTF_8);
        assertTrue(reviewedIdentity.contains(plan.canonicalHash()));
        assertTrue(reviewedIdentity.contains(confirmed.artifact("reviewed-route.json").sha256()));

        JunctionFixture missingEvidence = reconstructionFixture();
        LiveBPreviewService.Captured[] missingCaptured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> missingCaptured[0] = new LiveBPreviewService().capture(
                missingEvidence.dataSet(), missingEvidence.selection(),
                junctionReconstructionRaster(false), visibleConfig(), false, permissions));
        LiveBPreviewService.Computed missingComputed = new LiveBPreviewService().compute(
                missingCaptured[0], CancellationProbe.NONE);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(missingComputed, 0));
        assertTrue(failure.getMessage().contains("incident approach evidence"),
                failure::getMessage);
        var missingAssessment = new ModernSingleWayEditPlanAdapter().assess(missingComputed, 0);
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION,
                missingAssessment.availability(), missingAssessment.detail());
        assertEquals(ManualJunctionEligibility.Reason.MISSING_RECEIVER_EVIDENCE,
                missingAssessment.manualReason());
        assertTrue(missingAssessment.detail().contains(
                "Adjust this junction manually, then run alignment again."));
        var missingBundle = Format15ProductionBundleFactory.createLive("test",
                new FrozenReplayInput(missingComputed.request(), missingComputed.evidence(),
                        missingComputed.captured().network(), missingComputed.options()),
                missingComputed.pipeline(), "blocked", "visible-layer", 0, null, false, false,
                Map.of(), missingAssessment.manualReason());
        String missingStatus = new String(missingBundle.artifact("attempt-status.json").bytes(),
                StandardCharsets.UTF_8);
        assertTrue(missingStatus.contains("\"status\":\"blocked\""));
        assertTrue(missingStatus.contains(
                "\"manualJunctionReason\":\"MISSING_RECEIVER_EVIDENCE\""));
    }

    @Test
    void T169_displacedProtectedIncidentShapeFailsClosed() throws Exception {
        JunctionFixture fixture = reconstructionFixture(true);
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionReconstructionRaster(true),
                visibleConfig(), false, permissions));
        PrimitiveKey protectedShape = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                fixture.middle().getUniqueId());
        assertFalse(captured[0].network().closure().movableExistingNodeKeys()
                .contains(protectedShape));
        assertTrue(captured[0].network().closure().protectedExistingNodeKeys()
                .contains(protectedShape));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(fixture.dataSet(), computed,
                ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED);
    }

    @Test
    void T169_partialAndAmbiguousIncidentEvidenceFailClosed() throws Exception {
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        for (ReceiverEvidence evidence : List.of(ReceiverEvidence.NORTH_ONLY,
                ReceiverEvidence.AMBIGUOUS)) {
            JunctionFixture fixture = reconstructionFixture();
            LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
            SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                    fixture.dataSet(), fixture.selection(),
                    junctionReconstructionRaster(evidence, 2.0), visibleConfig(), false,
                    permissions));
            LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                    captured[0], CancellationProbe.NONE);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ModernSingleWayEditPlanAdapter().adapt(computed, 0),
                    evidence.name());
            assertTrue(failure.getMessage().contains("incident approach evidence"),
                    failure::getMessage);
        }
    }

    @Test
    void T169_reliabilityChangedRouteKeepsIncidentOccurrenceOrderValid() throws Exception {
        JunctionFixture fixture = orderInversionFixture();
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(),
                junctionReconstructionRaster(ReceiverEvidence.COMPLETE, 6.0),
                visibleConfig(), false, permissions));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        AlignmentEditPlan plan = assertDoesNotThrow(
                () -> new ModernSingleWayEditPlanAdapter().adapt(computed, 0));
        PrimitiveKey receiver = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                fixture.receiver().getUniqueId());
        assertTrue(plan.finalPreviewWays().containsKey(receiver));
    }

    @Test
    void T169_selectedInteriorWithOneCapturedThroughReceiverUsesDirectEvidence() throws Exception {
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        JunctionFixture selectedInterior = selectedInteriorReconstructionFixture();
        LiveBPreviewService.Captured[] interiorCaptured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> interiorCaptured[0] = new LiveBPreviewService().capture(
                selectedInterior.dataSet(), selectedInterior.selection(),
                selectedInteriorConnectorRaster(), visibleConfig(), false, permissions));
        LiveBPreviewService.Computed interiorComputed = new LiveBPreviewService().compute(
                interiorCaptured[0], CancellationProbe.NONE);
        AlignmentEditPlan interiorPlan = new ModernSingleWayEditPlanAdapter()
                .adapt(interiorComputed, 0);
        PrimitiveKey interiorReceiver = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                selectedInterior.receiver().getUniqueId());
        assertEquals(Set.of(interiorComputed.request().selectedWayKey(), interiorReceiver),
                interiorPlan.finalPreviewWays().keySet());
        assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED,
                interiorPlan.validation().disposition(), () -> interiorPlan.validation()
                        + " proposed J=" + ((DetachedNode) interiorPlan.after().primitives().get(
                                PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                                        selectedInterior.junction().getUniqueId()))).coordinate());
        assertEquals(((DetachedWay) interiorPlan.after().primitives().get(interiorReceiver))
                .nodeKeys().stream()
                .map(key -> ((DetachedNode) interiorPlan.after().primitives().get(key)).coordinate())
                .toList(), interiorPlan.finalPreviewWays().get(interiorReceiver));
        for (var port : interiorComputed.captured().network().closure().externalPorts().stream()
                .filter(value -> value.wayKey().equals(interiorReceiver)).toList()) {
            assertEquals(interiorPlan.before().primitives().get(port.boundaryNodeKey()),
                    interiorPlan.after().primitives().get(port.boundaryNodeKey()));
        }

        JunctionFixture missingEvidence = selectedInteriorReconstructionFixture();
        LiveBPreviewService.Captured[] missingCaptured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> missingCaptured[0] = new LiveBPreviewService().capture(
                missingEvidence.dataSet(), missingEvidence.selection(),
                junctionReconstructionRaster(false), visibleConfig(), false, permissions));
        LiveBPreviewService.Computed missingComputed = new LiveBPreviewService().compute(
                missingCaptured[0], CancellationProbe.NONE);
        IllegalArgumentException missingFailure = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(missingComputed, 0));
        assertTrue(missingFailure.getMessage().contains("incident approach evidence"),
                missingFailure::getMessage);
        assertFalse(missingFailure.getMessage().contains("bounded terminal-through topology"),
                missingFailure::getMessage);
    }

    @Test
    void T169_darkSelectedInteriorOutsideConnectorIsBlocked() throws Exception {
        JunctionFixture fixture = selectedInteriorReconstructionFixture();
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionReconstructionRaster(true),
                visibleConfig(), false, permissions));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        assertEquals(ValidationReport.Disposition.HARD_BLOCKED,
                plan.validation().disposition(), plan.validation()::toString);
        assertTrue(plan.validation().findingCodes().contains(
                "final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH"),
                plan.validation()::toString);
    }

    @Test
    void T169_fullSelectedRangeCapturesStrictlyInteriorSharedJunction() throws Exception {
        JunctionFixture fixture = selectedInteriorReconstructionFixture();
        Way selected = fixture.selection().way();
        SelectionContext fullRange = new SelectionContext(selected, 0, 2,
                selected.getNodes(), Set.of());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fullRange, junctionReconstructionRaster(true),
                visibleConfig(), false, permissions));

        PrimitiveKey receiver = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                fixture.receiver().getUniqueId());
        PrimitiveKey junction = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                fixture.junction().getUniqueId());
        assertFalse(captured[0].network().closure().editableWayOccurrences().containsKey(receiver));
        assertTrue(captured[0].network().closure().protectedExistingNodeKeys().contains(junction));
        var computed = new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(fixture.dataSet(), computed,
                ManualJunctionEligibility.Reason.SELECTED_INTERIOR);
    }

    @Test
    void T169_receiverCrossingLaterRequiresNodeResequencing() throws Exception {
        JunctionFixture fixture = displacedInteriorJunctionFixture();
        Way selected = fixture.selection().way();
        SelectionContext fullRange = new SelectionContext(selected, 0, 2,
                selected.getNodes(), Set.of());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fullRange,
                junctionReconstructionRaster(ReceiverEvidence.COMPLETE, 12.0),
                visibleConfig(), false, permissions));
        var computed = new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(fixture.dataSet(), computed,
                ManualJunctionEligibility.Reason.SELECTED_INTERIOR);
    }

    @Test
    void T169_splitReceiverTransfersOrdinaryMiddleToEarlierHalf() throws Exception {
        DataSet dataSet = new DataSet();
        Node west = loadedNode(701, latitude(6), longitude(-8));
        Node junction = loadedNode(702, latitude(0), longitude(10));
        Node farSouth = loadedNode(703, latitude(-49), longitude(10));
        Node southPort = loadedNode(704, latitude(-31), longitude(10));
        Node south = loadedNode(705, latitude(-8), longitude(10));
        Node middle = loadedNode(706, latitude(2), longitude(10));
        Node north = loadedNode(707, latitude(18), longitude(10));
        Node northPort = loadedNode(708, latitude(37), longitude(10));
        Node farNorth = loadedNode(709, latitude(49), longitude(10));
        Way selected = loadedHighwayWay(711, west, junction);
        Way first = loadedHighwayWay(712, farSouth, southPort, south, junction);
        Way second = loadedHighwayWay(713, junction, middle, north, northPort, farNorth);
        for (Node node : List.of(west, junction, farSouth, southPort, south,
                middle, north, northPort, farNorth)) {
            dataSet.addPrimitive(node);
        }
        for (Way way : List.of(selected, first, second)) {
            dataSet.addPrimitive(way);
        }
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                selected.getNodes(), Set.of());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection,
                junctionReconstructionRaster(ReceiverEvidence.COMPLETE, 6.0),
                visibleConfig(), false, permissions));
        var computed = new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(dataSet, computed,
                ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS);
        second.put("maxspeed", "30");
        LiveBPreviewService.Captured[] boundaryCaptured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> boundaryCaptured[0] = new LiveBPreviewService().capture(
                dataSet, selection,
                junctionReconstructionRaster(ReceiverEvidence.COMPLETE, 6.0),
                visibleConfig(), false, permissions));
        var boundaryComputed = new LiveBPreviewService().compute(boundaryCaptured[0],
                CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(dataSet, boundaryComputed,
                ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS);
    }

    @Test
    void T169_terminalExtensionWithoutSelectedImageSupportCannotApply() throws Exception {
        AlignmentEditPlan plan = terminalExtensionPlan(false);
        assertTrue(((DetachedNode) plan.after().primitives().get(
                PrimitiveKey.existing(PrimitiveKey.Type.NODE, 742))).coordinate()
                .longitudeDegrees() > longitude(20),
                "the counterexample must take the distant receiver crossing");
        assertEquals(ValidationReport.Disposition.HARD_BLOCKED,
                plan.validation().disposition(),
                "the newly extended selected approach has no heatmap support");
        assertTrue(plan.validation().findingCodes().contains(
                "final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH"),
                plan.validation()::toString);
    }

    @Test
    void T169_directlyMeasuredSelectedExtensionCanReachReceiver() throws Exception {
        AlignmentEditPlan plan = terminalExtensionPlan(true);
        assertTrue(((DetachedNode) plan.after().primitives().get(
                PrimitiveKey.existing(PrimitiveKey.Type.NODE, 742))).coordinate()
                .longitudeDegrees() > longitude(20));
        assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED,
                plan.validation().disposition(), plan.validation()::toString);
        assertFalse(plan.validation().findingCodes().contains(
                "final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH"));
    }

    @Test
    void T169_nearbyParallelRidgeCannotSupplySelectedTerminalExtension() throws Exception {
        LiveBPreviewService.Computed computed = terminalExtensionComputed(false, true);
        PrimitiveKey selected = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 750);
        PrimitiveKey junction = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 742);
        PrimitiveKey nearArm = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 752);
        PrimitiveKey receiver = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 751);
        NetworkSnapshot before = computed.captured().network();
        assertTrue(((DetachedWay) before.primitives().get(receiver)).nodeKeys().contains(nearArm),
                "the competing x10..24 arm belongs to the captured affected receiver");
        Map<PrimitiveKey, DetachedPrimitive> after = new LinkedHashMap<>(before.primitives());
        DetachedNode oldJunction = (DetachedNode) after.get(junction);
        after.put(junction, new DetachedNode(junction,
                new GeographicPoint(0.0, longitude(24)), oldJunction.tags(), false, true));
        List<FinalRoutePointId> ids = List.of(
                new ExistingWayNodeOccurrence(selected,
                        PrimitiveKey.existing(PrimitiveKey.Type.NODE, 741), 0),
                new ExistingWayNodeOccurrence(selected, junction, 1));
        ModernTracePipeline.Route route = exactRoute(computed, ids);
        assertTrue(supportFindings(computed, after, route, Set.of(junction)).contains(
                "final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH"),
                "a direct raster mode from the near-parallel receiver arm cannot authorize J");
    }

    private static AlignmentEditPlan terminalExtensionPlan(boolean selectedContinues)
            throws Exception {
        return terminalExtensionPlan(selectedContinues, false);
    }

    private static AlignmentEditPlan terminalExtensionPlan(boolean selectedContinues,
            boolean nearbyParallelRidge) throws Exception {
        return new ModernSingleWayEditPlanAdapter().adapt(
                terminalExtensionComputed(selectedContinues, nearbyParallelRidge), 0);
    }

    private static LiveBPreviewService.Computed terminalExtensionComputed(
            boolean selectedContinues, boolean nearbyParallelRidge) throws Exception {
        DataSet dataSet = new DataSet();
        Node west = loadedNode(741, 0.0, longitude(-8));
        Node junction = loadedNode(742, 0.0, longitude(10));
        Node farSouth = loadedNode(743, latitude(-49), longitude(25));
        Node southPort = loadedNode(744, latitude(-31), longitude(25));
        Node south = loadedNode(745, latitude(-8), longitude(25));
        Node middle = loadedNode(746, latitude(8), longitude(25));
        Node north = loadedNode(747, latitude(18), longitude(25));
        Node northPort = loadedNode(748, latitude(31), longitude(25));
        Node farNorth = loadedNode(749, latitude(49), longitude(25));
        Node nearReceiverArm = loadedNode(752, latitude(0.05), longitude(24));
        Way selected = loadedHighwayWay(750, west, junction);
        Way receiver = nearbyParallelRidge
                ? loadedHighwayWay(751, farSouth, southPort, south, junction,
                        nearReceiverArm, middle, north, northPort, farNorth)
                : loadedHighwayWay(751, farSouth, southPort, south, junction,
                        middle, north, northPort, farNorth);
        for (Node node : List.of(west, junction, farSouth, southPort, south,
                middle, north, northPort, farNorth)) {
            dataSet.addPrimitive(node);
        }
        if (nearbyParallelRidge) {
            dataSet.addPrimitive(nearReceiverArm);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                selected.getNodes(), Set.of());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, terminalExtensionRaster(selectedContinues,
                        nearbyParallelRidge),
                visibleConfig(), false, permissions));
        return new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);
    }

    @Test
    void T169_startBoundaryReinsertionChecksNewSelectedPredecessorSpan() throws Exception {
        assertTrue(startBoundarySupportFindings(false, false).contains(
                "final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH"),
                "the final A→J span is unsupported even though old J lies beyond X on B→X");
    }

    @Test
    void T169_measuredStartBoundaryReinsertionRemainsReviewable() throws Exception {
        assertFalse(startBoundarySupportFindings(true, true).contains(
                "final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH"));
    }

    @Test
    void T169_unmeasuredOutsidePrefixConnectorBlocksReinsertion() throws Exception {
        assertTrue(startBoundarySupportFindings(true, false).contains(
                "final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH"),
                "A→X is directly measured but the new outside-range P→A diagonal is dark");
    }

    @Test
    void T169_reorderedPrefixCannotBecomeAProposedAfterSnapshot() throws Exception {
        NetworkSnapshot before = startBoundaryReinsertionComputed(true, false)
                .captured().network();
        PrimitiveKey selected = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 789);
        PrimitiveKey junction = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 782);
        PrimitiveKey predecessor = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 783);
        PrimitiveKey end = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 784);
        assertTrue(before.closure().externalPorts().stream().anyMatch(port ->
                port.wayKey().equals(selected) && port.boundaryNodeKey().equals(junction)
                        && port.outsideNeighborKey().equals(
                                PrimitiveKey.existing(PrimitiveKey.Type.NODE, 781))));
        Map<PrimitiveKey, DetachedPrimitive> reordered = new LinkedHashMap<>(before.primitives());
        DetachedWay oldSelected = (DetachedWay) reordered.get(selected);
        reordered.put(selected, new DetachedWay(selected,
                List.of(oldSelected.nodeKeys().get(0), predecessor, junction, end),
                oldSelected.tags(), false, true));
        IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class,
                () -> new NetworkSnapshot("reordered-prefix", SnapshotRole.PROPOSED_AFTER,
                        before.datasetIdentity(), before.sourceGeneration(), before.closure(),
                        reordered, before.incomingReferrerWatches()));
        assertTrue(rejection.getMessage().contains("External port is inconsistent"),
                rejection::getMessage);
    }

    @Test
    void T169_changedOutsidePortConnectorRequiresDirectSupport() throws Exception {
        MovedPortFixture fixture = movedPortFixture(false);
        NetworkSnapshot after = fixture.after();
        PrimitiveKey selected = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 789);
        PrimitiveKey junction = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 782);
        assertEquals(List.of(PrimitiveKey.existing(PrimitiveKey.Type.NODE, 781), junction,
                PrimitiveKey.existing(PrimitiveKey.Type.NODE, 783),
                PrimitiveKey.existing(PrimitiveKey.Type.NODE, 784)),
                ((DetachedWay) after.primitives().get(selected)).nodeKeys(),
                "captured outside P→J port must retain its adjacency");
        assertTrue(((DetachedNode) after.primitives().get(junction)).coordinate()
                .longitudeDegrees() > longitude(20),
                "the selected boundary must actually reach the receiver at x≈25");
        assertTrue(fixture.supportFindings().contains(
                "final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH"),
                fixture.supportFindings()::toString);
    }

    @Test
    void T169_measuredOutsidePortConnectorRemainsReviewable() throws Exception {
        MovedPortFixture fixture = movedPortFixture(true);
        NetworkSnapshot after = fixture.after();
        PrimitiveKey junction = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 782);
        assertTrue(((DetachedNode) after.primitives().get(junction)).coordinate()
                .longitudeDegrees() > longitude(20));
        assertFalse(fixture.supportFindings().contains(
                "final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH"),
                fixture.supportFindings()::toString);
    }

    private record MovedPortFixture(NetworkSnapshot after, List<String> supportFindings) { }

    private static MovedPortFixture movedPortFixture(boolean connectorMeasured) throws Exception {
        LiveBPreviewService.Computed computed = startBoundaryMovedPortComputed(connectorMeasured);
        NetworkSnapshot before = computed.captured().network();
        PrimitiveKey selected = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 789);
        PrimitiveKey receiver = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 792);
        PrimitiveKey junction = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 782);
        PrimitiveKey predecessor = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 783);
        PrimitiveKey end = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 784);
        Map<PrimitiveKey, DetachedPrimitive> changed = new LinkedHashMap<>(before.primitives());
        DetachedNode oldJunction = (DetachedNode) changed.get(junction);
        changed.put(junction, new DetachedNode(junction,
                new GeographicPoint(0.0, longitude(25)), oldJunction.tags(), false, true));
        NetworkSnapshot after = new NetworkSnapshot("moved-port", SnapshotRole.PROPOSED_AFTER,
                before.datasetIdentity(), before.sourceGeneration(), before.closure(),
                changed, before.incomingReferrerWatches());
        Map<PrimitiveKey, List<GeographicPoint>> preview = new LinkedHashMap<>();
        for (PrimitiveKey key : List.of(selected, receiver)) {
            preview.put(key, ((DetachedWay) changed.get(key)).nodeKeys().stream()
                    .map(node -> ((DetachedNode) changed.get(node)).coordinate()).toList());
        }
        IllegalArgumentException planRefusal = assertThrows(IllegalArgumentException.class,
                () -> new AlignmentEditPlan(selected, computed.request().selectedRange(),
                        before, after, computed.evidence().coordinateFrame(),
                        computed.request().permissions(), "settings", "evidence", "parameters",
                        "exact-port-fixture", preview,
                        new ValidationReport(ValidationReport.Disposition.REVIEW_REQUIRED, List.of())));
        assertTrue(planRefusal.getMessage().contains("movement authority"),
                planRefusal::getMessage);
        List<FinalRoutePointId> ids = List.of(
                new ExistingWayNodeOccurrence(selected, junction, 1),
                new ExistingWayNodeOccurrence(selected, predecessor, 2),
                new ExistingWayNodeOccurrence(selected, end, 3));
        ModernTracePipeline.Route route = exactRoute(computed, ids, List.of(
                computed.evidence().coordinateFrame().toMetric(
                        new GeographicPoint(0.0, longitude(12))),
                computed.evidence().coordinateFrame().toMetric(
                        new GeographicPoint(0.0, longitude(30))),
                computed.evidence().coordinateFrame().toMetric(
                        new GeographicPoint(0.0, longitude(32)))));
        return new MovedPortFixture(after,
                supportFindings(computed, changed, route, Set.of(junction)));
    }

    private static LiveBPreviewService.Computed startBoundaryMovedPortComputed(
            boolean connectorMeasured) throws Exception {
        return startBoundaryReinsertionComputed(true, connectorMeasured, 30.0, 25.0);
    }

    private static List<String> startBoundarySupportFindings(boolean selectedContinues,
            boolean prefixConnectorMeasured)
            throws Exception {
        // Fix the route and final order explicitly so this oracle reaches the final-support
        // gate independently of the inference engine and topology planner's earlier guards.
        LiveBPreviewService.Computed computed = startBoundaryReinsertionComputed(
                selectedContinues, prefixConnectorMeasured);
        PrimitiveKey selected = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 789);
        PrimitiveKey junction = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 782);
        PrimitiveKey predecessor = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 783);
        PrimitiveKey end = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 784);
        NetworkSnapshot before = computed.captured().network();
        Map<PrimitiveKey, DetachedPrimitive> after = new LinkedHashMap<>(before.primitives());
        DetachedNode oldJunction = (DetachedNode) after.get(junction);
        after.put(junction, new DetachedNode(junction,
                new GeographicPoint(0.0, longitude(25)), oldJunction.tags(), false, true));
        DetachedWay oldSelected = (DetachedWay) after.get(selected);
        after.put(selected, new DetachedWay(selected,
                List.of(oldSelected.nodeKeys().get(0), predecessor, junction, end),
                oldSelected.tags(), false, true));
        List<FinalRoutePointId> ids = List.of(
                new ExistingWayNodeOccurrence(selected, junction, 1),
                new ExistingWayNodeOccurrence(selected, predecessor, 2),
                new ExistingWayNodeOccurrence(selected, end, 3));
        ModernTracePipeline.Route route = exactRoute(computed, ids, List.of(
                computed.evidence().coordinateFrame().toMetric(
                        new GeographicPoint(0.0, longitude(12))),
                computed.evidence().coordinateFrame().toMetric(
                        new GeographicPoint(0.0, longitude(15))),
                computed.evidence().coordinateFrame().toMetric(
                        new GeographicPoint(0.0, longitude(32)))));
        return supportFindings(computed, after, route, Set.of(junction));
    }

    private static ModernTracePipeline.Route exactRoute(LiveBPreviewService.Computed computed,
            List<FinalRoutePointId> ids) {
        List<MetricPoint> points = ids.stream().map(id -> computed.evidence().coordinateFrame()
                .toMetric(((DetachedNode) computed.captured().network().primitives().get(
                        ((ExistingWayNodeOccurrence) id).nodeKey())).coordinate())).toList();
        return exactRoute(computed, ids, points);
    }

    private static ModernTracePipeline.Route exactRoute(LiveBPreviewService.Computed computed,
            List<FinalRoutePointId> ids, List<MetricPoint> points) {
        List<ObservationOwnership> ownership = java.util.Collections.nCopies(ids.size(),
                ObservationOwnership.DIRECT_TWO_SIDED);
        TraceHypothesis hypothesis = new TraceHypothesis("exact-final-order-fixture",
                "exact-final-order-fixture", points, ownership, 0.0, OptionalDouble.empty(), Map.of());
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>();
        Map<FinalRoutePointId, ObservationOwnership> sourceOwnership = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            assignments.put(ids.get(index), points.get(index));
            sourceOwnership.put(ids.get(index), ownership.get(index));
        }
        return new ModernTracePipeline.Route(hypothesis, hypothesis, ids, assignments,
                sourceOwnership, new FinalGeometryEvaluator.Result(hypothesis.id(),
                        FinalGeometryEvaluator.Disposition.APPLICABLE,
                        List.of(), 10.0, 10.0, 0.0, 0.1, 0.0),
                ImageSupportedLocalCleanup.Status.UNCHANGED, false);
    }

    private static List<String> supportFindings(LiveBPreviewService.Computed computed,
            Map<PrimitiveKey, DetachedPrimitive> after, ModernTracePipeline.Route route,
            Set<PrimitiveKey> junctions) throws Exception {
        Method support = ModernSingleWayEditPlanAdapter.class.getDeclaredMethod(
                "unsupportedSelectedExtensions", NetworkSnapshot.class, Map.class,
                ModernTracePipeline.Route.class, TraceRequest.class, EvidenceSnapshot.class,
                String.class, Set.class);
        support.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> findings = (List<String>) support.invoke(null,
                computed.captured().network(), after, route, computed.request(),
                computed.evidence(), computed.options().fieldName(), junctions);
        return findings;
    }

    private static List<String> finalTopologyFindings(LiveBPreviewService.Computed computed,
            Map<PrimitiveKey, DetachedPrimitive> after) throws Exception {
        Method checker = ModernSingleWayEditPlanAdapter.class.getDeclaredMethod(
                "finalTopologyFindings", NetworkSnapshot.class, Map.class,
                PrimitiveKey.class, org.openstreetmap.josm.plugins.wayheatmaptracer.model
                        .OccurrenceRange.class, EvidenceSnapshot.class);
        checker.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> findings = (List<String>) checker.invoke(null,
                computed.captured().network(), after, computed.request().selectedWayKey(),
                computed.request().selectedRange(), computed.evidence());
        return findings;
    }

    private static LiveBPreviewService.Computed startBoundaryReinsertionComputed(
            boolean selectedContinues, boolean prefixConnectorMeasured)
            throws Exception {
        return startBoundaryReinsertionComputed(selectedContinues, prefixConnectorMeasured,
                15.0, 15.0);
    }

    private static LiveBPreviewService.Computed startBoundaryReinsertionComputed(
            boolean selectedContinues, boolean prefixConnectorMeasured,
            double predecessorEast, double connectorEndEast)
            throws Exception {
        DataSet dataSet = new DataSet();
        Node prefix = loadedNode(781, latitude(-20), longitude(10));
        Node junction = loadedNode(782, 0.0, longitude(10));
        Node predecessor = loadedNode(783, 0.0, longitude(predecessorEast));
        predecessor.put("barrier", "gate");
        Node end = loadedNode(784, 0.0, longitude(32));
        Node farSouth = loadedNode(785, latitude(-49), longitude(25));
        Node southPort = loadedNode(786, latitude(-31), longitude(25));
        Node south = loadedNode(787, latitude(-8), longitude(25));
        Node north = loadedNode(788, latitude(8), longitude(25));
        Node northPort = loadedNode(790, latitude(31), longitude(25));
        Node farNorth = loadedNode(791, latitude(49), longitude(25));
        Way selected = loadedHighwayWay(789, prefix, junction, predecessor, end);
        Way receiver = loadedHighwayWay(792, farSouth, southPort, south, junction,
                north, northPort, farNorth);
        for (Node node : List.of(prefix, junction, predecessor, end, farSouth,
                southPort, south, north, northPort, farNorth)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        SelectionContext selection = new SelectionContext(selected, 1, 3,
                List.of(junction, predecessor, end), Set.of());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, terminalExtensionRaster(selectedContinues, false,
                        true, prefixConnectorMeasured, connectorEndEast),
                visibleConfig(), false, permissions));
        return new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);
    }

    @Test
    void T169_distinctSupportedReceiverCrossingsRequireAnUnambiguousChoice() throws Exception {
        DataSet dataSet = new DataSet();
        Node west = loadedNode(761, 0.0, longitude(-20));
        Node junction = loadedNode(762, 0.0, longitude(10));
        Node farWest = loadedNode(763, latitude(-18), longitude(-45));
        Node westPort = loadedNode(764, latitude(-15), longitude(-35));
        Node approach = loadedNode(765, latitude(-10), longitude(-20));
        Node nearWest = loadedNode(766, latitude(-4), longitude(-5));
        Node nearEast = loadedNode(767, latitude(5), longitude(14));
        Node dip = loadedNode(768, latitude(-5), longitude(24));
        Node rise = loadedNode(769, latitude(20), longitude(35));
        Node eastPort = loadedNode(770, latitude(28), longitude(45));
        Node farEast = loadedNode(771, latitude(30), longitude(50));
        Way selected = loadedHighwayWay(772, west, junction);
        Way receiver = loadedHighwayWay(773, farWest, westPort, approach, nearWest,
                junction, nearEast, dip, rise, eastPort, farEast);
        for (Node node : List.of(west, junction, farWest, westPort, approach,
                nearWest, nearEast, dip, rise, eastPort, farEast)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                selected.getNodes(), Set.of());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, multiplyCrossedReceiverRaster(),
                visibleConfig(), false, permissions));
        var computed = new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(computed, 0));
        assertTrue(failure.getMessage().contains("AMBIGUOUS_JUNCTION_CROSSING"),
                failure::getMessage);
        assertEquals(List.of(west, junction), selected.getNodes());
        assertEquals(List.of(farWest, westPort, approach, nearWest, junction,
                nearEast, dip, rise, eastPort, farEast), receiver.getNodes());
    }

    @Test
    void T169_exactSharedVertexDuplicateRemainsOneJunctionChoice() throws Exception {
        List<?> crossings = twoAdjacentSegmentCrossings(10.0);
        assertEquals(1, crossings.size(),
                "two segment-pair hits at the same physical vertex are one crossing");
        assertEquals(crossings.get(0), chosenCrossing(crossings));
    }

    @Test
    void T169_closeButDistinctCrossingsStillRequireJointEvidence() throws Exception {
        List<?> crossings = twoAdjacentSegmentCrossings(10.02);
        assertEquals(2, crossings.size(),
                "2 cm is below a quarter source pitch but remains a distinct crossing");
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> chosenCrossing(crossings));
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
        assertTrue(failure.getCause().getMessage().contains("AMBIGUOUS_JUNCTION_CROSSING"));
    }

    @Test
    void T169_sharedReceiverVertexArithmeticIsOneJunctionChoice() throws Exception {
        MetricPoint selectedStart = new MetricPoint(0.0, 0.0);
        MetricPoint selectedEnd = new MetricPoint(5.3, 1.7);
        MetricPoint receiverStart = new MetricPoint(-2.1, 4.2);
        MetricPoint sharedVertex = new MetricPoint(1.06, 0.34);
        MetricPoint receiverEnd = new MetricPoint(11.1, 6.6);
        Method crossing = ModernSingleWayEditPlanAdapter.class.getDeclaredMethod(
                "segmentCrossing", MetricPoint.class, MetricPoint.class,
                MetricPoint.class, MetricPoint.class);
        crossing.setAccessible(true);
        MetricPoint first = (MetricPoint) crossing.invoke(null, selectedStart, selectedEnd,
                receiverStart, sharedVertex);
        MetricPoint second = (MetricPoint) crossing.invoke(null, selectedStart, selectedEnd,
                sharedVertex, receiverEnd);
        assertNotEquals(first, second, "adjacent hits differ at floating-point precision");

        LiveBPreviewService.Computed computed = terminalExtensionComputed(true, false);
        PrimitiveKey selected = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 750);
        PrimitiveKey junction = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 742);
        PrimitiveKey c = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 810);
        PrimitiveKey v = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 811);
        PrimitiveKey d = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 812);
        Map<PrimitiveKey, DetachedPrimitive> evidenced = new LinkedHashMap<>(
                computed.captured().network().primitives());
        for (Map.Entry<PrimitiveKey, MetricPoint> entry : Map.of(c, receiverStart,
                v, sharedVertex, d, receiverEnd).entrySet()) {
            evidenced.put(entry.getKey(), new DetachedNode(entry.getKey(),
                    computed.evidence().coordinateFrame().toGeographic(entry.getValue()),
                    Map.of(), false, true));
        }
        List<FinalRoutePointId> ids = List.of(
                new ExistingWayNodeOccurrence(selected, junction, 1),
                new GeneratedCandidatePoint("vertex", 0),
                new GeneratedCandidatePoint("vertex", 1));
        ModernTracePipeline.Route route = exactRoute(computed, ids,
                List.of(new MetricPoint(7.0, 2.0), selectedStart, selectedEnd));
        Method intersections = ModernSingleWayEditPlanAdapter.class.getDeclaredMethod(
                "selectedReceiverIntersections", NetworkSnapshot.class,
                ModernTracePipeline.Route.class, Map.class, EvidenceSnapshot.class,
                List.class, PrimitiveKey.class, MetricPoint.class);
        intersections.setAccessible(true);
        List<?> choices = (List<?>) intersections.invoke(null,
                computed.captured().network(), route, evidenced, computed.evidence(),
                List.of(c, v, d), junction, sharedVertex);
        assertEquals(1, choices.size(),
                "two arithmetic hits at receiver node V share one occurrence identity");
        assertEquals(choices.get(0), chosenCrossing(choices));
    }

    private static List<?> twoAdjacentSegmentCrossings(double secondEast) throws Exception {
        Class<?> crossing = java.util.Arrays.stream(
                        ModernSingleWayEditPlanAdapter.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("SelectedReceiverIntersection"))
                .findFirst().orElseThrow();
        Constructor<?> constructor = crossing.getDeclaredConstructor(MetricPoint.class,
                PrimitiveKey.class, double.class, int.class, int.class, PrimitiveKey.class);
        constructor.setAccessible(true);
        PrimitiveKey predecessor = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 799);
        Object first = constructor.newInstance(new MetricPoint(10.0, 0.0), predecessor,
                0.0, 0, 0, null);
        Object second = constructor.newInstance(new MetricPoint(secondEast, 0.0), predecessor,
                0.02, 1, 1, null);
        Method best = ModernSingleWayEditPlanAdapter.class.getDeclaredMethod(
                "bestIntersections", List.class);
        best.setAccessible(true);
        return (List<?>) best.invoke(null, new java.util.ArrayList<>(List.of(first, second)));
    }

    private static Object chosenCrossing(List<?> crossings) throws Exception {
        Method unique = ModernSingleWayEditPlanAdapter.class.getDeclaredMethod(
                "uniqueIntersection", List.class);
        unique.setAccessible(true);
        return unique.invoke(null, crossings);
    }

    @Test
    void T169_selectedInteriorWithAdditionalIncidentWayFailsClosed() throws Exception {
        JunctionFixture fixture = selectedInteriorReconstructionFixture();
        Node additionalEndpoint = loadedNode(312, latitude(18), longitude(8));
        Way additional = loadedWay(313, fixture.junction(), additionalEndpoint);
        fixture.dataSet().addPrimitive(additionalEndpoint);
        fixture.dataSet().addPrimitive(additional);
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionReconstructionRaster(true),
                visibleConfig(), false, permissions));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(fixture.dataSet(), computed,
                ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS);
    }

    @Test
    void T169_multipleReceiversRemainOutsideBoundedTopology() throws Exception {
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);

        JunctionFixture multipleReceivers = reconstructionFixture();
        Way duplicateReceiver = loadedWay(112,
                multipleReceivers.receiver().getNodes().toArray(Node[]::new));
        multipleReceivers.dataSet().addPrimitive(duplicateReceiver);
        LiveBPreviewService.Captured[] multipleCaptured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> multipleCaptured[0] = new LiveBPreviewService().capture(
                multipleReceivers.dataSet(), multipleReceivers.selection(),
                junctionReconstructionRaster(true), visibleConfig(), false, permissions));
        LiveBPreviewService.Computed multipleComputed = new LiveBPreviewService().compute(
                multipleCaptured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(multipleReceivers.dataSet(), multipleComputed,
                ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS);
    }

    @Test
    void T169_splitReceiverUsesBothMeasuredArms() throws Exception {
        DataSet dataSet = new DataSet();
        Node west = loadedNode(401, 0.0, longitude(-8));
        Node junction = loadedNode(402, 0.0, longitude(8));
        Node farSouth = loadedNode(403, latitude(-49), longitude(10));
        Node southPort = loadedNode(404, latitude(-31), longitude(10));
        Node south = loadedNode(405, latitude(-8), longitude(10));
        Node middle = loadedNode(406, latitude(8), longitude(10));
        Node north = loadedNode(407, latitude(18), longitude(10));
        Node northPort = loadedNode(408, latitude(31), longitude(10));
        Node farNorth = loadedNode(409, latitude(49), longitude(10));
        Way selected = loadedHighwayWay(410, west, junction);
        Way southReceiver = loadedHighwayWay(411, farSouth, southPort, south, junction);
        Way northReceiver = loadedHighwayWay(412, junction, middle, north, northPort, farNorth);
        for (Node node : List.of(west, junction, farSouth, southPort, south, middle,
                north, northPort, farNorth)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(southReceiver);
        dataSet.addPrimitive(northReceiver);
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                List.of(west, junction), Set.of());
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, junctionReconstructionRaster(true), visibleConfig(),
                false, permissions));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(dataSet, computed,
                ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS);

        LiveBPreviewService.Captured[] missingCaptured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> missingCaptured[0] = new LiveBPreviewService().capture(
                dataSet, selection, junctionReconstructionRaster(false), visibleConfig(),
                false, permissions));
        LiveBPreviewService.Computed missingComputed = new LiveBPreviewService().compute(
                missingCaptured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(dataSet, missingComputed,
                ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS);

        List<Node> originalNorthNodes = List.copyOf(northReceiver.getNodes());
        Node sameSideInner = loadedNode(415, latitude(-8), longitude(10));
        Node sameSideMiddle = loadedNode(416, latitude(-18), longitude(10));
        Node sameSidePort = loadedNode(417, latitude(-31), longitude(10));
        Node sameSideFar = loadedNode(418, latitude(-49), longitude(10));
        for (Node node : List.of(sameSideInner, sameSideMiddle,
                sameSidePort, sameSideFar)) {
            dataSet.addPrimitive(node);
        }
        northReceiver.setNodes(List.of(junction, sameSideInner, sameSideMiddle,
                sameSidePort, sameSideFar));
        LiveBPreviewService.Captured[] sameSideCaptured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> sameSideCaptured[0] = new LiveBPreviewService().capture(
                dataSet, selection, junctionReconstructionRaster(true), visibleConfig(),
                false, permissions));
        LiveBPreviewService.Computed sameSideComputed = new LiveBPreviewService().compute(
                sameSideCaptured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(dataSet, sameSideComputed,
                ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS);
        northReceiver.setNodes(originalNorthNodes);

        Node extraEndpoint = loadedNode(413, latitude(18), longitude(6));
        Way extraIncident = loadedWay(414, junction, extraEndpoint);
        dataSet.addPrimitive(extraEndpoint);
        dataSet.addPrimitive(extraIncident);
        LiveBPreviewService.Captured[] extraCaptured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> extraCaptured[0] = new LiveBPreviewService().capture(
                dataSet, selection, junctionReconstructionRaster(true), visibleConfig(),
                false, permissions));
        LiveBPreviewService.Computed extraComputed = new LiveBPreviewService().compute(
                extraCaptured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(dataSet, extraComputed,
                ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS);
    }

    @Test
    void T169_twoThroughReceiversRequireDirectEvidenceForEveryArm() throws Exception {
        JunctionFixture fixture = reconstructionFixture();
        Node farSouth = loadedNode(120, latitude(-49), longitude(23.4));
        Node southPort = loadedNode(121, latitude(-31), longitude(23.4));
        Node south = loadedNode(122, latitude(-8), longitude(15.4));
        Node middle = loadedNode(123, latitude(8), longitude(2.6));
        Node north = loadedNode(124, latitude(18), longitude(-5.4));
        Node northPort = loadedNode(125, latitude(31), longitude(-5.4));
        Node farNorth = loadedNode(126, latitude(49), longitude(-5.4));
        Way diagonal = loadedHighwayWay(127, farSouth, southPort, south,
                fixture.junction(), middle, north, northPort, farNorth);
        for (Node node : List.of(farSouth, southPort, south, middle, north,
                northPort, farNorth)) {
            fixture.dataSet().addPrimitive(node);
        }
        fixture.dataSet().addPrimitive(diagonal);
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionMultipleReceiverRaster(),
                visibleConfig(), false, permissions));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(fixture.dataSet(), computed,
                ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS);

        LiveBPreviewService.Captured[] missingCaptured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> missingCaptured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionReconstructionRaster(true),
                visibleConfig(), false, permissions));
        LiveBPreviewService.Computed missingComputed = new LiveBPreviewService().compute(
                missingCaptured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(fixture.dataSet(), missingComputed,
                ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS);
    }

    @Test
    void T169_coupledSelectedEndpointsReconstructDistinctMeasuredReceivers() throws Exception {
        DataSet dataSet = new DataSet();
        Node leftJunction = loadedNode(601, 0.0, longitude(-8));
        Node rightJunction = loadedNode(602, 0.0, longitude(8));
        Way selected = loadedHighwayWay(610, leftJunction, rightJunction);
        Node leftFarSouth = loadedNode(603, latitude(-49), longitude(-10));
        Node leftSouthPort = loadedNode(604, latitude(-31), longitude(-10));
        Node leftSouth = loadedNode(605, latitude(-8), longitude(-10));
        Node leftMiddle = loadedNode(606, latitude(8), longitude(-10));
        Node leftNorth = loadedNode(607, latitude(18), longitude(-10));
        Node leftNorthPort = loadedNode(608, latitude(31), longitude(-10));
        Node leftFarNorth = loadedNode(609, latitude(49), longitude(-10));
        Way leftReceiver = loadedHighwayWay(611, leftFarSouth, leftSouthPort, leftSouth,
                leftJunction, leftMiddle, leftNorth, leftNorthPort, leftFarNorth);
        Node rightFarSouth = loadedNode(613, latitude(-49), longitude(10));
        Node rightSouthPort = loadedNode(614, latitude(-31), longitude(10));
        Node rightSouth = loadedNode(615, latitude(-8), longitude(10));
        Node rightMiddle = loadedNode(616, latitude(8), longitude(10));
        Node rightNorth = loadedNode(617, latitude(18), longitude(10));
        Node rightNorthPort = loadedNode(618, latitude(31), longitude(10));
        Node rightFarNorth = loadedNode(619, latitude(49), longitude(10));
        Way rightReceiver = loadedHighwayWay(612, rightFarSouth, rightSouthPort, rightSouth,
                rightJunction, rightMiddle, rightNorth, rightNorthPort, rightFarNorth);
        for (Node node : List.of(leftJunction, rightJunction, leftFarSouth, leftSouthPort,
                leftSouth, leftMiddle, leftNorth, leftNorthPort, leftFarNorth,
                rightFarSouth, rightSouthPort, rightSouth, rightMiddle, rightNorth,
                rightNorthPort, rightFarNorth)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(leftReceiver);
        dataSet.addPrimitive(rightReceiver);
        Relation route = new Relation();
        route.setMembers(List.of(new RelationMember("forward", selected),
                new RelationMember("left", leftReceiver),
                new RelationMember("right", rightReceiver)));
        route.put("type", "route");
        route.put("route", "bicycle");
        route.setOsmId(630, 1);
        route.setModified(false);
        dataSet.addPrimitive(route);
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                List.of(leftJunction, rightJunction), Set.of());
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, junctionCoupledRaster(), visibleConfig(),
                false, permissions));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(dataSet, computed,
                ManualJunctionEligibility.Reason.MULTIPLE_JUNCTIONS);
        List<RelationMember> originalMembers = List.copyOf(route.getMembers());
        Map<PrimitiveKey, DatasetPrimitiveState> originalState = snapshot(dataSet);
        RecoveryPermissions fixedPermissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.FIXED, false);
        LiveBPreviewService.Captured fixedCaptured = onEdt(() -> new LiveBPreviewService().capture(
                dataSet, selection, junctionCoupledRaster(), visibleConfig(),
                false, fixedPermissions));
        LiveBPreviewService.Computed fixedComputed = new LiveBPreviewService().compute(
                fixedCaptured, CancellationProbe.NONE);
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(fixedComputed, 0);
        NetworkSnapshotCapture.CapturedSnapshot receipt = onEdt(() ->
                NetworkSnapshotCapture.captureBound(dataSet, fixedComputed.captured().specification()));
        LiveNetworkSnapshotValidator validator = new LiveNetworkSnapshotValidator(
                receipt, plan, () -> plan.before().sourceGeneration());

        Node entrantSouth = loadedNode(640, latitude(-10), longitude(0));
        Node entrantNorth = loadedNode(641, latitude(10), longitude(0));
        Way entrant = loadedWay(642, entrantSouth, entrantNorth);
        dataSet.addPrimitive(entrantSouth);
        dataSet.addPrimitive(entrantNorth);
        dataSet.addPrimitive(entrant);
        ApplyAlignmentEditPlanCommand stale = new ApplyAlignmentEditPlanCommand(
                dataSet, plan, validator, "Reject stale coupled alignment");
        assertThrows(IllegalStateException.class, () -> onEdt(stale::executeCommand));
        assertEquals(originalMembers, route.getMembers());
        assertEquals(List.of(entrantSouth, entrantNorth), entrant.getNodes());
        for (var entry : originalState.entrySet()) {
            assertEquals(entry.getValue(), snapshot(dataSet).get(entry.getKey()));
        }
    }

    @Test
    void T171_twoLiveIntervalsApplyAsOneExactUndoableHostEdit() throws Exception {
        IntervalHostFixture fixture = intervalHostFixture();
        var computed = fixture.computed();
        assertTrue(computed.partitioned(), "a real captured manual junction must select interval tracing");
        var batch = computed.intervalBatch();
        assertEquals(2, batch.runs().size(), batch.partition().toString());
        var assessment = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.CHANGED,
                FixedIntervalEditPlanComposer.Disposition.CHANGED), assessment.intervals().stream()
                .map(FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList(),
                assessment.intervals().toString());
        assertTrue(assessment.applyAvailable());
        AlignmentEditPlan plan = assessment.plan().orElseThrow();
        Map<PrimitiveKey, DatasetPrimitiveState> before = snapshot(fixture.dataSet());
        var receipt = onEdt(() -> NetworkSnapshotCapture.captureBound(
                fixture.dataSet(), computed.captured().specification()));
        var command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), plan,
                new VisibleSourceLockedApplyValidator(
                        new LiveNetworkSnapshotValidator(receipt, plan,
                                () -> plan.before().sourceGeneration()),
                        new LiveBPreviewService(), computed.captured(), fixture::raster,
                        fixture.epoch(), () -> { }, message -> { }),
                "Apply two fixed-island intervals");
        UndoRedoHandler.getInstance().clean();

        onEdt(() -> { UndoRedoHandler.getInstance().add(command); return null; });

        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
        assertAppliedPreviewWays(plan, fixture.dataSet(), command);
        assertTrue(fixture.selected().getNodes().stream()
                .anyMatch(node -> node == fixture.fixedJunction()));
        Map<PrimitiveKey, DatasetPrimitiveState> after = snapshot(fixture.dataSet());
        PrimitiveKey fixedKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                fixture.fixedJunction().getUniqueId());
        assertEquals(before.get(fixedKey), after.get(fixedKey),
                "fixed junction identity, coordinate, tags, flags, and referrers stay exact");
        assertNotEquals(before, after);
        for (int cycle = 0; cycle < 20; cycle++) {
            onEdt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
            assertEquals(before, snapshot(fixture.dataSet()), "Undo cycle " + cycle);
            onEdt(() -> { UndoRedoHandler.getInstance().redo(); return null; });
            assertEquals(after, snapshot(fixture.dataSet()), "Redo cycle " + cycle);
            assertAppliedPreviewWays(plan, fixture.dataSet(), command);
        }
        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
        UndoRedoHandler.getInstance().clean();
    }

    @Test
    void T172_staleLiveIntervalPreviewAndApplyLeaveExactHostState() throws Exception {
        IntervalHostFixture fixture = intervalHostFixture();
        var computed = fixture.computed();
        var assessment = new FixedIntervalEditPlanComposer().compose(computed.intervalBatch(), Map.of());
        assertTrue(assessment.applyAvailable());
        AlignmentEditPlan plan = assessment.plan().orElseThrow();
        ApplyAlignmentEditPlanCommand command = intervalHostCommand(fixture, plan, message -> { });
        UndoRedoHandler.getInstance().clean();
        fixture.fixedJunction().setCoor(new LatLon(latitude(0.2), longitude(0)));
        Map<PrimitiveKey, DatasetPrimitiveState> staleState = snapshot(fixture.dataSet());

        assertThrows(IllegalStateException.class, () -> onEdt(() -> {
            new LiveBPreviewService().requireCurrent(fixture.dataSet(), computed.captured(),
                    fixture.raster());
            return null;
        }));
        assertThrows(IllegalStateException.class,
                () -> onEdt(() -> { UndoRedoHandler.getInstance().add(command); return null; }));

        assertEquals(staleState, snapshot(fixture.dataSet()));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void T173_staleIntervalRedoReportsVisibleFailureWithoutChangingHistoryOrDataset()
            throws Exception {
        IntervalHostFixture fixture = intervalHostFixture();
        var assessment = new FixedIntervalEditPlanComposer().compose(
                fixture.computed().intervalBatch(), Map.of());
        assertEquals(2, assessment.intervals().stream().filter(interval ->
                interval.disposition() == FixedIntervalEditPlanComposer.Disposition.CHANGED).count());
        AlignmentEditPlan plan = assessment.plan().orElseThrow();
        UndoRedoHandler.getInstance().clean();
        Node unrelated = new Node(new LatLon(latitude(10_000), longitude(10_000)));
        onEdt(() -> { UndoRedoHandler.getInstance().add(
                new AddCommand(fixture.dataSet(), unrelated)); return null; });
        AtomicReference<String> shown = new AtomicReference<>();
        ApplyAlignmentEditPlanCommand command = intervalHostCommand(fixture, plan,
                AlignWayAction.redoFailureReporter(shown::set));
        onEdt(() -> { UndoRedoHandler.getInstance().add(command); return null; });
        onEdt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
        Map<PrimitiveKey, DatasetPrimitiveState> beforeRedo = snapshot(fixture.dataSet());
        var prior = UndoRedoHandler.getInstance().getUndoCommands().get(0);
        fixture.epoch().sourceChanged();

        assertThrows(IllegalStateException.class,
                () -> onEdt(() -> { UndoRedoHandler.getInstance().redo(); return null; }));

        assertEquals(beforeRedo, snapshot(fixture.dataSet()));
        assertEquals(List.of(prior), UndoRedoHandler.getInstance().getUndoCommands());
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(shown.get() != null && shown.get().contains("Alignment Redo failed"),
                "failed Redo must show a safe user-visible message");
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty()
                || UndoRedoHandler.getInstance().getRedoCommands().equals(List.of(command)),
                "JOSM 19555 may consume only the attempted Redo entry");
        UndoRedoHandler.getInstance().clean();
    }

    @Test
    void T174_twoIntervalMutationFailureRollsBackExactHostState() throws Exception {
        IntervalHostFixture fixture = intervalHostFixture();
        var assessment = new FixedIntervalEditPlanComposer().compose(
                fixture.computed().intervalBatch(), Map.of());
        assertEquals(2, assessment.intervals().stream().filter(interval ->
                interval.disposition() == FixedIntervalEditPlanComposer.Disposition.CHANGED).count());
        AlignmentEditPlan plan = assessment.plan().orElseThrow();
        var receipt = onEdt(() -> NetworkSnapshotCapture.captureBound(
                fixture.dataSet(), fixture.computed().captured().specification()));
        var validator = new LiveNetworkSnapshotValidator(receipt, plan,
                () -> plan.before().sourceGeneration());
        Class<?> probeType = Class.forName(ApplyAlignmentEditPlanCommand.class.getName()
                + "$MutationProbe");
        Object probe = java.lang.reflect.Proxy.newProxyInstance(probeType.getClassLoader(),
                new Class<?>[] {probeType}, (ignored, method, arguments) -> {
                    if (method.getName().equals("reached")
                            && arguments[0].toString().equals("AFTER_REPLACE_WAYS")) {
                        throw new IntervalMutationFailure();
                    }
                    return null;
                });
        var constructor = ApplyAlignmentEditPlanCommand.class.getDeclaredConstructor(
                DataSet.class, AlignmentEditPlan.class, LiveNetworkSnapshotValidator.class,
                String.class, probeType);
        constructor.setAccessible(true);
        var command = (ApplyAlignmentEditPlanCommand) constructor.newInstance(
                fixture.dataSet(), plan, validator,
                "Fail two-interval mutation after way replacement", probe);
        Map<PrimitiveKey, DatasetPrimitiveState> before = snapshot(fixture.dataSet());
        UndoRedoHandler.getInstance().clean();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> onEdt(() -> { UndoRedoHandler.getInstance().add(command); return null; }));

        assertTrue(failure.getCause() instanceof IntervalMutationFailure);
        assertEquals(before, snapshot(fixture.dataSet()));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void T175_allFrozenLiveJunctionHasNoApplyCommand() throws Exception {
        IntervalHostFixture fixture = intervalHostFixture(9, 11);
        assertTrue(fixture.computed().partitioned());
        var state = new AlignWayAction.IntervalPreviewState(fixture.computed().intervalBatch());
        Map<PrimitiveKey, DatasetPrimitiveState> before = snapshot(fixture.dataSet());
        UndoRedoHandler.getInstance().clean();

        assertTrue(state.batch().partition().slideIntervals().isEmpty(),
                state.batch().partition().toString());
        assertFalse(state.applyAvailable());
        assertTrue(state.assessment().plan().isEmpty());
        assertThrows(IllegalStateException.class, state::currentPlanForApply);
        assertEquals(before, snapshot(fixture.dataSet()));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
    }

    @Test
    void T176_twoSeparatedManualJunctionsKeepBothFixedIslandsAndReceiversExact()
            throws Exception {
        MultiIntervalHostFixture fixture = multiIntervalHostFixture();
        assertTrue(fixture.computed().partitioned());
        var batch = fixture.computed().intervalBatch();
        assertEquals(2, batch.partition().fixedIslands().size(),
                batch.partition().toString());
        var assessment = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertTrue(assessment.applyAvailable(), assessment.intervals().toString());
        assertEquals(FixedIntervalEditPlanComposer.Disposition.CHANGED,
                assessment.intervals().get(0).disposition(), assessment.intervals().toString());
        assertEquals(FixedIntervalEditPlanComposer.Disposition.CHANGED,
                assessment.intervals().get(assessment.intervals().size() - 1).disposition(),
                assessment.intervals().toString());
        AlignmentEditPlan plan = assessment.plan().orElseThrow();
        Map<PrimitiveKey, DatasetPrimitiveState> before = snapshot(fixture.dataSet());
        var receipt = onEdt(() -> NetworkSnapshotCapture.captureBound(fixture.dataSet(),
                fixture.computed().captured().specification()));
        var validator = new LiveNetworkSnapshotValidator(receipt, plan,
                () -> fixture.epoch().captureStable().revision());
        var command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), plan, validator,
                "Apply intervals around two manual junctions");
        UndoRedoHandler.getInstance().clean();

        onEdt(() -> { UndoRedoHandler.getInstance().add(command); return null; });

        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
        assertAppliedPreviewWays(plan, fixture.dataSet(), command);
        Map<PrimitiveKey, DatasetPrimitiveState> after = snapshot(fixture.dataSet());
        assertNotEquals(before, after);
        var selectedBefore = (DetachedWay) batch.network().primitives().get(plan.selectedWayKey());
        for (int intervalIndex : List.of(0, batch.runs().size() - 1)) {
            var owned = batch.runs().get(intervalIndex).interval().range();
            boolean moved = java.util.stream.IntStream.rangeClosed(owned.firstIndex(),
                    owned.lastIndex()).anyMatch(occurrence -> {
                        PrimitiveKey key = selectedBefore.nodeKeys().get(occurrence);
                        return !before.get(key).coordinate().equals(after.get(key).coordinate());
                    });
            assertTrue(moved, "outer interval " + intervalIndex
                    + " must move an owned original node in the applied dataset");
        }
        for (var island : batch.partition().fixedIslands()) {
            for (int occurrence = island.range().firstIndex();
                    occurrence <= island.range().lastIndex(); occurrence++) {
                PrimitiveKey key = selectedBefore.nodeKeys().get(occurrence);
                assertEquals(before.get(key), after.get(key), "fixed island node " + key);
                Node original = fixture.nodes().get(occurrence);
                assertTrue(fixture.selected().getNodes().stream().anyMatch(node -> node == original),
                        "fixed occurrence identity " + occurrence);
            }
        }
        for (Way receiver : fixture.receivers()) {
            PrimitiveKey key = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                    receiver.getUniqueId());
            assertEquals(before.get(key), after.get(key), "fixed incident receiver " + key);
        }
        for (int cycle = 0; cycle < 20; cycle++) {
            onEdt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
            assertEquals(before, snapshot(fixture.dataSet()), "two-island Undo cycle " + cycle);
            onEdt(() -> { UndoRedoHandler.getInstance().redo(); return null; });
            assertEquals(after, snapshot(fixture.dataSet()), "two-island Redo cycle " + cycle);
            assertAppliedPreviewWays(plan, fixture.dataSet(), command);
        }
        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
        UndoRedoHandler.getInstance().clean();
    }

    @Test
    void T177_managedIntervalCommandUsesFrozenSourceReceiptForApplyAndRedo()
            throws Exception {
        IntervalHostFixture fixture = intervalHostFixture();
        List<Node> nodes = fixture.selected().getNodes();
        SelectionContext selection = new SelectionContext(fixture.selected(), 0, 20, nodes,
                Set.of(nodes.get(0), nodes.get(20)));
        LiveBPreviewService service = new LiveBPreviewService();
        var seed = onEdt(() -> service.captureManagedSeed(fixture.dataSet(), selection,
                managedConfig(), "managed-interval-host"));
        int size = 600;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            double distance = (y - 299.0) / 6.0;
            int gray = (int) Math.round(255.0 * (0.02 + 0.80
                    * Math.exp(-0.5 * distance * distance / 1.44)));
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, 0xff000000 | gray << 16 | gray << 8 | gray);
            }
        }
        boolean[] valid = new boolean[size * size];
        java.util.Arrays.fill(valid, true);
        double equator = Math.scalb(256.0, 15) / 2.0;
        var raster = new ManagedModernPreviewSource.Raster(image, valid,
                SupportedInputRasterTransform.webMercator(15, equator - size / 4.0,
                        equator - size / 4.0, 2.0),
                "hot", 15, "managed-interval-host",
                new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(0L));
        var captured = service.attachManagedRaster(seed, raster);
        var computed = service.compute(captured, CancellationProbe.NONE);
        assertTrue(computed.partitioned());
        var assessment = new FixedIntervalEditPlanComposer().compose(
                computed.intervalBatch(), Map.of());
        assertTrue(assessment.applyAvailable(), assessment.intervals().toString());
        assertEquals(1, assessment.intervals().stream().filter(interval ->
                interval.disposition() == FixedIntervalEditPlanComposer.Disposition.CHANGED).count(),
                assessment.intervals().toString());
        AlignmentEditPlan plan = assessment.plan().orElseThrow();
        var receipt = onEdt(() -> NetworkSnapshotCapture.captureBound(fixture.dataSet(),
                captured.specification()));
        java.util.concurrent.atomic.AtomicLong generation =
                new java.util.concurrent.atomic.AtomicLong(0L);
        var validator = new LiveNetworkSnapshotValidator(receipt, plan, generation::get);
        AtomicReference<String> shown = new AtomicReference<>();
        var command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), plan,
                new ManagedSourceLockedApplyValidator(validator, service, captured, () -> {
                    if (generation.get() != 0L) {
                        throw new IllegalStateException("Managed source generation changed");
                    }
                }, AlignWayAction.redoFailureReporter(shown::set)),
                "Apply managed fixed-island intervals");
        Map<PrimitiveKey, DatasetPrimitiveState> before = snapshot(fixture.dataSet());
        UndoRedoHandler.getInstance().clean();

        onEdt(() -> { UndoRedoHandler.getInstance().add(command); return null; });
        assertAppliedPreviewWays(plan, fixture.dataSet(), command);
        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
        onEdt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
        assertEquals(before, snapshot(fixture.dataSet()));
        generation.incrementAndGet();
        assertThrows(IllegalStateException.class,
                () -> onEdt(() -> { UndoRedoHandler.getInstance().redo(); return null; }));
        assertEquals(before, snapshot(fixture.dataSet()));
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(shown.get() != null && shown.get().contains("Alignment Redo failed"));
        UndoRedoHandler.getInstance().clean();
    }

    private static final class IntervalMutationFailure extends RuntimeException { }

    private static ApplyAlignmentEditPlanCommand intervalHostCommand(IntervalHostFixture fixture,
            AlignmentEditPlan plan, java.util.function.Consumer<String> redoReporter)
            throws Exception {
        var receipt = onEdt(() -> NetworkSnapshotCapture.captureBound(
                fixture.dataSet(), fixture.computed().captured().specification()));
        return new ApplyAlignmentEditPlanCommand(fixture.dataSet(), plan,
                new VisibleSourceLockedApplyValidator(
                        new LiveNetworkSnapshotValidator(receipt, plan,
                                () -> plan.before().sourceGeneration()),
                        new LiveBPreviewService(), fixture.computed().captured(), fixture::raster,
                        fixture.epoch(), () -> { }, redoReporter),
                "Apply two fixed-island intervals");
    }

    @Test
    void T170_actualAtomicCommandAppliesEveryReviewedPreviewWayExactly() throws Exception {
        assertAtomicCommandAppliesPreviewWaysExactly(reconstructionFixture(),
                junctionReconstructionRaster(true), true);
        assertAtomicCommandAppliesPreviewWaysExactly(selectedInteriorReconstructionFixture(),
                selectedInteriorConnectorRaster(), false);
    }

    @Test
    void T170_ambiguousReceiverEvidenceBlocksApplyWithoutChangingDatasetOrUndoHistory()
            throws Exception {
        JunctionFixture fixture = reconstructionFixture();
        Map<PrimitiveKey, DatasetPrimitiveState> before = snapshot(fixture.dataSet());
        List<org.openstreetmap.josm.command.Command> undoBefore = onEdt(() ->
                List.copyOf(UndoRedoHandler.getInstance().getUndoCommands()));
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(),
                junctionReconstructionRaster(ReceiverEvidence.AMBIGUOUS, 2.0),
                visibleConfig(), false, permissions));
        var computed = new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);
        var adapter = new ModernSingleWayEditPlanAdapter();
        var assessment = adapter.assess(computed, 0);

        boolean applyAttempted = false;
        if (assessment.applyAvailable()) {
            applyAttempted = true;
            AlignmentEditPlan plan = assessment.plan().orElseThrow();
            NetworkSnapshotCapture.CapturedSnapshot receipt = onEdt(() ->
                    NetworkSnapshotCapture.captureBound(
                            fixture.dataSet(), computed.captured().specification()));
            ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
                    fixture.dataSet(), plan,
                    new LiveNetworkSnapshotValidator(receipt, plan,
                            () -> plan.before().sourceGeneration()),
                    "Apply ambiguous receiver candidate");
            onEdt(() -> {
                UndoRedoHandler.getInstance().add(command);
                return null;
            });
        }

        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION,
                assessment.availability());
        assertEquals(ManualJunctionEligibility.Reason.MISSING_RECEIVER_EVIDENCE,
                assessment.manualReason());
        assertTrue(assessment.detail().contains(
                "Adjust this junction manually, then run alignment again."));
        assertFalse(assessment.applyAvailable());
        assertFalse(applyAttempted);
        assertEquals(before, snapshot(fixture.dataSet()));
        assertEquals(undoBefore, onEdt(() ->
                List.copyOf(UndoRedoHandler.getInstance().getUndoCommands())));
    }

    @Test
    void T170_oppositeOrientationSplitReceiverMovesOrdinaryMiddleExactlyOnce()
            throws Exception {
        DataSet dataSet = new DataSet();
        Node west = loadedNode(741, latitude(6), longitude(-8));
        Node junction = loadedNode(742, latitude(0), longitude(10));
        Node farSouth = loadedNode(743, latitude(-49), longitude(10));
        Node southPort = loadedNode(744, latitude(-31), longitude(10));
        Node south = loadedNode(745, latitude(-8), longitude(10));
        Node north = loadedNode(747, latitude(18), longitude(10));
        Node northPort = loadedNode(748, latitude(37), longitude(10));
        Node farNorth = loadedNode(749, latitude(49), longitude(10));
        Node middle = loadedNode(746, latitude(2), longitude(10));
        Way selected = loadedHighwayWay(751, west, junction);
        Way w1 = loadedHighwayWay(752, junction, south, southPort, farSouth);
        Way w2 = loadedHighwayWay(753, farNorth, northPort, north, middle, junction);
        for (Node node : List.of(west, junction, farSouth, southPort, south, middle,
                north, northPort, farNorth)) {
            dataSet.addPrimitive(node);
        }
        for (Way way : List.of(selected, w1, w2)) {
            dataSet.addPrimitive(way);
        }
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                selected.getNodes(), Set.of());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection,
                junctionReconstructionRaster(ReceiverEvidence.COMPLETE, 6.0),
                visibleConfig(), false, permissions));
        var computed = new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);
        assertManualJunctionWithoutMutation(dataSet, computed,
                ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS);
    }

    private static void assertAtomicCommandAppliesPreviewWaysExactly(JunctionFixture fixture,
            LiveBPreviewService.VisibleRaster raster, boolean requireSimpleT)
            throws Exception {
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), raster,
                visibleConfig(), false, permissions));
        if (requireSimpleT) {
            assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                    ManualJunctionEligibility.evaluate(captured[0].network(),
                            captured[0].specification()).reason());
        }
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        assertTrue(plan.validation().applicable(), plan.validation()::toString);
        Map<PrimitiveKey, List<Node>> originalWays = plan.finalPreviewWays().keySet().stream()
                .collect(java.util.stream.Collectors.toMap(key -> key, key -> List.copyOf(
                        ((Way) fixture.dataSet().getPrimitiveById(
                                key.id(), OsmPrimitiveType.WAY)).getNodes())));
        Map<PrimitiveKey, List<LatLon>> originalCoordinates = originalWays.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(node -> new LatLon(node.lat(), node.lon())).toList()));
        Map<PrimitiveKey, DatasetPrimitiveState> originalState = snapshot(fixture.dataSet());
        NetworkSnapshotCapture.CapturedSnapshot receipt = onEdt(() ->
                NetworkSnapshotCapture.captureBound(
                        fixture.dataSet(), computed.captured().specification()));
        LiveNetworkSnapshotValidator validator = new LiveNetworkSnapshotValidator(
                receipt, plan, () -> plan.before().sourceGeneration());
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), plan, validator, "Apply reviewed all-way alignment");

        onEdt(command::executeCommand);

        assertEquals(Set.of(computed.request().selectedWayKey(),
                PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                        fixture.receiver().getUniqueId())), plan.finalPreviewWays().keySet());
        for (Map.Entry<PrimitiveKey, List<org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint>>
                entry : plan.finalPreviewWays().entrySet()) {
            Way applied = (Way) fixture.dataSet().getPrimitiveById(
                    entry.getKey().id(), OsmPrimitiveType.WAY);
            List<LatLon> expected = entry.getValue().stream()
                    .map(point -> new LatLon(point.latitudeDegrees(),
                            point.longitudeDegrees())).toList();
            List<LatLon> actual = applied.getNodes().stream()
                    .map(node -> new LatLon(node.lat(), node.lon())).toList();
            assertNotEquals(originalCoordinates.get(entry.getKey()), expected,
                    "T170 fixture must materially change " + entry.getKey());
            assertEquals(expected, actual, entry.getKey().toString());
        }
        assertAppliedPreviewWays(plan, fixture.dataSet(), command);

        onEdt(() -> {
            command.undoCommand();
            return null;
        });
        originalWays.forEach((key, nodes) -> assertEquals(nodes,
                ((Way) fixture.dataSet().getPrimitiveById(key.id(), OsmPrimitiveType.WAY))
                        .getNodes(), key.toString()));
        originalCoordinates.forEach((key, coordinates) -> assertEquals(coordinates,
                ((Way) fixture.dataSet().getPrimitiveById(key.id(), OsmPrimitiveType.WAY))
                .getNodes().stream()
                .map(node -> new LatLon(node.lat(), node.lon())).toList(), key.toString()));
        assertEquals(originalState, snapshot(fixture.dataSet()));
    }

    private static void assertManualJunctionWithoutMutation(DataSet dataSet,
            LiveBPreviewService.Computed computed, ManualJunctionEligibility.Reason reason)
            throws Exception {
        Map<PrimitiveKey, DatasetPrimitiveState> before = snapshot(dataSet);
        List<org.openstreetmap.josm.command.Command> undoBefore = onEdt(() ->
                List.copyOf(UndoRedoHandler.getInstance().getUndoCommands()));
        var decision = ManualJunctionEligibility.evaluate(computed.captured().network(),
                computed.captured().specification());
        assertEquals(reason, decision.reason());
        if (computed.partitioned()) {
            var partition = computed.intervalBatch().partition();
            assertTrue(partition.junctionDispositions().stream().anyMatch(disposition ->
                    disposition.reason() == reason && !disposition.automaticEligible()));
            var preview = new org.openstreetmap.josm.plugins.wayheatmaptracer.actions
                    .AlignWayAction.IntervalPreviewState(computed.intervalBatch());
            assertFalse(preview.applyAvailable(),
                    "a short all-frozen manual junction has no interval Apply plan");
            assertEquals(before, snapshot(dataSet));
            assertEquals(undoBefore, onEdt(() -> List.copyOf(
                    UndoRedoHandler.getInstance().getUndoCommands())));
            return;
        }
        var assessment = new ModernSingleWayEditPlanAdapter().assess(computed, 0);
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION,
                assessment.availability(), assessment.detail());
        assertFalse(assessment.applyAvailable());
        assertTrue(assessment.plan().isEmpty());
        assertTrue(assessment.detail().contains(
                "Adjust this junction manually, then run alignment again."));
        assertEquals(before, snapshot(dataSet));
        assertEquals(undoBefore, onEdt(() ->
                List.copyOf(UndoRedoHandler.getInstance().getUndoCommands())));
    }

    private static void assertAtomicApplyAndUndoMatchesPreview(DataSet dataSet,
            AlignmentEditPlan plan, LiveBPreviewService.Computed computed) throws Exception {
        Map<PrimitiveKey, DatasetPrimitiveState> before = snapshot(dataSet);
        NetworkSnapshotCapture.CapturedSnapshot receipt = onEdt(() ->
                NetworkSnapshotCapture.captureBound(dataSet, computed.captured().specification()));
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(dataSet, plan,
                new LiveNetworkSnapshotValidator(receipt, plan,
                        () -> plan.before().sourceGeneration()), "Apply opposite split receiver");

        onEdt(command::executeCommand);
        assertAppliedPreviewWays(plan, dataSet, command);
        onEdt(() -> {
            command.undoCommand();
            return null;
        });
        assertEquals(before, snapshot(dataSet), "Undo must restore all primitive state");
    }

    private static void assertAppliedPreviewWays(AlignmentEditPlan plan, DataSet dataSet,
            ApplyAlignmentEditPlanCommand command) {
        Map<PrimitiveKey, Long> actualNodeIds = new java.util.HashMap<>();
        plan.before().primitives().keySet().stream()
                .filter(key -> key.type() == PrimitiveKey.Type.NODE)
                .forEach(key -> actualNodeIds.put(key, key.id()));
        List<PrimitiveKey> createdKeys = plan.createdPrimitives().keySet().stream()
                .filter(key -> key.type() == PrimitiveKey.Type.NODE).sorted().toList();
        Set<Long> existingNodeIds = plan.before().primitives().keySet().stream()
                .filter(key -> key.type() == PrimitiveKey.Type.NODE)
                .map(PrimitiveKey::id).collect(java.util.stream.Collectors.toSet());
        List<Node> createdNodes = command.getParticipatingPrimitives().stream()
                .filter(Node.class::isInstance).map(Node.class::cast)
                .filter(node -> !existingNodeIds.contains(node.getUniqueId())).toList();
        assertEquals(createdKeys.size(), createdNodes.size(), "command-owned preview nodes");
        List<Node> unmatchedCreated = new java.util.ArrayList<>(createdNodes);
        for (PrimitiveKey key : createdKeys) {
            DetachedNode planned = (DetachedNode) plan.after().primitives().get(key);
            LatLon expectedCoordinate = new LatLon(planned.coordinate().latitudeDegrees(),
                    planned.coordinate().longitudeDegrees());
            List<Node> matches = unmatchedCreated.stream()
                    .filter(node -> new LatLon(node.lat(), node.lon()).equals(expectedCoordinate))
                    .toList();
            assertEquals(1, matches.size(), key + " must map to one created JOSM node");
            Node node = matches.get(0);
            unmatchedCreated.remove(node);
            actualNodeIds.put(key, node.getUniqueId());
            assertEquals(new LatLon(planned.coordinate().latitudeDegrees(),
                            planned.coordinate().longitudeDegrees()),
                    new LatLon(node.lat(), node.lon()), key + " created-node coordinate");
        }
        assertTrue(unmatchedCreated.isEmpty(), "every created JOSM node maps to one preview key");
        assertEquals(createdNodes.size(), createdNodes.stream().map(Node::getUniqueId).distinct().count(),
                "created JOSM IDs must be unique");
        for (Map.Entry<PrimitiveKey, List<org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint>>
                entry : plan.finalPreviewWays().entrySet()) {
            Way applied = (Way) dataSet.getPrimitiveById(entry.getKey().id(), OsmPrimitiveType.WAY);
            DetachedWay planned = (DetachedWay) plan.after().primitives().get(entry.getKey());
            assertEquals(planned.nodeKeys().stream().map(key -> actualNodeIds.get(key)).toList(),
                    applied.getNodes().stream().map(Node::getUniqueId).toList(),
                    entry.getKey() + " preview node identity/order");
            assertEquals(entry.getValue().stream()
                    .map(point -> new LatLon(point.latitudeDegrees(), point.longitudeDegrees()))
                    .toList(), applied.getNodes().stream()
                            .map(node -> new LatLon(node.lat(), node.lon())).toList(),
                    entry.getKey() + " preview coordinates/order");
        }
    }

    private static Map<PrimitiveKey, DatasetPrimitiveState> snapshot(DataSet dataSet) {
        Map<PrimitiveKey, DatasetPrimitiveState> state = new java.util.TreeMap<>();
        for (OsmPrimitive primitive : dataSet.allPrimitives()) {
            PrimitiveKey key = PrimitiveKey.existing(
                    PrimitiveKey.Type.valueOf(primitive.getType().name()),
                    primitive.getUniqueId());
            LatLon coordinate = primitive instanceof Node node
                    ? new LatLon(node.lat(), node.lon()) : null;
            List<Long> nodeIds = primitive instanceof Way way
                    ? way.getNodes().stream().map(Node::getUniqueId).toList() : List.of();
            List<String> members = primitive instanceof Relation relation
                    ? relation.getMembers().stream().map(member -> member.getRole() + ":"
                            + member.getMember().getType().name() + ":"
                            + member.getMember().getUniqueId()).toList() : List.of();
            Set<PrimitiveKey> referrers = primitive.getReferrers().stream()
                    .map(referrer -> PrimitiveKey.existing(
                            PrimitiveKey.Type.valueOf(referrer.getType().name()),
                            referrer.getUniqueId()))
                    .collect(java.util.stream.Collectors.toSet());
            state.put(key, new DatasetPrimitiveState(coordinate, nodeIds, members, referrers,
                    Map.copyOf(new java.util.TreeMap<>(primitive.getKeys())),
                    primitive.isModified(), primitive.isDeleted()));
        }
        return Map.copyOf(state);
    }

    private record DatasetPrimitiveState(LatLon coordinate, List<Long> nodeIds,
            List<String> relationMembers, Set<PrimitiveKey> referrers,
            Map<String, String> tags, boolean modified,
            boolean deleted) {
    }

    private record IntervalHostFixture(DataSet dataSet, Way selected, Node fixedJunction,
            LiveBPreviewService.VisibleRaster raster, VisibleSourceEpoch epoch,
            LiveBPreviewService.Computed computed) { }

    private record MultiIntervalHostFixture(DataSet dataSet, Way selected, List<Node> nodes,
            List<Way> receivers, LiveBPreviewService.VisibleRaster raster,
            VisibleSourceEpoch epoch, LiveBPreviewService.Computed computed) { }

    private static MultiIntervalHostFixture multiIntervalHostFixture() throws Exception {
        DataSet dataSet = new DataSet();
        List<Node> nodes = new java.util.ArrayList<>();
        for (int index = 0; index <= 40; index++) {
            Node node = loadedNode(900 + index, latitude(0.4 * Math.sin(index * 0.5)),
                    longitude(-100 + 5.0 * index));
            nodes.add(node);
            dataSet.addPrimitive(node);
        }
        Way selected = loadedHighwayWay(950, nodes.toArray(Node[]::new));
        dataSet.addPrimitive(selected);
        List<Way> receivers = new java.util.ArrayList<>();
        for (int occurrence : List.of(13, 27)) {
            nodes.get(occurrence).put("highway", "traffic_signals");
            double east = -100 + 5.0 * occurrence;
            Node south = loadedNode(960 + occurrence, latitude(-40), longitude(east));
            Node north = loadedNode(980 + occurrence, latitude(40), longitude(east));
            dataSet.addPrimitive(south);
            dataSet.addPrimitive(north);
            Way receiver = loadedHighwayWay(1_000 + occurrence, south, nodes.get(occurrence), north);
            dataSet.addPrimitive(receiver);
            receivers.add(receiver);
        }
        SelectionContext selection = new SelectionContext(selected, 0, 40, nodes,
                Set.of(nodes.get(0), nodes.get(40)));
        int width = 1200;
        int height = 400;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            double distance = (y - 200.0) / 5.0;
            double intensity = 0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44);
            int gray = (int) Math.round(255.0 * intensity);
            java.util.Arrays.fill(argb, y * width, (y + 1) * width,
                    0xff000000 | gray << 16 | gray << 8 | gray);
        }
        var epoch = new VisibleSourceEpoch();
        var raster = new LiveBPreviewService.VisibleRaster(width, height, argb,
                -120, -40, 120, 40, 1.2, 1.2, OptionalDouble.of(1.0),
                "two-junction-host-visible", "EPSG:3857", epoch.captureStable());
        var captured = onEdt(() -> new LiveBPreviewService().capture(dataSet, selection,
                raster, visibleConfig()));
        var computed = new LiveBPreviewService().compute(captured, CancellationProbe.NONE);
        return new MultiIntervalHostFixture(dataSet, selected, List.copyOf(nodes),
                List.copyOf(receivers), raster, epoch, computed);
    }

    private static IntervalHostFixture intervalHostFixture() throws Exception {
        return intervalHostFixture(0, 20);
    }

    private static IntervalHostFixture intervalHostFixture(int first, int last) throws Exception {
        DataSet dataSet = new DataSet();
        List<Node> nodes = new java.util.ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            Node node = loadedNode(800 + i, latitude(0.4 * Math.sin(i * 0.5)),
                    longitude(-50 + 5.0 * i));
            nodes.add(node);
            dataSet.addPrimitive(node);
        }
        nodes.get(10).put("highway", "traffic_signals");
        nodes.get(4).put("note", "fixed west arm boundary");
        nodes.get(16).put("note", "fixed east arm boundary");
        nodes.get(17).put("note", "second fixed east arm boundary");
        Way selected = loadedHighwayWay(850, nodes.toArray(Node[]::new));
        dataSet.addPrimitive(selected);
        Node south = loadedNode(851, latitude(-40), longitude(0));
        Node north = loadedNode(852, latitude(40), longitude(0));
        dataSet.addPrimitive(south);
        dataSet.addPrimitive(north);
        Way receiver = loadedHighwayWay(853, south, nodes.get(10), north);
        dataSet.addPrimitive(receiver);
        SelectionContext selection = new SelectionContext(selected, first, last,
                nodes.subList(first, last + 1), Set.of(nodes.get(first), nodes.get(last)));
        int width = 800;
        int height = 400;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            double distance = (y - 200.0) / 5.0;
            double intensity = 0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44);
            int gray = (int) Math.round(255.0 * intensity);
            java.util.Arrays.fill(argb, y * width, (y + 1) * width,
                    0xff000000 | gray << 16 | gray << 8 | gray);
        }
        var epoch = new VisibleSourceEpoch();
        var raster = new LiveBPreviewService.VisibleRaster(width, height, argb,
                -80, -40, 80, 40, 1.2, 1.2, OptionalDouble.of(1.0),
                "interval-host-visible", "EPSG:3857", epoch.captureStable());
        var captured = onEdt(() -> new LiveBPreviewService().capture(dataSet, selection,
                raster, visibleConfig()));
        var computed = new LiveBPreviewService().compute(captured, CancellationProbe.NONE);
        return new IntervalHostFixture(dataSet, selected, nodes.get(10), raster, epoch, computed);
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

    private static <T> T onEdt(java.util.concurrent.Callable<T> operation) throws Exception {
        java.util.concurrent.atomic.AtomicReference<T> value =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(operation.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() instanceof Exception exception) {
            throw exception;
        }
        if (failure.get() instanceof Error error) {
            throw error;
        }
        return value.get();
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
        Way selected = loadedHighwayWay(10, west, junction);
        Way receiver = loadedHighwayWay(11, farSouth, southPort, south, junction, middle, north,
                northPort, farNorth);
        for (Node node : List.of(west, junction, farSouth, southPort, south, middle, north,
                northPort, farNorth)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        if (crossing) {
            Node crossingSouth = loadedNode(20, latitude(-10), longitude(1));
            Node crossingNorth = loadedNode(21, latitude(10), longitude(1));
            dataSet.addPrimitive(crossingSouth);
            dataSet.addPrimitive(crossingNorth);
            dataSet.addPrimitive(loadedWay(12, crossingSouth, crossingNorth));
        }
        return new JunctionFixture(dataSet,
                new SelectionContext(selected, 0, 1, List.of(west, junction), Set.of()),
                receiver, west, junction, south, middle, north);
    }

    private static JunctionFixture incidentFoldbackFixture() {
        DataSet dataSet = new DataSet();
        Node west = loadedNode(501, 0.0, longitude(-8));
        Node junction = loadedNode(502, 0.0, longitude(8));
        Node south = loadedNode(503, latitude(-8), longitude(10));
        Node north = loadedNode(504, latitude(1), longitude(10));
        Way selected = loadedHighwayWay(510, west, junction);
        Way receiver = loadedHighwayWay(511, south, junction, north);
        for (Node node : List.of(west, junction, south, north)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        return new JunctionFixture(dataSet,
                new SelectionContext(selected, 0, 1, List.of(west, junction), Set.of()),
                receiver, west, junction, south, junction, north);
    }

    private static JunctionFixture reconstructionFixture() {
        return reconstructionFixture(false);
    }

    private static JunctionFixture reconstructionFixture(boolean protectMiddle) {
        DataSet dataSet = new DataSet();
        Node west = loadedNode(101, 0.0, longitude(-8));
        Node junction = loadedNode(102, 0.0, longitude(8));
        Node farSouth = loadedNode(103, latitude(-49), longitude(10));
        Node southPort = loadedNode(104, latitude(-31), longitude(10));
        Node south = loadedNode(105, latitude(-8), longitude(10));
        Node middle = loadedNode(106, latitude(8), longitude(10));
        if (protectMiddle) {
            middle.put("barrier", "gate");
        }
        Node north = loadedNode(107, latitude(18), longitude(10));
        Node northPort = loadedNode(108, latitude(31), longitude(10));
        Node farNorth = loadedNode(109, latitude(49), longitude(10));
        Way selected = loadedHighwayWay(110, west, junction);
        Way receiver = loadedHighwayWay(111, farSouth, southPort, south, junction, middle, north,
                northPort, farNorth);
        for (Node node : List.of(west, junction, farSouth, southPort, south, middle, north,
                northPort, farNorth)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        return new JunctionFixture(dataSet,
                new SelectionContext(selected, 0, 1, List.of(west, junction), Set.of()),
                receiver, west, junction, south, middle, north);
    }

    private static JunctionFixture orderInversionFixture() {
        DataSet dataSet = new DataSet();
        Node west = loadedNode(201, 0.0, longitude(-8));
        Node junction = loadedNode(202, 0.0, longitude(8));
        Node farSouth = loadedNode(203, latitude(-49), longitude(10));
        Node southPort = loadedNode(204, latitude(-31), longitude(10));
        Node south = loadedNode(205, latitude(-8), longitude(10));
        Node middle = loadedNode(206, latitude(3), longitude(10));
        Node north = loadedNode(207, latitude(18), longitude(10));
        Node northPort = loadedNode(208, latitude(31), longitude(10));
        Node farNorth = loadedNode(209, latitude(49), longitude(10));
        Way selected = loadedHighwayWay(210, west, junction);
        Way receiver = loadedHighwayWay(211, farSouth, southPort, south, junction, middle, north,
                northPort, farNorth);
        for (Node node : List.of(west, junction, farSouth, southPort, south, middle, north,
                northPort, farNorth)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        return new JunctionFixture(dataSet,
                new SelectionContext(selected, 0, 1, List.of(west, junction), Set.of()),
                receiver, west, junction, south, middle, north);
    }

    private static JunctionFixture selectedInteriorReconstructionFixture() {
        DataSet dataSet = new DataSet();
        Node west = loadedNode(301, 0.0, longitude(-8));
        Node junction = loadedNode(302, 0.0, longitude(8));
        Node selectedContinuation = loadedNode(303, 0.0, longitude(18));
        Node farSouth = loadedNode(304, latitude(-49), longitude(10));
        Node southPort = loadedNode(305, latitude(-31), longitude(10));
        Node south = loadedNode(306, latitude(-8), longitude(10));
        Node middle = loadedNode(307, latitude(8), longitude(10));
        Node north = loadedNode(308, latitude(18), longitude(10));
        Node northPort = loadedNode(309, latitude(31), longitude(10));
        Node farNorth = loadedNode(310, latitude(49), longitude(10));
        Way selected = loadedHighwayWay(310, west, junction, selectedContinuation);
        Way receiver = loadedHighwayWay(311, farSouth, southPort, south, junction, middle, north,
                northPort, farNorth);
        for (Node node : List.of(west, junction, selectedContinuation, farSouth, southPort,
                south, middle, north, northPort, farNorth)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        return new JunctionFixture(dataSet,
                new SelectionContext(selected, 0, 1, List.of(west, junction), Set.of()),
                receiver, west, junction, south, middle, north);
    }

    private static JunctionFixture displacedInteriorJunctionFixture() {
        DataSet dataSet = new DataSet();
        Node west = loadedNode(601, latitude(12), longitude(-8));
        Node junction = loadedNode(602, latitude(6), longitude(8));
        Node east = loadedNode(603, latitude(12), longitude(18));
        Node farSouth = loadedNode(604, latitude(-49), longitude(10));
        Node southPort = loadedNode(605, latitude(-31), longitude(10));
        Node south = loadedNode(606, latitude(-8), longitude(10));
        Node middle = loadedNode(607, latitude(8), longitude(10));
        Node north = loadedNode(608, latitude(18), longitude(10));
        Node northPort = loadedNode(609, latitude(37), longitude(10));
        Node farNorth = loadedNode(610, latitude(49), longitude(10));
        Way selected = loadedHighwayWay(611, west, junction, east);
        Way receiver = loadedHighwayWay(612, farSouth, southPort, south, junction, middle, north,
                northPort, farNorth);
        for (Node node : List.of(west, junction, east, farSouth, southPort,
                south, middle, north, northPort, farNorth)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        return new JunctionFixture(dataSet,
                new SelectionContext(selected, 0, 2, selected.getNodes(), Set.of()),
                receiver, west, junction, south, middle, north);
    }

    private static LiveBPreviewService.VisibleRaster visibleRaster() {
        return visibleRaster(600, 50.0, 288.0);
    }

    private static LiveBPreviewService.VisibleRaster junctionRaster() {
        return visibleRaster(720, 60.0, 348.0);
    }

    private static LiveBPreviewService.VisibleRaster junctionReconstructionRaster(
            boolean includeReceiver) {
        return junctionReconstructionRaster(includeReceiver
                ? ReceiverEvidence.COMPLETE : ReceiverEvidence.NONE, 2.0);
    }

    private static LiveBPreviewService.VisibleRaster junctionReconstructionRaster(
            ReceiverEvidence receiverEvidence, double selectedNorthMeters) {
        return junctionReconstructionRaster(receiverEvidence, selectedNorthMeters, false);
    }

    private static LiveBPreviewService.VisibleRaster junctionMultipleReceiverRaster() {
        return junctionReconstructionRaster(ReceiverEvidence.COMPLETE, 2.0, true);
    }

    private static LiveBPreviewService.VisibleRaster junctionCoupledRaster() {
        return junctionReconstructionRaster(ReceiverEvidence.COMPLETE, 2.0, false, true);
    }

    private static LiveBPreviewService.VisibleRaster junctionReconstructionRaster(
            ReceiverEvidence receiverEvidence, double selectedNorthMeters,
            boolean includeDiagonal) {
        return junctionReconstructionRaster(receiverEvidence, selectedNorthMeters,
                includeDiagonal, false);
    }

    private static LiveBPreviewService.VisibleRaster junctionReconstructionRaster(
            ReceiverEvidence receiverEvidence, double selectedNorthMeters,
            boolean includeDiagonal, boolean includeLeftReceiver) {
        return junctionReconstructionRaster(receiverEvidence, selectedNorthMeters,
                includeDiagonal, includeLeftReceiver, false);
    }

    private static LiveBPreviewService.VisibleRaster selectedInteriorConnectorRaster() {
        return junctionReconstructionRaster(ReceiverEvidence.COMPLETE, 2.0,
                false, false, true);
    }

    private static LiveBPreviewService.VisibleRaster junctionReconstructionRaster(
            ReceiverEvidence receiverEvidence, double selectedNorthMeters,
            boolean includeDiagonal, boolean includeLeftReceiver,
            boolean includeSelectedInteriorConnector) {
        int size = 720;
        double extent = 60.0;
        int[] argb = new int[size * size];
        double selectedRow = (extent - selectedNorthMeters)
                * RenderedHeatmapSampler.RASTER_SCALE;
        double receiverColumn = (extent + 8.0) * RenderedHeatmapSampler.RASTER_SCALE;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double selectedDistance = (y - selectedRow) / RenderedHeatmapSampler.RASTER_SCALE;
                double receiverDistance = (x - receiverColumn) / RenderedHeatmapSampler.RASTER_SCALE;
                double leftReceiverDistance = (x - (extent - 8.0)
                        * RenderedHeatmapSampler.RASTER_SCALE)
                        / RenderedHeatmapSampler.RASTER_SCALE;
                double selected = Math.exp(-0.5 * selectedDistance * selectedDistance / 1.44);
                double receiver = switch (receiverEvidence) {
                    case NONE -> 0.0;
                    case COMPLETE -> Math.exp(-0.5 * receiverDistance * receiverDistance / 1.44);
                    case NORTH_ONLY -> y < selectedRow - 6.0
                            ? Math.exp(-0.5 * receiverDistance * receiverDistance / 1.44) : 0.0;
                    case AMBIGUOUS -> Math.max(
                            Math.exp(-0.5 * receiverDistance * receiverDistance / 1.44),
                            Math.exp(-0.5 * Math.pow((x - 432.0)
                                    / RenderedHeatmapSampler.RASTER_SCALE, 2.0) / 1.44));
                };
                double worldNorth = extent - y / RenderedHeatmapSampler.RASTER_SCALE;
                double diagonalEast = 9.0 - 0.8
                        * Math.max(-18.0, Math.min(18.0, worldNorth));
                double diagonalDistance = (x / RenderedHeatmapSampler.RASTER_SCALE
                        - extent - diagonalEast) / Math.sqrt(1.64);
                double diagonal = includeDiagonal
                        ? Math.exp(-0.5 * diagonalDistance * diagonalDistance / 1.44) : 0.0;
                double worldEast = x / RenderedHeatmapSampler.RASTER_SCALE - extent;
                double connectorFraction = Math.max(0.0, Math.min(1.0,
                        ((worldEast - 8.0) * 10.0 - (worldNorth - 2.0) * 2.0) / 104.0));
                double selectedConnector = includeSelectedInteriorConnector
                        ? Math.exp(-0.5 * Math.pow(Math.hypot(
                                worldEast - 8.0 - 10.0 * connectorFraction,
                                worldNorth - 2.0 + 2.0 * connectorFraction) / 1.2, 2)) : 0.0;
                double leftReceiver = includeLeftReceiver
                        ? Math.exp(-0.5 * leftReceiverDistance * leftReceiverDistance / 1.44)
                        : 0.0;
                int gray = (int) Math.round(255.0 * (0.02 + 0.80
                        * Math.max(selected, Math.max(receiver,
                                Math.max(Math.max(diagonal, selectedConnector), leftReceiver)))));
                argb[y * size + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        return new LiveBPreviewService.VisibleRaster(size, size, argb, -extent, -extent,
                extent, extent, 1.0, 1.0, OptionalDouble.of(1.0),
                "visible-junction-" + receiverEvidence.name().toLowerCase()
                    + "-" + selectedNorthMeters
                    + (includeSelectedInteriorConnector ? "-selected-connector" : ""),
                "EPSG:3857");
    }

    private static LiveBPreviewService.VisibleRaster terminalExtensionRaster(
            boolean selectedContinues, boolean nearbyParallelRidge) {
        return terminalExtensionRaster(selectedContinues, nearbyParallelRidge, false, false);
    }

    private static LiveBPreviewService.VisibleRaster terminalExtensionRaster(
            boolean selectedContinues, boolean nearbyParallelRidge, boolean prefixPortPresent,
            boolean prefixConnectorMeasured) {
        return terminalExtensionRaster(selectedContinues, nearbyParallelRidge,
                prefixPortPresent, prefixConnectorMeasured, 15.0);
    }

    private static LiveBPreviewService.VisibleRaster terminalExtensionRaster(
            boolean selectedContinues, boolean nearbyParallelRidge, boolean prefixPortPresent,
            boolean prefixConnectorMeasured, double connectorEndEast) {
        int size = 720;
        double extent = 60.0;
        int[] argb = new int[size * size];
        for (int y = 0; y < size; y++) {
            double north = extent - y / RenderedHeatmapSampler.RASTER_SCALE;
            for (int x = 0; x < size; x++) {
                double east = x / RenderedHeatmapSampler.RASTER_SCALE - extent;
                double selected = selectedContinues || east <= 11.0
                        ? Math.exp(-0.5 * Math.pow(north / 1.2, 2)) : 0.0;
                double parallel = nearbyParallelRidge && east > 11.0
                        ? Math.exp(-0.5 * Math.pow((north - 0.05) / 1.2, 2)) : 0.0;
                double prefix = prefixPortPresent && north <= 0.0 && north >= -20.0
                        ? Math.exp(-0.5 * Math.pow((east - 10.0) / 1.2, 2)) : 0.0;
                double connectorEast = connectorEndEast - 10.0;
                double connectorSquaredLength = connectorEast * connectorEast + 400.0;
                double diagonalFraction = Math.max(0.0, Math.min(1.0,
                        ((east - 10.0) * connectorEast + (north + 20.0) * 20.0)
                                / connectorSquaredLength));
                double diagonal = prefixConnectorMeasured
                        ? Math.exp(-0.5 * Math.pow(Math.hypot(
                                east - 10.0 - connectorEast * diagonalFraction,
                                north + 20.0 - 20.0 * diagonalFraction) / 1.2, 2)) : 0.0;
                double receiver = Math.exp(-0.5 * Math.pow((east - 25.0) / 1.2, 2));
                int gray = (int) Math.round(255.0
                        * (0.02 + 0.80 * Math.max(Math.max(Math.max(selected, parallel),
                                Math.max(prefix, diagonal)), receiver)));
                argb[y * size + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        return new LiveBPreviewService.VisibleRaster(size, size, argb, -extent, -extent,
                extent, extent, 1.0, 1.0, OptionalDouble.of(1.0),
                "visible-selected-terminal-" + selectedContinues + "-parallel-"
                        + nearbyParallelRidge + "-connector-" + prefixConnectorMeasured,
                "EPSG:3857");
    }

    private static LiveBPreviewService.VisibleRaster multiplyCrossedReceiverRaster() {
        int size = 960;
        double extent = 80.0;
        double[][] path = {{-45, -18}, {-35, -15}, {-20, -10}, {-5, -4},
                {10, 0}, {14, 5}, {24, -5}, {35, 20}, {45, 28}, {50, 30}};
        int[] argb = new int[size * size];
        for (int y = 0; y < size; y++) {
            double north = extent - y / RenderedHeatmapSampler.RASTER_SCALE;
            for (int x = 0; x < size; x++) {
                double east = x / RenderedHeatmapSampler.RASTER_SCALE - extent;
                double receiverDistance = Double.POSITIVE_INFINITY;
                for (int segment = 1; segment < path.length; segment++) {
                    double[] a = path[segment - 1];
                    double[] b = path[segment];
                    double dx = b[0] - a[0];
                    double dy = b[1] - a[1];
                    double fraction = Math.max(0.0, Math.min(1.0,
                            ((east - a[0]) * dx + (north - a[1]) * dy)
                                    / (dx * dx + dy * dy)));
                    receiverDistance = Math.min(receiverDistance,
                            Math.hypot(east - a[0] - fraction * dx,
                                    north - a[1] - fraction * dy));
                }
                double selected = Math.exp(-0.5 * Math.pow(north / 1.2, 2));
                double receiver = Math.exp(-0.5 * Math.pow(receiverDistance / 1.2, 2));
                int gray = (int) Math.round(255.0 * (0.02 + 0.80 * Math.max(selected, receiver)));
                argb[y * size + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        return new LiveBPreviewService.VisibleRaster(size, size, argb, -extent, -extent,
                extent, extent, 1.0, 1.0, OptionalDouble.of(1.0),
                "visible-multiply-crossed-receiver", "EPSG:3857");
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

    private static Way loadedHighwayWay(long id, Node... nodes) {
        Way way = loadedWay(id, nodes);
        way.put("highway", "path");
        way.setModified(false);
        return way;
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

    private enum ReceiverEvidence { NONE, COMPLETE, NORTH_ONLY, AMBIGUOUS }
}

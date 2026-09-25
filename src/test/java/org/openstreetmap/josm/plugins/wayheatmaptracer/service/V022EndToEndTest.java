package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ApplyAlignmentEditPlanCommand;
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
            ValidationReport.Disposition expectedDisposition = policy
                    == JunctionPolicy.LEGACY_BOUNDED_MOVE
                    ? ValidationReport.Disposition.HARD_BLOCKED
                    : ValidationReport.Disposition.REVIEW_REQUIRED;
            assertEquals(expectedDisposition, plans.get(policy).validation().disposition(),
                    () -> policy + ": " + plans.get(policy).validation().findingCodes());
            assertTrue(plans.get(policy).validation().findingCodes()
                    .contains("network-review-required"));
            if (policy == JunctionPolicy.LEGACY_BOUNDED_MOVE) {
                assertTrue(plans.get(policy).validation().findingCodes()
                        .contains("final-topology:VERTEX_TOUCH"));
                assertTrue(plans.get(policy).validation().findingCodes()
                        .contains("final-topology:COLLINEAR_OVERLAP"));
            }
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
        LiveBPreviewService.Computed computed = compute(junctionFixture(0.0, true),
                JunctionPolicy.REATTACH);
        ModernSingleWayEditPlanAdapter.Assessment assessment =
                new ModernSingleWayEditPlanAdapter().assess(computed, 0);

        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.FINAL_TOPOLOGY_CROSSING,
                assessment.availability());
        assertEquals(ValidationReport.Disposition.HARD_BLOCKED,
                assessment.plan().orElseThrow().validation().disposition());
        assertFalse(assessment.applyAvailable());
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
    void movedSubrangeBoundaryCannotHideAReversedUnselectedContinuation() throws Exception {
        DataSet dataSet = new DataSet();
        Node west = loadedNode(401, 0.0, longitude(-8));
        Node boundary = loadedNode(402, 0.0, longitude(8));
        Node continuation = loadedNode(403, latitude(1), longitude(6));
        Way selected = loadedWay(410, west, boundary, continuation);
        for (Node node : List.of(west, boundary, continuation)) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(selected);
        SelectionContext selection = new SelectionContext(selected, 0, 1,
                List.of(west, boundary), Set.of());
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                dataSet, selection, junctionRaster(), visibleConfig(), false,
                new RecoveryPermissions(false, 7.0, 7.0,
                        JunctionPolicy.LEGACY_BOUNDED_MOVE, false)));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);

        ModernSingleWayEditPlanAdapter.Assessment assessment =
                new ModernSingleWayEditPlanAdapter().assess(computed, 0);

        assertTrue(assessment.plan().orElseThrow().validation().findingCodes()
                .contains("final-topology:CONTINUATION"), () -> assessment.plan()
                        .orElseThrow().validation().findingCodes().toString());
        assertFalse(assessment.applyAvailable());
    }

    @Test
    void movedIncidentWayFoldbackIsHardBlockedBeforeReviewConfirmation() throws Exception {
        JunctionFixture fixture = incidentFoldbackFixture();
        LiveBPreviewService.Computed computed = compute(fixture, JunctionPolicy.LEGACY_BOUNDED_MOVE);

        ModernSingleWayEditPlanAdapter.Assessment assessment =
                new ModernSingleWayEditPlanAdapter().assess(computed, 0);
        AlignmentEditPlan plan = assessment.plan().orElseThrow();

        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.FINAL_TOPOLOGY_CONTINUATION,
                assessment.availability(), () -> plan.validation().findingCodes().toString());
        assertEquals(ValidationReport.Disposition.HARD_BLOCKED,
                plan.validation().disposition());
        assertTrue(plan.validation().findingCodes().contains("final-topology:CONTINUATION"));
        assertEquals(Set.of(computed.request().selectedWayKey(),
                        PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                                fixture.receiver().getUniqueId())),
                plan.finalPreviewWays().keySet());
        assertEquals(plan.canonicalHash(),
                PreviewReviewState.fromEditPlan("incident-foldback", plan).exactEditPlanHash());
        assertFalse(assessment.applyAvailable());
        assertThrows(IllegalStateException.class,
                () -> PreviewReviewState.fromEditPlan("incident-foldback", plan).confirm());
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
        var expectedSelected = computed.pipeline().routes().get(0).hypothesis().points().stream()
                .map(computed.evidence().coordinateFrame()::toGeographic).toList();
        assertEquals(expectedSelected,
                plan.finalPreviewWays().get(computed.request().selectedWayKey()));
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
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(computed, 0));
        assertTrue(failure.getMessage().contains("protected incident control"),
                failure::getMessage);
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
                junctionReconstructionRaster(true), visibleConfig(), false, permissions));
        LiveBPreviewService.Computed interiorComputed = new LiveBPreviewService().compute(
                interiorCaptured[0], CancellationProbe.NONE);
        AlignmentEditPlan interiorPlan = new ModernSingleWayEditPlanAdapter()
                .adapt(interiorComputed, 0);
        PrimitiveKey interiorReceiver = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                selectedInterior.receiver().getUniqueId());
        assertEquals(Set.of(interiorComputed.request().selectedWayKey(), interiorReceiver),
                interiorPlan.finalPreviewWays().keySet());
        assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED,
                interiorPlan.validation().disposition());
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
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(computed, 0));
        assertTrue(failure.getMessage().contains("bounded terminal-through topology"),
                failure::getMessage);
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
        IllegalArgumentException multipleFailure = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(multipleComputed, 0));
        assertTrue(multipleFailure.getMessage().contains("bounded terminal-through topology"),
                multipleFailure::getMessage);
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
        Way selected = loadedWay(410, west, junction);
        Way southReceiver = loadedWay(411, farSouth, southPort, south, junction);
        Way northReceiver = loadedWay(412, junction, middle, north, northPort, farNorth);
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
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        assertEquals(Set.of(PrimitiveKey.existing(PrimitiveKey.Type.WAY, 410),
                PrimitiveKey.existing(PrimitiveKey.Type.WAY, 411),
                PrimitiveKey.existing(PrimitiveKey.Type.WAY, 412)),
                plan.finalPreviewWays().keySet());
        assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED,
                plan.validation().disposition());
        assertTrue(plan.validation().findingCodes().stream()
                .noneMatch(code -> code.startsWith("final-topology:")));
        for (Way receiver : List.of(southReceiver, northReceiver)) {
            PrimitiveKey receiverKey = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                    receiver.getUniqueId());
            DetachedWay after = (DetachedWay) plan.after().primitives().get(receiverKey);
            assertEquals(after.nodeKeys().stream()
                    .map(key -> ((DetachedNode) plan.after().primitives().get(key)).coordinate())
                    .toList(), plan.finalPreviewWays().get(receiverKey));
            for (var port : computed.captured().network().closure().externalPorts().stream()
                    .filter(value -> value.wayKey().equals(receiverKey)).toList()) {
                assertEquals(plan.before().primitives().get(port.boundaryNodeKey()),
                        plan.after().primitives().get(port.boundaryNodeKey()));
            }
        }

        Map<PrimitiveKey, List<LatLon>> original = plan.finalPreviewWays().keySet().stream()
                .collect(java.util.stream.Collectors.toMap(key -> key, key ->
                        ((Way) dataSet.getPrimitiveById(key.id(), OsmPrimitiveType.WAY))
                                .getNodes().stream()
                                .map(node -> new LatLon(node.lat(), node.lon())).toList()));
        NetworkSnapshotCapture.CapturedSnapshot receipt = onEdt(() ->
                NetworkSnapshotCapture.captureBound(dataSet, computed.captured().specification()));
        LiveNetworkSnapshotValidator validator = new LiveNetworkSnapshotValidator(
                receipt, plan, () -> plan.before().sourceGeneration());
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
                dataSet, plan, validator, "Apply split receiver alignment");
        onEdt(command::executeCommand);
        for (var entry : plan.finalPreviewWays().entrySet()) {
            Way applied = (Way) dataSet.getPrimitiveById(entry.getKey().id(),
                    OsmPrimitiveType.WAY);
            assertEquals(entry.getValue().stream()
                    .map(point -> new LatLon(point.latitudeDegrees(), point.longitudeDegrees()))
                    .toList(), applied.getNodes().stream()
                            .map(node -> new LatLon(node.lat(), node.lon())).toList());
        }
        onEdt(() -> {
            command.undoCommand();
            return null;
        });
        original.forEach((key, coordinates) -> assertEquals(coordinates,
                ((Way) dataSet.getPrimitiveById(key.id(), OsmPrimitiveType.WAY))
                        .getNodes().stream()
                        .map(node -> new LatLon(node.lat(), node.lon())).toList()));

        LiveBPreviewService.Captured[] missingCaptured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> missingCaptured[0] = new LiveBPreviewService().capture(
                dataSet, selection, junctionReconstructionRaster(false), visibleConfig(),
                false, permissions));
        LiveBPreviewService.Computed missingComputed = new LiveBPreviewService().compute(
                missingCaptured[0], CancellationProbe.NONE);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(missingComputed, 0));
        assertTrue(missing.getMessage().contains("incident approach evidence"),
                missing::getMessage);
        assertFalse(missing.getMessage().contains("bounded terminal-through topology"),
                missing::getMessage);

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
        IllegalArgumentException sameSide = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(sameSideComputed, 0));
        assertTrue(sameSide.getMessage().contains("bounded terminal-through topology"),
                sameSide::getMessage);
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
        IllegalArgumentException extra = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(extraComputed, 0));
        assertTrue(extra.getMessage().contains("bounded terminal-through topology"),
                extra::getMessage);
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
        Way diagonal = loadedWay(127, farSouth, southPort, south,
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
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        assertEquals(Set.of(computed.request().selectedWayKey(),
                PrimitiveKey.existing(PrimitiveKey.Type.WAY, fixture.receiver().getUniqueId()),
                PrimitiveKey.existing(PrimitiveKey.Type.WAY, diagonal.getUniqueId())),
                plan.finalPreviewWays().keySet());
        assertTrue(plan.validation().findingCodes().stream()
                .noneMatch(code -> code.startsWith("final-topology:")));
        for (Way receiver : List.of(fixture.receiver(), diagonal)) {
            PrimitiveKey receiverKey = PrimitiveKey.existing(PrimitiveKey.Type.WAY,
                    receiver.getUniqueId());
            DetachedWay after = (DetachedWay) plan.after().primitives().get(receiverKey);
            assertEquals(after.nodeKeys().stream()
                    .map(key -> ((DetachedNode) plan.after().primitives().get(key)).coordinate())
                    .toList(), plan.finalPreviewWays().get(receiverKey));
            for (var port : computed.captured().network().closure().externalPorts().stream()
                    .filter(value -> value.wayKey().equals(receiverKey)).toList()) {
                assertEquals(plan.before().primitives().get(port.boundaryNodeKey()),
                        plan.after().primitives().get(port.boundaryNodeKey()));
            }
        }

        Map<PrimitiveKey, List<LatLon>> original = plan.finalPreviewWays().keySet().stream()
                .collect(java.util.stream.Collectors.toMap(key -> key, key ->
                        ((Way) fixture.dataSet().getPrimitiveById(key.id(), OsmPrimitiveType.WAY))
                                .getNodes().stream()
                                .map(node -> new LatLon(node.lat(), node.lon())).toList()));
        NetworkSnapshotCapture.CapturedSnapshot receipt = onEdt(() ->
                NetworkSnapshotCapture.captureBound(
                        fixture.dataSet(), computed.captured().specification()));
        LiveNetworkSnapshotValidator validator = new LiveNetworkSnapshotValidator(
                receipt, plan, () -> plan.before().sourceGeneration());
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), plan, validator, "Apply two through receivers");
        onEdt(command::executeCommand);
        for (var entry : plan.finalPreviewWays().entrySet()) {
            Way applied = (Way) fixture.dataSet().getPrimitiveById(entry.getKey().id(),
                    OsmPrimitiveType.WAY);
            assertEquals(entry.getValue().stream()
                    .map(point -> new LatLon(point.latitudeDegrees(), point.longitudeDegrees()))
                    .toList(), applied.getNodes().stream()
                            .map(node -> new LatLon(node.lat(), node.lon())).toList());
        }
        onEdt(() -> {
            command.undoCommand();
            return null;
        });
        original.forEach((key, coordinates) -> assertEquals(coordinates,
                ((Way) fixture.dataSet().getPrimitiveById(key.id(), OsmPrimitiveType.WAY))
                        .getNodes().stream()
                        .map(node -> new LatLon(node.lat(), node.lon())).toList()));

        LiveBPreviewService.Captured[] missingCaptured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> missingCaptured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionReconstructionRaster(true),
                visibleConfig(), false, permissions));
        LiveBPreviewService.Computed missingComputed = new LiveBPreviewService().compute(
                missingCaptured[0], CancellationProbe.NONE);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(missingComputed, 0));
        assertTrue(missing.getMessage().contains("incident approach evidence"),
                missing::getMessage);
        assertFalse(missing.getMessage().contains("bounded terminal-through topology"),
                missing::getMessage);
    }

    @Test
    void T169_coupledSelectedEndpointsReconstructDistinctMeasuredReceivers() throws Exception {
        DataSet dataSet = new DataSet();
        Node leftJunction = loadedNode(601, 0.0, longitude(-8));
        Node rightJunction = loadedNode(602, 0.0, longitude(8));
        Way selected = loadedWay(610, leftJunction, rightJunction);
        Node leftFarSouth = loadedNode(603, latitude(-49), longitude(-10));
        Node leftSouthPort = loadedNode(604, latitude(-31), longitude(-10));
        Node leftSouth = loadedNode(605, latitude(-8), longitude(-10));
        Node leftMiddle = loadedNode(606, latitude(8), longitude(-10));
        Node leftNorth = loadedNode(607, latitude(18), longitude(-10));
        Node leftNorthPort = loadedNode(608, latitude(31), longitude(-10));
        Node leftFarNorth = loadedNode(609, latitude(49), longitude(-10));
        Way leftReceiver = loadedWay(611, leftFarSouth, leftSouthPort, leftSouth,
                leftJunction, leftMiddle, leftNorth, leftNorthPort, leftFarNorth);
        Node rightFarSouth = loadedNode(613, latitude(-49), longitude(10));
        Node rightSouthPort = loadedNode(614, latitude(-31), longitude(10));
        Node rightSouth = loadedNode(615, latitude(-8), longitude(10));
        Node rightMiddle = loadedNode(616, latitude(8), longitude(10));
        Node rightNorth = loadedNode(617, latitude(18), longitude(10));
        Node rightNorthPort = loadedNode(618, latitude(31), longitude(10));
        Node rightFarNorth = loadedNode(619, latitude(49), longitude(10));
        Way rightReceiver = loadedWay(612, rightFarSouth, rightSouthPort, rightSouth,
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
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        assertEquals(Set.of(PrimitiveKey.existing(PrimitiveKey.Type.WAY, 610),
                PrimitiveKey.existing(PrimitiveKey.Type.WAY, 611),
                PrimitiveKey.existing(PrimitiveKey.Type.WAY, 612)),
                plan.finalPreviewWays().keySet());
        assertTrue(plan.validation().findingCodes().stream()
                .noneMatch(code -> code.startsWith("final-topology:")));
        PrimitiveKey routeKey = PrimitiveKey.existing(PrimitiveKey.Type.RELATION, 630);
        assertEquals(plan.before().primitives().get(routeKey),
                plan.after().primitives().get(routeKey));
        LiveBPreviewService.Captured[] missingCaptured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> missingCaptured[0] = new LiveBPreviewService().capture(
                dataSet, selection, junctionReconstructionRaster(true), visibleConfig(),
                false, permissions));
        LiveBPreviewService.Computed missingComputed = new LiveBPreviewService().compute(
                missingCaptured[0], CancellationProbe.NONE);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(missingComputed, 0));
        assertTrue(missing.getMessage().contains("incident approach evidence"),
                missing::getMessage);
        assertFalse(missing.getMessage().contains("bounded terminal-through topology"),
                missing::getMessage);
        List<RelationMember> originalMembers = List.copyOf(route.getMembers());
        Map<PrimitiveKey, List<LatLon>> original = plan.finalPreviewWays().keySet().stream()
                .collect(java.util.stream.Collectors.toMap(key -> key, key ->
                        ((Way) dataSet.getPrimitiveById(key.id(), OsmPrimitiveType.WAY))
                                .getNodes().stream()
                                .map(node -> new LatLon(node.lat(), node.lon())).toList()));
        NetworkSnapshotCapture.CapturedSnapshot receipt = onEdt(() ->
                NetworkSnapshotCapture.captureBound(dataSet, computed.captured().specification()));
        LiveNetworkSnapshotValidator validator = new LiveNetworkSnapshotValidator(
                receipt, plan, () -> plan.before().sourceGeneration());
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
                dataSet, plan, validator, "Apply coupled receiver alignment");
        onEdt(command::executeCommand);
        for (var entry : plan.finalPreviewWays().entrySet()) {
            Way applied = (Way) dataSet.getPrimitiveById(entry.getKey().id(),
                    OsmPrimitiveType.WAY);
            assertEquals(entry.getValue().stream()
                    .map(point -> new LatLon(point.latitudeDegrees(), point.longitudeDegrees()))
                    .toList(), applied.getNodes().stream()
                            .map(node -> new LatLon(node.lat(), node.lon())).toList());
        }
        assertEquals(originalMembers, route.getMembers());
        onEdt(() -> {
            command.undoCommand();
            return null;
        });
        original.forEach((key, coordinates) -> assertEquals(coordinates,
                ((Way) dataSet.getPrimitiveById(key.id(), OsmPrimitiveType.WAY))
                        .getNodes().stream()
                        .map(node -> new LatLon(node.lat(), node.lon())).toList()));
        assertEquals(originalMembers, route.getMembers());

        Node entrantSouth = loadedNode(640, latitude(-10), longitude(0));
        Node entrantNorth = loadedNode(641, latitude(10), longitude(0));
        Way entrant = loadedWay(642, entrantSouth, entrantNorth);
        dataSet.addPrimitive(entrantSouth);
        dataSet.addPrimitive(entrantNorth);
        dataSet.addPrimitive(entrant);
        ApplyAlignmentEditPlanCommand stale = new ApplyAlignmentEditPlanCommand(
                dataSet, plan, validator, "Reject stale coupled alignment");
        assertThrows(IllegalStateException.class, () -> onEdt(stale::executeCommand));
        original.forEach((key, coordinates) -> assertEquals(coordinates,
                ((Way) dataSet.getPrimitiveById(key.id(), OsmPrimitiveType.WAY))
                        .getNodes().stream()
                        .map(node -> new LatLon(node.lat(), node.lon())).toList()));
        assertEquals(originalMembers, route.getMembers());
        assertEquals(List.of(entrantSouth, entrantNorth), entrant.getNodes());
    }

    @Test
    void T170_actualAtomicCommandAppliesEveryReviewedPreviewWayExactly() throws Exception {
        assertAtomicCommandAppliesPreviewWaysExactly(reconstructionFixture());
        assertAtomicCommandAppliesPreviewWaysExactly(selectedInteriorReconstructionFixture());
    }

    private static void assertAtomicCommandAppliesPreviewWaysExactly(JunctionFixture fixture)
            throws Exception {
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), junctionReconstructionRaster(true),
                visibleConfig(), false, permissions));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        AlignmentEditPlan plan = new ModernSingleWayEditPlanAdapter().adapt(computed, 0);
        Map<PrimitiveKey, List<Node>> originalWays = plan.finalPreviewWays().keySet().stream()
                .collect(java.util.stream.Collectors.toMap(key -> key, key -> List.copyOf(
                        ((Way) fixture.dataSet().getPrimitiveById(
                                key.id(), OsmPrimitiveType.WAY)).getNodes())));
        Map<PrimitiveKey, List<LatLon>> originalCoordinates = originalWays.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(node -> new LatLon(node.lat(), node.lon())).toList()));
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
        Way selected = loadedWay(510, west, junction);
        Way receiver = loadedWay(511, south, junction, north);
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
        Way selected = loadedWay(110, west, junction);
        Way receiver = loadedWay(111, farSouth, southPort, south, junction, middle, north,
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
        Way selected = loadedWay(210, west, junction);
        Way receiver = loadedWay(211, farSouth, southPort, south, junction, middle, north,
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
        Way selected = loadedWay(310, west, junction, selectedContinuation);
        Way receiver = loadedWay(311, farSouth, southPort, south, junction, middle, north,
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
                double leftReceiver = includeLeftReceiver
                        ? Math.exp(-0.5 * leftReceiverDistance * leftReceiverDistance / 1.44)
                        : 0.0;
                int gray = (int) Math.round(255.0 * (0.02 + 0.80
                        * Math.max(selected, Math.max(receiver,
                                Math.max(diagonal, leftReceiver)))));
                argb[y * size + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        return new LiveBPreviewService.VisibleRaster(size, size, argb, -extent, -extent,
                extent, extent, 1.0, 1.0, OptionalDouble.of(1.0),
                "visible-junction-" + receiverEvidence.name().toLowerCase()
                    + "-" + selectedNorthMeters,
                "EPSG:3857");
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

    private enum ReceiverEvidence { NONE, COMPLETE, NORTH_ONLY, AMBIGUOUS }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.ProjectionBounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
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
    void liveParameterIdentitySeparatesDirectBFromBaselineHybrid() throws Exception {
        String direct = LiveBPreviewService.parameterIdentity(TrackerMode.PROBABILISTIC);
        String hybrid = LiveBPreviewService.parameterIdentity(TrackerMode.HYBRID);
        Fixture fixture = fixture();
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[2];
        SwingUtilities.invokeAndWait(() -> {
            captured[0] = service.capture(fixture.dataSet(), fixture.selection(), raster(),
                    config(TrackerMode.PROBABILISTIC));
            captured[1] = service.capture(fixture.dataSet(), fixture.selection(), raster(),
                    config(TrackerMode.HYBRID));
        });

        assertTrue(direct.contains("direct-longitudinal-2"));
        assertTrue(direct.contains("DIRECT_LONGITUDINAL_V2"));
        assertTrue(hybrid.contains("BASELINE"));
        assertNotEquals(direct, hybrid);
        assertNotEquals(captured[0].parameterHash(), captured[1].parameterHash());
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

        assertEquals(TraceBudgets.interactiveProbabilisticPreview(), result.request().budgets());
        assertEquals("selected-visible", result.options().sourceTier());
        assertEquals(captured[0].cleanup(), result.options().cleanup());
        assertTrue(result.counters().containsKey("pipeline.inferenceMs"));
        assertTrue(result.counters().containsKey("inference.pairVisits"));
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
    void realProductionHybridRetainsUnguidedBWithFundedHybridBudget() throws Exception {
        Fixture fixture = fixture();
        List<String> before = state(fixture.dataSet());
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(fixture.dataSet(),
                fixture.selection(), raster(), config(TrackerMode.HYBRID)));

        LiveBPreviewService.Computed result = service.compute(captured[0], CancellationProbe.NONE);

        assertEquals(TrackerMode.HYBRID, result.request().engine());
        assertEquals(TraceBudgets.fundedHybrid(), result.request().budgets());
        assertEquals(TrackerMode.HYBRID, result.pipeline().inference().engine());
        assertTrue(result.pipeline().inference().hypotheses().stream()
                .anyMatch(hypothesis -> hypothesis.branchSignature().startsWith("b:")),
                "Hybrid output must retain the unguided B family");
        assertFalse(result.pipeline().routes().isEmpty(),
                "supported visible evidence must produce a final Hybrid route");
        assertTrue(result.pipeline().routes().stream()
                .anyMatch(route -> route.hypothesis().branchSignature().startsWith("b:")),
                "common final processing must retain an unguided B route");
        assertEquals(before, state(fixture.dataSet()));
    }

    @Test
    void realProductionDirectionalImageReturnsReadOnlyRouteFromVisibleFrame() throws Exception {
        Fixture fixture = fixture();
        List<String> before = state(fixture.dataSet());
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(fixture.dataSet(),
                fixture.selection(), raster(), config(TrackerMode.DIRECTIONAL_IMAGE), true));

        LiveBPreviewService.Computed result = service.compute(captured[0], CancellationProbe.NONE);

        assertEquals(TrackerMode.DIRECTIONAL_IMAGE, result.request().engine());
        assertEquals(TraceBudgets.defaults(), result.request().budgets());
        assertEquals(TrackerMode.DIRECTIONAL_IMAGE, result.pipeline().inference().engine());
        assertFalse(result.pipeline().routes().isEmpty(),
                "supported visible evidence must produce a final Image route");
        assertEquals(before, state(fixture.dataSet()));
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
    void visibleSourceFreshnessRejectsChangedEvidencePixels() throws Exception {
        Fixture fixture = fixture();
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(fixture.dataSet(),
                fixture.selection(), raster(), config()));

        LiveBPreviewService.VisibleRaster changed = rasterWithChangedEvidence();
        SwingUtilities.invokeAndWait(() -> {
            assertDoesNotThrow(() -> service.requireCurrent(fixture.dataSet(), captured[0], raster()));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> service.requireCurrent(fixture.dataSet(), captured[0], changed));
            assertTrue(failure.getMessage().contains("evidence"));
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
                        "hot", 15, "managed-test",
                        new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(0L)));

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
    void visibleCaptureMakesOrdinaryInteriorIdentitiesMovable() throws Exception {
        Fixture fixture = fiveNodeFixture();
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];

        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(fixture.dataSet(),
                fixture.selection(), raster(), config()));

        assertOrdinaryInteriorAuthority(captured[0].network().closure(), fixture.selection());
    }

    @Test
    void managedCaptureUsesTheSameOrdinaryInteriorAuthority() throws Exception {
        Fixture fixture = fiveNodeFixture();
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.ManagedCaptureSeed[] captured = new LiveBPreviewService.ManagedCaptureSeed[1];

        SwingUtilities.invokeAndWait(() -> captured[0] = service.captureManagedSeed(fixture.dataSet(),
                fixture.selection(), managedConfig(), "managed-authority-test"));

        assertOrdinaryInteriorAuthority(captured[0].network().closure(), fixture.selection());
    }

    @Test
    void productionBMovesOrdinaryInteriorsTowardAnOffsetRidgeWithoutReturnSpikes() throws Exception {
        Fixture fixture = fiveNodeFixture();
        for (int index = 1; index < fixture.selection().segmentNodes().size() - 1; index++) {
            Node node = fixture.selection().segmentNodes().get(index);
            node.setCoor(new LatLon(latitude(3.0), node.lon()));
        }
        List<String> before = state(fixture.dataSet());
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), rasterAt(300.0), config()));
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(captured[0], CancellationProbe.NONE);
        var route = computed.pipeline().routes().get(0);
        assertEquals(5, route.existingAssignments().size());
        for (int index = 1; index < 4; index++) {
            Node node = fixture.selection().segmentNodes().get(index);
            var id = route.existingAssignments().keySet().stream().filter(value ->
                    value.nodeKey().equals(PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId())))
                    .findFirst().orElseThrow();
            MetricPoint moved = route.existingAssignments().get(id);
            assertTrue(Math.abs(moved.yMeters() - captured[0].sourceMetric().get(0).yMeters())
                    < Math.abs(captured[0].sourceMetric().get(index).yMeters()
                            - captured[0].sourceMetric().get(0).yMeters()),
                    "ordinary interior must improve toward ridge");
            assertTrue(moved.distanceTo(captured[0].sourceMetric().get(index)) > 1.0e-6,
                    "ordinary interior must not return to source coordinate");
        }
        assertTrue(route.quality().findings().stream().noneMatch(finding ->
                finding.code() == org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.
                        FinalGeometryEvaluator.FindingCode.ADJACENT_BACKTRACK));
        assertEquals(before, state(fixture.dataSet()));
    }

    @Test
    void taggedAndRelationReferencedEndpointsRemainProtectedUnderJunctionPermission() throws Exception {
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.LEGACY_BOUNDED_MOVE, false);
        Fixture tagged = fiveNodeFixture();
        tagged.selection().segmentNodes().get(0).put("barrier", "gate");
        LiveBPreviewService.Captured[] taggedCapture = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> taggedCapture[0] = new LiveBPreviewService().capture(
                tagged.dataSet(), tagged.selection(), raster(), config(), true, permissions));
        PrimitiveKey taggedKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                tagged.selection().segmentNodes().get(0).getUniqueId());
        assertTrue(taggedCapture[0].network().closure().protectedExistingNodeKeys().contains(taggedKey));
        assertFalse(taggedCapture[0].network().closure().movableExistingNodeKeys().contains(taggedKey));

        Fixture related = fiveNodeFixture();
        Relation relation = new Relation();
        relation.setOsmId(190, 1);
        relation.setModified(false);
        related.dataSet().addPrimitive(relation);
        relation.setMembers(List.of(new RelationMember("via", related.selection().segmentNodes().get(0))));
        LiveBPreviewService.Captured[] relatedCapture = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> relatedCapture[0] = new LiveBPreviewService().capture(
                related.dataSet(), related.selection(), raster(), config(), true, permissions));
        PrimitiveKey relatedKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                related.selection().segmentNodes().get(0).getUniqueId());
        assertTrue(relatedCapture[0].network().closure().protectedExistingNodeKeys().contains(relatedKey));
        assertFalse(relatedCapture[0].network().closure().movableExistingNodeKeys().contains(relatedKey));
    }

    @Test
    void relationMemberReceiverIsFrozenAtVisibleCaptureBoundary() throws Exception {
        Fixture fixture = relationMemberReceiverFixture();
        Node junction = fixture.selection().segmentNodes().get(1);
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), raster(), config(), true, permissions));

        PrimitiveKey junctionKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE, junction.getUniqueId());
        PrimitiveKey receiverKey = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 53);
        assertTrue(captured[0].network().closure().protectedExistingNodeKeys().contains(junctionKey));
        assertFalse(captured[0].network().closure().editableExistingKeys().contains(receiverKey));
    }

    @Test
    void relationMemberReceiverIsFrozenAtManagedCaptureBoundary() throws Exception {
        Fixture fixture = relationMemberReceiverFixture();
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.ManagedCaptureSeed[] captured = new LiveBPreviewService.ManagedCaptureSeed[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().captureManagedSeed(
                fixture.dataSet(), fixture.selection(), managedConfig(), "managed-relation", permissions));

        PrimitiveKey junctionKey = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                fixture.selection().segmentNodes().get(1).getUniqueId());
        PrimitiveKey receiverKey = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 53);
        assertTrue(captured[0].network().closure().protectedExistingNodeKeys().contains(junctionKey));
        assertFalse(captured[0].network().closure().editableExistingKeys().contains(receiverKey));
    }

    @Test
    void persistedLegacyJunctionPermissionCannotMoveARelationReceiver() throws Exception {
        Fixture fixture = relationMemberReceiverFixture();
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.LEGACY_BOUNDED_MOVE, false);
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(fixture.dataSet(),
                fixture.selection(), raster(), config(), true, permissions));
        PrimitiveKey junction = PrimitiveKey.existing(PrimitiveKey.Type.NODE,
                fixture.selection().segmentNodes().get(1).getUniqueId());
        assertTrue(captured[0].network().closure().protectedExistingNodeKeys().contains(junction));
        assertFalse(captured[0].network().closure().movableExistingNodeKeys().contains(junction));
        LiveBPreviewService.Computed computed = service.compute(captured[0], CancellationProbe.NONE);
        var assessment = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                .ModernSingleWayEditPlanAdapter().assess(computed, 0);
        assertFalse(assessment.applyAvailable());
        assertTrue(assessment.detail().contains(
                "Adjust this junction manually, then run alignment again."));
    }

    @Test
    void repeatedReceiverIsCapturedAsTypedManualOnlyInVisibleAndManagedSources()
            throws Exception {
        Fixture fixture = fixture();
        Node junction = fixture.selection().segmentNodes().get(1);
        Node west = loadedNode(61, latitude(8), longitude(-8));
        Node east = loadedNode(62, latitude(8), longitude(8));
        Node north = loadedNode(63, latitude(16), longitude(8));
        for (Node node : List.of(west, east, north)) fixture.dataSet().addPrimitive(node);
        Way receiver = new Way();
        receiver.setNodes(List.of(west, junction, east, junction, north));
        receiver.setOsmId(64, 1);
        receiver.setModified(false);
        fixture.dataSet().addPrimitive(receiver);
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] visible = new LiveBPreviewService.Captured[1];
        LiveBPreviewService.ManagedCaptureSeed[] managed = new LiveBPreviewService.ManagedCaptureSeed[1];
        SwingUtilities.invokeAndWait(() -> {
            visible[0] = service.capture(fixture.dataSet(), fixture.selection(), raster(),
                    config(), true, permissions);
            managed[0] = service.captureManagedSeed(fixture.dataSet(), fixture.selection(),
                    managedConfig(), "managed-repeated-receiver", permissions);
        });
        var expected = org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .ManualJunctionEligibility.Reason.REPEATED_OCCURRENCE;
        assertEquals(expected, org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .ManualJunctionEligibility.evaluate(visible[0].network(),
                        visible[0].specification()).reason());
        assertEquals(expected, org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .ManualJunctionEligibility.evaluate(managed[0].network(),
                        managed[0].specification()).reason());
        PrimitiveKey key = PrimitiveKey.existing(PrimitiveKey.Type.NODE, junction.getUniqueId());
        assertTrue(visible[0].network().closure().protectedExistingNodeKeys().contains(key));
        assertTrue(managed[0].network().closure().protectedExistingNodeKeys().contains(key));
    }

    @Test
    void receiverArmOutsideCertifiedFrameIsTypedManualOnly() throws Exception {
        Fixture fixture = fixture();
        Node junction = fixture.selection().segmentNodes().get(1);
        Node south = loadedNode(71, latitude(-31), longitude(8));
        Node north = loadedNode(72, latitude(31), longitude(8));
        fixture.dataSet().addPrimitive(south);
        fixture.dataSet().addPrimitive(north);
        Way receiver = new Way();
        receiver.setNodes(List.of(south, junction, north));
        receiver.setOsmId(73, 1);
        receiver.setModified(false);
        fixture.dataSet().addPrimitive(receiver);
        int[] pixels = new int[360 * 360];
        java.util.Arrays.fill(pixels, 0xff808080);
        LiveBPreviewService.VisibleRaster narrow = new LiveBPreviewService.VisibleRaster(
                360, 360, pixels, -30.0, -30.0, 30.0, 30.0, 1.0, 1.0,
                OptionalDouble.of(1.0), "visible-narrow-junction", "EPSG:3857");
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), narrow, config(), true, permissions));
        var decision = org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .ManualJunctionEligibility.evaluate(captured[0].network(), captured[0].specification());
        assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .ManualJunctionEligibility.Reason.INCOMPLETE_ARM, decision.reason());
        assertTrue(decision.manualInstruction().contains(
                "Adjust this junction manually, then run alignment again."));
        assertTrue(captured[0].network().closure().protectedExistingNodeKeys().contains(
                PrimitiveKey.existing(PrimitiveKey.Type.NODE, junction.getUniqueId())));
    }

    @Test
    void incompleteIncidentReceiverRefusesCaptureWithManualFirstReason() throws Exception {
        Fixture fixture = fixture();
        Node junction = fixture.selection().segmentNodes().get(1);
        Way receiver = new Way();
        receiver.setNodes(List.of(junction));
        receiver.setOsmId(82, 1);
        receiver.setModified(false);
        fixture.dataSet().addPrimitive(receiver);
        List<String> before = state(fixture.dataSet());
        var undoBefore = List.copyOf(org.openstreetmap.josm.data.UndoRedoHandler
                .getInstance().getUndoCommands());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, true);

        LiveBPreviewService.ManualJunctionCaptureException[] refusal =
                new LiveBPreviewService.ManualJunctionCaptureException[1];
        SwingUtilities.invokeAndWait(() -> refusal[0] = assertThrows(
                LiveBPreviewService.ManualJunctionCaptureException.class,
                () -> new LiveBPreviewService().capture(
                        fixture.dataSet(), fixture.selection(), raster(), config(), true,
                        permissions)));

        assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                .ManualJunctionEligibility.Reason.INCOMPLETE_ARM, refusal[0].reason());
        assertTrue(refusal[0].getMessage().contains("incomplete incident way"));
        assertTrue(refusal[0].getMessage().contains(
                "Adjust this junction manually, then run alignment again."));
        assertEquals(before, state(fixture.dataSet()));
        assertEquals(undoBefore, List.copyOf(org.openstreetmap.josm.data.UndoRedoHandler
                .getInstance().getUndoCommands()));
    }

    @Test
    void incompleteRelationMemberOnReceiverRefusesVisibleAndManagedCaptureWithTypedReason()
            throws Exception {
        Fixture fixture = relationMemberReceiverFixture();
        Node incomplete = new Node(99);
        fixture.dataSet().addPrimitive(incomplete);
        Relation route = fixture.dataSet().getRelations().iterator().next();
        Way receiver = fixture.dataSet().getWays().stream()
                .filter(way -> way.getUniqueId() == 53).findFirst().orElseThrow();
        route.setMembers(List.of(new RelationMember("", receiver),
                new RelationMember("missing", incomplete)));
        List<String> before = state(fixture.dataSet());
        var undoBefore = List.copyOf(org.openstreetmap.josm.data.UndoRedoHandler
                .getInstance().getUndoCommands());
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.ManualJunctionCaptureException[] visible =
                new LiveBPreviewService.ManualJunctionCaptureException[1];
        LiveBPreviewService.ManualJunctionCaptureException[] managed =
                new LiveBPreviewService.ManualJunctionCaptureException[1];
        SwingUtilities.invokeAndWait(() -> {
            visible[0] = assertThrows(LiveBPreviewService.ManualJunctionCaptureException.class,
                    () -> service.capture(fixture.dataSet(), fixture.selection(), raster(),
                            config(), true, permissions));
            managed[0] = assertThrows(LiveBPreviewService.ManualJunctionCaptureException.class,
                    () -> service.captureManagedSeed(fixture.dataSet(), fixture.selection(),
                            managedConfig(), "managed-incomplete-relation", permissions));
        });

        for (var refusal : List.of(visible[0], managed[0])) {
            assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                    .ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE, refusal.reason());
            assertTrue(refusal.getMessage().contains(
                    "Adjust this junction manually, then run alignment again."));
        }
        assertEquals(before, state(fixture.dataSet()));
        assertEquals(undoBefore, List.copyOf(org.openstreetmap.josm.data.UndoRedoHandler
                .getInstance().getUndoCommands()));
    }

    @Test
    void managedCaptureCarriesFrozenRecoveryIntoTraceRequest() throws Exception {
        Fixture fixture = fiveNodeFixture();
        RecoveryPermissions permissions = new RecoveryPermissions(true, 7.01, 14.0,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.ManagedCaptureSeed[] seed = new LiveBPreviewService.ManagedCaptureSeed[1];
        SwingUtilities.invokeAndWait(() -> seed[0] = service.captureManagedSeed(fixture.dataSet(),
                fixture.selection(), managedConfig(), "managed-recovery-test", permissions));
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        LiveBPreviewService.Captured captured = service.attachManagedRaster(seed[0],
                new ManagedModernPreviewSource.Raster(image,
                        new boolean[] {true, true, true, true},
                        SupportedInputRasterTransform.webMercator(15, 0.0, 0.0, 2.0),
                        "hot", 15, "managed-recovery-test",
                        new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(0L)));

        LiveBPreviewService.Computed computed = service.compute(captured, CancellationProbe.NONE);

        assertEquals(permissions, seed[0].specification().permissions());
        assertEquals(permissions, computed.request().permissions());
    }

    @Test
    void legacyAdjustCheckboxCannotOverrideFrozenModernJunctionPolicy() throws Exception {
        Fixture fixture = fixture();
        RecoveryPermissions permissions = RecoveryPermissions.disabled(7.0);
        AlignmentConfig configured = withLegacyJunctionCheckbox(
                config(TrackerMode.CORRIDOR_AWARE));
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];

        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), raster(), configured, true, permissions));

        assertEquals(JunctionPolicy.FIXED,
                captured[0].specification().permissions().junctionPolicy());
    }

    @Test
    void ordinaryVisibleCaptureHonorsRecoveryRadiusAcrossViewScaleRounding() throws Exception {
        Fixture fixture = fixture();
        RecoveryPermissions permissions = RecoveryPermissions.disabled(7.01);
        AlignmentConfig configured = withVisibleHalfWidth(config(TrackerMode.CORRIDOR_AWARE), 18);
        AtomicInteger requestedHalfWidthPixels = new AtomicInteger();
        LiveBPreviewService.VisibleRaster[] roundedScaleRaster = new LiveBPreviewService.VisibleRaster[1];
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];

        SwingUtilities.invokeAndWait(() -> {
            roundedScaleRaster[0] = new AlignmentService().captureLiveBVisibleRaster(
                    fixture.selection(), configured, "ordinary-rounded-visible", permissions,
                    (source, halfWidthPixels) -> {
                        requestedHalfWidthPixels.set(halfWidthPixels);
                        double extent = 50.0 * 0.389;
                        return new AlignmentService.VisibleCaptureFrame(
                                rasterAtGroundScale(0.389).image(),
                                new ProjectionBounds(-extent, -extent, extent, extent),
                                0.389, OptionalDouble.of(1.0));
                    });
            captured[0] = new LiveBPreviewService().capture(
                    fixture.dataSet(), fixture.selection(), roundedScaleRaster[0],
                    configured, true, permissions);
        });
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);

        assertEquals(19, requestedHalfWidthPixels.get());
        assertEquals(0.389, roundedScaleRaster[0].groundMetersPerViewPixel(), 1.0e-9);
        assertEquals(7.01, captured[0].searchRadiusMeters(), 0.0);
        assertEquals(permissions, captured[0].specification().permissions());
        assertEquals(permissions, computed.request().permissions());
    }

    @Test
    void ordinaryVisibleCaptureRejectsPhysicallyUndersizedFootprint() throws Exception {
        Fixture fixture = fixture();
        RecoveryPermissions permissions = RecoveryPermissions.disabled(7.01);
        AlignmentConfig configured = withVisibleHalfWidth(config(TrackerMode.CORRIDOR_AWARE), 18);
        LiveBPreviewService.VisibleRaster undersized = new LiveBPreviewService.VisibleRaster(
                120, 60, new int[120 * 60], -10.0, -5.0, 10.0, 5.0,
                1.0, 0.389, OptionalDouble.empty(), "undersized-visible", "EPSG:3857");
        IllegalArgumentException[] failure = new IllegalArgumentException[1];

        SwingUtilities.invokeAndWait(() -> failure[0] = assertThrows(
                IllegalArgumentException.class, () -> new LiveBPreviewService().capture(
                        fixture.dataSet(), fixture.selection(), undersized,
                        configured, true, permissions)));

        assertTrue(failure[0].getMessage().contains(
                "recovery radius exceeds the captured decision corridor"));
    }

    @Test
    void moveExistingReachesTheCommonPipelineForSparseFinalAssessment() throws Exception {
        Fixture fixture = fixture();
        ManagedHeatmapConfig moveExisting = config().heatmap()
                .withAlignmentMode(AlignmentMode.MOVE_EXISTING_NODES);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), raster(),
                new AlignmentConfig(moveExisting, GeometryCleanupConfig.disabled())));

        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);

        assertEquals(AlignmentMode.MOVE_EXISTING_NODES, computed.request().geometryMode());
        assertFalse(computed.pipeline().routes().isEmpty());
        assertTrue(computed.pipeline().routes().get(0).quality().has(
                FinalGeometryEvaluator.FindingCode.PRECISE_SHAPE_REQUIRED));
        ModernSingleWayEditPlanAdapter.Assessment assessment =
                new ModernSingleWayEditPlanAdapter().assess(computed, 0);
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.PRECISE_SHAPE_REQUIRED,
                assessment.availability());
        assertTrue(assessment.plan().isEmpty());
        assertFalse(assessment.applyAvailable());
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

    private static Fixture relationMemberReceiverFixture() {
        Fixture fixture = fixture();
        Node junction = fixture.selection().segmentNodes().get(1);
        Node west = loadedNode(51, latitude(8), longitude(-8));
        Node east = loadedNode(52, latitude(8), longitude(8));
        fixture.dataSet().addPrimitive(west);
        fixture.dataSet().addPrimitive(east);
        Way receiver = new Way();
        receiver.setNodes(List.of(west, junction, east));
        receiver.setOsmId(53, 1);
        receiver.setModified(false);
        fixture.dataSet().addPrimitive(receiver);
        Relation route = new Relation();
        route.setMembers(List.of(new RelationMember("", receiver)));
        route.setOsmId(54, 1);
        route.setModified(false);
        fixture.dataSet().addPrimitive(route);
        return fixture;
    }

    private static LiveBPreviewService.VisibleRaster raster() {
        return rasterAt(288.0);
    }

    private static LiveBPreviewService.VisibleRaster rasterAt(double centerY) {
        int width = 600;
        int height = 600;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            double distance = (y - centerY) / RenderedHeatmapSampler.RASTER_SCALE;
            double intensity = 0.02 + 0.80 * Math.exp(-0.5 * distance * distance / (1.2 * 1.2));
            int gray = (int) Math.round(255.0 * intensity);
            int pixel = 0xff000000 | gray << 16 | gray << 8 | gray;
            java.util.Arrays.fill(argb, y * width, (y + 1) * width, pixel);
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb,
                -50.0, -50.0, 50.0, 50.0, 1.0, 1.0,
                OptionalDouble.of(1.0), "visible-test", "EPSG:3857");
    }

    private static LiveBPreviewService.VisibleRaster rasterWithChangedEvidence() {
        LiveBPreviewService.VisibleRaster original = raster();
        int[] changed = original.argb();
        int center = 288 * original.width() + original.width() / 2;
        changed[center] = 0xff000000;
        return new LiveBPreviewService.VisibleRaster(original.width(), original.height(), changed,
                original.minimumEast(), original.minimumNorth(), original.maximumEast(),
                original.maximumNorth(), original.projectionUnitsPerViewPixel(),
                original.groundMetersPerViewPixel(), original.nativePitchMeters(),
                original.sourceIdentity(), original.projectionCode());
    }

    private static LiveBPreviewService.VisibleRaster rasterAtGroundScale(double groundMetersPerViewPixel) {
        LiveBPreviewService.VisibleRaster original = raster();
        return new LiveBPreviewService.VisibleRaster(original.width(), original.height(), original.argb(),
                original.minimumEast(), original.minimumNorth(), original.maximumEast(),
                original.maximumNorth(), original.projectionUnitsPerViewPixel(),
                groundMetersPerViewPixel, original.nativePitchMeters(),
                original.sourceIdentity(), original.projectionCode());
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

    private static AlignmentConfig withLegacyJunctionCheckbox(AlignmentConfig config) {
        ManagedHeatmapConfig value = config.heatmap();
        return new AlignmentConfig(new ManagedHeatmapConfig(value.keyPairId(), value.policy(),
                value.signature(), value.sessionToken(), value.activity(), value.color(),
                value.manualLayerName(), value.layerRegex(), value.alignmentMode(), value.trackerMode(),
                value.verbose(), value.debug(), value.multiColorDetection(),
                value.aggregateAllColorSchemes(), value.showAggregateIntensityLayer(),
                value.candidateRatingEnabled(), value.parallelWayAwareness(),
                value.allowUndownloadedAlignment(), true, value.simplifyEnabled(),
                value.crossSectionHalfWidthPx(), value.crossSectionStepPx(),
                value.simplifyTolerancePx(), value.inferenceMode(), value.inferenceZoom(),
                value.validationZoom(), value.searchHalfWidthMeters(), value.sampleStepMeters(),
                value.intensitySamplingMode(), value.cacheBuster()), config.cleanup());
    }

    private static AlignmentConfig withVisibleHalfWidth(AlignmentConfig config, int halfWidthPixels) {
        ManagedHeatmapConfig value = config.heatmap();
        return new AlignmentConfig(new ManagedHeatmapConfig(value.keyPairId(), value.policy(),
                value.signature(), value.sessionToken(), value.activity(), value.color(),
                value.manualLayerName(), value.layerRegex(), value.alignmentMode(), value.trackerMode(),
                value.verbose(), value.debug(), value.multiColorDetection(),
                value.aggregateAllColorSchemes(), value.showAggregateIntensityLayer(),
                value.candidateRatingEnabled(), value.parallelWayAwareness(),
                value.allowUndownloadedAlignment(), value.adjustJunctionNodes(), value.simplifyEnabled(),
                halfWidthPixels, value.crossSectionStepPx(), value.simplifyTolerancePx(),
                value.inferenceMode(), value.inferenceZoom(), value.validationZoom(),
                value.searchHalfWidthMeters(), value.sampleStepMeters(),
                value.intensitySamplingMode(), value.cacheBuster()), config.cleanup());
    }

    private static double latitude(double meters) {
        return Math.toDegrees(meters / 6_378_137.0);
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

    private static Fixture fiveNodeFixture() {
        DataSet dataSet = new DataSet();
        List<Node> nodes = List.of(
                loadedNode(101, 0.0, longitude(-8)),
                loadedNode(102, 0.0, longitude(-4)),
                loadedNode(103, 0.0, longitude(0)),
                loadedNode(104, 0.0, longitude(4)),
                loadedNode(105, 0.0, longitude(8)));
        Way way = new Way();
        way.setNodes(nodes);
        way.setOsmId(110, 1);
        way.setModified(false);
        nodes.forEach(dataSet::addPrimitive);
        dataSet.addPrimitive(way);
        return new Fixture(dataSet, new SelectionContext(way, 0, 4, nodes,
                Set.of(nodes.get(0), nodes.get(nodes.size() - 1))));
    }

    private static void assertOrdinaryInteriorAuthority(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor closure,
            SelectionContext selection) {
        List<PrimitiveKey> keys = selection.segmentNodes().stream()
                .map(node -> PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId())).toList();
        Set<PrimitiveKey> interiors = Set.copyOf(keys.subList(1, keys.size() - 1));
        Set<PrimitiveKey> boundaries = Set.of(keys.get(0), keys.get(keys.size() - 1));
        assertTrue(closure.editableExistingKeys().contains(PrimitiveKey.existing(
                PrimitiveKey.Type.WAY, selection.way().getUniqueId())));
        assertEquals(interiors, closure.movableExistingNodeKeys());
        assertEquals(Set.of(), closure.removableExistingNodeKeys());
        assertEquals(boundaries, closure.protectedExistingNodeKeys());
        Set<PrimitiveKey> all = new java.util.LinkedHashSet<>();
        all.addAll(closure.movableExistingNodeKeys());
        all.addAll(closure.protectedExistingNodeKeys());
        all.addAll(closure.removableExistingNodeKeys());
        assertEquals(Set.copyOf(keys), all);
    }
    private record Fixture(DataSet dataSet, SelectionContext selection) { }
}

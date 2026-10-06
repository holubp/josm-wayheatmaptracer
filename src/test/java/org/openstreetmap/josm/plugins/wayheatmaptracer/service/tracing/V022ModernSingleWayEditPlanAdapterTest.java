package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ProductionBundleFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.ManualJunctionEligibility;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ApplyAlignmentEditPlanCommand;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.VisibleSourceLockedApplyValidator;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Detached Route-to-plan and real command boundary regressions for the supported single-way slice. */
class V022ModernSingleWayEditPlanAdapterTest {
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void applyRejectsEvidenceWhoseDerivationDisagreesWithCapturedIntensityMode() throws Exception {
        LiveBPreviewService.Computed computed = compute(fixture(), TrackerMode.CORRIDOR_AWARE);
        LiveBPreviewService.Captured source = computed.captured();
        LiveBPreviewService.Captured inconsistent = new LiveBPreviewService.Captured(
                source.raster(), source.managedRaster(), source.specification(), source.network(),
                source.sourceGeographic(), source.sourceMetric(), source.outputGrid(), source.palette(),
                source.searchRadiusMeters(), source.sampleStepMeters(), source.settingsHash(),
                source.parameterHash(), source.cleanup(), source.geometryMode(), source.engine(),
                source.projectionCode(), source.junctionDecision(), source.intervalPartition(),
                IntensitySamplingMode.DIRECT_VALUE);
        LiveBPreviewService.Computed mismatched = new LiveBPreviewService.Computed(inconsistent,
                computed.evidence(), computed.request(), computed.pipeline(), computed.options(),
                computed.counters(), null);

        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.SOURCE_LINEAGE_UNAVAILABLE,
                new ModernSingleWayEditPlanAdapter().assess(mismatched, 0).availability());
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
    @EnumSource(value = TrackerMode.class, mode = EnumSource.Mode.EXCLUDE, names = {"LEGACY_V02"})
    void t164RasterEvidenceProducesOneDeterministicDetachedPlanWithoutMutation(
            TrackerMode mode) throws Exception {
        Fixture fixture = fixture();
        List<String> beforeLive = state(fixture);
        LiveBPreviewService.Computed computed = compute(fixture, mode);
        ModernTracePipeline.Route route = computed.pipeline().routes().get(0);
        ModernSingleWayEditPlanAdapter adapter = new ModernSingleWayEditPlanAdapter();

        AlignmentEditPlan first = adapter.adapt(computed, 0);
        AlignmentEditPlan repeat = adapter.adapt(computed, 0);

        assertEquals(first.canonicalHash(), repeat.canonicalHash());
        assertTrue(computed.evidence().fields().values().stream()
            .mapToLong(field -> (long) field.width() * field.height()).sum() > 250_000,
            "production-sized scalar evidence must cross the v1 array limit");
        assertEquals(first, FrozenReplayCodec.decodeEditPlan(FrozenReplayCodec.encodeEditPlan(first)));
        var live = Format15ProductionBundleFactory.createLive("test",
            new FrozenReplayInput(computed.request(), computed.evidence(),
                computed.captured().network(), computed.options()), computed.pipeline(),
            "applied", "visible-layer", 0, first, true, true);
        assertEquals(computed.evidence().canonicalHash(), FrozenReplayCodec.decode(
            live.artifact("frozen-input.bin").bytes()).evidence().canonicalHash());
        assertEquals(first, FrozenReplayCodec.decodeEditPlan(
            live.artifact("frozen-edit-plan.bin").bytes()));
        assertTrue(live.artifactNames().containsAll(Set.of("original-geometry.json",
            "raw-route.json", "final-route.json", "reviewed-route.json",
            "planned-geometry.json", "applied-geometry.json", "edit-plan-identity.json")));
        if (mode == TrackerMode.CORRIDOR_AWARE) {
            FrozenReplayInput captured = new FrozenReplayInput(computed.request(),
                computed.evidence(), computed.captured().network(), computed.options());
            var review = Format15ProductionBundleFactory.createLive("test", captured,
                computed.pipeline(), "review-required", "visible-layer", 0, first, false, false);
            var confirmed = Format15ProductionBundleFactory.createLive("test", captured,
                computed.pipeline(), "confirmed", "visible-layer", 0, first, true, false);
            assertTrue(review.artifactNames().contains("planned-geometry.json"));
            assertFalse(review.artifactNames().contains("reviewed-route.json"));
            assertTrue(confirmed.artifactNames().contains("reviewed-route.json"));
            assertFalse(confirmed.artifactNames().contains("applied-geometry.json"));
        }
        AlignmentEditPlan wrongRoute = new AlignmentEditPlan(first.selectedWayKey(),
            first.selectedRange(), first.before(), first.after(), first.metricFrame(),
            first.permissions(), first.settingsHash(), first.evidenceHash(),
            first.parameterHash(), "wrong-route", first.finalPreviewWays(), first.validation());
        FrozenReplayInput frozen = new FrozenReplayInput(computed.request(), computed.evidence(),
            computed.captured().network(), computed.options());
        assertThrows(IllegalArgumentException.class, () -> Format15ProductionBundleFactory.createLive(
            "test", frozen, computed.pipeline(), "applied", "visible-layer", 0,
            wrongRoute, true, true));
        assertThrows(IllegalArgumentException.class, () -> Format15ProductionBundleFactory.createLive(
            "test", frozen, computed.pipeline(), "applied", "visible-layer", 0,
            null, false, true));
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
    void relationJunctionCanLeaveAnExactFrozenFootprintWhileSlidingOutside() throws Exception {
        Fixture fixture = relationJunctionFixture();
        Way receiverWithContinuation = fixture.dataSet().getWays().stream()
                .filter(way -> way.getUniqueId() == 11).findFirst().orElseThrow();
        Node southPort = loadedNode(22, longitude(-31), 0.0);
        Node northPort = loadedNode(23, longitude(31), 0.0);
        Node farSouth = loadedNode(24, longitude(-70), 0.0);
        Node farNorth = loadedNode(25, longitude(70), 0.0);
        for (Node node : List.of(southPort, northPort, farSouth, farNorth)) {
            fixture.dataSet().addPrimitive(node);
        }
        receiverWithContinuation.setNodes(List.of(farSouth, southPort,
                receiverWithContinuation.getNode(0), fixture.way().getNode(7),
                receiverWithContinuation.getNode(2), northPort, farNorth));
        List<String> liveBefore = state(fixture);
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, true);
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(
                fixture.dataSet(), fixture.selection(), manualJunctionRaster(false, 100),
                config(TrackerMode.PROBABILISTIC),
                true, permissions));
        LiveBPreviewService.Computed computed = service.compute(captured[0], CancellationProbe.NONE);
        ModernSingleWayEditPlanAdapter.Assessment assessment =
                new ModernSingleWayEditPlanAdapter().assess(computed, 0);

        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE,
                assessment.availability(), assessment.detail());
        assertEquals(ManualJunctionEligibility.Reason.PARTICIPATING_RELATION,
                captured[0].junctionDecision().reason());
        assertEquals(ManualJunctionEligibility.Reason.PARTICIPATING_RELATION,
                assessment.junctionReason());
        assertEquals(null, assessment.manualReason());
        AlignmentEditPlan plan = assessment.plan().orElseThrow();
        var diagnostic = Format15ProductionBundleFactory.createLive("test",
                new FrozenReplayInput(computed.request(), computed.evidence(),
                        computed.captured().network(), computed.options()),
                computed.pipeline(), "preview-open", "visible-layer", 0, plan,
                false, false, computed.counters(), assessment.junctionReason());
        assertTrue(new String(diagnostic.artifact("attempt-status.json").bytes(),
                StandardCharsets.UTF_8).contains(
                        "\"manualJunctionReason\":\"PARTICIPATING_RELATION\""));
        assertEquals(Set.of(PrimitiveKey.existing(PrimitiveKey.Type.WAY, 10)),
                plan.affectedWayKeys());
        for (int id : new int[] {2, 3, 4, 5, 6, 9}) {
            PrimitiveKey key = PrimitiveKey.existing(PrimitiveKey.Type.NODE, id);
            assertEquals(plan.before().primitives().get(key), plan.after().primitives().get(key));
        }
        PrimitiveKey receiver = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 11);
        assertEquals(plan.before().primitives().get(receiver), plan.after().primitives().get(receiver));
        assertFalse(plan.before().primitives().get(
                PrimitiveKey.existing(PrimitiveKey.Type.NODE, 8)).equals(plan.after().primitives().get(
                PrimitiveKey.existing(PrimitiveKey.Type.NODE, 8))));
        assertEquals(liveBefore, state(fixture));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());

        List<Node> protectedNodes = List.of(fixture.way().getNode(2), fixture.way().getNode(3),
                fixture.way().getNode(4), fixture.way().getNode(5), fixture.way().getNode(6),
                fixture.way().getNode(7), fixture.dataSet().getNodes().stream()
                        .filter(node -> node.getUniqueId() == 20).findFirst().orElseThrow(),
                fixture.dataSet().getNodes().stream()
                        .filter(node -> node.getUniqueId() == 21).findFirst().orElseThrow());
        List<LatLon> protectedCoordinates = protectedNodes.stream()
                .map(node -> new LatLon(node.lat(), node.lon())).toList();
        Way receivingWay = fixture.dataSet().getWays().stream()
                .filter(way -> way.getUniqueId() == 11).findFirst().orElseThrow();
        List<Node> receiverOrder = List.copyOf(receivingWay.getNodes());
        List<RelationMember> relationMembers = fixture.dataSet().getRelations().iterator().next()
                .getMembers();
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(
                fixture.dataSet(), plan, plan.before().datasetIdentity(),
                () -> plan.before().sourceGeneration(), "Apply safe outside-junction alignment");
        onEdt(() -> UndoRedoHandler.getInstance().add(command));
        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
        assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), fixture.way().getNodes()
                .stream().map(node -> new org.openstreetmap.josm.plugins.wayheatmaptracer.model
                        .GeographicPoint(node.lat(), node.lon())).toList());
        assertEquals(protectedCoordinates, protectedNodes.stream()
                .map(node -> new LatLon(node.lat(), node.lon())).toList());
        assertEquals(receiverOrder, receivingWay.getNodes());
        assertEquals(relationMembers,
                fixture.dataSet().getRelations().iterator().next().getMembers());
        List<String> applied = state(fixture);
        onEdt(() -> UndoRedoHandler.getInstance().undo());
        assertEquals(liveBefore, state(fixture));
        onEdt(() -> UndoRedoHandler.getInstance().redo());
        assertEquals(applied, state(fixture));
        assertEquals(protectedCoordinates, protectedNodes.stream()
                .map(node -> new LatLon(node.lat(), node.lon())).toList());
    }

    @Test
    void taggedJunctionCannotInventAReceiverPortAfterFreezing() throws Exception {
        Fixture fixture = relationJunctionFixture();
        Node junction = fixture.way().getNode(7);
        junction.put("highway", "traffic_signals");
        Way receiver = fixture.dataSet().getWays().stream()
                .filter(way -> way.getUniqueId() == 11).findFirst().orElseThrow();
        Node southPortLike = loadedNode(22, longitude(-31), 0.0);
        Node northPortLike = loadedNode(23, longitude(31), 0.0);
        Node farSouth = loadedNode(24, longitude(-70), 0.0);
        Node farNorth = loadedNode(25, longitude(70), 0.0);
        for (Node node : List.of(southPortLike, northPortLike, farSouth, farNorth)) {
            fixture.dataSet().addPrimitive(node);
        }
        receiver.setNodes(List.of(farSouth, southPortLike, receiver.getNode(0),
                junction, receiver.getNode(2), northPortLike, farNorth));
        List<String> before = state(fixture);
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = new LiveBPreviewService().capture(
                fixture.dataSet(), fixture.selection(), manualJunctionRaster(false, 100),
                config(TrackerMode.PROBABILISTIC), true, permissions));
        assertEquals(ManualJunctionEligibility.Reason.INCOMPLETE_ARM,
                captured[0].junctionDecision().reason());
        LiveBPreviewService.Computed computed = new LiveBPreviewService().compute(
                captured[0], CancellationProbe.NONE);
        var assessment = new ModernSingleWayEditPlanAdapter().assess(computed, 0);
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.MANUAL_JUNCTION,
                assessment.availability());
        assertEquals(ManualJunctionEligibility.Reason.INCOMPLETE_ARM,
                assessment.manualReason());
        assertFalse(assessment.applyAvailable());
        assertTrue(assessment.plan().isEmpty());
        assertEquals(before, state(fixture));
    }

    @Test
    void manualJunctionWithoutSupportedOutsideConnectorHasVisibleNonApplyableReason() throws Exception {
        Fixture fixture = relationJunctionFixture();
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.VisibleRaster broken = manualJunctionRaster(true);
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        RecoveryPermissions permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, true);
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(
                fixture.dataSet(), fixture.selection(), broken,
                config(TrackerMode.PROBABILISTIC), true, permissions));
        LiveBPreviewService.Computed computed = service.compute(captured[0], CancellationProbe.NONE);
        var assessment = new ModernSingleWayEditPlanAdapter().assess(computed, 0);

        assertFalse(assessment.applyAvailable());
        assertTrue(assessment.detail().contains("connector"), assessment.detail());
        assertTrue(assessment.detail().contains(
                "Adjust this junction manually, then run alignment again."));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
    }

    @Test
    void t165SelectedSubrangeRetainsExactPrefixSuffixInFinalPreview() throws Exception {
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
    void cleanupEnabledAProducesAnExactImmutablePlanWhileBCleanupIsTypedUnavailable()
            throws Exception {
        Fixture fixture = fixture();
        GeometryCleanupConfig cleanup = GeometryCleanupPreset.BALANCED
                .apply(org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode.REDUCE_POINTS_ONLY);
        LiveBPreviewService.Computed engineA = compute(fixture, TrackerMode.CORRIDOR_AWARE,
                new AlignmentConfig(config(TrackerMode.CORRIDOR_AWARE).heatmap(), cleanup));
        LiveBPreviewService.Computed engineB = compute(fixture, TrackerMode.PROBABILISTIC,
                new AlignmentConfig(config(TrackerMode.PROBABILISTIC).heatmap(), cleanup));
        ModernSingleWayEditPlanAdapter adapter = new ModernSingleWayEditPlanAdapter();

        ModernSingleWayEditPlanAdapter.Assessment a = adapter.assess(engineA, 0);
        ModernSingleWayEditPlanAdapter.Assessment b = adapter.assess(engineB, 0);

        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE,
                a.availability());
        assertTrue(a.plan().isPresent());
        assertEquals(engineA.pipeline().routes().get(0).hypothesis().points().stream()
                        .map(engineA.evidence().coordinateFrame()::toGeographic).toList(),
                a.plan().orElseThrow().finalPreviewWays().get(engineA.request().selectedWayKey()));
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.CLEANUP_UNAVAILABLE_FOR_ENGINE,
                b.availability());
        assertTrue(b.plan().isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("taskFourAuthorityCases")
    void taskFourMatrixRequiresExactPlanAuthorityOrTypedUnavailableReason(
            TaskFourAuthorityCase testCase) throws Exception {
        Fixture fixture = testCase.subrange() ? fixtureWithPrefixAndSuffix() : fixture();
        TrackerMode engine = testCase.engine();
        GeometryCleanupConfig cleanup = testCase.cleanupMode() == GeometryCleanupMode.NONE
                ? GeometryCleanupConfig.disabled()
                : GeometryCleanupPreset.BALANCED.apply(testCase.cleanupMode());
        ManagedHeatmapConfig base = config(engine).heatmap();
        boolean managed = testCase.managedSource();
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig(managed ? "key" : "",
                managed ? "policy" : "", managed ? "signature" : "", managed ? "session" : "",
                base.activity(), base.color(), base.manualLayerName(), base.layerRegex(),
                testCase.geometryMode(), engine, base.verbose(), base.debug(),
                testCase.intensityMode() != IntensitySamplingMode.COLOR_MAPPING
                        || base.multiColorDetection(),
                testCase.intensityMode() != IntensitySamplingMode.COLOR_MAPPING
                        || base.aggregateAllColorSchemes(), base.showAggregateIntensityLayer(),
                base.candidateRatingEnabled(), base.parallelWayAwareness(), base.allowUndownloadedAlignment(),
                base.adjustJunctionNodes(), base.simplifyEnabled(), base.crossSectionHalfWidthPx(),
                base.crossSectionStepPx(), base.simplifyTolerancePx(), base.inferenceMode(),
                base.inferenceZoom(), base.validationZoom(), base.searchHalfWidthMeters(),
                base.sampleStepMeters(), testCase.intensityMode(), base.cacheBuster());
        AlignmentConfig attempt = new AlignmentConfig(heatmap, cleanup);
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured captured;
        if (managed) {
            LiveBPreviewService.ManagedCaptureSeed[] seed = new LiveBPreviewService.ManagedCaptureSeed[1];
            SwingUtilities.invokeAndWait(() -> seed[0] = service.captureManagedSeed(
                    fixture.dataSet(), fixture.selection(), attempt, "task-four-matrix"));
            BufferedImage image = matrixRasterImage();
            boolean[] valid = new boolean[image.getWidth() * image.getHeight()];
            java.util.Arrays.fill(valid, true);
            double worldPixelsAtEquator = Math.scalb(256.0, 15) / 2.0;
            double sourceHalfWidthWorldPixels = image.getWidth() / (2.0 * 2.0);
            captured = service.attachManagedRaster(seed[0], new ManagedModernPreviewSource.Raster(
                    image, valid, SupportedInputRasterTransform.webMercator(
                            15, worldPixelsAtEquator - sourceHalfWidthWorldPixels,
                            worldPixelsAtEquator - sourceHalfWidthWorldPixels, 2.0),
                    "hot", 15, "task-four-matrix",
                    new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(0L)));
        } else {
            LiveBPreviewService.Captured[] visible = new LiveBPreviewService.Captured[1];
            SwingUtilities.invokeAndWait(() -> visible[0] = service.capture(fixture.dataSet(),
                    fixture.selection(), raster(), attempt, engine == TrackerMode.DIRECTIONAL_IMAGE));
            captured = visible[0];
        }
        LiveBPreviewService.Computed computed = service.compute(captured, CancellationProbe.NONE);
        ModernSingleWayEditPlanAdapter.Assessment assessment =
                new ModernSingleWayEditPlanAdapter().assess(computed, 0);

        assertEquals(testCase.expectedAvailability(), assessment.availability(),
                () -> testCase + ": " + assessment.detail());
        if (testCase.expectedAvailability() == ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE) {
            AlignmentEditPlan plan = assessment.plan().orElseThrow();
            assertTrue(assessment.applyAvailable(), () -> testCase + ": " + plan.validation().findingCodes());
            Map<PrimitiveKey, List<EastNorth>> displayed = assessment.projectFinalPreviewWays(point ->
                    new EastNorth(point.longitudeDegrees(), point.latitudeDegrees()));
            assertEquals(plan.finalPreviewWays().keySet(), displayed.keySet());
            plan.finalPreviewWays().forEach((way, points) -> assertEquals(points.stream()
                    .map(point -> new EastNorth(point.longitudeDegrees(), point.latitudeDegrees())).toList(),
                    displayed.get(way)));
            assertEquals(plan.canonicalHash(),
                    PreviewReviewState.fromEditPlan("task-four-matrix", plan).exactEditPlanHash());
            assertEquals("Exact immutable plan available", assessment.detail());
        } else {
            assertTrue(assessment.plan().isEmpty(), testCase.toString());
            assertEquals(testCase.expectedDetail(), assessment.detail(), testCase.toString());
        }
    }

    static Stream<TaskFourAuthorityCase> taskFourAuthorityCases() {
        return Stream.of(
                new TaskFourAuthorityCase(TrackerMode.CORRIDOR_AWARE, false,
                        GeometryCleanupMode.NONE, false, AlignmentMode.PRECISE_SHAPE,
                        ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE,
                        "Exact immutable plan available"),
                new TaskFourAuthorityCase(TrackerMode.CORRIDOR_AWARE, true,
                        GeometryCleanupMode.REDUCE_POINTS_ONLY, true, AlignmentMode.PRECISE_SHAPE,
                        ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE,
                        "Exact immutable plan available"),
                new TaskFourAuthorityCase(TrackerMode.PROBABILISTIC, false,
                        GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE, false,
                        AlignmentMode.PRECISE_SHAPE,
                        ModernSingleWayEditPlanAdapter.ApplyAvailability.CLEANUP_UNAVAILABLE_FOR_ENGINE,
                        "Probabilistic B cleanup is unavailable until its exact final pipeline is supported"),
                new TaskFourAuthorityCase(TrackerMode.PROBABILISTIC, true,
                        GeometryCleanupMode.NONE, true, AlignmentMode.PRECISE_SHAPE,
                        ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE,
                        "Exact immutable plan available"),
                new TaskFourAuthorityCase(TrackerMode.PROBABILISTIC, true,
                        GeometryCleanupMode.NONE, true, AlignmentMode.PRECISE_SHAPE,
                        ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE,
                        "Exact immutable plan available", IntensitySamplingMode.DIRECT_LUMINANCE),
                new TaskFourAuthorityCase(TrackerMode.HYBRID, false,
                        GeometryCleanupMode.REDUCE_POINTS_ONLY, true, AlignmentMode.MOVE_EXISTING_NODES,
                        ModernSingleWayEditPlanAdapter.ApplyAvailability.PRECISE_SHAPE_REQUIRED,
                        "The existing nodes cannot safely represent the actual final route; use Precise Shape"),
                new TaskFourAuthorityCase(TrackerMode.DIRECTIONAL_IMAGE, false,
                        GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE, false,
                        AlignmentMode.MOVE_EXISTING_NODES,
                        ModernSingleWayEditPlanAdapter.ApplyAvailability.PRECISE_SHAPE_REQUIRED,
                        "The existing nodes cannot safely represent the actual final route; use Precise Shape"));
    }

    private static BufferedImage matrixRasterImage() {
        BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB);
        double centerRow = image.getHeight() / 2.0;
        for (int y = 0; y < image.getHeight(); y++) {
            int gray = (int) Math.round(255.0 * (0.02 + 0.80 * Math.exp(
                    -0.5 * (y - centerRow) * (y - centerRow) / (1.2 * 1.2))));
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, 0xff000000 | gray << 16 | gray << 8 | gray);
            }
        }
        return image;
    }

    private record TaskFourAuthorityCase(TrackerMode engine, boolean managedSource,
            GeometryCleanupMode cleanupMode, boolean subrange, AlignmentMode geometryMode,
            ModernSingleWayEditPlanAdapter.ApplyAvailability expectedAvailability,
            String expectedDetail, IntensitySamplingMode intensityMode) {
        private TaskFourAuthorityCase(TrackerMode engine, boolean managedSource,
                GeometryCleanupMode cleanupMode, boolean subrange, AlignmentMode geometryMode,
                ModernSingleWayEditPlanAdapter.ApplyAvailability expectedAvailability,
                String expectedDetail) {
            this(engine, managedSource, cleanupMode, subrange, geometryMode,
                    expectedAvailability, expectedDetail, IntensitySamplingMode.COLOR_MAPPING);
        }
        @Override
        public String toString() {
            return engine + "/" + (managedSource ? "managed" : "visible") + "/"
                    + cleanupMode + "/" + (subrange ? "subrange" : "full-way") + "/"
                    + geometryMode + "/" + intensityMode;
        }
    }

    @Test
    void projectedPreviewContainsExactlyEveryImmutablePlanWay() throws Exception {
        LiveBPreviewService.Computed computed = compute(fixture(), TrackerMode.CORRIDOR_AWARE);
        ModernSingleWayEditPlanAdapter.Assessment assessment =
                new ModernSingleWayEditPlanAdapter().assess(computed, 0);
        AlignmentEditPlan plan = assessment.plan().orElseThrow();

        Map<PrimitiveKey, List<EastNorth>> projected = assessment.projectFinalPreviewWays(point ->
                new EastNorth(point.longitudeDegrees(), point.latitudeDegrees()));

        assertEquals(plan.finalPreviewWays().keySet(), projected.keySet());
        plan.finalPreviewWays().forEach((way, points) -> assertEquals(points.stream()
                .map(point -> new EastNorth(point.longitudeDegrees(), point.latitudeDegrees())).toList(),
                projected.get(way)));
        assertThrows(UnsupportedOperationException.class,
                () -> projected.put(computed.request().selectedWayKey(), List.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> projected.values().iterator().next().add(new EastNorth(0.0, 0.0)));
        PreviewReviewState review = PreviewReviewState.fromEditPlan("candidate", plan);
        assertEquals(plan.canonicalHash(), review.exactEditPlanHash());
        assertTrue(review.confirm().confirmed());
    }

    @Test
    void selectedSubrangeContinuationRejectsAReversalAtItsStoredFinalBoundary() {
        assertTrue(ModernSingleWayEditPlanAdapter.continuationReverses(
                new MetricPoint(-2, 0), new MetricPoint(0, 0), new MetricPoint(-1, 0)));
        assertFalse(ModernSingleWayEditPlanAdapter.continuationReverses(
                new MetricPoint(-2, 0), new MetricPoint(0, 0), new MetricPoint(1, 0)));
    }

    @Test
    void T116_realProductionPlanAppliesAndReplaysExactSingleWayStateTwentyTimes(
            @TempDir Path directory)
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

        var prepared = Format15ProductionBundleFactory.createLive("test",
            new FrozenReplayInput(computed.request(), computed.evidence(),
                computed.captured().network(), computed.options()), computed.pipeline(),
            "applied", "visible-layer", 0, plan, false, true);
        onEdt(() -> AlignWayAction.applyWithPreparedDiagnostics(() -> prepared,
            () -> UndoRedoHandler.getInstance().add(command)));
        Path appliedArchive = directory.resolve("applied.zip");
        DiagnosticsRegistry.writeLatest(appliedArchive.toFile());
        var appliedStatus = Format15ArchiveReader.read(appliedArchive);
        assertTrue(new String(appliedStatus.artifact("attempt-status.json").orElseThrow().bytes(),
            StandardCharsets.UTF_8).contains("\"status\":\"applied\""));
        assertTrue(new String(appliedStatus.artifact("edit-plan-identity.json").orElseThrow().bytes(),
            StandardCharsets.UTF_8).contains(plan.canonicalHash()));
        assertTrue(appliedStatus.artifact("applied-geometry.json").isPresent());
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
    void T116b_changedVisibleEvidenceRejectsInitialLockedApplyWithoutUndoEntry() throws Exception {
        Fixture fixture = fixture();
        LiveBPreviewService.Computed computed = compute(fixture, TrackerMode.PROBABILISTIC);
        AlignmentEditPlan plan = plan(computed);
        NetworkSnapshotCapture.CapturedSnapshot receipt = onEdtValue(() ->
            NetworkSnapshotCapture.captureBound(fixture.dataSet(), computed.captured().specification()));
        LiveNetworkSnapshotValidator network = new LiveNetworkSnapshotValidator(receipt, plan,
            () -> plan.before().sourceGeneration());
        ApplyAlignmentEditPlanCommand command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), plan,
            new VisibleSourceLockedApplyValidator(network, new LiveBPreviewService(), computed.captured(),
                V022ModernSingleWayEditPlanAdapterTest::rasterWithChangedEvidence,
                null, () -> { }, message -> { }),
            "Apply modern visible alignment");
        List<String> before = state(fixture);

        assertThrows(IllegalStateException.class, () -> onEdt(() -> UndoRedoHandler.getInstance().add(command)));

        assertEquals(before, state(fixture));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
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
        AlignmentEditPlan blockedPlan = adapter.adapt(withRoute(computed, blockedRoute), 0);
        assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport.Disposition.HARD_BLOCKED,
                blockedPlan.validation().disposition());
        assertTrue(blockedPlan.validation().findingCodes()
                .contains("modern-final:PROTECTED_ASSIGNMENT_MISMATCH"));
        ModernSingleWayEditPlanAdapter.Assessment blockedAssessment =
                adapter.assess(withRoute(computed, blockedRoute), 0);
        assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.FINAL_GEOMETRY_BLOCKED,
                blockedAssessment.availability());
        assertTrue(blockedAssessment.plan().isPresent());
        assertFalse(blockedAssessment.applyAvailable());
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
            source.searchRadiusMeters(), source.sampleStepMeters(), settingsHash, parameterHash, source.cleanup(),
            source.geometryMode(), source.engine(), source.projectionCode());
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
        return compute(fixture, mode, config(mode));
    }

    private static LiveBPreviewService.Computed compute(Fixture fixture, TrackerMode mode,
            AlignmentConfig config) throws Exception {
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(
            fixture.dataSet(), fixture.selection(), raster(), config,
            mode == TrackerMode.DIRECTIONAL_IMAGE));
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

    private static Fixture relationJunctionFixture() {
        DataSet dataSet = new DataSet();
        Node endpoint = loadedNode(1, 0.0, longitude(-38));
        Node outside = loadedNode(8, longitude(4), longitude(-34));
        List<Node> selectedNodes = new ArrayList<>();
        selectedNodes.add(endpoint);
        selectedNodes.add(outside);
        for (int id = 2; id <= 6; id++) {
            selectedNodes.add(loadedNode(id, 0.0, longitude(-36 + 6 * (id - 1))));
        }
        Node junction = loadedNode(9, 0.0, 0.0);
        selectedNodes.add(junction);
        selectedNodes.forEach(dataSet::addPrimitive);
        Way selected = new Way();
        selected.setNodes(selectedNodes);
        selected.setOsmId(10, 1);
        selected.setModified(false);
        Node south = loadedNode(20, longitude(-8), 0.0);
        Node north = loadedNode(21, longitude(8), 0.0);
        dataSet.addPrimitive(south);
        dataSet.addPrimitive(north);
        Way receiver = new Way();
        receiver.setNodes(List.of(south, junction, north));
        receiver.setOsmId(11, 1);
        receiver.setModified(false);
        dataSet.addPrimitive(selected);
        dataSet.addPrimitive(receiver);
        Relation route = new Relation();
        route.setMembers(List.of(new RelationMember("", receiver)));
        route.setOsmId(12, 1);
        route.setModified(false);
        dataSet.addPrimitive(route);
        return new Fixture(dataSet, selected, new SelectionContext(selected, 0,
                selectedNodes.size() - 1, selectedNodes,
                Set.of(endpoint, selectedNodes.get(2), junction)));
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

    private static LiveBPreviewService.VisibleRaster manualJunctionRaster(boolean darkConnector) {
        return manualJunctionRaster(darkConnector, 50);
    }

    private static LiveBPreviewService.VisibleRaster manualJunctionRaster(boolean darkConnector,
            int halfExtentMeters) {
        int width = 6 * 2 * halfExtentMeters;
        int height = width;
        int[] argb = new int[width * height];
        for (int x = 0; x < width; x++) {
            double groundX = (x - width / 2.0) / RenderedHeatmapSampler.RASTER_SCALE;
            double centerY = groundX <= -34.0 ? 2.0
                    : groundX >= -30.0 ? 0.0 : (-30.0 - groundX) * 0.5;
            for (int y = 0; y < height; y++) {
                double groundY = (height / 2.0 - y) / RenderedHeatmapSampler.RASTER_SCALE;
                double distance = groundY - centerY;
                double intensity = darkConnector && groundX >= -34.0 && groundX <= -30.0 ? 0.0
                        : 0.02 + 0.80 * Math.exp(-0.5 * distance * distance / (1.2 * 1.2));
                int gray = (int) Math.round(255.0 * intensity);
                argb[y * width + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb,
                -halfExtentMeters, -halfExtentMeters, halfExtentMeters, halfExtentMeters, 1.0, 1.0,
                OptionalDouble.of(1.0), darkConnector ? "visible-dark-connector"
                        : "visible-manual-junction", "EPSG:3857");
    }

    private static LiveBPreviewService.VisibleRaster rasterWithChangedEvidence() {
        LiveBPreviewService.VisibleRaster original = raster();
        int[] changed = original.argb();
        changed[288 * original.width() + original.width() / 2] = 0xff000000;
        return new LiveBPreviewService.VisibleRaster(original.width(), original.height(), changed,
            original.minimumEast(), original.minimumNorth(), original.maximumEast(),
            original.maximumNorth(), original.projectionUnitsPerViewPixel(),
            original.groundMetersPerViewPixel(), original.nativePitchMeters(),
            original.sourceIdentity(), original.projectionCode());
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

    private static <T> T onEdtValue(java.util.concurrent.Callable<T> operation) throws Exception {
        Object[] value = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                value[0] = operation.call();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        @SuppressWarnings("unchecked")
        T result = (T) value[0];
        return result;
    }

    private static void onEdt(Runnable operation) throws Exception {
        try {
            SwingUtilities.invokeAndWait(operation);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw exception;
        }
    }

    private record Fixture(DataSet dataSet, Way way, SelectionContext selection) {
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
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

    @Test void sequentialHybridSubrangeApplyPreservesOutsideOccurrencesAndUndoHistory() throws Exception {
        verifyHybridSubrange(new double[] {-30, -8, 0, 8, 30});
    }

    @Test void denseOriginalOccurrencesSurviveSparseHybridPreviewWithoutChangingItsCurve() throws Exception {
        var east = new double[23]; east[0] = -30; east[22] = 30;
        for (int index = 1; index <= 21; index++) east[index] = -8 + .8 * (index - 1);
        verifyHybridSubrange(east);
    }

    @Test void coincidentTaggedOccurrencesSurviveHybridApplyUndoRedo() throws Exception {
        verifyHybridSubrange(new double[] {-45, -32, 0, 0, 32, 45}, true);
    }

    private static void verifyHybridSubrange(double[] east) throws Exception {
        verifyHybridSubrange(east, false);
    }

    private static void verifyHybridSubrange(double[] east, boolean coincident) throws Exception {
        var dataSet = new DataSet();
        var nodes = new ArrayList<Node>();
        for (int index = 0; index < east.length; index++) {
            var node = loadedNode(100 + index, longitude(east[index]));
            if (coincident && (index == 2 || index == 3)) {
                node.put("note", "synthetic protected occurrence " + index);
                node.setModified(false);
            }
            nodes.add(node); dataSet.addPrimitive(node);
        }
        var way = new Way(); way.setNodes(nodes); way.setOsmId(110, 1); way.setModified(false);
        dataSet.addPrimitive(way);
        var originalCoordinates = nodes.stream().map(Node::getCoor).toList();
        int last = nodes.size() - 2;
        var fixed = new java.util.HashSet<Node>(Set.of(nodes.get(1), nodes.get(last)));
        if (coincident) { fixed.add(nodes.get(2)); fixed.add(nodes.get(3)); }
        var selection = new SelectionContext(way, 1, last, nodes.subList(1, last + 1), fixed);
        var published = new AtomicReference<LiveBPreviewService.Computed>();
        var ready = new CountDownLatch(1);
        try (var session = new PreviewSessionController<LiveBPreviewService.Computed>(Runnable::run)) {
            var owner = session.open(() -> { });
            SwingUtilities.invokeAndWait(() -> new AlignWayAction.OrdinaryModernAttemptAssembly().start(
                    session, owner, routing(TrackerMode.LEGACY_THEN_B, AlignmentSourceMode.VISIBLE_LAYER),
                    dataSet, selection, "hybrid-selected-subrange",
                    (source, invocation, permissions) -> visibleRaster(),
                    (seed, invocation, context) -> { throw new AssertionError("Visible input must stay visible"); },
                    attempt -> { published.set(attempt.result()); ready.countDown(); }));
            assertTrue(ready.await(60, TimeUnit.SECONDS), session.currentAttempt().toString());
            var computed = published.get(); assertNotNull(computed);
            assertEquals(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange(1, last),
                    computed.request().selectedRange());
            if (east.length > 5 && !coincident) {
                var refined = computed.legacyB().result().batch().candidates().stream().filter(candidate ->
                        candidate.sections().stream().anyMatch(section -> section.source()
                                == org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.Source.B))
                        .toList();
                assertFalse(refined.isEmpty());
                for (var candidate : refined) assertEquals(Math.max(selection.segmentNodes().size(),
                        candidate.nativeVertexIndexes().size()), candidate.points().size(),
                        "Reuse all native slots before adding only the missing mandatory occurrences");
            }
            assertEquals(nodes, way.getNodes(), "Temporary Legacy/B geometry must not mutate live OSM");
            var adapter = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter();
            var plan = java.util.stream.IntStream.range(0, computed.pipeline().routes().size())
                    .filter(index -> computed.pipeline().routes().get(index).hypothesis().diagnostics()
                            .getOrDefault("hasBRefinement", 0.0) == 1.0)
                    .mapToObj(index -> adapter.assess(computed, index))
                    .filter(assessment -> assessment.availability() == org.openstreetmap.josm.plugins.wayheatmaptracer
                            .service.tracing.ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE)
                    .findFirst().orElseThrow(() -> new AssertionError("No usable actual B-refined subrange: "
                            + computed.pipeline().routes().stream().map(route -> route.hypothesis().diagnostics()
                                    + ": " + route.quality()).toList()
                            + "; plans=" + java.util.stream.IntStream.range(0, computed.pipeline().routes().size())
                                    .mapToObj(index -> adapter.assess(computed, index))
                                    .map(assessment -> assessment.availability() + ":" + assessment.detail()).toList()
                            + "; sections=" + computed.legacyB().result().batch().sections().stream()
                                    .map(section -> section.outcome() + ":" + section.reason()).toList()))
                    .plan().orElseThrow();
            SwingUtilities.invokeAndWait(() -> {
                var history = org.openstreetmap.josm.data.UndoRedoHandler.getInstance(); history.clean();
                try {
                    var capture = org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture
                            .captureBound(dataSet, computed.captured().specification());
                    var validator = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator(
                            capture, plan, () -> plan.before().sourceGeneration());
                    history.add(new org.openstreetmap.josm.plugins.wayheatmaptracer.util.ApplyAlignmentEditPlanCommand(
                            dataSet, plan, validator, "Apply Hybrid subrange test"));
                    assertEquals(1, history.getUndoCommands().size());
                    assertTrue(way.getNodes().containsAll(nodes), "Every non-removable occurrence must retain its identity");
                    assertEquals(nodes.get(0), way.firstNode()); assertEquals(nodes.get(last + 1), way.lastNode());
                    for (int index : new int[] {0, 1, last, last + 1})
                        assertEquals(originalCoordinates.get(index), nodes.get(index).getCoor());
                    for (var node : fixed) assertEquals(originalCoordinates.get(nodes.indexOf(node)), node.getCoor());
                    if (coincident) {
                        assertTrue(way.getNodes().indexOf(nodes.get(2)) < way.getNodes().indexOf(nodes.get(3)));
                        assertEquals("synthetic protected occurrence 2", nodes.get(2).get("note"));
                        assertEquals("synthetic protected occurrence 3", nodes.get(3).get("note"));
                    }
                    var applied = way.getNodes().stream().map(Node::getCoor).toList();
                    history.undo();
                    assertEquals(nodes, way.getNodes());
                    assertEquals(originalCoordinates, way.getNodes().stream().map(Node::getCoor).toList());
                    assertTrue(dataSet.allPrimitives().stream().noneMatch(primitive -> primitive.isModified()));
                    history.redo(); assertEquals(applied, way.getNodes().stream().map(Node::getCoor).toList());
                    history.undo();
                } finally { history.clean(); }
            });
        }
    }

    @Test void managedSequentialHybridUsesAcquiredNativePixelsAndKeepsReplaySource(@TempDir Path directory)
            throws Exception {
        verifyManagedSequentialHybrid(directory,false);
    }

    @Test void managedHybridAdmitsAtMostTwoParentsAcrossAllRequestedSources(@TempDir Path directory)
            throws Exception {
        verifyManagedSequentialHybrid(directory,true);
    }

    private static void verifyManagedSequentialHybrid(Path directory, boolean allSources) throws Exception {
        var fixture = fixture();
        var config = config(TrackerMode.LEGACY_THEN_B,allSources,allSources);
        var recovery = RecoverySettings.defaults(7.01);
        var tracing = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION, TrackerMode.LEGACY_THEN_B,
                recovery, false, AlignmentSourceMode.MANAGED_TILES);
        var routing = AlignWayAction.resolveOrdinaryAction(tracing, config, () -> "unused", () -> "unused");
        var decoder = new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier();
        var fetches = new AtomicInteger();
        try (var coordinator = new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator(
                (request, credentials) -> {
                    fetches.incrementAndGet();
                    var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
                    double pitch = 156543.03392804097 / Math.pow(2, request.address().zoom()) / 2;
                    double equator = Math.scalb(512.0, request.address().zoom() - 1);
                    for (int y = 0; y < 512; y++) {
                        double north = (equator - (request.address().y() * 512.0 + y + .5)) * pitch;
                        double intensity = .02 + .8 * Math.exp(-.5 * Math.pow((north - 2) / 1.8, 2));
                        int gray = (int) Math.round(255 * intensity);
                        // Real managed heatmap tiles have transparent empty background. An almost
                        // solid opaque black synthetic tile is correctly rejected as a placeholder.
                        int alpha = intensity > .025 ? 0xFF000000 : 0;
                        for (int x = 0; x < 512; x++) image.setRGB(x, y,
                                alpha | gray << 16 | gray << 8 | gray);
                    }
                    var bytes = new java.io.ByteArrayOutputStream();
                    try { javax.imageio.ImageIO.write(image, "png", bytes); }
                    catch (java.io.IOException failure) { throw new AssertionError("Synthetic PNG encoding failed", failure); }
                    return new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TransportResponse(
                            org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus.SUCCESS_NETWORK,
                            200, "image/png", bytes.toByteArray(), null, java.time.Duration.ZERO, "");
                }, new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache(directory, decoder),
                decoder, org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy.defaults());
                var session = new PreviewSessionController<LiveBPreviewService.Computed>(Runnable::run)) {
            coordinator.updateActiveGeneration(new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration(0));
            var published = new AtomicReference<LiveBPreviewService.Computed>();
            var ready = new CountDownLatch(1);
            var owner = session.open(() -> { });
            SwingUtilities.invokeAndWait(() -> new AlignWayAction.OrdinaryModernAttemptAssembly().startWithSources(
                    session, owner, routing, fixture.dataSet(), fixture.selection(), "synthetic-managed-legacy-b",
                    (source, invocation, permissions) -> { throw new AssertionError("Managed source must be used"); },
                    (seed, invocation, context) -> new ManagedModernPreviewSource(coordinator).acquireSources(
                            seed.sourceGeographic(), invocation.config().effectiveHeatmap(), seed.sourceIdentity(),
                            org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot.fromConfig(null), context),
                    coordinator, attempt -> { published.set(attempt.result()); ready.countDown(); }));
            assertTrue(ready.await(allSources ? 120 : 30, TimeUnit.SECONDS), session.currentAttempt().toString());
            var computed = published.get();
            assertNotNull(computed.legacyB());
            assertNull(computed.captured().raster());
            assertNotNull(computed.legacyB().managedSource());
            if (!allSources) verifyManagedDiscoveryReservationBoundary(computed);
            assertEquals(config.heatmap().searchHalfWidthMeters(), computed.captured().searchRadiusMeters(),
                    "The evidence halo must not widen the search or edit permission");
            assertEquals(recovery.ordinaryRadiusMeters(), computed.captured().specification().permissions().ordinaryRadiusMeters());
            assertTrue(fetches.get() > 0);
            assertFalse(computed.legacyB().result().alternatives().isEmpty());
            assertFalse(computed.pipeline().routes().isEmpty());
            assertTrue(computed.legacyB().result().batch().sections().stream()
                    .anyMatch(section -> section.execution().isPresent()),
                    computed.legacyB().result().batch().sections().stream().map(section ->
                        section.reason().name() + ": " + section.section().reference()).toList().toString());
            if (allSources) {
                assertTrue(computed.detectorAttempts().size() >= 3);
                long parents = computed.productionRuns().stream().mapToLong(run -> run.legacyB().result().batch().sections()
                        .stream().map(section -> section.section().parentSeedId()).distinct().count()).sum();
                assertTrue(parents <= 2,"Global retained parents: " + parents);
                assertTrue(computed.productionRuns().stream().mapToInt(run -> run.pipeline().routes().size()).sum() <= 6);
                var aggregate = computed.productionRuns().stream().filter(run -> run.options().sourceTier()
                        .equals("all-colors-combined")).findFirst().orElseThrow();
                assertFalse(aggregate.legacyB().result().batch().sections().isEmpty(),
                        "A complete aggregate must retain its representative before a second native rank");
                var usage = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage.none();
                for (var run : computed.productionRuns()) usage = usage.plus(run.legacyB().result().batch().usage());
                var limits = org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults();
                assertTrue(usage.pairVisits() <= limits.maximumPairVisits());
                assertTrue(usage.transitions() <= limits.maximumTransitions());
                assertTrue(usage.rawAlternatives() <= limits.maximumRawAlternatives());
                assertTrue(usage.distinctAlternatives() <= limits.maximumDistinctAlternatives());
                int beforeSourceReplays = fetches.get();
                for (var run : computed.productionRuns()) {
                    var sourceBundle = AlignWayAction.createModernDiagnostics(computed, run, "preview-open", 0,
                            null, false, false, null);
                    var sourceFrozen = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec
                            .decode(sourceBundle.artifact("frozen-input.bin").bytes());
                    var sourceReplay = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ReplayRunner
                            .replay(sourceFrozen,
                                    org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel.FINAL_GEOMETRY,
                                    TrackerMode.LEGACY_THEN_B);
                    assertEquals(run.pipeline().routes().stream().map(route -> route.hypothesis().points()).toList(),
                            sourceReplay.routes().stream().map(route -> route.hypothesis().points()).toList(),
                            "Every source must replay its own admitted parents and actual allocation: "
                                    + run.options().sourceTier());
                }
                assertEquals(beforeSourceReplays, fetches.get(), "Every source replay must remain offline");
                org.junit.jupiter.api.Assertions.assertAll(
                        () -> verifyAttemptResourceStop(computed,fixture,config,false),
                        () -> verifyAttemptResourceStop(computed,fixture,config,true));
            }
            var bundle = AlignWayAction.createModernDiagnostics(computed, computed, "preview-open", 0,
                    null, false, false, null);
            var frozen = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec
                    .decode(bundle.artifact("frozen-input.bin").bytes());
            assertNotNull(frozen.legacyB().managedSource());
            int beforeReplay = fetches.get();
            var replay = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ReplayRunner
                    .replay(frozen, org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel.FINAL_GEOMETRY,
                            TrackerMode.LEGACY_THEN_B);
            assertEquals(computed.pipeline().routes().stream().map(route -> route.hypothesis().points()).toList(),
                    replay.routes().stream().map(route -> route.hypothesis().points()).toList());
            assertEquals(beforeReplay, fetches.get(), "Offline production replay must not acquire more tiles");
        }
    }

    private static void verifyManagedDiscoveryReservationBoundary(LiveBPreviewService.Computed computed) {
        var source = computed.legacyB().managedSource();
        var profiles = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyManagedProfileSampler()
                .sample(source, org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE)
                .get(0).profiles();
        long pixels = source.mosaics().values().stream().mapToLong(mosaic -> (long) mosaic.width() * mosaic.height()).sum();
        long samples = profiles.stream().mapToLong(profile -> profile.intensitySamples().size()).sum();
        long required = 12L * pixels + 4096L * samples;
        assertTrue(required > 0 && required < org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.MAX_BYTES);
        for (boolean exact : new boolean[] {true, false}) {
            var calls = new AtomicInteger();
            var ledger = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger(
                    exact ? required : required - 1);
            try (var owner = ledger.rootOwner()) {
                var runner = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBProductionRunner();
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe cancelAtSampling =
                        () -> calls.incrementAndGet() >= 2;
                if (exact) {
                    assertThrows(java.util.concurrent.CancellationException.class, () -> runner.discoverManaged(
                            computed.legacyB().input(), source, cancelAtSampling, owner,
                            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.Deadline.production()));
                    assertEquals(2, calls.get(), "Exact reservation reaches native sampling before controlled cancellation");
                } else {
                    assertThrows(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.ResourceLimitException.class,
                            () -> runner.discoverManaged(computed.legacyB().input(), source, cancelAtSampling, owner,
                                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.Deadline.production()));
                    assertEquals(1, calls.get(), "One byte short must refuse before native sampler execution");
                }
                assertEquals(0, owner.currentBytes(), "Both refusal and cancellation release all working ownership");
            }
        }
    }

    /** Exercise the real owned multi-source boundary with deterministic allocation refusals. */
    private static void verifyAttemptResourceStop(LiveBPreviewService.Computed capturedRun, Fixture fixture,
            AlignmentConfig config, boolean discoveryRefusal) throws Exception {
        var service = new LiveBPreviewService();
        var captured = capturedRun.captured();
        var partition = captured.intervalPartition();
        if (partition == null) partition = org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner
                .partition(captured.network(),captured.specification());
        var method = LiveBPreviewService.class.getDeclaredMethod("computeLegacyBAttempt",
                LiveBPreviewService.Captured.class,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner.Partition.class,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.class,
                LiveBPreviewService.LegacyBInvocation.class,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner.class,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.Deadline.class,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.class);
        method.setAccessible(true);
        var refused = new java.util.concurrent.atomic.AtomicBoolean();
        var invocation = new AtomicReference<LiveBPreviewService.LegacyBInvocation>();
        SwingUtilities.invokeAndWait(() -> invocation.set(LiveBPreviewService.LegacyBInvocation.capture(fixture.selection(),config)));
        try (var owner = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger
                .production().rootOwner()) {
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe probe = () -> {
                if (discoveryRefusal && !refused.get() && Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame ->
                        frame.getMethodName().equals("captureManagedAggregateEvidence"))) {
                    refused.set(true);
                    // Real ledger refusal after selected-source discovery owns retained data.
                    owner.reserve(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.MAX_BYTES);
                    throw new AssertionError("The full ledger reservation must fail");
                }
                return false;
            };
            var limits = discoveryRefusal ? org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults()
                    : new org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets(96,1,1,1,1);
            var result = (LiveBPreviewService.Computed) method.invoke(service,captured,partition,probe,
                    invocation.get(),owner,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.Deadline.production(),limits);
            assertFalse(result.pipeline().routes().isEmpty(),"Completed Legacy preview must survive a resource stop");
            if (discoveryRefusal) {
                assertTrue(refused.get(),"The optional aggregate discovery must reach the real ledger refusal");
                assertFalse(result.legacyB().bInferenceAllowed(),"Discovery resource stop must prevent starting B");
                assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage.none(),
                        result.legacyB().result().batch().usage());
            } else {
                assertTrue(result.legacyB().result().batch().sections().stream().anyMatch(section ->
                        section.execution().isPresent() && section.execution().orElseThrow().run().result().status()
                            == org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet.Status.RESOURCE_LIMIT));
                var aggregate = result.productionRuns().stream().filter(run -> run.options().sourceTier()
                        .equals("all-colors-combined")).findFirst().orElseThrow();
                assertFalse(aggregate.legacyB().bInferenceAllowed(),"A native resource stop must prevent B on later sources");
                assertFalse(aggregate.pipeline().routes().isEmpty(),"Admitted aggregate Legacy result must survive");
                assertTrue(aggregate.legacyB().result().batch().sections().stream().allMatch(section -> section.execution().isEmpty()));
            }
            for (var run : result.productionRuns()) {
                if (run.pipeline().routes().isEmpty()) continue;
                var bundle = AlignWayAction.createModernDiagnostics(result,run,"preview-open",0,null,false,false,null);
                var frozen = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec
                        .decode(bundle.artifact("frozen-input.bin").bytes());
                var policy = frozen.legacyB().parentPolicy().orElseThrow();
                assertEquals(run.legacyB().bInferenceAllowed(),policy.bInferenceAllowed());
                if (!policy.bInferenceAllowed()) assertEquals(
                        org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.Reason.RESOURCE_LIMIT,
                        policy.unavailableReason());
                var replay = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ReplayRunner
                        .replay(frozen,org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel.FINAL_GEOMETRY,
                                TrackerMode.LEGACY_THEN_B);
                assertEquals(run.pipeline().routes().stream().map(route -> route.hypothesis().points()).toList(),
                        replay.routes().stream().map(route -> route.hypothesis().points()).toList(),
                        "Resource-stopped source must replay its retained actual geometry");
            }
        }
    }

    @Test
    void productionAssemblyCapturesComputesAndPublishesAllOrdinaryModernRoutes(@TempDir Path directory)
            throws Exception {
        List<RouteCase> cases = List.of(
                new RouteCase(TrackerMode.CORRIDOR_AWARE, AlignmentSourceMode.VISIBLE_LAYER, false),
                new RouteCase(TrackerMode.CORRIDOR_AWARE, AlignmentSourceMode.MANAGED_TILES, true),
                new RouteCase(TrackerMode.PROBABILISTIC, AlignmentSourceMode.VISIBLE_LAYER, false),
                new RouteCase(TrackerMode.PROBABILISTIC, AlignmentSourceMode.MANAGED_TILES, true),
                new RouteCase(TrackerMode.LEGACY_THEN_B, AlignmentSourceMode.VISIBLE_LAYER, false),
                new RouteCase(TrackerMode.DIRECTIONAL_IMAGE, AlignmentSourceMode.VISIBLE_LAYER, false));

        for (RouteCase routeCase : cases) {
            Fixture fixture = fixture();
            AlignmentConfig config = routeCase.engine() == TrackerMode.PROBABILISTIC
                    ? incompatibleBConfig() : routeCase.engine() == TrackerMode.LEGACY_THEN_B
                            ? new AlignmentConfig(incompatibleBConfig().heatmap()
                                    .withTrackerMode(TrackerMode.LEGACY_THEN_B), GeometryCleanupConfig.disabled())
                            : config(routeCase.engine());
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
            if (routeCase.engine() == TrackerMode.LEGACY_THEN_B) {
                assertNotNull(computed.legacyB());
                assertFalse(computed.legacyB().result().alternatives().isEmpty());
                assertTrue(computed.legacyB().result().batch().sections().stream().anyMatch(section ->
                        section.execution().isPresent()));
                assertTrue(computed.pipeline().routes().stream().allMatch(route ->
                        route.hypothesis().posteriorProbability().isEmpty()));
                assertTrue(computed.legacyB().input().simplify());
                assertEquals(AlignWayAction.ModernApplyPreflight.READY,
                        AlignWayAction.modernApplyPreflight(computed.captured(), routing.route().invocation().config()));
                var adapter = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                        .ModernSingleWayEditPlanAdapter();
                var plans = java.util.stream.IntStream.range(0, computed.pipeline().routes().size())
                        .mapToObj(index -> adapter.assess(computed, index)).toList();
                assertTrue(plans.stream().anyMatch(plan -> plan.availability()
                        == org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                            .ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE
                        || plan.availability() == org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing
                            .ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE), plans.toString());
                assertDoesNotThrow(() -> AlignWayAction.createModernDiagnostics(computed, computed,
                        "preview-open", 0, null, false, false, null));
                AlignWayAction.recordModernDiagnostics(computed, "preview-open", 0, null,
                        false, false, "ordinary-legacy-b-attempt");
                Path exported = directory.resolve("legacy-b.zip");
                DiagnosticsRegistry.writeLatest(exported.toFile());
                var archive = Format15ArchiveReader.read(exported);
                var frozen = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                        .FrozenReplayCodec.decode(archive.artifact("frozen-input.bin").orElseThrow().bytes());
                assertNotNull(frozen.legacyB());
                assertTrue(archive.artifact("legacy-b-provenance.bin").isPresent(),
                        "Export must retain actual Legacy seeds, B references and section outcomes");
                assertEquals(computed.legacyB().input().occurrences(), frozen.legacyB().occurrences());
                assertTrue(archive.artifact("legacy-b-native-work.json").isPresent(),
                        "Actual B section work must remain separately available from the composition");
                var replay = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                        .Format15ReplayRunner.replay(archive,
                            org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel.FINAL_GEOMETRY,
                            archive.sourceIdentityHash(), archive.parameterHash());
                assertEquals(computed.pipeline().routes().stream().map(route -> route.hypothesis().points()).toList(),
                        replay.routes().stream().map(route -> route.hypothesis().points()).toList());
                var changedArtifacts = new java.util.LinkedHashMap<>(archive.artifacts());
                byte[] changedProvenance = archive.artifact("legacy-b-provenance.bin").orElseThrow().bytes();
                changedProvenance[changedProvenance.length - 1] ^= 1;
                changedArtifacts.put("legacy-b-provenance.bin",
                        org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Artifact
                                .binary("legacy-b-provenance.bin", changedProvenance));
                var changedBundle = new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                        .Format15Bundle(archive.buildIdentity(), archive.sourceIdentityHash(),
                                archive.parameterHash(), changedArtifacts);
                var changedPath = directory.resolve("changed-hybrid-provenance.zip");
                org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15BundleWriter
                        .write(changedBundle, changedPath);
                var changedArchive = Format15ArchiveReader.read(changedPath);
                var mismatch = assertThrows(org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay
                        .format15.ReplayMismatchException.class, () -> org.openstreetmap.josm.plugins.wayheatmaptracer
                                .diagnostics.replay.format15.Format15ReplayRunner.replay(changedArchive,
                                        org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel.FINAL_GEOMETRY,
                                        changedArchive.sourceIdentityHash(), changedArchive.parameterHash()));
                assertTrue(mismatch.getMessage().contains("legacy-provenance"));
                var expectedBundle = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                        .Format15ProductionBundleFactory.createWithExpectedFinalOutput("test", frozen, TrackerMode.LEGACY_THEN_B);
                var expectedPath = directory.resolve("recorded-hybrid-output.zip");
                org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15BundleWriter
                        .write(expectedBundle, expectedPath);
                var expectedArchive = Format15ArchiveReader.read(expectedPath);
                var recordedReplay = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15
                        .Format15ReplayRunner.replay(expectedArchive,
                                org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel.FINAL_GEOMETRY,
                                expectedArchive.sourceIdentityHash(), expectedArchive.parameterHash());
                assertEquals(replay.routes().stream().map(route -> route.hypothesis().points()).toList(),
                        recordedReplay.routes().stream().map(route -> route.hypothesis().points()).toList());
                int refined = java.util.stream.IntStream.range(0, computed.pipeline().routes().size())
                        .filter(index -> computed.pipeline().routes().get(index).hypothesis().diagnostics()
                                .getOrDefault("hasBRefinement", 0.0) == 1.0
                            && plans.get(index).availability() == org.openstreetmap.josm.plugins.wayheatmaptracer
                                .service.tracing.ModernSingleWayEditPlanAdapter.ApplyAvailability.PLAN_AVAILABLE)
                        .findFirst().orElseThrow(() -> new AssertionError(
                                "A genuine B refinement must have an exact usable plan: " + plans));
                var exact = plans.get(refined).plan().orElseThrow();
                assertEquals(exact.canonicalHash(), adapter.adapt(computed, refined).canonicalHash());
                assertEquals(fixture.selection().segmentNodes(), fixture.selection().way().getNodes(),
                        "Legacy and B must not mutate the dataset before Apply");
                assertEquals(2, fixture.dataSet().getNodes().size());
                assertTrue(fixture.dataSet().allPrimitives().stream().noneMatch(primitive -> primitive.isModified()));
                SwingUtilities.invokeAndWait(() -> {
                    var history = org.openstreetmap.josm.data.UndoRedoHandler.getInstance();
                    history.clean();
                    try {
                        var capture = org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                                .NetworkSnapshotCapture.captureBound(fixture.dataSet(), computed.captured().specification());
                        var validator = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                                .LiveNetworkSnapshotValidator(capture, exact, () -> exact.before().sourceGeneration());
                        var command = new org.openstreetmap.josm.plugins.wayheatmaptracer.util
                                .ApplyAlignmentEditPlanCommand(fixture.dataSet(), exact, validator, "Apply Hybrid test");
                        history.add(command);
                        assertEquals(1, history.getUndoCommands().size());
                        var actual = fixture.selection().way().getNodes().stream().map(node ->
                                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint(
                                        node.lat(), node.lon())).toList();
                        assertEquals(exact.finalPreviewWays().get(exact.selectedWayKey()), actual);
                        history.undo();
                        assertEquals(fixture.selection().segmentNodes(), fixture.selection().way().getNodes());
                        assertEquals(2, fixture.dataSet().getNodes().size());
                        assertTrue(fixture.dataSet().allPrimitives().stream().noneMatch(primitive -> primitive.isModified()));
                        history.redo();
                        assertEquals(actual, fixture.selection().way().getNodes().stream().map(node ->
                                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint(
                                        node.lat(), node.lon())).toList());
                        history.undo();
                    } finally { history.clean(); }
                });
            }
            if (routeCase.engine() == TrackerMode.PROBABILISTIC) {
                assertTrue(computed.captured().cleanup().isDisabled());
                assertTrue(computed.options().cleanup().isDisabled());
                assertTrue(config.heatmap().simplifyEnabled());
                assertFalse(config.cleanup().isDisabled());
                assertEquals(AlignWayAction.ModernApplyPreflight.READY,
                        AlignWayAction.modernApplyPreflight(computed.captured(),
                                routing.route().invocation().config()));
            }
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
                    assertTrue(archive.artifact("numerical-policy.json").isPresent());
                    String settings = new String(archive.artifact("settings-resolution.json")
                            .orElseThrow().bytes(), StandardCharsets.UTF_8);
                    assertTrue(settings.contains("\"requestedSimplification\":true"));
                    assertTrue(settings.contains("\"effectiveSimplification\":false"));
                    assertTrue(settings.contains("\"requestedCleanupMode\":\"CONSTRAINED_SMOOTH_AND_REDUCE\""));
                    assertTrue(settings.contains("\"effectiveCleanupMode\":\"NONE\""));
                    var identities = java.util.regex.Pattern.compile(
                            "\\\"requestedIdentity\\\":\\\"([0-9a-f]{64})\\\",\\\"effectiveIdentity\\\":\\\"([0-9a-f]{64})\\\"")
                            .matcher(settings);
                    assertTrue(identities.find());
                    assertFalse(identities.group(1).equals(identities.group(2)));
                    assertFalse(settings.contains("key"));
                    assertFalse(settings.contains("signature"));
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

    @Test void ordinaryHybridCannotPublishAfterCancellationOrSupersessionAtTheEventQueue() throws Exception {
        for (boolean supersede : new boolean[] {false, true}) {
            var fixture = fixture();
            var originalNodes = fixture.selection().way().getNodes();
            var originalCoordinates = originalNodes.stream().map(Node::getCoor).toList();
            var queue = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
            var published = new ArrayList<TrackerMode>();
            try (var session = new PreviewSessionController<LiveBPreviewService.Computed>(queue::add)) {
                var assembly = new AlignWayAction.OrdinaryModernAttemptAssembly();
                var owner = session.open(() -> { });
                SwingUtilities.invokeAndWait(() -> assembly.start(session, owner,
                        routing(TrackerMode.LEGACY_THEN_B, AlignmentSourceMode.VISIBLE_LAYER),
                        fixture.dataSet(), fixture.selection(), "hybrid-queued-publication",
                        (source, invocation, permissions) -> visibleRaster(),
                        (seed, invocation, context) -> { throw new AssertionError("Visible Hybrid cannot acquire tiles"); },
                        attempt -> published.add(attempt.result().request().engine())));
                Runnable oldPublication = queue.poll(60, TimeUnit.SECONDS);
                assertNotNull(oldPublication, String.valueOf(session.currentAttempt()));
                assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob.State.VALIDATING,
                        session.currentAttempt().state());
                if (supersede) {
                    var next = session.open(() -> { });
                    SwingUtilities.invokeAndWait(() -> assembly.start(session, next,
                            routing(TrackerMode.PROBABILISTIC, AlignmentSourceMode.VISIBLE_LAYER),
                            fixture.dataSet(), fixture.selection(), "newer-b-queued-publication",
                            (source, invocation, permissions) -> visibleRaster(),
                            (seed, invocation, context) -> { throw new AssertionError("Visible B cannot acquire tiles"); },
                            attempt -> published.add(attempt.result().request().engine())));
                    Runnable newPublication = queue.poll(60, TimeUnit.SECONDS);
                    assertNotNull(newPublication, String.valueOf(session.currentAttempt()));
                    SwingUtilities.invokeAndWait(newPublication);
                    assertEquals(List.of(TrackerMode.PROBABILISTIC), published);
                    var terminal = session.currentAttempt();
                    SwingUtilities.invokeAndWait(oldPublication);
                    assertEquals(terminal, session.currentAttempt(), "Old publication cannot overwrite a newer terminal result");
                } else {
                    assertTrue(session.close(owner));
                    var terminal = session.currentAttempt();
                    assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentJob.State.CANCELLED,
                            terminal.state());
                    SwingUtilities.invokeAndWait(oldPublication);
                    assertEquals(terminal, session.currentAttempt());
                    assertTrue(published.isEmpty(), "Cancelled Hybrid cannot publish its completed computation");
                }
                assertEquals(originalNodes, fixture.selection().way().getNodes());
                assertEquals(originalCoordinates, originalNodes.stream().map(Node::getCoor).toList());
                assertTrue(fixture.dataSet().allPrimitives().stream().noneMatch(primitive -> primitive.isModified()));
            }
        }
    }

    @Test
    void unsupportedOneShotWidthFailsBeforeManagedTileAcquisition() throws Exception {
        Fixture fixture = fixture();
        AlignmentConfig requested = incompatibleBConfig().withSearchHalfWidthMetersOverride(9.0);
        TracingSettings tracing = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                TrackerMode.PROBABILISTIC, RecoverySettings.defaults(7.01), false,
                AlignmentSourceMode.MANAGED_TILES);
        var routing = AlignWayAction.resolveOrdinaryAction(tracing, requested,
                () -> "visible", () -> "legacy");
        AtomicInteger tileAcquisitions = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (PreviewSessionController<LiveBPreviewService.Computed> session =
                new PreviewSessionController<>(Runnable::run)) {
            PreviewSessionController.Owner owner = session.open(() -> { });
            SwingUtilities.invokeAndWait(() -> {
                try {
                    new AlignWayAction.OrdinaryModernAttemptAssembly().start(session, owner,
                            routing, fixture.dataSet(), fixture.selection(), "managed-selected-hot-g0",
                            (source, invocation, permissions) -> visibleRaster(),
                            (seed, invocation, context) -> {
                                tileAcquisitions.incrementAndGet();
                                return managedRaster(seed.sourceIdentity());
                            }, attempt -> { });
                } catch (RuntimeException exception) {
                    failure.set(exception);
                }
            });
        }
        assertInstanceOf(IllegalArgumentException.class, failure.get());
        assertEquals(0, tileAcquisitions.get());
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
        return config(engine,false,false);
    }

    private static AlignmentConfig config(TrackerMode engine, boolean mappings, boolean aggregate) {
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig(
                "key", "policy", "signature", "session", "all", "hot", "", ".*",
                AlignmentMode.PRECISE_SHAPE, engine, false, false,
                mappings, aggregate, false, false, false, false, false, false,
                7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15,
                7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    private static AlignmentConfig incompatibleBConfig() {
        ManagedHeatmapConfig base = config(TrackerMode.PROBABILISTIC).heatmap();
        ManagedHeatmapConfig saved = new ManagedHeatmapConfig(base.keyPairId(), base.policy(),
                base.signature(), base.sessionToken(), base.activity(), base.color(),
                base.manualLayerName(), base.layerRegex(), base.alignmentMode(),
                base.trackerMode(), base.verbose(), base.debug(), base.multiColorDetection(),
                base.aggregateAllColorSchemes(), base.showAggregateIntensityLayer(),
                base.candidateRatingEnabled(), base.parallelWayAwareness(),
                base.allowUndownloadedAlignment(), base.adjustJunctionNodes(), true,
                base.crossSectionHalfWidthPx(), base.crossSectionStepPx(),
                base.simplifyTolerancePx(), base.inferenceMode(), base.inferenceZoom(),
                base.validationZoom(), base.searchHalfWidthMeters(), base.sampleStepMeters(),
                base.intensitySamplingMode(), base.cacheBuster());
        return new AlignmentConfig(saved, GeometryCleanupPreset.BALANCED.apply());
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

package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentSourceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoverySettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TracingSettings;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15BundleWriter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ReplayRunner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ProductionBundleFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewSessionController;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TransportResponse;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ManagedSourceLockedApplyValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ManagedSourceReceipt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ApplyAlignmentEditPlanCommand;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.VisibleSourceLockedApplyValidator;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Public synthetic ordinary action assembly through the real Apply listener's preparation seam. */
class OrdinaryModernApplyPreparationTest {
    @TempDir Path cacheDirectory;

    @BeforeAll static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @BeforeEach void clearUndo() { UndoRedoHandler.getInstance().clean(); }
    @AfterEach void leaveUndoClean() { UndoRedoHandler.getInstance().clean(); }

    @Test
    void completeManagedAggregateChoiceAppliesItsOwnFinalPreviewThroughHostHistory() throws Exception {
        Fixture fixture = fixture(false, true);
        List<String> original = state(fixture.dataSet());
        ManagedHeatmapConfig base = config(TrackerMode.PROBABILISTIC).heatmap();
        ManagedHeatmapConfig requested = new ManagedHeatmapConfig(base.keyPairId(), base.policy(),
                base.signature(), base.sessionToken(), base.activity(), base.color(),
                base.manualLayerName(), base.layerRegex(), base.alignmentMode(), base.trackerMode(),
                base.verbose(), base.debug(), false, true, base.showAggregateIntensityLayer(),
                base.candidateRatingEnabled(), base.parallelWayAwareness(),
                base.allowUndownloadedAlignment(), base.adjustJunctionNodes(), base.simplifyEnabled(),
                base.crossSectionHalfWidthPx(), base.crossSectionStepPx(), base.simplifyTolerancePx(),
                base.inferenceMode(), base.inferenceZoom(), base.validationZoom(),
                base.searchHalfWidthMeters(), base.sampleStepMeters(),
                base.intensitySamplingMode(), base.cacheBuster());
        AlignmentConfig config = new AlignmentConfig(requested, GeometryCleanupConfig.disabled());
        String identity = "managed-selected-hot-g0";
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.ManagedCaptureSeed seed = edt(() -> service.captureManagedSeed(
                fixture.dataSet(), fixture.selection(), config, identity));
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) ->
                new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                        managedChoicePng(request.address().y(), request.address().color()),
                        null, Duration.ZERO, ""),
                new ManagedTileCache(cacheDirectory.resolve("aggregate-apply"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(0L));
            var sources = new ManagedModernPreviewSource(coordinator).acquireSources(
                    seed.sourceGeographic(), requested, identity, CredentialSnapshot.fromConfig(null),
                    () -> false);
            assertTrue(sources.provenCompleteAggregate(coordinator),
                    sources.aggregateAvailability() + " / " + sources.aggregateFailure()
                            + " / " + sources.zoomReceipts());
            var computed = service.compute(service.attachManagedSources(seed, sources, coordinator),
                    () -> false);
            assertEquals(2, computed.productionRuns().size());
            var aggregate = computed.productionRuns().get(1);
            assertEquals("all-colors-combined", aggregate.options().sourceTier());
            assertFalse(aggregate.pipeline().routes().isEmpty(),
                    "aggregate must yield real B routes: " + aggregate.pipeline().inference().status()
                            + " / " + aggregate.pipeline().inference().explanation());
            var previewBundle = AlignWayAction.createModernDiagnostics(computed, aggregate,
                    "preview-open", 0, null, false, false, null);
            String sourceChoices = new String(previewBundle.artifact("source-choices.json").bytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(sourceChoices.contains("\"selectedTier\":\"all-colors-combined\""));
            assertTrue(sourceChoices.contains("\"liveCompleteAggregateProof\":true"));
            assertTrue(sourceChoices.contains(aggregate.request().evidenceContentHash()));
            Path archivePath = cacheDirectory.resolve("aggregate-preview.zip");
            Format15BundleWriter.write(previewBundle, archivePath);
            var archive = Format15ArchiveReader.read(archivePath);
            assertEquals(ReplayLevel.FINAL_GEOMETRY, Format15ReplayRunner.replay(archive,
                    ReplayLevel.FINAL_GEOMETRY, archive.sourceIdentityHash(),
                    archive.parameterHash()).level());
            assertThrows(RuntimeException.class, () -> Format15ReplayRunner.replay(archive,
                    ReplayLevel.FULL_EDIT_PLAN, archive.sourceIdentityHash(),
                    archive.parameterHash()));
            var assessment = new ModernSingleWayEditPlanAdapter().assess(aggregate, 0);
            assertTrue(assessment.applyAvailable(), assessment.detail());
            var plan = assessment.plan().orElseThrow();
            PreviewReviewState review = review(plan);
            AtomicReference<ManagedHeatmapConfig> currentSettings = new AtomicReference<>(requested);
            ManagedSourceReceipt receipt = new ManagedSourceReceipt(coordinator, computed.captured(),
                    requested, "all-colors-combined", () -> coordinator, currentSettings::get,
                    computed.captured()::projectionCode);
            var prepared = edt(() -> AlignWayAction.prepareModernApply(fixture.dataSet(), aggregate,
                    0, "ordinary-route", review, () -> {
                        receipt.requireCurrent();
                        service.requireCurrent(fixture.dataSet(), computed.captured());
                    }, coordinator::activeGenerationValue,
                    (network, currentPlan) -> new ManagedSourceLockedApplyValidator(network, service,
                            computed.captured(), receipt::requireCurrent,
                            failure -> { throw new AssertionError(failure); })));
            assertEquals(original, state(fixture.dataSet()));
            edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
            assertNotEquals(original, state(fixture.dataSet()));
            assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
            edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
            assertEquals(original, state(fixture.dataSet()));
            currentSettings.set(new ManagedHeatmapConfig(requested.keyPairId(), requested.policy(),
                    requested.signature(), requested.sessionToken(), requested.activity(), requested.color(),
                    requested.manualLayerName(), requested.layerRegex(), requested.alignmentMode(),
                    requested.trackerMode(), requested.verbose(), requested.debug(),
                    requested.multiColorDetection(), false, requested.showAggregateIntensityLayer(),
                    requested.candidateRatingEnabled(), requested.parallelWayAwareness(),
                    requested.allowUndownloadedAlignment(), requested.adjustJunctionNodes(),
                    requested.simplifyEnabled(), requested.crossSectionHalfWidthPx(),
                    requested.crossSectionStepPx(), requested.simplifyTolerancePx(),
                    requested.inferenceMode(), requested.inferenceZoom(), requested.validationZoom(),
                    requested.searchHalfWidthMeters(), requested.sampleStepMeters(),
                    requested.intensitySamplingMode(), requested.cacheBuster()));
            assertThrows(IllegalStateException.class, receipt::requireCurrent,
                    "removed aggregate request invalidates the chosen source receipt");
            currentSettings.set(requested);
            coordinator.updateActiveGeneration(new ManagedTileGeneration(1L));
            assertThrows(IllegalStateException.class, receipt::requireCurrent,
                    "changed generation invalidates the chosen source receipt");
            coordinator.updateActiveGeneration(new ManagedTileGeneration(0L));
            edt(() -> { UndoRedoHandler.getInstance().redo(); return null; });
            assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
        }
    }

    @Test
    void aggregatePixelBudgetRefusalKeepsNativePreviewAndVisibleTypedWarning() throws Exception {
        Fixture fixture = diagonalBudgetFixture();
        ManagedHeatmapConfig base = config(TrackerMode.PROBABILISTIC).heatmap();
        ManagedHeatmapConfig requested = new ManagedHeatmapConfig(base.keyPairId(), base.policy(),
                base.signature(), base.sessionToken(), base.activity(), base.color(),
                base.manualLayerName(), base.layerRegex(), base.alignmentMode(), base.trackerMode(),
                base.verbose(), base.debug(), false, true, base.showAggregateIntensityLayer(),
                base.candidateRatingEnabled(), base.parallelWayAwareness(),
                base.allowUndownloadedAlignment(), base.adjustJunctionNodes(), base.simplifyEnabled(),
                base.crossSectionHalfWidthPx(), base.crossSectionStepPx(), base.simplifyTolerancePx(),
                base.inferenceMode(), 16, 16, base.searchHalfWidthMeters(), 10.0,
                base.intensitySamplingMode(), base.cacheBuster());
        LiveBPreviewService service = new LiveBPreviewService();
        var seed = edt(() -> service.captureManagedSeed(fixture.dataSet(), fixture.selection(),
                new AlignmentConfig(requested, GeometryCleanupConfig.disabled()),
                "managed-aggregate-budget"));
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) ->
                new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                        managedDiagonalPng(request.address().zoom(), request.address().x(),
                                request.address().y()),
                        null, Duration.ZERO, ""),
                new ManagedTileCache(cacheDirectory.resolve("aggregate-budget"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(0L));
            var sources = new ManagedModernPreviewSource(coordinator).acquireSources(
                    seed.sourceGeographic(), requested, seed.sourceIdentity(),
                    CredentialSnapshot.fromConfig(null), () -> false);
            assertEquals(ManagedModernPreviewSource.AggregateAvailability.BUDGET_UNAVAILABLE,
                    sources.aggregateAvailability());
            assertNull(sources.aggregateFailure());
            var computed = service.compute(service.attachManagedSources(seed, sources, coordinator),
                    () -> false);
            assertEquals(1, computed.productionRuns().size());
            assertEquals("selected-visible", computed.productionRuns().get(0).options().sourceTier());
            assertFalse(computed.pipeline().routes().isEmpty(),
                    computed.pipeline().inference().explanation());
            assertEquals(LiveBPreviewService.DetectorAttemptStatus.SOURCE_UNAVAILABLE,
                    computed.detectorAttempts().get(1).status());
            assertTrue(AlignWayAction.aggregateSourceWarning(computed)
                    .contains("budget"), "ordinary preview must disclose optional budget refusal");
        }
    }

    @Test
    void ordinaryManagedAggregateOwnsConfirmedTwoIslandApplyAndIntervalArchive() throws Exception {
        Fixture fixture = twoIslandFixture();
        List<String> original = state(fixture.dataSet());
        ManagedHeatmapConfig base = config(TrackerMode.PROBABILISTIC).heatmap();
        ManagedHeatmapConfig requested = new ManagedHeatmapConfig(base.keyPairId(), base.policy(),
                base.signature(), base.sessionToken(), base.activity(), base.color(),
                base.manualLayerName(), base.layerRegex(), base.alignmentMode(), base.trackerMode(),
                base.verbose(), base.debug(), false, true, base.showAggregateIntensityLayer(),
                base.candidateRatingEnabled(), base.parallelWayAwareness(),
                base.allowUndownloadedAlignment(), base.adjustJunctionNodes(), base.simplifyEnabled(),
                base.crossSectionHalfWidthPx(), base.crossSectionStepPx(), base.simplifyTolerancePx(),
                base.inferenceMode(), base.inferenceZoom(), base.validationZoom(),
                10.0, base.sampleStepMeters(),
                base.intensitySamplingMode(), base.cacheBuster());
        AlignmentConfig saved = new AlignmentConfig(requested, GeometryCleanupConfig.disabled());
        TracingSettings tracing = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                TrackerMode.PROBABILISTIC, RecoverySettings.defaults(10.0), false,
                AlignmentSourceMode.MANAGED_TILES);
        var routing = AlignWayAction.resolveOrdinaryAction(tracing, saved,
                () -> "visible", () -> "legacy");
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) ->
                new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                        managedChoicePng(request.address().x(), request.address().y(),
                                request.address().color(), 4.0), null, Duration.ZERO, ""),
                new ManagedTileCache(cacheDirectory.resolve("aggregate-two-island-apply"), decoder),
                decoder, TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(0L));
            LiveBPreviewService service = new LiveBPreviewService();
            var computed = ordinaryManagedSourcesResult(fixture, routing, coordinator,
                    "managed-selected-hot-g0");
            assertEquals(2, computed.productionRuns().size());
            var preview = new AlignWayAction.IntervalPreviewState(computed);
            assertEquals(2, preview.batch().partition().fixedIslands().size());
            preview.chooseSource(1);
            assertEquals("all-colors-combined", preview.selectedRun().options().sourceTier());
            assertEquals(3, preview.batch().runs().size());
            assertTrue(preview.assessment().plan().isPresent(), preview.batch().runs().stream()
                    .map(run -> run.result().inference().status() + ":" + run.routes().stream()
                            .map(route -> route.quality().findings().toString()).toList())
                    .toList().toString());
            var planned = preview.assessment().plan().orElseThrow();
            assertNotEquals(geometry(fixture.way()),
                    planned.finalPreviewWays().get(planned.selectedWayKey()));
            assertNotNull(preview.review());
            preview.confirmReview();
            assertTrue(preview.applyAvailable());
            var exactPlan = preview.currentPlanForApply();
            assertEquals(planned.canonicalHash(), exactPlan.canonicalHash());
            var bundle = AlignWayAction.createIntervalDiagnostics(computed, preview,
                    Format15ProductionBundleFactory.IntervalArtifactStatus.CONFIRMED);
            String inventory = new String(bundle.artifact("source-choices.json").bytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(inventory.contains("\"selectedTier\":\"all-colors-combined\""));
            assertTrue(inventory.contains(preview.selectedRun().evidence().canonicalHash()));
            Path archivePath = cacheDirectory.resolve("aggregate-two-island-preview.zip");
            Format15BundleWriter.write(bundle, archivePath);
            var archive = Format15ArchiveReader.read(archivePath);
            assertTrue(archive.artifact("interval-production.json").isPresent());
            var replay = Format15ReplayRunner.replayIntervals(archive,
                    archive.sourceIdentityHash(), archive.parameterHash());
            assertEquals("MATCH", replay.outputComponentStatus());
            assertEquals(planned.canonicalHash(), replay.assessment().plan().orElseThrow().canonicalHash());
            assertEquals(preview.selectedRun().evidence().canonicalHash(),
                    replay.batch().evidence().canonicalHash());
            assertEquals("all-colors-combined", replay.batch().options().sourceTier());
            assertThrows(RuntimeException.class, () -> Format15ReplayRunner.replay(archive,
                    ReplayLevel.FINAL_GEOMETRY, archive.sourceIdentityHash(),
                    archive.parameterHash()), "interval archive must not claim unreconstructable replay");
            ManagedSourceReceipt receipt = new ManagedSourceReceipt(coordinator, computed.captured(),
                    requested, "all-colors-combined", () -> coordinator, () -> requested,
                    computed.captured()::projectionCode);
            receipt.requireCurrent();
            var bound = edt(() -> NetworkSnapshotCapture.captureBound(fixture.dataSet(),
                    computed.captured().specification()));
            var network = new LiveNetworkSnapshotValidator(bound, exactPlan,
                    coordinator::activeGenerationValue);
            var command = new ApplyAlignmentEditPlanCommand(fixture.dataSet(), exactPlan,
                    new ManagedSourceLockedApplyValidator(network, service, computed.captured(),
                            receipt::requireCurrent,
                            failure -> { throw new AssertionError(failure); }),
                    "Apply managed two-island aggregate");
            assertEquals(original, state(fixture.dataSet()));
            edt(() -> { UndoRedoHandler.getInstance().add(command); return null; });
            assertEquals(exactPlan.finalPreviewWays().get(exactPlan.selectedWayKey()),
                    geometry(fixture.way()));
            assertNotEquals(original, state(fixture.dataSet()));
            var appliedBundle = AlignWayAction.createIntervalDiagnostics(computed, preview,
                    Format15ProductionBundleFactory.IntervalArtifactStatus.APPLIED_AFTER_REVIEW);
            Path appliedArchive = cacheDirectory.resolve("aggregate-two-island-applied.zip");
            Format15BundleWriter.write(appliedBundle, appliedArchive);
            var appliedRead = Format15ArchiveReader.read(appliedArchive);
            var appliedReplay = Format15ReplayRunner.replayIntervals(appliedRead,
                    appliedRead.sourceIdentityHash(), appliedRead.parameterHash());
            assertEquals("MATCH", appliedReplay.outputComponentStatus());
            assertEquals(exactPlan.canonicalHash(),
                    appliedReplay.assessment().plan().orElseThrow().canonicalHash());
            edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
            assertEquals(original, state(fixture.dataSet()));
            edt(() -> { UndoRedoHandler.getInstance().redo(); return null; });
            assertEquals(exactPlan.finalPreviewWays().get(exactPlan.selectedWayKey()),
                    geometry(fixture.way()));
        }
    }

    private static byte[] managedChoicePng(int tileY, String palette) {
        return managedChoicePng(0, tileY, palette, 0.0);
    }

    private static byte[] managedChoicePng(int tileX, int tileY, String palette,
            double bendMeters) {
        try {
            BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
            int center = switch (palette) {
                case "blue" -> 0xff4ca9ff;
                case "bluered" -> 0xffff24b2;
                case "purple" -> 0xffbd9cff;
                case "gray" -> 0xffe84bea;
                default -> 0xfffdfdfd;
            };
            for (int row = 0; row < 512; row++) {
                for (int column = 0; column < 512; column++) {
                    double eastMeters = (tileX * 512.0 + column - 8_388_608.0)
                            * nativeTilePixelMeters(15);
                    double bend = bendMeters * Math.max(0.0,
                            Math.cos(Math.PI * eastMeters / 64.0));
                    double target = 8_388_607.5 - bend
                            / nativeTilePixelMeters(15);
                    double distance = tileY * 512.0 + row - target;
                    double strength = Math.exp(-0.5 * distance * distance / 1.44);
                    int background = 3 + (column * 7 + row * 3) % 29;
                    int red = (int) Math.round(background + strength * (((center >>> 16) & 255) - background));
                    int green = (int) Math.round(background + strength * (((center >>> 8) & 255) - background));
                    int blue = (int) Math.round(background + strength * ((center & 255) - background));
                    image.setRGB(column, row, 0xff000000 | red << 16 | green << 8 | blue);
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] managedDiagonalPng(int zoom, int tileX, int tileY) {
        try {
            BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
            double equator = Math.scalb(512.0, zoom - 1);
            for (int row = 0; row < 512; row++) {
                for (int column = 0; column < 512; column++) {
                    double worldX = tileX * 512.0 + column;
                    double worldY = tileY * 512.0 + row;
                    double distance = (worldX + worldY - 2.0 * equator - 1.0)
                            / Math.sqrt(2.0);
                    double strength = Math.exp(-0.5 * distance * distance / 1.44);
                    int background = 3 + (column * 7 + row * 3) % 29;
                    int channel = (int) Math.round(background + strength * (255 - background));
                    image.setRGB(column, row, 0xff000000 | channel << 16
                            | channel << 8 | channel);
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void managedUndersampledMoveRequiresOneShotPreciseWithCurrentSourceProof() throws Exception {
        Fixture fixture = twoNodeLongFixture();
        ManagedHeatmapConfig base = config(TrackerMode.PROBABILISTIC).heatmap();
        ManagedHeatmapConfig requested = new ManagedHeatmapConfig(base.keyPairId(), base.policy(),
                base.signature(), base.sessionToken(), base.activity(), base.color(),
                base.manualLayerName(), base.layerRegex(), AlignmentMode.MOVE_EXISTING_NODES,
                base.trackerMode(), base.verbose(), base.debug(), false, false,
                base.showAggregateIntensityLayer(), base.candidateRatingEnabled(),
                base.parallelWayAwareness(), base.allowUndownloadedAlignment(),
                base.adjustJunctionNodes(), base.simplifyEnabled(), base.crossSectionHalfWidthPx(),
                base.crossSectionStepPx(), base.simplifyTolerancePx(), base.inferenceMode(),
                17, 17, 7.01, base.sampleStepMeters(), base.intensitySamplingMode(),
                base.cacheBuster());
        AlignmentConfig saved = new AlignmentConfig(requested, GeometryCleanupConfig.disabled());
        String identity = "managed-selected-hot-g0";
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) ->
                new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                        managedOffsetPng(request.address().zoom(), request.address().y(), 3.0),
                        null, Duration.ZERO, ""),
                new ManagedTileCache(cacheDirectory.resolve("managed-precise-recovery"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(0L));
            TracingSettings tracing = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                    TrackerMode.PROBABILISTIC, RecoverySettings.defaults(7.01), false,
                    AlignmentSourceMode.MANAGED_TILES);
            var moveRouting = AlignWayAction.resolveOrdinaryAction(tracing, saved,
                    () -> "visible", () -> "legacy");
            LiveBPreviewService service = new LiveBPreviewService();
            var move = ordinaryManagedSourcesResult(fixture, moveRouting, coordinator, identity);
            assertFalse(move.pipeline().routes().isEmpty(), move.pipeline().inference().explanation());
            var moveAssessment = new ModernSingleWayEditPlanAdapter().assess(move, 0);
            assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.PRECISE_SHAPE_REQUIRED,
                    moveAssessment.availability(), moveAssessment.detail());
            assertTrue(moveAssessment.plan().isEmpty());
            var rerun = AlignWayAction.resolvePreciseRerun(moveAssessment.availability(),
                    tracing, saved, () -> "visible", () -> "legacy");
            assertEquals(AlignmentMode.MOVE_EXISTING_NODES, saved.heatmap().alignmentMode());
            assertEquals(AlignmentMode.PRECISE_SHAPE,
                    rerun.route().invocation().config().heatmap().alignmentMode());
            var precise = ordinaryManagedSourcesResult(fixture, rerun, coordinator, identity);
            assertSame(coordinator, precise.captured().sourceOwner());
            assertEquals(1, precise.productionRuns().size());
            assertFalse(precise.pipeline().routes().isEmpty(), precise.pipeline().inference().explanation());
            var preciseAssessment = new ModernSingleWayEditPlanAdapter().assess(precise, 0);
            assertTrue(preciseAssessment.applyAvailable(), preciseAssessment.detail());
            var plan = preciseAssessment.plan().orElseThrow();
            List<GeographicPoint> original = geometry(fixture.way());
            assertNotEquals(original, plan.finalPreviewWays().get(plan.selectedWayKey()));
            String candidateId = "managed-precise-recovery";
            PreviewReviewState review = PreviewReviewState.fromEditPlan(candidateId, plan);
            if (review.disposition() == ValidationReport.Disposition.REVIEW_REQUIRED) review = review.confirm();
            PreviewReviewState exactReview = review;
            ManagedSourceReceipt receipt = new ManagedSourceReceipt(coordinator, precise.captured(),
                    rerun.route().invocation().config().heatmap(), "selected-visible",
                    () -> coordinator, () -> requested, precise.captured()::projectionCode);
            receipt.requireCurrent();
            var prepared = edt(() -> AlignWayAction.prepareModernApply(fixture.dataSet(), precise,
                    0, candidateId, exactReview, () -> {
                        receipt.requireCurrent();
                        service.requireCurrent(fixture.dataSet(), precise.captured());
                    }, coordinator::activeGenerationValue,
                    (network, currentPlan) -> new ManagedSourceLockedApplyValidator(network, service,
                            precise.captured(), receipt::requireCurrent,
                            failure -> { throw new AssertionError(failure); })));
            edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
            assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
            assertNotEquals(original, geometry(fixture.way()));
            edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
            assertEquals(original, geometry(fixture.way()));
            edt(() -> { UndoRedoHandler.getInstance().redo(); return null; });
            assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
        }
    }

    private static LiveBPreviewService.Computed ordinaryManagedSourcesResult(Fixture fixture,
            AlignWayAction.OrdinaryActionRouting<String> routing, TileFetchCoordinator coordinator,
            String sourceIdentity) throws Exception {
        PreviewSessionController<LiveBPreviewService.Computed> session =
                new PreviewSessionController<>(SwingUtilities::invokeLater);
        PreviewSessionController.Owner owner = session.open(() -> { });
        AtomicReference<LiveBPreviewService.Computed> result = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        try {
            edt(() -> {
                new AlignWayAction.OrdinaryModernAttemptAssembly().startWithSources(session, owner,
                        routing, fixture.dataSet(), fixture.selection(), sourceIdentity,
                        (source, invocation, permissions) -> {
                            throw new AssertionError("managed ordinary action cannot capture visible imagery");
                        },
                        (seed, invocation, cancellation) -> new ManagedModernPreviewSource(coordinator)
                                .acquireSources(seed.sourceGeographic(), invocation.config().heatmap(),
                                        seed.sourceIdentity(), CredentialSnapshot.fromConfig(null), cancellation),
                        coordinator, attempt -> { result.set(attempt.result()); ready.countDown(); });
                return null;
            });
            assertTrue(ready.await(60, TimeUnit.SECONDS), session.currentAttempt().toString());
            assertNotNull(result.get());
            return result.get();
        } finally {
            session.close();
        }
    }

    private static byte[] managedOffsetPng(int zoom, int tileY, double northOffsetMeters) {
        try {
            BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
            double equator = Math.scalb(512.0, zoom - 1);
            double nativePitch = nativeTilePixelMeters(zoom);
            double target = equator - northOffsetMeters / nativePitch;
            for (int row = 0; row < 512; row++) {
                double distance = tileY * 512.0 + row - target;
                double strength = Math.exp(-0.5 * distance * distance / 4.0);
                for (int column = 0; column < 512; column++) {
                    int background = 3 + (column * 7 + row * 3) % 29;
                    int gray = (int) Math.round(background + strength * (250 - background));
                    image.setRGB(column, row, 0xff000000 | gray << 16 | gray << 8 | gray);
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static double nativeTilePixelMeters(int zoom) {
        return 2.0 * Math.PI * 6_378_137.0 / Math.scalb(512.0, zoom);
    }

    @Test
    void ordinaryDenseMoveAppliesNativeAndSelectedRasterAlternativeWithExactHistory() throws Exception {
        Fixture fixture = denseFixture();
        List<String> original = state(fixture.dataSet());
        List<Long> identities = fixture.way().getNodes().stream().map(Node::getUniqueId).toList();
        ManagedHeatmapConfig base = config(TrackerMode.PROBABILISTIC).heatmap();
        ManagedHeatmapConfig requested = new ManagedHeatmapConfig(base.keyPairId(), base.policy(),
                base.signature(), base.sessionToken(), base.activity(), base.color(),
                base.manualLayerName(), base.layerRegex(), AlignmentMode.MOVE_EXISTING_NODES,
                base.trackerMode(), base.verbose(), base.debug(), true, false,
                base.showAggregateIntensityLayer(), base.candidateRatingEnabled(),
                base.parallelWayAwareness(), base.allowUndownloadedAlignment(),
                base.adjustJunctionNodes(), base.simplifyEnabled(), base.crossSectionHalfWidthPx(),
                base.crossSectionStepPx(), base.simplifyTolerancePx(), base.inferenceMode(),
                base.inferenceZoom(), base.validationZoom(), base.searchHalfWidthMeters(),
                base.sampleStepMeters(), base.intensitySamplingMode(), base.cacheBuster());
        AlignmentConfig config = new AlignmentConfig(requested, GeometryCleanupConfig.disabled());
        String identity = "managed-selected-hot-g0";
        TileDecoderClassifier decoder = new TileDecoderClassifier();
        try (TileFetchCoordinator coordinator = new TileFetchCoordinator((request, credentials) ->
                new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png",
                        managedChoicePng(request.address().y(), request.address().color()),
                        null, Duration.ZERO, ""),
                new ManagedTileCache(cacheDirectory.resolve("dense-move"), decoder), decoder,
                TileReliabilityPolicy.defaults())) {
            coordinator.updateActiveGeneration(new ManagedTileGeneration(0L));
            var routing = AlignWayAction.resolveOrdinaryAction(new TracingSettings(
                    TracingSettings.CURRENT_SCHEMA_VERSION, TrackerMode.PROBABILISTIC,
                    RecoverySettings.defaults(7.01), false, AlignmentSourceMode.MANAGED_TILES),
                    config, () -> "visible", () -> "legacy");
            PreviewSessionController<LiveBPreviewService.Computed> session =
                    new PreviewSessionController<>(SwingUtilities::invokeLater);
            PreviewSessionController.Owner owner = session.open(() -> { });
            AtomicReference<LiveBPreviewService.Computed> published = new AtomicReference<>();
            CountDownLatch ready = new CountDownLatch(1);
            try {
                edt(() -> {
                    new AlignWayAction.OrdinaryModernAttemptAssembly().startWithSources(session, owner,
                            routing, fixture.dataSet(), fixture.selection(), identity,
                            (source, invocation, permissions) -> {
                                throw new AssertionError("managed ordinary action cannot capture visible imagery");
                            },
                            (seed, invocation, cancellation) -> new ManagedModernPreviewSource(coordinator)
                                    .acquireSources(seed.sourceGeographic(), invocation.config().heatmap(),
                                            seed.sourceIdentity(), CredentialSnapshot.fromConfig(null),
                                            cancellation), coordinator,
                            attempt -> { published.set(attempt.result()); ready.countDown(); });
                    return null;
                });
                assertTrue(ready.await(60, TimeUnit.SECONDS), session.currentAttempt().toString());
                var computed = published.get();
                assertNotNull(computed);
                assertEquals(Set.of("hot"), computed.captured().sourceRasters().palettes().keySet());
                assertEquals(20, computed.detectorAttempts().size());
                assertEquals("selected-visible", computed.productionRuns().get(0).options().sourceTier());
                LiveBPreviewService.Computed nativeRun = computed.productionRuns().get(0);
                LiveBPreviewService.Computed alternative = computed.productionRuns().stream()
                        .filter(run -> run.options().sourceTier().startsWith("selected-mapping-"))
                        .filter(run -> !run.pipeline().routes().isEmpty())
                        .filter(run -> new ModernSingleWayEditPlanAdapter().assess(run, 0).applyAvailable())
                        .findFirst().orElseThrow(() -> new AssertionError(
                                "at least one requested selected-raster detector must have an applicable route"));
                assertNotEquals(nativeRun.request().evidenceContentHash(),
                        alternative.request().evidenceContentHash());
                for (LiveBPreviewService.Computed run : List.of(nativeRun, alternative)) {
                    assertFalse(run.pipeline().routes().isEmpty());
                    var assessment = new ModernSingleWayEditPlanAdapter().assess(run, 0);
                    assertTrue(assessment.applyAvailable(), assessment.detail());
                    var plan = assessment.plan().orElseThrow();
                    assertNotEquals(geometry(fixture.way()), plan.finalPreviewWays().get(plan.selectedWayKey()),
                            "Move must actually change the dense way");
                    String candidateId = run.options().sourceTier();
                    PreviewReviewState review = PreviewReviewState.fromEditPlan(candidateId, plan);
                    if (review.disposition() == ValidationReport.Disposition.REVIEW_REQUIRED) review = review.confirm();
                    ManagedSourceReceipt receipt = new ManagedSourceReceipt(coordinator, computed.captured(),
                            requested, run.options().sourceTier(), () -> coordinator, () -> requested,
                            computed.captured()::projectionCode);
                    PreviewReviewState exactReview = review;
                    var prepared = edt(() -> AlignWayAction.prepareModernApply(fixture.dataSet(), run,
                            0, candidateId, exactReview, () -> {
                                receipt.requireCurrent();
                                new LiveBPreviewService().requireCurrent(fixture.dataSet(), computed.captured());
                            }, coordinator::activeGenerationValue,
                            (network, currentPlan) -> new ManagedSourceLockedApplyValidator(network,
                                    new LiveBPreviewService(), computed.captured(), receipt::requireCurrent,
                                    failure -> { throw new AssertionError(failure); })));
                    edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
                    assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
                    assertEquals(identities, fixture.way().getNodes().stream().map(Node::getUniqueId).toList());
                    assertEquals(identities.size(), fixture.way().getNodesCount());
                    assertNotEquals(original, state(fixture.dataSet()));
                    edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
                    assertEquals(original, state(fixture.dataSet()));
                    edt(() -> { UndoRedoHandler.getInstance().redo(); return null; });
                    assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
                    edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
                    assertEquals(original, state(fixture.dataSet()));
                }
            } finally {
                session.close();
            }
        }
    }

    @Test
    void ordinaryManagedBRetainsAndMovesItsCapturedInteriorThroughActualApply() throws Exception {
        Fixture fixture = fixture(false, true);
        List<String> original = state(fixture.dataSet());
        try (Attempt attempt = publish(fixture)) {
            var computed = attempt.computed;
            assertFalse(computed.partitioned());
            PrimitiveKey middle = key(fixture.middle());
            assertEquals(Set.of(middle), computed.captured().network().closure().movableExistingNodeKeys());
            assertEquals(Set.of(middle, computed.request().selectedWayKey()),
                    computed.captured().network().closure().editableExistingKeys());
            var route = computed.pipeline().routes().get(0);
            ExistingWayNodeOccurrence occurrence = route.pointIds().stream()
                    .filter(id -> id instanceof ExistingWayNodeOccurrence existing && existing.nodeKey().equals(middle))
                    .map(id -> (ExistingWayNodeOccurrence) id).findFirst().orElseThrow();
            assertNotEquals(computed.evidence().coordinateFrame().toMetric(
                    new GeographicPoint(fixture.middle().lat(), fixture.middle().lon())),
                    route.assignments().get(occurrence), "actual final candidate must move the retained interior");
            var assessment = new ModernSingleWayEditPlanAdapter().assess(computed, 0);
            if (!assessment.applyAvailable()) {
                IllegalStateException refusal = assertThrows(IllegalStateException.class,
                        () -> edt(() -> attempt.prepare(null)));
                assertEquals("Selected occurrence movement/protection authority is incomplete", refusal.getMessage());
            }
            assertTrue(assessment.applyAvailable(), assessment.detail());
            var plan = assessment.plan().orElseThrow();
            PreviewReviewState review = review(plan);
            var prepared = edt(() -> attempt.prepare(review));
            assertEquals(plan, prepared.plan());
            assertEquals(original, state(fixture.dataSet()), "capture/preview/review/preparation are mutation-free");
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
            List<Node> retainedOriginals = List.copyOf(fixture.way().getNodes());
            edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
            assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
            assertTrue(fixture.way().getNodes().containsAll(retainedOriginals));
            assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
            List<String> applied = state(fixture.dataSet());
            edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
            assertEquals(original, state(fixture.dataSet()));
            edt(() -> { UndoRedoHandler.getInstance().redo(); return null; });
            assertEquals(applied, state(fixture.dataSet()));
            assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
        }
    }

    @Test
    void incompatibleSavedBSettingsStillConfirmAndApplyBothGeometryModes() throws Exception {
        for (AlignmentMode mode : List.of(AlignmentMode.PRECISE_SHAPE,
                AlignmentMode.MOVE_EXISTING_NODES)) {
            Fixture fixture = fixture(false, true);
            List<Node> originalNodes = List.copyOf(fixture.way().getNodes());
            List<GeographicPoint> originalGeometry = geometry(fixture.way());
            AlignmentConfig requested = incompatibleBConfig(mode);
            try (Attempt attempt = publish(fixture, TrackerMode.PROBABILISTIC, requested)) {
                assertTrue(requested.heatmap().simplifyEnabled());
                assertFalse(requested.cleanup().isDisabled());
                assertTrue(attempt.computed.captured().cleanup().isDisabled());
                assertEquals(mode, attempt.computed.request().geometryMode());
                var assessment = new ModernSingleWayEditPlanAdapter().assess(attempt.computed, 0);
                assertTrue(assessment.applyAvailable(), mode + ": " + assessment.detail());
                AlignmentEditPlan plan = assessment.plan().orElseThrow();
                PreviewReviewState pending = PreviewReviewState.fromEditPlan("ordinary-route", plan);
                assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED, pending.disposition(),
                        mode + " must exercise the review boundary");
                PreviewReviewState confirmation = pending.confirm();
                assertTrue(confirmation.confirmed());
                var prepared = edt(() -> attempt.prepare(confirmation));
                edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
                assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
                assertNotEquals(originalGeometry, geometry(fixture.way()),
                        "the managed route must apply a genuine move");
                if (mode == AlignmentMode.MOVE_EXISTING_NODES) {
                    assertEquals(originalNodes, fixture.way().getNodes());
                }
                edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
                assertEquals(originalGeometry, geometry(fixture.way()));
            }
            UndoRedoHandler.getInstance().clean();
        }
    }

    @Test
    void preciseShapeRecoveryWithIncompatibleSavedBSettingsRunsOrdinaryAttemptAndApplies() throws Exception {
        Fixture fixture = fixture(false, false);
        AlignmentConfig saved = incompatibleBConfig(AlignmentMode.MOVE_EXISTING_NODES);
        List<GeographicPoint> originalGeometry = geometry(fixture.way());
        LiveBPreviewService.VisibleRaster rendered = offsetVisibleRaster();
        TracingSettings tracing = new TracingSettings(TracingSettings.CURRENT_SCHEMA_VERSION,
                TrackerMode.PROBABILISTIC, RecoverySettings.defaults(7.01), false,
                AlignmentSourceMode.VISIBLE_LAYER);
        var initial = AlignWayAction.resolveOrdinaryAction(tracing, saved,
                () -> "visible", () -> "legacy");
        try (Attempt moveAttempt = publishVisible(fixture, initial, rendered)) {
            var moveAssessment = new ModernSingleWayEditPlanAdapter().assess(moveAttempt.computed, 0);
            assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.PRECISE_SHAPE_REQUIRED,
                    moveAssessment.availability(), moveAssessment.detail());
            assertTrue(moveAssessment.plan().isEmpty());
        }
        var rerun = AlignWayAction.resolvePreciseRerun(
                ModernSingleWayEditPlanAdapter.ApplyAvailability.PRECISE_SHAPE_REQUIRED,
                tracing, saved,
                () -> "visible", () -> "legacy");
        assertEquals(AlignmentMode.MOVE_EXISTING_NODES, saved.heatmap().alignmentMode());
        assertTrue(saved.heatmap().simplifyEnabled());
        assertFalse(saved.cleanup().isDisabled());
        assertEquals(AlignmentMode.PRECISE_SHAPE, rerun.requestedConfig().heatmap().alignmentMode());
        assertTrue(rerun.requestedConfig().heatmap().simplifyEnabled());
        assertFalse(rerun.requestedConfig().cleanup().isDisabled());
        assertEquals(AlignmentMode.PRECISE_SHAPE,
                rerun.route().invocation().config().heatmap().alignmentMode());
        assertFalse(rerun.route().invocation().config().heatmap().simplifyEnabled());
        assertTrue(rerun.route().invocation().config().cleanup().isDisabled());
        try (Attempt preciseAttempt = publishVisible(fixture, rerun, rendered)) {
            assertNotNull(preciseAttempt.computed.settingsResolutionJson());
            assertTrue(preciseAttempt.computed.settingsResolutionJson()
                    .contains("\"requestedCleanupMode\":\"CONSTRAINED_SMOOTH_AND_REDUCE\""));
            assertTrue(preciseAttempt.computed.settingsResolutionJson()
                    .contains("\"effectiveCleanupMode\":\"NONE\""));
            var assessment = new ModernSingleWayEditPlanAdapter().assess(preciseAttempt.computed, 0);
            assertTrue(assessment.applyAvailable(), assessment.detail());
            var plan = assessment.plan().orElseThrow();
            PreviewReviewState pending = PreviewReviewState.fromEditPlan("ordinary-route", plan);
            assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED, pending.disposition());
            var prepared = edt(() -> preciseAttempt.prepare(pending.confirm()));
            edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
            assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), geometry(fixture.way()));
            assertNotEquals(originalGeometry, geometry(fixture.way()));
            edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
            assertEquals(originalGeometry, geometry(fixture.way()));
        }
    }

    @Test
    void noChangeIsCurrentOnlyForTheExactCapturedNetworkAndCreatesNoHistory() throws Exception {
        Fixture fixture = fixture(false, false);
        try (Attempt attempt = publish(fixture, TrackerMode.PROBABILISTIC,
                incompatibleBConfig(AlignmentMode.MOVE_EXISTING_NODES))) {
            var assessment = new ModernSingleWayEditPlanAdapter().assess(attempt.computed, 0);
            assertEquals(ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE,
                    assessment.availability(), assessment.detail());
            assertTrue(assessment.plan().isEmpty());
            var current = edt(() -> NetworkSnapshotCapture.capture(fixture.dataSet(),
                    attempt.computed.captured().specification()));
            AlignWayAction.requireNoChangeCurrent(assessment, attempt.computed, current);
            var watch = assessment.noChangeWatch().orElseThrow();
            var wrongGeneration = new ModernSingleWayEditPlanAdapter.Assessment(
                    java.util.Optional.empty(),
                    ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE,
                    assessment.detail(), null, null,
                    java.util.Optional.of(new ModernSingleWayEditPlanAdapter.NoChangeWatch(
                            watch.selectedWayKey(), watch.selectedRange(), watch.snapshotId(),
                            watch.datasetIdentity(), watch.sourceGeneration() + 1,
                            watch.networkContentHash())));
            assertThrows(IllegalStateException.class,
                    () -> AlignWayAction.requireNoChangeCurrent(wrongGeneration,
                            attempt.computed, current));
            var regeneratedSource = new NetworkSnapshot(current.snapshotId(), current.role(),
                    current.datasetIdentity(), current.sourceGeneration() + 1,
                    current.closure(), current.primitives(), current.incomingReferrerWatches());
            assertThrows(IllegalStateException.class,
                    () -> AlignWayAction.requireNoChangeCurrent(assessment,
                            attempt.computed, regeneratedSource));
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
            edt(() -> { fixture.way().getNode(0).setCoor(new LatLon(0.00001, 0)); return null; });
            var changed = edt(() -> NetworkSnapshotCapture.capture(fixture.dataSet(),
                    attempt.computed.captured().specification()));
            assertThrows(IllegalStateException.class,
                    () -> AlignWayAction.requireNoChangeCurrent(assessment, attempt.computed, changed));
            assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        }
    }

    @Test
    void ordinarySubrangeAndTwoEndpointControlsKeepEveryOriginalCoordinateAndIdentity() throws Exception {
        for (boolean interior : List.of(true, false)) {
            Fixture fixture = fixture(true, interior);
            List<Node> originals = List.copyOf(fixture.way().getNodes());
            List<GeographicPoint> originalPoints = geometry(fixture.way());
            List<String> before = state(fixture.dataSet());
            try (Attempt attempt = publish(fixture)) {
                var assessment = new ModernSingleWayEditPlanAdapter().assess(attempt.computed, 0);
                assertTrue(assessment.applyAvailable(), assessment.detail());
                var prepared = edt(() -> attempt.prepare(review(assessment.plan().orElseThrow())));
                assertEquals(before, state(fixture.dataSet()));
                edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
                assertEquals(prepared.plan().finalPreviewWays().get(prepared.plan().selectedWayKey()), geometry(fixture.way()));
                assertEquals(originalPoints.get(0), geometry(fixture.way()).get(0));
                assertEquals(originalPoints.get(originalPoints.size() - 1),
                        geometry(fixture.way()).get(fixture.way().getNodesCount() - 1));
                assertTrue(fixture.way().getNodes().containsAll(originals));
                assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
                List<String> applied = state(fixture.dataSet());
                edt(() -> { UndoRedoHandler.getInstance().undo(); return null; });
                assertEquals(before, state(fixture.dataSet()));
                edt(() -> { UndoRedoHandler.getInstance().redo(); return null; });
                assertEquals(applied, state(fixture.dataSet()));
                UndoRedoHandler.getInstance().clean();
            }
        }
    }

    @Test
    void otherManagedModernEngineUsesTheSameOrdinaryInteriorApplyBoundary() throws Exception {
        Fixture fixture = fixture(false, true);
        try (Attempt attempt = publish(fixture, TrackerMode.CORRIDOR_AWARE)) {
            var assessment = new ModernSingleWayEditPlanAdapter().assess(attempt.computed, 0);
            assertTrue(assessment.applyAvailable(), assessment.detail());
            var prepared = edt(() -> attempt.prepare(review(assessment.plan().orElseThrow())));
            assertTrue(prepared.plan().before().closure().movableExistingNodeKeys().contains(key(fixture.middle())));
            edt(() -> { UndoRedoHandler.getInstance().add(prepared.command()); return null; });
            assertEquals(prepared.plan().finalPreviewWays().get(prepared.plan().selectedWayKey()), geometry(fixture.way()));
        }
    }

    @Test
    void actualPreparationRejectsUnreviewedStaleWindowManagedOwnerAndNetworkWithoutMutation() throws Exception {
        Fixture fixture = fixture(false, true);
        try (Attempt attempt = publish(fixture)) {
            var plan = new ModernSingleWayEditPlanAdapter().adapt(attempt.computed, 0);
            PreviewReviewState unconfirmed = PreviewReviewState.fromEditPlan("ordinary-route", plan);
            PreviewReviewState current = review(plan);
            refuse(attempt, attempt.computed, null, "reviewed candidate plan is stale");
            assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED, unconfirmed.disposition(),
                    "the actual managed B route must deterministically exercise required confirmation");
            refuse(attempt, attempt.computed, unconfirmed, "reviewed candidate plan is stale");
            refuse(attempt, attempt.computed, current.withCandidate("different-route"), "reviewed candidate plan is stale");
            attempt.activeWindow.set(new Object());
            refuse(attempt, attempt.computed, current, "preview window no longer owns");
            attempt.activeWindow.set(attempt.window);
            attempt.source.updateActiveGeneration(new ManagedTileGeneration(1));
            refuse(attempt, attempt.computed, current, "captured managed source changed");
            attempt.source.updateActiveGeneration(new ManagedTileGeneration(0));
            attempt.currentSource.set(null);
            refuse(attempt, attempt.computed, current, "captured managed source changed");
            attempt.currentSource.set(attempt.source);
            edt(() -> { fixture.middle().setCoor(new LatLon(0.000001, fixture.middle().lon())); return null; });
            refuse(attempt, attempt.computed, current, "network snapshot is stale");
        }
    }

    @Test
    void hostileDetachedAuthorityCannotAuthorizeTaggedSharedOutsideOrExtraEditableNodes() throws Exception {
        Fixture fixture = fixture(true, true);
        try (Attempt attempt = publish(fixture)) {
            var base = attempt.computed.captured().network();
            PrimitiveKey middle = key(fixture.middle());
            DetachedNode original = (DetachedNode) base.primitives().get(middle);
            Map<PrimitiveKey, DetachedPrimitive> tagged = new LinkedHashMap<>(base.primitives());
            tagged.put(middle, new DetachedNode(middle, original.coordinate(), Map.of("highway", "traffic_signals"), false, false));
            assertAuthorityRefused(attempt, network(base, base.closure(), tagged, base.incomingReferrerWatches()));
            Map<PrimitiveKey, Set<PrimitiveKey>> shared = new LinkedHashMap<>(base.incomingReferrerWatches());
            shared.put(middle, Set.of(attempt.computed.request().selectedWayKey(),
                    PrimitiveKey.existing(PrimitiveKey.Type.WAY, 99)));
            assertAuthorityRefused(attempt, network(base, base.closure(), base.primitives(), shared));
            PrimitiveKey outside = key(fixture.way().firstNode());
            Set<PrimitiveKey> movable = new LinkedHashSet<>(base.closure().movableExistingNodeKeys());
            movable.add(outside);
            Set<PrimitiveKey> editable = new LinkedHashSet<>(base.closure().editableExistingKeys()); editable.add(outside);
            Set<PrimitiveKey> protectedKeys = new LinkedHashSet<>(base.closure().protectedExistingNodeKeys()); protectedKeys.remove(outside);
            assertAuthorityRefused(attempt, network(base, closure(base.closure(), editable, movable, protectedKeys),
                    base.primitives(), base.incomingReferrerWatches()));
            // Extra editable outside node grants no movement but still exceeds selected-way edit scope.
            assertAuthorityRefused(attempt, network(base, closure(base.closure(), editable,
                    base.closure().movableExistingNodeKeys(), base.closure().protectedExistingNodeKeys()),
                    base.primitives(), base.incomingReferrerWatches()));
        }
    }

    @Test
    void finalAssignmentsRetainFixedCoordinatesAndEveryOriginalInteriorOccurrence() throws Exception {
        Fixture fixture = fixture(false, true);
        try (Attempt attempt = publish(fixture)) {
            var route = attempt.computed.pipeline().routes().get(0);
            var ids = route.pointIds();
            int middle = java.util.stream.IntStream.range(0, ids.size()).filter(index ->
                    ids.get(index) instanceof ExistingWayNodeOccurrence existing
                            && existing.nodeKey().equals(key(fixture.middle()))).findFirst().orElseThrow();
            MetricPoint boundary = route.assignments().get(ids.get(0));
            var movedBoundary = changedRoute(route, ids, 0, new MetricPoint(boundary.xMeters(), boundary.yMeters() + 1));
            refuse(attempt, withRoute(attempt.computed, movedBoundary), null, "exceeds its movement authority");
            List<FinalRoutePointId> omitted = new ArrayList<>(ids); omitted.remove(middle);
            refuse(attempt, withRoute(attempt.computed, changedRoute(route, omitted, -1, null)), null,
                    "omits a protected or movable existing occurrence");
            List<FinalRoutePointId> reordered = new ArrayList<>(ids);
            java.util.Collections.swap(reordered, 0, middle);
            refuse(attempt, withRoute(attempt.computed, changedRoute(route, reordered, -1, null)), null,
                    "retain both selected boundaries");
            Map<FinalRoutePointId, MetricPoint> missing = new LinkedHashMap<>(route.assignments()); missing.remove(ids.get(middle));
            List<String> before = state(fixture.dataSet());
            assertThrows(IllegalArgumentException.class, () -> new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(),
                    ids, missing, route.sourceOwnership(), route.quality(), route.cleanupStatus(), route.geometryChanged()));
            List<FinalRoutePointId> duplicate = new ArrayList<>(ids); duplicate.set(1, duplicate.get(0));
            assertThrows(IllegalArgumentException.class, () -> new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(),
                    duplicate, route.assignments(), route.sourceOwnership(), route.quality(), route.cleanupStatus(), route.geometryChanged()));
            assertEquals(before, state(fixture.dataSet())); assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        }
    }

    @Test
    void taggedAndExplicitlyFixedInteriorAssignmentsRemainExact() throws Exception {
        for (boolean tagged : List.of(true, false)) {
            Fixture base = fixture(false, true);
            if (tagged) base.middle().put("highway", "traffic_signals");
            Fixture fixture = tagged ? base : new Fixture(base.dataSet(), base.way(),
                    new SelectionContext(base.way(), 0, 2, base.way().getNodes(),
                            Set.copyOf(base.way().getNodes())), base.middle());
            try (Attempt attempt = publish(fixture)) {
                assertTrue(attempt.computed.captured().network().closure().protectedExistingNodeKeys().contains(key(fixture.middle())));
                assertTrue(attempt.computed.captured().network().closure().movableExistingNodeKeys().isEmpty());
                var route = attempt.computed.pipeline().routes().get(0);
                int middle = java.util.stream.IntStream.range(0, route.pointIds().size()).filter(index ->
                        route.pointIds().get(index) instanceof ExistingWayNodeOccurrence existing
                                && existing.nodeKey().equals(key(fixture.middle()))).findFirst().orElseThrow();
                MetricPoint position = route.assignments().get(route.pointIds().get(middle));
                refuse(attempt, withRoute(attempt.computed, changedRoute(route, route.pointIds(), middle,
                        new MetricPoint(position.xMeters(), position.yMeters() + 1))), null,
                        "exceeds its movement authority");
                List<FinalRoutePointId> omitted = new ArrayList<>(route.pointIds()); omitted.remove(middle);
                refuse(attempt, withRoute(attempt.computed, changedRoute(route, omitted, -1, null)), null,
                        "omits a protected or movable existing occurrence");
            }
        }
    }

    @Test
    void fullPreviewCrossingTouchOverlapAndPrefixContinuationStillBlockActualPreparation() throws Exception {
        Fixture fixture = fixture(false, true);
        try (Attempt attempt = publish(fixture)) {
            var route = attempt.computed.pipeline().routes().get(0);
            int middle = java.util.stream.IntStream.range(0, route.pointIds().size()).filter(index ->
                    route.pointIds().get(index) instanceof ExistingWayNodeOccurrence existing
                            && existing.nodeKey().equals(key(fixture.middle()))).findFirst().orElseThrow();
            MetricPoint center = route.hypothesis().points().get(middle);
            for (String defect : List.of("CROSSING", "VERTEX_TOUCH", "COLLINEAR_OVERLAP")) {
                MetricPoint next = route.hypothesis().points().get(middle + 1);
                // A proper crossing is interior to both segments; crossing a
                // final route vertex is correctly classified as a vertex touch.
                MetricPoint crossing = new MetricPoint((center.xMeters() + next.xMeters()) / 2,
                        (center.yMeters() + next.yMeters()) / 2);
                MetricPoint first = defect.equals("CROSSING")
                        ? new MetricPoint(crossing.xMeters(), crossing.yMeters() - 0.4) : center;
                MetricPoint last = defect.equals("COLLINEAR_OVERLAP")
                        ? next : defect.equals("CROSSING")
                                ? new MetricPoint(crossing.xMeters(), crossing.yMeters() + 0.4)
                                : new MetricPoint(center.xMeters(), center.yMeters() + 0.4);
                var frame = attempt.computed.evidence().coordinateFrame();
                Node a = loadedNode(30, frame.toGeographic(first)), b = loadedNode(31, frame.toGeographic(last));
                Way context = new Way(); context.setNodes(List.of(a, b)); context.setOsmId(32, 1); context.setModified(false);
                edt(() -> { fixture.dataSet().addPrimitive(a); fixture.dataSet().addPrimitive(b); fixture.dataSet().addPrimitive(context); return null; });
                // Hostile detached output retains the real raster geometry and quality while
                // the actual current network proves a newly conflicting read-only context.
                NetworkSnapshot current = edt(() -> NetworkSnapshotCapture.capture(fixture.dataSet(),
                        attempt.computed.captured().specification()));
                var changed = withNetwork(attempt.computed, current);
                var assessment = new ModernSingleWayEditPlanAdapter().assess(changed, 0);
                assertFalse(assessment.applyAvailable());
                assertTrue(assessment.plan().orElseThrow().validation().findingCodes().contains("final-topology:" + defect),
                        assessment.detail());
                refuse(attempt, changed, null, "final preview is blocked");
                Set<PrimitiveKey> editable = new LinkedHashSet<>(current.closure().editableExistingKeys());
                editable.add(PrimitiveKey.existing(PrimitiveKey.Type.WAY, 32));
                assertAuthorityRefused(attempt, network(current, closure(current.closure(), editable,
                        current.closure().movableExistingNodeKeys(), current.closure().protectedExistingNodeKeys()),
                        current.primitives(), current.incomingReferrerWatches()));
                edt(() -> { fixture.dataSet().removePrimitive(context); fixture.dataSet().removePrimitive(a);
                    fixture.dataSet().removePrimitive(b); return null; });
            }
        }
        Fixture subrange = fixture(true, true);
        try (Attempt attempt = publish(subrange)) {
            var route = attempt.computed.pipeline().routes().get(0);
            assertTrue(route.pointIds().get(1) instanceof GeneratedCandidatePoint);
            MetricPoint boundary = route.hypothesis().points().get(0);
            var reversed = changedRoute(route, route.pointIds(), 1,
                    new MetricPoint(boundary.xMeters() - 4, boundary.yMeters() + 2));
            var changed = withRoute(attempt.computed, reversed);
            var assessment = new ModernSingleWayEditPlanAdapter().assess(changed, 0);
            assertFalse(assessment.applyAvailable());
            assertTrue(assessment.plan().orElseThrow().validation().findingCodes().contains("final-topology:CONTINUATION"));
            refuse(attempt, changed, null, "final preview is blocked");
        }
    }

    private void refuse(Attempt attempt, LiveBPreviewService.Computed computed, PreviewReviewState review,
            String reason) throws Exception {
        List<String> before = state(attempt.fixture.dataSet());
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> edt(() -> attempt.prepare(computed, review)));
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
        assertEquals(before, state(attempt.fixture.dataSet()));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
    }

    private static void assertAuthorityRefused(Attempt attempt, NetworkSnapshot network) {
        List<String> before = state(attempt.fixture.dataSet());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ModernSingleWayEditPlanAdapter().adapt(withNetwork(attempt.computed, network), 0));
        assertEquals("Selected occurrence movement/protection authority is incomplete", failure.getMessage());
        assertEquals(before, state(attempt.fixture.dataSet()));
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
    }

    private static ClosureDescriptor closure(ClosureDescriptor base, Set<PrimitiveKey> editable,
            Set<PrimitiveKey> movable, Set<PrimitiveKey> protectedKeys) {
        return new ClosureDescriptor(base.scope(), base.queryVersion(), base.primitiveKeys(), editable,
                movable, protectedKeys, base.removableExistingNodeKeys(), base.editableWayOccurrences(),
                base.externalPorts(), base.collisionEnvelope(), base.editRegion(), base.mayCreateNodes(),
                base.wayReferrersComplete(), base.relationReferrersComplete(), base.nearbyGeometryComplete());
    }

    private static NetworkSnapshot network(NetworkSnapshot base, ClosureDescriptor closure,
            Map<PrimitiveKey, DetachedPrimitive> primitives, Map<PrimitiveKey, Set<PrimitiveKey>> watches) {
        return new NetworkSnapshot(base.snapshotId(), base.role(), base.datasetIdentity(), base.sourceGeneration(), closure, primitives, watches);
    }

    private static LiveBPreviewService.Computed withNetwork(LiveBPreviewService.Computed base, NetworkSnapshot network) {
        var old = base.captured();
        var captured = new LiveBPreviewService.Captured(old.raster(), old.managedRaster(), old.specification(), network,
                old.sourceGeographic(), old.sourceMetric(), old.outputGrid(), old.palette(), old.searchRadiusMeters(),
                old.sampleStepMeters(), old.settingsHash(), old.parameterHash(), old.cleanup(), old.geometryMode(),
                old.engine(), old.projectionCode(), old.junctionDecision(), old.intervalPartition());
        TraceRequest r = base.request();
        var request = new TraceRequest(r.selectedWayKey(), r.selectedRange(), r.engine(), r.geometryMode(), r.permissions(),
                r.budgets(), r.evidenceSnapshotId(), r.evidenceContentHash(), network.snapshotId(), network.canonicalHash(),
                r.settingsHash(), r.parameterHash(), r.samplerId(), r.configuredSampleStepMeters(), r.profileChainage(),
                r.evidenceResolution(), r.corridorInput());
        return new LiveBPreviewService.Computed(captured, base.evidence(), request, base.pipeline(), base.options(), base.counters());
    }

    private static LiveBPreviewService.Computed withRoute(LiveBPreviewService.Computed base, ModernTracePipeline.Route route) {
        return new LiveBPreviewService.Computed(base.captured(), base.evidence(), base.request(),
                new ModernTracePipeline.Result(base.pipeline().inference(), List.of(route)), base.options(), base.counters());
    }

    private static ModernTracePipeline.Route changedRoute(ModernTracePipeline.Route route,
            List<FinalRoutePointId> ids, int movedIndex, MetricPoint movedPoint) {
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>();
        var support = new ArrayList<org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership>();
        List<MetricPoint> points = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            MetricPoint point = index == movedIndex ? movedPoint : route.assignments().get(ids.get(index));
            points.add(point); assignments.put(ids.get(index), point); support.add(route.sourceOwnership().get(ids.get(index)));
        }
        var old = route.hypothesis();
        var hypothesis = new TraceHypothesis(old.id(), old.branchSignature(), points, support, old.objective(),
                old.posteriorProbability(), old.diagnostics());
        Map<FinalRoutePointId, org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership> ownership = new LinkedHashMap<>();
        for (FinalRoutePointId id : ids) ownership.put(id, route.sourceOwnership().get(id));
        return new ModernTracePipeline.Route(route.rawHypothesis(), hypothesis, ids, assignments, ownership,
                route.quality(), route.cleanupStatus(), true);
    }

    private static PreviewReviewState review(AlignmentEditPlan plan) {
        PreviewReviewState review = PreviewReviewState.fromEditPlan("ordinary-route", plan);
        return review.disposition() == ValidationReport.Disposition.REVIEW_REQUIRED ? review.confirm() : review;
    }

    private Attempt publish(Fixture fixture) throws Exception {
        return publish(fixture, TrackerMode.PROBABILISTIC);
    }

    private Attempt publish(Fixture fixture, TrackerMode engine) throws Exception {
        return publish(fixture, engine, config(engine));
    }

    private Attempt publish(Fixture fixture, TrackerMode engine, AlignmentConfig config) throws Exception {
        var routing = AlignWayAction.resolveOrdinaryAction(new TracingSettings(
                TracingSettings.CURRENT_SCHEMA_VERSION, engine,
                RecoverySettings.defaults(7.01), false, AlignmentSourceMode.MANAGED_TILES),
                config, () -> "visible", () -> "legacy");
        return publish(fixture, routing);
    }

    private Attempt publish(Fixture fixture, AlignWayAction.OrdinaryActionRouting<String> routing)
            throws Exception {
        PreviewSessionController<LiveBPreviewService.Computed> session =
                new PreviewSessionController<>(SwingUtilities::invokeLater);
        PreviewSessionController.Owner owner = session.open(() -> { });
        AtomicReference<LiveBPreviewService.Computed> result = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        edt(() -> {
            new AlignWayAction.OrdinaryModernAttemptAssembly().start(session, owner, routing,
                    fixture.dataSet(), fixture.selection(), "managed-selected-hot-g0",
                    (source, invocation, permissions) -> { throw new AssertionError("managed routing must not capture visible"); },
                    (seed, invocation, cancellation) -> raster(seed.sourceIdentity()), attempt -> {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        result.set(attempt.result()); published.countDown();
                    });
            return null;
        });
        assertTrue(published.await(30, TimeUnit.SECONDS), session.currentAttempt().toString());
        assertNotNull(result.get());
        TileDecoderClassifier classifier = new TileDecoderClassifier();
        TileFetchCoordinator source = new TileFetchCoordinator((request, credentials) -> {
            throw new AssertionError("receipt validation must not acquire tiles");
        }, new ManagedTileCache(cacheDirectory, classifier), classifier, TileReliabilityPolicy.defaults());
        source.updateActiveGeneration(new ManagedTileGeneration(0L));
        return new Attempt(fixture, result.get(), session, owner, source,
                routing.route().invocation().config(), null);
    }

    private Attempt publishVisible(Fixture fixture,
            AlignWayAction.OrdinaryActionRouting<String> routing,
            LiveBPreviewService.VisibleRaster raster) throws Exception {
        PreviewSessionController<LiveBPreviewService.Computed> session =
                new PreviewSessionController<>(SwingUtilities::invokeLater);
        PreviewSessionController.Owner owner = session.open(() -> { });
        AtomicReference<LiveBPreviewService.Computed> result = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        edt(() -> {
            new AlignWayAction.OrdinaryModernAttemptAssembly().start(session, owner, routing,
                    fixture.dataSet(), fixture.selection(), raster.sourceIdentity(),
                    (source, invocation, permissions) -> raster,
                    (seed, invocation, cancellation) -> {
                        throw new AssertionError("visible rerun must not acquire managed tiles");
                    }, attempt -> {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        result.set(attempt.result()); published.countDown();
                    });
            return null;
        });
        assertTrue(published.await(30, TimeUnit.SECONDS), session.currentAttempt().toString());
        TileDecoderClassifier classifier = new TileDecoderClassifier();
        TileFetchCoordinator source = new TileFetchCoordinator((request, credentials) -> {
            throw new AssertionError("visible rerun must not acquire managed tiles");
        }, new ManagedTileCache(cacheDirectory, classifier), classifier, TileReliabilityPolicy.defaults());
        return new Attempt(fixture, result.get(), session, owner, source,
                routing.route().invocation().config(), raster);
    }

    private static final class Attempt implements AutoCloseable {
        final Fixture fixture;
        final LiveBPreviewService.Computed computed;
        final PreviewSessionController<LiveBPreviewService.Computed> session;
        final PreviewSessionController.Owner owner;
        final TileFetchCoordinator source;
        final AtomicReference<TileFetchCoordinator> currentSource;
        final Object window = new Object();
        final AtomicReference<Object> activeWindow = new AtomicReference<>(window);
        final LiveBPreviewService service = new LiveBPreviewService();
        final ManagedSourceReceipt receipt;
        final LiveBPreviewService.VisibleRaster visibleRaster;

        Attempt(Fixture fixture, LiveBPreviewService.Computed computed,
                PreviewSessionController<LiveBPreviewService.Computed> session,
                PreviewSessionController.Owner owner, TileFetchCoordinator source,
                AlignmentConfig config, LiveBPreviewService.VisibleRaster visibleRaster) {
            this.fixture = fixture; this.computed = computed; this.session = session; this.owner = owner;
            this.source = source; currentSource = new AtomicReference<>(source);
            this.visibleRaster = visibleRaster;
            receipt = visibleRaster == null
                    ? new ManagedSourceReceipt(source, computed.captured(), config.heatmap(),
                            currentSource::get, () -> config.heatmap(),
                            () -> ProjectionRegistry.getProjection().toCode()) : null;
        }

        AlignWayAction.PreparedModernApply prepare(PreviewReviewState review) {
            return prepare(computed, review);
        }

        AlignWayAction.PreparedModernApply prepare(LiveBPreviewService.Computed selected, PreviewReviewState review) {
            return AlignWayAction.prepareModernApply(fixture.dataSet(), selected, 0, "ordinary-route", review,
                    () -> {
                        if (!session.isCurrentWindow(owner, window, activeWindow.get(), true)) {
                            throw new IllegalStateException("The preview window no longer owns this attempt");
                        }
                        if (visibleRaster == null) {
                            receipt.requireCurrent();
                            service.requireCurrent(fixture.dataSet(), selected.captured());
                        } else {
                            service.requireCurrent(fixture.dataSet(), selected.captured(), visibleRaster);
                        }
                    }, visibleRaster == null ? source::activeGenerationValue
                            : () -> selected.captured().network().sourceGeneration(),
                    (network, plan) -> visibleRaster == null
                            ? new ManagedSourceLockedApplyValidator(network, service, selected.captured(),
                                    receipt::requireCurrent, failure -> { throw new AssertionError(failure); })
                            : new VisibleSourceLockedApplyValidator(network, service, selected.captured(),
                                    () -> visibleRaster, null, () -> { },
                                    failure -> { throw new AssertionError(failure); }));
        }

        @Override public void close() { session.close(); source.close(); }
    }

    private static ManagedModernPreviewSource.Raster raster(String identity) {
        int size = 256;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            int gray = (int) Math.round(255 * (0.02 + 0.80 * Math.exp(-0.5
                    * (y - 127.0) * (y - 127.0) / 1.44)));
            for (int x = 0; x < size; x++) image.setRGB(x, y,
                    0xff000000 | gray << 16 | gray << 8 | gray);
        }
        boolean[] valid = new boolean[size * size]; Arrays.fill(valid, true);
        double equator = Math.scalb(256.0, 15) / 2.0;
        return new ManagedModernPreviewSource.Raster(image, valid,
                SupportedInputRasterTransform.webMercator(15, equator - size / 4.0,
                        equator - size / 4.0, 2),
                "hot", 15, identity, new ManagedTileGeneration(0L));
    }

    private static LiveBPreviewService.VisibleRaster offsetVisibleRaster() {
        int width = 600;
        int height = 600;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            double distanceMeters = (y - 288.0) / RenderedHeatmapSampler.RASTER_SCALE;
            double intensity = 0.02 + 0.80 * Math.exp(-0.5
                    * distanceMeters * distanceMeters / (1.2 * 1.2));
            int gray = (int) Math.round(255.0 * intensity);
            int pixel = 0xff000000 | gray << 16 | gray << 8 | gray;
            Arrays.fill(argb, y * width, (y + 1) * width, pixel);
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb,
                -50.0, -50.0, 50.0, 50.0, 1.0, 1.0,
                java.util.OptionalDouble.of(1.0), "visible-test", "EPSG:3857");
    }

    private static AlignmentConfig config(TrackerMode engine) {
        return new AlignmentConfig(new ManagedHeatmapConfig("key", "policy", "signature", "session", "all", "hot", "", ".*",
                AlignmentMode.PRECISE_SHAPE, engine, false, false,
                false, false, false, false, false, false, false, false,
                7, 4, 3, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15, 7.01, 1.56,
                IntensitySamplingMode.COLOR_MAPPING, 0L), GeometryCleanupConfig.disabled());
    }

    private static AlignmentConfig incompatibleBConfig(AlignmentMode mode) {
        ManagedHeatmapConfig base = config(TrackerMode.PROBABILISTIC).heatmap();
        return new AlignmentConfig(new ManagedHeatmapConfig(base.keyPairId(), base.policy(),
                base.signature(), base.sessionToken(), base.activity(), base.color(),
                base.manualLayerName(), base.layerRegex(), mode, base.trackerMode(),
                base.verbose(), base.debug(), base.multiColorDetection(),
                base.aggregateAllColorSchemes(), base.showAggregateIntensityLayer(),
                base.candidateRatingEnabled(), base.parallelWayAwareness(),
                base.allowUndownloadedAlignment(), base.adjustJunctionNodes(), true,
                base.crossSectionHalfWidthPx(), base.crossSectionStepPx(),
                base.simplifyTolerancePx(), base.inferenceMode(), base.inferenceZoom(),
                base.validationZoom(), base.searchHalfWidthMeters(), base.sampleStepMeters(),
                base.intensitySamplingMode(), base.cacheBuster()),
                GeometryCleanupPreset.BALANCED.apply());
    }

    private static Fixture fixture(boolean subrange, boolean interior) {
        DataSet dataSet = new DataSet();
        List<Node> nodes = new ArrayList<>();
        if (subrange) nodes.add(node(4, -32));
        nodes.add(node(1, -16));
        Node middle = interior ? node(2, 0) : null;
        if (interior) nodes.add(middle);
        nodes.add(node(3, 16));
        if (subrange) nodes.add(node(5, 32));
        nodes.forEach(dataSet::addPrimitive);
        Way way = new Way(); way.setNodes(nodes); way.setOsmId(10, 1); way.setModified(false); dataSet.addPrimitive(way);
        int first = subrange ? 1 : 0, last = nodes.size() - 1 - (subrange ? 1 : 0);
        return new Fixture(dataSet, way, new SelectionContext(way, first, last,
                nodes.subList(first, last + 1), Set.of(nodes.get(first), nodes.get(last))), middle);
    }

    private static Fixture denseFixture() {
        DataSet dataSet = new DataSet();
        List<Node> nodes = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            Node node = node(100 + index, (index - 4) * 8.0);
            nodes.add(node);
            dataSet.addPrimitive(node);
        }
        Way way = new Way();
        way.setNodes(nodes);
        way.setOsmId(110, 1);
        way.setModified(false);
        dataSet.addPrimitive(way);
        return new Fixture(dataSet, way, new SelectionContext(way, 0, nodes.size() - 1,
                nodes, Set.of(nodes.get(0), nodes.get(nodes.size() - 1))), nodes.get(4));
    }

    private static Fixture twoNodeLongFixture() {
        DataSet dataSet = new DataSet();
        Node first = node(201, -32.0);
        Node last = node(202, 32.0);
        dataSet.addPrimitive(first);
        dataSet.addPrimitive(last);
        Way way = new Way();
        way.setNodes(List.of(first, last));
        way.setOsmId(210, 1);
        way.setModified(false);
        dataSet.addPrimitive(way);
        return new Fixture(dataSet, way, new SelectionContext(way, 0, 1,
                List.of(first, last), Set.of(first, last)), null);
    }

    private static Fixture twoIslandFixture() {
        DataSet dataSet = new DataSet();
        List<Node> nodes = new ArrayList<>();
        for (int index = 0; index <= 30; index++) {
            Node current = node(5000 + index, (index - 15) * 10.0);
            nodes.add(current);
            dataSet.addPrimitive(current);
        }
        for (int index : List.of(4, 12, 18, 26)) {
            nodes.get(index).put("note", "manual junction boundary");
        }
        Way selected = new Way();
        selected.setNodes(nodes);
        selected.setOsmId(5100, 1);
        selected.setModified(false);
        dataSet.addPrimitive(selected);
        for (int index : List.of(8, 22)) {
            double east = (index - 15) * 10.0;
            Node south = loadedNode(5200 + index, new GeographicPoint(
                    Math.toDegrees(-40.0 / 6_378_137.0),
                    Math.toDegrees(east / 6_378_137.0)));
            Node north = loadedNode(5300 + index, new GeographicPoint(
                    Math.toDegrees(40.0 / 6_378_137.0),
                    Math.toDegrees(east / 6_378_137.0)));
            dataSet.addPrimitive(south);
            dataSet.addPrimitive(north);
            Way crossing = new Way();
            crossing.setNodes(List.of(south, nodes.get(index), north));
            crossing.setOsmId(5400 + index, 1);
            crossing.setModified(false);
            dataSet.addPrimitive(crossing);
        }
        return new Fixture(dataSet, selected, new SelectionContext(selected, 0, 30,
                nodes, Set.of(nodes.get(0), nodes.get(30))), nodes.get(15));
    }

    private static Fixture diagonalBudgetFixture() {
        DataSet dataSet = new DataSet();
        List<Node> nodes = new ArrayList<>();
        for (int index = -1; index <= 1; index++) {
            double metres = index * 900.0;
            Node current = loadedNode(5600 + index, new GeographicPoint(
                    Math.toDegrees(metres / 6_378_137.0),
                    Math.toDegrees(metres / 6_378_137.0)));
            nodes.add(current);
            dataSet.addPrimitive(current);
        }
        Way way = new Way();
        way.setNodes(nodes);
        way.setOsmId(5700, 1);
        way.setModified(false);
        dataSet.addPrimitive(way);
        return new Fixture(dataSet, way, new SelectionContext(way, 0, 2,
                nodes, Set.of(nodes.get(0), nodes.get(2))), nodes.get(1));
    }

    private static Node node(long id, double metres) {
        Node node = new Node(new LatLon(0, Math.toDegrees(metres / 6_378_137.0)));
        node.setOsmId(id, 1); node.setModified(false); return node;
    }

    private static Node loadedNode(long id, GeographicPoint point) {
        Node node = new Node(new LatLon(point.latitudeDegrees(), point.longitudeDegrees()));
        node.setOsmId(id, 1); node.setModified(false); return node;
    }

    private static PrimitiveKey key(Node node) { return PrimitiveKey.existing(PrimitiveKey.Type.NODE, node.getUniqueId()); }
    private static List<GeographicPoint> geometry(Way way) {
        return way.getNodes().stream().map(n -> new GeographicPoint(n.lat(), n.lon())).toList();
    }
    private static List<String> state(DataSet ds) {
        return ds.allPrimitives().stream().map(p -> p.getPrimitiveId() + "|" + p.getKeys() + "|" + p.isModified()
                + "|" + p.isDeleted() + "|" + (p instanceof Node n ? n.getCoor()
                : p instanceof Way w ? w.getNodes().stream().map(Node::getUniqueId).toList() : "relation"))
                .sorted().toList();
    }
    private static <T> T edt(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(action.get()); } catch (Throwable thrown) { failure.set(thrown); } });
        if (failure.get() instanceof RuntimeException runtime) throw runtime;
        if (failure.get() instanceof Error error) throw error;
        return result.get();
    }
    private record Fixture(DataSet dataSet, Way way, SelectionContext selection, Node middle) { }
}

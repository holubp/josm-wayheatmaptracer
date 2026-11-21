package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticExecutionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticInference;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticInferenceResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticObservationModel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticStateBuilder;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;
import org.openstreetmap.josm.tools.PlatformManager;

class PublicGuiSmokeContractTest {
    @Test void settingsControlLookupRejectsMissingOrAmbiguousControls() {
        var panel = new JPanel(); var nested = new JPanel(); panel.add(nested);
        var box = new javax.swing.JCheckBox("Verbose logging"); nested.add(box);
        assertEquals(box, PublicGuiSmokeMain.requiredCheckbox(panel, "Verbose logging"));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requiredCheckbox(panel, "missing"));
        panel.add(new javax.swing.JCheckBox("Verbose logging"));
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requiredCheckbox(panel, "Verbose logging"));
    }
    @Test void smokeEngineAdmissionNeverSubstitutesAnotherEngine() {
        assertEquals(TrackerMode.PROBABILISTIC, PublicGuiSmokeMain.guiEngine("PROBABILISTIC"));
        assertEquals(TrackerMode.CORRIDOR_AWARE, PublicGuiSmokeMain.guiEngine("CORRIDOR_AWARE"));
        assertEquals(TrackerMode.LEGACY_THEN_B, PublicGuiSmokeMain.guiEngine("LEGACY_THEN_B"));
        assertEquals(TrackerMode.LEGACY_V02, PublicGuiSmokeMain.guiEngine("LEGACY_V02"));
        for (String other : List.of("HYBRID", "DIRECTIONAL_IMAGE", "unknown"))
            assertThrows(IllegalArgumentException.class, () -> PublicGuiSmokeMain.guiEngine(other));
    }
    @Test void stepwiseControlLookupIsExactAndRejectsAmbiguity() {
        var panel=new JPanel();var apply=new javax.swing.JButton("Apply");apply.setName("legacy-apply");panel.add(apply);
        assertEquals(apply,PublicGuiSmokeMain.requiredNamed(panel,"legacy-apply",javax.swing.JButton.class));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requiredNamed(panel,"missing",javax.swing.JButton.class));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requiredNamed(panel,"legacy-apply",JComboBox.class));
        var duplicate=new javax.swing.JButton("Other");duplicate.setName("legacy-apply");panel.add(duplicate);
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requiredNamed(panel,"legacy-apply",javax.swing.JButton.class));
    }
    @Test void stepwisePublicFixtureHasEveryNativePaletteAtBothZooms() throws Exception {
        Path root=temporary.resolve("stepwise-cache");
        var fixture=PublicGuiSmokeFixture.create(temporary.resolve("stepwise-input"));
        var cache=new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache(root,new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier());
        assertEquals(90,fixture.seedRefinementTiles(cache));
        for(String palette:List.of("hot","blue","bluered","purple","gray"))for(int zoom:List.of(14,15)) {
            Path directory=root.resolve("cache-0/all/"+palette+"/"+zoom);
            try(var files=Files.walk(directory)){assertEquals(9,files.filter(Files::isRegularFile).count());}
            int x=(int)Math.floor((PublicGuiSmokeOracle.BASE_LONGITUDE+180)/360*(1<<zoom));
            double lat=Math.toRadians(PublicGuiSmokeOracle.BASE_LATITUDE);
            int y=(int)Math.floor((1-Math.log(Math.tan(lat)+1/Math.cos(lat))/Math.PI)/2*(1<<zoom));
            var address=new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileAddress("all",palette,zoom,x,y);
            assertTrue(new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier().decodeAndClassify(address,"image/png",Files.readAllBytes(cache.path(new ManagedTileGeneration(0),address))).usable(),palette+" z"+zoom);
        }
    }
    @Test void cancellationCannotPassDisabledOrRefusedNativeWork() {
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireNativeCancellation(false,false,false,true,false,true,0));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireNativeCancellation(true,true,true,true,false,false,1024));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireNativeCancellation(true,true,true,false,true,false,1024));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.requireNativeCancellation(true,true,true,true,true,true,1024));
        PublicGuiSmokeMain.requireNativeCancellation(true,true,true,true,true,false,1024);
    }
    @Test void requestedModeCannotBeRelabelledFromObservedMode() {
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.checkedMode(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.MOVE_EXISTING_NODES,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE));
    }
    @Test void moveCannotReplaceOriginalOccurrencesAtIdenticalCoordinates() {
        var a=new org.openstreetmap.josm.data.osm.Node(new LatLon(0,0));var b=new org.openstreetmap.josm.data.osm.Node(new LatLon(0,.001));
        var originals=List.of(a,b);var replacements=List.of(new org.openstreetmap.josm.data.osm.Node(a.getCoor()),new org.openstreetmap.josm.data.osm.Node(b.getCoor()));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.checkedAppliedGeometry(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.MOVE_EXISTING_NODES,originals,replacements));
    }
    @Test void noninitialParentOracleCannotSkipMissingOrUnchangedChoice() {
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.checkedNoninitialChoice("legacy-choice-0","legacy-choice-0",1));
        assertThrows(IllegalStateException.class,()->PublicGuiSmokeMain.checkedNoninitialChoice("legacy-choice-0","legacy-choice-0",2));
        assertEquals("legacy-choice-1",PublicGuiSmokeMain.checkedNoninitialChoice("legacy-choice-0","legacy-choice-1",2));
    }
    @Test void generatedStepwiseFixtureProvidesActualNoninitialLegacyChoice() throws Exception {
        var fixture=PublicGuiSmokeFixture.create(temporary.resolve("legacy-choice-input"));
        var decoder=new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier();
        var cache=new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache(temporary.resolve("legacy-choice-cache"),decoder);
        fixture.seedRefinementTiles(cache);
        try(var coordinator=new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator((request,credentials)->{throw new IllegalStateException("Public offline fixture cache miss");},cache,decoder,org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy.defaults())) {
            var actual=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult[1];
            SwingUtilities.invokeAndWait(()->{
                ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));fixture.dataSet().setSelected(fixture.selectedWay());
                var selected=SelectionResolver.resolve(fixture.dataSet(),false);
                actual[0]=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentService(new org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler(coordinator))
                    .align(selected,null,null,AlignmentConfig.withoutCleanup(PublicGuiSmokeMain.stepwiseConfig(org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE)));
            });
            assertTrue(actual[0].candidates().size()>1,"Real Legacy engine must supply noninitial choices for this GUI gate");
            assertTrue(actual[0].candidates().stream().map(org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate::id).distinct().count()>1);
        }
    }
    @TempDir Path temporary;
    private static final List<String> JOSM_STARTUP_EXPORTS = List.of(
            "--add-exports=java.base/sun.security.action=ALL-UNNAMED",
            "--add-exports=java.desktop/com.sun.imageio.plugins.jpeg=ALL-UNNAMED",
            "--add-exports=java.desktop/com.sun.imageio.spi=ALL-UNNAMED");

    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void generatedPublicFixtureHasKnownGoodElevenNodeWayAndUsableAnalyticTiles() throws Exception {
        var fixture = PublicGuiSmokeFixture.create(temporary);
        assertEquals(11, fixture.selectedWay().getNodesCount());
        assertEquals(0.01, fixture.selectedWay().getNode(5).lat(), 1.0e-8);
        assertEquals(2, fixture.tileCount());
        assertTrue(fixture.tilesUsable());
        assertTrue(fixture.measuredRidgeOffsetMeters() > 2.5);
        assertTrue(fixture.measuredRidgeOffsetMeters() < 5.0);
    }

    @Test
    void publicNativeHotRasterDrivesTheRealManagedProbabilisticEngineToItsRidge() throws Exception {
        var computed = publicComputed();
        assertFalse(computed.pipeline().routes().isEmpty());
        var choices = new LiveBPreviewService().adaptChoices(computed, point -> ProjectionRegistry.getProjection()
                .latlon2eastNorth(new LatLon(point.latitudeDegrees(), point.longitudeDegrees())));
        assertEquals(computed.pipeline().routes().size(), choices.size());
        for (int i = 0; i < choices.size(); i++) {
            assertEquals(computed, choices.get(i).owner());
            assertEquals(i, choices.get(i).localRouteIndex());
        }
        assertEquals(choices.size(), choices.stream().map(choice -> choice.candidate().id()).distinct().count());
        var first = new ModernSingleWayEditPlanAdapter().assess(computed, 0).plan().orElseThrow();
        PublicGuiSmokeOracle.verifyFinalGeometry(first.finalPreviewWays().get(first.selectedWayKey()));
        int selected = PublicGuiSmokeMain.oraclePassingRouteIndex(choices);
        var selectedPlan = new ModernSingleWayEditPlanAdapter().assess(computed, selected).plan().orElseThrow();
        assertEquals(choices.get(selected).candidate().id(), selectedPlan.routeIdentity());
        PublicGuiSmokeOracle.verifyFinalGeometry(selectedPlan.finalPreviewWays().get(selectedPlan.selectedWayKey()));
    }

    @Test
    void publicNativeHotRasterDrivesActualCorridorAwareChoiceToAnalyticRidge() throws Exception {
        var computed = publicComputed(TrackerMode.CORRIDOR_AWARE);
        assertEquals(TrackerMode.CORRIDOR_AWARE, computed.request().engine());
        assertEquals(TrackerMode.CORRIDOR_AWARE, computed.pipeline().inference().engine());
        var choices = new LiveBPreviewService().adaptChoices(computed, point -> ProjectionRegistry.getProjection()
                .latlon2eastNorth(new LatLon(point.latitudeDegrees(), point.longitudeDegrees())));
        int selected = PublicGuiSmokeMain.oraclePassingRouteIndex(choices);
        var plan = new ModernSingleWayEditPlanAdapter().assess(computed, selected).plan().orElseThrow();
        assertEquals(choices.get(selected).candidate().id(), plan.routeIdentity());
        PublicGuiSmokeOracle.verifyFinalGeometry(plan.finalPreviewWays().get(plan.selectedWayKey()));
    }

    @Test
    void publicMeasuredRidgeRetainsExactEagerAndOptimizedPaths() throws Exception {
        var computed = publicComputed();
        var request = computed.request();
        var network = computed.captured().network();
        var way = (org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay)
                network.primitives().get(request.selectedWayKey());
        var source = way.nodeKeys().subList(request.selectedRange().firstIndex(), request.selectedRange().lastIndex() + 1)
                .stream().map(key -> computed.evidence().coordinateFrame().toMetric(
                        ((org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode)
                                network.primitives().get(key)).coordinate())).toList();
        var parameters = EvidenceModelParameters.defaults();
        var profiles = new ProbabilisticProfileFactory().create(source, request.configuredSampleStepMeters(),
                request.permissions().ordinaryRadiusMeters(), true, computed.evidence(),
                computed.evidence().fields().get(computed.options().fieldName()));
        var evaluated = profiles.stream().map(profile -> new ProbabilisticObservationModel().evaluate(profile,
                new ProbabilisticStateBuilder().build(profile, request.budgets().maximumStatesPerProfile())
                        .lattice().orElseThrow(), parameters)).toList();
        ProbabilisticInferenceResult reference = null;
        for (var policy : ProbabilisticExecutionPolicy.Mode.values()) {
            var ledger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
            var owner = ledger.rootOwner();
            try {
                var result = new ProbabilisticInference().solve(evaluated, parameters, request.budgets(),
                        computed.evidence().decisionRegion(), CancellationProbe.NONE, owner, policy);
                if (reference == null) reference = result;
                else {
                    assertEquals(reference.rawPaths(), result.rawPaths());
                    assertEquals(reference.distinctPaths(), result.distinctPaths());
                }
            } finally { owner.close(); }
        }
    }

    @Test void publicHybridOracleRequiresAnActualBRefinedRidge() throws Exception {
        var computed = publicComputed(TrackerMode.LEGACY_THEN_B);
        var choices = new LiveBPreviewService().adaptChoices(computed, point -> ProjectionRegistry.getProjection()
                .latlon2eastNorth(new LatLon(point.latitudeDegrees(), point.longitudeDegrees())));
        int selected = PublicGuiSmokeMain.oraclePassingRouteIndex(choices);
        assertEquals(1.0, computed.pipeline().routes().get(selected).hypothesis().diagnostics().get("hasBRefinement"));
        var legacyOnly = choices.stream().filter(choice -> computed.pipeline().routes().get(choice.localRouteIndex())
                .hypothesis().diagnostics().getOrDefault("hasBRefinement", 0.0) == 0.0).toList();
        assertFalse(legacyOnly.isEmpty());
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.oraclePassingRouteIndex(legacyOnly));
    }

    private LiveBPreviewService.Computed publicComputed() throws Exception {
        return publicComputed(TrackerMode.PROBABILISTIC);
    }

    private LiveBPreviewService.Computed publicComputed(TrackerMode engine) throws Exception {
        var fixture = PublicGuiSmokeFixture.create(temporary);
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        fixture.dataSet().setSelected(fixture.selectedWay());
        var selection = SelectionResolver.resolve(fixture.dataSet(), false);
        var configMethod = PublicGuiSmokeMain.class.getDeclaredMethod("config");
        configMethod.setAccessible(true);
        var config = new AlignmentConfig(((ManagedHeatmapConfig) configMethod.invoke(null)).withTrackerMode(engine),
                GeometryCleanupConfig.disabled());
        var service = new LiveBPreviewService();
        LiveBPreviewService.ManagedCaptureSeed[] seed = new LiveBPreviewService.ManagedCaptureSeed[1];
        LiveBPreviewService.LegacyBInvocation[] legacy = new LiveBPreviewService.LegacyBInvocation[1];
        SwingUtilities.invokeAndWait(() -> {
            seed[0] = service.captureManagedSeed(fixture.dataSet(), selection, config, "public-native-hot");
            if (engine == TrackerMode.LEGACY_THEN_B) legacy[0] = LiveBPreviewService.LegacyBInvocation.capture(selection, config);
        });
        if (engine == TrackerMode.LEGACY_THEN_B) {
            var decoder = new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier();
            var cache = new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache(
                    temporary.resolve("hybrid-tile-cache"), decoder);
            fixture.seedTiles(cache);
            try (var coordinator = new org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator(
                    (request, credentials) -> { throw new AssertionError("Public fixture must stay offline"); },
                    cache, decoder, org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileReliabilityPolicy.defaults())) {
                coordinator.updateActiveGeneration(new ManagedTileGeneration(0));
                var sources = new ManagedModernPreviewSource(coordinator).acquireSources(seed[0].sourceGeographic(),
                        config.effectiveHeatmap(), seed[0].sourceIdentity(),
                        org.openstreetmap.josm.plugins.wayheatmaptracer.tile.CredentialSnapshot.fromConfig(config.heatmap()),
                        CancellationProbe.NONE);
                return service.compute(service.attachManagedSources(seed[0], sources, coordinator),
                        CancellationProbe.NONE, legacy[0]);
            }
        }
        BufferedImage image = ImageIO.read(temporary.resolve("public-hot-z15.png").toFile());
        boolean[] valid = new boolean[image.getWidth() * image.getHeight()];
        Arrays.fill(valid, true);
        var raster = new ManagedModernPreviewSource.Raster(image, valid,
                SupportedInputRasterTransform.webMercator(15, 16_384 * 256.0,
                        16_383 * 256.0, 2.0), "hot", 15, "public-native-hot",
                new ManagedTileGeneration(0L));
        return service.compute(service.attachManagedRaster(seed[0], raster),
                CancellationProbe.NONE, legacy[0]);
    }

    @Test
    void analyticOracleRejectsUnchangedDisplacedWay() {
        List<GeographicPoint> unchanged = List.of(
                PublicGuiSmokeOracle.point(-40, 0),
                PublicGuiSmokeOracle.point(0, 0),
                PublicGuiSmokeOracle.point(40, 0));
        var failure = assertThrows(IllegalStateException.class,
                () -> PublicGuiSmokeOracle.verifyFinalGeometry(unchanged));
        assertTrue(failure.getMessage().contains("centralSamples=1"));
        assertTrue(failure.getMessage().contains("maxErrorMeters=4.00"));
        assertTrue(failure.getMessage().contains("meanObservedNorthMeters=0.00"));
    }

    @Test
    void analyticOracleRequiresFixedEndpointsAndCentralRidge() {
        List<GeographicPoint> aligned = List.of(
                PublicGuiSmokeOracle.point(-40, 0),
                PublicGuiSmokeOracle.point(-16, 2.8),
                PublicGuiSmokeOracle.point(0, 4),
                PublicGuiSmokeOracle.point(16, 2.8),
                PublicGuiSmokeOracle.point(40, 0));
        PublicGuiSmokeOracle.verifyFinalGeometry(aligned);
        List<GeographicPoint> movedEndpoint = List.of(
                PublicGuiSmokeOracle.point(-40, 0.2),
                PublicGuiSmokeOracle.point(0, 4),
                PublicGuiSmokeOracle.point(40, 0));
        assertThrows(IllegalStateException.class,
                () -> PublicGuiSmokeOracle.verifyFinalGeometry(movedEndpoint));
    }

    @Test
    void guiHostFailsClosedWithoutRealDisplay() {
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeMain.requireDisplay(true));
    }

    @Test
    void visibleChoiceDiscoveryRequiresOneDisplayedTypedControl() {
        JPanel panel = new JPanel();
        assertThrows(IllegalStateException.class,
                () -> PublicGuiSmokeMain.visibleChoiceCombo(panel, String.class));
        JComboBox<Integer> unrelated = new JComboBox<>(new Integer[] {0, 1});
        panel.add(unrelated);
        assertThrows(IllegalStateException.class,
                () -> PublicGuiSmokeMain.visibleChoiceCombo(panel, String.class));
        JPanel nested = new JPanel();
        JComboBox<String> route = new JComboBox<>(new String[] {"unchanged", "ridge"});
        nested.add(route);
        panel.add(nested);
        assertEquals(route, PublicGuiSmokeMain.visibleChoiceCombo(panel, String.class));
        panel.add(new JComboBox<>(new String[] {"ambiguous"}));
        assertThrows(IllegalStateException.class,
                () -> PublicGuiSmokeMain.visibleChoiceCombo(panel, String.class));
    }

    @Test
    void soleProductionChoiceNeedsNoChooserButCannotHideMultipleRoutes() {
        JPanel panel = new JPanel();
        assertEquals("ridge", PublicGuiSmokeMain.soleChoiceWithoutControl(
            panel, List.of("ridge"), String.class));
        assertThrows(IllegalStateException.class,
            () -> PublicGuiSmokeMain.soleChoiceWithoutControl(panel, List.of(), String.class));
        assertThrows(IllegalStateException.class,
            () -> PublicGuiSmokeMain.soleChoiceWithoutControl(
                panel, List.of("ridge", "other"), String.class));
        panel.add(new JComboBox<>(new String[] {"ridge"}));
        assertThrows(IllegalStateException.class,
            () -> PublicGuiSmokeMain.soleChoiceWithoutControl(panel, List.of("ridge"), String.class));
    }

    @Test
    void freshJvmInitializesJosmBeforeParsingPublicFixture() throws Exception {
        Process child = startProbe(StartupProbe.class, temporary.resolve("fresh-jvm").toString());
        assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Fresh JVM fixture probe timed out");
        String output = new String(child.getInputStream().readAllBytes());
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("PUBLIC_FIXTURE_READY"), output);
    }

    @Test
    void stalledJosmStartupProducesBoundedSafeFailureSummary() throws Exception {
        Path report = temporary.resolve("startup-timeout.json");
        Process child = startProbe(StartupTimeoutProbe.class, report.toString());
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Startup watchdog probe timed out");
        String output = new String(child.getInputStream().readAllBytes());
        assertEquals(1, child.exitValue(), output);
        assertTrue(output.contains("GUI_SMOKE_STARTUP_TIMEOUT"), output);
        assertTrue(output.contains("GUI_SMOKE_FRAME role=startup"), output);
        assertFalse(output.contains(temporary.toString()), output);
        String summary = Files.readString(report);
        assertTrue(summary.contains("\"status\":\"FAIL\""), summary);
        assertTrue(summary.contains("\"stage\":\"josm-startup\""), summary);
        assertTrue(summary.contains("\"errorType\":\"StartupTimeout\""), summary);
    }

    @Test
    void returnedJosmStartupStopsWatchdogBeforeItCanPublishFailure() throws Exception {
        Path report = temporary.resolve("no-timeout.json");
        CountDownLatch deadlineWaiting = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        PublicGuiSmokeMain.startJosmWithDeadline(() -> {
            try {
                assertTrue(deadlineWaiting.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }, report, () -> {
            deadlineWaiting.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException completed) {
                interrupted.set(true);
                throw completed;
            }
        });
        assertTrue(interrupted.get());
        assertFalse(Files.exists(report));
    }

    @Test
    void publicGuiTaskProvidesJosmStartupModuleExports() throws Exception {
        String build = Files.readString(Path.of("build.gradle.kts"));
        String task = build.substring(build.indexOf("tasks.register<JavaExec>(\"v022PublicGuiSmoke\")"));
        task = task.substring(0, task.indexOf("tasks.jar {"));
        for (String argument : JOSM_STARTUP_EXPORTS) {
            assertTrue(task.contains("\"" + argument + "\""), argument);
        }
    }

    @Test
    void josmSanityCheckAcceptsExactlyTheRequiredJava17Exports() throws Exception {
        Process missing = startProbe(StartupSanityProbe.class, "missing");
        assertTrue(missing.waitFor(10, TimeUnit.SECONDS));
        String missingOutput = new String(missing.getInputStream().readAllBytes());
        assertEquals(0, missing.exitValue(), missingOutput);
        for (String argument : JOSM_STARTUP_EXPORTS) {
            assertTrue(missingOutput.contains(argument), missingOutput);
        }
        Process configured = startProbe(StartupSanityProbe.class, "configured",
                JOSM_STARTUP_EXPORTS);
        assertTrue(configured.waitFor(10, TimeUnit.SECONDS));
        String configuredOutput = new String(configured.getInputStream().readAllBytes());
        assertEquals(0, configured.exitValue(), configuredOutput);
        assertTrue(configuredOutput.contains("JOSM_SANITY_CLEAR"), configuredOutput);
    }

    private static Process startProbe(Class<?> probe, String argument) throws Exception {
        return startProbe(probe, argument, List.of());
    }

    private static Process startProbe(Class<?> probe, String argument, List<String> vmArguments)
            throws Exception {
        List<String> classPath = new ArrayList<>();
        classPath.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = probe.getClassLoader(); loader != null;
                loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        classPath.add(Path.of(url.toURI()).toString());
                    }
                }
            }
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(vmArguments);
        command.addAll(List.of("-cp", String.join(File.pathSeparator, classPath),
                probe.getName(), argument));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    public static final class StartupProbe {
        private StartupProbe() { }

        public static void main(String[] args) throws Exception {
            var fixture = PublicGuiSmokeMain.startJosmThenCreateFixture(
                    () -> Config.setPreferencesInstance(new MemoryPreferences()),
                    Path.of(args[0]));
            if (fixture.selectedWay().getNodesCount() != 11 || !fixture.tilesUsable()) {
                throw new AssertionError("Public fixture was not parsed after JOSM initialization");
            }
            System.out.println("PUBLIC_FIXTURE_READY");
        }
    }

    public static final class StartupTimeoutProbe {
        private StartupTimeoutProbe() { }

        public static void main(String[] args) {
            PublicGuiSmokeMain.startJosmWithDeadline(() -> {
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }, Path.of(args[0]), () -> { });
        }
    }

    public static final class StartupSanityProbe {
        private StartupSanityProbe() { }

        public static void main(String[] args) {
            List<String> messages = new ArrayList<>();
            PlatformManager.getPlatform().startupSanityChecks((title, canContinue, issues) ->
                    messages.addAll(List.of(issues)));
            if ("configured".equals(args[0])) {
                if (!messages.isEmpty()) throw new AssertionError("JOSM still reports a sanity issue");
                System.out.println("JOSM_SANITY_CLEAR");
            } else {
                System.out.println(String.join("\n", messages));
            }
        }
    }
}

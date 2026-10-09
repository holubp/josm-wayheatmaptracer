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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;
import org.openstreetmap.josm.tools.PlatformManager;

class PublicGuiSmokeContractTest {
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
        var fixture = PublicGuiSmokeFixture.create(temporary);
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        fixture.dataSet().setSelected(fixture.selectedWay());
        var selection = SelectionResolver.resolve(fixture.dataSet(), false);
        var configMethod = PublicGuiSmokeMain.class.getDeclaredMethod("config");
        configMethod.setAccessible(true);
        var config = new AlignmentConfig((ManagedHeatmapConfig) configMethod.invoke(null),
                GeometryCleanupConfig.disabled());
        var service = new LiveBPreviewService();
        LiveBPreviewService.ManagedCaptureSeed[] seed = new LiveBPreviewService.ManagedCaptureSeed[1];
        SwingUtilities.invokeAndWait(() -> seed[0] = service.captureManagedSeed(fixture.dataSet(),
                selection, config, "public-native-hot"));
        BufferedImage image = ImageIO.read(temporary.resolve("public-hot-z15.png").toFile());
        boolean[] valid = new boolean[image.getWidth() * image.getHeight()];
        Arrays.fill(valid, true);
        var raster = new ManagedModernPreviewSource.Raster(image, valid,
                SupportedInputRasterTransform.webMercator(15, 16_384 * 256.0,
                        16_383 * 256.0, 2.0), "hot", 15, "public-native-hot",
                new ManagedTileGeneration(0L));
        var computed = service.compute(service.attachManagedRaster(seed[0], raster),
                CancellationProbe.NONE);
        assertTrue(computed.pipeline().routes().size() > 1);
        var choices = service.adaptChoices(computed, point -> ProjectionRegistry.getProjection()
                .latlon2eastNorth(new LatLon(point.latitudeDegrees(), point.longitudeDegrees())));
        assertEquals(computed, choices.get(0).owner());
        assertEquals(computed, choices.get(1).owner());
        assertEquals(0, choices.get(0).localRouteIndex());
        assertEquals(1, choices.get(1).localRouteIndex());
        assertFalse(choices.get(0).candidate().id().equals(choices.get(1).candidate().id()));
        var first = new ModernSingleWayEditPlanAdapter().assess(computed, 0).plan().orElseThrow();
        assertThrows(IllegalStateException.class, () -> PublicGuiSmokeOracle.verifyFinalGeometry(
                first.finalPreviewWays().get(first.selectedWayKey())));
        var assessment = new ModernSingleWayEditPlanAdapter().assess(computed, 1);
        var plan = assessment.plan().orElseThrow(() -> new AssertionError(
                "Managed Engine B fixture produced no edit plan: " + assessment.availability()));
        assertEquals(choices.get(1).candidate().id(), plan.routeIdentity());
        PublicGuiSmokeOracle.verifyFinalGeometry(
                plan.finalPreviewWays().get(plan.selectedWayKey()));
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

package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class PublicGuiSmokeContractTest {
    @TempDir Path temporary;

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
    void analyticOracleRejectsUnchangedDisplacedWay() {
        List<GeographicPoint> unchanged = List.of(
                PublicGuiSmokeOracle.point(-40, 0),
                PublicGuiSmokeOracle.point(0, 0),
                PublicGuiSmokeOracle.point(40, 0));
        assertThrows(IllegalStateException.class,
                () -> PublicGuiSmokeOracle.verifyFinalGeometry(unchanged));
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
    void freshJvmInitializesJosmBeforeParsingPublicFixture() throws Exception {
        List<String> classPath = new ArrayList<>();
        classPath.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = getClass().getClassLoader(); loader != null;
                loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        classPath.add(Path.of(url.toURI()).toString());
                    }
                }
            }
        }
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-cp", String.join(File.pathSeparator, classPath),
                StartupProbe.class.getName(), temporary.resolve("fresh-jvm").toString())
                .redirectErrorStream(true).start();
        assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Fresh JVM fixture probe timed out");
        String output = new String(child.getInputStream().readAllBytes());
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains("PUBLIC_FIXTURE_READY"), output);
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
}

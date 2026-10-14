package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class BenchmarkHostMainTest {
    @BeforeEach
    void resetPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void preflightHandoffExecutesOnTheRealEventDispatchThread() throws Exception {
        assertTrue(BenchmarkHostMain.onEventThread(SwingUtilities::isEventDispatchThread));
    }

    @Test
    void nonzeroFrozenGenerationIsSharedByFixtureConfigPreferenceAndTileIdentity() {
        BenchmarkHostMain.FixtureSource source = BenchmarkHostMain.fixtureSource(37L);

        assertEquals(37L, source.config().cacheBuster());
        source.saveCacheGenerationPreference();
        assertEquals(37L, Config.getPref().getLong("wayheatmaptracer.cacheBuster", -1L));
        assertEquals("managed-selected-hot-g37", source.sourceIdentity());
        assertEquals(new ManagedTileGeneration(37L), source.tileGeneration());
    }

    @Test
    void fixtureSourceRejectsANegativeFrozenGeneration() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkHostMain.fixtureSource(-1L));
    }

    @Test
    void fatalEntrypointFailureExitsEvenWithASwallowingJosmStyleHandler() throws Exception {
        String classpath = java.util.stream.Stream.of(BenchmarkHostMainTest.class, BenchmarkHostMain.class,
                org.openstreetmap.josm.data.osm.DataSet.class, org.junit.jupiter.api.Test.class)
                .map(type -> type.getProtectionDomain().getCodeSource().getLocation().getPath())
                .distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        Path log = Files.createTempFile("benchmark-fatal-entry", ".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, FatalFailureProcess.class.getName()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "fatal benchmark failures must not wait on JOSM's bug-report queue");
            assertEquals(1, process.exitValue());
            assertTrue(Files.readString(log).contains("benchmark-host-fatal"));
        } finally {
            process.destroyForcibly(); process.waitFor(); Files.deleteIfExists(log);
        }
    }

    public static final class FatalFailureProcess {
        public static void main(String[] args) throws Exception {
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { });
            new Thread(() -> {
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "simulated-josm-bug-report-owner").start();
            BenchmarkHostMain.main(new String[0]);
        }
    }
}

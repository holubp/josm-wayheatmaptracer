package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.swing.SwingUtilities;

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
}

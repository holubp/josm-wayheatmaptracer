package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

class BenchmarkHostMainTest {
    @Test
    void preflightHandoffExecutesOnTheRealEventDispatchThread() throws Exception {
        assertTrue(BenchmarkHostMain.onEventThread(SwingUtilities::isEventDispatchThread));
    }
}

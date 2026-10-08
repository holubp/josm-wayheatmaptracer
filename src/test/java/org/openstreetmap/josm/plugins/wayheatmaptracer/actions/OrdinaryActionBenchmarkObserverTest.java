package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class OrdinaryActionBenchmarkObserverTest {
    @Test
    void normalActionDoesNotEvaluateBenchmarkGeometry() {
        String key = "wayheatmaptracer.benchmark.receipt";
        String previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertDoesNotThrow(() -> OrdinaryActionBenchmarkObserver.previewReady("single", null,
                    () -> { throw new AssertionError("Normal action must not inspect benchmark geometry"); }));
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }
}

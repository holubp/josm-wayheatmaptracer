package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

/** Uses the native canonical replay fingerprint for captured-result tests. */
public final class LiveAReplayAssertions {
    private LiveAReplayAssertions() { }

    public static void assertFinalMatches(ModernTracePipeline.Result expected, Format15ReplayRunner.Result actual) {
        var recorded = new Format15ReplayRunner.Result(ReplayLevel.FINAL_GEOMETRY,
                actual.capturedEngine(), actual.engine(), expected.inference(), expected.routes(), actual.inputHash());
        assertEquals(FinalReplayFingerprint.sha256(recorded), FinalReplayFingerprint.sha256(actual));
    }
}

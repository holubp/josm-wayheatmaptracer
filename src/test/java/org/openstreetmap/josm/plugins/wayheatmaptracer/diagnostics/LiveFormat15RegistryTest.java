package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ProductionBundleFactory;

class LiveFormat15RegistryTest {
    @Test
    void newestModernAttemptExportsEvenWhenItFailedBeforeInference(@TempDir Path directory)
            throws Exception {
        DiagnosticsRegistry.setLastBundle(null);
        DiagnosticsRegistry.setLastModernBundle(Format15ProductionBundleFactory
            .createUnavailableLive("test", "failed", "visible-layer", "attempt-new"));
        Path output = directory.resolve("latest.zip");
        DiagnosticsRegistry.writeLatest(output.toFile());
        var archive = Format15ArchiveReader.read(output);
        assertFalse(archive.capability().supports(ReplayLevel.SCALAR_INFERENCE));
        assertTrue(new String(archive.artifact("attempt-status.json").orElseThrow().bytes(),
            StandardCharsets.UTF_8).contains("failed"));
    }
}

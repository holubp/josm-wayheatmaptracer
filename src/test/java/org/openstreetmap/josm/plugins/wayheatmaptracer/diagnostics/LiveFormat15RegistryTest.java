package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        String plan = new String(archive.artifact("plan-availability.json").orElseThrow().bytes(),
            StandardCharsets.UTF_8);
        assertTrue(plan.contains("\"status\":\"FAILED\""));
        assertTrue(plan.contains("\"reason\":\"ATTEMPT_FAILED\""));
    }

    @Test
    void newestPlanAvailabilityAttemptIsCoordinateFreeAndDoesNotRequireApply(@TempDir Path directory)
            throws Exception {
        String credentialSentinel = "https://tiles.invalid/15/5/8.png?X-Amz-Signature=secret-sentinel";
        String serverFailureSentinel = "host failure: Cookie: SESSION_COOKIE_SENTINEL body=private";
        assertThrows(IllegalArgumentException.class, () ->
            Format15ProductionBundleFactory.createUnavailableLiveWithPlanAvailability(
                "test", "failed", "visible-layer", "attempt-inconsistent",
                Format15ProductionBundleFactory.PlanAvailability.cancelled()));
        assertThrows(IllegalArgumentException.class, () ->
            Format15ProductionBundleFactory.createUnavailableLive("test", "failed",
                credentialSentinel, "attempt-credential"));
        assertThrows(IllegalArgumentException.class, () ->
            Format15ProductionBundleFactory.createUnavailableLive("test", "failed",
                "visible-layer", serverFailureSentinel));
        DiagnosticsRegistry.setLastBundle(null);
        DiagnosticsRegistry.setLastModernBundle(Format15ProductionBundleFactory
            .createUnavailableLive("test", "failed", "visible-layer", "attempt-old"));
        DiagnosticsRegistry.setLastModernBundle(Format15ProductionBundleFactory
            .createUnavailableLiveWithPlanAvailability("test", "cancelled", "managed-tiles",
                "attempt-new", Format15ProductionBundleFactory.PlanAvailability.cancelled()));
        Path output = directory.resolve("latest-plan.zip");
        DiagnosticsRegistry.writeLatest(output.toFile());
        var archive = Format15ArchiveReader.read(output);
        String status = new String(archive.artifact("plan-availability.json").orElseThrow().bytes(),
            StandardCharsets.UTF_8);
        assertTrue(status.contains("\"status\":\"CANCELLED\""));
        assertTrue(status.contains("\"reason\":\"ATTEMPT_CANCELLED\""));
        assertFalse(archive.artifactNames().contains("frozen-edit-plan.bin"));
        assertFalse(archive.artifactNames().contains("applied-geometry.json"));
        assertFalse(archive.capability().supports(ReplayLevel.FULL_EDIT_PLAN));
        String allArtifacts = archive.artifactNames().stream()
            .map(name -> new String(archive.artifact(name).orElseThrow().bytes(), StandardCharsets.UTF_8))
            .reduce("", (left, right) -> left + right);
        assertFalse(allArtifacts.contains("SESSION_COOKIE_SENTINEL"));
        assertFalse(allArtifacts.contains("X-Amz-Signature"));
        assertFalse(allArtifacts.contains("body=private"));
    }
}

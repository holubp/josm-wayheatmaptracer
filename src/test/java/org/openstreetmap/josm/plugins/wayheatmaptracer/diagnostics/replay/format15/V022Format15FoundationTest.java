package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;

/** Focused CP10 contract tests for additive Format-15 diagnostics and replay. */
class V022Format15FoundationTest {
    private static final String SOURCE_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String PARAMETER_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void t131Format15RoundTripPreservesArtifactsAndChecksums(@TempDir Path directory) throws Exception {
        Format15Bundle original = completeBundle();
        Path file = directory.resolve("format15.zip");

        Format15BundleWriter.write(original, file);
        Format15Archive read = Format15ArchiveReader.read(file);

        assertEquals(15, read.formatVersion());
        assertEquals(SOURCE_HASH, read.sourceIdentityHash());
        assertEquals(PARAMETER_HASH, read.parameterHash());
        assertEquals(original.artifactNames(), read.artifactNames());
        assertEquals(original.artifact("trace-request.json").sha256(),
            read.artifact("trace-request.json").orElseThrow().sha256());
        assertTrue(read.capability().supports(ReplayLevel.FULL_EDIT_PLAN));
    }

    @Test
    void t132OldFormatsRemainReadableWithTheirActualCapabilities(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("format14.zip");
        writeZip(file, Map.of(
            "manifest.json", "{\"formatVersion\":14}",
            "diagnostics.json", "{}",
            "profile-intensity.csv", "profile,value\n0,1\n",
            "rendered-layer-capture.png", "not-an-image"));

        Format15Archive read = Format15ArchiveReader.read(file);

        assertEquals(14, read.formatVersion());
        assertTrue(read.capability().supports(ReplayLevel.SCALAR_INFERENCE));
        assertTrue(read.capability().supports(ReplayLevel.RASTER_INFERENCE));
        assertFalse(read.capability().supports(ReplayLevel.FULL_EDIT_PLAN));
    }

    @Test
    void t133MissingReplayContextIsEnumeratedWithoutFabrication(@TempDir Path directory) throws Exception {
        Format15Bundle partial = new Format15Bundle("build", SOURCE_HASH, PARAMETER_HASH,
            Map.of("trace-request.json", Format15Artifact.text("trace-request.json", "{}")));
        Path file = directory.resolve("partial.zip");
        Format15BundleWriter.write(partial, file);

        Format15Archive read = Format15ArchiveReader.read(file);

        assertFalse(read.capability().supports(ReplayLevel.SCALAR_INFERENCE));
        assertTrue(read.capability().missingPrerequisites().stream()
            .anyMatch(value -> value.startsWith("SCALAR_INFERENCE:")));
        assertFalse(read.capability().supports(ReplayLevel.FULL_EDIT_PLAN));
    }

    @Test
    void t134RepeatedSerializationIsByteStable(@TempDir Path directory) throws Exception {
        Format15Bundle bundle = completeBundle();
        Path first = directory.resolve("first.zip");
        Path second = directory.resolve("second.zip");

        Format15BundleWriter.write(bundle, first);
        Format15BundleWriter.write(bundle, second);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void t135ReplayIsStrictlyOffline(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("format15.zip");
        Format15BundleWriter.write(completeBundle(), file);
        Format15Archive archive = Format15ArchiveReader.read(file);

        String result = Format15ReplayRunner.replay(archive, ReplayLevel.FULL_EDIT_PLAN,
            SOURCE_HASH, PARAMETER_HASH, context -> {
                assertFalse(context.allowsNetwork());
                assertThrows(IllegalStateException.class, () -> context.requireNetwork("missing.png"));
                return "replayed";
            });

        assertEquals("replayed", result);
    }

    @Test
    void t136OriginalAndAppliedArtifactsAreIndependentAndImmutable() {
        byte[] originalBytes = "before".getBytes(StandardCharsets.UTF_8);
        byte[] appliedBytes = "after".getBytes(StandardCharsets.UTF_8);
        Format15Artifact original = Format15Artifact.binary("original-network.osm", originalBytes);
        Format15Artifact applied = Format15Artifact.binary("applied-network.osm", appliedBytes);
        Format15Bundle bundle = new Format15Bundle("build", SOURCE_HASH, PARAMETER_HASH,
            Map.of(original.name(), original, applied.name(), applied));

        originalBytes[0] = 'X';
        byte[] returned = bundle.artifact(original.name()).bytes();
        returned[0] = 'Y';

        assertArrayEquals("before".getBytes(StandardCharsets.UTF_8), bundle.artifact(original.name()).bytes());
        assertArrayEquals("after".getBytes(StandardCharsets.UTF_8), bundle.artifact(applied.name()).bytes());
    }

    @Test
    void t137EditPlanExportsEveryChangedWayBeforeAndAfter() {
        Format15EditPlan editPlan = new Format15EditPlan(
            Map.of("way-1", "selected-before", "way-2", "incident-before"),
            Map.of("way-1", "selected-after", "way-2", "incident-after"));
        Format15Artifact artifact = editPlan.asArtifact();

        String text = new String(artifact.bytes(), StandardCharsets.UTF_8);
        assertEquals("edit-plan.json", artifact.name());
        assertTrue(text.contains("way-1"));
        assertTrue(text.contains("way-2"));
        assertTrue(text.contains("selected-before"));
        assertTrue(text.contains("incident-after"));
        assertEquals(Set.of("way-1", "way-2"), editPlan.changedWayKeys());
    }

    @Test
    void t138ReplayRejectsSourceOrParameterHashMismatch(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("format15.zip");
        Format15BundleWriter.write(completeBundle(), file);
        Format15Archive archive = Format15ArchiveReader.read(file);

        assertThrows(ReplayMismatchException.class, () -> Format15ReplayRunner.replay(archive,
            ReplayLevel.SCALAR_INFERENCE, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            PARAMETER_HASH, ignored -> null));
        assertThrows(ReplayMismatchException.class, () -> Format15ReplayRunner.replay(archive,
            ReplayLevel.SCALAR_INFERENCE, SOURCE_HASH,
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", ignored -> null));
    }

    @Test
    void format15RejectsUnsafeTextAndArchiveNames(@TempDir Path directory) throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> Format15Artifact.text("verbose-log.txt", "Cookie: secret"));
        assertThrows(IllegalArgumentException.class,
            () -> Format15Artifact.binary("verbose-log.txt", "Cookie: secret".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
            () -> Format15Artifact.binary("../outside", new byte[] {1}));
        assertThrows(IllegalArgumentException.class,
            () -> Format15Artifact.binary("manifest.json", new byte[] {1}));

        Path unsafe = directory.resolve("unsafe.zip");
        writeZip(unsafe, Map.of("../outside", "x"));
        assertThrows(Format15ArchiveException.class, () -> Format15ArchiveReader.read(unsafe));
    }

    private static Format15Bundle completeBundle() {
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>();
        artifacts.put("trace-request.json", Format15Artifact.text("trace-request.json", "{}"));
        artifacts.put("evidence-frame.json", Format15Artifact.text("evidence-frame.json", "{}"));
        artifacts.put("solver-summary.json", Format15Artifact.text("solver-summary.json", "{}"));
        artifacts.put("posterior-profiles.csv", Format15Artifact.text("posterior-profiles.csv", "profile,lateral\n"));
        artifacts.put("path-alternatives.csv", Format15Artifact.text("path-alternatives.csv", "route\n"));
        artifacts.put("local-defects.csv", Format15Artifact.text("local-defects.csv", "defect\n"));
        artifacts.put("refit-intervals.csv", Format15Artifact.text("refit-intervals.csv", "interval\n"));
        artifacts.put("validation.json", Format15Artifact.text("validation.json", "{}"));
        artifacts.put("junction-proposals.json", Format15Artifact.text("junction-proposals.json", "{}"));
        artifacts.put("edit-plan.json", Format15Artifact.text("edit-plan.json", "{}"));
        artifacts.put("attempt-lineage.json", Format15Artifact.text("attempt-lineage.json", "{}"));
        return new Format15Bundle("test-build", SOURCE_HASH, PARAMETER_HASH, artifacts);
    }

    private static void writeZip(Path file, Map<String, String> entries) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                writeEntry(output, entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void writeEntry(ZipOutputStream output, String name, byte[] bytes) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }
}

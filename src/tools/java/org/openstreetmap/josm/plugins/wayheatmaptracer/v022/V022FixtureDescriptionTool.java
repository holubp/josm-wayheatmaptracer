package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Emits the neutral v0.22 analytic-fixture manifest for offline fixture tooling. */
public final class V022FixtureDescriptionTool {
    private static final List<Scene> SCENES = List.of(
            new Scene("S01", "straight-displaced"), new Scene("S02", "real-sine"),
            new Scene("S03", "moderate-false-dogleg"), new Scene("S04", "severe-false-apex"),
            new Scene("S05", "genuine-apex-control"), new Scene("S06", "weak-crossing"),
            new Scene("S07", "symmetric-same-endpoint-branches"), new Scene("S08", "brighter-wrong-parallel"),
            new Scene("S09", "intermittent-union"), new Scene("S10", "persistent-parallel-roads"),
            new Scene("S11", "fragmented-evidence-long-tail"), new Scene("S12", "real-missing-tail"),
            new Scene("S13", "missed-u-turn"), new Scene("S14", "one-sided-clipped-core"),
            new Scene("S15", "broad-plateau"), new Scene("S16", "false-northwest-drift"),
            new Scene("S17", "narrow-t-reattachment"), new Scene("S18", "preserve-old-receiver-bend"),
            new Scene("S19", "split-receiving-road"), new Scene("S20", "interior-x-relocation"),
            new Scene("S21", "multi-arm-relocation"), new Scene("S22", "coupled-junctions"),
            new Scene("S23", "fixed-anchor-loop"), new Scene("S24", "grade-separated-crossing"),
            new Scene("S25", "location-bound-feature"), new Scene("S26", "partial-cleanup"),
            new Scene("S27", "late-refit-defect"), new Scene("S28", "empty-adversarial"));

    private V022FixtureDescriptionTool() {
        // Command-line only.
    }

    /** Writes the matching public fixture descriptor JSON to stdout or {@code --output PATH}. */
    public static void main(String[] args) throws IOException {
        Path output = parseOutput(args);
        if (output == null) {
            PrintWriter writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
            writeManifest(writer);
            return;
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(output, StandardCharsets.UTF_8))) {
            writeManifest(writer);
        }
    }

    private static Path parseOutput(String[] args) {
        if (args.length == 0) {
            return null;
        }
        if (args.length == 2 && "--output".equals(args[0]) && !args[1].isBlank()) {
            return Path.of(args[1]);
        }
        throw new IllegalArgumentException("Usage: V022FixtureDescriptionTool [--output PATH]");
    }

    private static void writeManifest(PrintWriter writer) {
        writer.println("{");
        writer.println("  \"schema\": \"wayheatmaptracer-v022-analytic-fixtures-1\",");
        writer.println("  \"rasterPitchMeters\": 1.5,");
        writer.println("  \"haloMeters\": 30.0,");
        writer.println("  \"backgroundIntensity\": 0.01,");
        writer.println("  \"developmentSeeds\": [11, 29, 47, 83],");
        writer.println("  \"withheldSeeds\": [101, 131, 173],");
        writer.println("  \"scenes\": [");
        for (int index = 0; index < SCENES.size(); index++) {
            Scene scene = SCENES.get(index);
            writer.printf("    {\"id\":\"%s\",\"mechanism\":\"%s\"}%s%n", scene.id(), scene.mechanism(),
                    index + 1 == SCENES.size() ? "" : ",");
        }
        writer.println("  ]");
        writer.println("}");
        writer.flush();
        if (writer.checkError()) {
            throw new IllegalStateException("Could not write fixture manifest");
        }
    }

    /** Public neutral scene descriptor entry. */
    private record Scene(String id, String mechanism) {
    }
}

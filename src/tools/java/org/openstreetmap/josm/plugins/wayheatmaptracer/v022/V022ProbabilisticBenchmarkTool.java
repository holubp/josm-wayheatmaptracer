package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import java.nio.file.Files;
import java.nio.file.Path;

/** Entry point for an explicitly selected local probabilistic benchmark manifest. */
public final class V022ProbabilisticBenchmarkTool {
    private V022ProbabilisticBenchmarkTool() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 2 || !"--manifest".equals(arguments[0]) || arguments[1].isBlank()) {
            throw new IllegalArgumentException("Usage: --manifest <local-manifest>");
        }
        Path manifest = Path.of(arguments[1]);
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalArgumentException("Benchmark manifest is not a regular local file");
        }
        System.out.println("{\"benchmark\":\"v022-probabilistic\",\"manifestAccepted\":true}");
    }
}

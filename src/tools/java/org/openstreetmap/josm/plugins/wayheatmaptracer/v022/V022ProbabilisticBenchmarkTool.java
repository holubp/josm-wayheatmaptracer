package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.InferenceProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.LateralStateCell;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticInference;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticInferenceResult;

/** Runs the production B inference engine for an explicitly selected synthetic local manifest. */
public final class V022ProbabilisticBenchmarkTool {
    private static final int MAX_PROFILES = 1_024;
    private static final int MAX_STATES = 96;

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
        int[] stateCounts = stateCounts(manifest);
        List<InferenceProfile> profiles = profiles(stateCounts);
        long started = System.nanoTime();
        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
            EvidenceModelParameters.withoutShapeTerms(), TraceBudgets.defaults());
        long elapsed = System.nanoTime() - started;
        System.out.println("{\"benchmark\":\"v022-probabilistic\",\"profiles\":"
            + stateCounts.length + ",\"stateDistribution\":\"" + distribution(stateCounts)
            + "\",\"pairVisits\":" + result.evaluatedPairVisits() + ",\"transitions\":"
            + result.evaluatedTransitions() + ",\"rawAlternatives\":" + result.rawPaths().size()
            + ",\"elapsedNanos\":" + elapsed + ",\"estimatedMessageBytes\":"
            + estimatedMessageBytes(stateCounts) + "}");
    }

    private static int[] stateCounts(Path manifest) {
        Properties properties = new Properties();
        try (Reader input = Files.newBufferedReader(manifest)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Benchmark manifest cannot be read", exception);
        }
        String declared = properties.getProperty("states", "").trim();
        if (declared.isEmpty()) {
            throw new IllegalArgumentException("Benchmark manifest requires states=2,3,...");
        }
        String[] tokens = declared.split(",", -1);
        if (tokens.length < 2 || tokens.length > MAX_PROFILES) {
            throw new IllegalArgumentException("Benchmark profile count is outside the supported bound");
        }
        int[] result = new int[tokens.length];
        for (int index = 0; index < tokens.length; index++) {
            try {
                result[index] = Integer.parseInt(tokens[index].trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Benchmark state count is invalid", exception);
            }
            if (result[index] < 1 || result[index] > MAX_STATES) {
                throw new IllegalArgumentException("Benchmark state count is outside the supported bound");
            }
        }
        return result;
    }

    private static List<InferenceProfile> profiles(int[] stateCounts) {
        List<InferenceProfile> result = new ArrayList<>(stateCounts.length);
        for (int profile = 0; profile < stateCounts.length; profile++) {
            List<LateralStateCell> cells = new ArrayList<>(stateCounts[profile]);
            double[] unary = new double[stateCounts[profile]];
            for (int state = 0; state < stateCounts[profile]; state++) {
                cells.add(new LateralStateCell(state - 0.5 * (stateCounts[profile] - 1),
                    1.0, false, true, "benchmark"));
            }
            double chainage = 2.0 * profile;
            result.add(new InferenceProfile(chainage, new MetricPoint(chainage, 0.0),
                new MetricPoint(0.0, 1.0), cells, unary, List.of(), 0.0,
                ObservationOwnership.DIRECT_TWO_SIDED, false));
        }
        return List.copyOf(result);
    }

    private static String distribution(int[] stateCounts) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < stateCounts.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(stateCounts[index]);
        }
        return result.toString();
    }

    private static long estimatedMessageBytes(int[] stateCounts) {
        long pairs = 0;
        for (int index = 1; index < stateCounts.length; index++) {
            pairs += (long) stateCounts[index - 1] * stateCounts[index];
        }
        return 16L * pairs;
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Declared synthetic graphs and complete raw-bit results; never reads an observation scene. */
final class Frozen043InferenceOracle {
    static final String RESOURCE = "/b-observation-identification/frozen043-inference.sha256";

    record Graph(String name, List<InferenceProfile> profiles, EvidenceModelParameters parameters) { }

    static List<Graph> graphs() {
        var graphs = new ArrayList<Graph>();
        graphs.add(graph("single", new int[] {3}, 0));
        graphs.add(graph("two", new int[] {2, 3}, 1));
        graphs.add(graph("unequal", new int[] {2, 4, 3, 2, 3}, 2));
        graphs.add(graph("anchors", new int[] {1, 4, 3, 4, 1}, 3));
        graphs.add(graph("missing", new int[] {3, 4, 3, 4, 3}, 4));
        graphs.add(graph("censored", new int[] {4, 3, 4, 3, 4}, 5));
        graphs.add(graph("signedzero-ties", new int[] {3, 4, 3, 4, 3}, 6));
        graphs.add(graph("extreme-finite", new int[] {2, 3, 2, 3}, 7));
        graphs.add(graph("overflow", new int[] {2, 2, 2, 2}, 8));
        return List.copyOf(graphs);
    }

    static Graph graph(String name, int[] widths, int kind) {
        var profiles = new ArrayList<InferenceProfile>();
        for (int p = 0; p < widths.length; p++) {
            var cells = new ArrayList<LateralStateCell>();
            double[] costs = new double[widths[p]], guides = new double[widths[p]];
            double[][] responsibilities = new double[widths[p]][2];
            for (int s = 0; s < widths[p]; s++) {
                cells.add(new LateralStateCell((s - (widths[p] - 1) * 0.5) * 0.6,
                        0.25 + (s + p) % 4 * 0.375, kind == 3 && widths[p] == 1,
                        true, s % 2 == 0 ? "left" : "right"));
                costs[s] = kind == 6 ? (s % 2 == 0 ? -0.0 : 0.0)
                        : kind == 7 ? (s == 0 ? 1e200 : -1e200)
                        : kind == 8 ? Double.MAX_VALUE : (s * 7 + p * 3) % 11 * 0.03125 - 0.125;
                guides[s] = kind >= 6 ? 0.0 : (s + p) % 3 * 0.015625;
                responsibilities[s][0] = s % 2 == 0 ? 0.75 : 0.25;
                responsibilities[s][1] = 1.0 - responsibilities[s][0];
            }
            profiles.add(new InferenceProfile(p * 2.25, new MetricPoint(p * 2.25, p * p * 0.03),
                    new MetricPoint(0, 1), cells, costs, List.of(0.0, 0.25), 0.8,
                    kind == 5 ? ObservationOwnership.CORE_CENSORED
                        : kind == 4 ? ObservationOwnership.NO_SIGNAL_VALID_RASTER : ObservationOwnership.DIRECT_TWO_SIDED,
                    kind == 4, responsibilities).withStructuralGuideCosts(guides));
        }
        var parameters = kind >= 6 ? EvidenceModelParameters.withoutShapeTerms()
                : EvidenceModelParameters.defaults();
        return new Graph(name, List.copyOf(profiles), parameters);
    }

    static List<String> snapshot(boolean full) throws Exception {
        var rows = new ArrayList<String>();
        for (var graph : graphs()) for (int cap : new int[] {1, 2, 32})
            for (var mode : List.of(ProbabilisticExecutionPolicy.Mode.EAGER_V1, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2))
                for (int region = 0; region < 3; region++) {
                    var ledger = AttemptMemoryLedger.production();
                    var owner = ledger.rootOwner();
                    try {
                        var result = new ProbabilisticInference().solve(graph.profiles(), graph.parameters(),
                                new TraceBudgets(96, 1_000_000, 1_000_000, cap, Math.min(8, cap)),
                                region == 0 ? null : region == 1 ? MetricRegion.rectangle(-2, -0.5, 20, 2)
                                    : MetricRegion.rectangle(100, 100, 110, 110),
                                CancellationProbe.NONE, owner, mode);
                        String bits = canonical(result);
                        String key = graph.name() + "/" + cap + "/" + mode + "/" + region;
                        rows.add(key + "\t" + (full ? bits : sha256(bits)));
                    } finally {
                        owner.close();
                        if (ledger.currentBytes() != 0) throw new AssertionError("retained oracle owner");
                    }
                }
        var wide = graph("bounded-failure", new int[] {16, 16, 16, 16}, 2);
        for (var mode : List.of(ProbabilisticExecutionPolicy.Mode.EAGER_V1, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2))
            for (long memory : new long[] {4096, 8192, 16384, 32768, 65536, 131072, 262144})
                for (long scores : new long[] {15, 1_000_000}) {
                    var ledger = new AttemptMemoryLedger(memory); var owner = ledger.rootOwner();
                    String bits;
                    try {
                        bits = canonical(new ProbabilisticInference().solve(wide.profiles(), wide.parameters(),
                                new TraceBudgets(96, 1_000_000, scores, 1, 1), null,
                                CancellationProbe.NONE, owner, mode));
                    } catch (org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceMemoryLimitException failure) {
                        bits = failure.getClass().getSimpleName() + ":" + failure.getMessage();
                    } finally {
                        owner.close();
                        if (ledger.currentBytes() != 0) throw new AssertionError("retained bounded owner");
                    }
                    rows.add("bounded-failure/" + mode + "/" + memory + "/" + scores + "\t"
                            + (full ? bits : sha256(bits)));
                }
        return rows;
    }

    /** Visits every retained result field, including every double's raw bits and nested arrays. */
    static String canonical(Object value) throws Exception {
        if (value == null) return "null";
        if (value instanceof Double number) return "d" + Long.toHexString(Double.doubleToRawLongBits(number));
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) return value.toString();
        if (value instanceof String string) return string.length() + ":" + string;
        if (value instanceof Optional<?> optional) return optional.isEmpty() ? "empty" : canonical(optional.orElseThrow());
        if (value instanceof List<?> list) {
            var out = new StringBuilder("[");
            for (Object item : list) out.append(canonical(item)).append(';');
            return out.append(']').toString();
        }
        if (value.getClass().isArray()) {
            var out = new StringBuilder("[");
            for (int i = 0; i < Array.getLength(value); i++) out.append(canonical(Array.get(value, i))).append(';');
            return out.append(']').toString();
        }
        if (!value.getClass().getPackageName().startsWith("org.openstreetmap.josm.plugins.wayheatmaptracer"))
            throw new IllegalArgumentException("Unexpected oracle field " + value.getClass());
        var fields = value.getClass().getDeclaredFields();
        Arrays.sort(fields, Comparator.comparing(java.lang.reflect.Field::getName));
        var out = new StringBuilder(value.getClass().getSimpleName()).append('{');
        for (var field : fields) if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
            field.setAccessible(true);
            out.append(field.getName()).append('=').append(canonical(field.get(value))).append(';');
        }
        return out.append('}').toString();
    }

    static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** Explicit pre-edit capture only; unit tests never generate or update the frozen oracle. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("New capture directory required");
        Path output = Path.of(args[0]);
        Files.createDirectory(output);
        Files.write(output.resolve("full-result-raw-bits.txt"), snapshot(true), StandardCharsets.UTF_8);
        Files.write(output.resolve("frozen043-inference.sha256"), snapshot(false), StandardCharsets.UTF_8);
    }
}

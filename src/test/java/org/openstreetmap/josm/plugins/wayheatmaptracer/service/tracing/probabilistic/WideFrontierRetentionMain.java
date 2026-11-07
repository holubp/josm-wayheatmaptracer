package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Separate public six-scene V5 gate; all native execution and assessment use the shared runner. */
public final class WideFrontierRetentionMain {
    static final BPolicy POLICY = BPolicy.INVENTORY_FRONTIER_V5;
    static final List<String> IDS = List.of("remote-drift", "thin-hairpin", "faint-apex",
            "distinct-parallel", "diffuse-three-strand", "remote-drift-reversed");
    static final String DEFAULT_OUTPUT = "build/reports/wide-frontier-v5-retention";
    record Invocation(Path output) { }
    record Binding(WideCompactScenes.Scene scene, String frozenV4Hash) { }

    static Invocation invocation(String[] args) {
        if (args.length < 1 || args.length > 2 || !"--frontier-retention".equals(args[0])
                || args.length == 2 && (args[1].isBlank() || args[1].startsWith("--"))) {
            throw new IllegalArgumentException("Expected --frontier-retention [output]");
        }
        return new Invocation(Path.of(args.length == 1 ? DEFAULT_OUTPUT : args[1]));
    }

    static String frozenManifest() throws IOException {
        try (var stream = WideFrontierRetentionMain.class.getResourceAsStream(WideCompactQualityMain.MANIFEST)) {
            if (stream == null) throw new IOException("Missing frozen public scene hashes");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Source-only validation: builds analytic inputs but never prepares or executes either engine. */
    static List<Binding> preflight(Properties frozen) {
        require(IDS.equals(WideCompactScenes.IDS), "The gate requires the exact six frozen public scenes");
        require(WideCompactScenes.SPEC.equals(frozen.getProperty("spec")), "Frozen scene specification differs");
        var keys = new java.util.HashSet<>(IDS); keys.add("spec");
        require(frozen.stringPropertyNames().equals(keys), "Frozen scene manifest keys differ");
        var bindings = new ArrayList<Binding>();
        for (String id : IDS) {
            var original = WideCompactScenes.scene(id, BPolicy.INVENTORY_PREP_V4);
            require(original.input().canonicalHash().equals(frozen.getProperty(id)), "Frozen scene hash differs: " + id);
            var frontier = WideCompactScenes.scene(id, POLICY);
            requirePolicyOnlyScene(original, frontier);
            bindings.add(new Binding(frontier, original.input().canonicalHash()));
        }
        return List.copyOf(bindings);
    }

    static void requirePolicyOnlyScene(WideCompactScenes.Scene original, WideCompactScenes.Scene frontier) {
        require(BPolicy.recorded(original.input().request()) == BPolicy.INVENTORY_PREP_V4,
                "Frozen source must retain its V4 identity");
        var exact = WideCompactQualityMain.historical(original.input());
        var request = BPolicy.adaptiveRequest(exact.request(), POLICY);
        var expected = new FrozenReplayInput(request, original.input().evidence(), original.input().network(),
                original.input().options(), original.input().planSearchRadiusMeters(), original.input().legacyB());
        require(expected.canonicalHash().equals(frontier.input().canonicalHash())
                && original.id().equals(frontier.id()) && original.analytic().equals(frontier.analytic())
                && original.strong() == frontier.strong() && original.hairpin() == frontier.hairpin()
                && Double.doubleToRawLongBits(original.length()) == Double.doubleToRawLongBits(frontier.length()),
                "V5 scene differs beyond the known typed source policy: " + original.id());
    }

    static WideCompactRetentionFlow.Report newReport() {
        var report = new WideCompactRetentionFlow.Report();
        for (String id : IDS) report.ensureScene(id);
        return report;
    }

    static void requireFreshOutput(Path output) throws IOException {
        Files.createDirectories(output);
        try (var children = Files.list(output)) {
            if (children.findAny().isPresent()) throw new IOException("Existing public frontier evidence must not be overwritten");
        }
    }

    /** Separate source contract beside the unchanged common assessor report; this declares no native success. */
    static void writeBindings(Path output, List<Binding> bindings, boolean preflightComplete, String sourceCommit) throws IOException {
        require(preflightComplete ? bindings.stream().map(binding -> binding.scene().id()).toList().equals(IDS)
                : bindings.isEmpty(), "Incomplete source bindings cannot claim complete preflight");
        String commit = sourceCommit.matches("[0-9a-f]{40}") ? sourceCommit : "unavailable-local";
        var json = new StringBuilder("{\"schema\":1,\"contract\":\"public-wide-frontier-v5-performance-retention\",")
                .append("\"policy\":\"").append(POLICY).append("\",\"policyVersion\":\"").append(POLICY.version())
                .append("\",\"candidateExecutionVersion\":\"").append(POLICY.compactExecutionVersion())
                .append("\",\"candidateMustBeNativeCompact\":true,\"denseCandidateAdmissionAllowed\":false,")
                .append("\"referenceRole\":\"actual-full-reference-with-explicit-existing-resource-limit-fallback\",")
                .append("\"assessmentArtifact\":\"summary.json\",\"frozenManifest\":\"public-wide-compact-v1.properties\",")
                .append("\"releaseQualified\":false,\"sourceCommit\":\"").append(commit)
                .append("\",\"preflightStatus\":\"").append(preflightComplete ? "PASS" : "FAILED")
                .append("\",\"requiredScenes\":6,\"bindings\":[");
        boolean first = true;
        for (var binding : bindings) {
            if (!first) json.append(','); first = false;
            var input = binding.scene().input();
            json.append("{\"scene\":\"").append(binding.scene().id()).append("\",\"frozenV4Hash\":\"")
                    .append(binding.frozenV4Hash()).append("\",\"v5InputHash\":\"").append(input.canonicalHash())
                    .append("\",\"evidenceHash\":\"").append(input.evidence().canonicalHash())
                    .append("\",\"networkHash\":\"").append(input.network().canonicalHash())
                    .append("\",\"parameterHash\":\"").append(input.request().parameterHash()).append("\"}");
        }
        Files.writeString(output.resolve("frontier-source-bindings.json"), json.append("]}\n"), StandardOpenOption.CREATE_NEW);
    }

    public static void main(String[] args) throws Exception {
        var output = invocation(args).output();
        requireFreshOutput(output);
        Config.setPreferencesInstance(new MemoryPreferences());
        var report = newReport();
        String manifest = "# UNAVAILABLE: frozen public source manifest could not be read\n";
        String commit = System.getenv().getOrDefault("GITHUB_SHA", "unavailable-local");
        List<Binding> bindings;
        try {
            manifest = frozenManifest();
            var properties = new Properties(); properties.load(new StringReader(manifest));
            bindings = preflight(properties);
        } catch (Throwable failure) {
            for (String id : IDS) report.fail(id, WideCompactRetentionFlow.Stage.REFERENCE_IDENTITY, failure);
            writeBindings(output, List.of(), false, commit);
            report.write(output, commit, manifest);
            throw new AssertionError("Frozen public V5 preflight failed; no native execution started", failure);
        }
        writeBindings(output, bindings, true, commit);
        Files.writeString(output.resolve("progress-before-native.json"), report.json(commit), StandardOpenOption.CREATE_NEW);
        try {
            for (var binding : bindings) {
                var scene = binding.scene(); String id = scene.id();
                var dir = output.resolve(id); Files.createDirectories(dir);
                FrozenReplayInput referenceInput = null;
                WideCompactQualityMain.Run reference = null, candidate = null;
                try {
                    referenceInput = WideCompactQualityMain.historical(scene.input());
                    reference = WideCompactRetentionMain.executeReference(scene, dir.resolve("reference"), POLICY);
                    if (reference.pipeline().result().inference().workAccounting()
                            .map(work -> work.hasPolicy(BQualityProbe.ORACLE_VERSION)).orElse(false)) referenceInput = scene.input();
                    report.scene(id).mark(WideCompactRetentionFlow.Stage.REFERENCE_EXECUTION,
                            WideCompactRetentionFlow.Status.PASS, "actual native reference returned; backend separately assessed");
                } catch (Throwable failure) {
                    report.fail(id, WideCompactRetentionFlow.Stage.REFERENCE_EXECUTION, failure);
                }
                try {
                    candidate = WideCompactQualityMain.execute(scene, scene.input(), false, true, dir.resolve("candidate"), POLICY);
                    report.scene(id).mark(WideCompactRetentionFlow.Stage.CANDIDATE_EXECUTION,
                            WideCompactRetentionFlow.Status.PASS, "actual native V5 candidate returned; compact proof separately assessed");
                } catch (Throwable failure) {
                    report.fail(id, WideCompactRetentionFlow.Stage.CANDIDATE_EXECUTION, failure);
                }
                WideCompactRetentionMain.completeScene(report, scene, referenceInput, reference, scene.input(), candidate, dir, POLICY);
                Files.writeString(output.resolve("progress-after-" + id + ".json"), report.json(commit), StandardOpenOption.CREATE_NEW);
            }
        } finally {
            report.write(output, commit, manifest);
        }
        WideCompactRetentionMain.requireAccepted(report);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private WideFrontierRetentionMain() { }
}

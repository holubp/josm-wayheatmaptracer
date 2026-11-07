package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure CLI, frozen-source and report wiring tests: never execute an engine. */
class V022WideFrontierRetentionMainTest {
    private static Properties frozen() throws Exception {
        var properties = new Properties();
        properties.load(new StringReader(WideFrontierRetentionMain.frozenManifest()));
        return properties;
    }

    @Test void cliHasOneFixedV5ModeAndCannotChoosePoliciesOrSkipScenes() {
        assertEquals(Path.of("build/reports/wide-frontier-v5-retention"),
                WideFrontierRetentionMain.invocation(new String[]{"--frontier-retention"}).output());
        assertEquals(Path.of("out"), WideFrontierRetentionMain.invocation(new String[]{"--frontier-retention", "out"}).output());
        for (var args : List.of(new String[]{}, new String[]{"--retention"},
                new String[]{"--frontier-retention", "--policy=V4"},
                new String[]{"--frontier-retention", "out", "--skip=faint-apex"})) {
            assertThrows(IllegalArgumentException.class, () -> WideFrontierRetentionMain.invocation(args));
        }
    }

    @Test void allSixFrozenScenesHaveExactlyTheTypedV5PolicyOnlyTransform() throws Exception {
        var properties = frozen();
        var bindings = WideFrontierRetentionMain.preflight(properties);
        assertEquals(List.of("remote-drift", "thin-hairpin", "faint-apex", "distinct-parallel",
                "diffuse-three-strand", "remote-drift-reversed"),
                bindings.stream().map(binding -> binding.scene().id()).toList());
        for (var binding : bindings) {
            assertEquals(properties.getProperty(binding.scene().id()), binding.frozenV4Hash());
            assertNotEquals(binding.frozenV4Hash(), binding.scene().input().canonicalHash());
            assertEquals(BPolicy.INVENTORY_FRONTIER_V5, BPolicy.recorded(binding.scene().input().request()));
            assertThrows(AssertionError.class, () -> WideFrontierRetentionMain.requirePolicyOnlyScene(
                    WideCompactScenes.scene(binding.scene().id()), WideCompactScenes.scene(binding.scene().id())));
        }
    }

    @Test void changedFrozenHashMissingSceneOrSpecificationFailsBeforeAnyExecution() throws Exception {
        for (String key : List.of("spec", "remote-drift", "faint-apex", "remote-drift-reversed")) {
            var changed = frozen(); changed.setProperty(key, "changed");
            assertThrows(AssertionError.class, () -> WideFrontierRetentionMain.preflight(changed));
            var missing = frozen(); missing.remove(key);
            assertThrows(AssertionError.class, () -> WideFrontierRetentionMain.preflight(missing));
        }
    }

    @Test void settingsOnlySubstitutionCannotHideBehindTheTypedV5Parameter() {
        var original = WideCompactScenes.scene("remote-drift", BPolicy.INVENTORY_PREP_V4);
        var frontier = WideCompactScenes.scene("remote-drift", BPolicy.INVENTORY_FRONTIER_V5);
        var source = frontier.input(); var r = source.request();
        var changedRequest = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest(
                r.selectedWayKey(), r.selectedRange(), r.engine(), r.geometryMode(), r.permissions(), r.budgets(),
                r.evidenceSnapshotId(), r.evidenceContentHash(), r.networkSnapshotId(), r.networkContentHash(),
                "changed-settings", r.parameterHash(), r.samplerId(), r.configuredSampleStepMeters(),
                r.profileChainage(), r.evidenceResolution(), r.corridorInput(), r.disconnectionSource());
        var changedInput = new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput(
                changedRequest, source.evidence(), source.network(), source.options(), source.planSearchRadiusMeters(), source.legacyB());
        var changed = new WideCompactScenes.Scene(frontier.id(), changedInput, frontier.analytic(),
                frontier.strong(), frontier.hairpin(), frontier.length());
        assertThrows(AssertionError.class, () -> WideFrontierRetentionMain.requirePolicyOnlyScene(original, changed));
        var relabeled = new WideCompactScenes.Scene(frontier.id(), frontier.input(), frontier.analytic(),
                !frontier.strong(), frontier.hairpin(), frontier.length());
        assertThrows(AssertionError.class, () -> WideFrontierRetentionMain.requirePolicyOnlyScene(original, relabeled));
    }

    @Test void ciAndGradleUseSeparateV5EntrypointAndOnlyScopedPublicArtifacts() throws Exception {
        var build = Files.readString(Path.of("build.gradle.kts"));
        int task = build.indexOf("tasks.register<JavaExec>(\"v022WideFrontierRetention\")");
        assertTrue(task >= 0);
        var block = build.substring(task, build.indexOf("\n}", task));
        assertTrue(block.contains("WideFrontierRetentionMain"));
        assertTrue(block.contains("\"--frontier-retention\""));
        assertTrue(block.contains("-Xmx512m"));
        var workflow = Files.readString(Path.of(".github/workflows/wide-frontier-v5-retention.yml"));
        assertTrue(workflow.contains("branches: [\"codex/v022-b-frontier-v5-qualification-20261107\"]"));
        assertTrue(workflow.contains("v022WideFrontierRetention -PvalidationProfile=public"));
        assertTrue(workflow.contains("build/reports/wide-frontier-v5-retention/"));
        assertTrue(workflow.contains("build/reports/wide-frontier-v5-source-evidence/"));
        assertFalse(workflow.contains("B_ROOT"));
        assertFalse(workflow.contains("private-drasov"));
        assertFalse(workflow.contains("v022WideCompactRetention -P"));
        // The pinned host provider's actual receipt name is an interface, not an invented artifact.
        assertTrue(Files.readString(Path.of("scripts/prepare-ci-josm19555.py")).contains("RECEIPT_NAME = \"host-runtime.json\""));
        assertTrue(workflow.contains("host / 'host-runtime.json'"));
    }

    @Test void partialReportKeepsAllScenesUnavailableAndNeverClaimsNativeOrReleasePass(@TempDir Path output) throws Exception {
        WideFrontierRetentionMain.requireFreshOutput(output);
        var bindings = WideFrontierRetentionMain.preflight(frozen());
        var report = WideFrontierRetentionMain.newReport();
        report.fail("faint-apex", WideCompactRetentionFlow.Stage.CANDIDATE_EXECUTION,
                new IllegalStateException("wiring failure; no native run"));
        WideFrontierRetentionMain.writeBindings(output, bindings, true, "unavailable-local");
        report.write(output, "unavailable-local", WideFrontierRetentionMain.frozenManifest());
        var summary = Files.readString(output.resolve("summary.json"));
        for (String id : WideCompactScenes.IDS) assertTrue(summary.contains('"' + id + '"'));
        assertTrue(summary.contains("\"status\":\"FAILED\""));
        assertTrue(summary.contains("\"releaseQualified\":false"));
        assertTrue(summary.contains("\"referenceRun\":null"));
        assertTrue(summary.contains("\"candidateRun\":null"));
        var contract = Files.readString(output.resolve("frontier-source-bindings.json"));
        assertTrue(contract.contains("public-wide-frontier-v5-performance-retention"));
        assertTrue(contract.contains(BPolicy.FRONTIER_COMPACT_VERSION));
        assertFalse(contract.contains("\"nativeExecution\":\"PASS\""));
        var parsed = org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader
                .parseObject(Files.readAllBytes(output.resolve("frontier-source-bindings.json")), "frontier-source-bindings.json");
        assertEquals(false, parsed.get("releaseQualified"));
        assertEquals("INVENTORY_FRONTIER_V5", parsed.get("policy"));
        assertEquals(6, ((List<?>) parsed.get("bindings")).size());
        assertThrows(AssertionError.class, () -> WideCompactRetentionMain.requireAccepted(report));
        assertThrows(java.io.IOException.class, () -> WideFrontierRetentionMain.requireFreshOutput(output));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> WideFrontierRetentionMain.writeBindings(output, bindings, true, "unavailable-local"));
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.BenchmarkDecisionComparatorMain;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

class V022BenchmarkFinalOutputComparisonTest {
    private static FrozenReplayInput input;
    private static Format15ReplayRunner.Result output;

    @BeforeAll
    static void frozenOutput() {
        input = V022ProductionReplayTest.syntheticRidgeInput();
        output = Format15ReplayRunner.replay(input, ReplayLevel.FINAL_GEOMETRY,
                TrackerMode.PROBABILISTIC);
    }

    @Test
    void completeNativeOutputsCompareAcrossOwnBuildAndRunLocalInputBindings(@TempDir Path directory)
            throws Exception {
        NetworkSnapshot old = input.network();
        NetworkSnapshot network = new NetworkSnapshot(old.snapshotId(), old.role(),
                "second-run", old.sourceGeneration(), old.closure(), old.primitives(),
                old.incomingReferrerWatches());
        TraceRequest r = input.request();
        TraceRequest request = new TraceRequest(r.selectedWayKey(), r.selectedRange(), r.engine(),
                r.geometryMode(), r.permissions(), r.budgets(), r.evidenceSnapshotId(),
                r.evidenceContentHash(), r.networkSnapshotId(), network.canonicalHash(),
                r.settingsHash(), r.parameterHash(), r.samplerId(), r.configuredSampleStepMeters(),
                r.profileChainage(), r.evidenceResolution(), r.corridorInput());
        FrozenReplayInput second = new FrozenReplayInput(request, input.evidence(), network, input.options());
        BenchmarkDecisionComparatorMain.requireSame(input, second);
        var firstArchive = archive(directory.resolve("first.zip"), bundle("old-build", input, output));
        var secondArchive = archive(directory.resolve("second.zip"), bundle("new-build", second,
                new Format15ReplayRunner.Result(output.level(), output.capturedEngine(), output.engine(),
                        output.inference(), output.routes(), second.canonicalHash())));
        var compared = BenchmarkFinalOutputComparison.compare(firstArchive, input, secondArchive, second);
        assertEquals(output.routes(), compared.candidate().output().routes());
        assertEquals(0, compared.candidate().routeIndex());
    }

    @Test
    void identicalGeometryWithChangedNativeQualityCannotCompare(@TempDir Path directory) throws Exception {
        var route = output.routes().get(0);
        var q = route.quality();
        var changed = new FinalGeometryEvaluator.Result(q.id(), q.disposition(), q.findings(),
                q.totalLengthMeters() + 0.01, q.directlySupportedLengthMeters(),
                q.worstUnsupportedSpanMeters(), q.meanImageCenterCost(), q.bendPreservingRoughness());
        var alteredRoute = new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(),
                route.pointIds(), route.assignments(), route.sourceOwnership(), changed,
                route.cleanupStatus(), route.geometryChanged());
        var routes = new java.util.ArrayList<>(output.routes());
        routes.set(0, alteredRoute);
        var altered = new Format15ReplayRunner.Result(output.level(), output.capturedEngine(),
                output.engine(), output.inference(), routes, output.inputHash());
        var baseline = archive(directory.resolve("base.zip"), bundle("old-build", input, output));
        var candidate = archive(directory.resolve("candidate.zip"), bundle("new-build", input, altered));
        assertThrows(IllegalStateException.class,
                () -> BenchmarkFinalOutputComparison.compare(baseline, input, candidate, input));
    }

    @ParameterizedTest
    @MethodSource("corruptions")
    void requiredNativeEnvelopeAndPublishedRouteFailClosed(String corruption, @TempDir Path directory)
            throws Exception {
        var original = bundle("build", input, output);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(original.artifacts());
        if (corruption.equals("missing-companion")) artifacts.remove(FinalOutputComponentsCodec.ARTIFACT);
        else if (corruption.equals("missing-expectation")) artifacts.remove(FinalReplayExpectation.ARTIFACT_NAME);
        else if (corruption.equals("trailing-companion")) {
            byte[] old = artifacts.get(FinalOutputComponentsCodec.ARTIFACT).bytes();
            artifacts.put(FinalOutputComponentsCodec.ARTIFACT, Format15Artifact.binary(
                    FinalOutputComponentsCodec.ARTIFACT, java.util.Arrays.copyOf(old, old.length + 1)));
        } else if (corruption.equals("final-route")) artifacts.put("final-route.json",
                Format15Artifact.text("final-route.json", "{\"coordinateSpace\":\"local-meters\",\"points\":[]}\n"));
        else {
            String status = new String(artifacts.get("attempt-status.json").bytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (corruption.equals("blocked")) status = status.replace("review-required", "blocked");
            if (corruption.equals("missing-index")) status = status.replace("\"routeIndex\":0,", "");
            if (corruption.equals("invalid-index")) status = status.replace("\"routeIndex\":0", "\"routeIndex\":9999");
            artifacts.put("attempt-status.json", Format15Artifact.text("attempt-status.json", status));
        }
        var good = archive(directory.resolve("good.zip"), original);
        var bad = archive(directory.resolve("bad.zip"), new Format15Bundle(original.buildIdentity(),
                original.sourceIdentityHash(), original.parameterHash(), artifacts));
        assertThrows(RuntimeException.class,
                () -> BenchmarkFinalOutputComparison.compare(good, input, bad, input));
    }

    static Stream<String> corruptions() {
        return Stream.of("missing-companion", "missing-expectation", "trailing-companion",
                "final-route", "blocked", "missing-index", "invalid-index");
    }

    private static Format15Bundle bundle(String build, FrozenReplayInput frozen,
            Format15ReplayRunner.Result result) {
        return Format15ProductionBundleFactory.createLive(build, frozen,
                new ModernTracePipeline.Result(result.inference(), result.routes()),
                "review-required", "synthetic-source", 0, null, false, false);
    }

    private static Format15Archive archive(Path path, Format15Bundle bundle) throws Exception {
        Format15BundleWriter.write(bundle, path);
        return Format15ArchiveReader.read(path);
    }
}

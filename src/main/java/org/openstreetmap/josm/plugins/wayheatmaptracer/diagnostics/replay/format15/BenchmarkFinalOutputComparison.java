package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

/** Native-bound, complete final-output admission for paired private benchmark publications. */
public final class BenchmarkFinalOutputComparison {
    private BenchmarkFinalOutputComparison() { }

    /** The archive's own complete output and explicitly published route selection. */
    public record VerifiedOutput(Format15ReplayRunner.Result output, int routeIndex) {
        /** Returns the selected route whose exported geometry was verified exactly. */
        public ModernTracePipeline.Route selectedRoute() { return output.routes().get(routeIndex); }
    }

    /** Independently bound native outputs whose complete component inventories match. */
    public record Comparison(VerifiedOutput baseline, VerifiedOutput candidate) { }

    /**
     * Validates each own envelope before comparing every component digest and complete root.
     * Build and input identities remain native to each archive; no payload is rebound or replayed.
     */
    public static Comparison compare(Format15Archive baseline, FrozenReplayInput baselineInput,
            Format15Archive candidate, FrozenReplayInput candidateInput) {
        FinalOutputComponentsCodec.Snapshot first = read(baseline, baselineInput);
        FinalOutputComponentsCodec.Snapshot second = read(candidate, candidateInput);
        var x = first.binding();
        var y = second.binding();
        if (!first.digests().equals(second.digests())
                || !x.rootFingerprint().equals(y.rootFingerprint())
                || !x.parameterHash().equals(y.parameterHash())
                || x.capturedEngine() != y.capturedEngine()
                || x.requestedEngine() != y.requestedEngine()
                || x.replayLevel() != y.replayLevel()
                || x.fingerprintSchema() != y.fingerprintSchema()) {
            throw new IllegalStateException("Complete native final-output components differ");
        }
        VerifiedOutput a = published(baseline, first.output());
        VerifiedOutput b = published(candidate, second.output());
        if (a.routeIndex() != b.routeIndex()) {
            throw new IllegalStateException("Published native route selection differs");
        }
        return new Comparison(a, b);
    }

    private static FinalOutputComponentsCodec.Snapshot read(Format15Archive archive,
            FrozenReplayInput input) {
        FinalReplayExpectation expectation = FinalReplayExpectation.read(archive).orElseThrow(
                () -> new IllegalStateException("Complete native output expectation is required"));
        expectation.validateBinding(archive, input);
        return FinalOutputComponentsCodec.decode(archive.artifact(FinalOutputComponentsCodec.ARTIFACT)
                .orElseThrow(() -> new IllegalStateException("Complete native output companion is required"))
                .bytes(), expectation.componentsBinding());
    }

    private static VerifiedOutput published(Format15Archive archive, Format15ReplayRunner.Result output) {
        Map<String, Object> status;
        try {
            status = Format15ArchiveReader.parseObject(archive.artifact("attempt-status.json")
                    .orElseThrow(() -> new IllegalStateException("Published route status is required")).bytes(),
                    "attempt-status.json");
        } catch (Format15ArchiveException invalid) {
            throw new IllegalStateException("Published route status is malformed", invalid);
        }
        String state = ScalarReplayExpectation.string(status, "status");
        int routeIndex = ScalarReplayExpectation.integer(status, "routeIndex");
        if (!state.equals("preview-open") && !state.equals("review-required")
                || routeIndex < 0 || routeIndex >= output.routes().size()) {
            throw new IllegalStateException("Publication is blocked or lacks a usable native route");
        }
        byte[] expected = Format15ProductionBundleFactory.metricGeometry(
                output.routes().get(routeIndex).hypothesis().points()).getBytes(StandardCharsets.UTF_8);
        byte[] published = archive.artifact("final-route.json")
                .orElseThrow(() -> new IllegalStateException("Published final route is required")).bytes();
        if (!Arrays.equals(expected, published)) {
            throw new IllegalStateException("Published final route differs from its native companion");
        }
        return new VerifiedOutput(output, routeIndex);
    }
}

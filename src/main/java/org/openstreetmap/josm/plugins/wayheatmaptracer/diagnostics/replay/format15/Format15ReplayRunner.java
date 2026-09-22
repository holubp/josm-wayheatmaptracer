package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorEngineAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.HybridTraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image.DirectionalImageTraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticTraceEngine;

/** Strict offline runner that always invokes an actual production engine on frozen inputs. */
public final class Format15ReplayRunner {
    /** Result materialized from a real production computation, never a recorded candidate. */
    public record Result(ReplayLevel level, TrackerMode capturedEngine, TrackerMode engine,
            TraceHypothesisSet inference, List<ModernTracePipeline.Route> routes,
            String inputHash) {
        public Result {
            if (level == null || capturedEngine == null || engine == null || inference == null
                    || routes == null || inputHash == null || inputHash.isBlank()) {
                throw new IllegalArgumentException("Production replay result is incomplete");
            }
            if (inference.engine() != engine) {
                throw new IllegalArgumentException("Production replay engine lineage is inconsistent");
            }
            routes = List.copyOf(routes);
        }

        /** Returns actual route geometry for manifest/invariant validation. */
        public List<TraceHypothesis> geometry() {
            return routes.stream().map(ModernTracePipeline.Route::hypothesis).toList();
        }
    }

    private Format15ReplayRunner() { }

    /** Runs the captured engine at a supported scalar or final level. */
    public static Result replay(Format15Archive archive, ReplayLevel level,
            String expectedInputHash, String expectedParameterHash) {
        FrozenReplayInput input = load(archive, level, expectedInputHash, expectedParameterHash);
        return replay(input, level, input.request().engine());
    }

    /** Loads a verified archive and runs one requested engine over the exact frozen values. */
    public static Result replay(Format15Archive archive, ReplayLevel level, String expectedInputHash,
            String expectedParameterHash, TrackerMode engine) {
        FrozenReplayInput input = load(archive, level, expectedInputHash, expectedParameterHash);
        return replay(input, level, engine);
    }

    /** Executes one requested modern engine over the same complete frozen attempt values. */
    public static Result replay(FrozenReplayInput input, ReplayLevel level, TrackerMode engine) {
        if (input == null || level == null || engine == null) {
            throw new IllegalArgumentException("Frozen input, level, and engine are required");
        }
        requireSupportedLevel(level);
        if (engine == TrackerMode.LEGACY_V02) {
            throw new ReplayMismatchException("Legacy engine has no modern frozen replay route");
        }
        if ((engine == TrackerMode.CORRIDOR_AWARE || engine == TrackerMode.HYBRID)
                && input.request().corridorInput().isEmpty()) {
            throw new ReplayMismatchException(
                "Requested engine requires an exact frozen CorridorTraceInput");
        }
        TrackerMode capturedEngine = input.request().engine();
        String inputHash = input.canonicalHash();
        FrozenReplayInput requested = withEngine(input, engine);
        return level == ReplayLevel.SCALAR_INFERENCE
            ? scalar(requested, capturedEngine, inputHash)
            : finalGeometry(requested, capturedEngine, inputHash);
    }

    private static FrozenReplayInput load(Format15Archive archive, ReplayLevel level,
            String expectedInputHash, String expectedParameterHash) {
        if (archive == null || level == null || expectedInputHash == null
                || expectedParameterHash == null) {
            throw new IllegalArgumentException("Replay archive, level, and identities are required");
        }
        requireSupportedLevel(level);
        if (!archive.capability().supports(level)) {
            throw new ReplayMismatchException(
                "Replay archive lacks required executable capability: " + level);
        }
        if (!archive.sourceIdentityHash().equals(expectedInputHash)
                || !archive.parameterHash().equals(expectedParameterHash)) {
            throw new ReplayMismatchException("Replay source or parameter identity does not match");
        }
        FrozenReplayInput input = archive.artifact("frozen-input.bin")
            .map(Format15Artifact::bytes)
            .map(FrozenReplayCodec::decode)
            .orElseThrow(() -> new ReplayMismatchException(
                "Replay archive lacks frozen production input"));
        if (!input.canonicalHash().equals(archive.sourceIdentityHash())
                || !input.request().parameterHash().equals(archive.parameterHash())) {
            throw new ReplayMismatchException(
                "Frozen replay input identity disagrees with archive manifest");
        }
        return input;
    }

    private static void requireSupportedLevel(ReplayLevel level) {
        if (level != ReplayLevel.SCALAR_INFERENCE && level != ReplayLevel.FINAL_GEOMETRY) {
            throw new ReplayMismatchException(
                "Replay level is not implemented by the production runner: " + level);
        }
    }

    private static FrozenReplayInput withEngine(FrozenReplayInput input, TrackerMode engine) {
        TraceRequest source = input.request();
        TraceRequest request = new TraceRequest(source.selectedWayKey(), source.selectedRange(),
            engine, source.geometryMode(), source.permissions(), source.budgets(),
            source.evidenceSnapshotId(), source.evidenceContentHash(), source.networkSnapshotId(),
            source.networkContentHash(), source.settingsHash(), source.parameterHash(),
            source.samplerId(), source.configuredSampleStepMeters(), source.profileChainage(),
            source.evidenceResolution(), source.corridorInput());
        return new FrozenReplayInput(request, input.evidence(), input.network(), input.options());
    }

    private static Result scalar(FrozenReplayInput input, TrackerMode capturedEngine,
            String inputHash) {
        try {
            TraceEngine engine = engine(input.request().engine(), input.options().fieldName());
            TraceHypothesisSet inference = engine.trace(input.request(), input.evidence(),
                input.network(), CancellationProbe.NONE);
            return new Result(ReplayLevel.SCALAR_INFERENCE, capturedEngine,
                input.request().engine(), inference, List.of(), inputHash);
        } catch (RuntimeException failure) {
            throw new ReplayMismatchException("production-engine-execution-failed");
        }
    }

    private static Result finalGeometry(FrozenReplayInput input, TrackerMode capturedEngine,
            String inputHash) {
        try {
            ModernTracePipeline.Result actual = new ModernTracePipeline(
                new CorridorEngineAdapter(input.options().fieldName())).run(input.request(),
                input.evidence(), input.network(), input.options(), CancellationProbe.NONE);
            return new Result(ReplayLevel.FINAL_GEOMETRY, capturedEngine,
                input.request().engine(), actual.inference(), actual.routes(), inputHash);
        } catch (RuntimeException failure) {
            throw new ReplayMismatchException("production-engine-execution-failed");
        }
    }

    private static TraceEngine engine(TrackerMode mode, String fieldName) {
        return switch (mode) {
            case CORRIDOR_AWARE -> new CorridorEngineAdapter(fieldName);
            case PROBABILISTIC -> new ProbabilisticTraceEngine(fieldName);
            case HYBRID -> new HybridTraceEngine(new CorridorEngineAdapter(fieldName),
                new ProbabilisticTraceEngine(fieldName, EvidenceModelParameters.defaults(),
                        ProbabilisticTraceEngine.ReliabilityPolicy.BASELINE));
            case DIRECTIONAL_IMAGE -> new DirectionalImageTraceEngine(fieldName);
            case LEGACY_V02 -> throw new ReplayMismatchException(
                "Legacy engine has no modern frozen replay route");
        };
    }
}

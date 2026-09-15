package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

/** Versioned binding between a frozen input and one expected final-geometry output. */
final class FinalReplayExpectation {
    static final String ARTIFACT_NAME = "expected-final-output.json";
    private static final String SCHEMA = "wayheatmaptracer-final-output-expectation-1";
    private static final Set<String> KEYS = Set.of("schema", "fingerprintSchema",
        "buildIdentity", "inputHash", "parameterHash", "capturedEngine",
        "requestedEngine", "replayLevel", "fingerprintSha256");

    private final String buildIdentity;
    private final String inputHash;
    private final String parameterHash;
    private final TrackerMode capturedEngine;
    private final TrackerMode requestedEngine;
    private final String fingerprint;

    private FinalReplayExpectation(String buildIdentity, String inputHash,
            String parameterHash, TrackerMode capturedEngine, TrackerMode requestedEngine,
            String fingerprint) {
        this.buildIdentity = buildIdentity;
        this.inputHash = inputHash;
        this.parameterHash = parameterHash;
        this.capturedEngine = capturedEngine;
        this.requestedEngine = requestedEngine;
        this.fingerprint = fingerprint;
    }

    static FinalReplayExpectation capture(String buildIdentity, FrozenReplayInput input,
            Format15ReplayRunner.Result actual) {
        if (input == null || actual == null || actual.level() != ReplayLevel.FINAL_GEOMETRY
                || actual.capturedEngine() != input.request().engine()
                || !ScalarReplayExpectation.isExecutableModern(actual.capturedEngine())
                || !ScalarReplayExpectation.isExecutableModern(actual.engine())
                || !actual.inputHash().equals(input.canonicalHash())) {
            throw new IllegalArgumentException("final-expectation-capture-invalid");
        }
        Format15Safety.requireSafeExportedMetadata(buildIdentity);
        return new FinalReplayExpectation(buildIdentity, actual.inputHash(),
            input.request().parameterHash(), actual.capturedEngine(), actual.engine(),
            FinalReplayFingerprint.sha256(actual));
    }

    static Optional<FinalReplayExpectation> read(Format15Archive archive) {
        Optional<Format15Artifact> artifact = archive.artifact(ARTIFACT_NAME);
        if (artifact.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes = artifact.orElseThrow().bytes();
        if (bytes.length > ScalarReplayExpectation.MAX_BYTES) {
            throw new ReplayMismatchException("expected-final-output-invalid");
        }
        try {
            Map<String, Object> object = Format15ArchiveReader.parseObject(bytes, ARTIFACT_NAME);
            if (!object.keySet().equals(KEYS)
                    || !SCHEMA.equals(ScalarReplayExpectation.string(object, "schema"))
                    || ScalarReplayExpectation.integer(object, "fingerprintSchema")
                        != FinalReplayFingerprint.SCHEMA_VERSION
                    || !ReplayLevel.FINAL_GEOMETRY.name().equals(
                        ScalarReplayExpectation.string(object, "replayLevel"))) {
                throw new IllegalArgumentException("schema");
            }
            String build = ScalarReplayExpectation.string(object, "buildIdentity");
            Format15Safety.requireSafeExportedMetadata(build);
            String input = Format15Safety.requiredHash(
                ScalarReplayExpectation.string(object, "inputHash"), "inputHash");
            String parameter = Format15Safety.requiredHash(
                ScalarReplayExpectation.string(object, "parameterHash"), "parameterHash");
            String fingerprint = Format15Safety.requiredHash(
                ScalarReplayExpectation.string(object, "fingerprintSha256"),
                "fingerprintSha256");
            TrackerMode captured = TrackerMode.valueOf(
                ScalarReplayExpectation.string(object, "capturedEngine"));
            TrackerMode requested = TrackerMode.valueOf(
                ScalarReplayExpectation.string(object, "requestedEngine"));
            if (!ScalarReplayExpectation.isExecutableModern(captured)
                    || !ScalarReplayExpectation.isExecutableModern(requested)) {
                throw new IllegalArgumentException("engine");
            }
            return Optional.of(new FinalReplayExpectation(build, input, parameter,
                captured, requested, fingerprint));
        } catch (Format15ArchiveException | IllegalArgumentException exception) {
            throw new ReplayMismatchException("expected-final-output-invalid");
        }
    }

    byte[] bytes() {
        ScalarReplayExpectation.requireBuildIdentityFitsEnvelope(buildIdentity);
        String json = "{\"schema\":" + ScalarReplayExpectation.quote(SCHEMA)
            + ",\"fingerprintSchema\":" + FinalReplayFingerprint.SCHEMA_VERSION
            + ",\"buildIdentity\":" + ScalarReplayExpectation.quote(buildIdentity)
            + ",\"inputHash\":" + ScalarReplayExpectation.quote(inputHash)
            + ",\"parameterHash\":" + ScalarReplayExpectation.quote(parameterHash)
            + ",\"capturedEngine\":" + ScalarReplayExpectation.quote(capturedEngine.name())
            + ",\"requestedEngine\":" + ScalarReplayExpectation.quote(requestedEngine.name())
            + ",\"replayLevel\":" + ScalarReplayExpectation.quote(
                ReplayLevel.FINAL_GEOMETRY.name())
            + ",\"fingerprintSha256\":" + ScalarReplayExpectation.quote(fingerprint) + "}\n";
        return ScalarReplayExpectation.encodeJson(json, "final-expectation-budget",
            "final-expectation-invalid");
    }

    void validateBinding(Format15Archive archive, FrozenReplayInput input) {
        if (!buildIdentity.equals(archive.buildIdentity())
                || !inputHash.equals(archive.sourceIdentityHash())
                || !parameterHash.equals(archive.parameterHash())
                || !inputHash.equals(input.canonicalHash())
                || !parameterHash.equals(input.request().parameterHash())
                || capturedEngine != input.request().engine()) {
            throw new ReplayMismatchException("expected-final-output-stale");
        }
    }

    TrackerMode requestedEngine() {
        return requestedEngine;
    }

    boolean matches(Format15ReplayRunner.Result actual) {
        return actual.level() == ReplayLevel.FINAL_GEOMETRY
            && actual.capturedEngine() == capturedEngine
            && actual.engine() == requestedEngine
            && actual.inputHash().equals(inputHash)
            && FinalReplayFingerprint.sha256(actual).equals(fingerprint);
    }
}

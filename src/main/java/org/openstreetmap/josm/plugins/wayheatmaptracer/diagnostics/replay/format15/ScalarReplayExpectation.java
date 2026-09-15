package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

/** Versioned binding between a frozen input and one expected scalar production output. */
final class ScalarReplayExpectation {
    static final String ARTIFACT_NAME = "expected-scalar-output.json";
    private static final String SCHEMA = "wayheatmaptracer-scalar-output-expectation-1";
    static final int MAX_BYTES = 16 * 1024;
    private static final Set<String> KEYS = Set.of("schema", "fingerprintSchema",
        "buildIdentity", "inputHash", "parameterHash", "capturedEngine",
        "requestedEngine", "replayLevel", "fingerprintSha256");

    private final String buildIdentity;
    private final String inputHash;
    private final String parameterHash;
    private final TrackerMode capturedEngine;
    private final TrackerMode requestedEngine;
    private final String fingerprint;

    private ScalarReplayExpectation(String buildIdentity, String inputHash,
            String parameterHash, TrackerMode capturedEngine, TrackerMode requestedEngine,
            String fingerprint) {
        this.buildIdentity = buildIdentity;
        this.inputHash = inputHash;
        this.parameterHash = parameterHash;
        this.capturedEngine = capturedEngine;
        this.requestedEngine = requestedEngine;
        this.fingerprint = fingerprint;
    }

    static ScalarReplayExpectation capture(String buildIdentity, FrozenReplayInput input,
            Format15ReplayRunner.Result actual) {
        if (input == null || actual == null || actual.level() != ReplayLevel.SCALAR_INFERENCE
                || actual.capturedEngine() != input.request().engine()
                || !isExecutableModern(actual.capturedEngine())
                || !isExecutableModern(actual.engine())
                || !actual.inputHash().equals(input.canonicalHash())) {
            throw new IllegalArgumentException("scalar-expectation-capture-invalid");
        }
        Format15Safety.requireSafeExportedMetadata(buildIdentity);
        return new ScalarReplayExpectation(buildIdentity, actual.inputHash(),
            input.request().parameterHash(), actual.capturedEngine(), actual.engine(),
            ScalarReplayFingerprint.sha256(actual.inference()));
    }

    static Optional<ScalarReplayExpectation> read(Format15Archive archive) {
        Optional<Format15Artifact> artifact = archive.artifact(ARTIFACT_NAME);
        if (artifact.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes = artifact.orElseThrow().bytes();
        if (bytes.length > MAX_BYTES) {
            throw new ReplayMismatchException("expected-scalar-output-invalid");
        }
        try {
            Map<String, Object> object = Format15ArchiveReader.parseObject(bytes, ARTIFACT_NAME);
            if (!object.keySet().equals(KEYS)
                    || !SCHEMA.equals(string(object, "schema"))
                    || integer(object, "fingerprintSchema")
                        != ScalarReplayFingerprint.SCHEMA_VERSION
                    || !ReplayLevel.SCALAR_INFERENCE.name().equals(
                        string(object, "replayLevel"))) {
                throw new IllegalArgumentException("schema");
            }
            String build = string(object, "buildIdentity");
            Format15Safety.requireSafeExportedMetadata(build);
            String input = Format15Safety.requiredHash(string(object, "inputHash"), "inputHash");
            String parameter = Format15Safety.requiredHash(
                string(object, "parameterHash"), "parameterHash");
            String fingerprint = Format15Safety.requiredHash(
                string(object, "fingerprintSha256"), "fingerprintSha256");
            TrackerMode captured = TrackerMode.valueOf(string(object, "capturedEngine"));
            TrackerMode requested = TrackerMode.valueOf(string(object, "requestedEngine"));
            if (!isExecutableModern(captured) || !isExecutableModern(requested)) {
                throw new IllegalArgumentException("engine");
            }
            return Optional.of(new ScalarReplayExpectation(build, input, parameter,
                captured, requested, fingerprint));
        } catch (Format15ArchiveException | IllegalArgumentException exception) {
            throw new ReplayMismatchException("expected-scalar-output-invalid");
        }
    }

    byte[] bytes() {
        requireBuildIdentityFitsEnvelope(buildIdentity);
        String json = "{\"schema\":" + quote(SCHEMA)
            + ",\"fingerprintSchema\":" + ScalarReplayFingerprint.SCHEMA_VERSION
            + ",\"buildIdentity\":" + quote(buildIdentity)
            + ",\"inputHash\":" + quote(inputHash)
            + ",\"parameterHash\":" + quote(parameterHash)
            + ",\"capturedEngine\":" + quote(capturedEngine.name())
            + ",\"requestedEngine\":" + quote(requestedEngine.name())
            + ",\"replayLevel\":" + quote(ReplayLevel.SCALAR_INFERENCE.name())
            + ",\"fingerprintSha256\":" + quote(fingerprint) + "}\n";
        return encodeJson(json, "scalar-expectation-budget",
            "scalar-expectation-invalid");
    }

    static byte[] encodeJson(String json, String budgetReason, String invalidReason) {
        if (json == null || json.length() > MAX_BYTES) {
            throw new IllegalArgumentException(budgetReason);
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(json));
            if (encoded.remaining() > MAX_BYTES) {
                throw new IllegalArgumentException(budgetReason);
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(invalidReason, exception);
        }
    }

    static void requireBuildIdentityFitsEnvelope(String buildIdentity) {
        if (buildIdentity == null || buildIdentity.length() > MAX_BYTES) {
            throw new IllegalArgumentException("scalar-expectation-budget");
        }
    }

    void validateBinding(Format15Archive archive, FrozenReplayInput input) {
        if (!buildIdentity.equals(archive.buildIdentity())
                || !inputHash.equals(archive.sourceIdentityHash())
                || !parameterHash.equals(archive.parameterHash())
                || !inputHash.equals(input.canonicalHash())
                || !parameterHash.equals(input.request().parameterHash())
                || capturedEngine != input.request().engine()) {
            throw new ReplayMismatchException("expected-scalar-output-stale");
        }
    }

    TrackerMode requestedEngine() {
        return requestedEngine;
    }

    boolean matches(Format15ReplayRunner.Result actual) {
        return actual.level() == ReplayLevel.SCALAR_INFERENCE
            && actual.capturedEngine() == capturedEngine
            && actual.engine() == requestedEngine
            && actual.inputHash().equals(inputHash)
            && ScalarReplayFingerprint.sha256(actual.inference()).equals(fingerprint);
    }

    static boolean isExecutableModern(TrackerMode engine) {
        return engine == TrackerMode.CORRIDOR_AWARE
            || engine == TrackerMode.PROBABILISTIC
            || engine == TrackerMode.HYBRID
            || engine == TrackerMode.DIRECTIONAL_IMAGE;
    }

    static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException("field");
        }
        return string;
    }

    static int integer(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Number number) || number.intValue() != number.doubleValue()) {
            throw new IllegalArgumentException("field");
        }
        return number.intValue();
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                result.append('\\');
            }
            if (character < 0x20) {
                result.append(String.format("\\u%04x", (int) character));
            } else {
                result.append(character);
            }
        }
        return result.append('"').toString();
    }
}

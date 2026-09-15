package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.LinkedHashMap;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

/** Produces the named, checksummed frozen inputs consumed by strict production replay. */
public final class Format15ProductionBundleFactory {
    private Format15ProductionBundleFactory() {
    }

    /** Creates a Format-15 archive payload for scalar/final replay only; it does not claim raster or edit-plan replay. */
    public static Format15Bundle create(String buildIdentity, FrozenReplayInput input) {
        if (buildIdentity == null || buildIdentity.isBlank() || input == null) {
            throw new IllegalArgumentException("Build identity and frozen replay input are required");
        }
        byte[] frozenInput = FrozenReplayCodec.encode(input);
        String inputHash = Format15Safety.sha256(frozenInput);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>();
        artifacts.put("frozen-input.bin",
            Format15Artifact.binary("frozen-input.bin", frozenInput));
        String identities = "{\"codecVersion\":" + FrozenReplayCodec.VERSION
            + ",\"inputHash\":" + quote(inputHash) + ",\"evidenceHash\":"
            + quote(input.evidence().canonicalHash()) + ",\"networkHash\":"
            + quote(input.network().canonicalHash()) + ",\"parameterHash\":"
            + quote(input.request().parameterHash()) + "}\n";
        artifacts.put("frozen-input-identities.json",
            Format15Artifact.text("frozen-input-identities.json", identities));
        artifacts.put("trace-request.json", Format15Artifact.text("trace-request.json",
            "{\"engine\":" + quote(input.request().engine().name()) + ",\"fieldName\":"
                + quote(input.options().fieldName()) + ",\"frozenInputHash\":"
                + quote(inputHash) + "}\n"));
        artifacts.put("evidence-frame.json", Format15Artifact.text("evidence-frame.json",
            "{\"snapshotId\":" + quote(input.evidence().snapshotId())
                + ",\"contentHash\":" + quote(input.evidence().canonicalHash()) + "}\n"));
        return new Format15Bundle(buildIdentity, inputHash,
            input.request().parameterHash(), artifacts);
    }

    /** Creates a frozen bundle that records one actual scalar production result. */
    public static Format15Bundle createWithExpectedScalarOutput(String buildIdentity,
            FrozenReplayInput input, TrackerMode requestedEngine) {
        ScalarReplayExpectation.requireBuildIdentityFitsEnvelope(buildIdentity);
        Format15Bundle base = create(buildIdentity, input);
        Format15ReplayRunner.Result actual = Format15ReplayRunner.replay(input,
            ReplayLevel.SCALAR_INFERENCE, requestedEngine);
        ScalarReplayExpectation expectation = ScalarReplayExpectation.capture(
            base.buildIdentity(), input, actual);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(base.artifacts());
        artifacts.put(ScalarReplayExpectation.ARTIFACT_NAME, Format15Artifact.binary(
            ScalarReplayExpectation.ARTIFACT_NAME, expectation.bytes()));
        return new Format15Bundle(base.buildIdentity(), base.sourceIdentityHash(),
            base.parameterHash(), artifacts);
    }

    /** Creates a frozen bundle that records one actual final-geometry production result. */
    public static Format15Bundle createWithExpectedFinalOutput(String buildIdentity,
            FrozenReplayInput input, TrackerMode requestedEngine) {
        ScalarReplayExpectation.requireBuildIdentityFitsEnvelope(buildIdentity);
        Format15Bundle base = create(buildIdentity, input);
        Format15ReplayRunner.Result actual = Format15ReplayRunner.replay(input,
            ReplayLevel.FINAL_GEOMETRY, requestedEngine);
        FinalReplayExpectation expectation = FinalReplayExpectation.capture(
            base.buildIdentity(), input, actual);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(base.artifacts());
        artifacts.put(FinalReplayExpectation.ARTIFACT_NAME, Format15Artifact.binary(
            FinalReplayExpectation.ARTIFACT_NAME, expectation.bytes()));
        return new Format15Bundle(base.buildIdentity(), base.sourceIdentityHash(),
            base.parameterHash(), artifacts);
    }

    private static String quote(String value) {
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

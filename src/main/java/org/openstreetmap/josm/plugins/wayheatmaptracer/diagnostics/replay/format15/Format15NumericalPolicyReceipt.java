package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernNumericalPolicy;

/** Bounded caller-declared arithmetic policy; never inferred from historic output values. */
final class Format15NumericalPolicyReceipt {
    static final String ARTIFACT = "numerical-policy.json";
    private static final int MAX_BYTES = 4096;
    private static final Set<String> KEYS = Set.of("schema", "declaration", "sourceIdentityHash",
        "inputHash", "parameterHash", "capturedEngine", "computedEngine", "inferencePolicy", "sharedPolicy");

    private Format15NumericalPolicyReceipt() { }

    static Format15Artifact current(String sourceHash, String inputHash, String parameterHash,
            TrackerMode capturedEngine, TrackerMode computedEngine) {
        if (!modern(capturedEngine) || !modern(computedEngine)) {
            throw new IllegalArgumentException("numerical-policy-engine-unsupported");
        }
        String json = "{\"schema\":1,\"declaration\":\"explicit-current-computation\","
            + "\"sourceIdentityHash\":\"" + Format15Safety.requiredHash(sourceHash, "sourceHash") + "\","
            + "\"inputHash\":" + (inputHash == null ? "null" : "\""
                + Format15Safety.requiredHash(inputHash, "inputHash") + "\"") + ","
            + "\"parameterHash\":\"" + Format15Safety.requiredHash(parameterHash, "parameterHash") + "\","
            + "\"capturedEngine\":\"" + capturedEngine.name() + "\","
            + "\"computedEngine\":\"" + computedEngine.name() + "\","
            + "\"inferencePolicy\":\"" + inferencePolicy(computedEngine) + "\","
            + "\"sharedPolicy\":\"" + ModernNumericalPolicy.SHARED_COST + "\"}\n";
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("numerical-policy-budget");
        }
        return Format15Artifact.text(ARTIFACT, json);
    }

    static TrackerMode validate(Format15Archive archive, FrozenReplayInput input, String verifiedInputHash) {
        Format15Artifact artifact = archive.artifact(ARTIFACT)
            .orElseThrow(() -> new ReplayMismatchException("numerical-policy-unattested"));
        if (artifact.sizeBytes() > MAX_BYTES) throw new ReplayMismatchException("numerical-policy-invalid");
        try {
            Map<String, Object> declared = Format15ArchiveReader.parseObject(artifact.bytes(), ARTIFACT);
            if (!declared.keySet().equals(KEYS) || !Long.valueOf(1L).equals(declared.get("schema"))) {
                throw new IllegalArgumentException("schema");
            }
            TrackerMode engine = TrackerMode.valueOf(ScalarReplayExpectation.string(declared, "computedEngine"));
            Map<String, Object> expected = Format15ArchiveReader.parseObject(
                current(archive.sourceIdentityHash(), verifiedInputHash, input.request().parameterHash(),
                    input.request().engine(), engine).bytes(), ARTIFACT);
            if (!declared.equals(expected)) throw new IllegalArgumentException("binding");
            var scalar = ScalarReplayExpectation.read(archive);
            var geometry = FinalReplayExpectation.read(archive);
            if (scalar.isPresent() && scalar.orElseThrow().requestedEngine() != engine
                    || geometry.isPresent() && geometry.orElseThrow().requestedEngine() != engine
                    || scalar.isEmpty() && geometry.isEmpty() && engine != input.request().engine()) {
                throw new ReplayMismatchException("numerical-policy-engine-mismatch");
            }
            return engine;
        } catch (Format15ArchiveException | IllegalArgumentException exception) {
            throw new ReplayMismatchException("numerical-policy-invalid");
        }
    }

    private static String inferencePolicy(TrackerMode engine) {
        return engine == TrackerMode.PROBABILISTIC || engine == TrackerMode.HYBRID
            ? ModernNumericalPolicy.B_INFERENCE : "engine-specific";
    }

    private static boolean modern(TrackerMode engine) {
        return engine != null && engine != TrackerMode.LEGACY_V02;
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ExecutionWorkReceipt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticExecutionPolicy;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernNumericalPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/** Bounded caller-declared runtime arithmetic/objective policy, separate from captured parameter identity. */
final class Format15NumericalPolicyReceipt {
    static final String ARTIFACT = "numerical-policy.json";
    private static final int MAX_BYTES = 4096;
    private static final Set<String> KEYS = Set.of("schema", "declaration", "sourceIdentityHash",
        "inputHash", "parameterHash", "capturedEngine", "computedEngine", "inferencePolicy", "sharedPolicy");

    private Format15NumericalPolicyReceipt() { }

    static Format15Artifact current(String sourceHash, String inputHash, String parameterHash,
            TrackerMode capturedEngine, TrackerMode computedEngine,
            List<Optional<ExecutionWorkReceipt>> receipts) {
        if (!probabilistic(computedEngine)) {
            return current(sourceHash, inputHash, parameterHash, capturedEngine, computedEngine);
        }
        String legacy = new String(current(sourceHash, inputHash, parameterHash,
                capturedEngine, computedEngine).bytes(), StandardCharsets.UTF_8);
        String json = legacy.stripTrailing().substring(0, legacy.stripTrailing().length() - 1)
                .replace("\"schema\":1", "\"schema\":2")
                + ",\"executionWorkHash\":\"" + Format15ExecutionWorkCodec.artifact(receipts).sha256() + "\"}\n";
        if (receipts.stream().anyMatch(receipt -> receipt.isEmpty()
                || !receipt.orElseThrow().hasPolicy(ProbabilisticExecutionPolicy.CURRENT_VERSION))) {
            json = json.replace("explicit-current-computation", "execution-unattested");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("numerical-policy-budget");
        }
        return Format15Artifact.text(ARTIFACT, json);
    }

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
            TrackerMode engine = TrackerMode.valueOf(ScalarReplayExpectation.string(declared, "computedEngine"));
            boolean scored = probabilistic(engine);
            var keys = new java.util.HashSet<>(KEYS);
            if (scored) keys.add("executionWorkHash");
            if (!declared.keySet().equals(keys)
                    || !Long.valueOf(scored ? 2L : 1L).equals(declared.get("schema"))) {
                throw new IllegalArgumentException("schema");
            }
            var workArtifact = scored ? archive.artifact(Format15ExecutionWorkCodec.ARTIFACT)
                    .orElseThrow(() -> new IllegalArgumentException("execution-work-unattested")) : null;
            if (scored && !workArtifact.sha256().equals(declared.get("executionWorkHash"))) {
                throw new IllegalArgumentException("execution-work-binding-invalid");
            }
            List<Optional<ExecutionWorkReceipt>> receipts = scored
                    ? Format15ExecutionWorkCodec.read(workArtifact) : List.of();
            long computed = 0L;
            for (var receipt : receipts) {
                if (receipt.isEmpty() || !receipt.orElseThrow().hasPolicy(ProbabilisticExecutionPolicy.CURRENT_VERSION)) {
                    throw new IllegalArgumentException("execution-policy-unattested");
                }
                computed = Math.addExact(computed, receipt.orElseThrow().computedTransitions());
            }
            if (computed > input.request().budgets().maximumTransitions()) {
                throw new IllegalArgumentException("execution-work-budget");
            }
            Map<String, Object> expected = Format15ArchiveReader.parseObject(
                (scored ? current(archive.sourceIdentityHash(), verifiedInputHash, input.request().parameterHash(),
                    input.request().engine(), engine, receipts)
                    : current(archive.sourceIdentityHash(), verifiedInputHash, input.request().parameterHash(),
                    input.request().engine(), engine)).bytes(), ARTIFACT);
            if (!declared.equals(expected)) throw new IllegalArgumentException("binding");
            var scalar = ScalarReplayExpectation.read(archive);
            var geometry = FinalReplayExpectation.read(archive);
            if (scalar.isPresent() && scalar.orElseThrow().requestedEngine() != engine
                    || geometry.isPresent() && geometry.orElseThrow().requestedEngine() != engine
                    || scalar.isEmpty() && geometry.isEmpty() && engine != input.request().engine()) {
                throw new ReplayMismatchException("numerical-policy-engine-mismatch");
            }
            return engine;
        } catch (Format15ArchiveException | IllegalArgumentException | ArithmeticException exception) {
            throw new ReplayMismatchException("numerical-policy-invalid");
        }
    }

    private static String inferencePolicy(TrackerMode engine) {
        if (engine == TrackerMode.LEGACY_THEN_B) return "legacy-then-b-v1/" + inferencePolicy(TrackerMode.PROBABILISTIC);
        return engine == TrackerMode.PROBABILISTIC || engine == TrackerMode.HYBRID
            ? ModernNumericalPolicy.B_INFERENCE + "/" + EvidenceModelParameters.orientationIntegrationVersion()
                + "/" + org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic
                    .ProbabilisticTraceEngine.reliabilityPolicyVersionFor(engine)
                + "/" + EvidenceModelParameters.profileSamplingPolicyVersion()
                + "/" + org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic
                    .ProbabilisticTraceEngine.stateSamplingPolicyVersionFor(engine)
                + "/" + ProbabilisticExecutionPolicy.CURRENT_VERSION
            : "engine-specific";
    }

    private static boolean modern(TrackerMode engine) {
        return engine != null && engine != TrackerMode.LEGACY_V02;
    }

    private static boolean probabilistic(TrackerMode engine) {
        return engine == TrackerMode.PROBABILISTIC || engine == TrackerMode.HYBRID;
    }

    static void validateExecuted(TraceHypothesisSet inference) {
        if (probabilistic(inference.engine()) && (inference.workAccounting().isEmpty()
                || !inference.workAccounting().orElseThrow().hasPolicy(ProbabilisticExecutionPolicy.CURRENT_VERSION))) {
            throw new ReplayMismatchException("numerical-policy-runtime-unattested");
        }
    }

    static void validateRecordedWork(Format15Archive archive, List<TraceHypothesisSet> actual) {
        if (actual.isEmpty() || !probabilistic(actual.get(0).engine())) return;
        try {
            var policy = Format15ArchiveReader.parseObject(archive.artifact(ARTIFACT).orElseThrow().bytes(), ARTIFACT);
            if (!actual.get(0).engine().name().equals(policy.get("computedEngine"))) return;
            var captured = Format15ExecutionWorkCodec.read(
                    archive.artifact(Format15ExecutionWorkCodec.ARTIFACT).orElseThrow());
            if (!captured.equals(actual.stream().map(TraceHypothesisSet::workAccounting).toList())) {
                throw new ReplayMismatchException("numerical-policy-runtime-work-mismatch");
            }
        } catch (Format15ArchiveException | IllegalArgumentException | java.util.NoSuchElementException malformed) {
            throw new ReplayMismatchException("numerical-policy-runtime-work-mismatch");
        }
    }
}

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

    /** Actual ordered child/interval evidence is assembled before this declaration. */
    static Format15Artifact actualRuns(String sourceHash,String inputHash,FrozenReplayInput input,Format15Artifact admission) {
        var runs=Format15BRunAdmissionReceipt.read(admission);
        if(!runs.sourceHash().equals(sourceHash)||!runs.inputHash().equals(inputHash)||!runs.parameterHash().equals(input.request().parameterHash()))
            throw new IllegalArgumentException("b-run-numerical-binding-invalid");
        String json=new String(withLegacySourcePolicy(current(sourceHash,inputHash,input.request().parameterHash(),
                input.request().engine(),input.request().engine()),input.request().engine(),input.legacyB()).bytes(),StandardCharsets.UTF_8);
        if(input.request().engine()==TrackerMode.LEGACY_THEN_B)json=json.replace(inferencePolicy(TrackerMode.PROBABILISTIC),
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BReferencePolicyBinding.VERSION+"/"
                +org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BPolicy.INVENTORY_PREP_VERSION);
        json=json.stripTrailing();json=json.substring(0,json.length()-1).replace("\"schema\":1","\"schema\":3")
                +",\"executionWorkHash\":\""+Format15BRunAdmissionReceipt.workArtifact(runs).sha256()
                +"\",\"runAdmissionsHash\":\""+admission.sha256()+"\"}\n";
        if(!Format15BRunAdmissionReceipt.attested(runs,input.request()))json=json.replace("explicit-current-computation","execution-unattested");
        if(json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException("numerical-policy-budget");
        return Format15Artifact.text(ARTIFACT,json);
    }

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
        String expectedExecution = executionPolicy(computedEngine, parameterHash);
        if (receipts.stream().anyMatch(receipt -> receipt.isEmpty()
                || !matchesPolicy(receipt.orElseThrow(),expectedExecution))) {
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
            + "\"inferencePolicy\":\"" + inferencePolicy(computedEngine, parameterHash) + "\","
            + "\"sharedPolicy\":\"" + ModernNumericalPolicy.SHARED_COST + "\"}\n";
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("numerical-policy-budget");
        }
        return Format15Artifact.text(ARTIFACT, json);
    }

    /** Binds Hybrid's effective source policy while retaining the byte-exact historical receipt. */
    static Format15Artifact withLegacySourcePolicy(Format15Artifact receipt, TrackerMode engine,
            FrozenLegacyBInput source) {
        if (engine != TrackerMode.LEGACY_THEN_B || source == null
                || !source.hasExplicitNormalPolicy() && !source.hasExplicitTrackerPolicy()) return receipt;
        String json = new String(receipt.bytes(), StandardCharsets.UTF_8);
        String old = "\"inferencePolicy\":\"" + inferencePolicy(engine) + "\"";
        String actual;
        if (source.hasExplicitTrackerPolicy()) {
            String pitch = source.nativeSourcePitchRasterPx().isPresent()
                    ? Double.toString(source.nativeSourcePitchRasterPx().getAsDouble()) : "unavailable";
            String normalFallback = source.normalPolicyFallbackReason().isEmpty()
                    ? "none" : source.normalPolicyFallbackReason();
            String trackerFallback = source.trackerPolicyFallbackReason().isEmpty()
                    ? "none" : source.trackerPolicyFallbackReason();
            actual = "\"inferencePolicy\":\"legacy-then-b-v3/" + source.normalPolicy().id() + "/"
                    + normalFallback + "/" + source.trackerPolicy().id() + "/" + pitch + "/"
                    + trackerFallback + "/" + inferencePolicy(TrackerMode.PROBABILISTIC) + "\"";
        } else {
            actual = "\"inferencePolicy\":\"legacy-then-b-v2/" + source.normalPolicy().id() + "/"
                    + inferencePolicy(TrackerMode.PROBABILISTIC) + "\"";
        }
        if (!json.contains(old)) throw new IllegalArgumentException("Legacy numerical policy is unbound");
        return Format15Artifact.text(ARTIFACT, json.replace(old, actual));
    }

    static TrackerMode validate(Format15Archive archive, FrozenReplayInput input, String verifiedInputHash) {
        Format15Artifact artifact = archive.artifact(ARTIFACT)
            .orElseThrow(() -> new ReplayMismatchException("numerical-policy-unattested"));
        if (artifact.sizeBytes() > MAX_BYTES) throw new ReplayMismatchException("numerical-policy-invalid");
        try {
            Map<String, Object> declared = Format15ArchiveReader.parseObject(artifact.bytes(), ARTIFACT);
            if(Long.valueOf(3L).equals(declared.get("schema"))) {
                var runs=Format15BRunAdmissionReceipt.validate(archive,input,verifiedInputHash);
                var admission=archive.artifact(Format15BRunAdmissionReceipt.ARTIFACT).orElseThrow();
                var expected=Format15ArchiveReader.parseObject(actualRuns(archive.sourceIdentityHash(),verifiedInputHash,input,admission).bytes(),ARTIFACT);
                if(!declared.equals(expected)||!"explicit-current-computation".equals(declared.get("declaration")))
                    throw new IllegalArgumentException("actual-run-binding");
                var scalar=ScalarReplayExpectation.read(archive);var geometry=FinalReplayExpectation.read(archive);
                if(scalar.isPresent()&&scalar.orElseThrow().requestedEngine()!=input.request().engine()
                        ||geometry.isPresent()&&geometry.orElseThrow().requestedEngine()!=input.request().engine())
                    throw new ReplayMismatchException("numerical-policy-engine-mismatch");
                return input.request().engine();
            }
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
                if (receipt.isEmpty() || !matchesPolicy(receipt.orElseThrow(),executionPolicy(engine, input.request().parameterHash()))) {
                    throw new IllegalArgumentException("execution-policy-unattested");
                }
                computed = Math.addExact(computed, receipt.orElseThrow().computedTransitions());
            }
            if (computed > input.request().budgets().maximumTransitions()) {
                throw new IllegalArgumentException("execution-work-budget");
            }
            if(engine==TrackerMode.PROBABILISTIC&&Format15DenseBAdmissionReceipt.required(input.request().parameterHash()))
                Format15DenseBAdmissionReceipt.validate(archive,input,verifiedInputHash);
            Format15Artifact expectedReceipt = scored ? current(archive.sourceIdentityHash(), verifiedInputHash, input.request().parameterHash(),
                    input.request().engine(), engine, receipts)
                    : current(archive.sourceIdentityHash(), verifiedInputHash, input.request().parameterHash(),
                    input.request().engine(), engine);
            Map<String, Object> expected = Format15ArchiveReader.parseObject(
                    withLegacySourcePolicy(expectedReceipt, engine, input.legacyB()).bytes(), ARTIFACT);
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

    private static String executionPolicy(TrackerMode engine, String parameterHash) {
        String adaptive=org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BPolicy.adaptiveVersion(parameterHash);
        return engine == TrackerMode.PROBABILISTIC && adaptive!=null ? adaptive : ProbabilisticExecutionPolicy.CURRENT_VERSION;
    }
    private static boolean matchesPolicy(ExecutionWorkReceipt receipt,String expected) {
        if(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BPolicy.DENSE_ADMISSION_VERSION.equals(expected)
                ||org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BPolicy.INVENTORY_PREP_VERSION.equals(expected)) {
            var selected=org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BPolicy.DENSE_ADMISSION_VERSION.equals(expected)
                    ?org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BPolicy.DENSE_ADMITTED_V3
                    :org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BPolicy.INVENTORY_PREP_V4;
            return receipt.bStages().size()==1&&receipt.otherEngineTransitions()==0
                    &&(receipt.hasPolicy(selected.fullExecutionVersion())||receipt.hasPolicy(selected.compactExecutionVersion()));
        }
        return receipt.hasPolicy(expected);
    }
    private static String inferencePolicy(TrackerMode engine, String parameterHash) {
        String original = inferencePolicy(engine);
        return executionPolicy(engine, parameterHash).equals(ProbabilisticExecutionPolicy.CURRENT_VERSION)
                ? original : original.replace("/" + ProbabilisticExecutionPolicy.CURRENT_VERSION,
                    "/" + executionPolicy(engine,parameterHash));
    }

    private static boolean modern(TrackerMode engine) {
        return engine != null && engine != TrackerMode.LEGACY_V02;
    }

    private static boolean probabilistic(TrackerMode engine) {
        return engine == TrackerMode.PROBABILISTIC || engine == TrackerMode.HYBRID;
    }

    static void validateExecuted(TraceHypothesisSet inference) {
        validateExecuted(inference, null);
    }

    static void validateExecuted(TraceHypothesisSet inference, String parameterHash) {
        if (probabilistic(inference.engine()) && (inference.workAccounting().isEmpty()
                || !matchesPolicy(inference.workAccounting().orElseThrow(),executionPolicy(inference.engine(), parameterHash)))) {
            throw new ReplayMismatchException("numerical-policy-runtime-unattested");
        }
    }

    static void validateReferenceExecuted(TraceHypothesisSet inference,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Reference reference) {
        if(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BReferencePolicyBinding.recordedPolicy(reference)
                ==org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BPolicy.INVENTORY_PREP_V4) {
            if(inference.workAccounting().isEmpty()||!matchesPolicy(inference.workAccounting().orElseThrow(),
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BPolicy.INVENTORY_PREP_VERSION))
                throw new ReplayMismatchException("numerical-policy-runtime-unattested");
        }else validateExecuted(inference);
    }

    static void validateRecordedWork(Format15Archive archive, List<TraceHypothesisSet> actual) {
        if (actual.isEmpty() || !probabilistic(actual.get(0).engine())) return;
        try {
            var policy = Format15ArchiveReader.parseObject(archive.artifact(ARTIFACT).orElseThrow().bytes(), ARTIFACT);
            if(Long.valueOf(3L).equals(policy.get("schema"))) {
                var runs=Format15BRunAdmissionReceipt.read(archive.artifact(Format15BRunAdmissionReceipt.ARTIFACT).orElseThrow());
                if(!Format15BRunAdmissionReceipt.workArtifact(runs).sha256().equals(
                        Format15ExecutionWorkCodec.artifact(actual.stream().map(TraceHypothesisSet::workAccounting).toList()).sha256()))
                    throw new ReplayMismatchException("numerical-policy-runtime-work-mismatch");
                return;
            }
            if (!actual.get(0).engine().name().equals(policy.get("computedEngine"))) return;
            var captured = Format15ExecutionWorkCodec.read(
                    archive.artifact(Format15ExecutionWorkCodec.ARTIFACT).orElseThrow());
            if (!captured.equals(actual.stream().map(TraceHypothesisSet::workAccounting).toList())) {
                throw new ReplayMismatchException("numerical-policy-runtime-work-mismatch");
            }
            Format15DenseBAdmissionReceipt.validateActual(archive,actual);
        } catch (Format15ArchiveException | IllegalArgumentException | java.util.NoSuchElementException malformed) {
            throw new ReplayMismatchException("numerical-policy-runtime-work-mismatch");
        }
    }
}

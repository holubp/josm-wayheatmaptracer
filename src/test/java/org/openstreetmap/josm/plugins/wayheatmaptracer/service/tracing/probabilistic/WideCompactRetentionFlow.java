package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Separate runner-stage ledger and report for the named public retention assessment. */
final class WideCompactRetentionFlow {
    enum Status { PASS, FAILED, UNAVAILABLE, NOT_RUN }
    enum Stage { REFERENCE_EXECUTION, REFERENCE_IDENTITY, REFERENCE_NATIVE_AUTHORITY, REFERENCE_DENSE_COMPLETE,
        REFERENCE_GEOMETRY, REFERENCE_BRANCH, CANDIDATE_EXECUTION, CANDIDATE_IDENTITY, CANDIDATE_NATIVE_AUTHORITY,
        CANDIDATE_BRANCH, CANDIDATE_SAFETY, CANDIDATE_GEOMETRY, COMPARISON }
    record FirstFailure(String scene, Stage stage, Throwable cause, Status status) { }
    record RunFacts(String backend, String inputHash, String evidenceHash, String networkHash, String parameterHash,
            String policy, String inferenceStatus, String completion, long computedTransitions, long denseStates,
            int denseCalls, int inferenceCalls, int closedCalls, long ownerClosedBytes, long peakBytes,
            int retainedPasses, long retainedCells) { }

    static final class SceneRecord {
        private final String id;
        private final EnumMap<Stage,Status> stages=new EnumMap<>(Stage.class);
        private final EnumMap<Stage,String> details=new EnumMap<>(Stage.class);
        private WideCompactRetentionAssessment.Input input;
        private WideCompactRetentionAssessment.Result result;
        private RunFacts referenceFacts, candidateFacts;
        SceneRecord(String id) {
            this.id=id;
            for(var stage:Stage.values()) { stages.put(stage,Status.NOT_RUN);details.put(stage,""); }
        }
        void mark(Stage stage,Status status,String detail) { stages.put(stage,status);details.put(stage,detail==null?"":detail); }
        Status stage(Stage stage) { return stages.get(stage); }
        String detail(Stage stage) { return details.get(stage); }
        void setFacts(RunFacts reference,RunFacts candidate) { referenceFacts=reference;candidateFacts=candidate; }
        WideCompactRetentionAssessment.Result setAssessment(WideCompactRetentionAssessment.Input input) {
            this.input=new WideCompactRetentionAssessment.Input(input.sceneId(),input.strength(),runnerChecks(),input.distances(),
                    input.supportedBend(),input.absoluteAmplitudeApplicable(),input.amplitudes(),input.absoluteAmplitude(),
                    input.referenceGeometry(),input.candidateGeometry());
            this.result=WideCompactRetentionAssessment.assess(this.input);return result;
        }
        Status status() {
            if(result==null)return stages.values().stream().anyMatch(v->v==Status.FAILED)?Status.FAILED:Status.UNAVAILABLE;
            return switch(result.verdict()) {
                case RETENTION_PASS, RETENTION_PASS_WITH_BASELINE_DEBT -> Status.PASS;
                case ASSESSED_WITH_UNSUITABLE_REFERENCE -> id.equals("diffuse-three-strand")
                        && result.referenceDisposition()==WideCompactRetentionAssessment.ReferenceDisposition.UNSUITABLE_REFERENCE
                        && result.distanceStatus()==WideCompactRetentionAssessment.Status.FAILED
                        && result.distanceEvidenceCompleteness()==WideCompactRetentionAssessment.Status.PASS ? Status.PASS : Status.FAILED;
                case RETENTION_PASS_WITH_CANDIDATE_DEBT, RETENTION_PASS_WITH_REFERENCE_DEBT, FAILED -> Status.FAILED;
                case UNAVAILABLE -> Status.UNAVAILABLE;
            };
        }
        WideCompactRetentionAssessment.RunnerChecks runnerChecks() {
            return new WideCompactRetentionAssessment.RunnerChecks(evidence(Stage.REFERENCE_EXECUTION),evidence(Stage.REFERENCE_IDENTITY),
                    evidence(Stage.REFERENCE_NATIVE_AUTHORITY),evidence(Stage.REFERENCE_DENSE_COMPLETE),evidence(Stage.CANDIDATE_EXECUTION),
                    evidence(Stage.CANDIDATE_IDENTITY),evidence(Stage.CANDIDATE_NATIVE_AUTHORITY),evidence(Stage.CANDIDATE_BRANCH),
                    evidence(Stage.CANDIDATE_SAFETY));
        }
        private WideCompactRetentionAssessment.Evidence evidence(Stage stage) {
            return switch(stages.get(stage)) {
                case PASS -> WideCompactRetentionAssessment.Evidence.RUNNER_VALIDATED;
                case FAILED -> WideCompactRetentionAssessment.Evidence.FAILED;
                default -> WideCompactRetentionAssessment.Evidence.UNAVAILABLE;
            };
        }
        String id() { return id; }
        WideCompactRetentionAssessment.Result result() { return result; }
        RunFacts referenceFacts() { return referenceFacts; }
        RunFacts candidateFacts() { return candidateFacts; }
    }

    static final class Report {
        private final Map<String,SceneRecord> scenes=new LinkedHashMap<>();
        private FirstFailure firstFailure;
        void ensureScene(String id) { scenes.computeIfAbsent(id,SceneRecord::new); }
        SceneRecord scene(String id) { ensureScene(id);return scenes.get(id); }
        void fail(String id,Stage stage,Throwable failure) {
            var scene=scene(id);scene.mark(stage,Status.FAILED,failure.toString());
            if(firstFailure==null)firstFailure=new FirstFailure(id,stage,failure,Status.FAILED);
        }
        void unavailable(String id,Stage stage,String detail) {
            var scene=scene(id);scene.mark(stage,Status.UNAVAILABLE,detail);
            if(firstFailure==null)firstFailure=new FirstFailure(id,stage,new IllegalStateException(detail),Status.UNAVAILABLE);
        }
        FirstFailure firstFailure() { return firstFailure; }
        Status status() {
            if(firstFailure!=null&&firstFailure.status()==Status.FAILED)return Status.FAILED;
            if(scenes.values().stream().anyMatch(s->s.status()==Status.FAILED))return Status.FAILED;
            if(firstFailure!=null&&firstFailure.status()==Status.UNAVAILABLE)return Status.UNAVAILABLE;
            if(scenes.size()!=WideCompactScenes.IDS.size()||scenes.values().stream().anyMatch(s->s.status()!=Status.PASS))return Status.UNAVAILABLE;
            return Status.PASS;
        }
        boolean releaseQualified() { return false; }
        void write(Path output,String sourceCommit,String manifest) throws IOException {
            Files.createDirectories(output);
            if(Files.exists(output.resolve("summary.json")))throw new IOException("Existing retention evidence must not be overwritten");
            Files.writeString(output.resolve("public-wide-compact-v1.properties"),manifest,java.nio.file.StandardOpenOption.CREATE_NEW);
            Files.writeString(output.resolve("summary.json"),json(sourceCommit),java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        String json(String sourceCommit) {
            var out=new StringBuilder("{\"schema\":1,\"assessmentVersion\":\"").append(WideCompactRetentionAssessment.VERSION)
                    .append("\",\"gate\":\"public-wide-compact-performance-retention\",\"status\":\"").append(status())
                    .append("\",\"releaseQualified\":false,\"sourceCommit\":\"").append(escape(sourceCommit))
                    .append("\",\"requiredScenes\":").append(WideCompactScenes.IDS.size())
                    .append(",\"firstFailure\":");
            if(firstFailure==null)out.append("null");
            else out.append("{\"scene\":\"").append(escape(firstFailure.scene())).append("\",\"stage\":\"").append(firstFailure.stage())
                    .append("\",\"status\":\"").append(firstFailure.status()).append("\",\"type\":\"")
                    .append(escape(firstFailure.cause().getClass().getName())).append("\",\"message\":\"")
                    .append(escape(String.valueOf(firstFailure.cause().getMessage()))).append("\"}");
            out.append(",\"scenes\":{");boolean first=true;
            for(var entry:scenes.entrySet()) {
                if(!first)out.append(',');first=false;SceneRecord scene=entry.getValue();
                out.append('"').append(escape(entry.getKey())).append("\":{\"status\":\"").append(scene.status()).append("\",\"stages\":{");
                boolean firstStage=true;
                for(var stage:Stage.values()) { if(!firstStage)out.append(',');firstStage=false;out.append('"').append(stage).append("\":{\"status\":\"").append(scene.stage(stage)).append("\",\"detail\":\"").append(escape(scene.detail(stage))).append("\"}"); }
                out.append("},\"referenceRun\":");appendFacts(out,scene.referenceFacts);
                out.append(",\"candidateRun\":");appendFacts(out,scene.candidateFacts);
                out.append(",\"assessment\":");appendAssessment(out,scene.result,scene.input);
                out.append('}');
            }
            return out.append("}}\n").toString();
        }
    }

    static boolean accepted(SceneRecord scene) { return scene.status()==Status.PASS; }
    static boolean exitFailed(Report report) { return report.status()!=Status.PASS; }

    private static void appendFacts(StringBuilder out,RunFacts facts) {
        if(facts==null) { out.append("null");return; }
        out.append("{\"backend\":\"").append(escape(facts.backend())).append("\",\"inputHash\":\"").append(escape(facts.inputHash()))
                .append("\",\"evidenceHash\":\"").append(escape(facts.evidenceHash())).append("\",\"networkHash\":\"").append(escape(facts.networkHash()))
                .append("\",\"parameterHash\":\"").append(escape(facts.parameterHash())).append("\",\"policy\":\"").append(escape(facts.policy()))
                .append("\",\"inferenceStatus\":\"").append(escape(facts.inferenceStatus())).append("\",\"completion\":\"").append(escape(facts.completion()))
                .append("\",\"computedTransitions\":").append(facts.computedTransitions()).append(",\"denseStates\":").append(facts.denseStates())
                .append(",\"denseCalls\":").append(facts.denseCalls()).append(",\"inferenceCalls\":").append(facts.inferenceCalls())
                .append(",\"closedCalls\":").append(facts.closedCalls()).append(",\"ownerClosedBytes\":").append(facts.ownerClosedBytes())
                .append(",\"peakBytes\":").append(facts.peakBytes()).append(",\"retainedPasses\":").append(facts.retainedPasses())
                .append(",\"retainedCells\":").append(facts.retainedCells()).append('}');
    }

    private static void appendAssessment(StringBuilder out,WideCompactRetentionAssessment.Result result,WideCompactRetentionAssessment.Input input) {
        if(result==null||input==null) { out.append("null");return; }
        out.append("{\"version\":\"").append(result.version()).append("\",\"verdict\":\"").append(result.verdict())
                .append("\",\"distanceStatus\":\"").append(result.distanceStatus()).append("\",\"distanceEvidenceCompleteness\":\"").append(result.distanceEvidenceCompleteness())
                .append("\",\"referenceDisposition\":\"").append(result.referenceDisposition()).append("\",\"amplitudeRetentionStatus\":\"").append(result.amplitudeRetentionStatus())
                .append("\",\"amplitudeRetentionRatio\":").append(number(result.amplitudeRetentionRatio()))
                .append(",\"candidateAbsoluteQuality\":\"").append(result.candidateAbsoluteQuality()).append("\",\"referenceAbsoluteQuality\":\"").append(result.referenceAbsoluteQuality())
                .append("\",\"absoluteQualityDebt\":\"").append(result.absoluteQualityDebt()).append("\",\"releaseQualified\":false,\"measurements\":{");
        var d=input.distances();
        metric(out,"rawP95",d==null?null:d.rawP95());metric(out,"rawCertifiedMaximum",d==null?null:d.rawCertifiedMaximum());
        metric(out,"finalP95",d==null?null:d.finalP95());metric(out,"finalCertifiedMaximum",d==null?null:d.finalCertifiedMaximum());
        var reference=input.referenceGeometry();var candidate=input.candidateGeometry();
        metric(out,"referenceAnalyticP95",reference==null?null:reference.p95());metric(out,"referenceAnalyticCertifiedMaximum",reference==null?null:reference.certifiedMaximum());
        metric(out,"candidateAnalyticP95",candidate==null?null:candidate.p95());metric(out,"candidateAnalyticCertifiedMaximum",candidate==null?null:candidate.certifiedMaximum());
        out.append("},\"amplitudes\":");appendAmplitudes(out,input.amplitudes());
        out.append(",\"absoluteAmplitude\":");appendAbsolute(out,input.absoluteAmplitude());
        out.append(",\"reasons\":[");for(int i=0;i<result.reasons().size();i++){if(i>0)out.append(',');out.append('"').append(escape(result.reasons().get(i))).append('"');}out.append("]}");
    }
    private static void appendAmplitudes(StringBuilder out,WideCompactRetentionAssessment.Amplitudes a) {
        if(a==null){out.append("null");return;}
        out.append("{\"candidate\":");appendMetricValue(out,a.candidate());out.append(",\"reference\":");appendMetricValue(out,a.reference());
        out.append(",\"candidateInterval\":\"").append(escape(a.candidateInterval())).append("\",\"referenceInterval\":\"").append(escape(a.referenceInterval()))
                .append("\",\"candidateBaseline\":\"").append(escape(a.candidateBaseline())).append("\",\"referenceBaseline\":\"").append(escape(a.referenceBaseline())).append("\"}");
    }
    private static void appendAbsolute(StringBuilder out,WideCompactRetentionAssessment.AbsoluteAmplitude a) {
        if(a==null){out.append("null");return;}
        out.append("{\"candidate\":");appendMetricValue(out,a.candidate());out.append(",\"reference\":");appendMetricValue(out,a.reference());
        out.append(",\"target\":");appendMetricValue(out,a.target());out.append(",\"requiredMinimum\":");appendMetricValue(out,a.requiredMinimum());
        out.append(",\"interval\":\"").append(escape(a.interval())).append("\",\"baseline\":\"").append(escape(a.baseline())).append("\"}");
    }
    private static void appendMetricValue(StringBuilder out,WideCompactRetentionAssessment.Metric metric) {
        if(metric==null){out.append("null");return;}
        boolean measured=metric.value()!=null&&Double.isFinite(metric.value())&&metric.value()>=0;
        out.append("{\"value\":").append(measured?number(metric.value()):"null").append(",\"evidence\":\"")
                .append(measured&&metric.evidence()!=null?metric.evidence():WideCompactRetentionAssessment.Evidence.UNAVAILABLE).append("\"}");
    }
    private static void metric(StringBuilder out,String name,WideCompactRetentionAssessment.Metric metric) {
        if(out.charAt(out.length()-1)!='{')out.append(',');
        out.append('"').append(name).append("\":");
        if(metric==null)out.append("{\"value\":null,\"evidence\":\"UNAVAILABLE\"}");
        else appendMetricValue(out,metric);
    }

    private static String number(Double value) { return value==null||!Double.isFinite(value)?"null":Double.toString(value); }
    private static String escape(String value) {
        var out=new StringBuilder();
        for(char c:String.valueOf(value).toCharArray()) {
            switch(c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> { if(c<0x20)out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else out.append(c); }
            }
        }
        return out.toString();
    }

    private WideCompactRetentionFlow() { }
}

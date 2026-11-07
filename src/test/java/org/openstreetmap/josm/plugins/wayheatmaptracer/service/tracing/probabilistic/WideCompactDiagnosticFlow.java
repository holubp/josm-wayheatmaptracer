package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent assessment and first-failure reporting for the diagnostic driver. */
final class WideCompactDiagnosticFlow {
    enum Status { PASS, FAILED, UNAVAILABLE, NOT_RUN, DIAGNOSTIC_ONLY }
    enum Assessment { REFERENCE, REFERENCE_SAFETY, REFERENCE_ANALYTIC, CANDIDATE,
        CANDIDATE_BRANCH, CANDIDATE_SAFETY, CANDIDATE_ANALYTIC, COMPARISON, REVERSAL }
    @FunctionalInterface interface Action { void run() throws Exception; }

    record FirstFailure(String scene, Assessment assessment, String stage, Throwable cause) { }
    static final class Report {
        private final Map<String, EnumMap<Assessment, Status>> scenes = new LinkedHashMap<>();
        private final List<String> failures = new ArrayList<>();
        private FirstFailure firstFailure;
        private String firstFailureText;
        private String lastStage = "";

        void ensureScene(String scene) {
            scenes.computeIfAbsent(scene, ignored -> {
                var states = new EnumMap<Assessment, Status>(Assessment.class);
                for (var assessment : Assessment.values()) states.put(assessment, Status.NOT_RUN);
                return states;
            });
        }
        Status status(String scene, Assessment assessment) { ensureScene(scene); return scenes.get(scene).get(assessment); }
        void mark(String scene, Assessment assessment, Status status) { ensureScene(scene); scenes.get(scene).put(assessment, status); }
        FirstFailure firstFailure() { return firstFailure; }
        void begin(String stage) { lastStage = stage; }
        boolean failed() { return firstFailure != null; }
        void preserve(String scene, Assessment assessment, String stage, Throwable failure) {
            failures.add(scene + ":" + assessment + ":" + stage + ": " + failure);
            if (firstFailure == null) {
                firstFailure = new FirstFailure(scene, assessment, stage, failure);
                firstFailureText = failures.get(0);
            }
        }
        void markUnavailable(String scene, Assessment assessment) { mark(scene, assessment, Status.UNAVAILABLE); }
        void write(Path out, boolean diagnostic) throws IOException {
            Files.writeString(out.resolve("summary.json"), json(diagnostic));
            if (firstFailureText != null) Files.writeString(out.resolve("first-failure.txt"), firstFailureText + "\n");
        }
        private Status sceneStatus(EnumMap<Assessment, Status> values) {
            if (values.containsValue(Status.FAILED)) return Status.FAILED;
            if (values.values().stream().allMatch(s -> s == Status.NOT_RUN)) return Status.NOT_RUN;
            if (values.containsValue(Status.UNAVAILABLE)) return Status.UNAVAILABLE;
            for (var required : List.of(Assessment.REFERENCE, Assessment.REFERENCE_ANALYTIC, Assessment.CANDIDATE, Assessment.CANDIDATE_ANALYTIC, Assessment.COMPARISON))
                if (values.get(required) == Status.NOT_RUN) return Status.NOT_RUN;
            return Status.PASS;
        }
        private long completedScenes() {
            return scenes.values().stream().filter(v -> sceneStatus(v) == Status.PASS).count();
        }
        String json(boolean diagnostic) {
            if (!diagnostic) {
                var legacy = new StringBuilder("{\"schema\":1,\"status\":\"").append(failed() ? "FAILED" : "PASS")
                        .append("\",\"lastStage\":\"").append(escape(lastStage))
                        .append("\",\"requiredScenes\":").append(scenes.size()).append(",\"completedScenes\":")
                        .append(scenes.values().stream().filter(v -> sceneStatus(v) == Status.PASS).count()).append(",\"extentAdjustments\":1,\"scenes\":{");
                boolean first = true;
                for (var entry : scenes.entrySet()) { if (!first) legacy.append(','); first = false; legacy.append('\"').append(escape(entry.getKey())).append("\":\"").append(sceneStatus(entry.getValue())).append('\"'); }
                return legacy.append("}}\n").toString();
            }
            var out = new StringBuilder("{\"schema\":").append(diagnostic ? 2 : 1);
            out.append(",\"diagnosticOnly\":true,\"releaseQualified\":false");
            out.append(",\"status\":\"").append(failed() ? "FAILED" : "PASS").append("\",\"lastStage\":\"")
                    .append(escape(lastStage))
                    .append("\",\"firstFailureStage\":\"").append(firstFailure == null ? "" : escape(firstFailure.scene() + ":" + firstFailure.stage()))
                    .append("\",\"requiredScenes\":").append(scenes.size()).append(",\"completedScenes\":").append(completedScenes())
                    .append(",\"firstFailure\":\"").append(firstFailureText == null ? "" : escape(firstFailureText)).append("\",\"failures\":[");
            for (int i = 0; i < failures.size(); i++) { if (i > 0) out.append(','); out.append('\"').append(escape(failures.get(i))).append('\"'); }
            out.append("],\"scenes\":{");
            boolean firstScene = true;
            for (var entry : scenes.entrySet()) {
                if (!firstScene) out.append(','); firstScene = false;
                out.append('\"').append(escape(entry.getKey())).append("\":{\"status\":\"")
                        .append(sceneStatus(entry.getValue()))
                        .append("\",\"assessments\":{");
                boolean firstAssessment = true;
                for (var assessment : Assessment.values()) {
                    if (!firstAssessment) out.append(','); firstAssessment = false;
                    out.append('\"').append(assessment).append("\":\"").append(entry.getValue().get(assessment)).append('\"');
                }
                out.append("}}");
            }
            return out.append("}}\n").toString();
        }
    }

    static boolean runAssessment(Report report, String scene, Assessment assessment, String stage,
            boolean diagnostic, boolean qualityEligible, boolean diagnosticBaseline, Action action) throws Exception {
        report.ensureScene(scene);
        report.begin(stage);
        try {
            action.run();
            report.mark(scene, assessment, diagnosticBaseline ? Status.DIAGNOSTIC_ONLY : Status.PASS);
            return true;
        } catch (WideCompactQualityMain.QualityFailure failure) {
            report.mark(scene, assessment, Status.FAILED);
            if (!qualityEligible) {
                report.preserve(scene, assessment, stage, failure);
                throw failure;
            }
            report.preserve(scene, assessment, stage, failure);
            if (!diagnostic) throw failure;
            return false;
        } catch (Throwable failure) {
            report.mark(scene, assessment, Status.FAILED);
            report.preserve(scene, assessment, stage, failure);
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new AssertionError(failure);
        }
    }

    static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }

    static boolean exitFailed(Report report) { return report.failed(); }

    static void diagnosticComparisonStatus(Report report, String scene, boolean comparisonAvailable, boolean analyticReferenceFailed) {
        if (!comparisonAvailable) report.markUnavailable(scene, Assessment.COMPARISON);
        else if (analyticReferenceFailed && report.status(scene, Assessment.COMPARISON) == Status.PASS)
            report.mark(scene, Assessment.COMPARISON, Status.DIAGNOSTIC_ONLY);
    }

    private WideCompactDiagnosticFlow() { }
}

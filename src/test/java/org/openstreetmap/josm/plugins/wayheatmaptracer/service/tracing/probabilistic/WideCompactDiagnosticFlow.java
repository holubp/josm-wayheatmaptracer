package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.List;

/** Execution ordering for the explicitly diagnostic-only wide quality continuation. */
final class WideCompactDiagnosticFlow {
    enum Outcome { PASS, FAILED, NOT_RUN }
    enum Stage { REFERENCE, REFERENCE_QUALITY, CANDIDATE, CANDIDATE_QUALITY, COMPARISON }
    record Failure(Stage stage, String message) { }
    record SceneResult(Outcome outcome, List<Failure> failures) { }
    @FunctionalInterface interface Action { void run() throws Exception; }

    static SceneResult run(boolean diagnostic, Action reference, Action referenceQuality,
            Action candidate, Action candidateQuality, Action comparison) throws Exception {
        var failures = new ArrayList<Failure>();
        execute(Stage.REFERENCE, reference, failures);
        if (!failures.isEmpty()) return result(diagnostic, failures);
        execute(Stage.REFERENCE_QUALITY, referenceQuality, failures);
        if (!diagnostic && !failures.isEmpty()) return result(false, failures);
        int beforeCandidate = failures.size();
        execute(Stage.CANDIDATE, candidate, failures);
        if (failures.size() != beforeCandidate) return result(diagnostic, failures);
        execute(Stage.CANDIDATE_QUALITY, candidateQuality, failures);
        if (!diagnostic && !failures.isEmpty()) return result(false, failures);
        execute(Stage.COMPARISON, comparison, failures);
        return result(diagnostic, failures);
    }

    private static void execute(Stage stage, Action action, List<Failure> failures) throws Exception {
        try { action.run(); }
        catch (WideCompactQualityMain.QualityFailure failure) { failures.add(new Failure(stage, failure.getMessage())); }
    }

    private static SceneResult result(boolean diagnostic, List<Failure> failures) {
        return new SceneResult(failures.isEmpty() ? Outcome.PASS : Outcome.FAILED, List.copyOf(failures));
    }

    private WideCompactDiagnosticFlow() { }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class V022WideCompactDiagnosticFlowTest {
    private static WideCompactDiagnosticFlow.Action step(List<String> order, String name) {
        return () -> order.add(name);
    }
    private static WideCompactDiagnosticFlow.Action failure(List<String> order, String name, String message) {
        return () -> { order.add(name); throw new WideCompactQualityMain.QualityFailure(message); };
    }
    private static WideCompactDiagnosticFlow.SceneResult run(boolean diagnostic, List<String> order,
            WideCompactDiagnosticFlow.Action rq, WideCompactDiagnosticFlow.Action cq,
            WideCompactDiagnosticFlow.Action comparison) throws Exception {
        return WideCompactDiagnosticFlow.run(diagnostic, step(order,"reference"), rq,
                step(order,"candidate"), cq, comparison);
    }

    @Test void normalModeStopsAtFirstQualityFailure() throws Exception {
        var order = new ArrayList<String>();
        var result = run(false, order, failure(order,"reference-quality","ref"), step(order,"candidate-quality"), step(order,"comparison"));
        assertEquals(List.of("reference","reference-quality"), order);
        assertEquals(WideCompactDiagnosticFlow.Outcome.FAILED, result.outcome());
        assertEquals(WideCompactDiagnosticFlow.Stage.REFERENCE_QUALITY, result.failures().get(0).stage());
    }

    @Test void diagnosticRunsCandidateComparisonAndLaterSceneAfterReferenceQualityFailure() throws Exception {
        var order = new ArrayList<String>();
        var result = run(true, order, failure(order,"reference-quality","ref"), step(order,"candidate-quality"), step(order,"comparison"));
        assertEquals(List.of("reference","reference-quality","candidate","candidate-quality","comparison"), order);
        assertEquals(WideCompactDiagnosticFlow.Outcome.FAILED, result.outcome());
        assertEquals("ref", result.failures().get(0).message());
        order.clear();
        run(true, order, step(order,"reference-quality"), step(order,"candidate-quality"), step(order,"comparison"));
        assertEquals(List.of("reference","reference-quality","candidate","candidate-quality","comparison"), order);
    }

    @Test void multipleQualityFailuresRetainFirstAndComparisonFailure() throws Exception {
        var order = new ArrayList<String>();
        var result = run(true, order, failure(order,"reference-quality","first"), failure(order,"candidate-quality","second"), failure(order,"comparison","third"));
        assertEquals(List.of("reference","reference-quality","candidate","candidate-quality","comparison"), order);
        assertEquals(List.of("first","second","third"), result.failures().stream().map(WideCompactDiagnosticFlow.Failure::message).toList());
        assertEquals(WideCompactDiagnosticFlow.Outcome.FAILED, result.outcome());
    }

    @Test void integrityFailureStopsBeforeCandidateAndCannotBeConvertedToQualityFailure() {
        var order = new ArrayList<String>();
        var integrity = new IllegalStateException("identity failed");
        var thrown = assertThrows(IllegalStateException.class, () -> WideCompactDiagnosticFlow.run(true,
                step(order,"reference"), () -> { order.add("reference-quality"); throw integrity; },
                step(order,"candidate"), step(order,"candidate-quality"), step(order,"comparison")));
        assertSame(integrity, thrown);
        assertEquals(List.of("reference","reference-quality"), order);
    }

    @Test void arbitraryAssertionErrorIsNotAContinuationFailure() {
        var order = new ArrayList<String>();
        assertThrows(AssertionError.class, () -> WideCompactDiagnosticFlow.run(true,
                step(order,"reference"), () -> { order.add("reference-quality"); throw new AssertionError("not typed"); },
                step(order,"candidate"), step(order,"candidate-quality"), step(order,"comparison")));
        assertEquals(List.of("reference","reference-quality"), order);
    }

    @Test void invocationIsExplicitAndUnknownFlagsFailBeforeWork() {
        var normal=WideCompactQualityMain.invocation(new String[0]);
        assertFalse(normal.diagnostic());
        assertEquals("build/reports/wide-compact-quality",normal.output().toString());
        var diagnostic=WideCompactQualityMain.invocation(new String[]{"--diagnostic"});
        assertTrue(diagnostic.diagnostic());
        assertEquals("build/reports/wide-compact-diagnostic",diagnostic.output().toString());
        assertThrows(IllegalArgumentException.class,()->WideCompactQualityMain.invocation(new String[]{"--surprise"}));
        assertThrows(IllegalArgumentException.class,()->WideCompactQualityMain.invocation(new String[]{"--diagnostic","--surprise"}));
    }
}

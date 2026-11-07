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

    @Test void normalModeStopsAtFirstQualityFailure() throws Exception {
        var order = new ArrayList<String>(); var report = new WideCompactDiagnosticFlow.Report();
        assertThrows(WideCompactQualityMain.QualityFailure.class, () -> WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.REFERENCE_ANALYTIC,"reference-analytic",false,true,false,failure(order,"reference-quality","ref")));
        assertEquals(List.of("reference-quality"), order);
        assertEquals(WideCompactDiagnosticFlow.Status.FAILED, report.status("scene",WideCompactDiagnosticFlow.Assessment.REFERENCE_ANALYTIC));
        assertTrue(WideCompactDiagnosticFlow.exitFailed(report));
    }

    @Test void diagnosticRunsCandidateComparisonAndLaterSceneAfterReferenceQualityFailure() throws Exception {
        var order = new ArrayList<String>(); var report = new WideCompactDiagnosticFlow.Report();
        WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.REFERENCE,"reference",true,false,false,step(order,"reference"));
        assertFalse(WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.REFERENCE_ANALYTIC,"reference-analytic",true,true,false,failure(order,"reference-quality","ref")));
        WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.CANDIDATE,"candidate",true,false,false,step(order,"candidate"));
        WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.CANDIDATE_ANALYTIC,"candidate-analytic",true,true,false,step(order,"candidate-quality"));
        WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.COMPARISON,"comparison",true,true,true,step(order,"comparison"));
        assertEquals(List.of("reference","reference-quality","candidate","candidate-quality","comparison"), order);
        assertEquals(WideCompactDiagnosticFlow.Status.FAILED, report.status("scene",WideCompactDiagnosticFlow.Assessment.REFERENCE_ANALYTIC));
        assertEquals(WideCompactDiagnosticFlow.Status.DIAGNOSTIC_ONLY, report.status("scene",WideCompactDiagnosticFlow.Assessment.COMPARISON));
        WideCompactDiagnosticFlow.runAssessment(report,"later-scene",WideCompactDiagnosticFlow.Assessment.REFERENCE,"later-reference",true,false,false,step(order,"later-scene"));
        assertEquals(WideCompactDiagnosticFlow.Status.PASS,report.status("later-scene",WideCompactDiagnosticFlow.Assessment.REFERENCE));
        assertTrue(WideCompactDiagnosticFlow.exitFailed(report));
    }

    @Test void multipleQualityFailuresRetainFirstAndComparisonFailure() throws Exception {
        var order = new ArrayList<String>(); var report = new WideCompactDiagnosticFlow.Report();
        WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.REFERENCE_ANALYTIC,"ref-a",true,true,false,failure(order,"ra","first"));
        WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.CANDIDATE_ANALYTIC,"cand-a",true,true,false,failure(order,"ca","second"));
        WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.COMPARISON,"cmp",true,true,false,failure(order,"cmp","third"));
        assertEquals("first", report.firstFailure().cause().getMessage());
        assertTrue(WideCompactDiagnosticFlow.exitFailed(report));
    }

    @Test void integrityFailureStopsBeforeCandidateAndCannotBeConvertedToQualityFailure() {
        var order = new ArrayList<String>(); var report = new WideCompactDiagnosticFlow.Report();
        var integrity = new IllegalStateException("identity failed");
        var thrown = assertThrows(IllegalStateException.class, () -> WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.REFERENCE,"reference-integrity",true,false,false,()->{order.add("reference-quality"); throw integrity;}));
        assertSame(integrity, thrown);
        assertEquals(List.of("reference-quality"), order);
        assertEquals(WideCompactDiagnosticFlow.Status.NOT_RUN,report.status("scene",WideCompactDiagnosticFlow.Assessment.COMPARISON));
    }

    @Test void arbitraryAssertionErrorIsNotAContinuationFailure() {
        var order = new ArrayList<String>(); var report = new WideCompactDiagnosticFlow.Report();
        assertThrows(AssertionError.class, () -> WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.REFERENCE_ANALYTIC,"analytic",true,true,false,()->{order.add("reference-quality"); throw new AssertionError("not typed");}));
        assertEquals(List.of("reference-quality"), order);
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

    @Test void mixedQualityThenFatalWritesOriginalFailureAndActualExitDecision() throws Exception {
        var report=new WideCompactDiagnosticFlow.Report();var root=java.nio.file.Files.createTempDirectory("wide-report");
        var original=new WideCompactQualityMain.QualityFailure("original analytic failure");
        assertFalse(WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.REFERENCE_ANALYTIC,"reference-analytic",true,true,false,()->{throw original;}));
        var fatal=assertThrows(IllegalStateException.class,()->WideCompactDiagnosticFlow.runAssessment(report,"scene",WideCompactDiagnosticFlow.Assessment.CANDIDATE,"candidate-execution",true,false,false,()->{throw new IllegalStateException("later authority failure");}));
        assertEquals("later authority failure",fatal.getMessage());
        report.markUnavailable("scene",WideCompactDiagnosticFlow.Assessment.COMPARISON);
        report.write(root,true);
        var summary=java.nio.file.Files.readString(root.resolve("summary.json"));var first=java.nio.file.Files.readString(root.resolve("first-failure.txt"));
        assertTrue(WideCompactDiagnosticFlow.exitFailed(report));
        assertTrue(summary.contains("\"diagnosticOnly\":true")&&summary.contains("\"releaseQualified\":false")&&summary.contains("\"status\":\"FAILED\""));
        assertTrue(summary.contains("\"lastStage\":\"candidate-execution\"")&&summary.contains("\"firstFailureStage\":\"scene:reference-analytic\""));
        assertTrue(summary.contains("\"REFERENCE_ANALYTIC\":\"FAILED\"")&&summary.contains("\"CANDIDATE\":\"FAILED\"")&&summary.contains("\"COMPARISON\":\"UNAVAILABLE\""));
        assertTrue(first.contains("original analytic failure"));assertFalse(first.contains("later authority failure"));assertTrue(summary.contains("later authority failure"));
        assertSame(original,report.firstFailure().cause());
    }

    @Test void comparisonFailureDoesNotPreventReversalAssessmentFromUsingValidForwardGeometry() throws Exception {
        var report=new WideCompactDiagnosticFlow.Report();var order=new ArrayList<String>();var forward=List.of(1,2,3);
        WideCompactDiagnosticFlow.runAssessment(report,"forward",WideCompactDiagnosticFlow.Assessment.CANDIDATE_SAFETY,"candidate-safety",true,false,false,()->{});
        assertFalse(WideCompactDiagnosticFlow.runAssessment(report,"forward",WideCompactDiagnosticFlow.Assessment.COMPARISON,"forward-comparison",true,true,false,()->{order.add("compare-forward");throw new WideCompactQualityMain.QualityFailure("forward differs");}));
        WideCompactDiagnosticFlow.runAssessment(report,"reverse",WideCompactDiagnosticFlow.Assessment.REVERSAL,"reversal",true,true,false,()->{order.add("compare-reversal:"+forward.size());});
        assertEquals(List.of("compare-forward","compare-reversal:3"),order);
        assertEquals(WideCompactDiagnosticFlow.Status.FAILED,report.status("forward",WideCompactDiagnosticFlow.Assessment.COMPARISON));
        assertEquals(WideCompactDiagnosticFlow.Status.PASS,report.status("reverse",WideCompactDiagnosticFlow.Assessment.REVERSAL));
    }
}

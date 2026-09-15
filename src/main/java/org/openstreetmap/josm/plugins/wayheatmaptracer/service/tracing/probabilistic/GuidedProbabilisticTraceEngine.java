package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.BudgetReportingTraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceEngineRun;

/** Engine B capability for exact work accounting and one qualified same-image structural prior. */
public interface GuidedProbabilisticTraceEngine extends BudgetReportingTraceEngine {
    /** Runs exact B inference with the supplied structural guide and returns actual work usage. */
    TraceEngineRun traceGuidedWithUsage(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network, ProbabilisticStructuralGuide guide, CancellationProbe cancellation);

    /** Runs guided B where only the typed inference result is required. */
    default TraceHypothesisSet traceGuided(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, ProbabilisticStructuralGuide guide,
            CancellationProbe cancellation) {
        return traceGuidedWithUsage(request, evidence, network, guide, cancellation).result();
    }
}

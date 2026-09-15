package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;

/** Trace engine that exposes actual work in the units of {@code TraceBudgets}. */
public interface BudgetReportingTraceEngine extends TraceEngine {
    @Override
    default TraceHypothesisSet trace(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, CancellationProbe cancellation) {
        return traceWithUsage(request, evidence, network, cancellation).result();
    }

    /** Runs tracing and returns its typed result with actual resource usage. */
    TraceEngineRun traceWithUsage(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network, CancellationProbe cancellation);
}

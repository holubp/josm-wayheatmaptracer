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

    /**
     * Runs tracing inside a caller-owned attempt-memory scope.
     *
     * <p>Engines are wired to this seam explicitly in their owning implementation tasks. The
     * default fails closed so an unaccounted engine cannot be mistaken for an accounted one.
     * Implementations may throw {@link TraceMemoryLimitException} only when even a fully charged
     * native resource-limit result cannot fit after all candidate work has been released.</p>
     */
    default Accounted<TraceEngineRun> traceWithUsage(TraceRequest request,
            EvidenceSnapshot evidence, NetworkSnapshot network, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner attemptOwner) {
        throw new UnsupportedOperationException(
                "attempt-memory ownership is not implemented by " + getClass().getName());
    }
}

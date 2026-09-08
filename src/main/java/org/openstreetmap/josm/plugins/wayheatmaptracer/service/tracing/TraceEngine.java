package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;

/** Pure tracing boundary operating only on immutable slide-time snapshots. */
public interface TraceEngine {
    /**
     * Infers candidate geometry without accessing live JOSM state or network resources.
     *
     * @param request frozen effective tracing request
     * @param evidence immutable scalar image evidence
     * @param network detached OSM network closure
     * @param cancellation cooperative attempt-owned cancellation probe
     * @return ordered trace hypotheses and an explicit completion status
     */
    TraceHypothesisSet trace(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network, CancellationProbe cancellation);

    /** Runs with a never-cancelled probe for deterministic tests and offline replay. */
    default TraceHypothesisSet trace(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network) {
        return trace(request, evidence, network, CancellationProbe.NONE);
    }
}

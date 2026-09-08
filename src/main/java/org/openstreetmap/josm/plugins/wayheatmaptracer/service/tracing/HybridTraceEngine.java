package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

/**
 * Runs corridor-aware proposals and probabilistic inference independently on one frozen snapshot.
 *
 * <p>The A result is structural proposal evidence, not a second observation of the imagery. The
 * unguided B result therefore always remains present when it succeeds, and posterior mass is never
 * added across engines. Common final assessment and ranking decide between the retained routes.</p>
 */
public final class HybridTraceEngine implements TraceEngine {
    private final TraceEngine corridorProposalEngine;
    private final TraceEngine probabilisticEngine;

    /** Creates a hybrid from explicit A and B implementations. */
    public HybridTraceEngine(TraceEngine corridorProposalEngine, TraceEngine probabilisticEngine) {
        if (corridorProposalEngine == null || probabilisticEngine == null) {
            throw new IllegalArgumentException("Hybrid engines are required");
        }
        this.corridorProposalEngine = corridorProposalEngine;
        this.probabilisticEngine = probabilisticEngine;
    }

    @Override
    public TraceHypothesisSet trace(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, CancellationProbe cancellation) {
        validate(request, evidence, network, cancellation);
        try {
            cancellation.checkpoint();
            TraceHypothesisSet corridor = corridorProposalEngine.trace(
                    withEngine(request, TrackerMode.CORRIDOR_AWARE), evidence, network, cancellation);
            cancellation.checkpoint();
            TraceHypothesisSet probabilistic = probabilisticEngine.trace(
                    withEngine(request, TrackerMode.PROBABILISTIC), evidence, network, cancellation);
            cancellation.checkpoint();
            return combine(corridor, probabilistic);
        } catch (CancellationException exception) {
            return new TraceHypothesisSet(TrackerMode.HYBRID, List.of(),
                    TraceHypothesisSet.Status.CANCELLED, false, 0, 0, "cancelled");
        }
    }

    private static TraceHypothesisSet combine(TraceHypothesisSet corridor,
            TraceHypothesisSet probabilistic) {
        List<TraceHypothesis> routes = new ArrayList<>();
        append(routes, "a", corridor.hypotheses());
        append(routes, "b", probabilistic.hypotheses());
        boolean truncated = corridor.alternativesTruncated() || probabilistic.alternativesTruncated();
        long states = saturatedAdd(corridor.evaluatedStates(), probabilistic.evaluatedStates());
        long transitions = saturatedAdd(corridor.evaluatedTransitions(), probabilistic.evaluatedTransitions());
        TraceHypothesisSet.Status status;
        if (routes.isEmpty()) {
            if (corridor.status() == TraceHypothesisSet.Status.RESOURCE_LIMIT
                    || probabilistic.status() == TraceHypothesisSet.Status.RESOURCE_LIMIT) {
                status = TraceHypothesisSet.Status.RESOURCE_LIMIT;
                truncated = true;
            } else if (corridor.status() == TraceHypothesisSet.Status.CANCELLED
                    || probabilistic.status() == TraceHypothesisSet.Status.CANCELLED) {
                status = TraceHypothesisSet.Status.CANCELLED;
            } else {
                status = TraceHypothesisSet.Status.NO_ROUTE;
            }
        } else {
            boolean ambiguous = routes.size() > 1
                    || corridor.status() == TraceHypothesisSet.Status.AMBIGUOUS
                    || probabilistic.status() == TraceHypothesisSet.Status.AMBIGUOUS;
            status = ambiguous ? TraceHypothesisSet.Status.AMBIGUOUS
                    : TraceHypothesisSet.Status.COMPLETE;
            states = Math.max(1, states);
        }
        return new TraceHypothesisSet(TrackerMode.HYBRID, routes, status, truncated, states,
                transitions, "independent A proposals and unguided B; common final ranking required");
    }

    private static void append(List<TraceHypothesis> target, String family,
            List<TraceHypothesis> source) {
        for (TraceHypothesis hypothesis : source) {
            Map<String, Double> diagnostics = new LinkedHashMap<>(hypothesis.diagnostics());
            diagnostics.put("structuralPriorOnly", "a".equals(family) ? 1.0 : 0.0);
            diagnostics.put("independentImageObservationCount", 1.0);
            diagnostics.put("sourcePosteriorProbability", hypothesis.posteriorProbability());
            target.add(new TraceHypothesis("hybrid-" + family + "-" + hypothesis.id(),
                    family + ":" + hypothesis.branchSignature(), hypothesis.points(),
                    hypothesis.support(), hypothesis.objective(),
                    0.0, diagnostics));
        }
    }

    private static TraceRequest withEngine(TraceRequest request, TrackerMode engine) {
        return new TraceRequest(request.selectedWayKey(), request.selectedRange(), engine,
                request.geometryMode(), request.permissions(), request.budgets(),
                request.evidenceSnapshotId(), request.evidenceContentHash(),
                request.networkSnapshotId(), request.networkContentHash(), request.settingsHash(),
                request.parameterHash(), request.samplerId(), request.configuredSampleStepMeters(),
                request.profileChainage(), request.evidenceResolution());
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static void validate(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, CancellationProbe cancellation) {
        if (request == null || evidence == null || network == null || cancellation == null
                || request.engine() != TrackerMode.HYBRID
                || !request.evidenceSnapshotId().equals(evidence.snapshotId())
                || !request.evidenceContentHash().equals(evidence.canonicalHash())
                || !request.networkSnapshotId().equals(network.snapshotId())
                || !request.networkContentHash().equals(network.canonicalHash())) {
            throw new IllegalArgumentException("Hybrid request does not match immutable snapshots");
        }
    }
}

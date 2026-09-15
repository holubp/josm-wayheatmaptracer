package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.GuidedProbabilisticTraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticStructuralGuide;

/**
 * Runs exact unguided B first, then admits A and one guided B from the remaining attempt budget.
 *
 * <p>The A result is structural proposal evidence, not a second observation of the imagery. The
 * unguided B request is identical to standalone B and always has first claim on attempt resources.
 * Posterior mass is never added across engines. Common final assessment and ranking decide between
 * the retained routes.</p>
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
        long completedStates = 0;
        long completedTransitions = 0;
        try {
            cancellation.checkpoint();
            TraceRequest probabilisticRequest = withEngineAndBudgets(
                request, TrackerMode.PROBABILISTIC, request.budgets());
            TraceEngineRun probabilisticRun = runWithUsage(probabilisticEngine,
                probabilisticRequest, evidence, network, cancellation);
            TraceHypothesisSet probabilistic = probabilisticRun.result();
            completedStates = probabilistic.evaluatedStates();
            completedTransitions = probabilistic.evaluatedTransitions();
            if (probabilistic.status() == TraceHypothesisSet.Status.CANCELLED) {
                return cancelled(completedStates, completedTransitions);
            }
            cancellation.checkpoint();

            RemainingBudget remaining = RemainingBudget.from(request.budgets())
                .consume(probabilisticRun.usage());
            if (!remaining.canRunStage()) {
                return combine(null, probabilistic, null, request.budgets(), true,
                    "A omitted: " + remaining.exhaustionReason());
            }

            TraceBudgets corridorBudgets = remaining.reserveGuidedSlice();
            TraceRequest corridorRequest = withEngineAndBudgets(
                request, TrackerMode.CORRIDOR_AWARE, corridorBudgets);
            TraceEngineRun corridorRun = runWithUsage(corridorProposalEngine,
                corridorRequest, evidence, network, cancellation);
            TraceHypothesisSet corridor = corridorRun.result();
            completedStates = saturatedAdd(completedStates, corridor.evaluatedStates());
            completedTransitions = saturatedAdd(completedTransitions, corridor.evaluatedTransitions());
            if (corridor.status() == TraceHypothesisSet.Status.CANCELLED) {
                return cancelled(completedStates, completedTransitions);
            }
            cancellation.checkpoint();
            remaining = remaining.consume(corridorRun.usage());

            TraceHypothesisSet guided = null;
            boolean resourceOmission = remaining.overrun();
            String limitation = remaining.overrun()
                ? "guided B omitted: " + remaining.exhaustionReason() : null;
            var guide = ProbabilisticStructuralGuide.qualified(request, corridor, network);
            if (guide.isPresent()) {
                if (!(probabilisticEngine instanceof GuidedProbabilisticTraceEngine capable)) {
                    resourceOmission = true;
                    limitation = "guided B omitted: probabilistic work accounting is unavailable";
                } else if (!remaining.canRunStage()) {
                    resourceOmission = true;
                    limitation = "guided B omitted: " + remaining.exhaustionReason();
                } else {
                    TraceRequest guidedRequest = withEngineAndBudgets(
                        request, TrackerMode.PROBABILISTIC, remaining.all());
                    TraceEngineRun guidedRun = capable.traceGuidedWithUsage(guidedRequest,
                        evidence, network, guide.orElseThrow(), cancellation);
                    guided = guidedRun.result();
                    completedStates = saturatedAdd(completedStates, guided.evaluatedStates());
                    completedTransitions = saturatedAdd(completedTransitions, guided.evaluatedTransitions());
                    if (guided.status() == TraceHypothesisSet.Status.CANCELLED) {
                        return cancelled(completedStates, completedTransitions);
                    }
                    cancellation.checkpoint();
                    RemainingBudget afterGuided = remaining.consume(guidedRun.usage());
                    if (afterGuided.overrun()) {
                        resourceOmission = true;
                        limitation = "guided B exceeded " + afterGuided.exhaustionReason();
                    }
                }
            }
            return combine(corridor, probabilistic, guided, request.budgets(),
                resourceOmission, limitation);
        } catch (CancellationException exception) {
            return cancelled(completedStates, completedTransitions);
        }
    }

    private static TraceEngineRun runWithUsage(TraceEngine engine, TraceRequest request,
            EvidenceSnapshot evidence, NetworkSnapshot network, CancellationProbe cancellation) {
        if (engine instanceof BudgetReportingTraceEngine reporting) {
            return reporting.traceWithUsage(request, evidence, network, cancellation);
        }
        TraceHypothesisSet result = engine.trace(request, evidence, network, cancellation);
        TraceWorkUsage conservative = result.status() == TraceHypothesisSet.Status.CANCELLED
            ? TraceWorkUsage.none()
            : new TraceWorkUsage(request.budgets().maximumPairVisits(),
                request.budgets().maximumTransitions(),
                request.budgets().maximumRawAlternatives(), result.hypotheses().size());
        return new TraceEngineRun(result, conservative);
    }

    private static TraceHypothesisSet combine(TraceHypothesisSet corridor,
            TraceHypothesisSet probabilistic, TraceHypothesisSet guided, TraceBudgets budgets,
            boolean resourceOmission, String limitation) {
        List<TraceHypothesis> routes = new ArrayList<>();
        boolean outputOmitted = append(routes, "b", probabilistic.hypotheses(),
            budgets.maximumDistinctAlternatives());
        if (corridor != null) {
            outputOmitted |= append(routes, "a", corridor.hypotheses(),
                budgets.maximumDistinctAlternatives());
        }
        if (guided != null) {
            outputOmitted |= append(routes, "guided-b", guided.hypotheses(),
                budgets.maximumDistinctAlternatives());
        }
        boolean truncated = resourceOmission || outputOmitted || probabilistic.alternativesTruncated()
            || corridor != null && corridor.alternativesTruncated()
            || guided != null && guided.alternativesTruncated();
        long states = probabilistic.evaluatedStates();
        long transitions = probabilistic.evaluatedTransitions();
        if (corridor != null) {
            states = saturatedAdd(states, corridor.evaluatedStates());
            transitions = saturatedAdd(transitions, corridor.evaluatedTransitions());
        }
        if (guided != null) {
            states = saturatedAdd(states, guided.evaluatedStates());
            transitions = saturatedAdd(transitions, guided.evaluatedTransitions());
        }
        boolean resourceLimited = resourceOmission || outputOmitted
            || probabilistic.status() == TraceHypothesisSet.Status.RESOURCE_LIMIT
            || corridor != null && corridor.status() == TraceHypothesisSet.Status.RESOURCE_LIMIT
            || guided != null && guided.status() == TraceHypothesisSet.Status.RESOURCE_LIMIT;
        TraceHypothesisSet.Status status;
        if (resourceLimited) {
            status = TraceHypothesisSet.Status.RESOURCE_LIMIT;
            truncated = true;
        } else if (routes.isEmpty()) {
            status = TraceHypothesisSet.Status.NO_ROUTE;
        } else {
            boolean ambiguous = routes.size() > 1
                || probabilistic.status() == TraceHypothesisSet.Status.AMBIGUOUS
                || corridor != null && corridor.status() == TraceHypothesisSet.Status.AMBIGUOUS
                || guided != null && guided.status() == TraceHypothesisSet.Status.AMBIGUOUS;
            status = ambiguous ? TraceHypothesisSet.Status.AMBIGUOUS
                : TraceHypothesisSet.Status.COMPLETE;
        }
        String explanation = limitation != null ? limitation
            : guided != null ? "unguided B, independent A, and capped guided B; common final ranking required"
            : corridor != null ? "unguided B and independent A; common final ranking required"
            : "unguided B completed; no remaining budget for A";
        return new TraceHypothesisSet(TrackerMode.HYBRID, routes, status, truncated,
            states, transitions, explanation);
    }

    private static boolean append(List<TraceHypothesis> target, String family,
            List<TraceHypothesis> source, int maximumDistinct) {
        boolean omitted = false;
        for (TraceHypothesis hypothesis : source) {
            if (target.size() == maximumDistinct) {
                omitted = true;
                continue;
            }
            Map<String, Double> diagnostics = new LinkedHashMap<>(hypothesis.diagnostics());
            diagnostics.put("structuralPriorOnly", "a".equals(family) ? 1.0 : 0.0);
            diagnostics.put("independentImageObservationCount", 1.0);
            diagnostics.put("sourcePosteriorAvailable",
                hypothesis.posteriorProbability().isPresent() ? 1.0 : 0.0);
            hypothesis.posteriorProbability().ifPresent(
                value -> diagnostics.put("sourcePosteriorProbability", value));
            target.add(new TraceHypothesis("hybrid-" + family + "-" + hypothesis.id(),
                family + ":" + hypothesis.branchSignature(), hypothesis.points(),
                hypothesis.support(), hypothesis.objective(), OptionalDouble.empty(), diagnostics));
        }
        return omitted;
    }

    private static TraceHypothesisSet cancelled(long states, long transitions) {
        return new TraceHypothesisSet(TrackerMode.HYBRID, List.of(),
            TraceHypothesisSet.Status.CANCELLED, false, states, transitions, "cancelled");
    }

    private static TraceRequest withEngineAndBudgets(
            TraceRequest request, TrackerMode engine, TraceBudgets budgets) {
        return new TraceRequest(request.selectedWayKey(), request.selectedRange(), engine,
            request.geometryMode(), request.permissions(), budgets,
            request.evidenceSnapshotId(), request.evidenceContentHash(),
            request.networkSnapshotId(), request.networkContentHash(), request.settingsHash(),
            request.parameterHash(), request.samplerId(), request.configuredSampleStepMeters(),
            request.profileChainage(), request.evidenceResolution(), request.corridorInput());
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

    private record RemainingBudget(int maximumStatesPerProfile, long pairVisits,
            long transitions, int rawAlternatives, int distinctAlternatives,
            boolean overrun, String exhaustedDimension) {
        static RemainingBudget from(TraceBudgets budgets) {
            return new RemainingBudget(budgets.maximumStatesPerProfile(), budgets.maximumPairVisits(),
                budgets.maximumTransitions(), budgets.maximumRawAlternatives(),
                budgets.maximumDistinctAlternatives(), false, "none");
        }

        RemainingBudget consume(TraceWorkUsage usage) {
            String exhausted = usage.pairVisits() > pairVisits ? "pair-visit budget"
                : usage.transitions() > transitions ? "transition budget"
                : usage.rawAlternatives() > rawAlternatives ? "raw-alternative budget"
                : usage.distinctAlternatives() > distinctAlternatives
                    ? "distinct-alternative budget" : "none";
            return new RemainingBudget(maximumStatesPerProfile,
                Math.max(0, pairVisits - usage.pairVisits()),
                Math.max(0, transitions - usage.transitions()),
                Math.max(0, rawAlternatives - usage.rawAlternatives()),
                Math.max(0, distinctAlternatives - usage.distinctAlternatives()),
                !"none".equals(exhausted), exhausted);
        }

        boolean canRunStage() {
            return !overrun && pairVisits > 0 && transitions > 0
                && rawAlternatives > 0 && distinctAlternatives > 0;
        }

        String exhaustionReason() {
            if (overrun) {
                return exhaustedDimension;
            }
            if (pairVisits == 0) {
                return "pair-visit budget exhausted";
            }
            if (transitions == 0) {
                return "transition budget exhausted";
            }
            if (rawAlternatives == 0) {
                return "raw-alternative budget exhausted";
            }
            return "distinct-alternative budget exhausted";
        }

        TraceBudgets reserveGuidedSlice() {
            long pair = slice(pairVisits);
            long transition = slice(transitions);
            int raw = slice(rawAlternatives);
            int distinct = Math.min(slice(distinctAlternatives), raw);
            return new TraceBudgets(maximumStatesPerProfile, pair, transition, raw, distinct);
        }

        TraceBudgets all() {
            return new TraceBudgets(maximumStatesPerProfile, pairVisits, transitions,
                rawAlternatives, Math.min(distinctAlternatives, rawAlternatives));
        }

        private static long slice(long available) {
            return available >= 2 ? (available + 1) / 2 : available;
        }

        private static int slice(int available) {
            return available >= 2 ? (available + 1) / 2 : available;
        }
    }
}

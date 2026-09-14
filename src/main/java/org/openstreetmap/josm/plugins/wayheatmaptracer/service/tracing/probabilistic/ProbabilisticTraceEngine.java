package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceEngine;

/** Standalone B engine using full scalar profiles and exact finite-state longitudinal inference. */
public final class ProbabilisticTraceEngine implements TraceEngine {
    private final String fieldName;
    private final EvidenceModelParameters parameters;

    /** Creates B for one named scalar field and the normative v0 parameter set. */
    public ProbabilisticTraceEngine(String fieldName) {
        this(fieldName, EvidenceModelParameters.defaults());
    }

    /** Creates B for one named scalar field and explicit versioned parameters. */
    public ProbabilisticTraceEngine(String fieldName, EvidenceModelParameters parameters) {
        if (fieldName == null || fieldName.isBlank() || parameters == null) {
            throw new IllegalArgumentException("Probabilistic engine configuration is incomplete");
        }
        this.fieldName = fieldName;
        this.parameters = parameters;
    }

    @Override
    public TraceHypothesisSet trace(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network, CancellationProbe cancellation) {
        try {
            return traceInternal(request, evidence, network, cancellation);
        } catch (java.util.concurrent.CancellationException exception) {
            return new TraceHypothesisSet(request.engine(), List.of(), TraceHypothesisSet.Status.CANCELLED,
                false, 0, 0, "cancelled");
        }
    }

    private TraceHypothesisSet traceInternal(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network, CancellationProbe cancellation) {
        cancellation.checkpoint();
        validateSnapshots(request, evidence, network);
        ScalarEvidenceField field = evidence.fields().get(fieldName);
        if (field == null) {
            return noRoute(request, "scalar evidence field is unavailable");
        }
        List<MetricPoint> source = selectedPolyline(request, evidence, network);
        boolean fixedEndpoints = request.permissions().junctionPolicy() == JunctionPolicy.FIXED;
        List<ProbabilisticProfile> profiles = new ProbabilisticProfileFactory().create(source,
            request.profileChainage(), request.permissions().ordinaryRadiusMeters(), fixedEndpoints,
            evidence, field, parameters, cancellation);
        if (parameters.orientationWeight() > 0.0
            && profiles.stream().anyMatch(ProbabilisticProfile::orientationResourceLimited)) {
            return new TraceHypothesisSet(TrackerMode.PROBABILISTIC, List.of(),
                TraceHypothesisSet.Status.RESOURCE_LIMIT, true, 0, 0,
                "orientation descriptor resource limit");
        }
        List<InferenceProfile> evaluated = new ArrayList<>(profiles.size());
        long stateCount = 0;
        ProbabilisticStateBuilder stateBuilder = new ProbabilisticStateBuilder();
        ProbabilisticObservationModel observationModel = new ProbabilisticObservationModel();
        for (ProbabilisticProfile profile : profiles) {
            cancellation.checkpoint();
            StateSpaceBuildResult stateResult = stateBuilder.build(profile,
                request.budgets().maximumStatesPerProfile());
            if (stateResult.status() == StateSpaceBuildResult.Status.STATE_LIMIT) {
                return new TraceHypothesisSet(TrackerMode.PROBABILISTIC, List.of(),
                    TraceHypothesisSet.Status.RESOURCE_LIMIT, true, stateCount, 0,
                    "STATE_LIMIT at profile " + profile.profileIndex() + ": " + stateResult.explanation());
            }
            ProbabilisticStateLattice lattice = stateResult.lattice().orElseThrow();
            stateCount += lattice.cells().size();
            evaluated.add(observationModel.evaluate(profile, lattice, parameters));
        }
        ProbabilisticInferenceResult inference = new ProbabilisticInference().solve(evaluated,
            parameters, request.budgets(), evidence.decisionRegion(), cancellation);
        List<TraceHypothesis> hypotheses = toHypotheses(inference, evaluated, evidence, field);
        TraceHypothesisSet.Status status = switch (inference.status()) {
            case COMPLETE -> TraceHypothesisSet.Status.COMPLETE;
            case AMBIGUOUS, REVIEW_REQUIRED -> TraceHypothesisSet.Status.AMBIGUOUS;
            case ALL_MISSING, NO_ROUTE, NUMERIC_FAILURE -> TraceHypothesisSet.Status.NO_ROUTE;
            case RESOURCE_LIMIT -> TraceHypothesisSet.Status.RESOURCE_LIMIT;
        };
        return new TraceHypothesisSet(TrackerMode.PROBABILISTIC, hypotheses, status,
            inference.alternativeSearchTruncated(), stateCount, inference.evaluatedTransitions(),
            inference.explanation());
    }

    private List<TraceHypothesis> toHypotheses(ProbabilisticInferenceResult inference,
        List<InferenceProfile> profiles, EvidenceSnapshot evidence, ScalarEvidenceField field) {
        List<TraceHypothesis> result = new ArrayList<>();
        int index = 0;
        for (ProbabilisticPath path : inference.distinctPaths()) {
            List<ObservationOwnership> support = supportOnFinalCurve(path.points(), profiles, evidence, field);
            Map<String, Double> diagnostics = new LinkedHashMap<>();
            diagnostics.put("logPartition", inference.logPartition());
            diagnostics.put("logBaseMeasure", path.logBaseMeasure());
            diagnostics.put("measuredMeters", inference.gapSummary().measuredMeters());
            diagnostics.put("absentMeters", inference.gapSummary().absentMeters());
            diagnostics.put("longestInternalGapMeters", inference.gapSummary().longestInternalGapMeters());
            diagnostics.put("temperature", parameters.temperature());
            diagnostics.put("turnWeight", parameters.turnWeight());
            diagnostics.put("orientationWeight", parameters.orientationWeight());
            result.add(new TraceHypothesis("probabilistic-" + index++, path.branchSignature(),
                path.points(), support, path.energy(), path.conditionalPosteriorMass(), diagnostics));
        }
        return List.copyOf(result);
    }

    private static List<ObservationOwnership> supportOnFinalCurve(List<MetricPoint> points,
        List<InferenceProfile> profiles, EvidenceSnapshot evidence, ScalarEvidenceField field) {
        ProbabilisticProfileFactory sampler = new ProbabilisticProfileFactory();
        List<ObservationOwnership> result = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            MetricPoint point = points.get(index);
            if (!evidence.routePositionAuthorized(point)) {
                result.add(ObservationOwnership.NO_RASTER);
                continue;
            }
            java.util.OptionalDouble center = sampler.sample(evidence, field, point);
            if (center.isEmpty()) {
                result.add(ObservationOwnership.NO_RASTER);
            } else if (profiles.get(index).ownership() == ObservationOwnership.CORE_CENSORED
                || profiles.get(index).ownership() == ObservationOwnership.SHOULDER_CENSORED) {
                result.add(profiles.get(index).ownership());
            } else if (center.getAsDouble() > 0.0) {
                result.add(ObservationOwnership.DIRECT_TWO_SIDED);
            } else {
                result.add(ObservationOwnership.NO_SIGNAL_VALID_RASTER);
            }
        }
        return List.copyOf(result);
    }

    private static List<MetricPoint> selectedPolyline(TraceRequest request,
        EvidenceSnapshot evidence, NetworkSnapshot network) {
        DetachedPrimitive primitive = network.primitives().get(request.selectedWayKey());
        if (!(primitive instanceof DetachedWay way)
            || request.selectedRange().lastIndex() >= way.nodeKeys().size()
            || request.selectedRange().size() < 2) {
            throw new IllegalArgumentException("Selected occurrence range is absent from the network snapshot");
        }
        List<MetricPoint> result = new ArrayList<>(request.selectedRange().size());
        for (int index = request.selectedRange().firstIndex(); index <= request.selectedRange().lastIndex(); index++) {
            PrimitiveKey nodeKey = way.nodeKeys().get(index);
            DetachedPrimitive nodePrimitive = network.primitives().get(nodeKey);
            if (!(nodePrimitive instanceof DetachedNode node)) {
                throw new IllegalArgumentException("Selected way node is absent from the network snapshot");
            }
            result.add(evidence.coordinateFrame().toMetric(node.coordinate()));
        }
        return List.copyOf(result);
    }

    private static void validateSnapshots(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network) {
        if (request == null || evidence == null || network == null
            || request.engine() != TrackerMode.PROBABILISTIC
            || !request.evidenceSnapshotId().equals(evidence.snapshotId())
            || !request.evidenceContentHash().equals(evidence.canonicalHash())
            || !request.networkSnapshotId().equals(network.snapshotId())
            || !request.networkContentHash().equals(network.canonicalHash())
            || !request.evidenceResolution().equals(evidence.resolution())
            || network.role() != org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole.CAPTURED_BEFORE) {
            throw new IllegalArgumentException("Probabilistic request does not match immutable snapshots");
        }
    }

    private static TraceHypothesisSet noRoute(TraceRequest request, String explanation) {
        return new TraceHypothesisSet(request.engine(), List.of(), TraceHypothesisSet.Status.NO_ROUTE,
            false, 0, 0, explanation);
    }
}

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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceEngineRun;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage;

/** Standalone B engine using full scalar profiles and exact finite-state longitudinal inference. */
public final class ProbabilisticTraceEngine implements GuidedProbabilisticTraceEngine {
    /** Caller-owned capability boundary for B's experimental weak-signal semantics. */
    public enum ReliabilityPolicy { DIRECT_LONGITUDINAL_V2, BASELINE }

    private final String fieldName;
    private final EvidenceModelParameters parameters;
    private final ReliabilityPolicy reliabilityPolicy;

    /** Creates B for one named scalar field and the normative v0 parameter set. */
    public ProbabilisticTraceEngine(String fieldName) {
        this(fieldName, EvidenceModelParameters.defaults(),
                ReliabilityPolicy.DIRECT_LONGITUDINAL_V2);
    }

    /** Creates B for one named scalar field and explicit versioned parameters. */
    public ProbabilisticTraceEngine(String fieldName, EvidenceModelParameters parameters) {
        this(fieldName, parameters, ReliabilityPolicy.DIRECT_LONGITUDINAL_V2);
    }

    /** Creates a probabilistic engine with an explicit reliability capability. */
    public ProbabilisticTraceEngine(String fieldName, EvidenceModelParameters parameters,
            ReliabilityPolicy reliabilityPolicy) {
        if (fieldName == null || fieldName.isBlank() || parameters == null
                || reliabilityPolicy == null) {
            throw new IllegalArgumentException("Probabilistic engine configuration is incomplete");
        }
        this.fieldName = fieldName;
        this.parameters = parameters;
        this.reliabilityPolicy = reliabilityPolicy;
    }

    @Override
    public TraceHypothesisSet trace(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, CancellationProbe cancellation) {
        return traceWithUsage(request, evidence, network, cancellation).result();
    }

    @Override
    public TraceEngineRun traceWithUsage(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, CancellationProbe cancellation) {
        try {
            return traceInternal(request, evidence, network, cancellation, null);
        } catch (java.util.concurrent.CancellationException exception) {
            return run(new TraceHypothesisSet(request.engine(), List.of(),
                TraceHypothesisSet.Status.CANCELLED, false, 0, 0, "cancelled"), 0, 0);
        } catch (LongitudinalModeReliability.ResourceLimitException exception) {
            return run(new TraceHypothesisSet(request.engine(), List.of(),
                TraceHypothesisSet.Status.RESOURCE_LIMIT, true, 0, 0,
                exception.getMessage()), 0, 0);
        }
    }

    @Override
    public TraceEngineRun traceGuidedWithUsage(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, ProbabilisticStructuralGuide guide,
            CancellationProbe cancellation) {
        if (guide == null) {
            throw new IllegalArgumentException("Structural guide is required");
        }
        try {
            return traceInternal(request, evidence, network, cancellation, guide);
        } catch (java.util.concurrent.CancellationException exception) {
            return run(new TraceHypothesisSet(request.engine(), List.of(),
                TraceHypothesisSet.Status.CANCELLED, false, 0, 0, "cancelled"), 0, 0);
        } catch (LongitudinalModeReliability.ResourceLimitException exception) {
            return run(new TraceHypothesisSet(request.engine(), List.of(),
                TraceHypothesisSet.Status.RESOURCE_LIMIT, true, 0, 0,
                exception.getMessage()), 0, 0);
        }
    }

    private TraceEngineRun traceInternal(TraceRequest request, EvidenceSnapshot evidence,
        NetworkSnapshot network, CancellationProbe cancellation,
        ProbabilisticStructuralGuide guide) {
        long started = System.nanoTime();
        cancellation.checkpoint();
        validateSnapshots(request, evidence, network);
        ScalarEvidenceField field = evidence.fields().get(fieldName);
        if (field == null) {
            return run(noRoute(request, "scalar evidence field is unavailable"), 0, 0);
        }
        List<MetricPoint> source = selectedPolyline(request, evidence, network);
        boolean fixedEndpoints = request.permissions().junctionPolicy() == JunctionPolicy.FIXED;
        long profileSamplingStarted = System.nanoTime();
        List<ProbabilisticProfile> profiles = new ProbabilisticProfileFactory().create(source,
            request.profileChainage(), request.permissions().ordinaryRadiusMeters(), fixedEndpoints,
            evidence, field, parameters, cancellation,
            reliabilityPolicy == ReliabilityPolicy.DIRECT_LONGITUDINAL_V2);
        long profileSamplingNanos = System.nanoTime() - profileSamplingStarted;
        logReliability(profiles);
        if (parameters.orientationWeight() > 0.0
            && profiles.stream().anyMatch(ProbabilisticProfile::orientationResourceLimited)) {
            return run(new TraceHypothesisSet(TrackerMode.PROBABILISTIC, List.of(),
                TraceHypothesisSet.Status.RESOURCE_LIMIT, true, 0, 0,
                "orientation descriptor resource limit"), 0, 0);
        }
        long stateObservationStarted = System.nanoTime();
        List<InferenceProfile> evaluated = new ArrayList<>(profiles.size());
        long stateCount = 0;
        int minimumStates = Integer.MAX_VALUE;
        int maximumStates = 0;
        ProbabilisticStateBuilder stateBuilder = new ProbabilisticStateBuilder();
        ProbabilisticObservationModel observationModel = new ProbabilisticObservationModel();
        for (ProbabilisticProfile profile : profiles) {
            cancellation.checkpoint();
            StateSpaceBuildResult stateResult = stateBuilder.build(profile,
                request.budgets().maximumStatesPerProfile());
            if (stateResult.status() == StateSpaceBuildResult.Status.STATE_LIMIT) {
                return run(new TraceHypothesisSet(TrackerMode.PROBABILISTIC, List.of(),
                    TraceHypothesisSet.Status.RESOURCE_LIMIT, true, stateCount, 0,
                    "STATE_LIMIT at profile " + profile.profileIndex() + ": " + stateResult.explanation()), 0, 0);
            }
            ProbabilisticStateLattice lattice = stateResult.lattice().orElseThrow();
            stateCount += lattice.cells().size();
            minimumStates = Math.min(minimumStates, lattice.cells().size());
            maximumStates = Math.max(maximumStates, lattice.cells().size());
            InferenceProfile evaluatedProfile = observationModel.evaluate(profile, lattice, parameters,
                    reliabilityPolicy == ReliabilityPolicy.DIRECT_LONGITUDINAL_V2);
            evaluated.add(guide == null ? evaluatedProfile
                : guide.apply(evaluatedProfile, profile.sourcePitchMeters()));
        }
        long stateObservationNanos = System.nanoTime() - stateObservationStarted;
        long inferenceStarted = System.nanoTime();
        ProbabilisticInferenceResult inference = new ProbabilisticInference().solve(evaluated,
            parameters, request.budgets(), evidence.decisionRegion(), cancellation);
        long inferenceNanos = System.nanoTime() - inferenceStarted;
        boolean usableRoutes = inference.status() == ProbabilisticInferenceResult.Status.COMPLETE
                || inference.status() == ProbabilisticInferenceResult.Status.AMBIGUOUS
                || inference.status() == ProbabilisticInferenceResult.Status.REVIEW_REQUIRED;
        long materializationStarted = System.nanoTime();
        List<TraceHypothesis> hypotheses = usableRoutes
                ? toHypotheses(inference, profiles, evaluated, evidence, guide) : List.of();
        long materializationNanos = System.nanoTime() - materializationStarted;
        TraceHypothesisSet.Status status = switch (inference.status()) {
            case COMPLETE -> TraceHypothesisSet.Status.COMPLETE;
            case AMBIGUOUS, REVIEW_REQUIRED -> TraceHypothesisSet.Status.AMBIGUOUS;
            case ALL_MISSING, NO_ROUTE, NUMERIC_FAILURE -> TraceHypothesisSet.Status.NO_ROUTE;
            case RESOURCE_LIMIT -> TraceHypothesisSet.Status.RESOURCE_LIMIT;
        };
        TraceHypothesisSet result = new TraceHypothesisSet(TrackerMode.PROBABILISTIC,
            hypotheses, status, inference.alternativeSearchTruncated(), stateCount,
            inference.evaluatedTransitions(), guide == null ? inference.explanation()
                : inference.explanation() + "; capped same-image structural guide applied");
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.profileMs", millis(profileSamplingNanos));
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.stateObservationMs", millis(stateObservationNanos));
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.inferenceMs", millis(inferenceNanos));
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.materializeMs", millis(materializationNanos));
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.totalMs", millis(System.nanoTime() - started));
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.profiles", profiles.size());
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.states", stateCount);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.minStates", minimumStates == Integer.MAX_VALUE ? 0 : minimumStates);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.maxStates", maximumStates);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "trace.hypotheses", hypotheses.size());
        return run(result, inference.evaluatedPairVisits(), inference.rawPaths().size());
    }

    private void logReliability(List<ProbabilisticProfile> profiles) {
        int count = 0;
        double minimumAmplitude = 1.0, totalAmplitude = 0.0, maximumAmplitude = 0.0;
        double minimumCorroboration = 1.0, totalCorroboration = 0.0, maximumCorroboration = 0.0;
        double minimumPosition = 1.0, totalPosition = 0.0, maximumPosition = 0.0;
        for (ProbabilisticProfile profile : profiles) {
            for (ProbabilisticProfile.Mode mode : profile.modes()) {
                count++;
                minimumAmplitude = Math.min(minimumAmplitude, mode.scalarAmplitudeReliability());
                totalAmplitude += mode.scalarAmplitudeReliability();
                maximumAmplitude = Math.max(maximumAmplitude, mode.scalarAmplitudeReliability());
                minimumCorroboration = Math.min(minimumCorroboration, mode.branchCoherence());
                totalCorroboration += mode.branchCoherence();
                maximumCorroboration = Math.max(maximumCorroboration, mode.branchCoherence());
                minimumPosition = Math.min(minimumPosition, mode.positionalReliability());
                totalPosition += mode.positionalReliability();
                maximumPosition = Math.max(maximumPosition, mode.positionalReliability());
            }
        }
        if (count == 0) {
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "reliability.modes", 0);
            return;
        }
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.modes", count);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.amplitudeMin", minimumAmplitude);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.amplitudeMean", totalAmplitude / count);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.amplitudeMax", maximumAmplitude);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.corroborationMin", minimumCorroboration);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.corroborationMean", totalCorroboration / count);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.corroborationMax", maximumCorroboration);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.positionMin", minimumPosition);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.positionMean", totalPosition / count);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
            "reliability.positionMax", maximumPosition);
    }

    private List<TraceHypothesis> toHypotheses(ProbabilisticInferenceResult inference,
        List<ProbabilisticProfile> sampledProfiles, List<InferenceProfile> profiles,
        EvidenceSnapshot evidence, ProbabilisticStructuralGuide guide) {
        List<TraceHypothesis> result = new ArrayList<>();
        List<ProbabilisticPath> distinctPaths = inference.distinctPaths();
        List<String> exportedBranches = exportedBranchSignatures(distinctPaths);
        int index = 0;
        for (ProbabilisticPath path : distinctPaths) {
            List<ObservationOwnership> support = supportOnFinalCurve(path, sampledProfiles, profiles, evidence);
            Map<String, Double> diagnostics = new LinkedHashMap<>();
            diagnostics.put("logPartition", inference.logPartition());
            diagnostics.put("logBaseMeasure", path.logBaseMeasure());
            diagnostics.put("measuredMeters", inference.gapSummary().measuredMeters());
            diagnostics.put("absentMeters", inference.gapSummary().absentMeters());
            diagnostics.put("longestInternalGapMeters", inference.gapSummary().longestInternalGapMeters());
            diagnostics.put("temperature", parameters.temperature());
            diagnostics.put("turnWeight", parameters.turnWeight());
            diagnostics.put("orientationWeight", parameters.orientationWeight());
            if (guide != null) {
                diagnostics.put("structuralGuideApplied", 1.0);
                diagnostics.put("structuralGuideWeight", parameters.guideWeight());
                diagnostics.put("structuralGuideCap", ProbabilisticStructuralGuide.MAXIMUM_COST);
                diagnostics.put("structuralGuideSameImage", 1.0);
                diagnostics.put("structuralGuideSectionCount", (double) guide.sectionCount());
            }
            String exportedBranch = exportedBranches.get(index);
            result.add(new TraceHypothesis("probabilistic-" + index++, exportedBranch,
                path.points(), support, path.energy(), path.conditionalPosteriorMass(), diagnostics));
        }
        return List.copyOf(result);
    }

    private static List<ObservationOwnership> supportOnFinalCurve(ProbabilisticPath path,
        List<ProbabilisticProfile> sampledProfiles, List<InferenceProfile> profiles,
        EvidenceSnapshot evidence) {
        List<MetricPoint> points = path.points();
        int[] states = path.stateIndices();
        List<ObservationOwnership> result = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            MetricPoint point = points.get(index);
            InferenceProfile evaluated = profiles.get(index);
            ObservationOwnership profileOwnership = evaluated.ownership();
            if (!evidence.routePositionAuthorized(point)) {
                result.add(ObservationOwnership.NO_RASTER);
            } else if (profileOwnership == ObservationOwnership.CORE_CENSORED
                    || profileOwnership == ObservationOwnership.SHOULDER_CENSORED
                    || profileOwnership == ObservationOwnership.NO_RASTER
                    || profileOwnership == ObservationOwnership.NO_SIGNAL_VALID_RASTER) {
                result.add(profileOwnership);
            } else if (directlyOwnedByMeasuredMode(sampledProfiles.get(index), evaluated,
                    states[index])) {
                result.add(ObservationOwnership.DIRECT_TWO_SIDED);
            } else {
                result.add(ObservationOwnership.INFERRED_GAP);
            }
        }
        return List.copyOf(result);
    }

    static List<String> exportedBranchSignatures(List<ProbabilisticPath> paths) {
        if (paths == null) {
            throw new IllegalArgumentException("Exported alternatives are missing");
        }
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        List<String> result = new ArrayList<>(paths.size());
        for (ProbabilisticPath path : paths) {
            int occurrence = occurrences.merge(path.branchSignature(), 1, Integer::sum) - 1;
            result.add(occurrence == 0 ? path.branchSignature()
                    : path.branchSignature() + "#alternative-" + occurrence);
        }
        return List.copyOf(result);
    }

    private static boolean directlyOwnedByMeasuredMode(ProbabilisticProfile sampled,
            InferenceProfile evaluated, int stateIndex) {
        LateralStateCell cell = evaluated.cells().get(stateIndex);
        double offset = cell.offsetMeters();
        double tolerance = 1e-9 * Math.max(1.0, sampled.sourcePitchMeters());
        return sampled.modes().stream().anyMatch(mode ->
                mode.id().equals(cell.branchLabel())
                    && offset + tolerance >= mode.coreMinimumMeters()
                    && offset - tolerance <= mode.coreMaximumMeters());
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

    private static long millis(long nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static TraceEngineRun run(TraceHypothesisSet result,
            long pairVisits, int rawAlternatives) {
        return new TraceEngineRun(result, new TraceWorkUsage(pairVisits,
            result.evaluatedTransitions(), rawAlternatives, result.hypotheses().size()));
    }

    private static TraceHypothesisSet noRoute(TraceRequest request, String explanation) {
        return new TraceHypothesisSet(request.engine(), List.of(), TraceHypothesisSet.Status.NO_ROUTE,
            false, 0, 0, explanation);
    }
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorTraceInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorAwareTracker;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorBand;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorCenterlineOptimizer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorPointSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorResourceLimitException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorTrack;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorTrackPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedScalarProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.EndpointConstraint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.JunctionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.MultiScaleProfileSet;

/** Production Engine A adapter over frozen scalar evidence and explicit profile locations. */
public final class CorridorEngineAdapter implements BudgetReportingTraceEngine {
    private final String fieldName;

    /** Creates Engine A for one scalar evidence field. */
    public CorridorEngineAdapter(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("Corridor engine field name is required");
        }
        this.fieldName = fieldName;
    }

    @Override
    public TraceHypothesisSet trace(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, CancellationProbe cancellation) {
        return traceWithUsage(request, evidence, network, cancellation).result();
    }

    @Override
    public TraceEngineRun traceWithUsage(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, CancellationProbe cancellation) {
        if (request != null && request.corridorInput().filter(c -> c.boundedBridgePolicy()).isPresent())
            return new OwnedInvocation(request,evidence,network,cancellation).execute();
        try {
            return traceInternal(request, evidence, network, cancellation);
        } catch (CancellationException exception) {
            return run(new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE, List.of(),
                TraceHypothesisSet.Status.CANCELLED, false, 0, 0, "cancelled"), 0, 0);
        } catch (CorridorResourceLimitException exception) {
            return run(new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE, List.of(),
                TraceHypothesisSet.Status.RESOURCE_LIMIT, true, 0, 0,
                exception.getMessage()), 0, exception.rawAlternatives());
        }
    }

    /** Invocation-local ownership; no mutable run state survives on a reusable adapter. */
    private final class OwnedInvocation {
        private final TraceRequest request;private final EvidenceSnapshot evidence;private final NetworkSnapshot network;
        private final CancellationProbe cancellation;
        private CorridorExecutionReceipt.Phase phase=CorridorExecutionReceipt.Phase.SAMPLING;
        private java.util.Optional<DetachedScalarProfileSampler.RadiusThreeWork> sampling=java.util.Optional.empty();
        private CorridorSourceProtection.Certificate certificate;
        private CorridorAwareTracker tracker;
        private CorridorAwareTracker.TrackingResult tracked;
        OwnedInvocation(TraceRequest request,EvidenceSnapshot evidence,NetworkSnapshot network,CancellationProbe cancellation){
            this.request=request;this.evidence=evidence;this.network=network;this.cancellation=cancellation;
        }
        TraceEngineRun execute(){
            try{
                validate(request,evidence,network,cancellation);
                var input=request.corridorInput().orElseThrow();
                var sampled=new DetachedScalarProfileSampler().sampleRadiusThree(evidence,fieldName,input.profileLocations(),
                    request.permissions().ordinaryRadiusMeters(),input.lateralStepMeters(),cancellation,
                    request.profileChainage().sourceOriginGroundMeters());
                sampling=java.util.Optional.of(sampled.work());
                phase=CorridorExecutionReceipt.Phase.SOURCE_CERTIFICATE;
                certificate=CorridorSourceProtection.inspect(request,evidence,network,fieldName,cancellation);
                double nativePitch=maximumSourcePitch(evidence,input.profileLocations(),request.profileChainage().sourceOriginGroundMeters());
                var optimizer=new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(evidence.transform(),cancellation);
                if(certificate.available()){
                    var context=certificate.bind(cancellation);
                    tracker=CorridorAwareTracker.withTargetOwnedBridgeCorrespondence(optimizer,context,
                        domain->org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorWorkingSchedule.prepare(
                            domain,context,nativePitch,cancellation));
                }else{
                    tracker=CorridorAwareTracker.withTargetOwnedBridgeCorrespondence(optimizer,
                        domain->org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorWorkingSchedule.refusal(certificate.status()));
                }
                phase=CorridorExecutionReceipt.Phase.TRACKING;
                double outputPitch=evidence.resolution().outputRasterPitchMeters();
                double sourcePixel=nativePitch/outputPitch;
                tracked=tracker.trackDetailed(sampled.profiles(),sourcePixel,
                    junctionContext(request,network,sampled.profiles().levelZeroProfiles().size()),fieldName,
                    GeometryCleanupConfig.disabled(),outputPitch,request.budgets());
                phase=CorridorExecutionReceipt.Phase.ADAPTING;cancellation.checkpoint();
                var usable=tracked.candidates().stream().filter(candidate->candidate.evidence().hasSignal()).toList();
                int count=Math.min(usable.size(),request.budgets().maximumDistinctAlternatives());
                var hypotheses=new ArrayList<TraceHypothesis>(count);
                for(int i=0;i<count;i++){
                    cancellation.checkpoint();var candidate=usable.get(i);
                    var prepared=tracker.preparedOptimization(candidate.id()).orElse(null);
                    hypotheses.add(toHypothesis(candidate,tracked,evidence,fieldName,request,usable.size()>1,
                        prepared==null?null:prepared.schedule()));
                }
                cancellation.checkpoint();phase=CorridorExecutionReceipt.Phase.COMPLETED;
                boolean truncated=count<usable.size();
                var status=truncated?TraceHypothesisSet.Status.RESOURCE_LIMIT:hypotheses.isEmpty()?TraceHypothesisSet.Status.NO_ROUTE:
                    hypotheses.size()==1?TraceHypothesisSet.Status.COMPLETE:TraceHypothesisSet.Status.AMBIGUOUS;
                return finish(status,hypotheses,truncated,"production opt-in owned correspondence completed");
            }catch(CancellationException cancelled){
                return finish(TraceHypothesisSet.Status.CANCELLED,List.of(),false,"cancelled opt-in owned correspondence");
            }catch(CorridorResourceLimitException|CorridorCenterlineOptimizer.HardGeometryResourceLimitException limited){
                return finish(TraceHypothesisSet.Status.RESOURCE_LIMIT,List.of(),true,"opt-in owned correspondence resource limit");
            }
        }
        private TraceEngineRun finish(TraceHypothesisSet.Status status,List<TraceHypothesis> hypotheses,boolean truncated,String reason){
            var totals=CorridorExecutionReceipt.totals(tracker);
            var result=new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE,hypotheses,status,truncated,
                totals.unaryEvaluations(),totals.attemptedTransitions(),reason);
            var receipt=CorridorExecutionReceipt.freeze(request,evidence,network,result,phase,sampling,certificate,tracker,tracked);
            int raw=tracker==null?0:tracker.correspondenceDiscoveredTracks().orElse(0);
            return new TraceEngineRun(result,new TraceWorkUsage(totals.pairVisits(),totals.attemptedTransitions(),raw,hypotheses.size()),
                0L,java.util.Optional.of(receipt));
        }
    }

    private TraceEngineRun traceInternal(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, CancellationProbe cancellation) {
        validate(request, evidence, network, cancellation);
        CorridorTraceInput input = request.corridorInput().orElseThrow();
        cancellation.checkpoint();
        MultiScaleProfileSet profiles = new DetachedScalarProfileSampler().sample(
            evidence, fieldName, input.profileLocations(),
            request.permissions().ordinaryRadiusMeters(), input.lateralStepMeters(), cancellation,
            request.profileChainage().sourceOriginGroundMeters());
        double outputPitch = evidence.resolution().outputRasterPitchMeters();
        double sourcePixelSizePx = maximumSourcePitch(evidence, input.profileLocations(),
                request.profileChainage().sourceOriginGroundMeters()) / outputPitch;
        cancellation.checkpoint();
        CorridorAwareTracker.TrackingResult tracked = new CorridorAwareTracker().trackDetailed(
            profiles, sourcePixelSizePx, junctionContext(request, network, profiles.levelZeroProfiles().size()),
            fieldName, GeometryCleanupConfig.disabled(), outputPitch, request.budgets());
        cancellation.checkpoint();

        long evaluatedStates = tracked.optimizations().values().stream()
            .mapToLong(CorridorCenterlineOptimizer.OptimizationResult::profileCostEvaluations).sum();
        long evaluatedTransitions = tracked.optimizations().values().stream()
            .mapToLong(CorridorCenterlineOptimizer.OptimizationResult::transitionEvaluations).sum();
        List<CenterlineCandidate> usable = tracked.candidates().stream()
            .filter(candidate -> candidate.evidence().hasSignal()).toList();
        if (usable.isEmpty()) {
            return run(new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE, List.of(),
                TraceHypothesisSet.Status.NO_ROUTE, false, evaluatedStates, evaluatedTransitions,
                "corridor tracker found no route with measured signal"), evaluatedTransitions,
                tracked.tracks().size());
        }
        int retainedCount = Math.min(usable.size(), request.budgets().maximumDistinctAlternatives());
        boolean truncated = retainedCount < usable.size();
        List<TraceHypothesis> hypotheses = new ArrayList<>(retainedCount);
        for (int index = 0; index < retainedCount; index++) {
            cancellation.checkpoint();
            hypotheses.add(toHypothesis(usable.get(index), tracked, evidence, fieldName, request,
                usable.size() > 1));
        }
        cancellation.checkpoint();
        TraceHypothesisSet.Status status = truncated ? TraceHypothesisSet.Status.RESOURCE_LIMIT
            : hypotheses.size() == 1 ? TraceHypothesisSet.Status.COMPLETE
            : TraceHypothesisSet.Status.AMBIGUOUS;
        return run(new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE, hypotheses, status,
            truncated, evaluatedStates, evaluatedTransitions,
            truncated ? "corridor alternatives exceeded the retained-result budget"
                : "production corridor tracker completed"), evaluatedTransitions,
                tracked.tracks().size());
    }

    private static TraceEngineRun run(TraceHypothesisSet result,
            long pairVisits, int rawAlternatives) {
        return new TraceEngineRun(result, new TraceWorkUsage(pairVisits,
            result.evaluatedTransitions(), rawAlternatives, result.hypotheses().size()));
    }

    private static TraceHypothesis toHypothesis(CenterlineCandidate candidate,
            CorridorAwareTracker.TrackingResult tracked, EvidenceSnapshot evidence, String fieldName,
            TraceRequest request, boolean competingPersistentMode) {
        return toHypothesis(candidate,tracked,evidence,fieldName,request,competingPersistentMode,null);
    }

    private static TraceHypothesis toHypothesis(CenterlineCandidate candidate,
            CorridorAwareTracker.TrackingResult tracked,EvidenceSnapshot evidence,String fieldName,
            TraceRequest request,boolean competingPersistentMode,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorWorkingSchedule schedule) {
        CorridorCenterlineOptimizer.OptimizationResult optimization =
            tracked.optimizations().get(candidate.id());
        CorridorTrack track = tracked.tracks().stream()
            .filter(value -> value.id().equals(candidate.id())).findFirst()
            .orElseThrow(() -> new IllegalStateException("Corridor candidate lost its source track"));
        if (optimization == null || candidate.screenPoints().size() < 2
                || candidate.screenPoints().size() != candidate.offsetsPx().size()) {
            throw new IllegalStateException("Corridor candidate lost its optimizer provenance");
        }
        List<MetricPoint> points = candidate.screenPoints().stream()
            .map(point -> evidence.transform().pixelCenterToMetric(point.x, point.y)).toList();
        List<ObservationOwnership> support = support(
            track, candidate.offsetsPx(), points, evidence, fieldName,
            request.permissions().junctionPolicy() == JunctionPolicy.FIXED,schedule);
        Map<String, Double> diagnostics = new LinkedHashMap<>();
        diagnostics.put("rawCorridorProposal", 1.0);
        diagnostics.put("trackerScore", candidate.score());
        diagnostics.put("sourcePixelSizeRasterPixels", tracked.sourcePixelSizePx());
        diagnostics.put("outputRasterPitchMeters", evidence.resolution().outputRasterPitchMeters());
        diagnostics.put("profileCostEvaluations", (double) optimization.profileCostEvaluations());
        diagnostics.put("transitionEvaluations", (double) optimization.transitionEvaluations());
        diagnostics.put("maximumOffsetStates", (double) optimization.maximumOffsetStates());
        diagnostics.put("retainedPairStateAllocations", (double) optimization.retainedPairStateAllocations());
        diagnostics.put("longitudinalPersistence", candidate.evidence().longitudinalStability());
        diagnostics.put("scaleConflictFraction", candidate.evidence().scaleConflictFraction());
        boolean localDefect = !candidate.safetyWarnings().isEmpty()
            || candidate.evidence().corridorQuality().unsupportedExcursions() > 0
            || candidate.evidence().corridorQuality().unsupportedReversalCount() > 0
            || candidate.evidence().corridorQuality().forwardProgressViolations() > 0;
        diagnostics.put("localDefect", localDefect ? 1.0 : 0.0);
        diagnostics.put("junctionAmbiguity",
            request.permissions().junctionPolicy() == JunctionPolicy.FIXED ? 0.0 : 1.0);
        diagnostics.put("competingPersistentMode", competingPersistentMode ? 1.0 : 0.0);
        if(request.corridorInput().filter(c->c.boundedBridgePolicy()).isPresent()){
            diagnostics.put("sourceSecantRadiusProfiles",3.0);
            diagnostics.put("originalObservationProfiles",(double)candidate.evidence().totalProfiles());
            diagnostics.put("retainedGeometryProfiles",(double)candidate.offsetsPx().size());
            diagnostics.put("preparedCorrespondence",schedule==null?0.0:1.0);
            diagnostics.put("cleanupEvidenceMisalignedCandidateRows",schedule==null?0.0:1.0);
        }
        return new TraceHypothesis("corridor-" + candidate.id(), candidate.id(), points, support,
            optimization.totalCost(), OptionalDouble.empty(), diagnostics);
    }

    private static List<ObservationOwnership> support(CorridorTrack track, List<Double> offsets,
            List<MetricPoint> points, EvidenceSnapshot evidence, String fieldName,
            boolean fixedEndpoints) {
        return support(track,offsets,points,evidence,fieldName,fixedEndpoints,null);
    }

    /** Explicit retained-output ownership view; the original track and gap identities stay unchanged. */
    public static List<ObservationOwnership> support(CorridorTrack track,List<Double> offsets,
            List<MetricPoint> points,EvidenceSnapshot evidence,String fieldName,boolean fixedEndpoints,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorWorkingSchedule schedule){
        if(offsets.size()!=points.size()||schedule!=null&&schedule.workingCount()!=points.size())
            throw new IllegalArgumentException("Output ownership mapping differs");
        List<ObservationOwnership> result = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            if (fixedEndpoints && (index == 0 || index == points.size() - 1)) {
                result.add(ObservationOwnership.FIXED_TOPOLOGY_ONLY);
                continue;
            }
            MetricPoint point = points.get(index);
            if (!evidence.routePositionAuthorized(point)
                    || !sampleSupported(evidence, fieldName, point)) {
                result.add(ObservationOwnership.NO_RASTER);
                continue;
            }
            CorridorTrackPoint source = track.points().get(schedule==null?index:schedule.originalRow(index));
            if (source == null || source.support() == CorridorPointSupport.BOUNDED_INTERPOLATION) {
                result.add(ObservationOwnership.INFERRED_GAP);
                continue;
            }
            CorridorBand band = source.band();
            if (!band.hasMeasuredCenter()) {
                result.add(ObservationOwnership.CORE_CENSORED);
                continue;
            }
            if (offsets.get(index) < band.coreMinPx() - 1.0e-9
                    || offsets.get(index) > band.coreMaxPx() + 1.0e-9) {
                result.add(ObservationOwnership.DIRECT_AMBIGUOUS);
                continue;
            }
            result.add(switch (band.boundaryCompleteness()) {
                case COMPLETE -> ObservationOwnership.DIRECT_TWO_SIDED;
                case SHOULDER_CENSORED -> ObservationOwnership.SHOULDER_CENSORED;
                case CORE_CENSORED, FULLY_CENSORED -> ObservationOwnership.CORE_CENSORED;
            });
        }
        return List.copyOf(result);
    }

    private static boolean sampleSupported(EvidenceSnapshot evidence, String fieldName, MetricPoint point) {
        var raster = evidence.transform().metricToPixelCenter(point);
        return evidence.fields().get(fieldName).sampleBilinear(raster.x(), raster.y()).isPresent();
    }

    private static JunctionContext junctionContext(
            TraceRequest request, NetworkSnapshot network, int profileCount) {
        if (request.permissions().junctionPolicy() != JunctionPolicy.FIXED) {
            return JunctionContext.empty();
        }
        DetachedWay way = (DetachedWay) network.primitives().get(request.selectedWayKey());
        PrimitiveKey first = way.nodeKeys().get(request.selectedRange().firstIndex());
        PrimitiveKey last = way.nodeKeys().get(request.selectedRange().lastIndex());
        return new JunctionContext(List.of(
            new EndpointConstraint(0, first.id(), true, false, 0.0, 0.0, 0),
            new EndpointConstraint(profileCount - 1, last.id(), true, false, 0.0, 0.0, 0)));
    }

    private static double maximumSourcePitch(EvidenceSnapshot evidence,
            List<DetachedProfileSamplingLocation> locations, double sourceOriginGroundMeters) {
        double maximum = 0.0;
        for (DetachedProfileSamplingLocation location : locations) {
            maximum = Math.max(maximum, evidence.resolution().effectivePitchMetersAt(
                sourceOriginGroundMeters + location.cumulativeGroundDistanceMeters()));
        }
        return maximum;
    }

    private void validate(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, CancellationProbe cancellation) {
        if (request == null || evidence == null || network == null || cancellation == null
                || request.engine() != TrackerMode.CORRIDOR_AWARE
                || !request.evidenceSnapshotId().equals(evidence.snapshotId())
                || !request.evidenceContentHash().equals(evidence.canonicalHash())
                || !request.networkSnapshotId().equals(network.snapshotId())
                || !request.networkContentHash().equals(network.canonicalHash())
                || !request.evidenceResolution().equals(evidence.resolution())
                || network.role() != SnapshotRole.CAPTURED_BEFORE
                || !evidence.fields().containsKey(fieldName)
                || request.corridorInput().isEmpty()) {
            throw new IllegalArgumentException("Corridor request does not match immutable snapshots");
        }
        CorridorTraceInput input = request.corridorInput().orElseThrow();
        if (input.profileLocations().size() != request.profileChainage().cumulativeGroundMeters().size()) {
            throw new IllegalArgumentException("Corridor locations do not match measured profile chainage");
        }
        for (int index = 0; index < input.profileLocations().size(); index++) {
            DetachedProfileSamplingLocation location = input.profileLocations().get(index);
            if (!location.coordinateFrame().equals(evidence.coordinateFrame())
                    || !location.rasterTransform().equals(evidence.transform())
                    || Double.doubleToLongBits(location.cumulativeGroundDistanceMeters())
                        != Double.doubleToLongBits(request.profileChainage().cumulativeGroundMeters().get(index))) {
                throw new IllegalArgumentException("Corridor location uses a mismatched frozen frame or chainage");
            }
        }
        validateSelectedEndpoints(request, network, input);
    }

    private static void validateSelectedEndpoints(TraceRequest request, NetworkSnapshot network,
            CorridorTraceInput input) {
        DetachedPrimitive primitive = network.primitives().get(request.selectedWayKey());
        if (!(primitive instanceof DetachedWay way)
                || request.selectedRange().lastIndex() >= way.nodeKeys().size()) {
            throw new IllegalArgumentException("Selected occurrence range is absent from the network snapshot");
        }
        PrimitiveKey firstKey = way.nodeKeys().get(request.selectedRange().firstIndex());
        PrimitiveKey lastKey = way.nodeKeys().get(request.selectedRange().lastIndex());
        DetachedPrimitive first = network.primitives().get(firstKey);
        DetachedPrimitive last = network.primitives().get(lastKey);
        if (!(first instanceof DetachedNode firstNode) || !(last instanceof DetachedNode lastNode)
                || firstNode.coordinate().latitudeDegrees()
                    != input.profileLocations().get(0).geographicPoint().latitudeDegrees()
                || firstNode.coordinate().longitudeDegrees()
                    != input.profileLocations().get(0).geographicPoint().longitudeDegrees()
                || lastNode.coordinate().latitudeDegrees()
                    != input.profileLocations().get(input.profileLocations().size() - 1)
                        .geographicPoint().latitudeDegrees()
                || lastNode.coordinate().longitudeDegrees()
                    != input.profileLocations().get(input.profileLocations().size() - 1)
                        .geographicPoint().longitudeDegrees()) {
            throw new IllegalArgumentException("Corridor profile endpoints do not match selected occurrences");
        }
    }
}

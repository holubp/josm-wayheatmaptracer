package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.CandidateRankingPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.FinalControlBasis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedRefitter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image.DirectionalImageTraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticTraceEngine;

/**
 * Runs one modern detached engine through common image refinement, final-geometry assessment,
 * and physical cross-engine ranking.
 *
 * <p>This class has no JOSM live-object dependency. Capture and edit-plan construction remain
 * separate ownership boundaries, which makes the numerical pipeline replayable and cancellable.</p>
 */
public final class ModernTracePipeline {
    private static final double FRACTION_TOLERANCE = 1.0e-9;
    /** Immutable pipeline options derived from one slide-time configuration. */
    public record Options(String fieldName, GeometryCleanupConfig cleanup,
            String sourceTier, int mappingPreference) {
        /** Validates common final-processing settings. */
        public Options {
            if (fieldName == null || fieldName.isBlank() || cleanup == null
                    || sourceTier == null || sourceTier.isBlank() || mappingPreference < 0) {
                throw new IllegalArgumentException("Modern pipeline options are incomplete");
            }
        }
    }

    /** One final route and the common physical evidence used to rank it. */
    public enum RouteVariant { BASE, LONG_SCALE_SMOOTHED }

    /** Native origins use raw/profile indexes, including existing-occurrence aliases. */
    public record RouteLineage(String nativeParentId, RouteVariant variant,
            Map<Integer,Integer> nativeAliases) {
        public RouteLineage {
            if (nativeParentId == null || nativeParentId.isBlank() || variant == null)
                throw new IllegalArgumentException("Missing native lineage");
            nativeAliases = Map.copyOf(nativeAliases);
            if (variant == RouteVariant.BASE && !nativeAliases.isEmpty())
                throw new IllegalArgumentException("Historical base lineage has no derived origin map");
        }
        public static RouteLineage base(String id) { return new RouteLineage(id,RouteVariant.BASE,Map.of()); }
    }

    public record Route(TraceHypothesis rawHypothesis, TraceHypothesis hypothesis,
            List<FinalRoutePointId> pointIds, Map<FinalRoutePointId, MetricPoint> assignments,
            Map<FinalRoutePointId, ObservationOwnership> sourceOwnership,
            FinalGeometryEvaluator.Result quality,
            ImageSupportedLocalCleanup.Status cleanupStatus, boolean geometryChanged, RouteLineage lineage) {
        /** Preserves the historical BASE constructor and identity contract. */
        public Route(TraceHypothesis rawHypothesis, TraceHypothesis hypothesis,
                List<FinalRoutePointId> pointIds, Map<FinalRoutePointId,MetricPoint> assignments,
                Map<FinalRoutePointId,ObservationOwnership> sourceOwnership, FinalGeometryEvaluator.Result quality,
                ImageSupportedLocalCleanup.Status cleanupStatus, boolean geometryChanged) {
            this(rawHypothesis,hypothesis,pointIds,assignments,sourceOwnership,quality,cleanupStatus,geometryChanged,
                    rawHypothesis==null?null:RouteLineage.base(rawHypothesis.id()));
        }
        /** Validates aligned immutable raw/final geometry, identity and source provenance. */
        public Route {
            if (rawHypothesis == null || hypothesis == null || pointIds == null
                    || assignments == null || sourceOwnership == null || quality == null
                    || cleanupStatus == null || !quality.id().equals(hypothesis.id())
                    || lineage == null || !rawHypothesis.id().equals(lineage.nativeParentId())
                    || (lineage.variant() == RouteVariant.BASE) != rawHypothesis.id().equals(hypothesis.id())) {
                throw new IllegalArgumentException("Modern final route is inconsistent");
            }
            pointIds = List.copyOf(pointIds);
            assignments = Map.copyOf(new LinkedHashMap<>(assignments));
            sourceOwnership = Map.copyOf(new LinkedHashMap<>(sourceOwnership));
            boolean aligned = pointIds.size() == hypothesis.points().size()
                    && new LinkedHashSet<>(pointIds).size() == pointIds.size()
                    && assignments.keySet().equals(new LinkedHashSet<>(pointIds))
                    && sourceOwnership.keySet().equals(new LinkedHashSet<>(pointIds));
            for (int index = 0; aligned && index < pointIds.size(); index++) {
                aligned = hypothesis.points().get(index).equals(assignments.get(pointIds.get(index)));
            }
            if (!aligned) {
                throw new IllegalArgumentException("Modern final route provenance is incomplete or reordered");
            }
            if (lineage.variant() != RouteVariant.BASE) {
                if (!geometryChanged || quality.disposition() != FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED)
                    throw new IllegalArgumentException("Derived geometry must require review");
                int previousRaw=-1, previousSlot=-1;
                for(var entry:new java.util.TreeMap<>(lineage.nativeAliases()).entrySet()) {
                    int raw=entry.getKey(), slot=entry.getValue();
                    if(raw<0 || raw>=rawHypothesis.points().size() || raw<=previousRaw
                            || slot<=previousSlot || slot>=pointIds.size())
                        throw new IllegalArgumentException("Invalid native origin order or bounds");
                    if(pointIds.get(slot) instanceof GeneratedCandidatePoint generated
                            && (generated.originalPointIndex()!=raw || !generated.candidateId().equals(hypothesis.id())))
                        throw new IllegalArgumentException("Generated origin differs from native lineage");
                    previousRaw=raw;previousSlot=slot;
                }
                for(int slot=0;slot<pointIds.size();slot++) if(pointIds.get(slot) instanceof GeneratedCandidatePoint generated
                        && (!generated.candidateId().equals(hypothesis.id())
                            || !Integer.valueOf(slot).equals(lineage.nativeAliases().get(generated.originalPointIndex()))))
                    throw new IllegalArgumentException("Generated origin is missing");
            }
        }

        /** Returns complete candidate-owned assignments for retained existing occurrences. */
        public Map<ExistingWayNodeOccurrence, MetricPoint> existingAssignments() {
            Map<ExistingWayNodeOccurrence, MetricPoint> result = new LinkedHashMap<>();
            for (FinalRoutePointId id : pointIds) {
                if (id instanceof ExistingWayNodeOccurrence existing) {
                    result.put(existing, assignments.get(id));
                }
            }
            return Map.copyOf(result);
        }
    }

    /** Engine result plus final routes ordered by common physical quality. */
    public record Result(TraceHypothesisSet inference, List<Route> routes) {
        /** Copies the ranked route list. */
        public Result {
            if (inference == null || routes == null) {
                throw new IllegalArgumentException("Modern pipeline result is incomplete");
            }
            routes = List.copyOf(routes);
            Set<String> identities=new LinkedHashSet<>();
            Map<String,Integer> children=new LinkedHashMap<>();
            for(var route:routes) {
                if(!identities.add(route.hypothesis().id())) throw new IllegalArgumentException("Duplicate final route identity");
                if(route.lineage().variant()!=RouteVariant.BASE && (!inference.hypotheses().contains(route.rawHypothesis())
                        || children.merge(route.lineage().nativeParentId(),1,Integer::sum)>1))
                    throw new IllegalArgumentException("Derived route has no unique native owner");
            }
            for(var child:routes)if(child.lineage().variant()!=RouteVariant.BASE) {
                var parent=routes.stream().filter(r->r.lineage().variant()==RouteVariant.BASE
                        &&r.hypothesis().id().equals(child.lineage().nativeParentId())).findFirst();
                if(parent.isEmpty() || !parent.orElseThrow().rawHypothesis().equals(child.rawHypothesis())
                        || parent.orElseThrow().hypothesis().points().equals(child.hypothesis().points()))
                    throw new IllegalArgumentException("Derived route omitted or did not change its actual raw parent");
            }
        }
    }

    /**
     * Applies the existing publication cap without discarding native alternatives. If optional
     * children alone overflow, retain every BASE route and record the actual omitted child work.
     * Native-only overflow keeps the existing explicit resource refusal.
     */
    public static List<Result> retainNativeRoutesWithinBudget(List<Result> results,int maximumRoutes) {
        if(maximumRoutes<0)throw new IllegalArgumentException("Negative retained-route cap");
        long total=0,nativeCount=0;
        for(var result:results)for(var route:result.routes()) {
            total++;if(route.lineage().variant()==RouteVariant.BASE)nativeCount++;
        }
        if(nativeCount>maximumRoutes)throw new IntervalTraceBatch.ResourceLimitException("native routes exceed retained-route budget");
        if(total<=maximumRoutes)return List.copyOf(results);
        List<Result> retained=new ArrayList<>();
        for(var result:results) {
            Map<String,Route> children=new LinkedHashMap<>();
            for(var route:result.routes())if(route.lineage().variant()!=RouteVariant.BASE)
                children.put(route.lineage().nativeParentId(),route);
            List<Route> bases=new ArrayList<>();
            for(var route:result.routes())if(route.lineage().variant()==RouteVariant.BASE) {
                var child=children.get(route.hypothesis().id());
                if(child==null){bases.add(route);continue;}
                var diagnostics=new LinkedHashMap<>(route.hypothesis().diagnostics());
                diagnostics.put("smoothingOmittedRetainedRouteCap",1.);
                diagnostics.put("smoothingOmittedChildWorkVisits",child.hypothesis().diagnostics().getOrDefault("longScaleWorkVisits",0.));
                diagnostics.put("smoothingOmittedChildSourceCells",child.hypothesis().diagnostics().getOrDefault("longScaleSourceCells",0.));
                var old=route.hypothesis();var finalHypothesis=new TraceHypothesis(old.id(),old.branchSignature(),old.points(),old.support(),
                        old.objective(),old.posteriorProbability(),diagnostics);
                bases.add(new Route(route.rawHypothesis(),finalHypothesis,route.pointIds(),route.assignments(),route.sourceOwnership(),
                        route.quality(),route.cleanupStatus(),route.geometryChanged(),route.lineage()));
            }
            retained.add(new Result(result.inference(),bases));
        }
        return List.copyOf(retained);
    }

    /** Production result with actual engine work for an outer attempt-wide budget. */
    public record PipelineRun(Result result, TraceWorkUsage usage,
            java.util.Optional<CorridorExecutionReceipt> corridorReceipt) {
        public PipelineRun(Result result,TraceWorkUsage usage){this(result,usage,java.util.Optional.empty());}
        public PipelineRun {
            if (result == null || usage == null || corridorReceipt == null) {
                throw new IllegalArgumentException("Pipeline work result is incomplete");
            }
        }
    }

    private final TraceEngine corridorProposalEngine;

    /**
     * Creates a pipeline with an explicit detached A proposal engine.
     *
     * @param corridorProposalEngine detached A adapter used by A and Hybrid
     */
    public ModernTracePipeline(TraceEngine corridorProposalEngine) {
        if (corridorProposalEngine == null) {
            throw new IllegalArgumentException("A proposal engine is required");
        }
        this.corridorProposalEngine = corridorProposalEngine;
    }

    /**
     * Runs inference and every common post-inference stage on immutable inputs.
     *
     *  request frozen engine request and budgets
     *  evidence frozen scalar raster evidence
     *  network frozen OSM network closure
     *  options common refinement and ranking options
     *  cancellation cooperative cancellation boundary
     *  inference diagnostics and physically ranked final routes
     */
    public Result run(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network,
            Options options, CancellationProbe cancellation) {
        return run(request, evidence, network, options, cancellation, frozenFixedOccurrences(request));
    }

    private static Set<Integer> frozenFixedOccurrences(TraceRequest request) {
        return request == null ? Set.of() : request.corridorInput().filter(c -> c.boundedBridgePolicy())
            .map(c -> c.additionalFixedOccurrences()).orElse(Set.of());
    }

    /** Uses explicit frozen A authority, retaining empty historical convenience authority. */
    public PipelineRun runWithUsage(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, Options options, CancellationProbe cancellation) {
        return runWithUsage(request, evidence, network, options, cancellation, frozenFixedOccurrences(request));
    }

    /** Runs production inference with additional original occurrences held as exact trace anchors. */
    public Result run(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network,
            Options options, CancellationProbe cancellation, Set<Integer> fixedOccurrences) {
        return runWithUsage(request, evidence, network, options, cancellation,
                fixedOccurrences).result();
    }

    /** Runs production inference and reports actual work to the caller's attempt budget. */
    public PipelineRun runWithUsage(TraceRequest request, EvidenceSnapshot evidence,
            NetworkSnapshot network, Options options, CancellationProbe cancellation,
            Set<Integer> fixedOccurrences) {
        if (request == null || evidence == null || network == null || options == null
                || cancellation == null || fixedOccurrences == null
                || fixedOccurrences.stream().anyMatch(index -> index == null
                        || index < request.selectedRange().firstIndex()
                        || index > request.selectedRange().lastIndex())) {
            throw new IllegalArgumentException("Modern pipeline inputs are incomplete");
        }
        Set<Integer> exactFixed = Set.copyOf(fixedOccurrences);
        if (request.corridorInput().filter(c -> c.boundedBridgePolicy()).isPresent()
                && !exactFixed.equals(frozenFixedOccurrences(request)))
            throw new IllegalArgumentException("Pipeline fixed occurrences differ from frozen A authority");
        var disconnections = SharedNodeDisconnectionPlanner.forRequest(request, network);
        if (!exactFixed.isEmpty()) {
            DetachedPrimitive selected = network.primitives().get(request.selectedWayKey());
            if (!(selected instanceof DetachedWay way)
                    || request.selectedRange().lastIndex() >= way.nodeKeys().size()) {
                throw new IllegalArgumentException("Fixed interval occurrences lack a captured selected way");
            }
            for (int index : exactFixed) {
                PrimitiveKey key = way.nodeKeys().get(index);
                if (!(network.primitives().get(key) instanceof DetachedNode)
                        || java.util.Collections.frequency(way.nodeKeys(), key) != 1) {
                    throw new IllegalArgumentException("Fixed interval occurrence identity is ambiguous");
                }
            }
        }
        TraceEngine engine = engine(request.engine(), options.fieldName());
        long inferenceStarted = System.nanoTime();
        TraceEngineRun engineRun;
        if (engine instanceof BudgetReportingTraceEngine reporting) {
            engineRun = reporting.traceWithUsage(request, evidence, network, cancellation);
        } else {
            TraceHypothesisSet result = engine.trace(request, evidence, network, cancellation);
            engineRun = new TraceEngineRun(result, result.status() == TraceHypothesisSet.Status.CANCELLED
                    ? TraceWorkUsage.none()
                    : new TraceWorkUsage(request.budgets().maximumPairVisits(),
                            request.budgets().maximumTransitions(),
                            request.budgets().maximumRawAlternatives(), result.hypotheses().size()));
        }
        TraceHypothesisSet inference = engineRun.result();
        var corridorReceipt=engineRun.corridorReceipt();
        corridorReceipt.ifPresent(receipt->receipt.requireBinding(request,evidence,network,inference));
        long inferenceNanos = System.nanoTime() - inferenceStarted;
        if (inference.hypotheses().isEmpty()) {
            return new PipelineRun(new Result(inference, List.of()), engineRun.usage(),corridorReceipt);
        }
        try {
        ScalarEvidenceField scalar = evidence.fields().get(options.fieldName());
        if (scalar == null) {
            throw new IllegalArgumentException("Configured scalar field is unavailable");
        }
        double pitch = evidence.resolution().effectivePitchMeters();
        ImageCostField image = new ImageCostField(scalar, evidence.transform(),
                evidence.decisionRegion(), pitch);
        List<Route> routes = new ArrayList<>();
        ImageCostField.SegmentSupportBudget sourceBudget=new ImageCostField.SegmentSupportBudget(8_000_000);
        long finalizationNanos = 0L;
        for (TraceHypothesis hypothesis : inference.hypotheses()) {
            cancellation.checkpoint();
            long routeStarted = System.nanoTime();
            if(longScaleEnabled(request,options)) {
                Options rawOptions=new Options(options.fieldName(),GeometryCleanupConfig.disabled(),options.sourceTier(),options.mappingPreference());
                Route raw=finalizeRoute(hypothesis,inference,image,evidence,network,request,rawOptions,pitch,
                        cancellation,exactFixed,disconnections,corridorReceipt.map(r->r.candidates().get(hypothesis.id())));
                routes.add(raw);
                smoothedRoute(raw,inference,image,evidence,network,request,options,pitch,cancellation,exactFixed,
                        disconnections,corridorReceipt.map(r->r.candidates().get(hypothesis.id())),sourceBudget)
                    .ifPresent(routes::add);
            } else routes.add(finalizeRoute(hypothesis, inference, image, evidence, network,
                    request, options, pitch, cancellation, exactFixed, disconnections,
                    corridorReceipt.map(receipt->receipt.candidates().get(hypothesis.id()))));
            finalizationNanos += System.nanoTime() - routeStarted;
        }
        long rankingStarted = System.nanoTime();
        List<Route> ranked = rankRoutes(routes, request.engine(), options);
        long rankingNanos = System.nanoTime() - rankingStarted;
        if (request.engine() == TrackerMode.PROBABILISTIC) {
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "pipeline.inferenceMs", millis(inferenceNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "pipeline.finalizationMs", millis(finalizationNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "pipeline.rankingMs", millis(rankingNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.record(
                "pipeline.routes", ranked.size());
        }
        return new PipelineRun(new Result(inference, ranked), engineRun.usage(),corridorReceipt);
        } catch (java.util.concurrent.CancellationException cancelled) {
            if(corridorReceipt.isPresent())throw new CorridorFinalizationCancelledException(engineRun,cancelled);
            throw cancelled;
        }
    }

    /** Final production reconstruction from one factual native run and its exact parent reference rows. */
    /** Factual ordered native row, not an independent-control DOF or a reconstructed final-slot alias. */
    public record NativePoint(int index,MetricPoint point,double referenceChainage,ObservationOwnership ownership) {
        public NativePoint {if(index<0||point==null||!Double.isFinite(referenceChainage)||ownership==null)throw new IllegalArgumentException("Native row metadata is incomplete");}
    }
    public record NativeTrace(String nativeCandidateId,List<NativePoint> points) {
        public NativeTrace {if(nativeCandidateId==null||nativeCandidateId.isBlank())throw new IllegalArgumentException("Native candidate identity required");points=List.copyOf(points);
            for(int i=0;i<points.size();i++)if(points.get(i).index()!=i)throw new IllegalArgumentException("Native rows must preserve their actual index order");}
    }
    public record FinalProvenance(java.util.Optional<FinalControlBasis> nativeBasis,
            List<FinalRoutePointId> pointIds, List<Double> referenceChainages,
            FinalGeometryEvaluator.Result quality, java.util.Optional<CorridorExecutionReceipt> corridorReceipt,java.util.Optional<NativeTrace> nativeTrace) {
        public FinalProvenance(java.util.Optional<FinalControlBasis> nativeBasis,List<FinalRoutePointId> pointIds,List<Double> referenceChainages,
                FinalGeometryEvaluator.Result quality,java.util.Optional<CorridorExecutionReceipt> corridorReceipt){this(nativeBasis,pointIds,referenceChainages,quality,corridorReceipt,java.util.Optional.empty());}
        public FinalProvenance {
            java.util.Objects.requireNonNull(nativeBasis);java.util.Objects.requireNonNull(quality);
            java.util.Objects.requireNonNull(corridorReceipt);
            java.util.Objects.requireNonNull(nativeTrace);
            pointIds=List.copyOf(pointIds);referenceChainages=List.copyOf(referenceChainages);
            if(pointIds.size()!=referenceChainages.size())throw new IllegalArgumentException("Final provenance slot count differs");
        }
    }
    public record RefinedFinalization(Result result, Map<String,List<Double>> nativePointReferenceChainages,
            Map<String,List<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.ParentArc>> finalCorrespondence,
            Map<String,FinalProvenance> finalProvenance) {
        public RefinedFinalization(Result result,Map<String,List<Double>> nativePointReferenceChainages,
                Map<String,List<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.ParentArc>> finalCorrespondence) {
            this(result,nativePointReferenceChainages,finalCorrespondence,Map.of());
        }
        public RefinedFinalization {
            nativePointReferenceChainages=Map.copyOf(nativePointReferenceChainages);
            finalCorrespondence=Map.copyOf(finalCorrespondence);
            finalProvenance=Map.copyOf(finalProvenance);
        }
    }

    /** Does not run inference or optional smoothing; the caller retains the actual native work/owner. */
    public RefinedFinalization finalizeRefined(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ParentTraceReference parent,
            TraceRequest request,EvidenceSnapshot evidence,NetworkSnapshot network,Options options,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementEngineAdapter.NativeExecution execution,CancellationProbe cancellation) {
        execution.requireBinding(parent,request,evidence,network);
        TraceEngineRun nativeRun=execution.nativeRun();
        parent.requireBinding(request,evidence,network,cancellation);
        if(!options.cleanup().isDisabled() || nativeRun.result().engine()!=request.engine())
            throw new IllegalArgumentException("Refinement requires explicit cleanup Off and the actual requested engine");
        nativeRun.corridorReceipt().ifPresent(r->r.requireBinding(request,evidence,network,nativeRun.result()));
        var field=evidence.fields().get(options.fieldName());
        if(field==null)throw new IllegalArgumentException("Refinement scalar field unavailable");
        var image=new ImageCostField(field,evidence.transform(),evidence.decisionRegion(),evidence.resolution().effectivePitchMeters());
        var routes=new ArrayList<Route>();var rows=new LinkedHashMap<String,List<Double>>();
        var mappings=new LinkedHashMap<String,List<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.ParentArc>>();
        var provenance=new LinkedHashMap<String,FinalProvenance>();
        var way=(DetachedWay)network.primitives().get(request.selectedWayKey());
        for(var raw:nativeRun.result().hypotheses()) {
            cancellation.checkpoint();
            List<Double> nativeChainages=referenceRows(raw,request,nativeRun);
            var nativePoints=new ArrayList<NativePoint>();
            for(int i=0;i<raw.points().size();i++)nativePoints.add(new NativePoint(i,raw.points().get(i),nativeChainages.get(i),raw.support().get(i)));
            var nativeTrace=new NativeTrace(raw.id(),nativePoints);
            rows.put(raw.id(),nativeChainages);
            var entries=new ArrayList<SeedEntry>();
            String scope=raw.id()+"/"+request.parameterHash();
            for(int i=0;i<raw.points().size();i++) {
                boolean boundary=i==0||i==raw.points().size()-1;
                int occurrence=i==0?request.selectedRange().firstIndex():request.selectedRange().lastIndex();
                MetricPoint target=boundary?parent.frozenAnchors().get(occurrence):raw.points().get(i);
                if(target==null)throw new IllegalArgumentException("Refined boundary lost its exact parent anchor");
                entries.add(new SeedEntry(nativeChainages.get(i),1,i,new GeneratedCandidatePoint(scope,i),target,
                    boundary?ObservationOwnership.FIXED_TOPOLOGY_ONLY:raw.support().get(i),false,boundary));
            }
            for(int occurrence=request.selectedRange().firstIndex();occurrence<=request.selectedRange().lastIndex();occurrence++) {
                var key=way.nodeKeys().get(occurrence);
                // Removable Precise occurrences retain lineage, but never gain movement authority by being reused.
                if(request.geometryMode()==AlignmentMode.PRECISE_SHAPE&&network.closure().removableExistingNodeKeys().contains(key))continue;
                double s=parent.occurrenceChainage(occurrence);
                MetricPoint target=parent.frozenAnchors().get(occurrence);
                boolean fixed=target!=null;
                if(!fixed)target=pointAtReference(raw.points(),nativeChainages,s);
                var id=new ExistingWayNodeOccurrence(request.selectedWayKey(),key,occurrence);
                int nativeIndex=exactReferenceRow(nativeChainages,s);
                var support=fixed?ObservationOwnership.FIXED_TOPOLOGY_ONLY:nativeIndex>=0?raw.support().get(nativeIndex):ObservationOwnership.INFERRED_GAP;
                entries.add(new SeedEntry(s,0,occurrence,id,target,support,true,fixed));
            }
            entries.sort(Comparator.comparingDouble(SeedEntry::fraction).thenComparingInt(SeedEntry::priority).thenComparingInt(SeedEntry::ordinal));
            var retainedRows=entries.stream().filter(e->e.id() instanceof ExistingWayNodeOccurrence).map(SeedEntry::fraction).toList();
            var dense=entries.stream().filter(e->!(e.id() instanceof GeneratedCandidatePoint)||exactReferenceRow(retainedRows,e.fraction())<0).toList();
            List<SeedEntry> finalEntries=request.geometryMode()==AlignmentMode.MOVE_EXISTING_NODES
                ?dense.stream().filter(e->e.id() instanceof ExistingWayNodeOccurrence).toList():dense;
            var ids=finalEntries.stream().map(SeedEntry::id).toList();var points=finalEntries.stream().map(SeedEntry::point).toList();
            var assignments=new LinkedHashMap<FinalRoutePointId,MetricPoint>();var ownership=new LinkedHashMap<FinalRoutePointId,ObservationOwnership>();
            var protectedPoints=new LinkedHashMap<Integer,MetricPoint>();
            for(int i=0;i<finalEntries.size();i++){var entry=finalEntries.get(i);assignments.put(entry.id(),entry.point());ownership.put(entry.id(),entry.sourceOwnership());if(entry.protectedCoordinate())protectedPoints.put(i,entry.point());}
            var quality=evaluate(raw.id(),points,ids,protectedPoints,image,evidence.resolution().effectivePitchMeters(),nativeRun.result(),cancellation);
            double loss=request.geometryMode()==AlignmentMode.MOVE_EXISTING_NODES?maximumDistanceToPolyline(raw.points(),points):0;
            boolean outside=false;
            for(int i=1;i<points.size();i++)outside|=!network.closure().editRegion().containsSegment(points.get(i-1),points.get(i));
            if(loss>evidence.resolution().effectivePitchMeters()+1e-9||outside) {
                var findings=new ArrayList<>(quality.findings());
                findings.add(new FinalGeometryEvaluator.Finding(outside?FinalGeometryEvaluator.FindingCode.PROTECTED_ASSIGNMENT_MISMATCH:
                    FinalGeometryEvaluator.FindingCode.PRECISE_SHAPE_REQUIRED,FinalGeometryEvaluator.Severity.HARD_BLOCK,0,points.size()-1,loss));
                quality=new FinalGeometryEvaluator.Result(quality.id(),FinalGeometryEvaluator.Disposition.HARD_BLOCKED,findings,
                    quality.totalLengthMeters(),quality.directlySupportedLengthMeters(),quality.worstUnsupportedSpanMeters(),quality.meanImageCenterCost(),quality.bendPreservingRoughness());
            }
            var diagnostics=new LinkedHashMap<>(raw.diagnostics());diagnostics.put("stepwiseParentReference",1.);diagnostics.put("rootEditEnvelopeViolation",outside?1.:0.);
            var finalHypothesis=new TraceHypothesis(raw.id(),raw.branchSignature(),points,freshSupport(points,protectedPoints.keySet(),image),raw.objective(),raw.posteriorProbability(),diagnostics);
            routes.add(new Route(raw,finalHypothesis,ids,assignments,ownership,quality,ImageSupportedLocalCleanup.Status.UNCHANGED,!points.equals(raw.points())));
            var arcs=new ArrayList<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.ParentArc>();
            var outputRows=finalEntries.stream().map(SeedEntry::fraction).toList();
            // This path never built ControlProvenance. Preserve honest absence, including A;
            // typed slot/reference metadata is factual but is not a native-control basis.
            provenance.put(raw.id(),new FinalProvenance(java.util.Optional.empty(),ids,outputRows,quality,nativeRun.corridorReceipt(),java.util.Optional.of(nativeTrace)));
            for(int occurrence=request.selectedRange().firstIndex();occurrence<=request.selectedRange().lastIndex();occurrence++) {
                double s=parent.occurrenceChainage(occurrence);int edge=referenceEdge(outputRows,s);
                double f=(s-outputRows.get(edge))/(outputRows.get(edge+1)-outputRows.get(edge));
                if(s==outputRows.get(outputRows.size()-1)){edge=outputRows.size()-2;f=1;}
                arcs.add(new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.ParentArc(occurrence,edge,f));
            }
            mappings.put(raw.id(),List.copyOf(arcs));
        }
        return new RefinedFinalization(new Result(nativeRun.result(),rankRoutes(routes,request.engine(),options)),rows,mappings,provenance);
    }

    /** Native row correspondence is factual: prepared A schedules are never replaced by output arc length. */
    public static List<Double> referenceRows(TraceHypothesis raw,TraceRequest request,TraceEngineRun run) {
        var source=request.profileChainage().cumulativeGroundMeters();var rows=new ArrayList<Double>();
        if(request.engine()==TrackerMode.CORRIDOR_AWARE) {
            var receipt=run.corridorReceipt().orElseThrow(()->new IllegalArgumentException("Refined A requires its actual execution receipt"));
            var provenance=receipt.candidates().get(raw.id());
            if(provenance!=null) {
                var mapping=provenance.mapping();
                if(mapping.originalCount()!=source.size()||mapping.workingCount()!=raw.points().size())throw new IllegalArgumentException("Native A reference row counts differ");
                for(int i=0;i<raw.points().size();i++)rows.add(source.get(mapping.originalRow(i)));
            } else {
                var track=receipt.tracks().get(raw.branchSignature());
                boolean unscheduled=track!=null && track.mapping().isEmpty()
                    && Double.valueOf(0).equals(raw.diagnostics().get("preparedCorrespondence"));
                if(!unscheduled||raw.points().size()!=source.size())throw new IllegalArgumentException("Unscheduled native A row ownership unavailable");
                rows.addAll(source);
            }
        } else {
            if(raw.points().size()!=source.size())throw new IllegalArgumentException("Native B reference rows unavailable");
            rows.addAll(source);
        }
        for(int i=1;i<rows.size();i++)if(!(rows.get(i)>rows.get(i-1)))throw new IllegalArgumentException("Native reference rows are ambiguous");
        return List.copyOf(rows);
    }
    private static int exactReferenceRow(List<Double> rows,double s){for(int i=0;i<rows.size();i++)if(Math.abs(rows.get(i)-s)<=1e-8)return i;return -1;}
    private static int referenceEdge(List<Double> rows,double s){int edge=0;while(edge<rows.size()-2&&rows.get(edge+1)<=s)edge++;return edge;}
    private static MetricPoint pointAtReference(List<MetricPoint> points,List<Double> rows,double s){
        int exact=exactReferenceRow(rows,s);if(exact>=0)return points.get(exact);
        int edge=referenceEdge(rows,s);double f=(s-rows.get(edge))/(rows.get(edge+1)-rows.get(edge));
        if(f<0||f>1)throw new IllegalArgumentException("Occurrence outside actual native reference rows");
        return org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ParentTraceReference.interpolate(points.get(edge),points.get(edge+1),f);
    }

    private static boolean longScaleEnabled(TraceRequest request, Options options) {
        return request.engine()==TrackerMode.CORRIDOR_AWARE && request.geometryMode()==AlignmentMode.PRECISE_SHAPE
                && options.cleanup().mode()==GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE;
    }

    private static java.util.Optional<Route> smoothedRoute(Route raw,TraceHypothesisSet inference,ImageCostField image,
            EvidenceSnapshot evidence,NetworkSnapshot network,TraceRequest request,Options options,double pitch,
            CancellationProbe cancellation,Set<Integer> fixedOccurrences,SharedNodeDisconnectionPlanner.Authority disconnections,
            java.util.Optional<CorridorExecutionReceipt.CandidateProvenance> provenance,
            ImageCostField.SegmentSupportBudget sourceBudget) {
        if(provenance.filter(CorridorExecutionReceipt.CandidateProvenance::cleanupUnavailable).isPresent())return java.util.Optional.empty();
        long initialSourceCells=sourceBudget.visitedCells();
        SeedGeometry seed=seedGeometryWithNativeControls(raw.rawHypothesis(),evidence,network,request,fixedOccurrences,disconnections);
        if(seed.controlProvenance().basis().isEmpty())return java.util.Optional.empty();
        ImageSupportedRefitter.GeometryValidator safety=points->{
            cancellation.checkpoint();
            for(int i=1;i<points.size();i++) {
                if(points.get(i-1).equals(seed.points().get(i-1)) && points.get(i).equals(seed.points().get(i)))continue;
                var support=image.certifySegmentSupport(points.get(i-1),points.get(i),sourceBudget,cancellation);
                if(support!=ImageCostField.SegmentSupport.COMPLETE)
                    return ImageSupportedRefitter.Validation.rejected("exact-source-cell-"+support.name());
                if(!network.closure().editRegion().containsSegment(points.get(i-1),points.get(i)))
                    return ImageSupportedRefitter.Validation.rejected("source-edit-envelope");
            }
            var assignments=new LinkedHashMap<FinalRoutePointId,MetricPoint>();
            for(int i=0;i<points.size();i++)assignments.put(seed.pointIds().get(i),points.get(i));
            var quality=evaluate(raw.rawHypothesis().id(),points,seed.pointIds(),protectedAssignments(seed,seed.pointIds(),assignments),
                    image,pitch,inference,cancellation);
            if(quality.disposition()==FinalGeometryEvaluator.Disposition.HARD_BLOCKED)
                return ImageSupportedRefitter.Validation.rejected("final-geometry-hard-block");
            var trial=new Route(raw.rawHypothesis(),new TraceHypothesis(raw.rawHypothesis().id(),raw.rawHypothesis().branchSignature(),
                    points,freshSupport(points,seed.protectedIndices(),image),raw.rawHypothesis().objective(),
                    raw.rawHypothesis().posteriorProbability(),raw.rawHypothesis().diagnostics()),seed.pointIds(),assignments,
                    seed.sourceOwnership(),quality,ImageSupportedLocalCleanup.Status.UNCHANGED,true);
            return ModernSingleWayEditPlanAdapter.validateCleanupTrial(request,evidence,network,options,inference,raw,trial,cancellation);
        };
        var cleanupRequest=new ImageSupportedLocalCleanup.Request(seed.pointIds(),seed.points(),seed.retainedIndices(),
                seed.protectedIndices(),image,evidence.decisionRegion(),ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE,
                ImageSupportedRefitter.Config.defaults(pitch),options.cleanup().simplificationDeviationMeters(),seed.assignments(),List.of());
        var cleanup=new ImageSupportedLocalCleanup().cleanLongScale(new ImageSupportedLocalCleanup.LongScaleRequest(cleanupRequest,
                seed.controlProvenance().basis().orElseThrow(),seed.controlProvenance().nativeAliases(),
                seed.pointIds().stream().map(seed.sourceOwnership()::get).toList(),true,8_000_000,safety,java.util.Optional.empty()),cancellation);
        cancellation.checkpoint();
        if(!cleanup.reviewRequired() || cleanup.cleanup().points().equals(raw.hypothesis().points()))return java.util.Optional.empty();
        validateFinalProvenance(seed,cleanup.cleanup(),network.closure().removableExistingNodeKeys());
        String childId=raw.hypothesis().id()+":long-scale-smoothed";
        while(inference.hypotheses().stream().map(TraceHypothesis::id).toList().contains(childId))childId+=":derived";
        List<FinalRoutePointId> ids=new ArrayList<>();var assignments=new LinkedHashMap<FinalRoutePointId,MetricPoint>();
        var ownership=new LinkedHashMap<FinalRoutePointId,ObservationOwnership>();
        for(int i=0;i<cleanup.cleanup().occurrenceIds().size();i++) {
            FinalRoutePointId old=cleanup.cleanup().occurrenceIds().get(i);
            FinalRoutePointId id=old instanceof GeneratedCandidatePoint generated
                    ? new GeneratedCandidatePoint(childId,generated.originalPointIndex()) : old;
            ids.add(id);assignments.put(id,cleanup.cleanup().points().get(i));ownership.put(id,seed.sourceOwnership().get(old));
        }
        var diagnostics=new LinkedHashMap<>(raw.rawHypothesis().diagnostics());
        diagnostics.put("longScaleSmoothing",1.);diagnostics.put("longScaleWorkVisits",(double)cleanup.proposal().orElseThrow().workVisits());
        diagnostics.put("longScaleSourceCells",(double)(sourceBudget.visitedCells()-initialSourceCells));
        diagnostics.put("longScaleReductionValidationUnavailable",1.);
        var points=cleanup.cleanup().points();var protectedPoints=protectedAssignments(seed,cleanup.cleanup().occurrenceIds(),cleanup.cleanup().assignments());
        var quality=evaluate(childId,points,ids,protectedPoints,image,pitch,inference,cancellation);
        if(quality.disposition()==FinalGeometryEvaluator.Disposition.HARD_BLOCKED)return java.util.Optional.empty();
        quality=new FinalGeometryEvaluator.Result(quality.id(),FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED,quality.findings(),
                quality.totalLengthMeters(),quality.directlySupportedLengthMeters(),quality.worstUnsupportedSpanMeters(),
                quality.meanImageCenterCost(),quality.bendPreservingRoughness());
        var child=new Route(raw.rawHypothesis(),new TraceHypothesis(childId,raw.rawHypothesis().branchSignature(),points,
                freshSupport(points,protectedPoints.keySet(),image),raw.rawHypothesis().objective(),raw.rawHypothesis().posteriorProbability(),diagnostics),
                ids,assignments,ownership,quality,cleanup.cleanup().status(),true,
                new RouteLineage(raw.rawHypothesis().id(),RouteVariant.LONG_SCALE_SMOOTHED,seed.controlProvenance().nativeAliases()));
        var validation=ModernSingleWayEditPlanAdapter.validateCleanupTrial(request,evidence,network,options,inference,raw,child,cancellation);
        return validation.feasible()?java.util.Optional.of(child):java.util.Optional.empty();
    }

    private static FinalGeometryEvaluator.Result evaluate(String id,List<MetricPoint> points,List<FinalRoutePointId> ids,
            Map<Integer,MetricPoint> protectedPoints,ImageCostField image,double pitch,TraceHypothesisSet inference,CancellationProbe cancellation) {
        return new FinalGeometryEvaluator().evaluate(new FinalGeometryEvaluator.Request(id,points,ids,image,pitch,protectedPoints,List.of(),true,
                inference.status()==TraceHypothesisSet.Status.AMBIGUOUS,inference.alternativesTruncated(),
                inference.status()==TraceHypothesisSet.Status.RESOURCE_LIMIT),cancellation);
    }

    private static long millis(long nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private TraceEngine engine(TrackerMode mode, String fieldName) {
        return switch (mode) {
            case CORRIDOR_AWARE -> corridorProposalEngine;
            case PROBABILISTIC -> new ProbabilisticTraceEngine(fieldName, EvidenceModelParameters.defaults(),
                    ProbabilisticTraceEngine.reliabilityPolicyFor(mode));
            case HYBRID -> throw new IllegalArgumentException("Historical A+B Hybrid is no longer supported");
            case DIRECTIONAL_IMAGE -> new DirectionalImageTraceEngine(fieldName);
            case LEGACY_V02 -> throw new IllegalArgumentException(
                    "Legacy tracing does not use the modern detached pipeline");
            case LEGACY_THEN_B -> throw new IllegalArgumentException(
                    "Sequential Hybrid requires its original-authority refinement pipeline");
        };
    }

    private static Route finalizeRoute(TraceHypothesis source, TraceHypothesisSet inference,
            ImageCostField image, EvidenceSnapshot evidence, NetworkSnapshot network,
            TraceRequest request, Options options, double pitch, CancellationProbe cancellation,
            Set<Integer> fixedOccurrences, SharedNodeDisconnectionPlanner.Authority disconnections) {
        return finalizeRoute(source,inference,image,evidence,network,request,options,pitch,cancellation,
            fixedOccurrences,disconnections,java.util.Optional.empty());
    }

    private static Route finalizeRoute(TraceHypothesis source,TraceHypothesisSet inference,
            ImageCostField image,EvidenceSnapshot evidence,NetworkSnapshot network,TraceRequest request,
            Options options,double pitch,CancellationProbe cancellation,Set<Integer> fixedOccurrences,
            SharedNodeDisconnectionPlanner.Authority disconnections,
            java.util.Optional<CorridorExecutionReceipt.CandidateProvenance> corridorProvenance){
        SeedGeometry seed = seedGeometry(source, evidence, network, request, fixedOccurrences, disconnections);
        ImageSupportedLocalCleanup.Mode mode = request.geometryMode()
                == AlignmentMode.MOVE_EXISTING_NODES
                || request.engine() == TrackerMode.PROBABILISTIC
                ? ImageSupportedLocalCleanup.Mode.OFF
                : cleanupMode(options.cleanup().mode());
        if(corridorProvenance.filter(CorridorExecutionReceipt.CandidateProvenance::cleanupUnavailable).isPresent())
            mode=ImageSupportedLocalCleanup.Mode.OFF;
        long cleanupStarted = System.nanoTime();
        ImageSupportedLocalCleanup.Result cleanup = new ImageSupportedLocalCleanup().clean(
                new ImageSupportedLocalCleanup.Request(seed.pointIds(), seed.points(),
                    seed.retainedIndices(), seed.protectedIndices(), image,
                    evidence.decisionRegion(), mode, ImageSupportedRefitter.Config.defaults(pitch),
                    options.cleanup().simplificationDeviationMeters(), seed.assignments(), List.of()),
                cancellation);
        long cleanupNanos = System.nanoTime() - cleanupStarted;
        cancellation.checkpoint();
        validateFinalProvenance(seed, cleanup, network.closure().removableExistingNodeKeys());
        List<FinalRoutePointId> finalIds = cleanup.occurrenceIds();
        Map<FinalRoutePointId, MetricPoint> finalAssignments = cleanup.assignments();
        List<MetricPoint> finalPoints = cleanup.points();
        boolean preciseShapeRequired = false;
        if (request.geometryMode() == AlignmentMode.MOVE_EXISTING_NODES) {
            finalIds = cleanup.occurrenceIds().stream()
                    .filter(ExistingWayNodeOccurrence.class::isInstance).toList();
            Map<FinalRoutePointId, MetricPoint> sparseAssignments = new LinkedHashMap<>();
            for (FinalRoutePointId id : finalIds) {
                sparseAssignments.put(id, cleanup.assignments().get(id));
            }
            finalAssignments = Map.copyOf(sparseAssignments);
            finalPoints = finalIds.stream().map(finalAssignments::get).toList();
            preciseShapeRequired = maximumDistanceToPolyline(cleanup.points(), finalPoints)
                    > pitch + 1.0e-9;
        }
        Map<FinalRoutePointId, ObservationOwnership> retainedSource = new LinkedHashMap<>();
        for (FinalRoutePointId id : finalIds) {
            retainedSource.put(id, seed.sourceOwnership().get(id));
        }
        Map<Integer, MetricPoint> protectedAssignments = protectedAssignments(seed,
                finalIds, finalAssignments);
        long supportStarted = System.nanoTime();
        List<ObservationOwnership> support = freshSupport(finalPoints,
                protectedAssignments.keySet(), image);
        long supportNanos = System.nanoTime() - supportStarted;
        boolean changed = !finalPoints.equals(source.points());
        Map<String, Double> diagnostics = new LinkedHashMap<>(source.diagnostics());
        diagnostics.put("commonFinalProcessing", 1.0);
        diagnostics.put("cleanupChanged", changed ? 1.0 : 0.0);
        diagnostics.put("cleanupStatus", (double) cleanup.status().ordinal());
        if (request.engine() == TrackerMode.PROBABILISTIC && !options.cleanup().isDisabled()) {
            diagnostics.put("cleanupSuppressedForDirectReliability", 1.0);
        }
        diagnostics.put("retainedExistingOccurrences",
                (double) cleanup.occurrenceIds().stream()
                    .filter(ExistingWayNodeOccurrence.class::isInstance).count());
        diagnostics.put("protectedExistingOccurrences", (double) protectedAssignments.size());
        TraceHypothesis finalized = new TraceHypothesis(source.id(), source.branchSignature(),
                finalPoints, support, source.objective(), source.posteriorProbability(), diagnostics);
        long finalGeometryStarted = System.nanoTime();
        FinalGeometryEvaluator.Result quality = new FinalGeometryEvaluator().evaluate(
                new FinalGeometryEvaluator.Request(finalized.id(), finalized.points(),
                    finalIds, image, pitch, protectedAssignments, List.of(), changed,
                    inference.status() == TraceHypothesisSet.Status.AMBIGUOUS,
                    inference.alternativesTruncated(),
                    inference.status() == TraceHypothesisSet.Status.RESOURCE_LIMIT), cancellation);
        if (preciseShapeRequired) {
            List<FinalGeometryEvaluator.Finding> findings = new ArrayList<>(quality.findings());
            findings.add(new FinalGeometryEvaluator.Finding(
                    FinalGeometryEvaluator.FindingCode.PRECISE_SHAPE_REQUIRED,
                    FinalGeometryEvaluator.Severity.HARD_BLOCK, 0, finalPoints.size() - 1,
                    maximumDistanceToPolyline(cleanup.points(), finalPoints)));
            quality = new FinalGeometryEvaluator.Result(quality.id(),
                    FinalGeometryEvaluator.Disposition.HARD_BLOCKED, findings,
                    quality.totalLengthMeters(), quality.directlySupportedLengthMeters(),
                    quality.worstUnsupportedSpanMeters(), quality.meanImageCenterCost(),
                    quality.bendPreservingRoughness());
        }
        cancellation.checkpoint();
        long finalGeometryNanos = System.nanoTime() - finalGeometryStarted;
        if (request.engine() == TrackerMode.PROBABILISTIC) {
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "final.cleanupMs", millis(cleanupNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "final.supportMs", millis(supportNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "final.geometryMs", millis(finalGeometryNanos));
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "final.rawPoints", source.points().size());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "final.finalPoints", finalPoints.size());
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.add(
                "final.findings", quality.findings().size());
        }
        return new Route(source, finalized, finalIds, finalAssignments,
                retainedSource, quality, cleanup.status(), changed);
    }

    static SeedGeometry seedGeometry(TraceHypothesis source, EvidenceSnapshot evidence,
            NetworkSnapshot network, TraceRequest request, Set<Integer> fixedOccurrences,
            SharedNodeDisconnectionPlanner.Authority disconnections) {
        return seedGeometry(source, evidence, network, request, fixedOccurrences, disconnections, false);
    }

    /** Explicit enriched seed for the later long-scale route; historical callers incur no basis limits. */
    static SeedGeometry seedGeometryWithNativeControls(TraceHypothesis source, EvidenceSnapshot evidence,
            NetworkSnapshot network, TraceRequest request, Set<Integer> fixedOccurrences,
            SharedNodeDisconnectionPlanner.Authority disconnections) {
        return seedGeometry(source, evidence, network, request, fixedOccurrences, disconnections, true);
    }

    private static SeedGeometry seedGeometry(TraceHypothesis source, EvidenceSnapshot evidence,
            NetworkSnapshot network, TraceRequest request, Set<Integer> fixedOccurrences,
            SharedNodeDisconnectionPlanner.Authority disconnections, boolean nativeControls) {
        DetachedPrimitive selectedPrimitive = network.primitives().get(request.selectedWayKey());
        if (!(selectedPrimitive instanceof DetachedWay selected)
                || request.selectedRange().lastIndex() >= selected.nodeKeys().size()) {
            throw new IllegalArgumentException("Selected occurrence range is absent from captured network");
        }
        validateSelectedOccurrenceIdentity(selected, request);
        List<MetricPoint> selectedPoints = new ArrayList<>(request.selectedRange().size());
        List<ExistingWayNodeOccurrence> existingIds = new ArrayList<>(request.selectedRange().size());
        for (int occurrence = request.selectedRange().firstIndex();
                occurrence <= request.selectedRange().lastIndex(); occurrence++) {
            PrimitiveKey nodeKey = selected.nodeKeys().get(occurrence);
            DetachedPrimitive nodePrimitive = network.primitives().get(nodeKey);
            if (!(nodePrimitive instanceof DetachedNode node)) {
                throw new IllegalArgumentException("Selected occurrence node is absent from captured network");
            }
            selectedPoints.add(evidence.coordinateFrame().toMetric(node.coordinate()));
            existingIds.add(new ExistingWayNodeOccurrence(request.selectedWayKey(), nodeKey, occurrence));
        }
        double[] sourceFractions = normalizedChainage(selectedPoints, "captured selected geometry");
        double[] candidateFractions = normalizedChainage(source.points(), "raw candidate geometry");
        List<SeedEntry> entries = new ArrayList<>(source.points().size() + existingIds.size());
        for (int index = 0; index < source.points().size(); index++) {
            entries.add(new SeedEntry(candidateFractions[index], 1, index,
                    new GeneratedCandidatePoint(source.id(), index), source.points().get(index),
                    source.support().get(index), false, false));
        }
        for (int local = 0; local < existingIds.size(); local++) {
            ExistingWayNodeOccurrence id = existingIds.get(local);
            PrimitiveKey nodeKey = id.nodeKey();
            double fraction = sourceFractions[local];
            int exactCandidate = exactFractionIndex(candidateFractions, fraction);
            boolean ambiguousMapping = exactCandidate == -2
                    || fractionMultiplicity(sourceFractions, fraction) > 1;
            var permission = CorridorSourceProtection.permission(network, nodeKey,
                disconnections.mayPosition(id.originalOccurrenceIndex()),
                fixedOccurrences.contains(id.originalOccurrenceIndex()), ambiguousMapping);
            boolean removableIdentity = permission.removable();
            boolean fixedCoordinate = permission.fixed();
            MetricPoint assignment = fixedCoordinate ? selectedPoints.get(local)
                    : interpolateAt(source.points(), candidateFractions, fraction);
            ObservationOwnership ownership = fixedCoordinate
                    ? ObservationOwnership.FIXED_TOPOLOGY_ONLY
                    : exactCandidate >= 0 ? source.support().get(exactCandidate)
                    : ObservationOwnership.INFERRED_GAP;
            entries.add(new SeedEntry(fraction, 0, local, id, assignment, ownership,
                    !removableIdentity || fixedOccurrences.contains(id.originalOccurrenceIndex()),
                    fixedCoordinate));
        }
        entries.sort(Comparator.comparingDouble(SeedEntry::fraction)
                .thenComparingInt(SeedEntry::priority).thenComparingInt(SeedEntry::ordinal));
        // Bind native identities before the generated entries can be suppressed. A retained
        // occurrence aliases a native control, but its coordinate/ownership remains its own.
        Map<Integer, FinalRoutePointId> nativeIdentities = new LinkedHashMap<>();
        for (int raw = 0; nativeControls && raw < candidateFractions.length; raw++) {
            FinalRoutePointId alias = new GeneratedCandidatePoint(source.id(), raw);
            int existing = lowerBound(sourceFractions, candidateFractions[raw] - FRACTION_TOLERANCE);
            if (existing < sourceFractions.length
                    && sourceFractions[existing] <= candidateFractions[raw] + FRACTION_TOLERANCE)
                alias = existingIds.get(existing);
            nativeIdentities.put(raw, alias);
        }
        List<FinalRoutePointId> pointIds = new ArrayList<>();
        List<Double> finalFractions = new ArrayList<>();
        List<MetricPoint> points = new ArrayList<>();
        Set<Integer> retainedIndices = new LinkedHashSet<>();
        Set<Integer> protectedIndices = new LinkedHashSet<>();
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>();
        Map<FinalRoutePointId, ObservationOwnership> ownership = new LinkedHashMap<>();
        for (SeedEntry entry : entries) {
            if (entry.id() instanceof GeneratedCandidatePoint
                    && containsFraction(sourceFractions, entry.fraction())) {
                continue;
            }
            int outputIndex = pointIds.size();
            if (assignments.put(entry.id(), entry.point()) != null) {
                throw new IllegalArgumentException("Duplicate final route point identity");
            }
            pointIds.add(entry.id());
            if (nativeControls) finalFractions.add(entry.fraction());
            points.add(entry.point());
            ownership.put(entry.id(), entry.sourceOwnership());
            if (entry.retained()) {
                retainedIndices.add(outputIndex);
            }
            if (entry.protectedCoordinate()) {
                protectedIndices.add(outputIndex);
            }
        }
        if (points.size() < 2) {
            throw new IllegalArgumentException("Final route provenance has fewer than two points");
        }
        Map<Integer, Integer> aliases = new LinkedHashMap<>();
        Map<FinalRoutePointId, Integer> finalSlots = new LinkedHashMap<>();
        for (int slot = 0; nativeControls && slot < pointIds.size(); slot++) finalSlots.put(pointIds.get(slot), slot);
        nativeIdentities.forEach((raw, id) -> aliases.put(raw, finalSlots.get(id)));
        ControlProvenance provenance = nativeControls ? controlProvenance(points, finalFractions, aliases,
                source.points().size(), protectedIndices) : new ControlProvenance(java.util.Optional.empty(),
                        Map.of(), ProvenanceFailure.NOT_REQUESTED);
        return new SeedGeometry(List.copyOf(pointIds), List.copyOf(points),
                Set.copyOf(retainedIndices), Set.copyOf(protectedIndices),
                Map.copyOf(assignments), Map.copyOf(ownership), provenance);
    }

    static void validateSelectedOccurrenceIdentity(DetachedWay selected,
            TraceRequest request) {
        Map<PrimitiveKey, Integer> occurrenceCounts = new LinkedHashMap<>();
        for (PrimitiveKey node : selected.nodeKeys()) {
            occurrenceCounts.merge(node, 1, Integer::sum);
        }
        for (int selectedIndex = request.selectedRange().firstIndex();
                selectedIndex <= request.selectedRange().lastIndex(); selectedIndex++) {
            PrimitiveKey node = selected.nodeKeys().get(selectedIndex);
            if (occurrenceCounts.getOrDefault(node, 0) != 1) {
                throw new IllegalArgumentException(
                        "Selected node identity has repeated or ambiguous way occurrences");
            }
        }
    }

    static double[] normalizedChainage(List<MetricPoint> points, String description) {
        double[] chainage = new double[points.size()];
        for (int index = 1; index < points.size(); index++) {
            double segment = points.get(index - 1).distanceTo(points.get(index));
            if (!Double.isFinite(segment)) {
                throw new IllegalArgumentException(description + " has non-finite extent");
            }
            chainage[index] = chainage[index - 1] + segment;
        }
        double total = chainage[chainage.length - 1];
        if (!(total > 0.0) || !Double.isFinite(total)) {
            throw new IllegalArgumentException(description + " has no finite forward extent");
        }
        for (int index = 0; index < chainage.length; index++) {
            chainage[index] /= total;
        }
        return chainage;
    }

    private static MetricPoint interpolateAt(List<MetricPoint> points, double[] fractions,
            double target) {
        int segment = Math.max(0, Math.min(fractions.length - 2,
                upperBound(fractions, target) - 1));
        double width = fractions[segment + 1] - fractions[segment];
        if (!(width > 0.0)) {
            throw new IllegalArgumentException("Candidate assignment interval is ambiguous");
        }
        double local = (target - fractions[segment]) / width;
        MetricPoint first = points.get(segment);
        MetricPoint last = points.get(segment + 1);
        return new MetricPoint(first.xMeters() + local * (last.xMeters() - first.xMeters()),
                first.yMeters() + local * (last.yMeters() - first.yMeters()));
    }

    private static int exactFractionIndex(double[] fractions, double target) {
        int first = lowerBound(fractions, target - FRACTION_TOLERANCE);
        int last = upperBound(fractions, target + FRACTION_TOLERANCE);
        return last == first ? -1 : last == first + 1 ? first : -2;
    }

    static int fractionMultiplicity(double[] fractions, double target) {
        return upperBound(fractions, target + FRACTION_TOLERANCE)
                - lowerBound(fractions, target - FRACTION_TOLERANCE);
    }

    private static boolean containsFraction(double[] fractions, double target) {
        return lowerBound(fractions, target - FRACTION_TOLERANCE)
                < upperBound(fractions, target + FRACTION_TOLERANCE);
    }

    private static int lowerBound(double[] values, double target) {
        int first = 0;
        int last = values.length;
        while (first < last) {
            int middle = (first + last) >>> 1;
            if (values[middle] < target) {
                first = middle + 1;
            } else {
                last = middle;
            }
        }
        return first;
    }

    private static int upperBound(double[] values, double target) {
        int first = 0;
        int last = values.length;
        while (first < last) {
            int middle = (first + last) >>> 1;
            if (values[middle] <= target) {
                first = middle + 1;
            } else {
                last = middle;
            }
        }
        return first;
    }

    private static void validateFinalProvenance(SeedGeometry seed,
            ImageSupportedLocalCleanup.Result cleanup, Set<PrimitiveKey> removable) {
        Set<FinalRoutePointId> finalIds = new LinkedHashSet<>(cleanup.occurrenceIds());
        Map<FinalRoutePointId, Integer> seedIndices = indexByIdentity(seed.pointIds());
        if (finalIds.size() != cleanup.occurrenceIds().size()
                || !seedIndices.keySet().containsAll(cleanup.occurrenceIds())) {
            throw new IllegalStateException("Cleanup returned duplicate or unknown occurrence identity");
        }
        int previous = -1;
        for (FinalRoutePointId id : cleanup.occurrenceIds()) {
            int sourceIndex = seedIndices.get(id);
            if (sourceIndex <= previous) {
                throw new IllegalStateException("Cleanup reordered final occurrence identity");
            }
            previous = sourceIndex;
        }
        for (int index : seed.retainedIndices()) {
            FinalRoutePointId id = seed.pointIds().get(index);
            if (!finalIds.contains(id)) {
                throw new IllegalStateException("Cleanup omitted required existing occurrence identity");
            }
        }
        for (FinalRoutePointId id : seed.pointIds()) {
            if (id instanceof ExistingWayNodeOccurrence existing
                    && !removable.contains(existing.nodeKey()) && !finalIds.contains(id)) {
                throw new IllegalStateException("Final route lacks a required existing-node assignment");
            }
        }
    }

    private static Map<Integer, MetricPoint> protectedAssignments(SeedGeometry seed,
            List<FinalRoutePointId> finalIds,
            Map<FinalRoutePointId, MetricPoint> finalAssignments) {
        Map<Integer, MetricPoint> result = new LinkedHashMap<>();
        Map<FinalRoutePointId, Integer> finalIndices = indexByIdentity(finalIds);
        for (int sourceIndex : seed.protectedIndices()) {
            FinalRoutePointId id = seed.pointIds().get(sourceIndex);
            int finalIndex = finalIndices.getOrDefault(id, -1);
            MetricPoint expected = seed.assignments().get(id);
            if (finalIndex < 0) {
                if (seed.retainedIndices().contains(sourceIndex)) {
                    throw new IllegalStateException("Protected occurrence assignment disappeared");
                }
                continue;
            }
            if (!expected.equals(finalAssignments.get(id))) {
                throw new IllegalStateException("Protected occurrence assignment changed");
            }
            result.put(finalIndex, expected);
        }
        return Map.copyOf(result);
    }

    private static double maximumDistanceToPolyline(List<MetricPoint> points,
            List<MetricPoint> polyline) {
        if (polyline.size() < 2) {
            return Double.POSITIVE_INFINITY;
        }
        double maximum = 0.0;
        for (MetricPoint point : points) {
            double nearest = Double.POSITIVE_INFINITY;
            for (int index = 1; index < polyline.size(); index++) {
                nearest = Math.min(nearest, pointSegmentDistance(point,
                        polyline.get(index - 1), polyline.get(index)));
            }
            maximum = Math.max(maximum, nearest);
        }
        return maximum;
    }

    private static double pointSegmentDistance(MetricPoint point, MetricPoint start,
            MetricPoint end) {
        double dx = end.xMeters() - start.xMeters();
        double dy = end.yMeters() - start.yMeters();
        double lengthSquared = dx * dx + dy * dy;
        if (!(lengthSquared > 0.0)) {
            return point.distanceTo(start);
        }
        double fraction = ((point.xMeters() - start.xMeters()) * dx
                + (point.yMeters() - start.yMeters()) * dy) / lengthSquared;
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        return point.distanceTo(new MetricPoint(start.xMeters() + fraction * dx,
                start.yMeters() + fraction * dy));
    }

    private static Map<FinalRoutePointId, Integer> indexByIdentity(
            List<FinalRoutePointId> identities) {
        Map<FinalRoutePointId, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < identities.size(); index++) {
            if (result.put(identities.get(index), index) != null) {
                throw new IllegalStateException("Duplicate final route point identity");
            }
        }
        return result;
    }

    private static List<ObservationOwnership> freshSupport(List<MetricPoint> points,
            Set<Integer> protectedIndices, ImageCostField image) {
        List<ObservationOwnership> result = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            if (protectedIndices.contains(index)) {
                result.add(ObservationOwnership.FIXED_TOPOLOGY_ONLY);
                continue;
            }
            MetricPoint tangent = tangent(points, index);
            if (tangent == null) {
                result.add(ObservationOwnership.NO_RASTER);
                continue;
            }
            ImageCostField.RouteSample sample = image.sampleRoute(points.get(index), tangent).orElse(null);
            result.add(sample == null ? ObservationOwnership.NO_RASTER : sample.ownership());
        }
        return List.copyOf(result);
    }

    private static MetricPoint tangent(List<MetricPoint> points, int index) {
        MetricPoint start = points.get(Math.max(0, index - 1));
        MetricPoint end = points.get(Math.min(points.size() - 1, index + 1));
        double x = end.xMeters() - start.xMeters();
        double y = end.yMeters() - start.yMeters();
        return StrictMath.hypot(x, y) <= 1.0e-12 ? null : new MetricPoint(x, y);
    }

    private static ImageSupportedLocalCleanup.Mode cleanupMode(GeometryCleanupMode mode) {
        return switch (mode) {
            case NONE -> ImageSupportedLocalCleanup.Mode.OFF;
            case REDUCE_POINTS_ONLY -> ImageSupportedLocalCleanup.Mode.REDUCE_POINTS_ONLY;
            case CONSTRAINED_SMOOTH_AND_REDUCE -> ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE;
        };
    }

    private record SeedEntry(double fraction, int priority, int ordinal,
            FinalRoutePointId id, MetricPoint point, ObservationOwnership sourceOwnership,
            boolean retained, boolean protectedCoordinate) {
    }

    record SeedGeometry(List<FinalRoutePointId> pointIds, List<MetricPoint> points,
            Set<Integer> retainedIndices, Set<Integer> protectedIndices,
            Map<FinalRoutePointId, MetricPoint> assignments,
            Map<FinalRoutePointId, ObservationOwnership> sourceOwnership,
            ControlProvenance controlProvenance) {
    }

    enum ProvenanceFailure { NONE, NOT_REQUESTED, MISSING_NATIVE_ALIAS, AMBIGUOUS_FRACTION, RESOURCE_LIMIT }

    /** Factual native-index aliases, never reconstructed from output coordinates or IDs. */
    record ControlProvenance(java.util.Optional<FinalControlBasis> basis,
            Map<Integer, Integer> nativeAliases, ProvenanceFailure failure) {
        ControlProvenance { nativeAliases = Map.copyOf(nativeAliases); }
    }

    static ControlProvenance controlProvenance(List<MetricPoint> points, List<Double> fractions,
            Map<Integer, Integer> nativeAliases, int nativeCount, Set<Integer> protectedSlots) {
        Map<Integer, Integer> aliases = new LinkedHashMap<>(nativeAliases);
        if (points.size() > FinalControlBasis.MAX_FINAL_POINTS)
            return new ControlProvenance(java.util.Optional.empty(), Map.of(), ProvenanceFailure.RESOURCE_LIMIT);
        int previousSlot = -1;
        for (int raw = 0; raw < nativeCount; raw++) {
            Integer slot = aliases.get(raw);
            if (slot == null || slot < 0 || slot >= points.size())
                return new ControlProvenance(java.util.Optional.empty(), Map.of(),
                        ProvenanceFailure.MISSING_NATIVE_ALIAS);
            if (slot <= previousSlot) return new ControlProvenance(java.util.Optional.empty(), aliases,
                    ProvenanceFailure.AMBIGUOUS_FRACTION);
            previousSlot = slot;
        }
        boolean invalid = fractions.size() != points.size() || aliases.size() != nativeCount
                || new LinkedHashSet<>(aliases.values()).size() != nativeCount;
        for (int i = 0; i < fractions.size(); i++)
            invalid |= !Double.isFinite(fractions.get(i)) || i > 0
                    && fractions.get(i) - fractions.get(i - 1) <= FRACTION_TOLERANCE;
        if (invalid) return new ControlProvenance(java.util.Optional.empty(), aliases,
                ProvenanceFailure.AMBIGUOUS_FRACTION);
        Set<Integer> independent = new java.util.TreeSet<>(aliases.values());
        independent.addAll(protectedSlots);
        independent.add(0); independent.add(points.size() - 1);
        List<Integer> controls = List.copyOf(independent);
        List<FinalControlBasis.Row> rows = new ArrayList<>();
        int bracket = 0;
        for (int slot = 0; slot < points.size(); slot++) {
            if (independent.contains(slot)) {
                rows.add(FinalControlBasis.Row.independent(slot));
                while (bracket + 1 < controls.size() && controls.get(bracket + 1) <= slot) bracket++;
            } else {
                int left = controls.get(bracket), right = controls.get(bracket + 1);
                rows.add(FinalControlBasis.Row.dependent(slot, left, right,
                        (fractions.get(slot) - fractions.get(left))
                                / (fractions.get(right) - fractions.get(left))));
            }
        }
        return new ControlProvenance(java.util.Optional.of(new FinalControlBasis(points, rows,
                protectedSlots)), aliases, ProvenanceFailure.NONE);
    }

    /** Reuses common physical ranking for already assessed production compositions. */
    public static List<Route> rankRoutes(List<Route> routes, TrackerMode engine, Options options) {
        CandidateRankingPolicy ranking = new CandidateRankingPolicy();
        Map<String, Route> byId = new LinkedHashMap<>();
        List<CandidateRankingPolicy.Candidate> candidates = new ArrayList<>();
        List<Route> unavailable = new ArrayList<>();
        for (Route route : routes) {
            FinalGeometryEvaluator.Result quality = route.quality();
            byId.put(route.hypothesis().id(), route);
            if (quality.has(FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY)) {
                unavailable.add(route);
                continue;
            }
            long hard = quality.findings().stream()
                    .filter(finding -> finding.severity() == FinalGeometryEvaluator.Severity.HARD_BLOCK)
                    .count();
            int branch = quality.has(FinalGeometryEvaluator.FindingCode.BRANCH_SWITCH)
                    || quality.has(FinalGeometryEvaluator.FindingCode.AMBIGUOUS_BRANCH) ? 1 : 0;
            int severe = Math.toIntExact(Math.min(Integer.MAX_VALUE,
                    quality.findings().size() - branch));
            CandidateRankingPolicy.PhysicalQuality physical = new CandidateRankingPolicy.PhysicalQuality(
                    quality.directlySupportedLengthMeters() + 1.0e-6 >= quality.totalLengthMeters(),
                    hard > 0, severe, branch, 0, quality.directlySupportedLengthMeters(),
                    quality.totalLengthMeters(), quality.worstUnsupportedSpanMeters(),
                    quality.meanImageCenterCost(), 0.0, quality.bendPreservingRoughness(),
                    options.mappingPreference(), route.hypothesis().diagnostics()
                        .getOrDefault("guideReliability", 0.0));
            candidates.add(new CandidateRankingPolicy.Candidate(route.hypothesis().id(), engine,
                    options.sourceTier(), physical, route.hypothesis().objective(),
                    route.hypothesis().points().size(), route.geometryChanged(),
                    route.hypothesis().branchSignature(), route.geometryChanged()));
        }
        Map<String, Integer> finiteRank = new LinkedHashMap<>();
        int position = 0;
        for (CandidateRankingPolicy.Candidate candidate : ranking.rank(candidates)) {
            finiteRank.put(candidate.id(), position++);
        }
        unavailable.sort(Comparator.comparing((Route route) -> route.hypothesis().branchSignature())
                .thenComparing(route -> route.hypothesis().id()));
        List<Route> ordered = new ArrayList<>(routes);
        ordered.sort(Comparator.comparing((Route route) -> isHardBlocked(route.quality()))
                .thenComparing(route -> !isComplete(route.quality()))
                .thenComparing(route -> route.quality().has(
                        FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY))
                .thenComparingInt(route -> finiteRank.getOrDefault(route.hypothesis().id(),
                        Integer.MAX_VALUE))
                .thenComparing(route -> route.hypothesis().branchSignature())
                .thenComparing(route -> route.hypothesis().id()));
        return List.copyOf(ordered);
    }

    private static boolean isHardBlocked(FinalGeometryEvaluator.Result quality) {
        return quality.findings().stream().anyMatch(
                finding -> finding.severity() == FinalGeometryEvaluator.Severity.HARD_BLOCK);
    }

    private static boolean isComplete(FinalGeometryEvaluator.Result quality) {
        return quality.directlySupportedLengthMeters() + 1.0e-6 >= quality.totalLengthMeters();
    }
}

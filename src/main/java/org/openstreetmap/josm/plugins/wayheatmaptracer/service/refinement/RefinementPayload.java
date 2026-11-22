package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.*;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.TileMosaic;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.TileMosaicSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler.TileRecord;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.RefusalReason;

/** Explicitly shared immutable payload ownership; no process-global snapshot cache. */
public final class RefinementPayload {
    /** Exact native root controls; interval and full-route ownership are mutually exclusive. */
    public record RootReviewState(String sourceOwnerIdentity,OptionalInt fullRouteIndex,Map<Integer,Integer> routeChoices,
            String compositionIdentity,Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer.Assessment> intervalAssessment) {
        public RootReviewState {
            Objects.requireNonNull(sourceOwnerIdentity);Objects.requireNonNull(fullRouteIndex);Objects.requireNonNull(compositionIdentity);Objects.requireNonNull(intervalAssessment);
            routeChoices=Map.copyOf(routeChoices);
            if(sourceOwnerIdentity.isBlank()||compositionIdentity.isBlank()||fullRouteIndex.orElse(0)<0
                    ||fullRouteIndex.isPresent()&&(intervalAssessment.isPresent()||!routeChoices.isEmpty())
                    ||fullRouteIndex.isEmpty()&&intervalAssessment.isEmpty())throw new IllegalArgumentException("Exact native root selection required");
        }
    }
    private final Optional<RootReviewState> rootReviewState;
    public Optional<RootReviewState> rootReviewState(){return rootReviewState;}
    public record SourceNode(long id, double latitude, double longitude) { }
    /** Original full-way source and selection, frozen before any command or mutable-handle change. */
    public record SourceSelection(long wayId,int firstOccurrence,int lastOccurrence,List<SourceNode> fullWayNodes,
            List<Long> selectedNodeIds) {
        public SourceSelection {fullWayNodes=List.copyOf(fullWayNodes);selectedNodeIds=List.copyOf(selectedNodeIds);}
    }
    public record NativeReview(AlignmentEditPlan plan,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Result quality,
            Optional<RefinementOperationReceipt> operation,List<RefinementSourcePlanner.SourceRequest> sources,
            OptionalInt retainedParentSections,Map<RefinementSourcePlanner.SourceRequest,RetainedSmoothingSource> retainedSources,
            List<RefinementPipeline.SourceSpan> sourceSpans,Optional<List<FinalRoutePointId>> finalPointIds,
            Optional<RefinementSmoothingAdapter.Receipt> smoothingReceipt) {
        public NativeReview(AlignmentEditPlan plan,org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Result quality,
                Optional<RefinementOperationReceipt> operation,List<RefinementSourcePlanner.SourceRequest> sources,OptionalInt retainedParentSections,
                Map<RefinementSourcePlanner.SourceRequest,RetainedSmoothingSource> retainedSources,List<RefinementPipeline.SourceSpan> sourceSpans,Optional<List<FinalRoutePointId>> finalPointIds) {
            this(plan,quality,operation,sources,retainedParentSections,retainedSources,sourceSpans,finalPointIds,Optional.empty());
        }
        public NativeReview {sources=List.copyOf(sources);retainedSources=Map.copyOf(retainedSources);sourceSpans=List.copyOf(sourceSpans);
            Objects.requireNonNull(smoothingReceipt);
            finalPointIds=finalPointIds.map(List::copyOf);
            for(var entry:retainedSources.entrySet())if(!sources.contains(entry.getKey())||!entry.getKey().equals(entry.getValue().source()))
                throw new IllegalArgumentException("Retained source differs from candidate source ownership");
        }
        public NativeReview(AlignmentEditPlan plan,org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Result quality,
                Optional<RefinementOperationReceipt> operation,List<RefinementSourcePlanner.SourceRequest> sources,OptionalInt retainedParentSections,
                Map<RefinementSourcePlanner.SourceRequest,RetainedSmoothingSource> retainedSources,List<RefinementPipeline.SourceSpan> sourceSpans) {
            this(plan,quality,operation,sources,retainedParentSections,retainedSources,sourceSpans,Optional.empty());
        }
        public NativeReview(AlignmentEditPlan plan,org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Result quality,
                Optional<RefinementOperationReceipt> operation,List<RefinementSourcePlanner.SourceRequest> sources,OptionalInt retainedParentSections) {
            this(plan,quality,operation,sources,retainedParentSections,Map.of(),List.of());
        }
        public NativeReview(AlignmentEditPlan plan,org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Result quality) {
            this(plan,quality,Optional.empty(),List.of(),OptionalInt.empty());
        }
    }
    private final Optional<NativeReview> nativeReview;
    public Optional<NativeReview> nativeReview() { return nativeReview; }
    private final AlignmentResult result;
    private final Optional<SourceSelection> sourceSelection;
    private final List<SourceNode> sourceNodes;
    private final IdentityHashMap<Object, Long> charges;
    private RefinementPayload(AlignmentResult result, List<SourceNode> nodes, Optional<SourceSelection> selection) {
        this(result,nodes,selection,Optional.empty());
    }
    private RefinementPayload(AlignmentResult result,List<SourceNode> nodes,Optional<SourceSelection> selection,Optional<NativeReview> nativeReview) {
        this(result,nodes,selection,nativeReview,Optional.empty());
    }
    private RefinementPayload(AlignmentResult result,List<SourceNode> nodes,Optional<SourceSelection> selection,Optional<NativeReview> nativeReview,Optional<RootReviewState> rootState) {
        rootReviewState=rootState;
        this.nativeReview=nativeReview;
        sourceSelection=selection;
        this.result = result;
        sourceNodes = List.copyOf(nodes);
        charges = measure(result);
        nativeReview.ifPresent(review->charges.putAll(nativeReviewCharges(review)));
        rootState.ifPresent(state->charges.putAll(RefinementRootAuthority.detachedCharges(state)));
        charges.put(this,96L);
        charges.put(selection.<Object>map(value->value).orElse(sourceNodes),sourceCharge(nodes.size(),selection.map(v->v.selectedNodeIds().size()).orElse(0)));
    }
    public RefinementPayload withRootReviewState(RootReviewState state) {
        if(nativeReview.isEmpty())throw new IllegalStateException("Native root state requires a stored native plan");
        return new RefinementPayload(result,sourceNodes,sourceSelection,nativeReview,Optional.of(state));
    }
    /**
     * Checks a conservative, overflow-safe bound before copying any raster, point, map or node list.
     * The limit refusal is fallible history construction; callers retain the ordinary Legacy preview.
     */
    public static RefinementPayload capture(AlignmentResult result, long maximumBytes) {
        Objects.requireNonNull(result);
        if (maximumBytes < 1) throw new IllegalArgumentException("Positive payload bound required");
        long estimate = estimateBytes(result);
        if (estimate > maximumBytes) throw new RefinementSession.Refused(RefusalReason.PAYLOAD_LIMIT);
        List<SourceNode> nodes = new ArrayList<>();
        if (result.selection() != null) {
            for (Node node : result.selection().way().getNodes()) {
                var coordinate = node.getCoor();
                nodes.add(new SourceNode(node.getUniqueId(), coordinate == null ? Double.NaN : coordinate.lat(),
                    coordinate == null ? Double.NaN : coordinate.lon()));
            }
        }
        Optional<SourceSelection> provenance=Optional.empty();
        if(result.selection()!=null) {
            var selection=result.selection();
            provenance=Optional.of(new SourceSelection(selection.way().getUniqueId(),selection.startIndex(),selection.endIndex(),
                nodes,selection.segmentNodes().stream().map(Node::getUniqueId).toList()));
        }
        return new RefinementPayload(copy(result), nodes,provenance);
    }
    /** Copies only retained attempt values on the worker, using original detached source facts. */
    public static RefinementPayload captureOriginal(AlignmentResult result, SourceSelection original, long maximumBytes) {
        Objects.requireNonNull(result); Objects.requireNonNull(original);
        long estimate=add(add(sum(measure(result)),96L),sourceCharge(original.fullWayNodes().size(),original.selectedNodeIds().size()));
        if(maximumBytes<1||estimate>maximumBytes)throw new RefinementSession.Refused(RefusalReason.PAYLOAD_LIMIT);
        return new RefinementPayload(copy(result),original.fullWayNodes(),Optional.of(original));
    }
    /** Conservative byte estimate for obtaining an EDT reservation before a worker snapshots history. */
    public static long estimateBytes(AlignmentResult result) {
        Objects.requireNonNull(result);
        int nodeCount = result.selection() == null ? 0 : result.selection().way().getNodesCount();
        return add(add(sum(measure(result)),96L), sourceCharge(nodeCount,result.selection()==null?0:result.selection().segmentNodes().size()));
    }
    private static long sourceCharge(int count,int selected){return 512L+80L*count+32L*selected;}
    /**
     * Carries the already validated original source through descendant/interval reconstruction.
     * The descendant's mutable selection handles cannot replace that frozen original provenance.
     */
    public static RefinementPayload captureDescendant(AlignmentResult result,RefinementPayload parent,long maximumBytes) {
        Objects.requireNonNull(result);Objects.requireNonNull(parent);
        if(maximumBytes<1)throw new IllegalArgumentException("Positive payload bound required");
        long estimate=add(add(sum(measure(result)),96L),sourceCharge(parent.sourceNodes.size(),parent.sourceSelection.map(v->v.selectedNodeIds().size()).orElse(0)));
        if(estimate>maximumBytes)throw new RefinementSession.Refused(RefusalReason.PAYLOAD_LIMIT);
        return new RefinementPayload(copy(result),parent.sourceNodes,parent.sourceSelection);
    }
    /** Keeps the actual full-composition plan and measured quality with the cached candidate. */
    public static RefinementPayload captureNative(AlignmentResult result,RefinementPayload original,
            AlignmentEditPlan plan,org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Result quality,long maximumBytes) {
        return captureNative(result,original,new NativeReview(plan,quality),maximumBytes);
    }
    public static RefinementPayload captureNative(AlignmentResult result,RefinementPayload original,NativeReview review,long maximumBytes) {
        long bytes=add(add(sum(measure(result)),sum(nativeReviewCharges(review))),
            sourceCharge(original.sourceNodes.size(),original.sourceSelection.map(v->v.selectedNodeIds().size()).orElse(0))+96L);
        if(maximumBytes<1||bytes>maximumBytes)throw new RefinementSession.Refused(RefusalReason.PAYLOAD_LIMIT);
        return new RefinementPayload(copy(result),original.sourceNodes,original.sourceSelection,Optional.of(review));
    }
    /** Native materialization only: pre-admit fresh wrappers and the marginal union of factual borrowed groups. */
    static RefinementPayload captureNative(AlignmentResult result,RefinementPayload original,NativeReview review,
            MarginalPayloadAdmission admission,long containerMetadataBytes) {
        if(result.capturedHeatmap()!=null||result.tileMosaics()!=null||result.candidates().stream().anyMatch(c->!c.screenPoints().isEmpty()))
            throw new IllegalArgumentException("Native marginal materialization requires immutable projected geometry");
        var prospective=measure(result);
        // copy() allocates these wrappers even when their input already exists in history.
        fresh(prospective,result);if(result.selection()!=null)fresh(prospective,result.selection());
        var wrappers=new IdentityHashMap<Object,Boolean>();
        for(var candidate:result.candidates())if(wrappers.put(candidate,true)==null)fresh(prospective,candidate);
        if(!result.candidates().isEmpty())fresh(prospective,result.candidates());
        if(!result.applicableCandidates().isEmpty()&&result.applicableCandidates()!=result.candidates())fresh(prospective,result.applicableCandidates());
        prospective.putAll(nativeReviewCharges(review));prospective.put(new Object(),96L);
        prospective.put(original.sourceSelection.<Object>map(value->value).orElse(original.sourceNodes),sourceCharge(original.sourceNodes.size(),original.sourceSelection.map(v->v.selectedNodeIds().size()).orElse(0)));
        long bound=admission.require(prospective,containerMetadataBytes);prospective.clear();
        var payload=new RefinementPayload(copy(result),original.sourceNodes,original.sourceSelection,Optional.of(review));
        admission.admit(payload.charges(),containerMetadataBytes,bound);return payload;
    }
    private static void fresh(IdentityHashMap<Object,Long> inventory,Object identity){Long bytes=inventory.remove(identity);if(bytes!=null)inventory.put(new Object(),bytes);}
    /** Immutable projected final preview, without copying any retained raster for metadata export. */
    public List<org.openstreetmap.josm.data.coor.EastNorth> finalPreviewGeometry(){return result.previewPolyline();}
    public Optional<SourceSelection> sourceSelection(){return sourceSelection;}
    /** Returns a detached mutable-raster/point view; mutation cannot change retained history. */
    public AlignmentResult result() { return copy(result); }
    /** Immutable binder view: excludes rasters, mutable screen points and live primitive handles. */
    public record NativeGeometry(String id,List<org.openstreetmap.josm.data.coor.EastNorth> finalPreviewPoints,
            Map<Long,org.openstreetmap.josm.data.coor.EastNorth> proposedNodePositions) {
        public NativeGeometry {finalPreviewPoints=List.copyOf(finalPreviewPoints);proposedNodePositions=Map.copyOf(proposedNodePositions);}
    }
    public List<NativeGeometry> nativeGeometry() {
        return result.candidates().stream().map(c->new NativeGeometry(c.id(),c.finalPreviewPoints(),c.proposedNodePositions())).toList();
    }
    public List<DetectorAttempt> detectorAttempts(){return result.detectorAttempts();}
    private CenterlineCandidate exactCandidate(String id) {
        CenterlineCandidate selected=null;
        for(var candidate:result.candidates())if(candidate.id().equals(id)) {
            if(selected!=null)throw new ParentReferenceBinder.Refused(ParentReferenceBinder.Reason.SELECTION_UNAVAILABLE);selected=candidate;
        }
        if(selected==null)throw new ParentReferenceBinder.Refused(ParentReferenceBinder.Reason.SELECTION_UNAVAILABLE);return selected;
    }
    private CenterlineCandidate exactCandidate(String id,org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work) {
        CenterlineCandidate selected=null;
        for(var candidate:result.candidates()) {
            work.visit(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.BIND,1);
            if(candidate.id().equals(id)){if(selected!=null)throw new ParentReferenceBinder.Refused(ParentReferenceBinder.Reason.SELECTION_UNAVAILABLE);selected=candidate;}
        }
        if(selected==null)throw new ParentReferenceBinder.Refused(ParentReferenceBinder.Reason.SELECTION_UNAVAILABLE);return selected;
    }
    int nativePointCount(String id){return exactCandidate(id).finalPreviewPoints().size();}
    int nativePointCount(String id,org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work){return exactCandidate(id,work).finalPreviewPoints().size();}
    boolean hasExistingAssignment(String id,long nodeId){return exactCandidate(id).proposedNodePositions().containsKey(nodeId);}
    /** Conservative view/hash materialization bound; no backing evidence arrays are counted again. */
    long smoothingFactsBytes(String id) {return smoothingFactsBytes(id,null);}
    long smoothingFactsBytes(String id,org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work) {
        var selected=work==null?exactCandidate(id):exactCandidate(id,work);long bytes=65536L;
        for(var finding:selected.junctionSafetyFindings()){metadataVisit(work);bytes=Math.addExact(bytes,Math.addExact(4096L,Math.multiplyExact(16L,(long)finding.reasonCode().length()+finding.geometryStage().length())));}
        for(var warning:selected.safetyWarnings()){metadataVisit(work);bytes=Math.addExact(bytes,Math.addExact(256L,Math.multiplyExact(16L,warning.length())));}
        for(var mode:selected.evidence().consensusModes()){metadataVisit(work);bytes=Math.addExact(bytes,Math.addExact(256L,Math.multiplyExact(16L,mode.length())));}
        for(var reason:selected.geometryCleanup().reasons()){metadataVisit(work);bytes=Math.addExact(bytes,Math.addExact(256L,Math.multiplyExact(16L,reason.length())));}
        for(var attempt:result.detectorAttempts()){metadataVisit(work);bytes=Math.addExact(bytes,Math.addExact(4096L,Math.multiplyExact(32L,(long)attempt.sourcePalette().length()+attempt.mappingName().length()+attempt.candidateIds().size())));}
        if(nativeReview.isPresent()&&nativeReview.get().plan()!=null)for(var reason:nativeReview.get().plan().validation().findingCodes()) {
            metadataVisit(work);bytes=Math.addExact(bytes,Math.addExact(256L,Math.multiplyExact(16L,reason.length())));
        }
        if(nativeReview.isPresent())for(var span:nativeReview.get().sourceSpans()) {
            metadataVisit(work);bytes=Math.addExact(bytes,4096L);
            if(span.nativeProvenance().isPresent()) {
                var p=span.nativeProvenance().get();bytes=Math.addExact(bytes,Math.multiplyExact(1024L,p.pointIds().size()));
                if(p.nativeTrace().isPresent())bytes=Math.addExact(bytes,Math.multiplyExact(2048L,p.nativeTrace().get().points().size()));
                if(p.corridorReceipt().isPresent())bytes=Math.addExact(bytes,Math.addExact(2_097_152L,p.corridorReceipt().get().originalCostPolicyMetadataBytes().orElse(0)));
            }
        }
        return bytes;
    }
    private static void metadataVisit(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work){if(work!=null)work.visit(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.BIND,1);}
    static boolean detectorContains(DetectorAttempt attempt,String id,org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work) {
        metadataVisit(work);boolean found=false;for(String candidate:attempt.candidateIds()){metadataVisit(work);if(candidate.equals(id))found=true;}return found;
    }
    SmoothingModels.NativeFacts smoothingFacts(String id,RetainedSmoothingSource source,org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work) {
        var selected=exactCandidate(id,work);var request=source.source();
        var attempts=new ArrayList<DetectorAttempt>();
        for(var attempt:result.detectorAttempts())if(detectorContains(attempt,id,work)&&attempt.sourcePalette().equals(request.palette())&&attempt.mappingName().equals(request.mapping()))attempts.add(attempt);
        if(attempts.isEmpty())throw new IllegalArgumentException("Selected source has no actual detector finding");
        return new SmoothingModels.NativeFacts(source,selected.evidence(),selected.geometryCleanup(),selected.safetyWarnings(),selected.junctionSafetyFindings(),
            source.legacyDisposition().filter(d->d.nativeCandidateId().equals(id)).map(RetainedSmoothingSource.LegacyDisposition::applicable)
                .orElseGet(()->{boolean applicable=false;for(var candidate:result.applicableCandidates()){metadataVisit(work);if(candidate.id().equals(id))applicable=true;}return applicable;}),attempts,nativeReview.map(NativeReview::quality),
            nativeReview.map(r->r.plan().validation().disposition()),nativeReview.map(NativeReview::sourceSpans).orElse(List.of()),
            nativeReview.map(NativeReview::plan).map(AlignmentEditPlan::validation));
    }
    public long retainedBytes() { return sum(charges); }
    /** Frozen original full-way coordinate/occurrence values, independent of the retained live handles. */
    public List<SourceNode> sourceNodes() { return sourceNodes; }
    Map<Object, Long> charges() { return Collections.unmodifiableMap(charges); }
    /** Borrowed evidence keeps its acquisition's grouped scalar/mask charge, never a second array inventory. */
    private static Map<Object,Long> nativeReviewCharges(NativeReview review) {
        var values=new IdentityHashMap<Object,Long>();
        values.putAll(RefinementRootAuthority.detachedCharges(review.plan(),review.quality(),review.operation(),review.sources(),review.retainedParentSections(),review.finalPointIds(),review.smoothingReceipt()));
        values.put(review,512L);values.put(review.retainedSources(),128L+128L*review.retainedSources().size());
        values.put(review.sourceSpans(),128L+32L*review.sourceSpans().size());
        review.retainedSources().values().forEach(source->values.putAll(source.charges()));
        for(var span:review.sourceSpans()) {
            values.put(span,256L);values.putAll(RefinementRootAuthority.detachedCharges(span.source(),span.span()));
            span.nativeProvenance().ifPresent(provenance->{
                values.put(provenance,256L);
                values.putAll(RefinementRootAuthority.detachedCharges(provenance.pointIds(),provenance.referenceChainages(),provenance.quality(),provenance.nativeTrace()));
                provenance.nativeBasis().ifPresent(basis->values.put(basis,basis.scratchReservationBytes()));
                provenance.corridorReceipt().ifPresent(receipt->{
                    // Bounded native record (<=512 track rows), including its factual policy map.
                    long bytes=Math.addExact(1_048_576L,receipt.originalCostPolicyMetadataBytes().orElse(0));
                    values.put(receipt,bytes);
                });
            });
        }
        return values;
    }
    /** Acquire already-admitted raster/descriptor identities before worker ownership settles. */
    void retainSmoothingSources( org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner destination) {
        nativeReview.ifPresent(review->review.retainedSources().values().forEach(source->source.retainInto(destination)));
    }
    /** Newly published metadata excludes the separately co-owned acquisition identities. */
    long publicationBytes() {
        return sum(publicationCharges());
    }
    Map<Object,Long> publicationCharges() {
        var borrowed=new IdentityHashMap<Object,Long>();
        nativeReview.ifPresent(review->review.retainedSources().values().forEach(source->borrowed.putAll(source.charges())));
        var allocated=new IdentityHashMap<Object,Long>(charges);
        borrowed.keySet().forEach(allocated::remove);return Collections.unmodifiableMap(allocated);
    }
    static long add(long left, long right) {
        if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
    private static long sum(Map<Object, Long> charges) {
        long bytes = 0;
        for (long value : charges.values()) bytes = add(bytes, value);
        return bytes;
    }

    private static AlignmentResult copy(AlignmentResult source) {
        IdentityHashMap<BufferedImage, BufferedImage> images = new IdentityHashMap<>();
        IdentityHashMap<CenterlineCandidate, CenterlineCandidate> candidates = new IdentityHashMap<>();
        IdentityHashMap<List<Point2D.Double>, List<Point2D.Double>> pointLists = new IdentityHashMap<>();
        IdentityHashMap<Point2D.Double, Point2D.Double> screenPoints = new IdentityHashMap<>();
        List<CenterlineCandidate> all = List.copyOf(source.candidates().stream()
            .map(candidate -> candidates.computeIfAbsent(candidate, value -> copyCandidate(value, pointLists, screenPoints))).toList());
        List<CenterlineCandidate> applicable = source.applicableCandidates() == source.candidates() ? all
            : List.copyOf(source.applicableCandidates().stream()
                .map(candidate -> candidates.computeIfAbsent(candidate, value -> copyCandidate(value, pointLists, screenPoints))).toList());
        SelectionContext selection = source.selection();
        if (selection != null) selection = new SelectionContext(selection.way(), selection.startIndex(), selection.endIndex(),
            List.copyOf(selection.segmentNodes()), Set.copyOf(selection.fixedNodes()));
        TileMosaicSet mosaics = source.tileMosaics();
        if (mosaics != null) {
            Map<String, TileMosaic> frozen = new LinkedHashMap<>();
            IdentityHashMap<TileMosaic, TileMosaic> mosaicCopies = new IdentityHashMap<>();
            IdentityHashMap<Map<String, BufferedImage>, Map<String, BufferedImage>> tileMaps = new IdentityHashMap<>();
            IdentityHashMap<List<TileRecord>, List<TileRecord>> tileLists = new IdentityHashMap<>();
            for (Map.Entry<String, TileMosaic> entry : mosaics.mosaics().entrySet()) {
                TileMosaic mosaic = entry.getValue();
                frozen.put(entry.getKey(), mosaicCopies.computeIfAbsent(mosaic, value -> {
                    Map<String, BufferedImage> tiles = tileMaps.computeIfAbsent(value.tileImages(), original -> {
                        Map<String, BufferedImage> detached = new LinkedHashMap<>();
                        original.forEach((name, raster) -> detached.put(name, image(raster, images)));
                        return Map.copyOf(detached);
                    });
                    return new TileMosaic(value.color(), value.zoom(), value.originWorldPxX(), value.originWorldPxY(),
                        image(value.image(), images), tileLists.computeIfAbsent(value.tiles(), List::copyOf), tiles, value.parameters(),
                        value.stableInferenceRaster(), value.virtualRasterScale());
                }));
            }
            mosaics = new TileMosaicSet(mosaics.inferenceMode(), mosaics.requestedInferenceZoom(),
                mosaics.requestedValidationZoom(), mosaics.inferenceZoom(), mosaics.validationZoom(), mosaics.tileSize(),
                Map.copyOf(frozen), mosaics.inferenceParameters(), mosaics.validationParameters());
        }
        return new AlignmentResult(selection, image(source.capturedHeatmap(), images), all, source.sourcePolyline(),
            source.previewPolyline(), source.nodeMoves(), source.diagnostics(), mosaics, source.detectorAttempts(), applicable);
    }
    private static CenterlineCandidate copyCandidate(CenterlineCandidate candidate,
            IdentityHashMap<List<Point2D.Double>, List<Point2D.Double>> lists,
            IdentityHashMap<Point2D.Double, Point2D.Double> points) {
        List<Point2D.Double> screen = lists.computeIfAbsent(candidate.screenPoints(), original -> List.copyOf(original.stream()
            .map(point -> points.computeIfAbsent(point, value -> new Point2D.Double(value.x, value.y))).toList()));
        return new CenterlineCandidate(candidate.id(), candidate.score(), screen, candidate.offsetsPx(),
            candidate.eastNorthPoints(), candidate.finalPreviewPoints(), candidate.proposedNodePositions(),
            candidate.junctionSafetyFindings(), candidate.junctionSafetyToleranceMeters(), candidate.evidence(),
            candidate.cleanupEvidence(), candidate.geometryCleanup(), candidate.safetyWarnings());
    }
    private static BufferedImage image(BufferedImage source, IdentityHashMap<BufferedImage, BufferedImage> images) {
        if (source == null) return null;
        return images.computeIfAbsent(source, original -> {
            BufferedImage detached = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < original.getHeight(); y++) {
                int[] row = original.getRGB(0, y, original.getWidth(), 1, null, 0, original.getWidth());
                detached.setRGB(0, y, original.getWidth(), 1, row, 0, original.getWidth());
            }
            return detached;
        });
    }

    /** Typed inventory of owned values; mutable Node/Way object graphs are identity handles, never traversed. */
    private static IdentityHashMap<Object, Long> measure(AlignmentResult result) {
        IdentityHashMap<Object, Long> values = new IdentityHashMap<>();
        put(values, result, 192);
        imageBytes(values, result.capturedHeatmap());
        points(values, result.sourcePolyline()); points(values, result.previewPolyline());
        collection(values, result.nodeMoves(), 48);
        for (NodeMove move : result.nodeMoves()) put(values, move.target(), 32);
        SelectionContext selection = result.selection();
        if (selection != null) {
            put(values, selection, 80);
            collection(values, selection.segmentNodes(), 0); collection(values, selection.fixedNodes(), 0);
        }
        collection(values, result.candidates(), 0); collection(values, result.applicableCandidates(), 0);
        for (CenterlineCandidate candidate : result.candidates()) candidateBytes(values, candidate);
        for (CenterlineCandidate candidate : result.applicableCandidates()) candidateBytes(values, candidate);
        collection(values, result.detectorAttempts(), 96);
        for (DetectorAttempt attempt : result.detectorAttempts()) {
            strings(values, attempt.sourcePalette(), attempt.mappingName(), attempt.reasonCode(), attempt.reason());
            collection(values, attempt.candidateIds(), 0);
            attempt.candidateIds().forEach(id -> strings(values, id));
        }
        AlignmentDiagnostics d = result.diagnostics();
        if (d != null) {
            put(values, d, 256);
            strings(values, d.layerName(), d.configJson(), d.selectionJson(), d.samplingJson(), d.colorSchemesJson(),
                d.candidatesJson(), d.profileDiagnosticsJson(), d.candidateMetricsCsv(), d.profilePeaksCsv(),
                d.paletteSamplesCsv(), d.profileIntensityCsv(), d.corridorBandsCsv(), d.corridorTracksCsv(),
                d.corridorBundlesCsv(), d.bundlePointsCsv(), d.optimizerCostsCsv(), d.scaleSpaceCsv(), d.corridorTubeCsv(),
                d.associationDecisionsCsv(), d.endpointApproachesCsv(), d.detectorPerformanceCsv(), d.parallelContextJson());
        }
        TileMosaicSet set = result.tileMosaics();
        if (set != null) {
            put(values, set, 128); put(values, set.mosaics(), 64L + 64L * set.mosaics().size());
            put(values, set.inferenceParameters(), 96); put(values, set.validationParameters(), 96);
            for (Map.Entry<String, TileMosaic> entry : set.mosaics().entrySet()) {
                strings(values, entry.getKey());
                TileMosaic mosaic = entry.getValue();
                put(values, mosaic, 128); strings(values, mosaic.color()); imageBytes(values, mosaic.image());
                put(values, mosaic.parameters(), 96);
                collection(values, mosaic.tiles(), 160);
                for (var tile : mosaic.tiles()) strings(values, tile.color(), tile.sha256(), tile.quality(), tile.error());
                put(values, mosaic.tileImages(), 64L + 64L * mosaic.tileImages().size());
                mosaic.tileImages().forEach((name, image) -> { strings(values, name); imageBytes(values, image); });
            }
        }
        return values;
    }
    private static void candidateBytes(IdentityHashMap<Object, Long> values, CenterlineCandidate candidate) {
        if (values.containsKey(candidate)) return;
        put(values, candidate, 192); strings(values, candidate.id());
        collection(values, candidate.screenPoints(), 32); collection(values, candidate.offsetsPx(), 24);
        points(values, candidate.eastNorthPoints()); points(values, candidate.finalPreviewPoints());
        put(values, candidate.proposedNodePositions(), 64L + 96L * candidate.proposedNodePositions().size());
        collection(values, candidate.junctionSafetyFindings(), 512);
        candidate.junctionSafetyFindings().forEach(finding -> strings(values, finding.reasonCode(), finding.geometryStage()));
        CandidateEvidence evidence = candidate.evidence();
        put(values, evidence, 512); strings(values, evidence.detectorMode(), evidence.corridorCoverage().reason());
        collection(values, evidence.consensusModes(), 0); evidence.consensusModes().forEach(value -> strings(values, value));
        CandidateCleanupEvidence cleanup = candidate.cleanupEvidence();
        put(values, cleanup, 96); collection(values, cleanup.profiles(), 160);
        CleanupSamplingFrame frame = cleanup.samplingFrame();
        put(values, frame, 96); strings(values, frame.detectorMode());
        collection(values, frame.profiles(), 0);
        frame.profiles().forEach(profile -> put(values, profile, add(256, profile.estimatedBytes())));
        CandidateGeometryCleanup report = candidate.geometryCleanup();
        put(values, report, 256); strings(values, report.parentCandidateId(), report.reasonCode());
        collection(values, report.reasons(), 0); report.reasons().forEach(value -> strings(values, value));
        collection(values, candidate.safetyWarnings(), 0); candidate.safetyWarnings().forEach(value -> strings(values, value));
    }
    private static void points(IdentityHashMap<Object, Long> values, List<?> points) { collection(values, points, 32); }
    private static void imageBytes(IdentityHashMap<Object, Long> values, BufferedImage image) {
        if (image != null) put(values, image, add(256, 4L * image.getWidth() * image.getHeight()));
    }
    private static void collection(IdentityHashMap<Object, Long> values, Collection<?> collection, long each) {
        put(values, collection, 64L + 16L * collection.size());
        if (each > 0) collection.forEach(value -> put(values, value, each));
    }
    private static void strings(IdentityHashMap<Object, Long> values, String... strings) {
        for (String string : strings) if (string != null) put(values, string, 48L + 2L * string.length());
    }
    private static void put(IdentityHashMap<Object, Long> values, Object key, long bytes) {
        if (key != null) values.merge(key, bytes, Math::max);
    }
}

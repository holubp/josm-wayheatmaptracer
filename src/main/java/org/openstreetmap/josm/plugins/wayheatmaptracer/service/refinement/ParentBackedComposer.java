package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.PostprocessingValidationWork;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.ParentArc;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.SharedNodeDisconnectionPlanner;

/** Exact parent-backed selected-range composition, without dataset mutation or inference. */
public final class ParentBackedComposer {
    public record Choice(ParentTraceReference reference,List<MetricPoint> points,List<FinalRoutePointId> pointIds,
            List<ParentArc> correspondence,String nativeId,String palette) {
        public Choice {
            Objects.requireNonNull(reference);Objects.requireNonNull(nativeId);Objects.requireNonNull(palette);
            points=List.copyOf(points);pointIds=List.copyOf(pointIds);correspondence=List.copyOf(correspondence);
            if(points.size()<2||points.size()!=pointIds.size()||nativeId.isBlank()||palette.isBlank())
                throw new IllegalArgumentException("Incomplete interval choice");
        }
    }
    public record Span(int firstVertex,int lastVertex,boolean parentRetained,String nativeId,String palette) { }
    public record Composed(String id,List<MetricPoint> points,List<FinalRoutePointId> pointIds,
            Map<FinalRoutePointId,MetricPoint> assignments,List<ParentArc> correspondence,List<Span> spans) {
        public Composed {
            Objects.requireNonNull(id);points=List.copyOf(points);pointIds=List.copyOf(pointIds);assignments=Map.copyOf(assignments);
            correspondence=List.copyOf(correspondence);spans=List.copyOf(spans);
            if(id.isBlank()||points.size()<2||points.size()!=pointIds.size()||assignments.size()!=points.size())
                throw new IllegalArgumentException("Incomplete composed final geometry");
            for(int i=0;i<points.size();i++){PostprocessingValidationWork.visit();if(!points.get(i).equals(assignments.get(pointIds.get(i))))
                throw new IllegalArgumentException("Composed assignments differ from geometry");
            }
        }
    }
    public record CopyAssessment(FinalRoutePointId.ExistingWayNodeOccurrence occurrence,PrimitiveKey destinationKey,
            Optional<DetachedNode> retainedOriginal,Optional<DetachedNode> destination,Map<PrimitiveKey,DetachedWay> contextWays) {
        public CopyAssessment {contextWays=Map.copyOf(contextWays);}
    }
    public record Assessed(Composed candidate,FinalGeometryEvaluator.Result quality,List<String> topologyFindings,List<CopyAssessment> copies) {
        public Assessed(Composed candidate,FinalGeometryEvaluator.Result quality,List<String> topologyFindings){this(candidate,quality,topologyFindings,List.of());}
        public Assessed { Objects.requireNonNull(candidate);Objects.requireNonNull(quality);topologyFindings=List.copyOf(topologyFindings);copies=List.copyOf(copies);
            if(!candidate.id().equals(quality.id()))throw new IllegalArgumentException("Assessment identity differs"); }
    }
    public Composed compose(String id,ParentTraceReference parent,List<Choice> choices) {
        Objects.requireNonNull(parent);var ordered=new ArrayList<>(choices);
        ordered.sort(Comparator.comparingInt(c->c.reference().originalRange().firstIndex()));
        var points=new ArrayList<MetricPoint>();var ids=new ArrayList<FinalRoutePointId>();
        var positions=new TreeMap<Integer,ParentArc>();var spans=new ArrayList<Span>();
        int cursor=parent.originalRange().firstIndex();
        for(var choice:ordered) {
            PostprocessingValidationWork.visit();
            var reference=choice.reference();var range=reference.originalRange();
            reference.requireRefinementGeometryAuthority();
            if(reference.root()!=parent.root()||!reference.parentStage().equals(parent.parentStage())
                    ||!reference.parentChoice().equals(parent.parentChoice())||range.firstIndex()<cursor
                    ||range.lastIndex()>parent.originalRange().lastIndex()||range.size()<2
                    ||!reference.points().equals(parent.slice(range).points())
                    ||!choice.points().get(0).equals(parent.occurrencePosition(range.firstIndex()))
                    ||!choice.points().get(choice.points().size()-1).equals(parent.occurrencePosition(range.lastIndex())))
                throw new IllegalArgumentException("Replacement overlaps, expands or moves its exact parent cuts");
            if(parent.root().network().closure().editableWayOccurrences().getOrDefault(parent.root().request().selectedWayKey(),List.of()).stream().peek(meteredValue -> PostprocessingValidationWork.visit())
                    .noneMatch(r->r.firstIndex()<=range.firstIndex()&&r.lastIndex()>=range.lastIndex()))
                throw new IllegalArgumentException("Replacement exceeds original interval edit authority");
            if(cursor<range.firstIndex())appendParent(parent.slice(new OccurrenceRange(cursor,range.firstIndex())),points,ids,positions,spans);
            append(choice.points(),choice.pointIds(),choice.correspondence(),false,choice.nativeId(),choice.palette(),points,ids,positions,spans);
            cursor=range.lastIndex();
        }
        if(ordered.isEmpty())appendParent(parent,points,ids,positions,spans);
        else if(cursor<parent.originalRange().lastIndex())appendParent(parent.slice(new OccurrenceRange(cursor,parent.originalRange().lastIndex())),points,ids,positions,spans);
        var arcs=new ArrayList<ParentArc>();
        for(var arc:positions.values()) {
            PostprocessingValidationWork.visit();
            arcs.add(arc.parentEdge()==points.size()-1
                    ?new ParentArc(arc.originalOccurrence(),arc.parentEdge()-1,1):arc);
        }
        // Only permanent original identities are required by a Precise parent with no optional
        // Legacy assignment map. A cut on a removed occurrence never invents node reuse.
        var root=parent.root();var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
        for(var arc:arcs) {
            PostprocessingValidationWork.visit();
            int occurrence=arc.originalOccurrence();var key=way.nodeKeys().get(occurrence);
            boolean required=root.request().geometryMode()==AlignmentMode.MOVE_EXISTING_NODES
                ||!root.network().closure().removableExistingNodeKeys().contains(key);
            if(required) {
                int vertex=arc.fraction()==0?arc.parentEdge():arc.fraction()==1?arc.parentEdge()+1:-1;
                if(vertex<0)throw new IllegalArgumentException("Required original occurrence was removed");
                var existing=new FinalRoutePointId.ExistingWayNodeOccurrence(way.key(),key,occurrence);
                if(ids.get(vertex) instanceof FinalRoutePointId.ExistingWayNodeOccurrence other&&!other.equals(existing))
                    throw new IllegalArgumentException("Original identities collide");
                ids.set(vertex,existing);
            }
        }
        for(int occurrence=parent.originalRange().firstIndex();occurrence<=parent.originalRange().lastIndex();occurrence++) {
            PostprocessingValidationWork.visit();
            var key=way.nodeKeys().get(occurrence);
            if(!root.network().closure().removableExistingNodeKeys().contains(key)&&!positions.containsKey(occurrence))
                throw new IllegalArgumentException("Missing permanent original occurrence");
        }
        for(int i=0;i<ids.size();i++) {
            PostprocessingValidationWork.visit();
            if(ids.get(i) instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing) {
                int occurrence=existing.originalOccurrenceIndex();
                if(!existing.wayKey().equals(way.key())||occurrence<parent.originalRange().firstIndex()||occurrence>parent.originalRange().lastIndex()
                        ||!existing.nodeKey().equals(way.nodeKeys().get(occurrence)))throw new IllegalArgumentException("Foreign existing assignment");
            } else ids.set(i,new FinalRoutePointId.GeneratedCandidatePoint(id,i));
        }
        if(root.request().geometryMode()==AlignmentMode.MOVE_EXISTING_NODES) {
            if(ids.size()!=parent.originalRange().size())throw new IllegalArgumentException("Move occurrence count changed");
            for(int i=0;i<ids.size();i++)if(!(ids.get(i) instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing)
                    ||existing.originalOccurrenceIndex()!=parent.originalRange().firstIndex()+i)throw new IllegalArgumentException("Move occurrence order changed");
        }
        var assignments=new LinkedHashMap<FinalRoutePointId,MetricPoint>();
        for(int i=0;i<ids.size();i++)if(assignments.put(ids.get(i),points.get(i))!=null)throw new IllegalArgumentException("Repeated final identity");
        return new Composed(id,points,ids,assignments,arcs,spans);
    }
    private static void appendParent(ParentTraceReference part,List<MetricPoint> points,List<FinalRoutePointId> ids,
            Map<Integer,ParentArc> positions,List<Span> spans) {
        var generated=new ArrayList<FinalRoutePointId>();for(int i=0;i<part.points().size();i++)generated.add(new FinalRoutePointId.GeneratedCandidatePoint("parent",i));
        append(part.points(),generated,part.correspondence(),true,part.nativeCandidateId(),part.parentPalette(),points,ids,positions,spans);
    }
    private static void append(List<MetricPoint> part,List<FinalRoutePointId> partIds,List<ParentArc> arcs,boolean retained,
            String nativeId,String palette,List<MetricPoint> points,List<FinalRoutePointId> ids,Map<Integer,ParentArc> positions,List<Span> spans) {
        int base=points.isEmpty()?0:points.size()-1;
        if(!points.isEmpty()&&!points.get(base).equals(part.get(0)))throw new IllegalArgumentException("Parent interval join differs");
        for(int i=points.isEmpty()?0:1;i<part.size();i++){
            PostprocessingValidationWork.visit();points.add(part.get(i));ids.add(partIds.get(i));}
        if(partIds.get(0) instanceof FinalRoutePointId.ExistingWayNodeOccurrence)ids.set(base,partIds.get(0));
        for(var arc:arcs) {
            PostprocessingValidationWork.visit();
            if(arc.parentEdge()>=part.size()-1)throw new IllegalArgumentException("Interval correspondence exceeds final geometry");
            int edge=base+arc.parentEdge();
            // Canonicalize a join vertex as fraction zero of its outgoing edge.
            // Adding an edge ordinal to a fractional coordinate loses precision.
            var mapped=arc.fraction()==1?new ParentArc(arc.originalOccurrence(),edge+1,0)
                    :new ParentArc(arc.originalOccurrence(),edge,arc.fraction());
            var previous=positions.put(arc.originalOccurrence(),mapped);
            if(previous!=null&&!previous.equals(mapped))throw new IllegalArgumentException("Join occurrence maps twice");
        }
        spans.add(new Span(base,points.size()-1,retained,nativeId,palette));
    }
    public Assessed assess(ParentTraceReference parent,Composed candidate,EvidenceSnapshot evidence,String field,CancellationProbe cancellation) {
        cancellation.checkpoint();var root=parent.root();
        root.requireAcquisitionEvidence(evidence);
        return assessInternal(parent,candidate,evidence,ImageCostField.fromEvidence(evidence,field),cancellation,null,null);
    }
    /** Postprocessing reuses the exact admitted selected-source image; no second raster copy. */
    public Assessed assessPostprocessed(ParentTraceReference parent,Composed candidate,EvidenceSnapshot evidence,
            ImageCostField image,org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work,CancellationProbe cancellation) {
        return assessPostprocessed(parent,candidate,evidence,image,work,cancellation,new ImageCostField.SegmentSupportBudget(Long.MAX_VALUE));
    }
    /** Exact source-cell work shares one operation counter across every assembled trial. */
    public Assessed assessPostprocessed(ParentTraceReference parent,Composed candidate,EvidenceSnapshot evidence,
            ImageCostField image,org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work,CancellationProbe cancellation,
            ImageCostField.SegmentSupportBudget sourceCells) {
        Objects.requireNonNull(sourceCells);
        try(var scope=PostprocessingValidationWork.open(work)) {
            return assessInternal(parent,candidate,evidence,image,cancellation,sourceCells,null);
        }
    }
    /** Uses the same admitted slots for draft topology and later exact-plan topology. */
    Assessed assessSmoothing(SmoothingModels.Parent parent,Composed candidate,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work,
            CancellationProbe cancellation,ImageCostField.SegmentSupportBudget sourceCells) {
        try(var scope=PostprocessingValidationWork.open(work)) {
            return assessInternal(parent.reference(),candidate,parent.evidence(),parent.image(),cancellation,sourceCells,parent);
        }
    }
    private Assessed assessInternal(ParentTraceReference parent,Composed candidate,EvidenceSnapshot evidence,ImageCostField image,CancellationProbe cancellation,
            ImageCostField.SegmentSupportBudget sourceCells,SmoothingModels.Parent smoothingParent) {
        cancellation.checkpoint();var root=parent.root();
        root.requireAcquisitionEvidence(evidence);
        var smoothingTopology=smoothingParent==null?null:requireSmoothingTrialTopology(
                smoothingParent,candidate,evidence,cancellation);
        var protectedAssignments=new LinkedHashMap<Integer,MetricPoint>();
        var extra=new ArrayList<FinalGeometryEvaluator.Finding>();
        if(root.frozenGeometryProof().isEmpty())extra.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.CONTEXT_PROOF_UNAVAILABLE,
            FinalGeometryEvaluator.Severity.HARD_BLOCK,0,candidate.points().size()-1,0));
        else for(var frozen:root.frozenGeometryProof().get().ranges()) {
            PostprocessingValidationWork.visit();
            int first=Math.max(frozen.firstIndex(),parent.originalRange().firstIndex()),last=Math.min(frozen.lastIndex(),parent.originalRange().lastIndex());
            if(first>last)continue;
            try {
                var expected=first==last?List.of(parent.occurrencePosition(first)):
                    PostprocessingValidationWork.current()!=null?cutGeometry(parent.points(),parent.correspondence(),first,last):parent.slice(new OccurrenceRange(first,last)).points();
                if(!expected.equals(cutGeometry(candidate.points(),candidate.correspondence(),first,last)))
                    extra.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.PROTECTED_ASSIGNMENT_MISMATCH,FinalGeometryEvaluator.Severity.HARD_BLOCK,0,candidate.points().size()-1,0));
            }catch(IllegalArgumentException|ParentTraceReference.UnavailableException unavailable) {
                extra.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.CONTEXT_PROOF_UNAVAILABLE,FinalGeometryEvaluator.Severity.HARD_BLOCK,0,candidate.points().size()-1,0));
            }
        }
        var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
        var original=new ArrayList<MetricPoint>();
        for(int i=parent.originalRange().firstIndex();i<=parent.originalRange().lastIndex();i++) {PostprocessingValidationWork.visit();
            original.add(evidence.coordinateFrame().toMetric(((DetachedNode)root.network().primitives().get(way.nodeKeys().get(i))).coordinate()));
        }
        for(int i=0;i<candidate.points().size();i++) {
            PostprocessingValidationWork.visit();
            cancellation.checkpoint();var point=candidate.points().get(i);
            boolean outside=!root.network().closure().editRegion().contains(point)||distanceToPolyline(point,original)>root.request().permissions().ordinaryRadiusMeters()+1e-7;
            if(i>0)outside|=!root.network().closure().editRegion().containsSegment(candidate.points().get(i-1),point);
            if(PostprocessingValidationWork.current()!=null&&(!evidence.evidenceRegion().contains(point)
                    ||i>0&&!evidence.evidenceRegion().containsSegment(candidate.points().get(i-1),point)))
                extra.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.CONTEXT_PROOF_UNAVAILABLE,
                    FinalGeometryEvaluator.Severity.HARD_BLOCK,i,i,0));
            if(sourceCells!=null&&i>0) {
                var coverage=image.certifySegmentSupport(candidate.points().get(i-1),point,sourceCells,cancellation);
                if(coverage==ImageCostField.SegmentSupport.RESOURCE_LIMIT)
                    throw new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.LimitReached();
                if(coverage!=ImageCostField.SegmentSupport.COMPLETE)
                    extra.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.CONTEXT_PROOF_UNAVAILABLE,
                        FinalGeometryEvaluator.Severity.HARD_BLOCK,i-1,i,0));
            }
            if(candidate.pointIds().get(i) instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing) {
                var pinned=parent.frozenAnchors().get(existing.originalOccurrenceIndex());
                if(pinned!=null)protectedAssignments.put(i,pinned);
                var captured=evidence.coordinateFrame().toMetric(((DetachedNode)root.network().primitives().get(existing.nodeKey())).coordinate());
                outside|=point.distanceTo(captured)>root.request().permissions().ordinaryRadiusMeters()+1e-7;
            }
            if(outside)extra.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.PROTECTED_ASSIGNMENT_MISMATCH,
                FinalGeometryEvaluator.Severity.HARD_BLOCK,i,i,0));
        }
        var request=new FinalGeometryEvaluator.Request(candidate.id(),candidate.points(),candidate.pointIds(),
                image,evidence.resolution().effectivePitchMeters(),protectedAssignments,List.of(),false,false,false,false);
        var evaluator=new FinalGeometryEvaluator();
        var quality=smoothingTopology==null?evaluator.evaluate(request,cancellation):evaluator.evaluateSmoothing(request,cancellation);
        var graph=smoothingTopology==null?topologyDraft(parent,candidate,evidence,cancellation,false):null;
        var topology=graph==null?smoothingTopology:ModernSingleWayEditPlanAdapter.assessFinalTopology(
                root,graph.after(),evidence,root.request().permissions().ordinaryRadiusMeters());
        for(String finding:topology.findingCodes())extra.add(new FinalGeometryEvaluator.Finding(
            finding.contains("review-required")?FinalGeometryEvaluator.FindingCode.INDEPENDENT_CONTEXT_CONTACT:FinalGeometryEvaluator.FindingCode.PREJUNCTION_CROSSING,
            finding.contains("review-required")?FinalGeometryEvaluator.Severity.REVIEW:FinalGeometryEvaluator.Severity.HARD_BLOCK,0,candidate.points().size()-1,0));
        if(!extra.isEmpty()) {
            var findings=new ArrayList<>(quality.findings());findings.addAll(extra);
            var disposition=findings.stream().peek(meteredValue -> PostprocessingValidationWork.visit()).anyMatch(f->f.severity()==FinalGeometryEvaluator.Severity.HARD_BLOCK)?FinalGeometryEvaluator.Disposition.HARD_BLOCKED:FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED;
            quality=new FinalGeometryEvaluator.Result(quality.id(),disposition,findings,quality.totalLengthMeters(),quality.directlySupportedLengthMeters(),quality.worstUnsupportedSpanMeters(),quality.meanImageCenterCost(),quality.bendPreservingRoughness());
        }
        if(graph==null) {
            cancellation.checkpoint();
            return new Assessed(candidate,quality,topology.findingCodes(),List.of());
        }
        var after=graph.after();var replacement=graph.replacement();var disconnections=graph.disconnections();
        var copies=new ArrayList<CopyAssessment>();
        for(int i=0;i<candidate.pointIds().size();i++){PostprocessingValidationWork.visit();if(candidate.pointIds().get(i) instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing
                &&disconnections.replacements().containsKey(existing.originalOccurrenceIndex())) {
            var context=new LinkedHashMap<PrimitiveKey,DetachedWay>();
            for(var ref:root.network().incomingReferrerWatches().getOrDefault(existing.nodeKey(),Set.of()))
                if(!ref.equals(way.key())&&after.get(ref) instanceof DetachedWay other&&!other.deleted())context.put(ref,other);
            var originalAfter=after.get(existing.nodeKey());var destinationAfter=after.get(replacement.get(i));
            copies.add(new CopyAssessment(existing,replacement.get(i),originalAfter instanceof DetachedNode node?Optional.of(node):Optional.empty(),
                destinationAfter instanceof DetachedNode node?Optional.of(node):Optional.empty(),context));
        }
        }
        cancellation.checkpoint();return new Assessed(candidate,quality,topology.findingCodes(),copies);
    }

    private ModernSingleWayEditPlanAdapter.FinalTopologyAssessment requireSmoothingTrialTopology(
            SmoothingModels.Parent smoothingParent,Composed candidate,EvidenceSnapshot evidence,CancellationProbe cancellation) {
        var parent=smoothingParent.reference();
        var root=parent.root();
        if(!root.request().permissions().sharedNodeDisconnectionIntent().isEmpty()) {
            throw new IllegalArgumentException("Smoothing cannot create disconnected junction copies");
        }
        if(!FinalGeometryEvaluator.backtrackFindings(candidate.points()).isEmpty()) {
            throw new IllegalArgumentException("Smoothing trial failed the adjacent backtrack check");
        }
        var graph=topologyDraft(parent,candidate,evidence,cancellation,true);
        var topology=ModernSingleWayEditPlanAdapter.validateSmoothingContacts(smoothingParent,candidate,graph.after(),
                ModernSingleWayEditPlanAdapter.assessFinalTopology(root,graph.after(),evidence,
                        root.request().permissions().ordinaryRadiusMeters()));
        if(topology.findingCodes().stream().anyMatch(code->!code.contains("review-required"))) {
            throw new IllegalArgumentException("Smoothing trial failed final topology");
        }
        return topology;
    }
    /** Measures only actual graph contacts before spending quality work on a smoothing proposal. */
    Set<Integer> smoothingContactRollbackSlots(SmoothingModels.Parent parent,Composed proposed,CancellationProbe cancellation) {
        if(proposed.points().size()!=parent.reference().points().size()||!proposed.pointIds().equals(parent.pointIds()))
            throw new IllegalArgumentException("Smoothing contact rollback requires the exact selected slots");
        var root=parent.reference().root();
        var graph=topologyDraft(parent.reference(),proposed,parent.evidence(),cancellation,true);
        var measured=ModernSingleWayEditPlanAdapter.assessFinalTopology(root,graph.after(),parent.evidence(),
                root.request().permissions().ordinaryRadiusMeters());
        return ModernSingleWayEditPlanAdapter.smoothingContactRollbackSlots(parent,proposed,graph.after(),measured);
    }
    private record TopologyDraft(Map<PrimitiveKey,DetachedPrimitive> after,List<PrimitiveKey> replacement,
            SharedNodeDisconnectionPlanner.Authority disconnections) { }
    private static TopologyDraft topologyDraft(ParentTraceReference parent,Composed candidate,
            EvidenceSnapshot evidence,CancellationProbe cancellation,boolean smoothing) {
        var root=parent.root();
        var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
        var after=new LinkedHashMap<>(root.network().primitives());var replacement=new ArrayList<PrimitiveKey>();
        var disconnections=SharedNodeDisconnectionPlanner.forRequest(root.request(),root.network());
        for(int i=0;i<candidate.points().size();i++) {
            PostprocessingValidationWork.visit();
            cancellation.checkpoint();var id=candidate.pointIds().get(i);
            var key=smoothing?ModernSingleWayEditPlanAdapter.smoothingSlotKey(id):
                    id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing?disconnections.destinationKey(existing):PrimitiveKey.planned(PrimitiveKey.Type.NODE,i+1);
            var old=after.get(key);if(!(id instanceof FinalRoutePointId.ExistingWayNodeOccurrence)&&old!=null)throw new IllegalArgumentException("Generated topology identity collision");
            var tags=old instanceof DetachedNode node?node.tags():Map.<String,String>of();
            var coordinate=evidence.coordinateFrame().toGeographic(candidate.points().get(i));
            if(id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing) {
                var source=(DetachedNode)root.network().primitives().get(existing.nodeKey());
                if(candidate.points().get(i).equals(evidence.coordinateFrame().toMetric(source.coordinate())))coordinate=source.coordinate();
            }
            if(!(old instanceof DetachedNode node)||!node.coordinate().equals(coordinate))
                after.put(key,new DetachedNode(key,coordinate,tags,false,true));
            replacement.add(key);
        }
        var whole=new ArrayList<>(way.nodeKeys().subList(0,parent.originalRange().firstIndex()));whole.addAll(replacement);
        whole.addAll(way.nodeKeys().subList(parent.originalRange().lastIndex()+1,way.nodeKeys().size()));
        after.put(way.key(),new DetachedWay(way.key(),whole,way.tags(),false,true));
        return new TopologyDraft(after,replacement,disconnections);
    }
    private static double distanceToPolyline(MetricPoint point,List<MetricPoint> polyline) {
        double best=Double.POSITIVE_INFINITY;
        for(int i=1;i<polyline.size();i++) {
            PostprocessingValidationWork.visit();
            var a=polyline.get(i-1);var b=polyline.get(i);double dx=b.xMeters()-a.xMeters(),dy=b.yMeters()-a.yMeters();
            double length=dx*dx+dy*dy;double f=length==0?0:Math.max(0,Math.min(1,((point.xMeters()-a.xMeters())*dx+(point.yMeters()-a.yMeters())*dy)/length));
            best=Math.min(best,point.distanceTo(new MetricPoint(a.xMeters()+f*dx,a.yMeters()+f*dy)));
        }
        return best;
    }
    /** Final assembly proof, distinct from permission to run inference over a reference. */
    public static void requireFrozenGeometryPreserved(ParentTraceReference parent,Composed candidate) {
        parent.requireComplete();
        var proof=parent.root().frozenGeometryProof().orElseThrow(()->new IllegalArgumentException("Frozen composition authority is unavailable"));
        var way=(DetachedWay)parent.root().network().primitives().get(parent.root().request().selectedWayKey());
        for(var range:proof.ranges()) {
            int first=Math.max(range.firstIndex(),parent.originalRange().firstIndex()),last=Math.min(range.lastIndex(),parent.originalRange().lastIndex());
            if(first>last)continue;
            var expected=cutGeometry(parent.points(),parent.correspondence(),first,last);
            if(!expected.equals(cutGeometry(candidate.points(),candidate.correspondence(),first,last)))
                throw new IllegalArgumentException("Final composition changed a frozen parent curve");
            for(int occurrence=first;occurrence<=last;occurrence++) {
                PostprocessingValidationWork.visit();final int current=occurrence;
                var arcs=candidate.correspondence().stream().peek(value->PostprocessingValidationWork.visit()).filter(a->a.originalOccurrence()==current).toList();
                if(arcs.size()!=1)throw new IllegalArgumentException("Frozen occurrence correspondence is unavailable");
                var arc=arcs.get(0);
                var expectedPosition=parent.occurrencePosition(occurrence);
                if(arc.parentEdge()>=candidate.points().size()-1
                        ||!expectedPosition.equals(ParentTraceReference.interpolate(candidate.points().get(arc.parentEdge()),
                                candidate.points().get(arc.parentEdge()+1),arc.fraction())))
                    throw new IllegalArgumentException("Final composition changed a frozen occurrence assignment");
                var key=way.nodeKeys().get(occurrence);
                // The Precise parent may have removed an ordinary original vertex already.
                // Its exact arc position and curve still have to match. The captured
                // protection contract and Move mode additionally require the original ID.
                boolean needsOriginalId=parent.root().request().geometryMode()==AlignmentMode.MOVE_EXISTING_NODES
                        ||!parent.root().network().closure().removableExistingNodeKeys().contains(key);
                if(needsOriginalId) {
                    int vertex=arc.fraction()==0?arc.parentEdge():arc.fraction()==1?arc.parentEdge()+1:-1;
                    var id=new FinalRoutePointId.ExistingWayNodeOccurrence(way.key(),key,occurrence);
                    if(vertex<0||!id.equals(candidate.pointIds().get(vertex))||!expectedPosition.equals(candidate.assignments().get(id)))
                        throw new IllegalArgumentException("Final composition changed a mandatory frozen node identity");
                }
            }
        }
    }
    private static List<MetricPoint> cutGeometry(List<MetricPoint> points,List<ParentArc> correspondence,int first,int last) {
        var starts=correspondence.stream().peek(meteredValue -> PostprocessingValidationWork.visit()).filter(a->a.originalOccurrence()==first).toList();
        var ends=correspondence.stream().peek(meteredValue -> PostprocessingValidationWork.visit()).filter(a->a.originalOccurrence()==last).toList();
        if(starts.size()!=1||ends.size()!=1)throw new IllegalArgumentException("Frozen geometry cut lineage unavailable");
        var a=starts.get(0);var b=ends.get(0);
        if(a.parentEdge()>=points.size()-1||b.parentEdge()>=points.size()-1||a.parentEdge()+a.fraction()>b.parentEdge()+b.fraction())
            throw new IllegalArgumentException("Frozen geometry cuts are out of order or bounds");
        var result=new ArrayList<MetricPoint>();result.add(ParentTraceReference.interpolate(points.get(a.parentEdge()),points.get(a.parentEdge()+1),a.fraction()));
        if(first==last)return List.copyOf(result);
        for(int i=a.parentEdge()+1;i<points.size();i++){PostprocessingValidationWork.visit();if(i>a.parentEdge()+a.fraction()&&i<b.parentEdge()+b.fraction())result.add(points.get(i));}
        result.add(ParentTraceReference.interpolate(points.get(b.parentEdge()),points.get(b.parentEdge()+1),b.fraction()));return List.copyOf(result);
    }
}

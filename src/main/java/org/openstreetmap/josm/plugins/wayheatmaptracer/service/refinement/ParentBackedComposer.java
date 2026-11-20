package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

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
            for(int i=0;i<points.size();i++)if(!points.get(i).equals(assignments.get(pointIds.get(i))))
                throw new IllegalArgumentException("Composed assignments differ from geometry");
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
        var positions=new TreeMap<Integer,Double>();var spans=new ArrayList<Span>();
        int cursor=parent.originalRange().firstIndex();
        for(var choice:ordered) {
            var reference=choice.reference();var range=reference.originalRange();
            reference.requireRefinementGeometryAuthority();
            if(reference.root()!=parent.root()||!reference.parentStage().equals(parent.parentStage())
                    ||!reference.parentChoice().equals(parent.parentChoice())||range.firstIndex()<cursor
                    ||range.lastIndex()>parent.originalRange().lastIndex()||range.size()<2
                    ||!reference.points().equals(parent.slice(range).points())
                    ||!choice.points().get(0).equals(parent.occurrencePosition(range.firstIndex()))
                    ||!choice.points().get(choice.points().size()-1).equals(parent.occurrencePosition(range.lastIndex())))
                throw new IllegalArgumentException("Replacement overlaps, expands or moves its exact parent cuts");
            if(parent.root().network().closure().editableWayOccurrences().getOrDefault(parent.root().request().selectedWayKey(),List.of()).stream()
                    .noneMatch(r->r.firstIndex()<=range.firstIndex()&&r.lastIndex()>=range.lastIndex()))
                throw new IllegalArgumentException("Replacement exceeds original interval edit authority");
            if(cursor<range.firstIndex())appendParent(parent.slice(new OccurrenceRange(cursor,range.firstIndex())),points,ids,positions,spans);
            append(choice.points(),choice.pointIds(),choice.correspondence(),false,choice.nativeId(),choice.palette(),points,ids,positions,spans);
            cursor=range.lastIndex();
        }
        if(ordered.isEmpty())appendParent(parent,points,ids,positions,spans);
        else if(cursor<parent.originalRange().lastIndex())appendParent(parent.slice(new OccurrenceRange(cursor,parent.originalRange().lastIndex())),points,ids,positions,spans);
        var arcs=new ArrayList<ParentArc>();
        for(var entry:positions.entrySet()) {
            double value=entry.getValue();int edge=(int)Math.floor(value);double fraction=value-edge;
            if(edge==points.size()-1){edge--;fraction=1;}
            arcs.add(new ParentArc(entry.getKey(),edge,fraction));
        }
        // Only permanent original identities are required by a Precise parent with no optional
        // Legacy assignment map. A cut on a removed occurrence never invents node reuse.
        var root=parent.root();var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
        for(var arc:arcs) {
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
            var key=way.nodeKeys().get(occurrence);
            if(!root.network().closure().removableExistingNodeKeys().contains(key)&&!positions.containsKey(occurrence))
                throw new IllegalArgumentException("Missing permanent original occurrence");
        }
        for(int i=0;i<ids.size();i++) {
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
            Map<Integer,Double> positions,List<Span> spans) {
        var generated=new ArrayList<FinalRoutePointId>();for(int i=0;i<part.points().size();i++)generated.add(new FinalRoutePointId.GeneratedCandidatePoint("parent",i));
        append(part.points(),generated,part.correspondence(),true,part.nativeCandidateId(),part.parentPalette(),points,ids,positions,spans);
    }
    private static void append(List<MetricPoint> part,List<FinalRoutePointId> partIds,List<ParentArc> arcs,boolean retained,
            String nativeId,String palette,List<MetricPoint> points,List<FinalRoutePointId> ids,Map<Integer,Double> positions,List<Span> spans) {
        int base=points.isEmpty()?0:points.size()-1;
        if(!points.isEmpty()&&!points.get(base).equals(part.get(0)))throw new IllegalArgumentException("Parent interval join differs");
        for(int i=points.isEmpty()?0:1;i<part.size();i++){points.add(part.get(i));ids.add(partIds.get(i));}
        if(partIds.get(0) instanceof FinalRoutePointId.ExistingWayNodeOccurrence)ids.set(base,partIds.get(0));
        for(var arc:arcs) {
            if(arc.parentEdge()>=part.size()-1)throw new IllegalArgumentException("Interval correspondence exceeds final geometry");
            double value=base+arc.parentEdge()+arc.fraction();var previous=positions.put(arc.originalOccurrence(),value);
            if(previous!=null&&Double.compare(previous,value)!=0)throw new IllegalArgumentException("Join occurrence maps twice");
        }
        spans.add(new Span(base,points.size()-1,retained,nativeId,palette));
    }
    public Assessed assess(ParentTraceReference parent,Composed candidate,EvidenceSnapshot evidence,String field,CancellationProbe cancellation) {
        cancellation.checkpoint();var root=parent.root();
        if(!evidence.coordinateFrame().equals(root.evidence().coordinateFrame())||!evidence.sourceIdentity().equals(root.sourceIdentity()))
            throw new IllegalArgumentException("Final assessment source differs from root");
        var image=ImageCostField.fromEvidence(evidence,field);var protectedAssignments=new LinkedHashMap<Integer,MetricPoint>();
        var extra=new ArrayList<FinalGeometryEvaluator.Finding>();
        if(root.frozenGeometryProof().isEmpty())extra.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.CONTEXT_PROOF_UNAVAILABLE,
            FinalGeometryEvaluator.Severity.HARD_BLOCK,0,candidate.points().size()-1,0));
        else for(var frozen:root.frozenGeometryProof().get().ranges()) {
            int first=Math.max(frozen.firstIndex(),parent.originalRange().firstIndex()),last=Math.min(frozen.lastIndex(),parent.originalRange().lastIndex());
            if(first>last)continue;
            try {
                var expected=first==last?List.of(parent.occurrencePosition(first)):parent.slice(new OccurrenceRange(first,last)).points();
                if(!expected.equals(cutGeometry(candidate.points(),candidate.correspondence(),first,last)))
                    extra.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.PROTECTED_ASSIGNMENT_MISMATCH,FinalGeometryEvaluator.Severity.HARD_BLOCK,0,candidate.points().size()-1,0));
            }catch(IllegalArgumentException|ParentTraceReference.UnavailableException unavailable) {
                extra.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.CONTEXT_PROOF_UNAVAILABLE,FinalGeometryEvaluator.Severity.HARD_BLOCK,0,candidate.points().size()-1,0));
            }
        }
        var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
        var original=new ArrayList<MetricPoint>();
        for(int i=parent.originalRange().firstIndex();i<=parent.originalRange().lastIndex();i++)
            original.add(evidence.coordinateFrame().toMetric(((DetachedNode)root.network().primitives().get(way.nodeKeys().get(i))).coordinate()));
        for(int i=0;i<candidate.points().size();i++) {
            cancellation.checkpoint();var point=candidate.points().get(i);
            boolean outside=!root.network().closure().editRegion().contains(point)||distanceToPolyline(point,original)>root.request().permissions().ordinaryRadiusMeters()+1e-7;
            if(i>0)outside|=!root.network().closure().editRegion().containsSegment(candidate.points().get(i-1),point);
            if(candidate.pointIds().get(i) instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing) {
                var pinned=parent.frozenAnchors().get(existing.originalOccurrenceIndex());
                if(pinned!=null)protectedAssignments.put(i,pinned);
                var captured=evidence.coordinateFrame().toMetric(((DetachedNode)root.network().primitives().get(existing.nodeKey())).coordinate());
                outside|=point.distanceTo(captured)>root.request().permissions().ordinaryRadiusMeters()+1e-7;
            }
            if(outside)extra.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.PROTECTED_ASSIGNMENT_MISMATCH,
                FinalGeometryEvaluator.Severity.HARD_BLOCK,i,i,0));
        }
        var quality=new FinalGeometryEvaluator().evaluate(new FinalGeometryEvaluator.Request(candidate.id(),candidate.points(),candidate.pointIds(),
            image,evidence.resolution().effectivePitchMeters(),protectedAssignments,List.of(),false,false,false,false),cancellation);
        var after=new LinkedHashMap<>(root.network().primitives());var replacement=new ArrayList<PrimitiveKey>();
        var disconnections=SharedNodeDisconnectionPlanner.forRequest(root.request(),root.network());
        for(int i=0;i<candidate.points().size();i++) {
            cancellation.checkpoint();var id=candidate.pointIds().get(i);
            var key=id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing?disconnections.destinationKey(existing):PrimitiveKey.planned(PrimitiveKey.Type.NODE,i+1);
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
        var topology=ModernSingleWayEditPlanAdapter.assessFinalTopology(root.network(),after,root.request(),root.evidence(),evidence,root.request().permissions().ordinaryRadiusMeters());
        for(String finding:topology.findingCodes())extra.add(new FinalGeometryEvaluator.Finding(
            finding.contains("review-required")?FinalGeometryEvaluator.FindingCode.INDEPENDENT_CONTEXT_CONTACT:FinalGeometryEvaluator.FindingCode.PREJUNCTION_CROSSING,
            finding.contains("review-required")?FinalGeometryEvaluator.Severity.REVIEW:FinalGeometryEvaluator.Severity.HARD_BLOCK,0,candidate.points().size()-1,0));
        if(!extra.isEmpty()) {
            var findings=new ArrayList<>(quality.findings());findings.addAll(extra);
            var disposition=findings.stream().anyMatch(f->f.severity()==FinalGeometryEvaluator.Severity.HARD_BLOCK)?FinalGeometryEvaluator.Disposition.HARD_BLOCKED:FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED;
            quality=new FinalGeometryEvaluator.Result(quality.id(),disposition,findings,quality.totalLengthMeters(),quality.directlySupportedLengthMeters(),quality.worstUnsupportedSpanMeters(),quality.meanImageCenterCost(),quality.bendPreservingRoughness());
        }
        var copies=new ArrayList<CopyAssessment>();
        for(int i=0;i<candidate.pointIds().size();i++)if(candidate.pointIds().get(i) instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing
                &&disconnections.replacements().containsKey(existing.originalOccurrenceIndex())) {
            var context=new LinkedHashMap<PrimitiveKey,DetachedWay>();
            for(var ref:root.network().incomingReferrerWatches().getOrDefault(existing.nodeKey(),Set.of()))
                if(!ref.equals(way.key())&&after.get(ref) instanceof DetachedWay other&&!other.deleted())context.put(ref,other);
            var originalAfter=after.get(existing.nodeKey());var destinationAfter=after.get(replacement.get(i));
            copies.add(new CopyAssessment(existing,replacement.get(i),originalAfter instanceof DetachedNode node?Optional.of(node):Optional.empty(),
                destinationAfter instanceof DetachedNode node?Optional.of(node):Optional.empty(),context));
        }
        cancellation.checkpoint();return new Assessed(candidate,quality,topology.findingCodes(),copies);
    }
    private static double distanceToPolyline(MetricPoint point,List<MetricPoint> polyline) {
        double best=Double.POSITIVE_INFINITY;
        for(int i=1;i<polyline.size();i++) {
            var a=polyline.get(i-1);var b=polyline.get(i);double dx=b.xMeters()-a.xMeters(),dy=b.yMeters()-a.yMeters();
            double length=dx*dx+dy*dy;double f=length==0?0:Math.max(0,Math.min(1,((point.xMeters()-a.xMeters())*dx+(point.yMeters()-a.yMeters())*dy)/length));
            best=Math.min(best,point.distanceTo(new MetricPoint(a.xMeters()+f*dx,a.yMeters()+f*dy)));
        }
        return best;
    }
    private static List<MetricPoint> cutGeometry(List<MetricPoint> points,List<ParentArc> correspondence,int first,int last) {
        var starts=correspondence.stream().filter(a->a.originalOccurrence()==first).toList();
        var ends=correspondence.stream().filter(a->a.originalOccurrence()==last).toList();
        if(starts.size()!=1||ends.size()!=1)throw new IllegalArgumentException("Frozen geometry cut lineage unavailable");
        var a=starts.get(0);var b=ends.get(0);
        if(a.parentEdge()>=points.size()-1||b.parentEdge()>=points.size()-1||a.parentEdge()+a.fraction()>b.parentEdge()+b.fraction())
            throw new IllegalArgumentException("Frozen geometry cuts are out of order or bounds");
        var result=new ArrayList<MetricPoint>();result.add(ParentTraceReference.interpolate(points.get(a.parentEdge()),points.get(a.parentEdge()+1),a.fraction()));
        if(first==last)return List.copyOf(result);
        for(int i=a.parentEdge()+1;i<points.size();i++)if(i>a.parentEdge()+a.fraction()&&i<b.parentEdge()+b.fraction())result.add(points.get(i));
        result.add(ParentTraceReference.interpolate(points.get(b.parentEdge()),points.get(b.parentEdge()+1),b.fraction()));return List.copyOf(result);
    }
}

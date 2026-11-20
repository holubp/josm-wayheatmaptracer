package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.JunctionTopologyRules;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.SharedNodeDisconnectionPlanner;

/** Detached original-occurrence fallback inventory, including factual frozen refusals. */
public final class RefinementIntervalPlanner {
    public enum Reason { ELIGIBLE, TOO_FEW_ORIGINAL_OCCURRENCES, MAPPING_UNAVAILABLE, MAPPING_AMBIGUOUS,
        DEGENERATE_REFERENCE, OUTSIDE_EDIT_AUTHORITY, REPEATED_OCCURRENCE, UNKNOWN_TOPOLOGY,
        FROZEN_GEOMETRY_PROOF_UNAVAILABLE, FROZEN_GEOMETRY_SPAN }
    public record Interval(OccurrenceRange originalRange,OccurrenceRange range,Reason reason,
            Optional<ParentTraceReference> reference) {
        public Interval { Objects.requireNonNull(originalRange);Objects.requireNonNull(range);Objects.requireNonNull(reason);Objects.requireNonNull(reference); }
        public boolean eligible() { return reason==Reason.ELIGIBLE; }
    }
    public List<Interval> plan(ParentTraceReference parent) {
        Objects.requireNonNull(parent);
        var root=parent.root();var network=root.network();var way=(DetachedWay)network.primitives().get(root.request().selectedWayKey());
        var selected=parent.originalRange();
        var editable=network.closure().editableWayOccurrences().getOrDefault(way.key(),List.of());
        var proof=root.frozenGeometryProof();var frozen=proof.map(RefinementRootAuthority.FrozenGeometryProof::ranges).orElse(List.of());
        var copied=SharedNodeDisconnectionPlanner.forRequest(root.request(),network).replacements().keySet();
        var ranges=JunctionTopologyRules.ranges(selected.firstIndex(),selected.lastIndex(),
            i->!copied.contains(i)&&hasOtherWay(network,way,way.nodeKeys().get(i))||parent.frozenAnchors().containsKey(i)
                ||editable.stream().anyMatch(r->r.firstIndex()==i||r.lastIndex()==i)
                ||frozen.stream().anyMatch(r->r.firstIndex()==i||r.lastIndex()==i),
            i->copied.contains(i)||safeEndpoint(network,way,way.nodeKeys().get(i)));
        var result=new ArrayList<Interval>();
        var counts=new HashMap<PrimitiveKey,Integer>();for(var key:way.nodeKeys())counts.merge(key,1,Integer::sum);
        for(var range:ranges) {
            var original=new OccurrenceRange(range.originalFirst(),range.originalLast());
            var cut=new OccurrenceRange(range.first(),range.last());Reason reason=Reason.ELIGIBLE;
            if(proof.isEmpty())reason=Reason.FROZEN_GEOMETRY_PROOF_UNAVAILABLE;
            else if(frozen.stream().anyMatch(r->cut.firstIndex()<r.lastIndex()&&cut.lastIndex()>r.firstIndex()))reason=Reason.FROZEN_GEOMETRY_SPAN;
            else if(cut.size()<=2)reason=Reason.TOO_FEW_ORIGINAL_OCCURRENCES;
            else if(network.closure().editableWayOccurrences().getOrDefault(way.key(),List.of()).stream()
                    .noneMatch(r->r.firstIndex()<=cut.firstIndex()&&r.lastIndex()>=cut.lastIndex()))reason=Reason.OUTSIDE_EDIT_AUTHORITY;
            else for(int i=cut.firstIndex();i<=cut.lastIndex();i++) {
                if(counts.get(way.nodeKeys().get(i))!=1){reason=Reason.REPEATED_OCCURRENCE;break;}
                if(parent.missingOccurrences().contains(i)){reason=Reason.MAPPING_UNAVAILABLE;break;}
                if(parent.ambiguousOccurrences().contains(i)){reason=Reason.MAPPING_AMBIGUOUS;break;}
            }
            Optional<ParentTraceReference> reference=Optional.empty();
            if(reason==Reason.ELIGIBLE)try {
                var sliced=parent.slice(cut);sliced.requireComplete();reference=Optional.of(sliced);
            }catch(ParentTraceReference.UnavailableException unavailable) {
                reason=switch(unavailable.reason()) {
                    case MAPPING_UNAVAILABLE->Reason.MAPPING_UNAVAILABLE;
                    case MAPPING_AMBIGUOUS->Reason.MAPPING_AMBIGUOUS;
                    default->Reason.DEGENERATE_REFERENCE;
                };
            }
            result.add(new Interval(original,cut,reason,reference));
        }
        return List.copyOf(result);
    }
    private static boolean hasOtherWay(NetworkSnapshot network,DetachedWay selected,PrimitiveKey node) {
        return network.incomingReferrerWatches().getOrDefault(node,Set.of()).stream()
            .anyMatch(k->k.type()==PrimitiveKey.Type.WAY&&!k.equals(selected.key())
                &&(!(network.primitives().get(k) instanceof DetachedWay way)||!way.deleted()));
    }
    private static boolean safeEndpoint(NetworkSnapshot network,DetachedWay selected,PrimitiveKey node) {
        // Absence of a watch is unknown, never proof of an unshared endpoint.
        if(!network.incomingReferrerWatches().containsKey(node))return false;
        var facts=new ArrayList<JunctionTopologyRules.Referrer>();
        for(var key:network.incomingReferrerWatches().get(node))if(key.type()==PrimitiveKey.Type.WAY) {
            var value=network.primitives().get(key);
            if(value instanceof DetachedWay way&&way.deleted())continue;
            boolean isSelected=key.equals(selected.key());
            boolean complete=value instanceof DetachedWay way&&complete(network,way);
            boolean endpoint=value instanceof DetachedWay way&&Collections.frequency(way.nodeKeys(),node)==1
                &&(way.nodeKeys().get(0).equals(node)||way.nodeKeys().get(way.nodeKeys().size()-1).equals(node));
            facts.add(new JunctionTopologyRules.Referrer(isSelected,value instanceof DetachedWay,complete,endpoint));
        }
        return JunctionTopologyRules.safeEndpoint(facts);
    }
    private static boolean complete(NetworkSnapshot network,DetachedWay way) {
        if(way.deleted())return false;
        MetricPoint previous=null;boolean nondegenerate=false;
        for(var key:way.nodeKeys()) {
            if(!(network.primitives().get(key) instanceof DetachedNode node)||node.deleted())return false;
            var point=new MetricPoint(node.coordinate().longitudeDegrees(),node.coordinate().latitudeDegrees());
            if(previous!=null&&point.distanceTo(previous)>0)nondegenerate=true;
            previous=point;
        }
        return nondegenerate;
    }
}

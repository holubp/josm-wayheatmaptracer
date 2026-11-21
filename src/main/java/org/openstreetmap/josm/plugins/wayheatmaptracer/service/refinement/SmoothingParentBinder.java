package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.SmoothingModels.*;

/** Lazily binds the exact selected unsmoothed parent. Never acquires imagery or runs a native engine. */
public final class SmoothingParentBinder {
    public enum Reason { SELECTED_SOURCE_NOT_RETAINED, STALE_SOURCE, SOURCE_MISMATCH, SOURCE_FRAME_UNAVAILABLE,
        SOURCE_COVERAGE_UNAVAILABLE, FROZEN_PARTITION_UNAVAILABLE, FINAL_SLOT_IDENTITY_UNAVAILABLE, SOURCE_EPOCH_UNAVAILABLE, ORIGINAL_INPUT_HASH_UNAVAILABLE }
    public static final class Unavailable extends IllegalStateException {
        private final Reason reason;
        public Unavailable(Reason reason){super(reason.name().toLowerCase(Locale.ROOT).replace('_','-'));this.reason=reason;}
        public Reason reason(){return reason;}
    }
    public Parent bind(RefinementRootAuthority root,OperationTicket ticket,Candidate candidate,
            RetainedSmoothingSource source,GeometryWorkContext work) {
        Objects.requireNonNull(root);Objects.requireNonNull(ticket);Objects.requireNonNull(candidate);Objects.requireNonNull(work);
        work.visit(GeometryWorkContext.Kind.BIND,1);
        if(source==null)throw new Unavailable(Reason.SELECTED_SOURCE_NOT_RETAINED);
        current(source);
        if(!source.hasFactualInputHash())throw new Unavailable(Reason.ORIGINAL_INPUT_HASH_UNAVAILABLE);
        if(source.acquisitionKind()==EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE&&source.generation().isEmpty()
                ||source.acquisitionKind()==EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER&&source.visibleReceipt().isEmpty())
            throw new Unavailable(Reason.SOURCE_EPOCH_UNAVAILABLE);
        if(ticket.kind()!=StageKind.SMOOTH||ticket.smoothingInput().isEmpty()
                ||!ticket.smoothingInput().get().unsmoothedChoice().equals(candidate.id())||ticket.mode()!=root.request().geometryMode())
            throw new IllegalArgumentException("Smoothing ticket does not bind the exact unsmoothed choice");
        var input=ticket.smoothingInput().get();
        int n=candidate.payload().nativePointCount(candidate.nativeCandidateId(),work);
        long metadata=Math.addExact(candidate.payload().smoothingFactsBytes(candidate.nativeCandidateId(),work),
            Math.addExact(16384L,Math.multiplyExact(8192L,(long)n+root.network().primitives().size())));
        try(var bindingOwner=work.owner().child("smoothing-parent-binding");
            var admitted=work.reserveOn(bindingOwner,"smoothing-parent-metadata",metadata)) {
        var reference=new ParentReferenceBinder().bind(root,input.unsmoothedParent(),candidate);
        reference.requireComplete();
        var request=source.source();
        boolean recordedAlias=candidate.payload().nativeReview().filter(review->review.sources().contains(request)&&review.retainedSources().get(request)==source).isPresent();
        if(candidate.payload().nativeReview().isPresent()&&!recordedAlias)throw new Unavailable(Reason.SELECTED_SOURCE_NOT_RETAINED);
        var mappings=candidate.payload().detectorAttempts().stream().filter(a->RefinementPayload.detectorContains(a,candidate.nativeCandidateId(),work)
            &&a.sourcePalette().equals(request.palette())).map(DetectorAttempt::mappingName).distinct().toList();
        if(!recordedAlias&&!request.palette().equals(reference.parentPalette())||!mappings.contains(request.mapping())
                ||!source.evidence().sourceIdentity().equals(root.sourceIdentity())||!source.evidence().coordinateFrame().equals(root.evidence().coordinateFrame()))
            throw new Unavailable(Reason.SOURCE_MISMATCH);
        candidate.payload().nativeReview().ifPresent(review->{
            if(review.retainedSources().get(request)!=source)throw new Unavailable(Reason.SELECTED_SOURCE_NOT_RETAINED);
        });
        var proof=root.frozenGeometryProof().orElseThrow(()->new Unavailable(Reason.FROZEN_PARTITION_UNAVAILABLE));
            try{source.retainInto(bindingOwner);}catch(IllegalArgumentException missing){throw new Unavailable(Reason.SELECTED_SOURCE_NOT_RETAINED);}
            var facts=candidate.payload().smoothingFacts(candidate.nativeCandidateId(),source,work);
            var fixed=new TreeSet<Integer>();fixed.add(0);fixed.add(n-1);
            var slots=new HashMap<Integer,Integer>();
            for(var arc:reference.correspondence()) {
                work.visit(GeometryWorkContext.Kind.BIND,1);
                int vertex=arc.fraction()==0?arc.parentEdge():arc.fraction()==1?arc.parentEdge()+1:-1;
                if(vertex>=0&&slots.put(vertex,arc.originalOccurrence())!=null)throw new Unavailable(Reason.FINAL_SLOT_IDENTITY_UNAVAILABLE);
                if(reference.frozenAnchors().containsKey(arc.originalOccurrence()))protect(fixed,arc);
            }
            for(var range:proof.ranges()) {
                var left=reference.correspondence().stream().filter(a->a.originalOccurrence()==range.firstIndex()).findFirst().orElseThrow();
                var right=reference.correspondence().stream().filter(a->a.originalOccurrence()==range.lastIndex()).findFirst().orElseThrow();
                int first=left.parentEdge()+(left.fraction()==1?1:0),last=right.parentEdge()+(right.fraction()>0?1:0);
                for(int i=first;i<=last;i++){work.visit(GeometryWorkContext.Kind.BIND,1);fixed.add(i);}
            }
            var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
            var ids=new ArrayList<FinalRoutePointId>();var rows=new ArrayList<FinalControlBasis.Row>();
            for(int i=0;i<n;i++) {
                work.visit(GeometryWorkContext.Kind.BIND,1);rows.add(FinalControlBasis.Row.independent(i));
                Integer occurrence=slots.get(i);
                if(root.request().geometryMode()==AlignmentMode.MOVE_EXISTING_NODES) {
                    occurrence=root.request().selectedRange().firstIndex()+i;
                    if(n!=root.request().selectedRange().size())throw new Unavailable(Reason.FINAL_SLOT_IDENTITY_UNAVAILABLE);
                }
                else if(occurrence!=null&&!fixed.contains(i)
                        &&!candidate.payload().hasExistingAssignment(candidate.nativeCandidateId(),way.nodeKeys().get(occurrence).id()))occurrence=null;
                ids.add(occurrence==null?new FinalRoutePointId.GeneratedCandidatePoint(candidate.immutableIdentity()+"/postprocessing-final-slots-v1",i):
                    new FinalRoutePointId.ExistingWayNodeOccurrence(root.request().selectedWayKey(),way.nodeKeys().get(occurrence),occurrence));
                if(i>0&&!source.evidence().evidenceRegion().containsSegment(reference.points().get(i-1),reference.points().get(i)))
                    throw new Unavailable(Reason.SOURCE_COVERAGE_UNAVAILABLE);
            }
            // Keep the published composed identities; native interval IDs remain separate provenance.
            if(candidate.payload().nativeReview().isPresent()) {
                var factual=candidate.payload().nativeReview().get().finalPointIds().orElseThrow(()->new Unavailable(Reason.FINAL_SLOT_IDENTITY_UNAVAILABLE));
                if(factual.size()!=n||new HashSet<>(factual).size()!=n)throw new Unavailable(Reason.FINAL_SLOT_IDENTITY_UNAVAILABLE);
                ids.clear();ids.addAll(factual);
                for(int i=0;i<n;i++)if(ids.get(i) instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing) {
                    work.visit(GeometryWorkContext.Kind.BIND,1);
                    if(!existing.wayKey().equals(root.request().selectedWayKey())||!existing.nodeKey().equals(way.nodeKeys().get(existing.originalOccurrenceIndex()))
                            ||!Objects.equals(slots.get(i),existing.originalOccurrenceIndex()))throw new Unavailable(Reason.FINAL_SLOT_IDENTITY_UNAVAILABLE);
                }
            }
            var basis=new FinalControlBasis(reference.points(),rows,fixed);
            Frame frame;
            try {frame=new Frame(source.inputByteHash(),source.imageOwnershipIdentity(),request.palette(),request.mapping(),source.intensityMode().name(),
                source.evidence().coordinateFrame(),source.referenceTransform(),source.referenceWidth(),source.referenceHeight(),
                source.evidence().resampling().inputCoordinateConvention(),affine(source));}
            catch(IllegalArgumentException missing){throw new Unavailable(Reason.SOURCE_FRAME_UNAVAILABLE);}
            var image=ImageCostField.fromEvidence(source.evidence(),source.fieldName(),work,bindingOwner);
            String identity=identity(reference,frame,ids,fixed,source.evidence().canonicalHash(work),facts,work);
            var bases=facts.sourceSpans().stream().filter(s->s.source().equals(source.source())&&!s.span().parentRetained()&&s.span().firstVertex()==0&&s.span().lastVertex()==n-1)
                .flatMap(s->s.nativeProvenance().stream()).flatMap(p->p.nativeBasis().stream()).distinct().toList();
            if(bases.size()>1)throw new Unavailable(Reason.FINAL_SLOT_IDENTITY_UNAVAILABLE);
            var parent=new Parent(ticket,reference,frame,bases.stream().findFirst(),basis,identity,ids,image,source.evidence(),facts);
            current(source);admitted.adopt(parent);bindingOwner.transferTo(work.owner());return parent;
        }
    }
    private static void current(RetainedSmoothingSource source){try{source.requireCurrent();}catch(IllegalStateException stale){throw new Unavailable(Reason.STALE_SOURCE);}}
    private static void protect(Set<Integer> fixed,ParentArc arc){fixed.add(arc.parentEdge()+(arc.fraction()==1?1:0));if(arc.fraction()>0&&arc.fraction()<1)fixed.add(arc.parentEdge()+1);}
    private static Optional<VerifiedOriginalAffine> affine(RetainedSmoothingSource source) {
        if(!(source.referenceTransform() instanceof SupportedInputRasterTransform.LocalMetricAffine a))return Optional.empty();
        return Optional.of(SmoothingModels.verifiedOriginalAffine(a));
    }
    private static String identity(ParentTraceReference reference,Frame frame,List<FinalRoutePointId> ids,Set<Integer> fixed,String evidenceHash,NativeFacts facts,GeometryWorkContext work) {
        try(var hash=new SmoothingBindingHasher(work)) {
            var c=frame.coordinateFrame().distortionCertificate();
            hash.value(reference.canonicalIdentity());hash.value(frame.inputHash());hash.value(facts.source().inputHashProtocol());
            hash.value(frame.imageIdentity());hash.value(frame.palette());hash.value(frame.mappingIdentity());hash.value(frame.intensityMode());
            hash.value(frame.coordinateFrame().projectionId());hash.value(frame.coordinateFrame().origin());hash.value(c.method());
            hash.value(c.southWest());hash.value(c.northEast());hash.value(c.maximumRelativeDistanceError());hash.value(c.eastMetersPerRadian());hash.value(c.northMetersPerRadian());
            hash.value(frame.originalInputTransform().kindId());hash.value(frame.originalInputTransform().parameterIdentity());
            hash.value(frame.inputWidth());hash.value(frame.inputHeight());hash.value(frame.inputCoordinateConvention());
            hash.value(frame.originalAffine());hash.value(evidenceHash);
            hash.value(facts.source().acquisitionKind());hash.value(facts.source().generation());
            hash.value(facts.source().visibleReceipt().map(r->r.revision()));hash.value(facts.source().legacyDisposition());
            hash.value(facts.evidence());hash.value(facts.geometryCleanup());hash.value(facts.safetyWarnings());hash.value(facts.parentApplicable());
            hash.value(facts.detectorAttempts());hash.value(facts.quality());hash.value(facts.nativeDisposition());hash.value(facts.junctionFindings());
            hash.value(facts.sourceSpans());hash.value(ids);hash.value(fixed);return hash.finish();
        }
    }
}

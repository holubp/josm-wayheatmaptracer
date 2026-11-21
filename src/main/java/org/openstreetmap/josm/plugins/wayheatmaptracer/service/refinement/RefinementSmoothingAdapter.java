package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.PostprocessingValidationWork;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Guarded existing-Gaussian postprocessing; this API performs no native inference or acquisition. */
public final class RefinementSmoothingAdapter {
    public enum Outcome { CHANGED, UNCHANGED, RETAINED_UNSAFE, RETAINED_RESOURCE, RETAINED_CANCELLED, RETAINED_STALE }
    public record Receipt(String method,String parentIdentity,SmoothingModels.Preset strength,
            SmoothingModels.BaselineReference reference,String inputHash,String representation,Outcome outcome,long workVisits,
            long proposalWorkVisits,int guardTrials,double acceptedScale,String reason,AlignmentMode mode) { }
    /** Keep open while any geometry/plan/receipt alias is used; the caller's ledger owner must remain open. */
    public static final class Result implements AutoCloseable {
        private final List<MetricPoint> points;private final Optional<AlignmentEditPlan> plan;private final Receipt receipt;
        private final Optional<ParentBackedComposer.Assessed> assessment;
        private final AttemptMemoryLedger.Owner owner;
        private final LongScaleNormalFairing.OwnedCaptureRequest proposal;
        private final SmoothingModels.NativeFacts inheritedFacts;
        Result(List<MetricPoint> points,Optional<AlignmentEditPlan> plan,Receipt receipt,
                Optional<ParentBackedComposer.Assessed> assessment,AttemptMemoryLedger.Owner owner,LongScaleNormalFairing.OwnedCaptureRequest proposal,SmoothingModels.NativeFacts inheritedFacts){
            this.points=points;this.plan=plan;this.receipt=receipt;this.assessment=assessment;this.owner=owner;this.proposal=proposal;this.inheritedFacts=inheritedFacts;
        }
        public List<MetricPoint> points(){return points;}
        public Optional<AlignmentEditPlan> plan(){return plan;}
        public Receipt receipt(){return receipt;}
        public Optional<ParentBackedComposer.Assessed> assessment(){return assessment;}
        public SmoothingModels.NativeFacts inheritedFacts(){return inheritedFacts;}
        @Override public void close(){if(proposal!=null)proposal.close();if(owner!=null)owner.close();}
    }
    public Result smooth(SmoothingModels.Parent parent,SmoothingModels.Preset strength,GeometryWorkContext work,CancellationProbe cancellation) {
        Objects.requireNonNull(parent);Objects.requireNonNull(strength);Objects.requireNonNull(work);Objects.requireNonNull(cancellation);
        long started=work.visits(),proposalWork=0;int trials=0;
        LongScaleNormalFairing.OwnedCaptureRequest proposal=null;AttemptMemoryLedger.Owner owner=null;
        try(var scope=PostprocessingValidationWork.open(work)) {
            cancellation.checkpoint();PostprocessingValidationWork.visit();current(parent);
            if(!parent.nativeFacts().parentApplicable()||parent.nativeFacts().quality().filter(q->q.disposition()==FinalGeometryEvaluator.Disposition.HARD_BLOCKED).isPresent()
                    ||parent.nativeFacts().nativeDisposition().filter(d->d==ValidationReport.Disposition.HARD_BLOCKED).isPresent())
                return retained(parent,strength,Outcome.RETAINED_UNSAFE,work.visits()-started,0,0,"parent-hard-failure");
            // Existing adaptRefinement explicitly has no qualified copy-destination materializer.
            if(!parent.reference().root().request().permissions().sharedNodeDisconnectionIntent().isEmpty())
                return retained(parent,strength,Outcome.RETAINED_UNSAFE,work.visits()-started,0,0,"disconnection-materialization-unavailable");
            parent.reference().requireComplete();
            var ref=parent.baselineReference();
            var policy=new CaptureScaleFairingPolicy(parent.frame().inputHash(),parent.frame().imageIdentity(),ref.width(),ref.height(),ref.transform(),
                strength==SmoothingModels.Preset.MEDIUM_1PX?1:2,1);
            proposal=LongScaleNormalFairing.requestCaptureScale(new LongScaleNormalFairing.CaptureRequest(parent.smoothingBasis(),policy,Long.MAX_VALUE,AttemptMemoryLedger.MAX_BYTES),cancellation,work);
            proposalWork=proposal.result().workVisits();
            if(proposal.result().status()==LongScaleNormalFairing.RequestedStatus.REFUSED_RESOURCE)
                return retained(parent,strength,Outcome.RETAINED_RESOURCE,work.visits()-started,proposalWork,0,"proposal-resource");
            if(proposal.result().status()==LongScaleNormalFairing.RequestedStatus.REFUSED_GEOMETRY)
                return retained(parent,strength,Outcome.RETAINED_UNSAFE,work.visits()-started,proposalWork,0,"proposal-geometry");
            var requested=proposal.result().requestedPoints().orElse(parent.reference().points());
            if(requested.equals(parent.reference().points()))return retained(parent,strength,Outcome.UNCHANGED,work.visits()-started,proposalWork,0,"no-distinct-output");
            owner=work.owner().child("guarded-baseline-smoothing");
            parent.nativeFacts().source().retainInto(owner);owner.retain(parent);owner.retain(parent.image());
            long graphUnits=graphUnits(parent,work);
            long outputBytes=Math.addExact(16384L,Math.addExact(Math.multiplyExact(8192L,graphUnits),Math.multiplyExact(2048L,parent.pointIds().size())));
            try(var scratch=work.reserve("baseline-smoothing-assessment-scratch",assessmentBytes(parent,requested,graphUnits,work));
                var retained=work.reserveOn(owner,"baseline-smoothing-plan",outputBytes)) {
                for(double scale:new double[]{1,.5,.25,.125}) {
                    cancellation.checkpoint();PostprocessingValidationWork.visit();current(parent);trials++;
                    var points=scale==1?requested:backtrack(parent.reference().points(),requested,scale,work);
                    if(points.equals(parent.reference().points()))continue;
                    var candidate=composition(parent,points,strength,work);
                    ParentBackedComposer.Assessed assessed;
                    AlignmentEditPlan plan;
                    try {
                        assessed=new ParentBackedComposer().assessPostprocessed(parent.reference(),candidate,parent.evidence(),parent.image(),work,cancellation);
                        if(assessed.quality().disposition()==FinalGeometryEvaluator.Disposition.HARD_BLOCKED)continue;
                        assessed=inheritReview(parent,assessed);
                        plan=new ModernSingleWayEditPlanAdapter().adaptPostprocessed(parent.reference(),assessed,parent.evidence(),work);
                    }
                    catch(IllegalArgumentException unsafe){continue;}
                    cancellation.checkpoint();PostprocessingValidationWork.visit();current(parent);
                    var receipt=receipt(parent,strength,Outcome.CHANGED,work.visits()-started,proposalWork,trials,scale,scale==1?"accepted":"safety-backtrack");
                    var result=new Result(assessed.candidate().points(),Optional.of(plan),receipt,Optional.of(assessed),owner,proposal,parent.nativeFacts());
                    retained.adopt(result);owner=null;proposal=null;return result;
                }
            }
            return retained(parent,strength,Outcome.RETAINED_UNSAFE,work.visits()-started,proposalWork,trials,"whole-output-safety");
        } catch(java.util.concurrent.CancellationException cancelled) {
            return retained(parent,strength,Outcome.RETAINED_CANCELLED,work.visits()-started,proposalWork,trials,"cancelled");
        } catch(GeometryWorkContext.LimitReached|AttemptMemoryLedger.ResourceLimitException|ArithmeticException limited) {
            return retained(parent,strength,Outcome.RETAINED_RESOURCE,work.visits()-started,proposalWork,trials,"caller-resource");
        } catch(StaleSource stale) {
            return retained(parent,strength,Outcome.RETAINED_STALE,work.visits()-started,proposalWork,trials,"stale-selected-source");
        } finally {if(proposal!=null)proposal.close();if(owner!=null)owner.close();}
    }
    private static final class StaleSource extends IllegalStateException { }
    private static void current(SmoothingModels.Parent parent) {
        try {parent.nativeFacts().source().requireCurrent();}
        catch(GeometryWorkContext.LimitReached|java.util.concurrent.CancellationException limited){throw limited;}
        catch(IllegalStateException stale){throw new StaleSource();}
    }
    private static Receipt receipt(SmoothingModels.Parent parent,SmoothingModels.Preset strength,Outcome outcome,long work,long proposal,int trials,double scale,String reason) {
        return new Receipt("existing-gaussian-capture-reference-v1",parent.representationIdentity(),strength,parent.baselineReference(),parent.frame().inputHash(),
            "postprocessing-final-slots-v1",outcome,work,proposal,trials,scale,reason,parent.ticket().mode());
    }
    private static Result retained(SmoothingModels.Parent parent,SmoothingModels.Preset strength,Outcome outcome,long work,long proposal,int trials,String reason) {
        return new Result(parent.reference().points(),Optional.empty(),receipt(parent,strength,outcome,work,proposal,trials,0,reason),Optional.empty(),null,null,parent.nativeFacts());
    }
    private static List<MetricPoint> backtrack(List<MetricPoint> parent,List<MetricPoint> requested,double scale,GeometryWorkContext work) {
        var points=new ArrayList<MetricPoint>(parent.size());
        for(int i=0;i<parent.size();i++){work.visit(GeometryWorkContext.Kind.MAP,1);var a=parent.get(i);var b=requested.get(i);points.add(a.equals(b)?a:new MetricPoint(a.xMeters()+scale*(b.xMeters()-a.xMeters()),a.yMeters()+scale*(b.yMeters()-a.yMeters())));}
        return List.copyOf(points);
    }
    private static ParentBackedComposer.Composed composition(SmoothingModels.Parent parent,List<MetricPoint> points,SmoothingModels.Preset strength,GeometryWorkContext work) {
        var assignments=new LinkedHashMap<FinalRoutePointId,MetricPoint>();
        for(int i=0;i<points.size();i++){work.visit(GeometryWorkContext.Kind.BIND,1);assignments.put(parent.pointIds().get(i),points.get(i));}
        return new ParentBackedComposer.Composed(parent.representationIdentity()+"/smooth/"+strength.name(),points,parent.pointIds(),assignments,parent.reference().correspondence(),
            List.of(new ParentBackedComposer.Span(0,points.size()-1,false,parent.reference().nativeCandidateId(),parent.frame().palette())));
    }
    private static ParentBackedComposer.Assessed inheritReview(SmoothingModels.Parent parent,ParentBackedComposer.Assessed assessed) {
        var facts=parent.nativeFacts();var findings=new ArrayList<>(assessed.quality().findings());
        facts.quality().ifPresent(q->{for(var f:q.findings()){PostprocessingValidationWork.visit();if(f.severity()==FinalGeometryEvaluator.Severity.REVIEW)findings.add(f);}});
        boolean review=facts.nativeDisposition().filter(d->d==ValidationReport.Disposition.REVIEW_REQUIRED).isPresent()||!facts.safetyWarnings().isEmpty()
            ||findings.stream().anyMatch(f->f.severity()==FinalGeometryEvaluator.Severity.REVIEW);
        var q=assessed.quality();
        if(review)q=new FinalGeometryEvaluator.Result(q.id(),FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED,findings,q.totalLengthMeters(),q.directlySupportedLengthMeters(),q.worstUnsupportedSpanMeters(),q.meanImageCenterCost(),q.bendPreservingRoughness());
        return new ParentBackedComposer.Assessed(assessed.candidate(),q,assessed.topologyFindings(),assessed.copies());
    }
    private static long graphUnits(SmoothingModels.Parent parent,GeometryWorkContext work) {
        long units=parent.reference().root().network().primitives().size();
        for(var primitive:parent.reference().root().network().primitives().values()) {
            work.visit(GeometryWorkContext.Kind.BIND,1);
            units=Math.addExact(units,primitive.tags().size());
            if(primitive instanceof DetachedWay way)units=Math.addExact(units,way.nodeKeys().size());
            if(primitive instanceof DetachedRelation relation)units=Math.addExact(units,relation.members().size());
        }
        for(var refs:parent.reference().root().network().incomingReferrerWatches().values()){work.visit(GeometryWorkContext.Kind.BIND,1);units=Math.addExact(units,refs.size());}
        for(var region:List.of(parent.evidence().decisionRegion(),parent.evidence().evidenceRegion(),parent.reference().root().network().closure().editRegion(),parent.reference().root().network().closure().collisionEnvelope()))
            for(var polygon:region.polygons()){work.visit(GeometryWorkContext.Kind.BIND,1);units=Math.addExact(units,polygon.size());}
        return units;
    }
    private static long assessmentBytes(SmoothingModels.Parent parent,List<MetricPoint> requested,long graphUnits,GeometryWorkContext work) {
        double length=0,proposedLength=0;var points=parent.reference().points();
        for(int i=1;i<points.size();i++){work.visit(GeometryWorkContext.Kind.BIND,1);length+=points.get(i-1).distanceTo(points.get(i));proposedLength+=requested.get(i-1).distanceTo(requested.get(i));}
        // Every bounded backtrack is a convex combination; its total length is at most this maximum.
        length=Math.max(length,proposedLength);
        double pitch=parent.evidence().resolution().effectivePitchMeters(),spacing=Math.min(1,pitch/2);
        double profileRows=Math.ceil(2*Math.max(EvidenceModelParameters.defaults().localization().routeProfileHalfWidthMeters(),4*pitch)/(pitch*.25))+3;
        double rows=Math.ceil(length/spacing)+points.size()+8;
        if(!Double.isFinite(rows)||!Double.isFinite(profileRows))throw new GeometryWorkContext.LimitReached();
        long cached=(long)Math.min(65536,rows*profileRows),window=(long)Math.min(65536,Math.ceil(20/spacing)+2L*points.size()+8);
        return Math.addExact(2_097_152L,Math.addExact(Math.multiplyExact(16384L,graphUnits+points.size()),Math.multiplyExact(2048L,cached+window+(long)Math.min(65536,profileRows))));
    }
}

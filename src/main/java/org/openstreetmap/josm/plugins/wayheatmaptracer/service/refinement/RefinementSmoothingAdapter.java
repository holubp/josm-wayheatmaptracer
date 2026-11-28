package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.PostprocessingValidationWork;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Guarded existing-Gaussian postprocessing; this API performs no native inference or acquisition. */
public final class RefinementSmoothingAdapter {
    public enum Outcome { CHANGED, UNCHANGED, RETAINED_UNSAFE, RETAINED_RESOURCE, RETAINED_CANCELLED, RETAINED_STALE }
    public record Receipt(String method,String parentIdentity,SmoothingModels.Preset strength,
            SmoothingModels.BaselineReference reference,String inputHash,String representation,Outcome outcome,long workVisits,
            long proposalWorkVisits,int guardTrials,double acceptedScale,String reason,AlignmentMode mode) { }
    /** Current measured assessment and factual inherited plan reasons remain distinct. */
    public record Assessment(ParentBackedComposer.Assessed evaluated,Optional<ValidationReport> inheritedValidation) {
        public Assessment {Objects.requireNonNull(evaluated);Objects.requireNonNull(inheritedValidation);}
        public ParentBackedComposer.Composed candidate(){return evaluated.candidate();}
        public FinalGeometryEvaluator.Result quality(){return evaluated.quality();}
        public List<String> topologyFindings(){return evaluated.topologyFindings();}
        public List<ParentBackedComposer.CopyAssessment> copies(){return evaluated.copies();}
    }
    /** Keep open while any geometry/plan/receipt alias is used; the caller's ledger owner must remain open. */
    public static final class Result implements AutoCloseable {
        private final List<MetricPoint> points;private final Optional<AlignmentEditPlan> plan;private final Receipt receipt;
        private final Optional<Assessment> assessment;
        private final AttemptMemoryLedger.Owner owner;
        private final LongScaleNormalFairing.OwnedCaptureRequest proposal;
        private final SmoothingModels.NativeFacts inheritedFacts;
        private final long outputBytes;
        Result(List<MetricPoint> points,Optional<AlignmentEditPlan> plan,Receipt receipt,
                Optional<Assessment> assessment,AttemptMemoryLedger.Owner owner,LongScaleNormalFairing.OwnedCaptureRequest proposal,SmoothingModels.NativeFacts inheritedFacts,long outputBytes){
            this.points=points;this.plan=plan;this.receipt=receipt;this.assessment=assessment;this.owner=owner;this.proposal=proposal;this.inheritedFacts=inheritedFacts;this.outputBytes=outputBytes;
        }
        public List<MetricPoint> points(){return points;}
        public Optional<AlignmentEditPlan> plan(){return plan;}
        public Receipt receipt(){return receipt;}
        public Optional<Assessment> assessment(){return assessment;}
        public SmoothingModels.NativeFacts inheritedFacts(){return inheritedFacts;}
        long publicationWorkspaceBytes() {
            // Detached inventory copies, projected point/assignment wrappers and the single payload.
            return Math.addExact(524_288L+1024L*points.size(),outputBytes);
        }
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
            if(requested.equals(parent.reference().points()))return retained(parent,strength,Outcome.UNCHANGED,work.visits()-started,proposalWork,0,proposal.result().reason());
            owner=work.owner().child("guarded-baseline-smoothing");
            parent.nativeFacts().source().retainInto(owner);owner.retain(parent);owner.retain(parent.image());
            long graphUnits=graphUnits(parent,work);
            long graphScratch=Math.addExact(262_144L,Math.multiplyExact(1024L,graphUnits+requested.size()));
            long inheritedFindings=parent.nativeFacts().quality().map(q->(long)q.findings().size()).orElse(0L);
            long inheritedCodes=parent.nativeFacts().nativeValidation().map(v->(long)v.findingCodes().size()).orElse(0L);
            // One composition and one plan. Source graph and images are already leased;
            // include the new primitive/watch maps, preview lists and validation wrappers.
            long outputBytes=Math.addExact(16_384L+128L*(inheritedCodes+inheritedFindings),
                    Math.multiplyExact(1536L,graphUnits+requested.size()));
            try(var retained=work.reserveOn(owner,"baseline-smoothing-plan",outputBytes)) {
                var adjusted=retainParentContactSlots(parent,requested,strength,work,cancellation,graphScratch);
                if(adjusted.equals(parent.reference().points()))
                    return retained(parent,strength,Outcome.RETAINED_UNSAFE,work.visits()-started,proposalWork,0,"contact-slots-retained");
                long assessmentScratch=assessmentBytes(parent,adjusted,work);
                var sourceCells=new ImageCostField.SegmentSupportBudget(Long.MAX_VALUE);
                for(double scale:new double[]{1,.5,.25,.125}) {
                    cancellation.checkpoint();PostprocessingValidationWork.visit();current(parent);trials++;
                    var points=scale==1?adjusted:backtrack(parent.reference().points(),adjusted,scale,work);
                    if(points.equals(parent.reference().points()))continue;
                    var candidate=composition(parent,points,strength,work);
                    Assessment assessed;
                    AlignmentEditPlan plan;
                    try {
                        var evaluated=assessTrial(parent,candidate,work,cancellation,sourceCells,assessmentScratch,graphScratch);
                        if(evaluated.quality().disposition()==FinalGeometryEvaluator.Disposition.HARD_BLOCKED)continue;
                        assessed=inheritReview(parent,evaluated);
                        try(var scratch=work.reserve("baseline-smoothing-plan-scratch",graphScratch);
                            var growing=PostprocessingValidationWork.openGrowingScratch(work)) {
                            plan=new ModernSingleWayEditPlanAdapter().adaptPostprocessed(parent,assessed.evaluated(),work,assessed.inheritedValidation());
                        }
                    }
                    catch(IllegalArgumentException unsafe){continue;}
                    cancellation.checkpoint();PostprocessingValidationWork.visit();current(parent);
                    var receipt=receipt(parent,strength,Outcome.CHANGED,work.visits()-started,proposalWork,trials,scale,scale==1?"accepted":"safety-backtrack");
                    var result=new Result(assessed.candidate().points(),Optional.of(plan),receipt,Optional.of(assessed),owner,proposal,parent.nativeFacts(),outputBytes);
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
    private static List<MetricPoint> retainParentContactSlots(SmoothingModels.Parent parent,List<MetricPoint> requested,
            SmoothingModels.Preset strength,GeometryWorkContext work,CancellationProbe cancellation,long graphScratch) {
        try(var scratch=work.reserve("baseline-smoothing-contact-retention",graphScratch);
            var growing=PostprocessingValidationWork.openGrowingScratch(work)) {
            var original=parent.reference().points();
            var baseline=composition(parent,original,strength,work);
            var retainedSlots=new ParentBackedComposer().retainedSmoothingContactSlots(parent,baseline,cancellation);
            if(retainedSlots.isEmpty())return requested;
            var adjusted=new ArrayList<>(requested);
            for(int index:retainedSlots) {
                work.visit(GeometryWorkContext.Kind.COPY,1);
                adjusted.set(index,original.get(index));
            }
            return List.copyOf(adjusted);
        }
    }
    private static ParentBackedComposer.Assessed assessTrial(SmoothingModels.Parent parent,
            ParentBackedComposer.Composed candidate,GeometryWorkContext work,CancellationProbe cancellation,
            ImageCostField.SegmentSupportBudget sourceCells,long assessmentScratch,long graphScratch) {
        // Evaluation and topology are sequential within the composer. Its common detached
        // original geometry and protected assignments are covered by the graph/point workspace.
        try(var scratch=work.reserve("baseline-smoothing-evaluation-scratch",
                    Math.addExact(Math.max(assessmentScratch,graphScratch),1024L*candidate.points().size()));
            var growing=PostprocessingValidationWork.openGrowingScratch(work);
            var routeSamples=parent.image().memoizeRouteSamples(work)) {
            return new ParentBackedComposer().assessSmoothing(parent,candidate,work,cancellation,sourceCells);
        }
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
        return new Result(parent.reference().points(),Optional.empty(),receipt(parent,strength,outcome,work,proposal,trials,0,reason),Optional.empty(),null,null,parent.nativeFacts(),0);
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
    private static Assessment inheritReview(SmoothingModels.Parent parent,ParentBackedComposer.Assessed assessed) {
        var facts=parent.nativeFacts();var findings=new ArrayList<>(assessed.quality().findings());
        facts.quality().ifPresent(q->{for(var f:q.findings()){PostprocessingValidationWork.visit();if(f.severity()==FinalGeometryEvaluator.Severity.REVIEW)findings.add(f);}});
        boolean review=facts.nativeDisposition().filter(d->d==ValidationReport.Disposition.REVIEW_REQUIRED).isPresent()||!facts.safetyWarnings().isEmpty()
            ||findings.stream().anyMatch(f->f.severity()==FinalGeometryEvaluator.Severity.REVIEW);
        var q=assessed.quality();
        if(review)q=new FinalGeometryEvaluator.Result(q.id(),FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED,findings,q.totalLengthMeters(),q.directlySupportedLengthMeters(),q.worstUnsupportedSpanMeters(),q.meanImageCenterCost(),q.bendPreservingRoughness());
        return new Assessment(new ParentBackedComposer.Assessed(assessed.candidate(),q,assessed.topologyFindings(),assessed.copies()),facts.nativeValidation());
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
    private static long assessmentBytes(SmoothingModels.Parent parent,List<MetricPoint> requested,GeometryWorkContext work) {
        double length=0,proposedLength=0;var points=parent.reference().points();
        for(int i=1;i<points.size();i++){work.visit(GeometryWorkContext.Kind.BIND,1);length+=points.get(i-1).distanceTo(points.get(i));proposedLength+=requested.get(i-1).distanceTo(requested.get(i));}
        // Every bounded backtrack is a convex combination, so its length is at most this maximum.
        return FinalGeometryEvaluator.postprocessingWorkspaceBytes(points.size(),
                Math.max(length,proposedLength),parent.evidence().resolution().effectivePitchMeters());
    }
}

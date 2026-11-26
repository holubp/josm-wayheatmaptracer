package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import java.util.concurrent.CancellationException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.*;

/** Explicit production A/B call and parent-aware finalization; source acquisition belongs to its caller. */
public final class RefinementEngineAdapter {
    /** Cannot be constructed from a supplied hypothesis. Only this adapter seals an actual native call. */
    public static final class NativeExecution {
        private final ParentTraceReference reference;
        private final TraceRequest request;
        private final EvidenceSnapshot evidence;
        private final NetworkSnapshot network;
        private final TraceEngineRun nativeRun;
        private final String field;
        private NativeExecution(ParentTraceReference reference,TraceRequest request,EvidenceSnapshot evidence,
                NetworkSnapshot network,TraceEngineRun nativeRun,String field) {
            this.reference=reference;this.request=request;this.evidence=evidence;this.network=network;this.nativeRun=nativeRun;this.field=field;
        }
        public TraceEngineRun nativeRun(){return nativeRun;}
        public String fieldName(){return field;}
        public void requireBinding(ParentTraceReference parent,TraceRequest derived,EvidenceSnapshot acquired,NetworkSnapshot root) {
            if(reference!=parent||!request.equals(derived)||evidence!=acquired||network!=root)
                throw new IllegalArgumentException("Native execution belongs to a different actual parent/source/request");
            nativeRun.corridorReceipt().ifPresent(r->r.requireBinding(derived,acquired,root,nativeRun.result()));
        }
    }
    public record Run(ParentTraceReference reference,TraceRequest request,Optional<NativeExecution> execution,
            Optional<TraceEngineRun> nativeRun,Optional<ModernTracePipeline.Result> finalized,
            Map<String,List<Double>> nativePointReferenceChainages,Map<String,List<RefinementModels.ParentArc>> finalCorrespondence,
            int nativeCalls,Optional<ParentTraceReference.Unavailable> unavailable,RefinementModels.Outcome outcome,
            Map<String,ModernTracePipeline.FinalProvenance> finalProvenance) {
        public Run(ParentTraceReference reference,TraceRequest request,Optional<NativeExecution> execution,
                Optional<TraceEngineRun> nativeRun,Optional<ModernTracePipeline.Result> finalized,
                Map<String,List<Double>> nativePointReferenceChainages,Map<String,List<RefinementModels.ParentArc>> finalCorrespondence,
                int nativeCalls,Optional<ParentTraceReference.Unavailable> unavailable,RefinementModels.Outcome outcome) {
            this(reference,request,execution,nativeRun,finalized,nativePointReferenceChainages,finalCorrespondence,nativeCalls,unavailable,outcome,Map.of());
        }
        public Run {
            Objects.requireNonNull(reference);Objects.requireNonNull(nativeRun);Objects.requireNonNull(finalized);
            Objects.requireNonNull(unavailable);Objects.requireNonNull(execution);Objects.requireNonNull(outcome);
            nativePointReferenceChainages=Map.copyOf(nativePointReferenceChainages);finalCorrespondence=Map.copyOf(finalCorrespondence);
            finalProvenance=Map.copyOf(finalProvenance);
            if(nativeCalls<0||nativeCalls>1||(nativeRun.isPresent()&&nativeCalls!=1)||execution.isPresent()!=nativeRun.isPresent())
                throw new IllegalArgumentException("Native call availability differs");
        }
        public Optional<TraceWorkUsage> actualWorkAvailability(){return nativeRun.map(TraceEngineRun::usage)
            .or(()->nativeCalls==0?Optional.of(TraceWorkUsage.none()):Optional.empty());}
        public TraceWorkUsage actualWork(){return actualWorkAvailability().orElseThrow(()->new IllegalStateException("Native work unavailable after failed engine receipt"));}
    }
    private final TrackerMode engine;
    private final String field;
    private final Runnable nativeEntry;
    public RefinementEngineAdapter(TrackerMode engine,String field) {this(engine,field,()->{});}
    /** Bounded notification at the native invocation boundary; it must not block or reenter. */
    public RefinementEngineAdapter(TrackerMode engine,String field,Runnable nativeEntry) {
        if(engine!=TrackerMode.CORRIDOR_AWARE&&engine!=TrackerMode.PROBABILISTIC||field==null||field.isBlank())
            throw new IllegalArgumentException("A/B engine and actual scalar field required");
        this.engine=engine;this.field=field;this.nativeEntry=Objects.requireNonNull(nativeEntry);
    }
    public TrackerMode engine(){return engine;}
    /** Convenience for detached consumers; live callers bind their deterministic session operation ticket. */
    public Accounted<Run> run(ParentTraceReference parent,EvidenceSnapshot evidence,TraceBudgets remaining,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        return run(parent,"detached-"+UUID.randomUUID(),evidence,remaining,cancellation,owner);
    }
    public Accounted<Run> run(RefinementModels.OperationTicket ticket,ParentTraceReference parent,EvidenceSnapshot evidence,
            TraceBudgets remaining,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        if(!ticket.parent().equals(parent.parentStage())||!ticket.choice().equals(parent.parentChoice())
                ||ticket.engine()!=engine||ticket.mode()!=parent.root().request().geometryMode())
            throw new IllegalArgumentException("Operation ticket does not pin this exact parent/engine/mode");
        var result=run(parent,ticket.session().value()+":"+ticket.sequence(),evidence,remaining,cancellation,owner);
        var observation=PipelineAdmissionObserver.active();
        if(observation!=null)result.value().nativeRun().ifPresent(nativeRun->observation.nativeResult(ticket,nativeRun));
        return result;
    }
    private Accounted<Run> run(ParentTraceReference unscoped,String operation,EvidenceSnapshot evidence,TraceBudgets remaining,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        Objects.requireNonNull(unscoped);Objects.requireNonNull(evidence);Objects.requireNonNull(owner);Objects.requireNonNull(cancellation);
        ParentTraceReference parent=unscoped;
        var invocation=owner.child("stepwise-refinement");
        TraceRequest request=null;
        AttemptMemoryLedger.Reservation finalization=null;
        NativeExecution execution=null;
        boolean nativeEntered=false;
        try {
            cancellation.checkpoint();
            unscoped.requireRefinementGeometryAuthority();
            // Canonical validation itself clones scalar arrays. Claim its workspace before
            // deriving operation/reference identities or requesting any hashes/profile containers.
            var bindingWorkspace=unscoped.reserveBindingWorkspace(evidence,invocation);
            parent=unscoped.forOperation(operation);
            request=parent.request(engine,evidence,remaining,evidence.resolution().outputRasterPitchMeters());
            if(!evidence.fields().containsKey(field))throw new IllegalArgumentException("Requested scalar field unavailable");
            for(int occurrence:parent.frozenAnchors().keySet())
                if(occurrence!=parent.originalRange().firstIndex()&&occurrence!=parent.originalRange().lastIndex())
                    throw new ParentTraceReference.UnavailableException(ParentTraceReference.Unavailable.FROZEN_INTERIOR);
            // Shared caller memory, reserved BEFORE native entry. Covers native A output plus reconstruction
            // entries, IDs, assignment/lineage maps, evaluator scratch and retained result containers.
            long perRoute=Math.addExact(16384L,Math.multiplyExact(2048L,
                (long)request.profileChainage().cumulativeGroundMeters().size()+request.selectedRange().size()));
            long bytes=Math.multiplyExact(perRoute,request.budgets().maximumDistinctAlternatives());
            finalization=invocation.reserve(bytes);
            Accounted<TraceEngineRun> nativeAccount;
            nativeEntered=true;
            nativeEntry.run();
            if(engine==TrackerMode.CORRIDOR_AWARE) {
                nativeAccount=new CorridorEngineAdapter(field).traceRefinedWithUsage(parent,request,evidence,parent.root().network(),cancellation,invocation,bindingWorkspace);
            } else nativeAccount=new ProbabilisticTraceEngine(field,EvidenceModelParameters.defaults(),
                ProbabilisticTraceEngine.reliabilityPolicyFor(engine)).traceRefinedWithUsage(parent,request,evidence,parent.root().network(),cancellation,invocation,bindingWorkspace);
            execution=new NativeExecution(parent,request,evidence,parent.root().network(),nativeAccount.value(),field);
            var status=nativeAccount.value().result().status();
            if(status==TraceHypothesisSet.Status.CANCELLED||status==TraceHypothesisSet.Status.RESOURCE_LIMIT) {
                var result=incomplete(parent,request,execution,1,status==TraceHypothesisSet.Status.CANCELLED?
                    RefinementModels.Outcome.CANCELLED:RefinementModels.Outcome.RESOURCE_LIMIT,Optional.empty());
                finalization.adopt(result);
                return new Accounted<>(result,invocation);
            }
            var options=new ModernTracePipeline.Options(field,GeometryCleanupConfig.disabled(),"native",0);
            var finalized=new ModernTracePipeline(new CorridorEngineAdapter(field)).finalizeRefined(parent,request,evidence,
                parent.root().network(),options,execution,cancellation);
            var result=new Run(parent,request,Optional.of(execution),Optional.of(nativeAccount.value()),Optional.of(finalized.result()),
                finalized.nativePointReferenceChainages(),finalized.finalCorrespondence(),1,Optional.empty(),
                finalized.result().routes().isEmpty()?RefinementModels.Outcome.NO_USABLE_RESULT:RefinementModels.Outcome.COMPLETE,finalized.finalProvenance());
            finalization.adopt(result);
            return new Accounted<>(result,invocation);
        } catch(ParentTraceReference.UnavailableException unavailable) {
            var result=incomplete(parent,request,execution,nativeEntered?1:0,RefinementModels.Outcome.NO_USABLE_RESULT,Optional.of(unavailable.reason()));
            if(finalization!=null){if(nativeEntered)finalization.adopt(result);else finalization.close();}
            return new Accounted<>(result,invocation);
        } catch(CancellationException cancelled) {
            var result=incomplete(parent,request,execution,nativeEntered?1:0,RefinementModels.Outcome.CANCELLED,Optional.empty());
            if(finalization!=null){if(nativeEntered)finalization.adopt(result);else finalization.close();}
            return new Accounted<>(result,invocation);
        } catch(AttemptMemoryLedger.ResourceLimitException limited) {
            var result=incomplete(parent,request,execution,nativeEntered?1:0,RefinementModels.Outcome.RESOURCE_LIMIT,Optional.empty());
            if(finalization!=null){if(nativeEntered)finalization.adopt(result);else finalization.close();}
            return new Accounted<>(result,invocation);
        } catch(IllegalArgumentException invalid) {
            if(execution!=null) {
                var result=incomplete(parent,request,execution,1,RefinementModels.Outcome.FAILED,Optional.empty());
                if(finalization!=null)finalization.adopt(result);
                return new Accounted<>(result,invocation);
            }
            if(finalization!=null)finalization.close();invocation.close();throw invalid;
        } catch(RuntimeException|Error failure) {
            if(finalization!=null)finalization.close();invocation.close();throw failure;
        }
    }
    private static Run incomplete(ParentTraceReference parent,TraceRequest request,NativeExecution execution,
            int nativeCalls,RefinementModels.Outcome outcome,Optional<ParentTraceReference.Unavailable> unavailable) {
        return new Run(parent,request,Optional.ofNullable(execution),Optional.ofNullable(execution).map(NativeExecution::nativeRun),
            Optional.empty(),Map.of(),Map.of(),nativeCalls,unavailable,outcome);
    }
}

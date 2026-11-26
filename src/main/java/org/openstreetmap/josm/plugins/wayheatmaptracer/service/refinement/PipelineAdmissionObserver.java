package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.OperationTicket;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceEngineRun;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorExecutionReceipt;

/** Opt-in, numeric-only observation of an actual pipeline admission refusal. */
public final class PipelineAdmissionObserver {
    private static final AtomicReference<Scope> ACTIVE=new AtomicReference<>();
    private PipelineAdmissionObserver() { }
    public enum Gate { ASSEMBLED_RETENTION, SCALAR_PREPARATION, ASSESSMENT_SCRATCH, INFERENCE_DEADLINE, FINAL_DEADLINE }
    public enum NativeFamily { ATTEMPT_MEMORY_LIMIT, CORRIDOR_RESOURCE_LIMIT, HARD_GEOMETRY_RESOURCE_LIMIT, UNAVAILABLE }
    /** Enum-only cause and factual work; native explanations and geometry are never retained. */
    public record NativeRejection(OperationTicket ticket,NativeFamily family,Optional<CorridorExecutionReceipt.Phase> phase,
            long peakBytes,long pairVisits,long transitions) { }
    public record GridCounts(int inputWidth,int inputHeight,int outputWidth,int outputHeight,int fields) {
        public GridCounts {
            if(inputWidth<=0||inputHeight<=0||outputWidth<=0||outputHeight<=0||fields<=0)throw new IllegalArgumentException("Positive factual grid counts required");
        }
    }
    public record Rejection(OperationTicket ticket,Gate gate,OptionalLong requestedBytes,OptionalLong currentBytes,
            OptionalLong limitBytes,OptionalLong elapsedNanos,OptionalLong deadlineNanos,int parentPoints,int selectedOccurrences,
            int intervals,OptionalLong assembledVertexBound,OptionalLong rowBound,double rootConfiguredStepMeters,int rootProfileRows,
            int rootRasterWidth,int rootRasterHeight,Optional<GridCounts> currentEvidence) {
        public Rejection {
            Objects.requireNonNull(ticket);Objects.requireNonNull(gate);Objects.requireNonNull(requestedBytes);Objects.requireNonNull(currentBytes);
            Objects.requireNonNull(limitBytes);Objects.requireNonNull(elapsedNanos);Objects.requireNonNull(deadlineNanos);Objects.requireNonNull(assembledVertexBound);
            Objects.requireNonNull(rowBound);Objects.requireNonNull(currentEvidence);
            if(!Double.isFinite(rootConfiguredStepMeters)||rootConfiguredStepMeters<=0)throw new IllegalArgumentException("Finite physical step required");
        }
    }
    /** Installs one diagnostic scope; closing an older scope never clears its replacement. */
    public static Scope openForTesting(Consumer<Rejection> observer) {
        var scope=new Scope(Objects.requireNonNull(observer));ACTIVE.set(scope);return scope;
    }
    static Scope active(){return ACTIVE.get();}
    public static final class Scope implements AutoCloseable {
        private final Consumer<Rejection> observer;
        private final AtomicReference<Rejection> latest=new AtomicReference<>();
        private final AtomicReference<NativeRejection> latestNative=new AtomicReference<>();
        private volatile boolean closed;
        private Scope(Consumer<Rejection> observer){this.observer=observer;}
        /** Missing, replaced, closed and foreign-attempt observations remain unavailable. */
        public Optional<Rejection> latest(OperationTicket ticket) {
            if(closed||ACTIVE.get()!=this||ticket==null)return Optional.empty();
            return Optional.ofNullable(latest.get()).filter(value->value.ticket().equals(ticket));
        }
        public Optional<NativeRejection> latestNative(OperationTicket ticket) {
            if(closed||ACTIVE.get()!=this||ticket==null)return Optional.empty();
            return Optional.ofNullable(latestNative.get()).filter(value->value.ticket().equals(ticket));
        }
        void nativeResult(OperationTicket ticket,TraceEngineRun run) {
            if(closed||ACTIVE.get()!=this||run.result().status()!=org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet.Status.RESOURCE_LIMIT)return;
            try {
                String reason=run.result().explanation();
                NativeFamily family=reason.equals("family=ATTEMPT_MEMORY_LIMIT")?NativeFamily.ATTEMPT_MEMORY_LIMIT
                    :reason.startsWith("family=CORRIDOR_RESOURCE_LIMIT stage=")?NativeFamily.CORRIDOR_RESOURCE_LIMIT
                    :reason.startsWith("family=HARD_GEOMETRY_RESOURCE_LIMIT stage=")?NativeFamily.HARD_GEOMETRY_RESOURCE_LIMIT:NativeFamily.UNAVAILABLE;
                var value=new NativeRejection(ticket,family,run.corridorReceipt().map(CorridorExecutionReceipt::phase),
                    run.peakAdditionalRetainedBytes(),run.usage().pairVisits(),run.usage().transitions());
                if(!closed&&ACTIVE.get()==this)latestNative.set(value);
            }catch(RuntimeException|Error ignored){ /* Observation cannot change native outcome. */ }
        }
        void memory(OperationTicket ticket,Gate gate,ParentTraceReference parent,int intervals,long vertices,
                EvidenceSnapshot evidence,AttemptMemoryLedger.ResourceLimitException limited) {
            report(ticket,gate,parent,intervals,vertices,evidence,OptionalLong.of(limited.requestedBytes()),
                OptionalLong.of(limited.currentBytes()),OptionalLong.of(limited.limitBytes()),OptionalLong.empty(),OptionalLong.empty());
        }
        void deadline(OperationTicket ticket,Gate gate,ParentTraceReference parent,int intervals,long vertices,
                EvidenceSnapshot evidence,long elapsed,long limit) {
            report(ticket,gate,parent,intervals,vertices,evidence,OptionalLong.empty(),OptionalLong.empty(),OptionalLong.empty(),OptionalLong.of(elapsed),OptionalLong.of(limit));
        }
        private void report(OperationTicket ticket,Gate gate,ParentTraceReference parent,int intervals,long vertices,
                EvidenceSnapshot evidence,OptionalLong requested,OptionalLong current,OptionalLong limit,OptionalLong elapsed,OptionalLong deadline) {
            if(closed||ACTIVE.get()!=this)return;
            try {
                var root=parent.root();var request=root.request();var raster=root.evidence().resampling();
                Optional<GridCounts> currentGrid=Optional.empty();
                if(evidence!=null){var grid=evidence.resampling();currentGrid=Optional.of(new GridCounts(grid.inputWidth(),grid.inputHeight(),grid.outputWidth(),grid.outputHeight(),evidence.fields().size()));}
                var rowBound=vertices<0?OptionalLong.empty():OptionalLong.of(vertices-parent.points().size()-parent.originalRange().size()-4L*intervals-16L);
                var value=new Rejection(ticket,gate,requested,current,limit,elapsed,deadline,parent.points().size(),parent.originalRange().size(),intervals,
                    vertices<0?OptionalLong.empty():OptionalLong.of(vertices),rowBound,request.configuredSampleStepMeters(),
                    request.profileChainage().cumulativeGroundMeters().size(),raster.outputWidth(),raster.outputHeight(),currentGrid);
                if(closed||ACTIVE.get()!=this)return;
                latest.set(value);observer.accept(value);
            }catch(RuntimeException|Error ignored){ /* Diagnostics cannot change the actual pipeline outcome. */ }
        }
        @Override public void close(){closed=true;ACTIVE.compareAndSet(this,null);}
    }
}

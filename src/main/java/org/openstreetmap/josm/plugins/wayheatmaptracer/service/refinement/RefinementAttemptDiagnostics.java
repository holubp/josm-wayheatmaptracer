package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorResourceLimitException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyRefinementBootstrap.SmoothingUnavailable;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.ManualJunctionCaptureException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource.RefinementFailed;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.IntervalTraceBatch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Latest current-owned attempt facts. No raster, request, mutable primitive or exception text is retained. */
public record RefinementAttemptDiagnostics(Binding binding, Optional<RefinementOperationReceipt> pipeline,
        Publication publication, Phase phase, Cause cause, int materializationRefusals, boolean materializationComplete,
        Optional<SourcePreparationWork> sourcePreparation) {
    public RefinementAttemptDiagnostics(Binding binding,Optional<RefinementOperationReceipt> pipeline,Publication publication,
            Phase phase,Cause cause,int refusals,boolean complete){this(binding,pipeline,publication,phase,cause,refusals,complete,Optional.empty());}
    /** Compatibility constructor: the supplied count is a completed observation. */
    public RefinementAttemptDiagnostics(Binding binding, Optional<RefinementOperationReceipt> pipeline,
            Publication publication, Phase phase, Cause cause, int materializationRefusals) {
        this(binding,pipeline,publication,phase,cause,materializationRefusals,true);
    }
    // An incomplete count records only stable observations, never a measured final zero.
    public enum Availability { NONE, RUNNING, AVAILABLE, UNAVAILABLE_BUDGET }
    public enum Publication { PUBLISHED, NO_CHILD, CANCELLED, STALE, REFUSED, FAILED }
    public enum Phase { PREPARATION, ACQUISITION, SOURCE_PREPARATION, PIPELINE, MATERIALIZATION, PUBLICATION }
    public enum Cause { NONE, NO_USABLE_RESULT, PLAN_REFUSED, CANCELLED, STALE, RESOURCE_LIMIT, FAILED, PUBLICATION_REFUSED, PRESENTATION_FAILED }
    public enum FailureKind {
        OWNERSHIP_RESOURCE_LIMIT, AUXILIARY_RESOURCE_LIMIT, DEADLINE, CAPTURE_RESOURCE_LIMIT,
        INTERVAL_RESOURCE_LIMIT, CORRIDOR_RESOURCE_LIMIT, SESSION_REFUSAL, SOURCE_UNAVAILABLE,
        MANUAL_JUNCTION, EXECUTOR_REJECTED, CANCELLED, ILLEGAL_ARGUMENT, ILLEGAL_STATE,
        UNSUPPORTED_OPERATION, IO, OTHER_RUNTIME, OTHER_EXCEPTION
    }

    public record MemoryRefusal(long requestedBytes, long currentBytes, long limitBytes) {
        public MemoryRefusal {
            if (requestedBytes < 0 || currentBytes < 0 || limitBytes < 0
                    || currentBytes <= limitBytes && requestedBytes <= limitBytes - currentBytes) {
                throw new IllegalArgumentException("Invalid memory refusal");
            }
        }
    }

    /** Typed result of inspecting one failure. It owns no exception, message, filename or input. */
    public record FailureFacts(FailureKind kind, String firstPluginFrame,
            Optional<RefusalReason> refusalReason, Optional<MemoryRefusal> memoryRefusal) {
        static final String PLUGIN_PREFIX = "org.openstreetmap.josm.plugins.wayheatmaptracer.";

        public FailureFacts {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(firstPluginFrame);
            Objects.requireNonNull(refusalReason);
            Objects.requireNonNull(memoryRefusal);
            if (!firstPluginFrame.isEmpty() && (firstPluginFrame.length() > 300
                    || !firstPluginFrame.matches("[A-Za-z_$][A-Za-z0-9_$]*\\.[A-Za-z_$][A-Za-z0-9_$]*:[0-9]{1,10}"))
                    || (kind == FailureKind.SESSION_REFUSAL) != refusalReason.isPresent()
                    || (kind == FailureKind.OWNERSHIP_RESOURCE_LIMIT) != memoryRefusal.isPresent()) {
                throw new IllegalArgumentException("Invalid bounded failure facts");
            }
        }

        public static FailureFacts capture(Exception failure) {
            var kind = kind(failure);
            Optional<RefusalReason> refusal = failure instanceof RefinementSession.Refused refused
                    ? Optional.of(refused.reason()) : Optional.empty();
            Optional<MemoryRefusal> memory = failure instanceof AttemptMemoryLedger.ResourceLimitException limited
                    ? Optional.of(new MemoryRefusal(limited.requestedBytes(), limited.currentBytes(), limited.limitBytes()))
                    : Optional.empty();
            return new FailureFacts(kind, firstPluginFrame(failure.getStackTrace()), refusal, memory);
        }

        public boolean resourceLimited() {
            return switch (kind) {
                case OWNERSHIP_RESOURCE_LIMIT, AUXILIARY_RESOURCE_LIMIT, DEADLINE, CAPTURE_RESOURCE_LIMIT,
                        INTERVAL_RESOURCE_LIMIT, CORRIDOR_RESOURCE_LIMIT -> true;
                case SESSION_REFUSAL -> switch (refusalReason.orElseThrow()) {
                    case PAYLOAD_LIMIT, STAGE_LIMIT, CANDIDATE_LIMIT -> true;
                    default -> false;
                };
                default -> false;
            };
        }

        private static FailureKind kind(Exception failure) {
            if (failure instanceof AttemptMemoryLedger.ResourceLimitException) return FailureKind.OWNERSHIP_RESOURCE_LIMIT;
            if (failure instanceof GeometryWorkContext.LimitReached limited)
                return limited.reason() == GeometryWorkContext.LimitReached.Reason.DEADLINE
                        ? FailureKind.DEADLINE : FailureKind.AUXILIARY_RESOURCE_LIMIT;
            if (failure instanceof RefinementPipeline.DeadlineReached) return FailureKind.DEADLINE;
            if (failure instanceof RasterEvidenceCapture.ResourceLimitException) return FailureKind.CAPTURE_RESOURCE_LIMIT;
            if (failure instanceof IntervalTraceBatch.ResourceLimitException) return FailureKind.INTERVAL_RESOURCE_LIMIT;
            if (failure instanceof CorridorResourceLimitException) return FailureKind.CORRIDOR_RESOURCE_LIMIT;
            if (failure instanceof RefinementSession.Refused) return FailureKind.SESSION_REFUSAL;
            if (failure instanceof SmoothingUnavailable || failure instanceof RefinementFailed || failure instanceof RefinementPipeline.SourceUnavailable) return FailureKind.SOURCE_UNAVAILABLE;
            if (failure instanceof ManualJunctionCaptureException) return FailureKind.MANUAL_JUNCTION;
            if (failure instanceof RejectedExecutionException) return FailureKind.EXECUTOR_REJECTED;
            if (failure instanceof CancellationException) return FailureKind.CANCELLED;
            if (failure instanceof IllegalArgumentException) return FailureKind.ILLEGAL_ARGUMENT;
            if (failure instanceof IllegalStateException) return FailureKind.ILLEGAL_STATE;
            if (failure instanceof UnsupportedOperationException) return FailureKind.UNSUPPORTED_OPERATION;
            if (failure instanceof java.io.IOException) return FailureKind.IO;
            return failure instanceof RuntimeException ? FailureKind.OTHER_RUNTIME : FailureKind.OTHER_EXCEPTION;
        }

        static String firstPluginFrame(StackTraceElement[] stack) {
            for (int i = 0; i < Math.min(64, stack.length); i++) {
                var frame = stack[i];
                if (frame == null || frame.getLineNumber() <= 0) continue;
                String type = frame.getClassName();
                String method = frame.getMethodName();
                if (type.startsWith(PLUGIN_PREFIX) && type.length() <= 192 && method.length() <= 96
                        && type.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
                        && method.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                    return type.substring(type.lastIndexOf('.') + 1) + "." + method + ":" + frame.getLineNumber();
                }
            }
            return "";
        }

        /** JSON members consist only of fixed enums, a validated symbol, and observed byte counts. */
        public String jsonMembers() {
            return ",\"exceptionKind\":\"" + kind.name() + "\",\"firstPluginFrame\":"
                    + (firstPluginFrame.isEmpty() ? "null" : "\"" + firstPluginFrame + "\"")
                    + ",\"refusalReason\":" + refusalReason.map(reason -> "\"" + reason.name() + "\"").orElse("null")
                    + ",\"memoryRefusal\":" + memoryRefusal.map(memory -> "{\"requestedBytes\":" + memory.requestedBytes()
                    + ",\"currentBytes\":" + memory.currentBytes() + ",\"limitBytes\":" + memory.limitBytes() + "}").orElse("null");
        }
    }

    /** The phase in which acquisition or source preparation actually raised the failure. */
    public record SourceFailure(Phase phase, FailureFacts facts) {
        public SourceFailure {
            Objects.requireNonNull(facts);
            if (phase != Phase.ACQUISITION && phase != Phase.SOURCE_PREPARATION)
                throw new IllegalArgumentException("Invalid source failure phase");
        }
    }

    /** Fixed-size observed auxiliary work. NONE means no source-preparation failure was observed, not that every source ran. */
    public record SourcePreparationWork(Cause cause,long visits,
            Map<org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind,Long> counters,
            Optional<RefinementSourcePlanner.SourceRequest> failedSource,Optional<NativeWork> nativeWork,
            Optional<SourceFacts> sourceFacts,OptionalLong maximumVisits,Optional<SourceFailure> failure) {
        public SourcePreparationWork(Cause cause,long visits,Map<GeometryWorkContext.Kind,Long> counters,
                Optional<RefinementSourcePlanner.SourceRequest> failedSource,Optional<NativeWork> nativeWork,
                Optional<SourceFacts> sourceFacts,OptionalLong maximumVisits) {
            this(cause,visits,counters,failedSource,nativeWork,sourceFacts,maximumVisits,Optional.empty());
        }
        public SourcePreparationWork(Cause cause,long visits,Map<org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind,Long> counters,
                Optional<RefinementSourcePlanner.SourceRequest> failedSource,Optional<NativeWork> nativeWork,Optional<SourceFacts> sourceFacts){this(cause,visits,counters,failedSource,nativeWork,sourceFacts,OptionalLong.empty());}
        public SourcePreparationWork(Cause cause,long visits,Map<org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind,Long> counters,
                Optional<RefinementSourcePlanner.SourceRequest> failedSource,Optional<NativeWork> nativeWork){this(cause,visits,counters,failedSource,nativeWork,Optional.empty());}
        public SourcePreparationWork {
            Objects.requireNonNull(cause);Objects.requireNonNull(failedSource);Objects.requireNonNull(nativeWork);Objects.requireNonNull(sourceFacts);Objects.requireNonNull(maximumVisits);Objects.requireNonNull(failure);counters=Map.copyOf(counters);
            long ceiling=maximumVisits.orElse(8_000_000L);
            if(!Set.of(Cause.NONE,Cause.RESOURCE_LIMIT,Cause.CANCELLED,Cause.STALE,Cause.FAILED).contains(cause)||ceiling<0||ceiling>64_000_000L||visits<0||visits>ceiling
                    ||counters.size()!=org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.values().length
                    // Acquisition can fail before any detector source is attempted; keep it unavailable.
                    ||cause==Cause.NONE&&(failedSource.isPresent()||failure.isPresent()))throw new IllegalArgumentException("Invalid auxiliary source work");
            failure.ifPresent(observed -> {
                if ((observed.phase()==Phase.SOURCE_PREPARATION)!=failedSource.isPresent()
                        || cause==Cause.RESOURCE_LIMIT&&!observed.facts().resourceLimited()) {
                    throw new IllegalArgumentException("Source failure does not match its observed work");
                }
            });
            long sum=0;for(var kind:org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.values()) {var count=counters.get(kind);if(count==null||count<0)throw new IllegalArgumentException("Invalid auxiliary counter");sum=Math.addExact(sum,count);}if(sum!=visits)throw new IllegalArgumentException("Auxiliary counter sum differs");
            if(sourceFacts.isPresent()&&sourceFacts.orElseThrow().captureAuxiliaryVisits().orElse(0)>visits)throw new IllegalArgumentException("Capture work exceeds total source work");
        }
    }
    public enum SourceKind { VISIBLE, MANAGED }
    public enum Coverage { COVERED, NEEDS_CAPTURE, CAPTURE_REQUESTED, RECAPTURED, UNSUPPORTED }
    public enum InputFrameRole { ORIGINAL_INPUT, CAPTURED_INPUT }
    /** Coordinate-free witness of the actual input frame and distinct scalar output grid for this ticket.
     * Capture auxiliary visits are unavailable for original reuse; they never measure opaque paint work.
     */
    public record SourceFacts(OperationTicket ticket,String sourceIdentityHash,SourceKind sourceKind,Coverage coverage,
            String planHash,String frameHash,String inputHash,InputFrameRole frameRole,int frameWidth,int frameHeight,
            int outputGridWidth,int outputGridHeight,long sourceRevision,OptionalLong captureAuxiliaryVisits) {
        public SourceFacts {
            Objects.requireNonNull(ticket);Objects.requireNonNull(sourceKind);Objects.requireNonNull(coverage);Objects.requireNonNull(frameRole);Objects.requireNonNull(captureAuxiliaryVisits);
            for(String value:List.of(sourceIdentityHash,planHash,frameHash,inputHash))if(!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Checksummed source facts required");
            if(frameWidth<1||frameHeight<1||outputGridWidth<1||outputGridHeight<1||sourceRevision<0||captureAuxiliaryVisits.orElse(0)<0||captureAuxiliaryVisits.orElse(0)>preparationCeiling(ticket.kind()))
                throw new IllegalArgumentException("Invalid source frame/work facts");
            if(frameRole==InputFrameRole.CAPTURED_INPUT&&coverage!=Coverage.RECAPTURED)throw new IllegalArgumentException("Captured input requires proven recapture coverage");
            if(coverage==Coverage.COVERED&&captureAuxiliaryVisits.isPresent())throw new IllegalArgumentException("Original reuse has no capture work receipt");
        }
    }
    public record NativeWork(Outcome outcome,Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage> actualWork,OptionalInt nativeCalls) {
        public NativeWork {Objects.requireNonNull(outcome);Objects.requireNonNull(actualWork);Objects.requireNonNull(nativeCalls);if(nativeCalls.orElse(0)<0)throw new IllegalArgumentException("Negative native calls");}
        static NativeWork capture(RefinementPipeline.Result result){int calls=0;boolean known=true;for(var attempt:result.attempts()){var count=attempt.nativeCallAvailability();if(count.isEmpty())known=false;else calls=Math.addExact(calls,count.getAsInt());}return new NativeWork(result.outcome(),result.actualWork(),known?OptionalInt.of(calls):OptionalInt.empty());}
    }
    public record Binding(OperationTicket ticket, StageId rootStage, String rootSourceIdentity,
            String originalNetworkHash, String rootParameterHash, String frozenGeometryIdentity, String parentIdentity) {
        public Binding {
            Objects.requireNonNull(ticket);Objects.requireNonNull(rootStage);
            for(String value:List.of(rootSourceIdentity,originalNetworkHash,rootParameterHash,frozenGeometryIdentity,parentIdentity))
                if(value==null||value.isBlank()||value.length()>4096)throw new IllegalArgumentException("Bounded factual diagnostic identity required");
        }
    }
    public RefinementAttemptDiagnostics {
        Objects.requireNonNull(binding);Objects.requireNonNull(pipeline);Objects.requireNonNull(publication);Objects.requireNonNull(phase);Objects.requireNonNull(cause);Objects.requireNonNull(sourcePreparation);
        if(materializationRefusals<0)throw new IllegalArgumentException("Negative observed refusal count");
        sourcePreparation.ifPresent(work->{if(work.maximumVisits().orElse(8_000_000L)>preparationCeiling(binding.ticket().kind()))throw new IllegalArgumentException("Preparation ceiling does not belong to this operation kind");});
        sourcePreparation.flatMap(SourcePreparationWork::sourceFacts).ifPresent(facts->{
            if(!facts.ticket().equals(binding.ticket())||!facts.sourceIdentityHash().equals(sourceIdentityHash(binding.rootSourceIdentity())))
                throw new IllegalArgumentException("Source facts belong to another attempt");
        });
        pipeline.ifPresent(receipt->{if(!receipt.ticket().equals(binding.ticket())||!receipt.parentIdentity().equals(binding.parentIdentity())
            ||!receipt.originalNetworkHash().equals(binding.originalNetworkHash()))throw new IllegalArgumentException("Attempt receipt binding differs");});
    }
    private static long preparationCeiling(StageKind kind){return kind==StageKind.REFINE_A||kind==StageKind.REFINE_B||kind==StageKind.SMOOTH?64_000_000L:8_000_000L;}
    private static String sourceIdentityHash(String identity) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required SHA-256 is unavailable",impossible);
        }
    }
    /** One fixed bounded slot within the existing attempt ledger; absent admission retains no fabricated receipt. */
    static final class Slot implements AutoCloseable {
        static final long MAX_BYTES=65536;
        private final AttemptMemoryLedger.Owner owner;
        private final AttemptMemoryLedger.Reservation reservation;
        private final long bytes;
        private boolean unavailable;
        private Slot(AttemptMemoryLedger.Owner owner,AttemptMemoryLedger.Reservation reservation,long bytes){this.owner=owner;this.reservation=reservation;this.bytes=bytes;}
        static Optional<Slot> open(AttemptMemoryLedger.Owner root,long available) {
            long bytes=Math.min(MAX_BYTES,available/4);
            if(bytes<8192)return Optional.empty();
            var owner=root.child("latest-refinement-diagnostic");
            try{return Optional.of(new Slot(owner,owner.reserve(bytes),bytes));}
            catch(AttemptMemoryLedger.ResourceLimitException limited){owner.close();return Optional.empty();}
        }
        long bytes(){return bytes;}
        boolean unavailable(){return unavailable;}
        Optional<RefinementOperationReceipt> receipt(Binding binding,ParentTraceReference parent,RefinementPipeline.Result result) {
            // Bound the copy before capture. Everything counted is an existing factual result leaf.
            long estimate=16384+textBytes(binding.rootSourceIdentity())+textBytes(binding.originalNetworkHash())
                +textBytes(binding.rootParameterHash())+textBytes(binding.frozenGeometryIdentity())+textBytes(binding.parentIdentity())+textBytes(binding.ticket().choice().value());
            for(var attempt:result.attempts()) {
                estimate=add(estimate,4096L+textBytes(attempt.reason())+textBytes(attempt.source().palette())+textBytes(attempt.source().mapping()));
                if(attempt.run().isPresent()) {
                    var run=attempt.run().get();
                    if(run.request()!=null)estimate=add(estimate,textBytes(run.request().samplerId())+textBytes(run.request().parameterHash())+textBytes(run.request().evidenceContentHash()));
                    if(run.nativeRun().isPresent())for(var hypothesis:run.nativeRun().get().result().hypotheses())estimate=add(estimate,256L+textBytes(hypothesis.id()));
                }
                if(estimate>bytes){unavailable=true;return Optional.empty();}
            }
            return Optional.of(RefinementOperationReceipt.capture(binding.ticket(),parent,result));
        }
        boolean retain(RefinementAttemptDiagnostics value) {
            return retain(value,Optional.empty(),Optional.empty());
        }
        boolean retain(RefinementAttemptDiagnostics value,Optional<RefinementSmoothingAdapter.Receipt> smoothing,Optional<String> inputProtocol) {
            // A full receipt may exceed the slot while the bounded core/auxiliary summary still fits.
            long total=0;
            for(long charge:RefinementRootAuthority.detachedCharges(value,smoothing,inputProtocol).values())total=add(total,charge);
            if(total>bytes){unavailable=true;return false;}
            reservation.adopt(value);return true;
        }
        private static long textBytes(String text){return text==null?0:128L+2L*text.length();}
        private static long add(long a,long b){return a>Long.MAX_VALUE-b?Long.MAX_VALUE:a+b;}
        public void close(){owner.close();}
    }
}

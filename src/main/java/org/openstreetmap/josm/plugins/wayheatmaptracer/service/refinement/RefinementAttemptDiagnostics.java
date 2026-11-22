package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
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
    /** Fixed-size observed auxiliary work. NONE means no source-preparation failure was observed, not that every source ran. */
    public record SourcePreparationWork(Cause cause,long visits,
            Map<org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind,Long> counters,
            Optional<RefinementSourcePlanner.SourceRequest> failedSource,Optional<NativeWork> nativeWork,Optional<SourceFacts> sourceFacts) {
        public SourcePreparationWork(Cause cause,long visits,Map<org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind,Long> counters,
                Optional<RefinementSourcePlanner.SourceRequest> failedSource,Optional<NativeWork> nativeWork){this(cause,visits,counters,failedSource,nativeWork,Optional.empty());}
        public SourcePreparationWork {
            Objects.requireNonNull(cause);Objects.requireNonNull(failedSource);Objects.requireNonNull(nativeWork);Objects.requireNonNull(sourceFacts);counters=Map.copyOf(counters);
            if(!Set.of(Cause.NONE,Cause.RESOURCE_LIMIT,Cause.CANCELLED,Cause.STALE,Cause.FAILED).contains(cause)||visits<0||visits>8_000_000L
                    ||counters.size()!=org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.values().length
                    ||(cause==Cause.NONE)!=failedSource.isEmpty())throw new IllegalArgumentException("Invalid auxiliary source work");
            long sum=0;for(var kind:org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.values()) {var count=counters.get(kind);if(count==null||count<0)throw new IllegalArgumentException("Invalid auxiliary counter");sum=Math.addExact(sum,count);}if(sum!=visits)throw new IllegalArgumentException("Auxiliary counter sum differs");
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
            if(frameWidth<1||frameHeight<1||outputGridWidth<1||outputGridHeight<1||sourceRevision<0||captureAuxiliaryVisits.orElse(0)<0||captureAuxiliaryVisits.orElse(0)>8_000_000L)
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
        sourcePreparation.flatMap(SourcePreparationWork::sourceFacts).ifPresent(facts->{
            if(!facts.ticket().equals(binding.ticket())||!facts.sourceIdentityHash().equals(sourceIdentityHash(binding.rootSourceIdentity())))
                throw new IllegalArgumentException("Source facts belong to another attempt");
        });
        pipeline.ifPresent(receipt->{if(!receipt.ticket().equals(binding.ticket())||!receipt.parentIdentity().equals(binding.parentIdentity())
            ||!receipt.originalNetworkHash().equals(binding.originalNetworkHash()))throw new IllegalArgumentException("Attempt receipt binding differs");});
    }
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

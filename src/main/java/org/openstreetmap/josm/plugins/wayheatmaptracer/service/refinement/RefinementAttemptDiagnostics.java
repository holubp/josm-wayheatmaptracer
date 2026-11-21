package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Latest current-owned attempt facts. No raster, request, mutable primitive or exception text is retained. */
public record RefinementAttemptDiagnostics(Binding binding, Optional<RefinementOperationReceipt> pipeline,
        Publication publication, Phase phase, Cause cause, int materializationRefusals, boolean materializationComplete) {
    /** Compatibility constructor: the supplied count is a completed observation. */
    public RefinementAttemptDiagnostics(Binding binding, Optional<RefinementOperationReceipt> pipeline,
            Publication publication, Phase phase, Cause cause, int materializationRefusals) {
        this(binding,pipeline,publication,phase,cause,materializationRefusals,true);
    }
    // An incomplete count records only stable observations, never a measured final zero.
    public enum Availability { NONE, RUNNING, AVAILABLE, UNAVAILABLE_BUDGET }
    public enum Publication { PUBLISHED, NO_CHILD, CANCELLED, STALE, REFUSED, FAILED }
    public enum Phase { PREPARATION, ACQUISITION, PIPELINE, MATERIALIZATION, PUBLICATION }
    public enum Cause { NONE, NO_USABLE_RESULT, PLAN_REFUSED, CANCELLED, STALE, RESOURCE_LIMIT, FAILED, PUBLICATION_REFUSED, PRESENTATION_FAILED }
    public record Binding(OperationTicket ticket, StageId rootStage, String rootSourceIdentity,
            String originalNetworkHash, String rootParameterHash, String frozenGeometryIdentity, String parentIdentity) {
        public Binding {
            Objects.requireNonNull(ticket);Objects.requireNonNull(rootStage);
            for(String value:List.of(rootSourceIdentity,originalNetworkHash,rootParameterHash,frozenGeometryIdentity,parentIdentity))
                if(value==null||value.isBlank()||value.length()>4096)throw new IllegalArgumentException("Bounded factual diagnostic identity required");
        }
    }
    public RefinementAttemptDiagnostics {
        Objects.requireNonNull(binding);Objects.requireNonNull(pipeline);Objects.requireNonNull(publication);Objects.requireNonNull(phase);Objects.requireNonNull(cause);
        if(materializationRefusals<0)throw new IllegalArgumentException("Negative observed refusal count");
        pipeline.ifPresent(receipt->{if(!receipt.ticket().equals(binding.ticket())||!receipt.parentIdentity().equals(binding.parentIdentity())
            ||!receipt.originalNetworkHash().equals(binding.originalNetworkHash()))throw new IllegalArgumentException("Attempt receipt binding differs");});
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
            if(unavailable)return false;
            long total=0;
            for(long charge:RefinementRootAuthority.detachedCharges(value).values())total=add(total,charge);
            if(total>bytes){unavailable=true;return false;}
            reservation.adopt(value);return true;
        }
        private static long textBytes(String text){return text==null?0:128L+2L*text.length();}
        private static long add(long a,long b){return a>Long.MAX_VALUE-b?Long.MAX_VALUE:a+b;}
        public void close(){owner.close();}
    }
}

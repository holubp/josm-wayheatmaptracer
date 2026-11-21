package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;

/** Coordinate-free factual summaries copied from the actual Task4 operation, never fabricated executions. */
public record RefinementOperationReceipt(OperationTicket ticket,String parentIdentity,String originalNetworkHash,
        Outcome outcome,List<Attempt> attempts,Optional<TraceWorkUsage> actualWork,boolean truncated) {
    public record Attempt(String palette,String mapping,RefinementPipeline.AttemptKind kind,OccurrenceRange range,
            String reason,Optional<Outcome> outcome,OptionalInt nativeCalls,Optional<TraceWorkUsage> actualWork,
            Optional<String> evidenceHash,Optional<String> parameterHash,Optional<String> samplerId,
            Optional<TraceHypothesisSet.Status> nativeStatus,List<String> nativeCandidateIds) {
        public Attempt {nativeCandidateIds=List.copyOf(nativeCandidateIds);}
    }
    public RefinementOperationReceipt {attempts=List.copyOf(attempts);}
    public static RefinementOperationReceipt capture(OperationTicket ticket,ParentTraceReference parent,RefinementPipeline.Result result) {
        var attempts=result.attempts().stream().map(a->{
            var request=a.run().map(RefinementEngineAdapter.Run::request);
            var nativeRun=a.run().flatMap(RefinementEngineAdapter.Run::nativeRun);
            return new Attempt(a.source().palette(),a.source().mapping(),a.kind(),a.range(),a.reason(),
                a.run().map(RefinementEngineAdapter.Run::outcome),a.nativeCallAvailability(),
                a.run().flatMap(RefinementEngineAdapter.Run::actualWorkAvailability),request.map(TraceRequest::evidenceContentHash),
                request.map(TraceRequest::parameterHash),request.map(TraceRequest::samplerId),
                nativeRun.map(r->r.result().status()),nativeRun.map(r->r.result().hypotheses().stream().map(TraceHypothesis::id).toList()).orElse(List.of()));
        }).toList();
        return new RefinementOperationReceipt(ticket,parent.canonicalIdentity(),parent.root().network().canonicalHash(),result.outcome(),attempts,result.actualWork(),result.truncated());
    }
    public String description() {
        long whole=attempts.stream().filter(a->a.kind()==RefinementPipeline.AttemptKind.WHOLE).count();
        long interval=attempts.stream().filter(a->a.kind()==RefinementPipeline.AttemptKind.INTERVAL).count();
        var unavailable=attempts.stream().filter(a->a.kind()==RefinementPipeline.AttemptKind.UNAVAILABLE||a.kind()==RefinementPipeline.AttemptKind.UNATTEMPTED).map(Attempt::palette).distinct().toList();
        String status=switch(outcome){case COMPLETE->"Complete";case PARTIAL->"Partial result";case RESOURCE_LIMIT->"Resource limit reached";
            case CANCELLED->"Cancelled";case STALE->"Stale source";case NO_USABLE_RESULT->"No usable result";case FAILED->"Failed";};
        return status+". Whole attempts: "+whole+"; interval attempts: "+interval+(truncated?"; truncated":"")
            +(unavailable.isEmpty()?"":"; unavailable palettes: "+String.join(", ",unavailable));
    }
}

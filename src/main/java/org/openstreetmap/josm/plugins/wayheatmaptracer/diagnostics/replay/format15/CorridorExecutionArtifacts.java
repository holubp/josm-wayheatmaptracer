package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Additive checksummed coordinate-free A receipts; frozen input/result encodings are unchanged. */
public final class CorridorExecutionArtifacts {
    private CorridorExecutionArtifacts(){ }
    /** Completed engine work only; no frozen input, inferred final route or edit plan is fabricated. */
    public static Format15Bundle attachCancellation(Format15Bundle bundle, CorridorCancellationDiagnostics summary){
        var csv = new Csv();
        csv.row("attempt", "engine-request", "phase", summary.phase());
        csv.row("attempt", "engine-request", "cause", summary.cause());
        csv.row("attempt", "engine-request", "overall_outcome", summary.overallOutcome());
        csv.row("attempt", "engine-request", "engine_outcome", summary.engineOutcome());
        csv.row("attempt", "engine-request", "final_geometry", summary.finalGeometryAvailability());
        csv.row("attempt", "engine-request", "assignments", summary.assignmentsAvailability());
        csv.row("attempt", "engine-request", "plan", summary.planAvailability());
        csv.row("attempt", "engine-request", "completed_engine_states", summary.evaluatedStates());
        csv.row("attempt", "engine-request", "completed_engine_transitions", summary.evaluatedTransitions());
        append(csv, "engine-request", summary.receipt());
        return attachCsv(bundle, csv.text());
    }
    public static Format15Bundle attach(Format15Bundle bundle,FrozenReplayInput input,
            TraceHypothesisSet result,CorridorExecutionReceipt receipt){
        receipt.requireBinding(input.request(),input.evidence(),input.network(),result);
        var csv=new Csv();append(csv,"engine-request",receipt);
        return attachCsv(bundle,csv.text());
    }
    public static Format15Bundle attachIntervals(Format15Bundle bundle,IntervalTraceBatch batch){
        var csv=new Csv();boolean found=false;int index=0;
        for(var run:batch.runs()){
            if(run.corridorReceipt().isPresent()){
                found=true;var receipt=run.corridorReceipt().orElseThrow();
                receipt.requireBinding(run.request(),batch.evidence(),batch.network(),run.result().inference());
                append(csv,"interval-"+index,receipt);
            }
            index++;
        }
        return found?attachCsv(bundle,csv.text()):bundle;
    }
    private static Format15Bundle attachCsv(Format15Bundle bundle,String csv){
        var artifacts=new LinkedHashMap<>(bundle.artifacts());
        artifacts.put("corridor-execution.csv",Format15Artifact.text("corridor-execution.csv",csv));
        return new Format15Bundle(bundle.buildIdentity(),bundle.sourceIdentityHash(),bundle.parameterHash(),artifacts);
    }
    private static void append(Csv csv,String scope,CorridorExecutionReceipt receipt){
        csv.row("receipt",scope,"scope","CURRENT_ENGINE_REQUEST_ONLY");
        csv.row("receipt",scope,"request_hash",receipt.requestHash());csv.row("receipt",scope,"raw_result_hash",receipt.resultHash());
        csv.row("receipt",scope,"evidence_hash",receipt.evidenceHash());csv.row("receipt",scope,"network_hash",receipt.networkHash());
        csv.row("receipt",scope,"phase",receipt.phase());csv.row("receipt",scope,"certificate_status",receipt.certificateStatus());
        csv.row("receipt",scope,"certificate_identity",receipt.certificateIdentity().orElse("UNAVAILABLE"));
        csv.row("receipt",scope,"source_occurrences",receipt.sourceOccurrences().isPresent()?receipt.sourceOccurrences().getAsInt():"UNAVAILABLE");
        csv.row("receipt",scope,"fixed_source_coordinates",receipt.fixedSourceCoordinates().isPresent()?receipt.fixedSourceCoordinates().getAsInt():"UNAVAILABLE");
        csv.row("receipt",scope,"discovered_tracks",receipt.discoveredTracks().isPresent()?receipt.discoveredTracks().getAsInt():"UNAVAILABLE");
        csv.row("receipt",scope,"visited_tracks",receipt.visitedTracks());
        csv.row("receipt",scope,"track_diagnostics_truncated",receipt.trackDiagnosticsTruncated());
        csv.row("receipt",scope,"retained_heap_peak",receipt.retainedHeapPeakAvailability());
        csv.row("sampling",scope,"availability",receipt.samplingAvailability());
        receipt.sampling().ifPresent(work->{
            csv.row("sampling",scope,"original_anchors",work.originalAnchorCount());csv.row("sampling",scope,"temporary_anchors",work.temporaryAnchorCount());
            csv.row("sampling",scope,"sampled_profiles",work.sampledProfileCount());csv.row("sampling",scope,"retained_profiles",work.retainedProfileCount());
            csv.row("sampling",scope,"scoped_materialization_estimate_bytes",work.materializationEstimateBytes());
            csv.row("sampling",scope,"construction_ns",work.constructionNanos());csv.row("sampling",scope,"sampling_ns",work.samplingNanos());csv.row("sampling",scope,"selection_ns",work.selectionNanos());
        });
        var t=receipt.totals();
        csv.row("totals",scope,"original_generated_states",t.originalStates());csv.row("totals",scope,"retained_unaries",t.retainedUnaryEvaluations());
        csv.row("totals",scope,"bridge_unaries",t.bridgeUnaryEvaluations());csv.row("totals",scope,"admission_contacts",t.admissionContacts());
        csv.row("totals",scope,"admission_pairs",t.admissionPairs());csv.row("totals",scope,"adjacent_objective_entries",t.adjacentObjectiveEntries());
        csv.row("totals",scope,"solver_starts",t.solverStarts());csv.row("totals",scope,"attempted_transitions",t.attemptedTransitions());
        csv.row("totals",scope,"tested_triples",t.testedTriples());csv.row("totals",scope,"rejected_triples",t.rejectedTriples());csv.row("totals",scope,"scored_transitions",t.scoredTransitions());
        for(var entry:new TreeMap<>(receipt.tracks()).entrySet()){
            var key=scope+":"+entry.getKey();var work=entry.getValue();var p=work.preparation();var h=work.hardGeometry();
            csv.row("track",key,"admission",work.admissionStatus());csv.row("track",key,"outcome",p.outcome());
            csv.row("track",key,"original_states",p.originalStates());csv.row("track",key,"retained_unaries",p.retainedUnaryEvaluations());
            csv.row("track",key,"bridge_unaries",p.bridgeUnaryEvaluations());csv.row("track",key,"solver_starts",p.solverStarts());
            csv.row("track",key,"adjacent_objective_entries",work.adjacentObjectiveEntries());csv.row("track",key,"physical_cached_edges",h.metricEdges());
            csv.row("track",key,"prepared_table_estimate_bytes",p.estimatedPreparedTableBytes().isPresent()?p.estimatedPreparedTableBytes().getAsLong():"UNAVAILABLE");
            csv.row("track",key,"metric_cache_reserved_bytes",h.metricCacheReservedBytes());
            csv.row("track",key,"memory_estimate_scope","PREPARED_TABLES_AND_METRIC_CACHE_NOT_WHOLE_HEAP");
            csv.row("track",key,"attempted_transitions",h.attemptedTransitions());csv.row("track",key,"rejected_triples",h.rejectedTriples());csv.row("track",key,"scored_transitions",h.scoredTransitions());
            work.mapping().ifPresent(map->{csv.row("mapping",key,"original_count",map.originalCount());csv.row("mapping",key,"left_original_row",map.leftOriginalRow());csv.row("mapping",key,"right_original_row",map.rightOriginalRow());csv.row("mapping",key,"working_count",map.workingCount());});
        }
        for(var entry:new TreeMap<>(receipt.candidates()).entrySet()){
            var p=entry.getValue();csv.row("candidate",scope+":"+entry.getKey(),"cleanup_evidence",p.cleanupStatus());
            csv.row("candidate",scope+":"+entry.getKey(),"cleanup_unavailable",p.cleanupUnavailable());
        }
    }
    /** One existing artifact budget, with explicit unavailable metadata instead of an unbounded copy. */
    private static final class Csv {
        private final StringBuilder value=new StringBuilder("kind,scope,key,value\n");
        private boolean unavailable;
        void row(Object... fields){
            if(unavailable)return;
            var line=new StringBuilder();
            for(int i=0;i<fields.length;i++){if(i>0)line.append(',');line.append('"').append(String.valueOf(fields[i]).replace("\"","\"\"")).append('"');}
            if(value.length()+line.length()+1>Format15Safety.MAX_ARTIFACT_BYTES/2){unavailable=true;value.setLength(0);return;}
            value.append(line).append('\n');
        }
        String text(){return unavailable?"kind,scope,key,value\nreceipt,engine-request,availability,UNAVAILABLE_DIAGNOSTIC_BUDGET\n":value.toString();}
    }
}

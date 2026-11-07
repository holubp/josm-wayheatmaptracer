package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayCapability;

/** Additive checksummed coordinate-free A receipts; frozen input/result encodings are unchanged. */
public final class CorridorExecutionArtifacts {
    private CorridorExecutionArtifacts(){ }
    /** Completed engine work only; no frozen input, inferred final route or edit plan is fabricated. */
    public static Format15Bundle attachCancellation(Format15Bundle bundle, CorridorCancellationDiagnostics summary){
        var csv = new Csv();
        bindingRows(csv,"UNAVAILABLE","UNAVAILABLE");
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
        String outputBinding=requireSingleBundle(bundle,input,result);
        var csv=new Csv();bindingRows(csv,"VALIDATED",outputBinding);append(csv,"engine-request",receipt);
        return attachCsv(bundle,csv.text());
    }
    public static Format15Bundle attachIntervals(Format15Bundle bundle,IntervalTraceBatch batch){
        if(batch.runs().stream().noneMatch(run->run.corridorReceipt().isPresent()))return bundle;
        boolean nativeBinding=requireIntervalBundle(bundle,batch);
        var csv=new Csv();bindingRows(csv,nativeBinding?"VALIDATED":"UNAVAILABLE",nativeBinding?"VALIDATED":"UNAVAILABLE");
        int index=0;
        for(var run:batch.runs()){
            if(run.corridorReceipt().isPresent()){
                var receipt=run.corridorReceipt().orElseThrow();
                receipt.requireBinding(run.request(),batch.evidence(),batch.network(),run.result().inference());
                append(csv,"interval-"+index,receipt);
            }
            index++;
        }
        return attachCsv(bundle,csv.text());
    }

    /** Work sidecars never grant Apply/replay authority, even after their native binding is checked. */
    private static void bindingRows(Csv csv,String input,String output){
        csv.row("binding","artifact","evidence_role","DIAGNOSTIC_ONLY");
        csv.row("binding","artifact","native_input_binding",input);
        csv.row("binding","artifact","native_result_binding",output);
    }

    private static void require(boolean valid){
        if(!valid)throw new IllegalArgumentException("A execution sidecar destination binding differs");
    }
    private static Map<String,Object> object(Format15Bundle bundle,String name){
        try{return Format15ArchiveReader.parseObject(bundle.artifact(name).bytes(),name);}
        catch(Format15ArchiveException malformed){throw new IllegalArgumentException("A sidecar destination metadata is invalid",malformed);}
    }
    /** An immutable metadata view only; no capabilities or producer attestation are synthesized. */
    private static Format15Archive bindingView(Format15Bundle bundle){
        return new Format15Archive(15,bundle.buildIdentity(),bundle.sourceIdentityHash(),bundle.parameterHash(),
            bundle.artifacts(),new ReplayCapability(15,Set.of(),List.of("diagnostic-binding-view-only")));
    }
    private static void requireOptionalComponentStatus(Format15Bundle bundle,String component,String summary){
        if(!bundle.artifacts().containsKey(summary))return;
        Object status=object(bundle,summary).get("status");
        require(status instanceof String && Set.of("AVAILABLE","UNAVAILABLE","UNAVAILABLE_BUDGET").contains(status)
            &&"AVAILABLE".equals(status)==bundle.artifacts().containsKey(component));
    }
    private static String requireSingleBundle(Format15Bundle bundle,FrozenReplayInput input,TraceHypothesisSet result){
        String hash=input.canonicalHash();var frozen=bundle.artifact("frozen-input.bin");
        require(hash.equals(frozen.sha256())&&hash.equals(bundle.sourceIdentityHash())
            &&input.request().parameterHash().equals(bundle.parameterHash()));
        var identities=object(bundle,"frozen-input-identities.json");
        require(hash.equals(identities.get("inputHash"))&&input.request().parameterHash().equals(identities.get("parameterHash"))
            &&input.evidence().canonicalHash().equals(identities.get("evidenceHash"))&&input.network().canonicalHash().equals(identities.get("networkHash"))
            &&Long.valueOf(FrozenReplayCodec.encodedVersion(frozen.bytes())).equals(identities.get("codecVersion")));
        var trace=object(bundle,"trace-request.json");var frame=object(bundle,"evidence-frame.json");
        require(hash.equals(trace.get("frozenInputHash"))&&input.request().engine().name().equals(trace.get("engine"))
            &&input.options().fieldName().equals(trace.get("fieldName"))&&input.evidence().snapshotId().equals(frame.get("snapshotId"))
            &&input.evidence().canonicalHash().equals(frame.get("contentHash")));
        requireOptionalComponentStatus(bundle,FinalOutputComponentsCodec.ARTIFACT,"private/final-output-components-summary.json");
        try{
            var view=bindingView(bundle);boolean nativeOutput=false;
            var scalar=ScalarReplayExpectation.read(view);
            if(scalar.isPresent()){
                scalar.get().validateBinding(view,input);
                require(scalar.get().matches(new Format15ReplayRunner.Result(ReplayLevel.SCALAR_INFERENCE,
                    input.request().engine(),input.request().engine(),result,List.of(),hash)));
                nativeOutput=true;
            }
            var finalExpected=FinalReplayExpectation.read(view);
            var components=bundle.artifacts().get(FinalOutputComponentsCodec.ARTIFACT);
            if(finalExpected.isPresent()){
                finalExpected.get().validateBinding(view,input);
                require(finalExpected.get().requestedEngine()==result.engine());
                if(components==null)return "UNAVAILABLE";
                var decoded=FinalOutputComponentsCodec.decode(components.bytes(),finalExpected.get().componentsBinding());
                require(finalExpected.get().matches(decoded.output())
                    &&ScalarReplayFingerprint.sha256(result).equals(ScalarReplayFingerprint.sha256(decoded.output().inference())));
                nativeOutput=true;
            }else require(components==null);
            return nativeOutput?"VALIDATED":"UNAVAILABLE";
        }catch(ReplayMismatchException malformed){throw new IllegalArgumentException("A sidecar destination native result differs",malformed);}
    }

    private static Map<String,Long> range(OccurrenceRange value){
        return Map.of("first",(long)value.firstIndex(),"last",(long)value.lastIndex());
    }

    private static boolean requireIntervalBundle(Format15Bundle bundle,IntervalTraceBatch batch){
        requireOptionalComponentStatus(bundle,IntervalFinalOutputComponentsCodec.ARTIFACT,"private/interval-output-components-summary.json");
        var index=object(bundle,Format15ProductionBundleFactory.INTERVAL_INDEX_ARTIFACT);
        String proof=FrozenReplayCodec.partitionProofHash(batch.partition());
        require(Long.valueOf(1).equals(index.get("schema"))&&"INTERVAL_PRODUCTION".equals(index.get("artifactKind"))
            &&batch.fullRequest().parameterHash().equals(bundle.parameterHash())&&bundle.parameterHash().equals(index.get("parameterHash"))
            &&batch.network().canonicalHash().equals(index.get("networkHash"))&&batch.evidence().canonicalHash().equals(index.get("evidenceHash"))
            &&batch.network().snapshotId().equals(index.get("networkSnapshotId"))&&batch.evidence().snapshotId().equals(index.get("evidenceSnapshotId"))
            &&proof.equals(index.get("partitionProofHash")));
        try{
            var source=Format15ReplayRunner.sourceReceipt(index.get("sourceReceipt"));
            require(bundle.sourceIdentityHash().equals(Format15ProductionBundleFactory.intervalSourceHash(
                batch.network().canonicalHash(),batch.evidence().canonicalHash(),source)));
        }catch(ReplayMismatchException invalid){throw new IllegalArgumentException("A interval source receipt differs",invalid);}
        require(index.get("intervals") instanceof List<?>);
        var intervals=(List<?>)index.get("intervals");require(intervals.size()==batch.runs().size());
        for(int i=0;i<intervals.size();i++){
            require(intervals.get(i) instanceof Map<?,?>);var entry=(Map<?,?>)intervals.get(i);var run=batch.runs().get(i);
            require(Long.valueOf(i).equals(entry.get("intervalIndex"))&&range(run.interval().range()).equals(entry.get("occurrenceRange"))
                &&range(run.interval().traceRange()).equals(entry.get("traceRange")));
            require(entry.get("alternatives") instanceof List<?>);var alternatives=(List<?>)entry.get("alternatives");
            require(alternatives.size()==run.routes().size());
            for(int j=0;j<alternatives.size();j++){
                require(alternatives.get(j) instanceof Map<?,?>);var alternative=(Map<?,?>)alternatives.get(j);
                require(Long.valueOf(j).equals(alternative.get("index"))&&run.routes().get(j).hypothesis().id().equals(alternative.get("identity")));
            }
        }
        var payload=bundle.artifacts().get(FrozenIntervalReplayCodec.ARTIFACT);
        require(index.get("capabilities") instanceof Map<?,?>);
        var capabilities=(Map<?,?>)index.get("capabilities");
        if(payload==null){
            require(index.get("sharedInputHash")==null&&Boolean.FALSE.equals(capabilities.get("STRICT_INTERVAL_PRODUCTION")));
            // Native components require their strict input authority; never infer or invent it.
            require(!bundle.artifacts().containsKey(IntervalFinalOutputComponentsCodec.ARTIFACT));
            return false; // Optional input omitted: this sidecar cannot attest native input/output.
        }
        require(Boolean.TRUE.equals(capabilities.get("STRICT_INTERVAL_PRODUCTION")));
        var frozen=FrozenIntervalReplayCodec.decode(payload.bytes());
        var shared=new FrozenReplayInput(batch.fullRequest(),batch.evidence(),batch.network(),batch.options());
        require(frozen.sharedInputHash().equals(index.get("sharedInputHash"))&&shared.canonicalHash().equals(frozen.sharedInputHash())
            &&shared.canonicalHash().equals(frozen.shared().canonicalHash())&&proof.equals(frozen.partitionProofHash())
            &&batch.authoritySpecification()!=null&&Arrays.equals(FrozenReplayCodec.encodeAuthority(batch.authoritySpecification()),
                FrozenReplayCodec.encodeAuthority(frozen.authority()))&&frozen.runs().size()==batch.runs().size());
        require(proof.equals(FrozenReplayCodec.partitionProofHash(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner.partition(batch.network(),frozen.authority()))));
        for(int i=0;i<batch.runs().size();i++){
            var actual=batch.runs().get(i);var expected=frozen.runs().get(i);
            byte[] request=FrozenReplayCodec.encodeRequestOnly(actual.request());
            require(Arrays.equals(request,FrozenReplayCodec.encodeRequestOnly(expected.request()))
                &&Double.doubleToLongBits(actual.request().profileChainage().sourceOriginGroundMeters())
                    ==Double.doubleToLongBits(expected.request().profileChainage().sourceOriginGroundMeters())
                &&expected.scalarHash().equals(ScalarReplayFingerprint.sha256(actual.result().inference()))
                &&expected.finalHash().equals(FinalReplayFingerprint.sha256(new Format15ReplayRunner.Result(ReplayLevel.FINAL_GEOMETRY,
                    actual.request().engine(),actual.request().engine(),actual.result().inference(),actual.routes(),Format15Safety.sha256(request)))));
        }
        var components=bundle.artifacts().get(IntervalFinalOutputComponentsCodec.ARTIFACT);
        if(components!=null){
            try{IntervalFinalOutputComponentsCodec.decode(components.bytes(),bundle.buildIdentity(),frozen);}
            catch(ReplayMismatchException malformed){throw new IllegalArgumentException("A interval native components differ",malformed);}
        }
        return true;
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
        String text(){return unavailable?"kind,scope,key,value\nbinding,artifact,evidence_role,DIAGNOSTIC_ONLY\nbinding,artifact,native_input_binding,UNAVAILABLE\nbinding,artifact,native_result_binding,UNAVAILABLE\nreceipt,engine-request,availability,UNAVAILABLE_DIAGNOSTIC_BUDGET\n":value.toString();}
    }
}

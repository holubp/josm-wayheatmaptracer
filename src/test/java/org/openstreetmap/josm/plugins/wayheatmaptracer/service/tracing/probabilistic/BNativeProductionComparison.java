package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;
import java.util.concurrent.CancellationException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Fixed public comparison harness, never an alternative inference implementation. */
final class BNativeProductionComparison {
    static final List<String> IDS=List.of("same-trail-three-strong","same-trail-five-strong","switchback-thin-strong","switchback-thin-faint","same-trail-three-separated-strong");
    static final long BYTES=64L*1024*1024,REPORT_BYTES=2L*1024*1024;
    static final String CONTRACT_SHA="46f5de9527f2a876b598e593c78ee5f2836f4d9adc900c85b11842bc53749b8d";
    static final String IDENTITY="CURRENT_NATIVE_PRODUCTION_B_NOT_IDENTIFIED_B0_OR_HISTORICAL_RC12";
    private BNativeProductionComparison() { }
    static void requireCase(String id){if(!IDS.contains(id))throw new IllegalArgumentException("Fixed public comparison case required");}
    static Map<String,Object> policy(){var b=TraceBudgets.defaults();return Map.of("identity",IDENTITY,
            "budgets",List.of(b.maximumStatesPerProfile(),Math.toIntExact(b.maximumPairVisits()),Math.toIntExact(b.maximumTransitions()),b.maximumRawAlternatives(),b.maximumDistinctAlternatives()),
            "parameters",EvidenceModelParameters.defaults().toString(),"reliabilityPolicy",ProbabilisticTraceEngine.reliabilityPolicyVersionFor(TrackerMode.PROBABILISTIC),
            "stateSamplingPolicy",ProbabilisticTraceEngine.stateSamplingPolicyVersionFor(TrackerMode.PROBABILISTIC));}
    static CancellationProbe deadline(LongSupplier clock,CancellationProbe delegate){Objects.requireNonNull(clock);Objects.requireNonNull(delegate);long start=clock.getAsLong();
        return ()->delegate.cancelled()||clock.getAsLong()-start>=300_000_000_000L;}
    static Map<String,Object> contract() throws Exception {try(var stream=BNativeProductionComparison.class.getResourceAsStream("/b-observed-gap/native-production-comparison.json")){
        if(stream==null)throw new SecurityException("Comparison contract absent");byte[] bytes=stream.readNBytes(65537);
        if(bytes.length>65536||!CONTRACT_SHA.equals(BIdentificationReceipt.hash(bytes)))throw new SecurityException("Comparison contract changed");
        var value=BIdentificationReceipt.parse(bytes);if(!IDS.equals(value.get("cases")))throw new SecurityException("Fixed comparison inventory changed");return value;}}
    record Bound(TraceRequest request,NetworkSnapshot network,List<MetricPoint> original,List<MetricPoint> actual,Map<String,Object> binding) { }
    /** Metadata only; points actually consumed by native B are recovered from the detached geographic nodes. */
    static Bound bind(EvidenceSnapshot evidence,List<MetricPoint> original){
        if(evidence==null||original==null||original.size()<2||original.size()>12)throw new IllegalArgumentException("Fixed source required");
        var frame=evidence.coordinateFrame();var primitives=new LinkedHashMap<PrimitiveKey,DetachedPrimitive>();var keys=new ArrayList<PrimitiveKey>();
        double maximumRoundTripError=0;var actual=new ArrayList<MetricPoint>();var geographic=new ArrayList<List<Double>>();var differences=new ArrayList<Map<String,Object>>();
        for(int i=0;i<original.size();i++){var key=PrimitiveKey.existing(PrimitiveKey.Type.NODE,100+i);keys.add(key);var geo=frame.toGeographic(original.get(i));
            primitives.put(key,new DetachedNode(key,geo,Map.of(),false,false));var point=frame.toMetric(geo);maximumRoundTripError=Math.max(maximumRoundTripError,point.distanceTo(original.get(i)));actual.add(point);geographic.add(List.of(geo.latitudeDegrees(),geo.longitudeDegrees()));
            differences.add(Map.of("originalBits",bits(original.get(i)),"nativeBits",bits(point),"deltaMeters",List.of(point.xMeters()-original.get(i).xMeters(),point.yMeters()-original.get(i).yMeters())));}
        var way=PrimitiveKey.existing(PrimitiveKey.Type.WAY,200);primitives.put(way,new DetachedWay(way,keys,Map.of("highway","path"),false,false));
        var protectedKeys=Set.of(keys.get(0),keys.get(keys.size()-1));
        var closure=new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,"fixed-native-comparison-v1",primitives.keySet(),Set.of(way),Set.of(),protectedKeys,Set.of(),
                Map.of(way,List.of(new OccurrenceRange(0,keys.size()-1))),List.of(),evidence.decisionRegion(),evidence.decisionRegion(),false,true,true,true);
        var network=new NetworkSnapshot("native-comparison-network",SnapshotRole.CAPTURED_BEFORE,"public-synthetic-comparison",1,closure,primitives,V022SnapshotFixtures.closedWorldReferrerWatches(primitives));
        var chainage=new ProbabilisticProfileFactory().profileChainage(actual,1.5);
        if(chainage.cumulativeGroundMeters().size()>81)throw new IllegalArgumentException("Fixed comparison metadata dimension exceeded");
        var parameterHash=BIdentificationReceipt.hash(EvidenceModelParameters.defaults().toString().getBytes(StandardCharsets.UTF_8));
        var request=new TraceRequest(way,new OccurrenceRange(0,keys.size()-1),TrackerMode.PROBABILISTIC,AlignmentMode.PRECISE_SHAPE,RecoveryPermissions.disabled(25),TraceBudgets.defaults(),
                evidence.snapshotId(),evidence.canonicalHash(),network.snapshotId(),network.canonicalHash(),BIdentificationReceipt.hash("native-comparison-fixed-25m-1.5m-v1".getBytes(StandardCharsets.UTF_8)),parameterHash,
                EvidenceModelParameters.profileSamplingPolicyVersion(),1.5,chainage,evidence.resolution());
        var binding=new LinkedHashMap<String,Object>();binding.put("snapshotHash",evidence.canonicalHash());binding.put("networkHash",network.canonicalHash());
        binding.put("maxPhysicalSourceRoundTripErrorMeters",maximumRoundTripError);binding.put("nativeReferenceIdentity","NATIVE_SELECTED_SOURCE_AFTER_GEOGRAPHIC_ROUNDTRIP");
        binding.put("originalReferenceExactEndpointCompatibility",original.get(0).equals(actual.get(0))&&original.get(original.size()-1).equals(actual.get(actual.size()-1)));
        binding.put("originalMetricSource",coordinates(original));binding.put("geographicSource",geographic);binding.put("nativeMetricSource",coordinates(actual));binding.put("roundTrip",differences);
        binding.put("configuredStepMeters",1.5);binding.put("actualRequestedProfileChainage",chainage.cumulativeGroundMeters());binding.put("resolution",evidence.resolution().toString());
        binding.put("coordinateFrame",frame.toString());binding.put("rasterTransform",evidence.transform().toString());binding.put("resampling",evidence.resampling().toString());
        binding.put("parameterHash",parameterHash);binding.put("settingsHash",request.settingsHash());return new Bound(request,network,List.copyOf(original),List.copyOf(actual),binding);
    }
    private static List<String> bits(MetricPoint p){return List.of(Long.toHexString(Double.doubleToRawLongBits(p.xMeters())),Long.toHexString(Double.doubleToRawLongBits(p.yMeters())));}
    private static List<List<Double>> coordinates(List<MetricPoint> points){return points.stream().map(p->List.of(p.xMeters(),p.yMeters())).toList();}
    static Map<String,Object> result(TraceEngineRun run){var set=run.result();var value=new LinkedHashMap<String,Object>();var routes=new ArrayList<Map<String,Object>>();
        for(var h:set.hypotheses()){var route=new LinkedHashMap<String,Object>();route.put("id",h.id());route.put("branchSignature",h.branchSignature());route.put("points",coordinates(h.points()));
            route.put("support",h.support().stream().map(Enum::name).toList());route.put("objective",h.objective());route.put("posteriorProbability",h.posteriorProbability().isPresent()?h.posteriorProbability().getAsDouble():null);
            route.put("quality",set.status()==TraceHypothesisSet.Status.COMPLETE||set.status()==TraceHypothesisSet.Status.AMBIGUOUS?"COMPLETE_NATIVE_ALTERNATIVE_ENGINE_BOUNDARY_ONLY":"PARTIAL_NATIVE_ALTERNATIVE_NOT_QUALIFIED");route.put("diagnostics",new LinkedHashMap<>(h.diagnostics()));routes.add(route);}
        value.put("comparisonBoundary","ENGINE_BOUNDARY_ONLY");value.put("finalPreviewOrApplyQualification",false);value.put("qualityComparisonAvailable",(set.status()==TraceHypothesisSet.Status.COMPLETE||set.status()==TraceHypothesisSet.Status.AMBIGUOUS)&&!routes.isEmpty());
        value.put("routes",routes);value.put("nativeStatus",set.status().name());value.put("nativeExplanation",safeExplanation(set.explanation()));value.put("alternativesTruncated",set.alternativesTruncated());
        value.put("evaluatedStates",set.evaluatedStates());value.put("transitions",run.usage().transitions());value.put("pairVisits",run.usage().pairVisits());
        value.put("rawAlternatives",run.usage().rawAlternatives());value.put("distinctAlternatives",run.usage().distinctAlternatives());value.put("nativeReportedPeakBytes",run.peakAdditionalRetainedBytes());
        value.put("graphHash","UNAVAILABLE_NOT_EXPOSED_BY_NATIVE_API");value.put("workAccounting",set.workAccounting().map(w->{var stages=new ArrayList<Map<String,Object>>();
            for(var s:w.bStages())stages.add(Map.of("executionPolicy",s.executionPolicy(),"forwardBackwardScores",s.forwardBackwardScores(),"kBestScores",s.kBestScores(),
                    "logicalKBestExtensions",s.logicalKBestExtensions(),"refusedScoreAttempts",s.refusedScoreAttempts()));return (Object)Map.of("bStages",stages,"otherEngineTransitions",w.otherEngineTransitions());}).orElse(null));return value;}
    static Map<String,Object> nativeGeometry(List<MetricPoint> original,List<MetricPoint> nativeSource,List<MetricPoint> path,CancellationProbe probe,AttemptMemoryLedger.Owner owner) {
        if(original.size()!=nativeSource.size())throw new IllegalArgumentException("Native source inventory changed");
        double maximum=0;for(int i=0;i<original.size();i++)maximum=Math.max(maximum,original.get(i).distanceTo(nativeSource.get(i)));
        var geometry=new LinkedHashMap<String,Object>(BThinSwitchbackAcceptance.evaluate(nativeSource,path,1,probe,owner).receipt());
        geometry.put("referenceIdentity","NATIVE_SELECTED_SOURCE_AFTER_GEOGRAPHIC_ROUNDTRIP");
        geometry.put("originalReferenceExactEndpointCompatibility",original.get(0).equals(nativeSource.get(0))&&original.get(original.size()-1).equals(nativeSource.get(nativeSource.size()-1)));
        geometry.put("maxPhysicalSourceRoundTripErrorMeters",maximum);geometry.put("originalReferenceGeometry","NOT_RELABELED_OR_REQUALIFIED");return geometry;
    }
    private static String safeExplanation(String s){return s.length()<=512&&s.matches("[A-Za-z0-9 .,;:()_+/=\\-]*")?s:"UNAVAILABLE_UNSAFE_OR_OVERSIZED_NATIVE_EXPLANATION";}
    static String safeCause(RuntimeException failure){return failure instanceof CancellationException?"CANCELLED":(failure instanceof AttemptMemoryLedger.ResourceLimitException||failure instanceof TraceMemoryLimitException)?"RESOURCE_LIMIT":
            failure instanceof ArithmeticException?"NUMERIC_FAILURE":failure instanceof SecurityException?"SECURITY":failure instanceof IllegalArgumentException?"ILLEGAL_ARGUMENT":"RUNTIME_FAILURE";}
    static final class ExportLimit extends IllegalArgumentException {ExportLimit(){super("Fixed native output leaf or inventory cap exceeded");}}
    /** Guard every complete leaf before any publication; rollback only files this transaction owns. */
    static void publish(Path directory,Map<String,Object> leaves) throws Exception {
        Files.createDirectories(directory);if(leaves.size()>12)throw new ExportLimit();var bytes=new LinkedHashMap<String,byte[]>();long total=0;
        for(var entry:leaves.entrySet()){
            if(!entry.getKey().matches("binding\\.json|result\\.json|ownership\\.json|phase\\.json|route-[0-7]\\.json"))throw new ExportLimit();
            byte[] encoded;try{encoded=BIdentificationReceipt.canonical(entry.getValue()).getBytes(StandardCharsets.UTF_8);}catch(IllegalArgumentException failed){throw new ExportLimit();}
            if(encoded.length>65536)throw new ExportLimit();total=Math.addExact(total,encoded.length);if(total>2L*1024*1024*1024)throw new ExportLimit();bytes.put(entry.getKey(),encoded);
            if(Files.exists(directory.resolve(entry.getKey())))throw new IllegalStateException("Native result already published; no retry");}
        var staged=new ArrayList<Path>();var published=new ArrayList<Path>();try{
            for(var entry:bytes.entrySet()){var temporary=directory.resolve(entry.getKey()+".partial");if(Files.exists(temporary))throw new IllegalStateException("Existing owned partial blocks retry");
                staged.add(temporary);Files.write(temporary,entry.getValue(),StandardOpenOption.CREATE_NEW);}
            for(String name:bytes.keySet()){var target=directory.resolve(name);Files.move(directory.resolve(name+".partial"),target,StandardCopyOption.ATOMIC_MOVE);published.add(target);}
        }catch(Exception failure){for(var path:staged)Files.deleteIfExists(path);for(var path:published)Files.deleteIfExists(path);throw failure;}
    }
    private static void phase(Path directory,String id,String name,int calls,int returns)throws Exception {
        Files.createDirectories(directory);var temporary=directory.resolve("phase.json.partial");boolean owned=false;
        byte[] bytes=BIdentificationReceipt.canonical(Map.of("case",id,"phase",name,"nativeInvocations",calls,"nativeReturns",returns,"status","IN_PROGRESS_OR_EXTERNALLY_CUT_OFF",
                "comparisonContractSha256",CONTRACT_SHA)).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>65536)throw new ExportLimit();
        try{Files.createFile(temporary);owned=true;Files.write(temporary,bytes,StandardOpenOption.TRUNCATE_EXISTING);Files.move(temporary,directory.resolve("phase.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{if(owned)Files.deleteIfExists(temporary);}
    }
    static void publishOutcome(Path directory,Map<String,Object> outcome,Map<String,Object> leaves)throws Exception {
    try{publish(directory,leaves);}catch(ExportLimit overflow){
        outcome.put("exportStatus","TERMINAL_EXPORT_OVERFLOW_ALL_ROUTES_UNAVAILABLE");outcome.put("returnedNativeAlternativeCount",outcome.get("distinctAlternatives"));
        outcome.put("routeFiles",List.of());outcome.put("routeDataAvailable",false);outcome.put("qualityComparisonAvailable",false);outcome.put("attemptComplete",false);
        publish(directory,Map.of("result.json",outcome,"ownership.json",leaves.get("ownership.json")));throw overflow;
    }
    }
    static Map<String,Object> execute(String id,Path directory,LongSupplier clock,CancellationProbe cancellation) throws Exception {
        requireCase(id);if(Files.exists(directory.resolve("phase.json")))throw new IllegalStateException("Attempt already started; retry forbidden");var probe=deadline(clock,cancellation);var ledger=new AttemptMemoryLedger(BYTES);var outcome=new LinkedHashMap<String,Object>();var leaves=new LinkedHashMap<String,Object>();
        int calls=0,returns=0;String phase="RESERVE",failure="NONE";boolean invoked=false;var captureBinding=new LinkedHashMap<String,Object>();
        try(var root=ledger.rootOwner();var report=root.reserve(REPORT_BYTES)) {
            //2MiB covers all bounded request/network/roundtrip metadata, <=8*65KiB encoded routes,
            //their maps, <=2 simultaneous131KiB UTF-16 serialization pages and publication bookkeeping.
            phase(directory,id,"RESERVE",0,0);
            try(var sourceOwner=root.child("native-comparison-source");var render=sourceOwner.reserve(32L*1024*1024)) {
                phase="CONTRACT";probe.checkpoint();var contract=contract();
                phase="CAPTURE";phase(directory,id,phase,calls,returns);var evidence=V022BGapMatchedControlsTest.captureNativeComparisonSource(id,captureBinding,probe);
                var expected=(Map<?,?>)((Map<?,?>)contract.get("nativeInputs")).get(id);
                if(!expected.get("rasterSha256").equals(captureBinding.get("rasterSha256"))||!expected.get("validitySha256").equals(captureBinding.get("validitySha256")))throw new SecurityException("Frozen native input changed");
                phase="REQUEST";probe.checkpoint();var bound=bind(evidence,V022BGapMatchedControlsTest.nativeComparisonSource(id));var binding=new LinkedHashMap<String,Object>(bound.binding());
                binding.put("nativeInput",captureBinding);binding.put("case",id);binding.put("policy",policy());binding.put("comparisonContractSha256",CONTRACT_SHA);binding.put("sourceRevision",System.getenv("GITHUB_SHA"));publish(directory,Map.of("binding.json",binding));
                phase="NATIVE_ENGINE";probe.checkpoint();phase(directory,id,phase,1,0);calls++;invoked=true;
                try(var resultOwner=root.child("native-comparison-result")) {
                    var accounted=new ProbabilisticTraceEngine("scalar").traceWithUsage(bound.request(),evidence,bound.network(),probe,resultOwner);
                    try{returns++;outcome.put("nativeStatus",accounted.value().result().status().name());phase="NATIVE_RESULT_COPY";outcome.putAll(result(accounted.value()));
                        var routes=(List<Map<String,Object>>)outcome.get("routes");
                        for(var route:routes)route.put("geometry",Map.of("available",false,"reason","UNAVAILABLE_NOT_EVALUATED"));
                        var status=accounted.value().result().status();
                        if(status==TraceHypothesisSet.Status.COMPLETE||status==TraceHypothesisSet.Status.AMBIGUOUS){phase="NATIVE_GEOMETRY";
                            for(int i=0;i<routes.size();i++)routes.get(i).put("geometry",nativeGeometry(bound.original(),bound.actual(),accounted.value().result().hypotheses().get(i).points(),probe,root));}
                    }finally{accounted.owner().close();}
                }
                phase="COMPLETE";
            } catch(RuntimeException stopped){failure=safeCause(stopped);
                if(stopped instanceof TraceMemoryLimitException memory)outcome.put("controlPlaneMemoryAbort",Map.of("pairVisits",memory.pairVisits(),"transitions",memory.transitions(),
                        "currentBytes",memory.currentBytes(),"peakBytes",memory.peakBytes(),"limitBytes",memory.limitBytes(),"stage",List.of("sampled profiles","profile boundary authority","continuation proposals","orientation descriptor","attempt memory","forward-backward","k-best","materialization").contains(memory.stage())?memory.stage():"UNAVAILABLE_STAGE_TOKEN"));
                if(stopped instanceof AttemptMemoryLedger.ResourceLimitException memory)outcome.put("allocationRefusal",Map.of("requestedBytes",memory.requestedBytes(),"currentBytes",memory.currentBytes(),"limitBytes",memory.limitBytes()));
            }
            outcome.put("case",id);outcome.put("identity",IDENTITY);outcome.put("comparisonContractSha256",CONTRACT_SHA);outcome.put("policy",policy());
            outcome.put("phase",phase);outcome.put("safeCause",failure);outcome.put("nativeInvocations",calls);outcome.put("nativeReturns",returns);
            outcome.put("attemptComplete",invoked);outcome.put("modelQualification",false);outcome.put("sourceRevision",System.getenv("GITHUB_SHA"));
            var rawRoutes=(List<?>)outcome.remove("routes");var names=new ArrayList<String>();if(rawRoutes!=null)for(int i=0;i<rawRoutes.size();i++){String name="route-"+i+".json";names.add(name);leaves.put(name,rawRoutes.get(i));}
            outcome.put("comparisonBoundary","ENGINE_BOUNDARY_ONLY");outcome.put("finalPreviewOrApplyQualification",false);
            outcome.put("qualityComparisonAvailable",returns==1&&failure.equals("NONE")&&rawRoutes!=null&&!rawRoutes.isEmpty()
                    &&List.of("COMPLETE","AMBIGUOUS").contains(outcome.get("nativeStatus")));
            outcome.put("routeFiles",names);outcome.put("routeDataAvailable",rawRoutes!=null);outcome.put("geometryQualification","NATIVE_REFERENCE_COMPARISON_ONLY_NOT_MODEL_QUALIFICATION");
            outcome.put("peakAttemptBytes",ledger.peakBytes());leaves.put("result.json",outcome);
            leaves.put("ownership.json",Map.of("engineAndSourceOwnersClosed",true,"remainingNonReportBytes",ledger.currentBytes()-REPORT_BYTES,"reportBytesStillFundedDuringPublication",REPORT_BYTES,
                    "outerJUnitFinallyRequiresWholeLedgerZero",true));
            publishOutcome(directory,outcome,leaves);
            phase(directory,id,"TERMINAL_"+phase,calls,returns);
        } finally {if(ledger.currentBytes()!=0)throw new IllegalStateException("Native comparison ownership leaked");}
        return outcome;
    }
}

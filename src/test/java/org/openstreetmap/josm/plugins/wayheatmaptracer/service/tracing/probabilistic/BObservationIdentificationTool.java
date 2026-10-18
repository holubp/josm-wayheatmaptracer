package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import java.util.function.LongSupplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationAcceptance.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BObservationRuntimeTelemetry.Phase.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BObservationRuntimeTelemetry.Work.*;

/** Explicit offline identification; a frozen score/SELECT artifact still requires unchanged regression. */
public final class BObservationIdentificationTool {
    private static final long RENDER_BYTES=32L*1024*1024,MAX_MODE_CELLS=8_000_000,MAX_DISK_BYTES=2L*1024*1024*1024;
    // Reviewed opaque renderer bound: source changes require a fresh accounting review.
    private static final String BOUNDED_RENDERER_SHA256="a7e754e04b1b6f631b5060cbf07fca0c3615536fe6baaa3b22941bd0e59fc2bc";
    record RunReceipt(String status, Path summary) { }
    record CandidateDecision(BIdentifiedObservationKernel.Parameters parameters,OptionalDouble selectScore,
            BIdentificationAcceptance.Checks controls) { }
    enum TrainState { UNTESTED, FAILED, FEASIBLE }
    record TrainInputBinding(String sourceHash,String rendererHash,String catalogHash,String recipeHash,
            SourceKey source,String factsHash,String latticeHash) {
        TrainInputBinding {
            for(String hash:new String[]{sourceHash,rendererHash,catalogHash,recipeHash,factsHash,latticeHash})
                if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Frozen TRAIN hashes required");
            Objects.requireNonNull(source,"original TRAIN source");
        }
    }
    record TrainSceneScore(BIdentificationScore.SceneScore baseline,
            Map<BIdentifiedObservationKernel.Parameters,BIdentificationScore.SceneScore> scores,
            Map<BIdentifiedObservationKernel.Parameters,Checks> cheapChecks,Optional<TrainInputBinding> input) { }
    record TrainEvidence(TrainState state,int scoredScenes,int cheapCheckedScenes,int testedGeometryScenes,
            int skippedGeometryScenes,int testedCoupledControls,int skippedRequiredPairs,
            int testedProfileControls,int testedGeometryControls,String reason) { }
    record TrainPass(Map<BIdentifiedObservationKernel.Parameters,Aggregate> scores,Aggregate baseline,
            List<BIdentifiedObservationKernel.Parameters> shortlist,
            Map<BIdentifiedObservationKernel.Parameters,TrainEvidence> evidence) { }
    /** Same orchestration entry used by run(); dummy drivers supply no evaluation authority. */
    interface TrainEvaluator {
        TrainSceneScore score(CaseDefinition definition,List<BIdentifiedObservationKernel.Parameters> parameters) throws Exception;
        TrainReconstruction reconstruct(CaseDefinition definition,BIdentifiedObservationKernel.Parameters parameter,
                TrainInputBinding frozenInput) throws Exception;
        Checks coupled(BIdentifiedObservationKernel.Parameters parameter,List<RequiredPair> required) throws Exception;
    }
    /** One bounded live reconstruction; compare input() before geometry(), and close on every path. */
    interface TrainReconstruction extends AutoCloseable {
        TrainInputBinding input();
        Checks geometry() throws Exception;
        @Override void close();
    }
    /** All scores keep scene order; only complete deterministic feasibility judgments enter the shortlist. */
    static TrainPass trainPassProspective(List<CaseDefinition> definitions,List<BIdentifiedObservationKernel.Parameters> parameters,
            TrainEvaluator evaluator,CancellationProbe cancellation,boolean complete) throws Exception {
        return trainPass(definitions,parameters,evaluator,cancellation,true,complete);
    }
    static TrainPass trainPass(List<CaseDefinition> definitions,List<BIdentifiedObservationKernel.Parameters> parameters,
            TrainEvaluator evaluator,CancellationProbe cancellation) throws Exception {
        return trainPass(definitions,parameters,evaluator,cancellation,false,false);
    }
    private static TrainPass trainPass(List<CaseDefinition> definitions,List<BIdentifiedObservationKernel.Parameters> parameters,
            TrainEvaluator evaluator,CancellationProbe cancellation,boolean prospective,boolean complete) throws Exception {
        if(definitions==null||definitions.size()>512||parameters==null||parameters.isEmpty()||parameters.size()>32
                ||new HashSet<>(parameters).size()!=parameters.size()||evaluator==null||cancellation==null)
            throw new IllegalArgumentException("Bounded complete TRAIN inputs required");
        var originals=definitions.stream().filter(d->d.key().split()==Split.TRAIN).toList();
        if(originals.isEmpty()||originals.stream().map(d->d.key().id()).distinct().count()!=originals.size())
            throw new IllegalArgumentException("Unique original TRAIN cases required");
        var prospectiveOrder=prospective?trainGeometryOrder(originals,true,complete):null;
        var scores=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,Aggregate>();
        var cheap=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,CheckTotals>();
        for(var parameter:parameters){scores.put(parameter,new Aggregate());cheap.put(parameter,new CheckTotals());}
        var baseline=new Aggregate();var frozen=new LinkedHashMap<String,TrainInputBinding>();
        for(var definition:originals) {
            cancellation.checkpoint();var report=Objects.requireNonNull(evaluator.score(definition,parameters));
            if(report.input()==null||report.input().isEmpty())throw new IllegalArgumentException("Missing initial TRAIN binding");
            frozen.put(definition.key().id(),report.input().orElseThrow());baseline.add(definition,report.baseline());
            for(var parameter:parameters) {
                var checks=report.cheapChecks().get(parameter);
                if(checks==null||checks.checks().isEmpty())throw new IllegalArgumentException("Missing required TRAIN profile controls");
                cheap.get(parameter).add(checks);scores.get(parameter).add(definition,Objects.requireNonNull(report.scores().get(parameter)));
            }
        }
        cancellation.checkpoint();
        var ranking=new ArrayList<>(parameters);ranking.removeIf(p->!scores.get(p).finite());
        ranking.sort(Comparator.comparingDouble((BIdentifiedObservationKernel.Parameters p)->scores.get(p).mean().orElseThrow())
                .thenComparingDouble(BIdentifiedObservationKernel.Parameters::gain)
                .thenComparingDouble(BIdentifiedObservationKernel.Parameters::nuisanceMultiplier)
                .thenComparingDouble(BIdentifiedObservationKernel.Parameters::directSpanSlope));
        var required=requiredPairs(originals,Split.TRAIN,cancellation);
        var geometryOrder=prospectiveOrder!=null?prospectiveOrder:trainGeometryOrder(originals,false,false);
        var evidence=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,TrainEvidence>();
        for(var parameter:parameters) {
            boolean finite=scores.get(parameter).finite(),profilePass=cheap.get(parameter).finish().passed();
            evidence.put(parameter,new TrainEvidence(!finite||!profilePass?TrainState.FAILED:TrainState.UNTESTED,
                    originals.size(),originals.size(),0,originals.size(),0,required.size(),
                    controlCount(cheap.get(parameter).finish()),0,!finite?"NONFINITE_TRAIN_SCORE":!profilePass?
                    "REQUIRED_PROFILE_CONTROL_NOT_PASSED":"LOWER_THAN_FOUR_PROVED_FEASIBLE"));
        }
        var shortlist=new ArrayList<BIdentifiedObservationKernel.Parameters>();
        for(var parameter:ranking) {
            cancellation.checkpoint();if(shortlist.size()==4)break;
            if(evidence.get(parameter).state()==TrainState.FAILED)continue;
            int tested=0,geometryControls=0,coupledControls=0,skippedPairs=required.size();boolean passed=true;
            String reason="ALL_REGISTERED_TRAIN_CONTROLS_COMPLETED";
            for(var definition:geometryOrder) {
                cancellation.checkpoint();var initial=frozen.get(definition.key().id());
                try(var reconstructed=Objects.requireNonNull(evaluator.reconstruct(definition,parameter,initial))) {
                    cancellation.checkpoint();
                    if(!initial.equals(reconstructed.input()))throw new IllegalArgumentException("Reconstructed TRAIN input binding changed");
                    var checks=Objects.requireNonNull(reconstructed.geometry());cancellation.checkpoint();tested++;
                    if(checks.checks().isEmpty())throw new IllegalArgumentException("Missing TRAIN geometry controls");
                    geometryControls=Math.addExact(geometryControls,controlCount(checks));
                    if(!checks.passed()){passed=false;reason="REQUIRED_GEOMETRY_CONTROL_NOT_PASSED";break;}
                }
            }
            if(passed&&!required.isEmpty()) {
                cancellation.checkpoint();var checks=Objects.requireNonNull(evaluator.coupled(parameter,required));cancellation.checkpoint();
                if(checks.checks().isEmpty())throw new IllegalArgumentException("Missing TRAIN coupled controls");
                coupledControls=controlCount(checks);skippedPairs=0;passed=checks.passed();
                if(!passed)reason="REQUIRED_COUPLED_CONTROL_NOT_PASSED";
            }
            evidence.put(parameter,new TrainEvidence(passed?TrainState.FEASIBLE:TrainState.FAILED,originals.size(),originals.size(),
                    tested,originals.size()-tested,coupledControls,skippedPairs,
                    controlCount(cheap.get(parameter).finish()),geometryControls,reason));
            if(passed)shortlist.add(parameter);
        }
        cancellation.checkpoint();return new TrainPass(Collections.unmodifiableMap(scores),baseline,List.copyOf(shortlist),Collections.unmodifiableMap(evidence));
    }
    private static int controlCount(Checks checks) {
        int count=0;for(var check:checks.checks())count=Math.addExact(count,check.count());return count;
    }
    private static Comparator<CaseDefinition> geometryComparator() {
        return Comparator.comparing((CaseDefinition d)->selector(d).isEmpty())
                .thenComparing(Comparator.comparingDouble(CaseDefinition::sourcePitchMeters).reversed())
                .thenComparing(CaseDefinition::variantId).thenComparing(CaseDefinition::contrastId).thenComparing(CaseDefinition::side);
    }
    static List<CaseDefinition> trainGeometryOrder(List<CaseDefinition> definitions,boolean prospective,boolean complete) {
        if(definitions==null)throw new IllegalArgumentException("TRAIN permutation required");
        var originals=definitions.stream().filter(d->d.key().split()==Split.TRAIN).toList();
        if(originals.isEmpty()||originals.stream().map(d->d.key().id()).distinct().count()!=originals.size())
            throw new IllegalArgumentException("Unique TRAIN permutation required");
        var result=new ArrayList<>(originals);result.sort(geometryComparator());
        if(prospective) {
            var registered=trainDiagnosticCatalog().definitions().stream().filter(d->d.key().split()==Split.TRAIN).toList();
            if(complete&&!new HashSet<>(originals).equals(new HashSet<>(registered)))
                throw new IllegalArgumentException("Complete exact original TRAIN definitions required");
            var prefix=new ArrayList<CaseDefinition>();
            for(String id:BProspectiveIdentificationManifest.PREFIX) {
                var expected=registered.stream().filter(d->d.key().id().equals(id)).findFirst().orElseThrow();
                var actual=originals.stream().filter(d->d.key().id().equals(id)).findFirst()
                        .orElseThrow(()->new IllegalArgumentException("Missing fixed TRAIN prefix"));
                if(!actual.equals(expected))throw new IllegalArgumentException("Fixed TRAIN prefix definition changed");
                prefix.add(actual);result.remove(actual);
            }
            result.addAll(0,prefix);
        }
        return List.copyOf(result);
    }
    private static Map<String,Object> inputFields(TrainInputBinding input) {
        return Map.of("sourceHash",input.sourceHash(),"rendererHash",input.rendererHash(),"catalogHash",input.catalogHash(),
                "recipeHash",input.recipeHash(),"source",sourceFields(input.source()),"factsHash",input.factsHash(),"latticeHash",input.latticeHash());
    }
    private static Map<String,Object> trainEvidenceFields(TrainEvidence evidence) {
        var fields=new LinkedHashMap<String,Object>();fields.put("state",evidence.state().name());fields.put("reason",evidence.reason());
        fields.put("scoredScenes",evidence.scoredScenes());fields.put("cheapCheckedScenes",evidence.cheapCheckedScenes());
        fields.put("testedProfileControls",evidence.testedProfileControls());fields.put("testedGeometryScenes",evidence.testedGeometryScenes());
        fields.put("skippedGeometryScenes",evidence.skippedGeometryScenes());fields.put("testedGeometryControls",evidence.testedGeometryControls());
        fields.put("skippedGeometryControls",evidence.skippedGeometryScenes()==0?0:"UNAVAILABLE_NOT_EVALUATED");fields.put("testedCoupledControls",evidence.testedCoupledControls());
        fields.put("skippedRequiredPairs",evidence.skippedRequiredPairs());fields.put("geometryOrder","train-control-inventory.json");
        return fields;
    }
    private static Map<String,Object> preflight(int catalogCount,List<CaseDefinition> definitions,int configurations,Controls controls) {
        long train=definitions.stream().filter(d->d.key().split()==Split.TRAIN).count();
        var fields=new LinkedHashMap<String,Object>();fields.put("catalogCaseCount",catalogCount);fields.put("catalogCeiling",512);
        fields.put("trainCaseCount",train);fields.put("configurations",configurations);
        fields.put("maximumTrainMapSolves",Math.multiplyExact(train,configurations));
        fields.put("fourFeasibleFirstMapSolves",Math.multiplyExact(train,Math.min(4,configurations)));
        fields.put("maximumTrainScoredTransitions",Math.multiplyExact(Math.multiplyExact(train,configurations),TraceBudgets.defaults().maximumTransitions()));
        fields.put("admission","unchanged-default-native-or-budget-coarsened-actual-plans-recorded-per-scene");
        fields.put("memoryBytes",controls.memoryBytes());fields.put("diskBytes",MAX_DISK_BYTES);fields.put("watchdogNanos",controls.watchdogNanos());
        fields.put("clock","one-clock-includes-all-scoring-reconstruction-controls-and-SELECT");
        fields.put("repeatedReads","counted-as-reads-not-new-registered-cases");fields.put("runtimeGuarantee",false);return fields;
    }
    /** Pure ranking only: actual protocol coverage and artifact authority belong to run(). */
    static Optional<BIdentifiedObservationKernel.Parameters> eligibleChoice(List<CandidateDecision> candidates,
            OptionalDouble baselineScore,CancellationProbe cancellation) {
        cancellation.checkpoint();
        if(candidates==null||candidates.size()>4||baselineScore==null)throw new IllegalArgumentException("Bounded SELECT decisions required");
        if(baselineScore.isEmpty()||!Double.isFinite(baselineScore.getAsDouble()))return Optional.empty();
        var eligible=new ArrayList<CandidateDecision>();
        for(var candidate:candidates) {
            cancellation.checkpoint();
            if(candidate!=null&&candidate.parameters()!=null&&candidate.controls()!=null&&candidate.controls().passed()
                    &&candidate.selectScore()!=null&&candidate.selectScore().isPresent()&&Double.isFinite(candidate.selectScore().getAsDouble())
                    &&candidate.selectScore().getAsDouble()<=baselineScore.getAsDouble()+1e-12)eligible.add(candidate);
        }
        eligible.sort(Comparator.comparingDouble((CandidateDecision d)->d.selectScore().orElseThrow())
                .thenComparingDouble(d->d.parameters().gain()).thenComparingDouble(d->d.parameters().nuisanceMultiplier())
                .thenComparingDouble(d->d.parameters().directSpanSlope()));
        cancellation.checkpoint();return eligible.isEmpty()?Optional.empty():Optional.of(eligible.get(0).parameters());
    }
    record Controls(List<String> caseIds,long memoryBytes,long watchdogNanos,
            LongSupplier clock,CancellationProbe cancellation,Runnable beforeRender) {
        Controls {
            if(memoryBytes<=0 || memoryBytes>64L*1024*1024 || watchdogNanos<=0
                    || watchdogNanos>3_600_000_000_000L || clock==null || cancellation==null || beforeRender==null)
                throw new IllegalArgumentException("Only bounded runner controls are permitted");
            caseIds=caseIds==null ? null:List.copyOf(caseIds);
        }
        static Controls defaults() {
            return new Controls(null,64L*1024*1024,3_600_000_000_000L,System::nanoTime,CancellationProbe.NONE,()->{});
        }
    }
    private BObservationIdentificationTool() { }
    /** Fixed dummy pipeline, unreachable from main; it never ranks, freezes or grants evaluation authority. */
    static RunReceipt runFixture(Phase phase,Path output,Path binding,Controls controls) throws Exception {
        if(phase==Phase.HOLDOUT)throw new SecurityException("Fixtures have no HOLDOUT authority");
        if(phase==null||output==null||controls==null)throw new IllegalArgumentException("Complete fixed fixture inputs required");
        long started=controls.clock().getAsLong();var cancellation=guarded(controls,started);cancellation.checkpoint();
        var definitions=fixtureDefinitions();var ids=definitions.stream().map(d->d.key().id()).collect(java.util.stream.Collectors.toSet());
        if(controls.caseIds()!=null&&!ids.equals(new HashSet<>(controls.caseIds())))
            throw new IllegalArgumentException("Fixture cases are fixed dummy identities only");
        String sourceHash=BIdentificationReceipt.sourceHash();
        String rendererHash=BIdentificationReceipt.sourceMemberHash("service/tracing/probabilistic/BObservationIdentificationTool.java");
        String catalogHash=BIdentificationReceipt.hash(BIdentificationReceipt.canonical(definitions.stream().map(d->
                Map.of("id",d.key().id(),"recipeHash",BIdentificationCatalog.recipeHash(d))).toList()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var identity=new InputIdentity(sourceHash,rendererHash,catalogHash);
        var bindingFields=Map.<String,Object>of("scope","NONQUALIFYING_DUMMY","phase","CATALOG","status","NONQUALIFYING_FIXTURE",
                "sourceHash",sourceHash,"rendererHash",rendererHash,"catalogHash",catalogHash,"fixtureRevision","straight-gaussian-native-v1");
        if(phase==Phase.CATALOG) {if(binding!=null)throw new IllegalArgumentException("Fixture CATALOG does not consume a binding");}
        else if(binding==null||!bindingFields.equals(BIdentificationReceipt.read(binding)))
            throw new IllegalArgumentException("Fixture binding changed or belongs to another protocol");
        SourceGuard guard=()->{cancellation.checkpoint();if(!sourceHash.equals(BIdentificationReceipt.sourceHash()))
            throw new IllegalStateException("Fixture sources changed");};
        int[] reads={0,0};
        InputProvider provider=d->{
            if(!definitions.contains(d))throw new SecurityException("Unregistered dummy identity");
            reads[0]++;var scene=fixtureScene(d,cancellation);reads[1]++;return scene;
        };
        guard.verify();newDirectory(output);
        try(var writer=new Writer(output,controls.memoryBytes())) {
            try {
                var fields=new LinkedHashMap<String,Object>();fields.put("status","NONQUALIFYING_FIXTURE");fields.put("scope","NONQUALIFYING_DUMMY");
                fields.put("sourceHash",sourceHash);fields.put("catalogHash",catalogHash);fields.put("holdoutAuthority",false);fields.put("selectionAuthority","NONE");
                writer.publish("binding.json",bindingFields);
                var all=fixtureParameters();var diagnostics=List.copyOf(all.subList(0,4));
                var train=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,Aggregate>();
                var select=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,Aggregate>();
                var cached=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,Map<String,BIdentificationPairCache.Index>>();
                var totals=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,CheckTotals>();
                for(var p:all)train.put(p,new Aggregate());
                for(var p:diagnostics){select.put(p,new Aggregate());cached.put(p,new TreeMap<>());totals.put(p,new CheckTotals());}
                int scenes=0,selectScenes=0,pipelines=0,rawSolves=0,pairChecks=0;long peak=0;
                var availability=availability();
                if(phase!=Phase.CATALOG)for(var definition:definitions) {
                    var requested=phase==Phase.EXTRACT?List.<BIdentifiedObservationKernel.Parameters>of():
                            definition.key().split()==Split.TRAIN?all:diagnostics;
                    var report=scene(definition,requested,phase,guard,provider,identity,controls,cancellation,availability,writer,scenes);
                    writer.publish("scene-%03d.json".formatted(scenes++),report.fields());peak=Math.max(peak,report.peakBytes());
                    if(phase==Phase.FIT_SELECT&&definition.key().split()==Split.TRAIN)
                        for(var p:all)train.get(p).add(definition,report.scores().get(p));
                    if(phase==Phase.FIT_SELECT&&definition.key().split()==Split.SELECT) {
                        selectScenes++;pipelines+=report.acceptanceReports().size();
                        rawSolves+=report.acceptanceReports().size()+(report.fields().containsKey("baselineRawGeometry")?1:0);
                        for(var p:diagnostics){select.get(p).add(definition,report.scores().get(p));
                            totals.get(p).add(report.checks().get(p));cached.get(p).put(definition.key().id(),report.pairs().get(p));}
                    }
                }
                var required=requiredPairs(definitions.stream().filter(d->d.key().split()==Split.SELECT).toList(),Split.SELECT,cancellation);
                var diagnosticFields=new ArrayList<Map<String,Object>>();var trainFields=new ArrayList<Map<String,Object>>();
                if(phase==Phase.FIT_SELECT) {
                    for(var p:diagnostics) {
                        var compared=pairs(required,cached.get(p),p,writer,controls,cancellation,Split.SELECT,guard);
                        pairChecks+=compared.reports().size();peak=Math.max(peak,compared.peakBytes());totals.get(p).add(compared.checks());
                        var item=new LinkedHashMap<>(configuration(p,select.get(p),"diagnostic",writer));
                        item.put("checks",checkFields(totals.get(p).finish()));item.put("purpose","FIXED_PIPELINE_EXERCISE_NOT_SELECTED");diagnosticFields.add(item);
                    }
                    for(var p:all)trainFields.add(configuration(p,train.get(p),"train",writer));
                }
                fields.put("trainConfigurations",trainFields);fields.put("diagnosticConfigurations",diagnosticFields);
                fields.put("sceneCount",phase==Phase.CATALOG?definitions.size():scenes);fields.put("rasterReads",reads[0]);fields.put("labelReads",reads[1]);fields.put("holdoutReads",0);
                fields.put("selectScenes",selectScenes);fields.put("selectPipelineCount",pipelines);fields.put("rawGeometrySolves",rawSolves);
                fields.put("pairChecks",pairChecks);fields.put("requiredPairCount",required.size());fields.put("peakSceneBytes",peak);
                if(phase==Phase.EXTRACT)fields.put("featureAvailability",availability);
                fields.put("trainingGeometry","NOT_RUN_FIXTURE_PIPELINE_ONLY");guard.verify();writer.publish("summary.json",fields);
                return new RunReceipt("NONQUALIFYING_FIXTURE",output.resolve("summary.json"));
            } catch(Exception failure) {
                var fields=new LinkedHashMap<String,Object>();fields.put("status","FAILED_NONQUALIFYING_FIXTURE");fields.put("scope","NONQUALIFYING_DUMMY");
                fields.put("rasterReads",reads[0]);fields.put("labelReads",reads[1]);fields.put("holdoutReads",0);fields.put("holdoutAuthority",false);
                fields.put("reason",failure instanceof CancellationException?"CANCELLED":failure instanceof RunLimitException limit?limit.reason:"FIXTURE_EVALUATION_FAILED");
                try {writer.publish("failure.json",fields);}catch(Exception publication){failure.addSuppressed(publication);}throw failure;
            }
        }
    }
    private static List<BIdentifiedObservationKernel.Parameters> fixtureParameters() {
        var result=new ArrayList<BIdentifiedObservationKernel.Parameters>();
        for(double g:new double[]{1,4,16,64})for(double l:new double[]{1,2})for(double b:new double[]{0,1,3,7})
            result.add(new BIdentifiedObservationKernel.Parameters(g,l,b));return List.copyOf(result);
    }
    private static List<CaseDefinition> fixtureDefinitions() {
        var result=new ArrayList<CaseDefinition>();
        for(var split:List.of(Split.TRAIN,Split.SELECT))for(int oversampling:new int[]{1,2}) {
            String variant=oversampling==1?"v0":"v6",id="NONQUALIFYING_DUMMY/"+split+"/"+variant;
            result.add(new CaseDefinition(new CaseKey(id,"NONQUALIFYING_DUMMY","DUMMY_LINE_"+split,"DUMMY_NATIVE_"+split,split,id,0),
                    "nonqualifying-dummy-straight","left",variant,0,1,oversampling,false,
                    "NONQUALIFYING_DUMMY:0.03+0.4*StrictMath.exp(-y*y/2)",List.of("NONQUALIFYING_DUMMY","KNOWN_NATIVE")));
        }
        return List.copyOf(result);
    }
    enum TrainFixtureFault { NONE, EXPECTED_FACTS_MISMATCH, MISSING_PAIR_INDEX, RETURNED_RESOURCE_LIMIT, RETURNED_NUMERIC_FAILURE, ABANDON_BEFORE_GEOMETRY }
    static RunReceipt runTrainFixture(Path output,Controls controls,TrainFixtureFault fault,
            BObservationRuntimeTelemetry telemetry) throws Exception {
        return runTrainFixture(output,controls,fault,telemetry,()->{});
    }
    static RunReceipt runTrainFixture(Path output,Controls controls,TrainFixtureFault fault,
            BObservationRuntimeTelemetry telemetry,Runnable callerIdle) throws Exception {
        if(output==null||controls==null||fault==null)throw new IllegalArgumentException("Complete fixed TRAIN fixture inputs required");
        long started=controls.clock().getAsLong();var cancellation=guarded(controls,started);cancellation.checkpoint();
        var definitions=fixtureDefinitions().stream().filter(d->d.key().split()==Split.TRAIN).toList();
        var ids=definitions.stream().map(d->d.key().id()).collect(java.util.stream.Collectors.toSet());
        if(controls.caseIds()!=null&&!ids.equals(new HashSet<>(controls.caseIds())))
            throw new IllegalArgumentException("Only the fixed dummy TRAIN identities are permitted");
        String sourceHash=BIdentificationReceipt.sourceHash();
        String rendererHash=BIdentificationReceipt.sourceMemberHash("service/tracing/probabilistic/BObservationIdentificationTool.java");
        String catalogHash=BIdentificationReceipt.hash(BIdentificationReceipt.canonical(definitions.stream().map(d->
                Map.of("id",d.key().id(),"recipeHash",BIdentificationCatalog.recipeHash(d))).toList()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var identity=new InputIdentity(sourceHash,rendererHash,catalogHash);
        SourceGuard guard=()->{cancellation.checkpoint();if(!sourceHash.equals(BIdentificationReceipt.sourceHash()))
            throw new IllegalStateException("TRAIN fixture sources changed");};
        int[] reads={0,0};InputProvider provider=d->{
            if(!definitions.contains(d))throw new SecurityException("TRAIN fixture cannot acquire another cohort");
            reads[0]++;var scene=fixtureScene(d,cancellation);reads[1]++;return scene;
        };
        var required=requiredPairs(definitions,Split.TRAIN,cancellation);var parameter=fixtureParameters().get(0);
        guard.verify();newDirectory(output);
        try(var writer=new Writer(output,controls.memoryBytes())) {
            writer.telemetry=telemetry;
            var driver=realTrainEvaluator(required,guard,provider,identity,controls,cancellation,availability(),writer,0,0,telemetry);
            driver.fixtureReturnedStatus=fault==TrainFixtureFault.RETURNED_RESOURCE_LIMIT?ProbabilisticInferenceResult.Status.RESOURCE_LIMIT:
                    fault==TrainFixtureFault.RETURNED_NUMERIC_FAILURE?ProbabilisticInferenceResult.Status.NUMERIC_FAILURE:null;
            try {
                var initial=new LinkedHashMap<String,TrainSceneScore>();var cheap=new CheckTotals();var geometry=new CheckTotals();
                for(var definition:definitions) {
                    var report=driver.score(definition,List.of(parameter));initial.put(definition.key().id(),report);
                    cheap.add(report.cheapChecks().get(parameter));
                }
                for(int i=0;i<definitions.size();i++) {
                    var definition=definitions.get(i);var frozen=initial.get(definition.key().id()).input().orElseThrow();
                    if(i==0&&fault==TrainFixtureFault.EXPECTED_FACTS_MISMATCH) {
                        String facts=(frozen.factsHash().charAt(0)=='0'?"1":"0")+frozen.factsHash().substring(1);
                        frozen=new TrainInputBinding(frozen.sourceHash(),frozen.rendererHash(),frozen.catalogHash(),frozen.recipeHash(),
                                frozen.source(),facts,frozen.latticeHash());
                    }
                    try(var reconstructed=driver.reconstruct(definition,parameter,frozen)) {
                        callerIdle.run();
                        if(fault==TrainFixtureFault.ABANDON_BEFORE_GEOMETRY)throw new CancellationException("DUMMY_ABANDONED");
                        geometry.add(reconstructed.geometry());
                        callerIdle.run();
                    }
                }
                if(fault==TrainFixtureFault.MISSING_PAIR_INDEX)driver.cache.remove(required.get(0).first().key().id());
                var coupled=driver.coupled(parameter,required);
                var fields=trainFixtureFields(driver,reads);
                fields.put("status","NONQUALIFYING_FIXTURE");fields.put("sourceHash",sourceHash);fields.put("catalogHash",catalogHash);
                fields.put("parameters",parameterFields(parameter));fields.put("profileChecks",checkFields(cheap.finish()));
                fields.put("geometryChecks",checkFields(geometry.finish()));fields.put("coupledChecks",checkFields(coupled));
                fields.put("purpose","FIXED_REAL_TRAIN_DRIVER_DIAGNOSTIC_NOT_SELECTION");
                guard.verify();writer.publish("summary.json",fields);return new RunReceipt("NONQUALIFYING_FIXTURE",output.resolve("summary.json"));
            } catch(Exception failure) {
                var fields=trainFixtureFields(driver,reads);fields.put("status","FAILED_NONQUALIFYING_FIXTURE");
                fields.put("reason",failure instanceof CancellationException?"CANCELLED":failure instanceof RunLimitException limit?limit.reason:"TRAIN_FIXTURE_EVALUATION_FAILED");
                try {writer.publish("failure.json",fields);}catch(Exception publication){failure.addSuppressed(publication);}throw failure;
            }
        }
    }
    /** Fixed one-configuration diagnostic of the actual TRAIN driver; no ranking or FEASIBLE claim is made. */
    static RunReceipt runTrainFixture(Path output,Controls controls,TrainFixtureFault fault) throws Exception {
        return runTrainFixture(output,controls,fault,BObservationRuntimeTelemetry.NONE);
    }
    /** Fixed TRAIN-only timing probe. This path cannot rank, select, freeze or obtain HOLDOUT access. */
    @FunctionalInterface interface DiagnosticVerification<T> {T verify() throws Exception;}
    record DiagnosticStart<T>(T value,long started,CancellationProbe cancellation) { }
    static <T> DiagnosticStart<T> diagnosticPreflight(Controls controls,DiagnosticVerification<T> verification) throws Exception {
        long started=controls.clock().getAsLong();var cancellation=guarded(controls,started);cancellation.checkpoint();
        T value=verification.verify();cancellation.checkpoint();return new DiagnosticStart<>(value,started,cancellation);
    }
    static void runTrainRuntimeDiagnostic(Path output) throws Exception {
        if(output==null)throw new IllegalArgumentException("New diagnostic output required");
        var controls=new Controls(null,BObservationTrainRuntimeDiagnosticTool.MEMORY_BYTES,
                BObservationTrainRuntimeDiagnosticTool.WATCHDOG_NANOS,System::nanoTime,CancellationProbe.NONE,()->{});
        var preflight=diagnosticPreflight(controls,BObservationTrainRuntimeDiagnosticTool::verifyFreeze);
        long started=preflight.started();var cancellation=preflight.cancellation();var freeze=preflight.value();
        freeze.verifyUnchanged();cancellation.checkpoint();
        runVerifiedTrainDiagnostic(output,controls,started,cancellation,freeze.protocolHash,freeze.sourceHash,
                freeze.catalogHash,"NONQUALIFYING_TRAIN_RUNTIME",freeze::verifyUnchanged,Map.of());
    }
    static void runOptimizedTrainRuntimeDiagnostic(Path output,String expectedPinsHash) throws Exception {
        if(output==null)throw new IllegalArgumentException("New diagnostic output required");
        var controls=new Controls(null,BObservationTrainRuntimeDiagnosticTool.MEMORY_BYTES,
                BObservationTrainRuntimeDiagnosticTool.WATCHDOG_NANOS,System::nanoTime,CancellationProbe.NONE,()->{});
        var preflight=diagnosticPreflight(controls,()->BProspectiveIdentificationManifest.registerMetadata(
                BProspectiveIdentificationManifest.Purpose.OPTIMIZED_TRAIN_DIAGNOSTIC,expectedPinsHash));
        var seal=preflight.value();seal.requireExecutionApproved();preflight.cancellation().checkpoint();
        runVerifiedTrainDiagnostic(output,controls,preflight.started(),preflight.cancellation(),seal.manifestHash(),seal.sourceHash(),
                seal.catalogHash(),seal.purpose().name(),seal::verifyUnchanged,seal.provenance());
    }
    private static void runVerifiedTrainDiagnostic(Path output,Controls controls,long started,CancellationProbe cancellation,
            String protocolHash,String sourceHash,String catalogHash,String purpose,SourceGuard verification,Map<String,Object> provenance) throws Exception {
        var catalog=trainDiagnosticCatalog();var definitions=new ArrayList<CaseDefinition>();
        for(String id:BObservationTrainRuntimeDiagnosticTool.CASES) {
            var definition=catalog.definitions().stream().filter(d->d.key().id().equals(id)).findFirst().orElseThrow();
            BObservationTrainRuntimeDiagnosticTool.requireCase(definition);definitions.add(definition);
        }
        var access=EvaluationAccess.preselection(Phase.EXTRACT);
        SourceGuard guard=()->{cancellation.checkpoint();verification.verify();cancellation.checkpoint();};
        InputProvider provider=d->{BObservationTrainRuntimeDiagnosticTool.requireCase(d);return BIdentificationScenes.render(d.key(),access);};
        var manifest=BIdentificationManifest.read(BObservationTrainRuntimeDiagnosticTool.readBounded(BObservationTrainRuntimeDiagnosticTool.MANIFEST,65_536));
        var parameter=parameters(manifest).get(0);var required=requiredPairs(definitions,Split.TRAIN,cancellation);
        if(required.size()!=2)throw new IllegalArgumentException("Fixed TRAIN diagnostic pair inventory changed");
        newDirectory(output);newDirectory(output.resolve("aggregate"));newDirectory(output.resolve("scratch"));
        try(var writer=new Writer(output.resolve("scratch"),controls.memoryBytes())) {
            var aggregate=new DiagnosticAggregateWriter(output.resolve("aggregate"),writer);
            var diagnosticBinding=new LinkedHashMap<String,Object>(Map.of("schema",1,"purpose",purpose,
                    "protocolSha256",protocolHash,"sourceSha256",sourceHash,"baselineSourceSha256",BObservationTrainRuntimeDiagnosticTool.OLD_SOURCE,
                    "selectionAuthority","NONE","holdoutAuthority",false));
            diagnosticBinding.putAll(provenance);aggregate.publish("binding.json",diagnosticBinding);
            var telemetry=new BObservationRuntimeTelemetry(controls.clock(),aggregate::progress);writer.telemetry=telemetry;
            var identity=new InputIdentity(sourceHash,BIdentificationReceipt.sourceMemberHash("service/tracing/probabilistic/BIdentificationScenes.java"),catalogHash);
            var driver=realTrainEvaluator(required,guard,provider,identity,controls,cancellation,availability(),writer,0,0,telemetry);
            try {
                var initial=new LinkedHashMap<String,TrainInputBinding>();
                for(var definition:definitions)initial.put(definition.key().id(),driver.score(definition,List.of(parameter)).input().orElseThrow());
                for(var definition:definitions)try(var reconstructed=driver.reconstruct(definition,parameter,initial.get(definition.key().id()))) {
                    if(!initial.get(definition.key().id()).equals(reconstructed.input()))throw new IllegalArgumentException("Diagnostic input changed");
                    reconstructed.geometry();
                }
                driver.coupled(parameter,required);guard.verify();cancellation.checkpoint();
                aggregate.publish("status.json",diagnosticStatus("DIAGNOSTIC_COMPLETED_NONQUALIFYING","NONE",driver,access,telemetry,aggregate,started,controls));
            } catch(Exception|Error failure) {
                String reason=failure instanceof CancellationException?"CANCELLED":failure instanceof RunLimitException limit?limit.reason:
                        failure instanceof AttemptMemoryLedger.ResourceLimitException||failure instanceof ProbabilisticInference.MemoryLimit?"MEMORY_OR_WORK_LIMIT":"DIAGNOSTIC_ERROR_NO_AUTHORITY";
                try {aggregate.publish("status.json",diagnosticStatus("DIAGNOSTIC_FAILED_NONQUALIFYING",reason,driver,access,telemetry,aggregate,started,controls));}
                catch(Exception publication){failure.addSuppressed(publication);}throw failure;
            }
        }
    }
    private static Map<String,Object> diagnosticStatus(String status,String reason,RealTrainEvaluator driver,EvaluationAccess access,
            BObservationRuntimeTelemetry telemetry,DiagnosticAggregateWriter aggregate,long started,Controls controls) {
        var result=new LinkedHashMap<String,Object>();result.put("schema",1);result.put("status",status);result.put("reason",reason);
        result.put("selectionAuthority","NONE");result.put("holdoutAuthority",false);result.put("coupledScope","FIXED_SUBSET_NOT_WHOLE_TRAIN_GATE");
        result.put("trainScoredScenes",driver.sceneCount);result.put("trainReconstructionReads",driver.reconstructionCount);
        result.put("trainMapSolves",driver.mapSolves);result.put("trainPairChecks",driver.pairChecks);result.put("peakSceneBytes",driver.peak);
        result.put("rasterReads",access.rasterReads());result.put("labelReads",access.labelReads());result.put("selectReads",0);result.put("holdoutReads",0);
        result.put("elapsedNanos",controls.clock().getAsLong()-started);result.put("chargedOrdinaryAndTelemetryBytesBeforeStatus",aggregate.writer.bytes);
        result.put("telemetryPublicationsBeforeStatus",aggregate.publications);result.put("chargedTelemetryBytesBeforeStatus",aggregate.bytes);
        result.put("telemetryPublicationNanosBeforeStatus",aggregate.publicationNanos);
        result.put("coupledAvailability",driver.pairChecks==2?"MEASURED_FIXED_SUBSET":"UNAVAILABLE_OR_INCOMPLETE");
        result.put("timing",telemetry.snapshot());return result;
    }
    interface AggregateIO {
        default void write(Path path,java.nio.channels.FileChannel channel,byte[] encoded) throws java.io.IOException {
            var buffer=java.nio.ByteBuffer.wrap(encoded);while(buffer.hasRemaining())channel.write(buffer);
        }
        default void restoreProgress(Path path,long length,boolean created) throws java.io.IOException {
            if(created)Files.delete(path);
            else try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.truncate(length);}
        }
        default void deleteOwned(Path path) throws java.io.IOException {Files.deleteIfExists(path);}
        default void moveOwned(Path source,Path destination) throws java.io.IOException {Files.move(source,destination,StandardCopyOption.ATOMIC_MOVE);}
    }
    /** Shares the unchanged global disk envelope; telemetry never profiles its own publication. */
    static final class DiagnosticAggregateWriter {
        private static final AggregateIO NIO=new AggregateIO() { };
        final Path directory;final Writer writer;int publications,snapshots;long bytes,publicationNanos,retainedReservationBytes,progressLength;
        private boolean progressOwned;
        final AggregateIO io;
        DiagnosticAggregateWriter(Path directory,Writer writer){this(directory,writer,NIO);}
        DiagnosticAggregateWriter(Path directory,Writer writer,AggregateIO io){this.directory=directory;this.writer=writer;this.io=Objects.requireNonNull(io);}
        void progress(Map<String,Object> fields) throws Exception {
            if(snapshots>=BObservationTrainRuntimeDiagnosticTool.MAX_SNAPSHOTS)throw new RunLimitException("TELEMETRY_SNAPSHOT_LIMIT");
            write(directory.resolve("progress.jsonl"),fields,true);snapshots++;
        }
        void publish(String name,Map<String,Object> fields) throws Exception {
            if(!Set.of("binding.json","status.json").contains(name))throw new IllegalArgumentException("Aggregate file allowlist");
            if(name.equals("status.json")) {
                var totals=new LinkedHashMap<>(fields);int length=0;
                boolean exact=retainedReservationBytes==0&&writer.retainedOrdinaryReservationBytes==0;
                totals.put("byteAccounting",exact?"EXACT_OWNED_PAYLOAD_BYTES":"CONSERVATIVE_RESERVED_BYTES");
                totals.put("retainedTelemetryReservationBytes",retainedReservationBytes);
                totals.put("retainedOrdinaryReservationBytes",writer.retainedOrdinaryReservationBytes);
                for(int i=0;i<16;i++) {
                    totals.put(exact?"totalOrdinaryAndTelemetryBytes":"totalChargedOrdinaryAndTelemetryBytes",writer.bytes+length);
                    totals.put(exact?"totalTelemetryBytes":"totalChargedTelemetryBytes",bytes+length);
                    totals.put("totalTelemetryPublications",publications+1);
                    int next=(BIdentificationReceipt.canonical(totals)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if(next==length){fields=totals;break;}
                    length=next;if(i==15)throw new IllegalStateException("Bounded terminal byte accounting did not converge");
                }
            }
            write(directory.resolve(name),fields,false);
        }
        private void write(Path path,Map<String,Object> fields,boolean append) throws Exception {
            long started=System.nanoTime();
            try {
            var encoded=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long telemetryLimit=BObservationTrainRuntimeDiagnosticTool.MAX_TELEMETRY_BYTES-(path.getFileName().toString().equals("status.json")?0:65_536);
            if(encoded.length>telemetryLimit-bytes||encoded.length>MAX_DISK_BYTES-writer.bytes)
                throw new RunLimitException("TELEMETRY_DISK_LIMIT");
            try(var reservation=new AggregateReservation(encoded.length)) {
                if(append)writeProgress(path,encoded,reservation);else writeAtomic(path,encoded,reservation);
                reservation.committed=true;publications++;
            }
            } finally {publicationNanos+=System.nanoTime()-started;}
        }
        /** Reserve the complete payload before I/O; failed cleanup retains a bounded upper charge. */
        private final class AggregateReservation implements AutoCloseable {
            final long length;boolean committed,retained;
            AggregateReservation(long length){this.length=length;bytes+=length;writer.bytes+=length;}
            void retain(){if(!retained){retained=true;retainedReservationBytes+=length;}}
            public void close(){if(!committed&&!retained){bytes-=length;writer.bytes-=length;}}
        }
        private void writeProgress(Path path,byte[] encoded,AggregateReservation reservation) throws Exception {
            if(retainedReservationBytes!=0)throw new RunLimitException("TELEMETRY_PARTIAL_RETAINED");
            boolean created=false,touched=false;
            try {
                try(var channel=progressOwned
                        ?java.nio.channels.FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)
                        :java.nio.channels.FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    created=!progressOwned;
                    if(channel.size()!=progressLength)throw new java.io.IOException("Owned progress length changed");
                    channel.position(progressLength);touched=true;io.write(path,channel,encoded);
                }
                progressOwned=true;progressLength+=encoded.length;
            } catch(Exception|Error failure) {
                if(created||touched)try{io.restoreProgress(path,progressLength,created);}
                    catch(Exception|Error cleanup){reservation.retain();failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        private void writeAtomic(Path path,byte[] encoded,AggregateReservation reservation) throws Exception {
            if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(path.toString());
            var partial=path.resolveSibling(path.getFileName()+".partial");boolean owned=false;
            try {
                try(var channel=java.nio.channels.FileChannel.open(partial,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    owned=true;io.write(partial,channel,encoded);
                }
                io.moveOwned(partial,path);owned=false;
            } catch(Exception|Error failure) {
                if(owned) {
                    // A failed atomic move may have completed. Preserve ownership uncertainty and its full reservation.
                    if(!Files.exists(partial,LinkOption.NOFOLLOW_LINKS)&&Files.exists(path,LinkOption.NOFOLLOW_LINKS))reservation.retain();
                    try{io.deleteOwned(partial);}catch(Exception|Error cleanup){reservation.retain();failure.addSuppressed(cleanup);}
                }
                throw failure;
            }
        }
    }
    private static LinkedHashMap<String,Object> trainFixtureFields(RealTrainEvaluator driver,int[] reads) {
        var fields=new LinkedHashMap<String,Object>();fields.put("scope","NONQUALIFYING_DUMMY");fields.put("holdoutAuthority",false);
        fields.put("selectionAuthority","NONE");fields.put("trainingGeometry","REAL_SHARED_TRAIN_DRIVER_DIAGNOSTIC");
        fields.put("trainScoredScenes",driver.sceneCount);fields.put("trainReconstructionReads",driver.reconstructionCount);
        fields.put("trainMapSolves",driver.mapSolves);fields.put("trainPairChecks",driver.pairChecks);
        fields.put("rasterReads",reads[0]);fields.put("labelReads",reads[1]);fields.put("holdoutReads",0);fields.put("peakSceneBytes",driver.peak);
        return fields;
    }
    /** This explicit native raster belongs solely to the fixture; no catalog producer or evaluation access is called. */
    private static Scene fixtureScene(CaseDefinition definition,CancellationProbe cancellation) throws Exception {
        int width=67,height=61;var values=new double[width*height];var valid=new boolean[values.length];Arrays.fill(valid,true);
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest))) {
            for(int y=0;y<height;y++){cancellation.checkpoint();double lateral=30-y;
                for(int x=0;x<width;x++){int i=y*width+x;values[i]=.03+.4*StrictMath.exp(-lateral*lateral/2);out.writeDouble(values[i]);out.writeBoolean(true);}}
        }
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        var source=List.of(new MetricPoint(0,0),new MetricPoint(6,0));double pitch=1d/definition.oversampling();
        var transform=org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.localMetricAffine(
                frame,new RasterPoint(30,30),1,0,0,-1);
        var grid=new MetricRasterGrid(frame,new MetricPoint(-30,30),1,0,0,-1,pitch,(width-1)*definition.oversampling()+1,(height-1)*definition.oversampling()+1);
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                "NONQUALIFYING_DUMMY",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        var evidence=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture().captureWithMetricScalarSource(
                definition.key().id(),new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB),valid,values,
                source.stream().map(frame::toGeographic).toList(),source,transform,grid,EvidenceResolution.nativeSource(1,1),25,
                "analytic-native:"+java.util.HexFormat.of().formatHex(digest.digest()),EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,"scalar",lineage,cancellation);
        var truth=new Truth(List.of(new LabelRegion(MetricRegion.rectangle(-30,-30,36,30),StructureLabel.OBSERVED_STRUCTURE,
                RasterLabel.VALID,AssociationLabel.UNIQUE,List.of(source))));
        return new Scene(evidence,source,truth);
    }
    static CancellationProbe guarded(Controls controls,long started) {
        return ()->{
            controls.cancellation().checkpoint();
            if(controls.clock().getAsLong()-started>=controls.watchdogNanos())throw new RunLimitException("TIME");
            return false;
        };
    }
    public static void main(String[] arguments) throws Exception {
        Map<String,String> options=new LinkedHashMap<>();
        for(int i=0;i<arguments.length;i+=2) {
            if(i+1>=arguments.length || !Set.of("--phase","--manifest","--output","--binding").contains(arguments[i])
                    || arguments[i+1].isBlank() || options.putIfAbsent(arguments[i],arguments[i+1])!=null)
                throw new IllegalArgumentException("Use --phase CATALOG|EXTRACT|FIT_SELECT --manifest PATH --output NEWDIR [--binding CATALOG_BINDING]");
        }
        if(!options.keySet().containsAll(Set.of("--phase","--manifest","--output")))
            throw new IllegalArgumentException("Phase, reviewed manifest and new output directory are required");
        Phase phase=Phase.valueOf(options.get("--phase"));
        run(Path.of(options.get("--manifest")),phase,Path.of(options.get("--output")),
                options.containsKey("--binding")?Path.of(options.get("--binding")):null,Controls.defaults());
    }
    static RunReceipt run(Path manifest,BIdentificationCatalog.Phase phase,Path output,Path binding,
            Controls controls) throws Exception {
        if(phase==Phase.HOLDOUT)throw new SecurityException("A score pass has no frozen candidate or HOLDOUT authority");
        if(manifest==null || phase==null || output==null || controls==null)
            throw new IllegalArgumentException("Complete explicit run inputs are required");
        long started=controls.clock().getAsLong();
        CancellationProbe guarded=guarded(controls,started);
        guarded.checkpoint();
        var catalog=publicCatalog();
        var old=BIdentificationManifest.register(manifest,BIdentificationReceipt.sourceHash());
        var seal=new RunBinding(old.manifest(),old.manifestHash(),old.sourceHash(),old.catalogHash(),old::verifyUnchanged,
                false,old.registryHash(),"",()->{},Map.of());
        return runVerified(phase,output,binding,controls,started,guarded,catalog,seal);
    }
    static RunReceipt runProspective(Phase phase,Path output,Path binding,Controls controls,String expectedPinsHash) throws Exception {
        if(phase==Phase.HOLDOUT)throw new SecurityException("Prospective fitting has no HOLDOUT authority");
        if(phase==null||output==null||controls==null)throw new IllegalArgumentException("Complete prospective inputs required");
        var launch=diagnosticPreflight(controls,()->BProspectiveIdentificationManifest.registerMetadata(
                BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT,expectedPinsHash));
        var current=launch.value();
        var seal=new RunBinding(current.family(),current.manifestHash(),current.sourceHash(),current.catalogHash(),
                current::verifyUnchanged,true,current.registryHash(),current.pinsHash(),current::requireExecutionApproved,current.provenance());
        return runVerified(phase,output,binding,controls,launch.started(),launch.cancellation(),publicCatalog(),seal);
    }
    private record RunBinding(BIdentificationManifest manifest,String manifestHash,String sourceHash,String catalogHash,
            SourceGuard verification,boolean prospective,String registryHash,String pinsHash,SourceGuard approval,Map<String,Object> provenance) {
        void verifyUnchanged() throws Exception {verification.verify();}
    }
    private static RunReceipt runVerified(Phase phase,Path output,Path binding,Controls controls,long started,
            CancellationProbe guarded,BIdentificationCatalog catalog,RunBinding seal) throws Exception {
        if(!BOUNDED_RENDERER_SHA256.equals(BIdentificationReceipt.sourceMemberHash("service/tracing/probabilistic/BIdentificationScenes.java")))
            throw new IllegalArgumentException("Renderer changed; its 32MiB accounting bound is unreviewed");
        if(phase==Phase.CATALOG) {
            if(binding!=null)throw new IllegalArgumentException("CATALOG creates its binding; it does not consume one");
        } else verifyBinding(binding,seal);
        List<CaseDefinition> definitions=definitions(catalog,phase,controls.caseIds());
        if(seal.prospective()&&phase==Phase.FIT_SELECT) {
            if(controls.caseIds()!=null)throw new SecurityException("Prospective FIT requires the complete original protocol");
            trainGeometryOrder(definitions,true,true);
        }
        if(catalog.caseCount()>((Number)seal.manifest().fields().get("maxScenes")).intValue())
            throw new IllegalArgumentException("Catalog exceeds the registered scene ceiling");
        var parameters=parameters(seal.manifest());
        var requiredPairs=requiredPairs(catalog.definitions().stream().filter(d->d.key().split()==Split.SELECT).toList(),guarded);
        seal.verifyUnchanged();guarded.checkpoint();
        if(seal.prospective()&&phase!=Phase.CATALOG){seal.approval().verify();guarded.checkpoint();}
        newDirectory(output);
        var access=phase==Phase.CATALOG?EvaluationAccess.catalogView():EvaluationAccess.preselection(phase);
        Map<String,Object> header=header(phase,seal,controls.caseIds()!=null);
        var writer=new Writer(output,controls.memoryBytes());
        try {
            var initial=new LinkedHashMap<>(header);initial.put("status","STARTED");
            initial.put("configurations",parameters.stream().map(BObservationIdentificationTool::parameterFields).toList());
            writer.publish("started.json",initial);
            if(phase==Phase.CATALOG) {
                var inventory=catalog.inventory(access);var inventoryFiles=new ArrayList<String>();
                for(int offset=0;offset<inventory.size();offset+=64) {
                    guarded.checkpoint();var items=new ArrayList<Map<String,Object>>();
                    for(var item:inventory.subList(offset,Math.min(offset+64,inventory.size())))
                        items.add(Map.of("id",item.id(),"split",item.split().name(),"recipeHash",item.recipeHash()));
                    String name="inventory-%03d.json".formatted(offset/64);
                    writer.publish(name,Map.of("schema",1,"items",items));inventoryFiles.add(name);
                }
                var bindingFields=bindingFields(seal);
                writer.publish("binding.json",bindingFields);
                var summary=new LinkedHashMap<>(header);summary.put("status","CATALOG_REGISTERED");
                summary.put("sceneCount",inventory.size());summary.put("inventoryFiles",inventoryFiles);
                summary.put("rasterReads",access.rasterReads());summary.put("labelReads",access.labelReads());summary.put("holdoutReads",0);
                seal.verifyUnchanged();writer.publish("summary.json",summary);
                return new RunReceipt("CATALOG_REGISTERED",output.resolve("summary.json"));
            }
            var availability=availability();
            writer.publish("required-pairs.json",Map.of("scope","REGISTERED_SELECT_METADATA","pairs",requiredPairs.stream().map(
                    p->Map.of("first",p.first().key().id(),"second",p.second().key().id(),"kind",p.kind().name())).toList()));
            var train=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,Aggregate>();
            var select=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,Aggregate>();
            for(var parameter:parameters)train.put(parameter,new Aggregate());
            var baselineTrain=new Aggregate();var baselineSelect=new Aggregate();
            int count=0;long peak=0;
            if(phase==Phase.EXTRACT) {
                for(var definition:definitions) {
                    var report=scene(definition,List.of(),phase,seal,access,controls,guarded,availability,writer,count);
                    writer.publish("scene-%03d.json".formatted(count++),report.fields());peak=Math.max(peak,report.peakBytes());
                }
                var summary=new LinkedHashMap<>(header);summary.put("status","EXTRACTED_PUBLIC_FACTS");
                summary.put("sceneCount",count);summary.put("featureAvailability",availability);
                reads(summary,access);summary.put("peakSceneBytes",peak);
                seal.verifyUnchanged();guarded.checkpoint();writer.publish("summary.json",summary);
                return new RunReceipt("EXTRACTED_PUBLIC_FACTS",output.resolve("summary.json"));
            }
            var trainingIdentity=new InputIdentity(seal.sourceHash(),BIdentificationReceipt.sourceMemberHash("service/tracing/probabilistic/BIdentificationScenes.java"),seal.catalogHash());
            var trainPairs=requiredPairs(definitions.stream().filter(d->d.key().split()==Split.TRAIN).toList(),Split.TRAIN,guarded);
            var resourcePreflight=new LinkedHashMap<>(preflight(catalog.caseCount(),definitions,parameters.size(),controls));
            if(seal.prospective()) {
                resourcePreflight.put("trainingControlOrder",BProspectiveIdentificationManifest.ORDER);
                resourcePreflight.put("geometryPrefix",BProspectiveIdentificationManifest.PREFIX);
                resourcePreflight.put("skippedErrors","UNVISITED_UNKNOWN_NOT_PROVED_ABSENT");
            }
            writer.publish("preflight.json",resourcePreflight);
            var driver=realTrainEvaluator(trainPairs,seal::verifyUnchanged,d->BIdentificationScenes.render(d.key(),access),trainingIdentity,
                    controls,guarded,availability,writer,count,peak);
            var training=seal.prospective()?trainPassProspective(definitions,parameters,driver,guarded,true)
                    :trainPass(definitions,parameters,driver,guarded);
            var initialOrdinals=driver.initialOrdinals;
            count=driver.sceneCount;peak=driver.peak;train.clear();train.putAll(training.scores());baselineTrain=training.baseline();
            List<BIdentifiedObservationKernel.Parameters> shortlist=training.shortlist();
            var acceptance=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,CheckTotals>();
            var cached=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,Map<String,BIdentificationPairCache.Index>>();
            var acceptanceReports=new ArrayList<String>();var evaluatedSelect=new HashSet<String>();
            for(var parameter:shortlist){acceptance.put(parameter,new CheckTotals());cached.put(parameter,new TreeMap<>());}
            for(var parameter:shortlist)select.put(parameter,new Aggregate());
            for(var definition:definitions)if(!shortlist.isEmpty()&&definition.key().split()==Split.SELECT) {
                var report=scene(definition,shortlist,phase,seal,access,controls,guarded,availability,writer,count);
                writer.publish("scene-%03d.json".formatted(count++),report.fields());peak=Math.max(peak,report.peakBytes());
                baselineSelect.add(definition,report.baseline());
                evaluatedSelect.add(definition.key().id());
                for(var parameter:shortlist) {
                    select.get(parameter).add(definition,report.scores().get(parameter));acceptance.get(parameter).add(report.checks().get(parameter));
                    cached.get(parameter).put(definition.key().id(),report.pairs().get(parameter));
                }
                acceptanceReports.addAll(report.acceptanceReports());
            }
            for(var parameter:shortlist) {
                var pairReport=pairs(requiredPairs,cached.get(parameter),parameter,writer,controls,guarded,Split.SELECT,seal::verifyUnchanged);
                acceptance.get(parameter).add(pairReport.checks());peak=Math.max(peak,pairReport.peakBytes());
                acceptanceReports.addAll(pairReport.reports());
            }
            var expectedSelect=catalog.definitions().stream().filter(d->d.key().split()==Split.SELECT).map(d->d.key().id()).collect(java.util.stream.Collectors.toSet());
            var expectedTrain=catalog.definitions().stream().filter(d->d.key().split()==Split.TRAIN).map(d->d.key().id()).collect(java.util.stream.Collectors.toSet());
            boolean completeTrain=initialOrdinals.keySet().equals(expectedTrain);
            boolean full=controls.caseIds()==null&&completeTrain&&(shortlist.isEmpty()||evaluatedSelect.equals(expectedSelect));
            var decisions=new ArrayList<CandidateDecision>();
            for(var parameter:shortlist)decisions.add(new CandidateDecision(parameter,select.get(parameter).mean(),acceptance.get(parameter).finish()));
            var choice=eligibleChoice(decisions,baselineSelect.mean(),guarded);
            String status=full?(shortlist.isEmpty()?"FAMILY_REJECTED_NO_FEASIBLE_TRAIN_CANDIDATE":choice.isPresent()?
                    "FROZEN_PENDING_REGRESSION":"FAMILY_REJECTED_NO_PASSING_CANDIDATE"):"FIT_SCORED_PENDING_GEOMETRY";
            var trainFields=new ArrayList<Map<String,Object>>();var selectFields=new ArrayList<Map<String,Object>>();
            var trainOrder=trainGeometryOrder(definitions,seal.prospective(),controls.caseIds()==null);
            writer.publish("train-control-inventory.json",Map.of("geometryOrder",trainOrder.stream().map(d->Map.of("id",d.key().id(),"initialOrdinal",initialOrdinals.get(d.key().id()))).toList(),
                    "requiredPairs",trainPairs.stream().map(p->Map.of("first",p.first().key().id(),"second",p.second().key().id(),"kind",p.kind().name())).toList(),
                    "caseCount",trainOrder.size(),"order",seal.prospective()?BProspectiveIdentificationManifest.ORDER:"selector-first,pitch-desc,variant,contrast,side",
                    "scope",completeTrain?"ALL_ORIGINAL_TRAIN":"TEST_SUBSET_NOT_QUALIFIED"));
            for(var parameter:parameters) {
                var evidence=training.evidence().get(parameter);
                var evidenceFields=new LinkedHashMap<>(trainEvidenceFields(evidence));
                if(seal.prospective())evidenceFields.put("skippedErrors","UNVISITED_UNKNOWN_NOT_PROVED_ABSENT");
                writer.publish("train-controls-"+parameterId(parameter)+".json",evidenceFields);
                var fields=new LinkedHashMap<>(configuration(parameter,train.get(parameter),"train",writer));
                fields.put("trainState",evidence.state().name());fields.put("controlReport","train-controls-"+parameterId(parameter)+".json");trainFields.add(fields);
            }
            for(var parameter:shortlist)selectFields.add(configuration(parameter,select.get(parameter),"select",writer));
            var summary=new LinkedHashMap<>(header);summary.put("status",status);
            summary.put("sceneCount",count);summary.put("trainConfigurations",trainFields);
            summary.put("shortlist",shortlist.stream().map(BObservationIdentificationTool::parameterFields).toList());
            summary.put("selectConfigurations",selectFields);summary.put("baselineTrain",baselineTrain.fields());
            summary.put("baselineSelect",baselineSelect.fields());summary.put("featureAvailability",availability);
            summary.put("peakSceneBytes",peak);reads(summary,access);
            summary.put("trainReconstructionReads",driver.reconstructionCount);summary.put("initialTrainScenes",initialOrdinals.size());
            summary.put("trainControlInventory","train-control-inventory.json");summary.put("trainCoverage",completeTrain?"ALL_ORIGINAL_TRAIN":"TEST_SUBSET_NOT_QUALIFIED");
            summary.put("selectionAuthority",full?"FULL_PUBLIC_PROTOCOL":"TEST_SUBSET_NO_FREEZE");
            summary.put("requiredPairCount",requiredPairs.size());summary.put("pairInventory","required-pairs.json");
            summary.put("acceptanceReports",acceptanceReports);
            summary.put("candidateControls",decisions.stream().map(d->Map.of("parameters",parameterFields(d.parameters()),"checks",checkFields(d.controls()))).toList());
            summary.put("pendingGates",List.of("unchanged-regressions-once-no-reselection","production-finalization-and-safety","sealed-holdout"));
            Map<String,Object> pendingArtifact=null;
            if(full&&choice.isPresent()) {
                var chosen=choice.orElseThrow();var artifact=new LinkedHashMap<>(header);artifact.put("status","FROZEN_PENDING_REGRESSION");
                artifact.put("parameters",parameterFields(chosen));artifact.put("modelHash",new BIdentifiedObservationKernel(chosen).identityHash());
                artifact.put("selectScore",scoreValue(select.get(chosen).mean()));artifact.put("baselineSelect",scoreValue(baselineSelect.mean()));
                artifact.put("selectCases",evaluatedSelect.stream().sorted().toList());artifact.put("acceptanceReports",acceptanceReports);
                artifact.put("regressionPolicy","once-after-this-freeze-failure-rejects-no-reselection");artifact.put("holdoutAuthority",false);
                artifact.put("receiptSetHash",writer.receiptSetHash());
                pendingArtifact=artifact;
                summary.put("frozenArtifact","frozen-candidate.json");
            }
            seal.verifyUnchanged();guarded.checkpoint();writer.publish("summary.json",summary);
            // The only freeze publication is the final bounded atomic write, after all refusal/checkpoint paths.
            if(pendingArtifact!=null){seal.verifyUnchanged();guarded.checkpoint();writer.publish("frozen-candidate.json",pendingArtifact);}
            return new RunReceipt(status,output.resolve("summary.json"));
        } catch(Exception failure) {
            var fields=new LinkedHashMap<>(header);fields.put("status","FAILED_NO_CANDIDATE");
            fields.put("reason",failure instanceof CancellationException?"CANCELLED":failure instanceof RunLimitException limit?limit.reason:
                    failure instanceof AttemptMemoryLedger.ResourceLimitException || failure instanceof ProbabilisticInference.MemoryLimit?"MEMORY_OR_WORK_LIMIT":"EVALUATION_FAILED");
            reads(fields,access);
            try {writer.publish("failure.json",fields);}catch(Exception publication){failure.addSuppressed(publication);}
            throw failure;
        } finally {writer.close();}
    }

    static final class RunLimitException extends IllegalStateException {
        final String reason;
        RunLimitException(String reason) {super(reason);this.reason=reason;}
    }
    private static void reads(Map<String,Object> fields,EvaluationAccess access) {
        fields.put("rasterReads",access.rasterReads());fields.put("labelReads",access.labelReads());fields.put("holdoutReads",0);
    }
    private static Map<String,Object> header(Phase phase,RunBinding seal,boolean subset) {
        var result=new LinkedHashMap<String,Object>();
        result.put("schema",1);result.put("phase",phase.name());result.put("manifestHash",seal.manifestHash());
        result.put("sourceHash",seal.sourceHash());result.put("catalogHash",seal.catalogHash());
        result.put("featureVersion",BIdentificationFacts.VERSION);result.put("scope",subset?"TEST_SUBSET":"FULL_PUBLIC_PROTOCOL");
        result.put("rendererCancellation","checkpoints-before-and-after-bounded-render-not-mid-pixel");
        result.put("acceptanceConventionsHash",acceptanceConventionsHash());
        if(seal.prospective()) {
            result.putAll(seal.provenance());
            result.put("protocolPurpose",BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT.name());
            result.put("sourceRegistryHash",seal.registryHash());result.put("executionPinsHash",seal.pinsHash());
            result.put("baseFamilyManifestHash",BObservationTrainRuntimeDiagnosticTool.OLD_MANIFEST);
            result.put("selectionProtocol","train-feasible-fixed-prefix-top4-score-order-v3");
            result.put("trainingControlOrder",BProspectiveIdentificationManifest.ORDER);
            result.put("skippedErrors","UNVISITED_UNKNOWN_NOT_PROVED_ABSENT");
        }
        return result;
    }
    private static Map<String,Object> bindingFields(RunBinding seal) {
        var result=new LinkedHashMap<String,Object>(Map.of("schema",1,"phase","CATALOG","status","CATALOG_REGISTERED","manifestHash",seal.manifestHash(),
                "sourceHash",seal.sourceHash(),"catalogHash",seal.catalogHash(),"rasterReads",0,"labelReads",0));
        if(seal.prospective()) {
            result.putAll(seal.provenance());
            result.put("protocolPurpose",BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT.name());
            result.put("sourceRegistryHash",seal.registryHash());result.put("executionPinsHash",seal.pinsHash());
        }
        return result;
    }
    private static void verifyBinding(Path path,RunBinding seal) throws Exception {
        if(path==null)throw new IllegalArgumentException("A registered CATALOG binding file is required");
        var actual=BIdentificationReceipt.read(path);var expected=bindingFields(seal);
        if(!actual.keySet().equals(expected.keySet()))throw new IllegalArgumentException("Invalid CATALOG binding schema");
        for(var entry:expected.entrySet()) {
            Object value=actual.get(entry.getKey());
            if(entry.getValue() instanceof Number number) {
                if(!(value instanceof Number observed)||observed.doubleValue()!=number.doubleValue())
                    throw new IllegalArgumentException("CATALOG binding changed");
            } else if(!entry.getValue().equals(value))throw new IllegalArgumentException("CATALOG source/catalog/manifest binding changed");
        }
    }
    private static List<CaseDefinition> definitions(BIdentificationCatalog catalog,Phase phase,List<String> subset) {
        var allowed=catalog.definitions().stream()
                .filter(d->phase!=Phase.EXTRACT || d.key().split()!=Split.SELECT)
                .filter(d->subset==null || d.key().split()!=Split.SELECT)
                .filter(d->phase!=Phase.FIT_SELECT || d.key().split()==Split.TRAIN || d.key().split()==Split.SELECT)
                .sorted(Comparator.comparing(d->d.key().id())).toList();
        if(subset==null)return allowed;
        if(subset.isEmpty()||new HashSet<>(subset).size()!=subset.size())throw new IllegalArgumentException("Test subset must contain unique public scene identities");
        var selected=new ArrayList<CaseDefinition>();
        for(String id:subset)selected.add(allowed.stream().filter(d->d.key().id().equals(id)).findFirst()
                .orElseThrow(()->new SecurityException("Test subset cannot open heldout or disallowed scene values")));
        selected.sort(Comparator.comparing(d->d.key().id()));return List.copyOf(selected);
    }
    private static List<BIdentifiedObservationKernel.Parameters> parameters(BIdentificationManifest manifest) {
        var result=new ArrayList<BIdentifiedObservationKernel.Parameters>();
        for(Object value:(List<?>)manifest.fields().get("parameterGrid")) {
            var fields=(Map<?,?>)value;
            result.add(new BIdentifiedObservationKernel.Parameters(((Number)fields.get("G")).doubleValue(),
                    ((Number)fields.get("lambda")).doubleValue(),((Number)fields.get("B")).doubleValue()));
        }
        if(result.size()!=32||new HashSet<>(result).size()!=32)throw new IllegalArgumentException("The one reviewed 32-configuration grid is required");
        return List.copyOf(result);
    }
    private static Map<String,Object> parameterFields(BIdentifiedObservationKernel.Parameters parameters) {
        return Map.of("G",parameters.gain(),"lambda",parameters.nuisanceMultiplier(),"B",parameters.directSpanSlope());
    }
    private record SceneReport(Map<String,Object> fields,BIdentificationScore.SceneScore baseline,
            Map<BIdentifiedObservationKernel.Parameters,BIdentificationScore.SceneScore> scores,long peakBytes,
            Map<BIdentifiedObservationKernel.Parameters,Checks> checks,
            Map<BIdentifiedObservationKernel.Parameters,BIdentificationPairCache.Index> pairs,List<String> acceptanceReports,
            TrainInputBinding input) { }
    @FunctionalInterface private interface SourceGuard {void verify() throws Exception;}
    @FunctionalInterface private interface InputProvider {Scene render(CaseDefinition definition) throws Exception;}
    private record InputIdentity(String sourceHash,String rendererHash,String catalogHash) { }
    private static RealTrainEvaluator realTrainEvaluator(List<RequiredPair> required,SourceGuard guard,InputProvider provider,
            InputIdentity identity,Controls controls,CancellationProbe cancellation,Map<String,Object> availability,
            Writer writer,int sceneCount,long peak) {
        return realTrainEvaluator(required,guard,provider,identity,controls,cancellation,availability,writer,sceneCount,peak,BObservationRuntimeTelemetry.NONE);
    }
    private static RealTrainEvaluator realTrainEvaluator(List<RequiredPair> required,SourceGuard guard,InputProvider provider,
            InputIdentity identity,Controls controls,CancellationProbe cancellation,Map<String,Object> availability,
            Writer writer,int sceneCount,long peak,BObservationRuntimeTelemetry telemetry) {
        SourceGuard measured=guard;
        if(telemetry.enabled())measured=()->{try(var phase=telemetry.begin(SOURCE_VERIFY)){guard.verify();phase.succeeded();}};
        return new RealTrainEvaluator(required,measured,provider,identity,controls,cancellation,availability,writer,sceneCount,peak,telemetry);
    }
    /** One actual driver assembly for the registered run and the explicitly nonqualifying TRAIN diagnostic. */
    private static final class RealTrainEvaluator implements TrainEvaluator {
        final SourceGuard guard;final InputProvider provider;final InputIdentity identity;final Controls controls;
        final CancellationProbe cancellation;final Map<String,Object> availability;final Writer writer;
        final BObservationRuntimeTelemetry telemetry;
        final Map<String,Integer> initialOrdinals=new LinkedHashMap<>();
        final Map<String,TrainInputBinding> initialBindings=new LinkedHashMap<>();
        final Map<String,BIdentificationPairCache.Index> cache=new TreeMap<>();final Set<String> pairCases=new HashSet<>();
        private BIdentifiedObservationKernel.Parameters current;
        private ProbabilisticInferenceResult.Status fixtureReturnedStatus;
        int sceneCount,reconstructionCount,mapSolves,pairChecks;long peak;
        RealTrainEvaluator(List<RequiredPair> required,SourceGuard guard,InputProvider provider,InputIdentity identity,Controls controls,
                CancellationProbe cancellation,Map<String,Object> availability,Writer writer,int sceneCount,long peak,BObservationRuntimeTelemetry telemetry) {
            this.guard=guard;this.provider=provider;this.identity=identity;this.controls=controls;this.cancellation=cancellation;
            this.availability=availability;this.writer=writer;this.sceneCount=sceneCount;this.peak=peak;
            this.telemetry=telemetry;
            for(var pair:required){pairCases.add(pair.first().key().id());pairCases.add(pair.second().key().id());}
        }
        public TrainSceneScore score(CaseDefinition definition,List<BIdentifiedObservationKernel.Parameters> requested) throws Exception {
            if(definition.key().split()!=Split.TRAIN)throw new IllegalArgumentException("Actual TRAIN driver rejects other splits");
            try(var phase=telemetry.begin(SCENE_SCORE)) {
            var report=scene(definition,requested,Phase.FIT_SELECT,guard,provider,identity,controls,cancellation,availability,writer,sceneCount,telemetry);
            initialOrdinals.put(definition.key().id(),sceneCount);initialBindings.put(definition.key().id(),report.input());
            writer.publish("scene-%03d.json".formatted(sceneCount++),report.fields());peak=Math.max(peak,report.peakBytes());
            var result=new TrainSceneScore(report.baseline(),report.scores(),report.checks(),Optional.of(report.input()));
            phase.succeeded();return result;
            }
        }
        public TrainReconstruction reconstruct(CaseDefinition definition,BIdentifiedObservationKernel.Parameters parameter,
                TrainInputBinding frozenInput) throws Exception {
            if(definition.key().split()!=Split.TRAIN||!initialBindings.containsKey(definition.key().id()))
                throw new IllegalArgumentException("TRAIN reconstruction requires its original scored scene");
            if(!parameter.equals(current)){cache.clear();current=parameter;}
            SceneInputs loaded;
            var computation=telemetry.begin(RECONSTRUCTION);
            try {
                loaded=openScene(definition,identity,guard,provider,controls,cancellation,telemetry);reconstructionCount++;
            } catch(Exception|Error failure){computation.close();throw failure;}
            try{computation.pause();}catch(Exception|Error failure){try{loaded.close();}finally{computation.close();}throw failure;}
            return new TrainReconstruction() {
                public TrainInputBinding input(){return loaded.input;}
                public Checks geometry() throws Exception {
                    try(computation) {
                    computation.resume();
                    guard.verify();cancellation.checkpoint();
                    if(!frozenInput.equals(loaded.input)||!frozenInput.equals(initialBindings.get(definition.key().id())))
                        throw new IllegalArgumentException("TRAIN input changed before geometry");
                    Checks checks;
                    try(var work=loaded.owner.child("train-geometry")) {
                        var kernel=new BIdentifiedObservationKernel(parameter);mapSolves++;
                        BObservationGeometry.MapResult outcome;
                        try(var phase=telemetry.begin(MAP_SOLVE)) {
                            outcome=fixtureReturnedStatus==null?BObservationGeometry.solve(loaded.facts,loaded.admitted.lattices(),loaded.scene.evidence().decisionRegion(),
                                    TraceBudgets.defaults(),kernel,BObservationGeometry.Enumeration.MAP_ONLY,cancellation,work):
                                    new BObservationGeometry.MapResult(new BObservationGeometry.PathOutcome(fixtureReturnedStatus,List.of(),17,false,kernel.identityHash()),
                                            List.of(),OptionalDouble.empty(),TraceBudgets.defaults(),BObservationGeometry.Enumeration.MAP_ONLY);
                            telemetry.count(MAP_TRANSITIONS,outcome.path().transitions());requireCompletedEvaluation(outcome);phase.succeeded();
                        }
                        try(var phase=telemetry.begin(ROUTE_CHECK)) {checks=routeChecks(definition,loaded.scene,loaded.profiles,outcome.path(),cancellation,work);phase.succeeded();}
                        String suffix="s%03d-%s".formatted(initialOrdinals.get(definition.key().id()),parameterId(parameter));
                        if(checks.passed()&&pairCases.contains(definition.key().id())) {
                            var summaries=ProbabilisticInference.allocated(work,ProbabilisticInference.listBytes(loaded.profiles.size()),
                                    "TRAIN pair profiles",()->new ArrayList<ProfileSummary>(loaded.profiles.size()));
                            try(var phase=telemetry.begin(PAIR_PROFILES)) {
                            for(int i=0;i<loaded.profiles.size();i++)try(var scratch=work.child("train-pair-profile")) {
                                cancellation.checkpoint();var mixture=kernel.evaluate(loaded.facts.get(i),loaded.admitted.lattices().get(i),cancellation,scratch);
                                summaries.add(inspectProfile(loaded.facts.get(i),loaded.admitted.lattices().get(i),mixture,parameter,cancellation,work).summary());
                                telemetry.count(PAIR_PROFILE_EVALUATIONS,1);
                            }
                            phase.succeeded();}
                            try(var phase=telemetry.begin(PAIR_CACHE_WRITE)) {
                            var handle=BIdentificationPairCache.write(writer.directory,"train-pair-"+suffix,
                                    new SceneSummary(definition,loaded.input.source(),summaries),loaded.input.factsHash(),kernel.identityHash(),writer::publish,cancellation);
                            cache.put(definition.key().id(),BIdentificationPairCache.index("train-pair-"+suffix,handle,writer::publish,cancellation));
                            phase.succeeded();}
                        }
                        writer.publish("train-geometry-"+suffix+".json",Map.of("case",definition.key().id(),"parameters",parameterFields(parameter),
                                "input",inputFields(loaded.input),"rawGeometry",routeFields(outcome,checks),"checks",checkFields(checks)));
                        guard.verify();cancellation.checkpoint();
                    }
                    computation.succeeded();return checks;
                    }
                }
                public void close(){try{peak=Math.max(peak,loaded.ledger.peakBytes()+Writer.METADATA_BYTES);loaded.close();}finally{computation.close();}}
            };
        }
        public Checks coupled(BIdentifiedObservationKernel.Parameters parameter,List<RequiredPair> required) throws Exception {
            try(var phase=telemetry.begin(COUPLED)) {
            guard.verify();cancellation.checkpoint();
            if(!parameter.equals(current))throw new IllegalStateException("TRAIN pair cache belongs to another configuration");
            for(var pair:required)for(var definition:List.of(pair.first(),pair.second())) {
                cancellation.checkpoint();var index=cache.get(definition.key().id());var initial=initialBindings.get(definition.key().id());
                // Each alias binds its OWN initial facts; aliases need not have equal fact hashes.
                if(index!=null&&(initial==null||!initial.source().equals(index.source())||!initial.factsHash().equals(index.factsHash())))
                    throw new IllegalArgumentException("TRAIN pair cache disagrees with its original input snapshot");
            }
            var result=pairs(required,cache,parameter,writer,controls,cancellation,Split.TRAIN,guard,telemetry);
            pairChecks+=result.reports().size();peak=Math.max(peak,result.peakBytes());guard.verify();phase.succeeded();return result.checks();
            }
        }
    }
    /** One live scene graph; its wrapper/hash scratch is included in the reserved 1MiB scratch. */
    private static final class SceneInputs implements AutoCloseable {
        final AttemptMemoryLedger ledger;final AttemptMemoryLedger.Owner owner;final Scene scene;
        final List<ProbabilisticProfile> profiles;final List<BIdentificationFacts.ProfileFacts> facts;
        final AdmittedLattices admitted;final TrainInputBinding input;final long modeCells;
        SceneInputs(AttemptMemoryLedger ledger,AttemptMemoryLedger.Owner owner,Scene scene,List<ProbabilisticProfile> profiles,
                List<BIdentificationFacts.ProfileFacts> facts,AdmittedLattices admitted,TrainInputBinding input,long modeCells) {
            this.ledger=ledger;this.owner=owner;this.scene=scene;this.profiles=profiles;this.facts=facts;
            this.admitted=admitted;this.input=input;this.modeCells=modeCells;
        }
        public void close() {owner.close();if(ledger.currentBytes()!=0)throw new IllegalStateException("Scene ownership did not close");}
    }
    private static SceneInputs openScene(CaseDefinition definition,InputIdentity identity,SourceGuard guard,
            InputProvider provider,Controls controls,CancellationProbe cancellation) throws Exception {
        return openScene(definition,identity,guard,provider,controls,cancellation,BObservationRuntimeTelemetry.NONE);
    }
    private static SceneInputs openScene(CaseDefinition definition,InputIdentity identity,SourceGuard guard,
            InputProvider provider,Controls controls,CancellationProbe cancellation,BObservationRuntimeTelemetry telemetry) throws Exception {
        try(var phase=telemetry.begin(OPEN_SCENE)) {
        guard.verify();cancellation.checkpoint();admitRender(definition);
        var ledger=new AttemptMemoryLedger(controls.memoryBytes()-Writer.METADATA_BYTES);var owner=ledger.rootOwner();
        try {
            owner.reserve(RENDER_BYTES);owner.reserve(1024L*1024);
            cancellation.checkpoint();controls.beforeRender().run();cancellation.checkpoint();guard.verify();
            Scene scene;
            try(var render=telemetry.begin(RENDER)) {scene=provider.render(definition);telemetry.count(RASTER_READS,1);telemetry.count(LABEL_READS,1);render.succeeded();}
            cancellation.checkpoint();guard.verify();
            long outputBytes=0;
            for(var field:scene.evidence().fields().values()) {
                long pixels=Math.multiplyExact((long)field.width(),field.height());
                if(pixels>1_000_000)throw new RunLimitException("RENDER_DIMENSIONS");
                outputBytes=Math.addExact(outputBytes,Math.multiplyExact(pixels,10));
            }
            if(outputBytes>RENDER_BYTES)throw new RunLimitException("RENDER_MEMORY");
            var factory=new ProbabilisticProfileFactory();var policy=EvidenceModelParameters.defaults();
            List<ProbabilisticProfile> profiles;
            try(var measured=telemetry.begin(PROFILE_BUILD)) {
                var chainage=factory.profileChainage(scene.source(),1.5);
                profiles=factory.create(scene.source(),chainage,25,true,scene.evidence(),scene.evidence().fields().get("scalar"),policy,cancellation,true,owner);
                telemetry.count(PROFILES,profiles.size());measured.succeeded();
            }
            if(profiles.size()>4096)throw new RunLimitException("PROFILE_COUNT");
            List<BIdentificationFacts.ProfileFacts> facts;
            try(var measured=telemetry.begin(FACT_EXTRACTION)){facts=new BIdentificationFactsExtractor().extract(scene.evidence(),profiles,cancellation,owner);measured.succeeded();}
            AdmittedLattices admitted;
            try(var measured=telemetry.begin(LATTICE_ADMISSION)){admitted=admitLattices(profiles,policy,cancellation,owner);measured.succeeded();}
            long modeCells=0;
            for(var lattice:admitted.lattices()) {
                cancellation.checkpoint();modeCells=Math.addExact(modeCells,Math.multiplyExact((long)lattice.cells().size(),lattice.components().size()));
                if(modeCells>MAX_MODE_CELLS)throw new RunLimitException("MODE_CELL_WORK");
                telemetry.count(ADMITTED_STATES,lattice.cells().size());
            }
            telemetry.count(MODE_CELLS,modeCells);TrainInputBinding input;
            try(var measured=telemetry.begin(INPUT_FINGERPRINT)) {
                input=new TrainInputBinding(identity.sourceHash(),identity.rendererHash(),identity.catalogHash(),BIdentificationCatalog.recipeHash(definition),
                        sourceKey(definition,scene),BIdentificationReceipt.factsHash(facts,admitted.lattices(),cancellation),latticeHash(admitted.lattices(),cancellation));
                measured.succeeded();
            }
            guard.verify();cancellation.checkpoint();var result=new SceneInputs(ledger,owner,scene,profiles,facts,admitted,input,modeCells);
            phase.succeeded();return result;
        } catch(Exception|Error failure) {owner.close();throw failure;}
        }
    }
    private static String latticeHash(List<ProbabilisticStateLattice> lattices,CancellationProbe cancellation) throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest))) {
            out.writeUTF(EvidenceModelParameters.stateSamplingPolicyVersion());out.writeInt(lattices.size());
            for(var lattice:lattices) {
                cancellation.checkpoint();out.writeDouble(lattice.actualPitchMeters());out.writeBoolean(lattice.resolutionLimited());
                out.writeInt(lattice.cells().size());out.writeInt(lattice.components().size());
                for(var cell:lattice.cells()) {
                    cancellation.checkpoint();out.writeDouble(cell.offsetMeters());out.writeDouble(cell.quadratureWidthMeters());
                    out.writeBoolean(cell.exactAnchor());out.writeBoolean(cell.mandatory());out.writeUTF(cell.branchLabel());
                }
                for(var component:lattice.components()) {
                    cancellation.checkpoint();out.writeUTF(component.id());out.writeUTF(component.evidenceLineage());
                    out.writeUTF(component.kind().name());out.writeDouble(component.priorWeight());out.writeBoolean(component.groupedParent());
                }
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    record AdmittedLattices(List<ProbabilisticStateLattice> lattices,boolean coarsened,int maximumStates,
            int finiteProposals,long nativeForwardBackwardBound,long completeScoreBound,long pairBound) { }
    static AdmittedLattices admitLattices(List<ProbabilisticProfile> profiles,EvidenceModelParameters policy,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        if(profiles==null || profiles.isEmpty() || profiles.size()>4096 || policy==null || cancellation==null)
            throw new IllegalArgumentException("Bounded original profiles and policy are required");
        for(int i=0;i<profiles.size();i++) {
            cancellation.checkpoint();
            if(profiles.get(i)==null || profiles.get(i).profileIndex()!=i)
                throw new IllegalArgumentException("Ordinary B profiles require their original sequential indices");
        }
        var transaction=owner==null?null:owner.child("ordinary-b-lattice-admission");
        try {
            double[] proposals=ProbabilisticContinuationProposals.build(profiles,cancellation,transaction,policy.localization());
            var plan=ProbabilisticLatticeAdmission.select(profiles,proposals,TraceBudgets.defaults(),cancellation,transaction);
            if(!plan.admitted())throw new RunLimitException("ORDINARY_B_ADMISSION_REFUSED");
            var lattices=transaction==null?new ArrayList<ProbabilisticStateLattice>(profiles.size())
                    :ProbabilisticInference.allocated(transaction,ProbabilisticInference.listBytes(profiles.size()),"admitted lattices",
                        ()->new ArrayList<ProbabilisticStateLattice>(profiles.size()));
            var builder=new ProbabilisticStateBuilder();int finite=0;
            for(int i=0;i<profiles.size();i++) {
                cancellation.checkpoint();
                var result=plan.coarsened()
                        ?builder.buildWithReservedProposal(profiles.get(i),plan.maximumStates(i),proposals[i],transaction)
                        :builder.buildWithProposal(profiles.get(i),plan.maximumStates(),proposals[i],transaction);
                if(result.status()!=StateSpaceBuildResult.Status.COMPLETE)throw new RunLimitException("UNCHANGED_STATE_LIMIT");
                lattices.add(result.lattice().orElseThrow());if(Double.isFinite(proposals[i]))finite++;
            }
            List<ProbabilisticStateLattice> frozen=transaction==null?List.copyOf(lattices)
                    :ProbabilisticInference.allocated(transaction,ProbabilisticInference.listBytes(lattices.size()),"frozen admitted lattices",()->List.copyOf(lattices));
            final int count=finite;
            var result=transaction==null?new AdmittedLattices(frozen,plan.coarsened(),plan.maximumStates(),count,
                    plan.nativeForwardBackwardBound(),plan.completeScoreBound(),plan.pairBound())
                    :ProbabilisticInference.allocated(transaction,AttemptMemoryLedger.objectBytes(64),"admitted lattice result",
                        ()->new AdmittedLattices(frozen,plan.coarsened(),plan.maximumStates(),count,
                            plan.nativeForwardBackwardBound(),plan.completeScoreBound(),plan.pairBound()));
            cancellation.checkpoint();if(transaction!=null)transaction.transferTo(owner);
            return result;
        } finally {if(transaction!=null)transaction.close();}
    }

    private static SceneReport scene(CaseDefinition definition,List<BIdentifiedObservationKernel.Parameters> parameters,
            Phase phase,RunBinding seal,EvaluationAccess access,Controls controls,CancellationProbe cancellation,
            Map<String,Object> availability,Writer writer,int sceneIndex) throws Exception {
        return scene(definition,parameters,phase,seal::verifyUnchanged,d->BIdentificationScenes.render(d.key(),access),
                new InputIdentity(seal.sourceHash(),BIdentificationReceipt.sourceMemberHash("service/tracing/probabilistic/BIdentificationScenes.java"),seal.catalogHash()),
                controls,cancellation,availability,writer,sceneIndex);
    }
    private static SceneReport scene(CaseDefinition definition,List<BIdentifiedObservationKernel.Parameters> parameters,
            Phase phase,SourceGuard guard,InputProvider provider,InputIdentity identity,Controls controls,CancellationProbe cancellation,
            Map<String,Object> availability,Writer writer,int sceneIndex) throws Exception {
        return scene(definition,parameters,phase,guard,provider,identity,controls,cancellation,availability,writer,sceneIndex,BObservationRuntimeTelemetry.NONE);
    }
    private static SceneReport scene(CaseDefinition definition,List<BIdentifiedObservationKernel.Parameters> parameters,
            Phase phase,SourceGuard guard,InputProvider provider,InputIdentity identity,Controls controls,CancellationProbe cancellation,
            Map<String,Object> availability,Writer writer,int sceneIndex,BObservationRuntimeTelemetry telemetry) throws Exception {
        SceneReport report;
        try(var loaded=openScene(definition,identity,guard,provider,controls,cancellation,telemetry)) {
            var owner=loaded.owner;var ledger=loaded.ledger;var scene=loaded.scene;var profiles=loaded.profiles;
            var facts=loaded.facts;var admitted=loaded.admitted;var lattices=admitted.lattices();var policy=EvidenceModelParameters.defaults();
            countAvailability(facts,availability,cancellation);
            var fields=new LinkedHashMap<String,Object>();fields.put("id",definition.key().id());fields.put("split",definition.key().split().name());
            fields.put("recipeHash",BIdentificationCatalog.recipeHash(definition));
            fields.put("factsHash",loaded.input.factsHash());fields.put("latticeHash",loaded.input.latticeHash());
            fields.put("inputBinding",inputFields(loaded.input));
            fields.put("source",sourceFields(loaded.input.source()));fields.put("profileCount",profiles.size());fields.put("modeCellWorkPerConfiguration",loaded.modeCells);
            fields.put("latticePolicy",EvidenceModelParameters.stateSamplingPolicyVersion());
            fields.put("reliabilityPolicy",EvidenceModelParameters.reliabilityPolicyVersion());
            var budgets=TraceBudgets.defaults();
            fields.put("admissionBudgets",Map.of("name","TraceBudgets.defaults","maximumStatesPerProfile",budgets.maximumStatesPerProfile(),
                    "maximumPairVisits",budgets.maximumPairVisits(),"maximumTransitions",budgets.maximumTransitions(),
                    "maximumRawAlternatives",budgets.maximumRawAlternatives(),"maximumDistinctAlternatives",budgets.maximumDistinctAlternatives()));
            fields.put("admission",Map.of("coarsened",admitted.coarsened(),"maximumStates",admitted.maximumStates(),
                    "finiteContinuationProposals",admitted.finiteProposals(),"nativeForwardBackwardBound",bound(admitted.nativeForwardBackwardBound()),
                    "completeScoreBound",bound(admitted.completeScoreBound()),"pairBound",bound(admitted.pairBound())));
            BIdentificationScore.SceneScore baseline=null;var scores=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,BIdentificationScore.SceneScore>();
            var checks=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,Checks>();
            var pairs=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,BIdentificationPairCache.Index>();
            var acceptanceReports=new ArrayList<String>();boolean assess=phase==Phase.FIT_SELECT&&definition.key().split()==Split.SELECT;
            if(phase==Phase.FIT_SELECT) {
                try(var measured=telemetry.begin(BASELINE_SCORE);var baselineOwner=owner.child("baseline-score")) {
                    var baselineRows=ProbabilisticInference.allocated(baselineOwner,ProbabilisticInference.listBytes(profiles.size()),"baseline rows",
                            ()->new ArrayList<InferenceProfile>(profiles.size()));
                    for(int i=0;i<profiles.size();i++) {
                        cancellation.checkpoint();baselineRows.add(new ProbabilisticObservationModel().evaluate(profiles.get(i),lattices.get(i),policy,true,baselineOwner));
                    }
                    baseline=BIdentificationScore.scoreBaseline(scene,profiles,lattices,baselineRows,cancellation);
                    measured.succeeded();
                }
                if(assess)try(var geometryOwner=owner.child("baseline-raw-geometry")) {
                    var outcome=BObservationGeometry.solve(facts,lattices,scene.evidence().decisionRegion(),TraceBudgets.defaults(),null,
                            BObservationGeometry.Enumeration.MAP_ONLY,cancellation,geometryOwner);
                    requireCompletedEvaluation(outcome);
                    fields.put("baselineRawGeometry",routeFields(outcome,routeChecks(definition,scene,profiles,outcome.path(),cancellation,geometryOwner)));
                }
                fields.put("baseline",scoreFields(baseline));var configurationScores=new ArrayList<Map<String,Object>>();
                for(var parameter:parameters)try(var candidateOwner=owner.child("candidate-score")) {
                    cancellation.checkpoint();var kernel=new BIdentifiedObservationKernel(parameter);
                    var profileSummaries=assess?ProbabilisticInference.allocated(candidateOwner,ProbabilisticInference.listBytes(profiles.size()),"pair profiles",
                            ()->new ArrayList<ProfileSummary>(profiles.size())):null;
                    var structural=new CheckTotals();
                    var rows=ProbabilisticInference.allocated(candidateOwner,ProbabilisticInference.listBytes(profiles.size()),"candidate rows",
                            ()->new ArrayList<BObservationMixture.ReducedRows>(profiles.size()));
                    try(var measured=telemetry.begin(CANDIDATE_PROFILES)) {
                    for(int i=0;i<profiles.size();i++) {
                        cancellation.checkpoint();
                        try(var profileOwner=candidateOwner.child("profile-mixture-scratch")) {
                            var mixture=kernel.evaluate(facts.get(i),lattices.get(i),cancellation,profileOwner);
                            if(phase==Phase.FIT_SELECT) {
                                var inspected=inspectProfile(facts.get(i),lattices.get(i),mixture,parameter,cancellation,candidateOwner);
                                rows.add(inspected.rows());if(assess)profileSummaries.add(inspected.summary());structural.add(inspected.checks());
                            } else rows.add(mixture.reduce(lattices.get(i),cancellation,candidateOwner));
                        }
                        telemetry.count(CANDIDATE_PROFILE_EVALUATIONS,1);
                    }
                    measured.succeeded();}
                    BIdentificationScore.SceneScore score;
                    try(var measured=telemetry.begin(PROPER_SCORE)){score=BIdentificationScore.score(scene,profiles,lattices,rows,cancellation);measured.succeeded();}
                    scores.put(parameter,score);
                    checks.put(parameter,structural.finish());
                    configurationScores.add(Map.of("parameters",parameterFields(parameter),"modelHash",kernel.identityHash(),"score",scoreFields(score),
                            "profileChecks",checkFields(structural.finish())));
                    if(assess) {
                        var outcome=BObservationGeometry.solve(facts,lattices,scene.evidence().decisionRegion(),TraceBudgets.defaults(),kernel,
                                BObservationGeometry.Enumeration.MAP_ONLY,cancellation,candidateOwner);
                        requireCompletedEvaluation(outcome);
                        var geometryChecks=routeChecks(definition,scene,profiles,outcome.path(),cancellation,candidateOwner);structural.add(geometryChecks);
                        String prefix="pair-s%03d-%s".formatted(sceneIndex,parameterId(parameter));
                        // The already-reserved 1MiB scene scratch covers bounded one-profile cache encoding.
                        var handle=BIdentificationPairCache.write(writer.directory,prefix,new SceneSummary(definition,sourceKey(definition,scene),profileSummaries),
                                (String)fields.get("factsHash"),kernel.identityHash(),writer::publish,cancellation);
                        pairs.put(parameter,BIdentificationPairCache.index(prefix,handle,writer::publish,cancellation));checks.put(parameter,structural.finish());
                        String file="acceptance-s%03d-%s.json".formatted(sceneIndex,parameterId(parameter));
                        writer.publish(file,Map.of("case",definition.key().id(),"parameters",parameterFields(parameter),"modelHash",kernel.identityHash(),
                                "factsHash",fields.get("factsHash"),"checks",checkFields(structural.finish()),"rawGeometry",routeFields(outcome,geometryChecks),
                                "pairChunks",handle.chunks().size(),"source",sourceFields(handle.source())));
                        acceptanceReports.add(file);
                    }
                }
                fields.put("configurationScores",configurationScores);
            }
            fields.put("peakSceneBytes",ledger.peakBytes()+Writer.METADATA_BYTES);guard.verify();cancellation.checkpoint();
            report=new SceneReport(Collections.unmodifiableMap(fields),baseline,Collections.unmodifiableMap(scores),ledger.peakBytes()+Writer.METADATA_BYTES,
                    Collections.unmodifiableMap(checks),Collections.unmodifiableMap(pairs),List.copyOf(acceptanceReports),loaded.input);
        }
        return report;
    }
    private static void requireCompletedEvaluation(BObservationGeometry.MapResult outcome) {
        if(outcome.path().status()==ProbabilisticInferenceResult.Status.RESOURCE_LIMIT)throw new RunLimitException("INFERENCE_RESOURCE_LIMIT");
        if(outcome.path().status()==ProbabilisticInferenceResult.Status.NUMERIC_FAILURE)throw new RunLimitException("INFERENCE_NUMERIC_FAILURE");
    }
    private static void admitRender(CaseDefinition definition) {
        if(definition.sourcePitchMeters()<.5 || definition.oversampling()>2)throw new RunLimitException("UNREVIEWED_RENDER_SIZE");
        long width=(long)Math.ceil(184/definition.sourcePitchMeters())+2;
        long height=(long)Math.ceil(64/definition.sourcePitchMeters())+2;
        long outputWidth=(width-1)*definition.oversampling()+1,outputHeight=(height-1)*definition.oversampling()+1;
        long operations=Math.addExact(Math.multiplyExact(width,height),Math.multiplyExact(32,Math.multiplyExact(outputWidth,outputHeight)));
        if(operations>8_000_000)throw new RunLimitException("RENDER_WORK");
    }
    private static String parameterId(BIdentifiedObservationKernel.Parameters p) {
        return "G%d-L%d-B%d".formatted((int)p.gain(),(int)p.nuisanceMultiplier(),(int)p.directSpanSlope());
    }
    private static String acceptanceConventionsHash() {
        try{return BIdentificationReceipt.sourceMemberHash("service/tracing/probabilistic/BIdentificationAcceptance.java");}
        catch(Exception failure){throw new IllegalStateException("Acceptance source binding unavailable",failure);}
    }
    static SourceKey sourceKey(CaseDefinition definition,Scene scene) {
        var evidence=scene.evidence();String identity=evidence.sourceIdentity();
        if(!identity.matches("analytic-native:[0-9a-f]{64}"))throw new IllegalArgumentException("Frozen original native-content receipt required");
        var provenance=evidence.resampling();var fields=new TreeMap<String,Object>();
        fields.put("sourceTransformKind",provenance.sourceTransformKind());fields.put("sourceTransformIdentity",provenance.sourceTransformIdentity());
        fields.put("inputWidth",provenance.inputWidth());fields.put("inputHeight",provenance.inputHeight());
        fields.put("inputCoordinates",provenance.inputCoordinateConvention());fields.put("scalarOrder",provenance.scalarOrder());
        fields.put("validityRule",provenance.validityRule());fields.put("inputSamplingPitchMeters",definition.sourcePitchMeters());
        fields.put("phase",definition.phase());fields.put("scalarLineage",evidence.fields().get("scalar").lineage().toString());
        boolean known=evidence.resolution().nativePitchMeters().isPresent();
        fields.put("nativePitchMeters",known?evidence.resolution().nativePitchMeters().getAsDouble():"UNAVAILABLE");
        return new SourceKey(definition.key().sourceGroupId(),identity.substring("analytic-native:".length()),
                BIdentificationReceipt.hash(BIdentificationReceipt.canonical(fields).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                known?Capability.KNOWN_NATIVE:Capability.PHYSICAL_SAMPLING_ONLY);
    }
    private static Map<String,Object> sourceFields(SourceKey key) {
        return Map.of("sourceGroup",key.sourceGroup(),"nativeHash",key.nativeHash(),"nativeInputFrame",key.nativeFrame(),"capability",key.capability().name());
    }
    private static Optional<GeometrySelector> selector(CaseDefinition definition) {
        return switch(definition.contrastId()) {
            case "resolved-short-apex-vs-stair-step-alias" -> Optional.of(GeometrySelector.APEX_X60_PM2);
            case "displaced-strong-corridor-vs-nearby-faint-strand" -> Optional.of(GeometrySelector.DISPLACED_X40_80);
            case "sparse-crossing-noisy-strands-vs-persistent-parallel" -> definition.side().equals("right")
                    ?Optional.of(GeometrySelector.PARALLEL_SEGMENTS_X40_80):Optional.empty();
            default -> Optional.empty();
        };
    }
    static boolean usableRawStatus(ProbabilisticInferenceResult.Status status) {
        return status==ProbabilisticInferenceResult.Status.COMPLETE||status==ProbabilisticInferenceResult.Status.AMBIGUOUS
                ||status==ProbabilisticInferenceResult.Status.REVIEW_REQUIRED;
    }
    private static Checks routeChecks(CaseDefinition definition,Scene scene,List<ProbabilisticProfile> profiles,
            BObservationGeometry.PathOutcome outcome,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        var totals=new CheckTotals();
        totals.add(new Checks(List.of(new Check(Code.RAW_ROUTE,usableRawStatus(outcome.status())?Verdict.PASS:Verdict.FAIL,1))));
        totals.add(exactAnchors(profiles,outcome.points(),cancellation));
        var registered=selector(definition);
        if(registered.isPresent()) {
            if(scene.evidence().resolution().nativePitchMeters().isEmpty())
                totals.add(new Checks(List.of(new Check(Code.RAW_ROUTE,Verdict.UNAVAILABLE,1))));
            else totals.add(geometry(registered.orElseThrow(),scene,outcome.points(),
                    scene.evidence().resolution().nativePitchMeters().getAsDouble(),cancellation,owner));
        }
        return totals.finish();
    }
    private static Map<String,Object> routeFields(BObservationGeometry.MapResult enumeration,Checks checks) {
        var outcome=enumeration.path();
        var budgets=TraceBudgets.defaults();
        var fields=new LinkedHashMap<String,Object>(Map.of("status",outcome.status().name(),"usableObservedRoute",usableRawStatus(outcome.status()),"pointCount",outcome.points().size(),
                "evaluatedTransitions",outcome.transitions(),"alternativeSearchTruncated",outcome.truncated(),"modelHash",outcome.modelHash(),
                "checks",checkFields(checks),"budgets",Map.of("maximumStatesPerProfile",budgets.maximumStatesPerProfile(),
                        "maximumPairVisits",budgets.maximumPairVisits(),"maximumTransitions",budgets.maximumTransitions(),
                        "maximumRawAlternatives",budgets.maximumRawAlternatives(),"maximumDistinctAlternatives",budgets.maximumDistinctAlternatives()),
                "authority","raw-graph-only-no-production-final-safety"));
        fields.put("enumeration",enumeration.enumeration().name());
        fields.put("admissionBudgets","TraceBudgets.defaults-96-8M-128M-32-8-unchanged-graph");
        fields.put("solverRequest",Map.of("maximumStatesPerProfile",enumeration.solverBudgets().maximumStatesPerProfile(),
                "maximumPairVisits",enumeration.solverBudgets().maximumPairVisits(),"maximumTransitions",enumeration.solverBudgets().maximumTransitions(),
                "maximumRawAlternatives",enumeration.solverBudgets().maximumRawAlternatives(),"maximumDistinctAlternatives",enumeration.solverBudgets().maximumDistinctAlternatives()));
        fields.put("mapStateIndices",enumeration.states());fields.put("mapEnergy",scoreValue(enumeration.energy()));
        fields.put("alternativeCompletionAuthority",false);return fields;
    }
    private static List<Map<String,Object>> checkFields(Checks checks) {
        return checks.checks().stream().map(check->{var fields=new LinkedHashMap<String,Object>();
            fields.put("code",check.code().name());fields.put("verdict",check.verdict().name());fields.put("count",check.count());
            fields.put("observed",scoreValue(check.observed()));fields.put("limit",scoreValue(check.limit()));return (Map<String,Object>)fields;
        }).toList();
    }
    private static final class CheckTotals {
        final EnumMap<Code,Check> values=new EnumMap<>(Code.class);
        void add(Checks checks) {
            if(checks==null)throw new IllegalArgumentException("Missing registered control result");
            for(var check:checks.checks())values.merge(check.code(),check,(a,b)->new Check(a.code(),
                    a.verdict()==Verdict.FAIL||b.verdict()==Verdict.FAIL?Verdict.FAIL:
                    a.verdict()==Verdict.UNAVAILABLE||b.verdict()==Verdict.UNAVAILABLE?Verdict.UNAVAILABLE:Verdict.PASS,
                    Math.addExact(a.count(),b.count()),a.observed().isEmpty()?b.observed():b.observed().isEmpty()?a.observed():
                        OptionalDouble.of(Math.max(a.observed().getAsDouble(),b.observed().getAsDouble())),b.limit().isPresent()?b.limit():a.limit()));
        }
        Checks finish(){return new Checks(new ArrayList<>(values.values()));}
    }
    private record PairReport(Checks checks,List<String> reports,long peakBytes) { }
    record RequiredPair(CaseDefinition first,CaseDefinition second,PairKind kind) { }
    static List<RequiredPair> requiredPairs(List<CaseDefinition> definitions,CancellationProbe cancellation) {
        if(definitions==null||definitions.size()>512)throw new IllegalArgumentException("Bounded pair inventory required");
        var result=new ArrayList<RequiredPair>();
        for(var a:definitions)for(var b:definitions) {
            cancellation.checkpoint();
            if(a.key().split()!=Split.SELECT||b.key().split()!=Split.SELECT)throw new IllegalArgumentException("SELECT pair inventory only");
            boolean same=a.key().sourceGroupId().equals(b.key().sourceGroupId())&&a.key().geometryGroupId().equals(b.key().geometryGroupId())
                    &&a.contrastId().equals(b.contrastId())&&a.phase()==b.phase()&&a.sourcePitchMeters()==b.sourcePitchMeters();
            boolean over=same&&a.variantId().equals("v0")&&b.variantId().equals("v6")&&a.side().equals(b.side())
                    &&a.reversed()==b.reversed()&&a.oversampling()==1&&b.oversampling()==2;
            boolean reverse=same&&a.contrastId().equals("multiple-physical-pitches-vs-reversed-source-order")
                    &&a.variantId().equals(b.variantId())&&a.side().equals("left")&&b.side().equals("right")
                    &&a.oversampling()==b.oversampling()&&!a.reversed()&&b.reversed();
            if(over||reverse)result.add(new RequiredPair(a,b,over?PairKind.OVERSAMPLING:PairKind.REVERSAL));
        }
        result.sort(Comparator.comparing((RequiredPair p)->p.first().key().id()).thenComparing(p->p.second().key().id()));
        return List.copyOf(result);
    }
    /** Both cohorts use the same physical pair rules; diagnostic/holdout rows cannot enter this inventory. */
    static List<RequiredPair> requiredPairs(List<CaseDefinition> definitions,Split expected,CancellationProbe cancellation) {
        if(expected!=Split.TRAIN&&expected!=Split.SELECT)throw new IllegalArgumentException("Explicit TRAIN or SELECT required");
        if(definitions==null||definitions.size()>512)throw new IllegalArgumentException("Bounded pair inventory required");
        var result=new ArrayList<RequiredPair>();
        for(var a:definitions)for(var b:definitions) {
            cancellation.checkpoint();if(a.key().split()!=expected||b.key().split()!=expected)
                throw new IllegalArgumentException("Pair cohort disagrees with expected split");
            boolean same=a.key().sourceGroupId().equals(b.key().sourceGroupId())&&a.key().geometryGroupId().equals(b.key().geometryGroupId())
                    &&a.contrastId().equals(b.contrastId())&&a.phase()==b.phase()&&a.sourcePitchMeters()==b.sourcePitchMeters();
            boolean over=same&&a.variantId().equals("v0")&&b.variantId().equals("v6")&&a.side().equals(b.side())
                    &&a.reversed()==b.reversed()&&a.oversampling()==1&&b.oversampling()==2;
            boolean reverse=same&&a.contrastId().equals("multiple-physical-pitches-vs-reversed-source-order")
                    &&a.variantId().equals(b.variantId())&&a.side().equals("left")&&b.side().equals("right")
                    &&a.oversampling()==b.oversampling()&&!a.reversed()&&b.reversed();
            if(over||reverse)result.add(new RequiredPair(a,b,over?PairKind.OVERSAMPLING:PairKind.REVERSAL));
        }
        result.sort(Comparator.comparing((RequiredPair p)->p.first().key().id()).thenComparing(p->p.second().key().id()));
        return List.copyOf(result);
    }
    private static PairReport pairs(List<RequiredPair> required,Map<String,BIdentificationPairCache.Index> cache,
            BIdentifiedObservationKernel.Parameters parameter,Writer writer,Controls controls,CancellationProbe cancellation,
            Split expected,SourceGuard guard) throws Exception {
        return pairs(required,cache,parameter,writer,controls,cancellation,expected,guard,BObservationRuntimeTelemetry.NONE);
    }
    private static PairReport pairs(List<RequiredPair> required,Map<String,BIdentificationPairCache.Index> cache,
            BIdentifiedObservationKernel.Parameters parameter,Writer writer,Controls controls,CancellationProbe cancellation,
            Split expected,SourceGuard guard,BObservationRuntimeTelemetry telemetry) throws Exception {
        if(expected!=Split.TRAIN&&expected!=Split.SELECT)throw new IllegalArgumentException("Explicit pair cohort required");
        var totals=new CheckTotals();var files=new ArrayList<String>();long peak=0,work=0;int pairCount=0;
        for(var pair:required) {
            guard.verify();cancellation.checkpoint();if(++work>8_000_000)throw new RunLimitException("PAIR_INVENTORY_WORK");
            var a=pair.first();var b=pair.second();
            Checks checks;var first=cache.get(a.key().id());var second=cache.get(b.key().id());
            var ledger=new AttemptMemoryLedger(controls.memoryBytes()-Writer.METADATA_BYTES);
            try(var owner=ledger.rootOwner()) {
                var binding=pairBindingForEvaluation(pair,first,second,parameter,expected);
                {
                    SceneSummary left,right;
                    try(var measured=telemetry.begin(PAIR_CACHE_READ)) {
                        var ah=BIdentificationPairCache.readIndex(writer.directory,first,cancellation,owner);
                        var bh=BIdentificationPairCache.readIndex(writer.directory,second,cancellation,owner);
                        left=BIdentificationPairCache.read(writer.directory,ah,cancellation,owner);
                        right=BIdentificationPairCache.read(writer.directory,bh,cancellation,owner);measured.succeeded();
                    }
                    try(var measured=telemetry.begin(PAIR_COMPARE)){checks=comparePair(binding,left,right,cancellation);telemetry.count(PAIR_CHECKS,1);measured.succeeded();}
                }
                peak=Math.max(peak,ledger.peakBytes()+Writer.METADATA_BYTES);
            }
            if(ledger.currentBytes()!=0)throw new IllegalStateException("Pair-cache ownership did not close");
            totals.add(checks);
            String file=(expected==Split.TRAIN?"train-":"")+"pair-check-%s-%03d.json".formatted(parameterId(parameter),pairCount++);
            var fields=new LinkedHashMap<String,Object>();fields.put("first",a.key().id());fields.put("second",b.key().id());
            fields.put("kind",pair.kind().name());fields.put("parameters",parameterFields(parameter));
            fields.put("coverage",first==null||second==null?"MISSING_REQUIRED_SCENE":"BOTH_REQUIRED_SCENES_EVALUATED");
            fields.put("checks",checkFields(checks));fields.put("peakPairBytes",peak);
            if(first!=null){fields.put("firstSource",sourceFields(first.source()));fields.put("firstFactsHash",first.factsHash());fields.put("modelHash",first.modelHash());}
            if(second!=null){fields.put("secondSource",sourceFields(second.source()));fields.put("secondFactsHash",second.factsHash());}
            guard.verify();cancellation.checkpoint();writer.publish(file,fields);files.add(file);
        }
        if(pairCount==0)totals.add(new Checks(List.of(new Check(Code.SOURCE_IDENTITY,Verdict.UNAVAILABLE,0))));
        return new PairReport(totals.finish(),List.copyOf(files),peak);
    }
    /** Missing/stale cache authority is an experiment error; only verified comparePair results are quality judgments. */
    static PairBinding pairBindingForEvaluation(RequiredPair pair,BIdentificationPairCache.Index first,
            BIdentificationPairCache.Index second,BIdentifiedObservationKernel.Parameters parameter,Split expected) {
        if(pair==null||first==null||second==null||parameter==null)throw new IllegalArgumentException("Missing required pair cache index");
        String model=new BIdentifiedObservationKernel(parameter).identityHash();
        if(!pair.first().equals(first.definition())||!pair.second().equals(second.definition())
                ||!model.equals(first.modelHash())||!model.equals(second.modelHash())
                ||first.factsHash()==null||!first.factsHash().matches("[0-9a-f]{64}")
                ||second.factsHash()==null||!second.factsHash().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Stale pair cache case/model/snapshot metadata");
        var binding=bindPair(pair.first(),first.source(),pair.second(),second.source(),expected)
                .orElseThrow(()->new IllegalArgumentException("Required pair source/frame binding failed"));
        if(binding.kind()!=pair.kind())throw new IllegalArgumentException("Pair cache kind disagrees with its registered inventory");
        return binding;
    }
    private static Map<String,Object> availability() {
        var result=new TreeMap<String,Object>();
        for(var feature:BIdentificationFacts.Feature.values()) {
            var counts=new TreeMap<String,Object>();
            for(var state:BIdentificationFacts.Availability.values())counts.put(state.name(),0L);
            result.put(feature.name(),counts);
        }
        return result;
    }
    @SuppressWarnings("unchecked") private static void countAvailability(List<BIdentificationFacts.ProfileFacts> profiles,
            Map<String,Object> counts,CancellationProbe cancellation) {
        for(var profile:profiles)for(var mode:profile.modes())for(var fact:mode.values()) {
            cancellation.checkpoint();var table=(Map<String,Object>)counts.get(fact.feature().name());
            String state=fact.availability().name();table.put(state,Math.addExact(((Number)table.get(state)).longValue(),1));
        }
    }
    private static Object scoreValue(OptionalDouble score) {
        return score.isEmpty()?"UNAVAILABLE":Double.isFinite(score.getAsDouble())?score.getAsDouble():"POSITIVE_INFINITY";
    }
    private static Object bound(long value) {return value<0?"UNAVAILABLE":value;}
    private static Map<String,Object> scoreFields(BIdentificationScore.SceneScore score) {
        return Map.of("meanLogLoss",scoreValue(score.meanLogLoss()),"available",score.available(),"ambiguous",score.ambiguous(),
                "unavailable",score.unavailable(),"anchors",score.anchors(),"scoredLengthMeters",score.scoredLengthMeters());
    }
    private static final class Mean {
        int count;double mean;boolean infinite;
        void add(double value) {count++;if(!Double.isFinite(value))infinite=true;else if(!infinite)mean+=(value-mean)/count;}
        OptionalDouble value() {return count==0?OptionalDouble.empty():OptionalDouble.of(infinite?Double.POSITIVE_INFINITY:mean);}
    }
    static final class Aggregate {
        final Map<String,Map<String,Mean>> families=new TreeMap<>();
        long scenes,available,ambiguous,unavailable,anchors;
        void add(CaseDefinition definition,BIdentificationScore.SceneScore score) {
            scenes++;available+=score.available();ambiguous+=score.ambiguous();unavailable+=score.unavailable();anchors+=score.anchors();
            String group=definition.key().geometryGroupId()+"|"+definition.key().sourceGroupId();
            var mean=families.computeIfAbsent(definition.contrastId(),k->new TreeMap<>()).computeIfAbsent(group,k->new Mean());
            score.meanLogLoss().ifPresent(mean::add);
        }
        OptionalDouble mean() {
            var total=new Mean();
            for(var groups:families.values()) {
                var family=new Mean();for(var group:groups.values())group.value().ifPresent(family::add);
                family.value().ifPresent(total::add);
            }
            return total.value();
        }
        boolean finite() {return mean().isPresent()&&Double.isFinite(mean().orElseThrow());}
        Map<String,Object> fields() {
            var result=new LinkedHashMap<String,Object>();result.put("meanLogLoss",scoreValue(mean()));result.put("scenes",scenes);
            result.put("available",available);result.put("ambiguous",ambiguous);result.put("unavailable",unavailable);result.put("anchors",anchors);
            var familyFields=new TreeMap<String,Object>();
            for(var family:families.entrySet()) {
                var groups=new TreeMap<String,Object>();
                for(var group:family.getValue().entrySet())groups.put(group.getKey(),Map.of("sceneScores",group.getValue().count,"meanLogLoss",scoreValue(group.getValue().value())));
                familyFields.put(family.getKey(),groups);
            }
            result.put("groupsByContrast",familyFields);return result;
        }
    }
    private static Map<String,Object> configuration(BIdentifiedObservationKernel.Parameters parameter,Aggregate aggregate,String phase,Writer writer) throws Exception {
        String file="%s-G%d-L%d-B%d.json".formatted(phase,(int)parameter.gain(),(int)parameter.nuisanceMultiplier(),(int)parameter.directSpanSlope());
        var detailed=new LinkedHashMap<>(aggregate.fields());detailed.put("parameters",parameterFields(parameter));
        detailed.put("rankableFinite",aggregate.finite());writer.publish(file,detailed);
        return Map.of("parameters",parameterFields(parameter),"meanLogLoss",scoreValue(aggregate.mean()),"rankableFinite",aggregate.finite(),"report",file,
                "available",aggregate.available,"ambiguous",aggregate.ambiguous,"unavailable",aggregate.unavailable);
    }
    static final class Writer implements AutoCloseable {
        @FunctionalInterface interface Publication {void publish(Path path,Map<String,Object> fields) throws Exception;}
        private static final Publication ORDINARY_PUBLICATION=BIdentificationReceipt::publish;
        static final long METADATA_BYTES=1024L*1024;
        final Path directory;long bytes,retainedOrdinaryReservationBytes;String chain="0".repeat(64);
        final AttemptMemoryLedger.Owner metadata;
        final Publication publication;
        BObservationRuntimeTelemetry telemetry=BObservationRuntimeTelemetry.NONE;
        Writer(Path directory,long limit) {
            this(directory,limit,ORDINARY_PUBLICATION);
        }
        Writer(Path directory,long limit,Publication publication) {
            this.publication=Objects.requireNonNull(publication);
            this.directory=directory;metadata=new AttemptMemoryLedger(limit).rootOwner();
            try{metadata.reserve(METADATA_BYTES);}catch(RuntimeException failure){metadata.close();throw failure;}
            // Current sealed catalog's indices, definitions, score/control totals and receipt-name lists.
            // Scene/pair ledgers subtract this live reservation from the same hard64MiB envelope.
        }
        String receiptSetHash(){return chain;}
        void publish(String file,Map<String,Object> fields) throws Exception {
            try(var measured=telemetry.begin(RECEIPT_WRITE)) {
            byte[] encoded=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long length=encoded.length;
            if(length>MAX_DISK_BYTES-bytes)throw new RunLimitException("DISK_LIMIT");
            bytes+=length;
            try{publication.publish(directory.resolve(file),fields);}
            catch(Exception|Error failure){retainedOrdinaryReservationBytes+=length;throw failure;}
            chain=BIdentificationReceipt.hash((chain+"\n"+file+"\n"+BIdentificationReceipt.hash(encoded)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            telemetry.count(ORDINARY_RECEIPTS,1);telemetry.count(ORDINARY_BYTES,length);measured.succeeded();
            }
        }
        public void close(){metadata.close();}
    }
    private static void newDirectory(Path output) throws Exception {
        Path absolute=output.toAbsolutePath().normalize();
        for(Path ancestor=absolute.getParent();ancestor!=null;ancestor=ancestor.getParent())
            if(Files.isSymbolicLink(ancestor))throw new IllegalArgumentException("Output ancestors must not be symbolic links");
        if(absolute.getParent()!=null)Files.createDirectories(absolute.getParent());
        Files.createDirectory(absolute);
    }
}

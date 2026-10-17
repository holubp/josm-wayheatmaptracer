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

/** Explicit offline identification; a frozen score/SELECT artifact still requires unchanged regression. */
public final class BObservationIdentificationTool {
    private static final long RENDER_BYTES=32L*1024*1024,MAX_MODE_CELLS=8_000_000,MAX_DISK_BYTES=2L*1024*1024*1024;
    // Reviewed opaque renderer bound: source changes require a fresh accounting review.
    private static final String BOUNDED_RENDERER_SHA256="abe854d810ccfd3997a9ace4bd67464ee9ec0b22d414126b8038d933bc7140e8";
    record RunReceipt(String status, Path summary) { }
    record CandidateDecision(BIdentifiedObservationKernel.Parameters parameters,OptionalDouble selectScore,
            BIdentificationAcceptance.Checks controls) { }
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
        CancellationProbe guarded=()->{
            controls.cancellation().checkpoint();
            if(controls.clock().getAsLong()-started>=controls.watchdogNanos())
                throw new RunLimitException("TIME");
            return false;
        };
        guarded.checkpoint();
        var catalog=publicCatalog();
        var seal=BIdentificationManifest.register(manifest,BIdentificationReceipt.sourceHash());
        if(!BOUNDED_RENDERER_SHA256.equals(BIdentificationReceipt.sourceMemberHash("service/tracing/probabilistic/BIdentificationScenes.java")))
            throw new IllegalArgumentException("Renderer changed; its 32MiB accounting bound is unreviewed");
        if(phase==Phase.CATALOG) {
            if(binding!=null)throw new IllegalArgumentException("CATALOG creates its binding; it does not consume one");
        } else verifyBinding(binding,seal);
        List<CaseDefinition> definitions=definitions(catalog,phase,controls.caseIds());
        if(catalog.caseCount()>((Number)seal.manifest().fields().get("maxScenes")).intValue())
            throw new IllegalArgumentException("Catalog exceeds the registered scene ceiling");
        var parameters=parameters(seal.manifest());
        var requiredPairs=requiredPairs(catalog.definitions().stream().filter(d->d.key().split()==Split.SELECT).toList(),guarded);
        seal.verifyUnchanged();guarded.checkpoint();
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
            for(var definition:definitions)if(definition.key().split()==Split.TRAIN) {
                var report=scene(definition,parameters,phase,seal,access,controls,guarded,availability,writer,count);
                writer.publish("scene-%03d.json".formatted(count++),report.fields());peak=Math.max(peak,report.peakBytes());
                baselineTrain.add(definition,report.baseline());
                for(var parameter:parameters)train.get(parameter).add(definition,report.scores().get(parameter));
            }
            var ranking=new ArrayList<>(parameters);
            ranking.removeIf(p->!train.get(p).finite());
            ranking.sort(Comparator.comparingDouble((BIdentifiedObservationKernel.Parameters p)->train.get(p).mean().orElseThrow())
                    .thenComparingDouble(BIdentifiedObservationKernel.Parameters::gain)
                    .thenComparingDouble(BIdentifiedObservationKernel.Parameters::nuisanceMultiplier)
                    .thenComparingDouble(BIdentifiedObservationKernel.Parameters::directSpanSlope));
            if(ranking.isEmpty())throw new RunLimitException("NO_FINITE_TRAIN_SCORE");
            List<BIdentifiedObservationKernel.Parameters> shortlist=List.copyOf(ranking.subList(0,Math.min(4,ranking.size())));
            var acceptance=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,CheckTotals>();
            var cached=new LinkedHashMap<BIdentifiedObservationKernel.Parameters,Map<String,BIdentificationPairCache.Index>>();
            var acceptanceReports=new ArrayList<String>();var evaluatedSelect=new HashSet<String>();
            for(var parameter:shortlist){acceptance.put(parameter,new CheckTotals());cached.put(parameter,new TreeMap<>());}
            for(var parameter:shortlist)select.put(parameter,new Aggregate());
            for(var definition:definitions)if(definition.key().split()==Split.SELECT) {
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
                var pairReport=pairs(requiredPairs,cached.get(parameter),parameter,writer,controls,guarded);
                acceptance.get(parameter).add(pairReport.checks());peak=Math.max(peak,pairReport.peakBytes());
                acceptanceReports.addAll(pairReport.reports());
            }
            var expectedSelect=catalog.definitions().stream().filter(d->d.key().split()==Split.SELECT).map(d->d.key().id()).collect(java.util.stream.Collectors.toSet());
            boolean full=controls.caseIds()==null&&evaluatedSelect.equals(expectedSelect);
            var decisions=new ArrayList<CandidateDecision>();
            for(var parameter:shortlist)decisions.add(new CandidateDecision(parameter,select.get(parameter).mean(),acceptance.get(parameter).finish()));
            var choice=eligibleChoice(decisions,baselineSelect.mean(),guarded);
            String status=full?(choice.isPresent()?"FROZEN_PENDING_REGRESSION":"FAMILY_REJECTED_NO_PASSING_CANDIDATE"):"FIT_SCORED_PENDING_GEOMETRY";
            var trainFields=new ArrayList<Map<String,Object>>();var selectFields=new ArrayList<Map<String,Object>>();
            for(var parameter:parameters)trainFields.add(configuration(parameter,train.get(parameter),"train",writer));
            for(var parameter:shortlist)selectFields.add(configuration(parameter,select.get(parameter),"select",writer));
            var summary=new LinkedHashMap<>(header);summary.put("status",status);
            summary.put("sceneCount",count);summary.put("trainConfigurations",trainFields);
            summary.put("shortlist",shortlist.stream().map(BObservationIdentificationTool::parameterFields).toList());
            summary.put("selectConfigurations",selectFields);summary.put("baselineTrain",baselineTrain.fields());
            summary.put("baselineSelect",baselineSelect.fields());summary.put("featureAvailability",availability);
            summary.put("peakSceneBytes",peak);reads(summary,access);
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
    private static Map<String,Object> header(Phase phase,BIdentificationManifest.Seal seal,boolean subset) {
        var result=new LinkedHashMap<String,Object>();
        result.put("schema",1);result.put("phase",phase.name());result.put("manifestHash",seal.manifestHash());
        result.put("sourceHash",seal.sourceHash());result.put("catalogHash",seal.catalogHash());
        result.put("featureVersion",BIdentificationFacts.VERSION);result.put("scope",subset?"TEST_SUBSET":"FULL_PUBLIC_PROTOCOL");
        result.put("rendererCancellation","checkpoints-before-and-after-bounded-render-not-mid-pixel");
        result.put("acceptanceConventionsHash",acceptanceConventionsHash());
        return result;
    }
    private static Map<String,Object> bindingFields(BIdentificationManifest.Seal seal) {
        return Map.of("schema",1,"phase","CATALOG","status","CATALOG_REGISTERED","manifestHash",seal.manifestHash(),
                "sourceHash",seal.sourceHash(),"catalogHash",seal.catalogHash(),"rasterReads",0,"labelReads",0);
    }
    private static void verifyBinding(Path path,BIdentificationManifest.Seal seal) throws Exception {
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
        var allowed=catalog.definitions().stream().filter(d->phase!=Phase.FIT_SELECT || d.key().split()==Split.TRAIN || d.key().split()==Split.SELECT)
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
            Map<BIdentifiedObservationKernel.Parameters,BIdentificationPairCache.Index> pairs,List<String> acceptanceReports) { }

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
            Phase phase,BIdentificationManifest.Seal seal,EvaluationAccess access,Controls controls,CancellationProbe cancellation,
            Map<String,Object> availability,Writer writer,int sceneIndex) throws Exception {
        seal.verifyUnchanged();cancellation.checkpoint();admitRender(definition);
        var ledger=new AttemptMemoryLedger(controls.memoryBytes()-Writer.METADATA_BYTES);
        SceneReport report;
        try(var owner=ledger.rootOwner();var rendering=owner.reserve(RENDER_BYTES);
                var smallScratch=owner.reserve(1024L*1024)) {
            // The frozen renderer is opaque and bounded; it does not poll inside its pixel loop.
            cancellation.checkpoint();controls.beforeRender().run();
            var scene=BIdentificationScenes.render(definition.key(),access);
            cancellation.checkpoint();
            long outputBytes=0;
            for(var field:scene.evidence().fields().values()) {
                long pixels=Math.multiplyExact((long)field.width(),field.height());
                if(pixels>1_000_000)throw new RunLimitException("RENDER_DIMENSIONS");
                outputBytes=Math.addExact(outputBytes,Math.multiplyExact(pixels,10));
            }
            if(outputBytes>RENDER_BYTES)throw new RunLimitException("RENDER_MEMORY");
            var factory=new ProbabilisticProfileFactory();var policy=EvidenceModelParameters.defaults();
            var chainage=factory.profileChainage(scene.source(),1.5);
            var profiles=factory.create(scene.source(),chainage,25,true,scene.evidence(),scene.evidence().fields().get("scalar"),policy,cancellation,true,owner);
            if(profiles.size()>4096)throw new RunLimitException("PROFILE_COUNT");
            var facts=new BIdentificationFactsExtractor().extract(scene.evidence(),profiles,cancellation,owner);
            var admitted=admitLattices(profiles,policy,cancellation,owner);
            var lattices=admitted.lattices();long modeCells=0;
            for(var lattice:lattices) {
                cancellation.checkpoint();
                modeCells=Math.addExact(modeCells,Math.multiplyExact((long)lattice.cells().size(),lattice.components().size()));
                if(modeCells>MAX_MODE_CELLS)throw new RunLimitException("MODE_CELL_WORK");
            }
            countAvailability(facts,availability,cancellation);
            var fields=new LinkedHashMap<String,Object>();fields.put("id",definition.key().id());fields.put("split",definition.key().split().name());
            fields.put("recipeHash",BIdentificationCatalog.recipeHash(definition));
            fields.put("factsHash",BIdentificationReceipt.factsHash(facts,lattices,cancellation));
            fields.put("profileCount",profiles.size());fields.put("modeCellWorkPerConfiguration",modeCells);
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
                try(var baselineOwner=owner.child("baseline-score")) {
                    var baselineRows=ProbabilisticInference.allocated(baselineOwner,ProbabilisticInference.listBytes(profiles.size()),"baseline rows",
                            ()->new ArrayList<InferenceProfile>(profiles.size()));
                    for(int i=0;i<profiles.size();i++) {
                        cancellation.checkpoint();baselineRows.add(new ProbabilisticObservationModel().evaluate(profiles.get(i),lattices.get(i),policy,true,baselineOwner));
                    }
                    baseline=BIdentificationScore.scoreBaseline(scene,profiles,lattices,baselineRows,cancellation);
                }
                if(assess)try(var geometryOwner=owner.child("baseline-raw-geometry")) {
                    var outcome=BObservationGeometry.solve(facts,lattices,scene.evidence().decisionRegion(),TraceBudgets.defaults(),null,
                            BObservationGeometry.Enumeration.MAP_ONLY,cancellation,geometryOwner);
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
                    for(int i=0;i<profiles.size();i++) {
                        cancellation.checkpoint();
                        try(var profileOwner=candidateOwner.child("profile-mixture-scratch")) {
                            var mixture=kernel.evaluate(facts.get(i),lattices.get(i),cancellation,profileOwner);
                            if(assess) {
                                var inspected=inspectProfile(facts.get(i),lattices.get(i),mixture,parameter,cancellation,candidateOwner);
                                rows.add(inspected.rows());profileSummaries.add(inspected.summary());structural.add(inspected.checks());
                            } else rows.add(mixture.reduce(lattices.get(i),cancellation,candidateOwner));
                        }
                    }
                    var score=BIdentificationScore.score(scene,profiles,lattices,rows,cancellation);scores.put(parameter,score);
                    configurationScores.add(Map.of("parameters",parameterFields(parameter),"modelHash",kernel.identityHash(),"score",scoreFields(score)));
                    if(assess) {
                        var outcome=BObservationGeometry.solve(facts,lattices,scene.evidence().decisionRegion(),TraceBudgets.defaults(),kernel,
                                BObservationGeometry.Enumeration.MAP_ONLY,cancellation,candidateOwner);
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
            fields.put("peakSceneBytes",ledger.peakBytes()+Writer.METADATA_BYTES);cancellation.checkpoint();
            report=new SceneReport(Collections.unmodifiableMap(fields),baseline,Collections.unmodifiableMap(scores),ledger.peakBytes()+Writer.METADATA_BYTES,
                    Collections.unmodifiableMap(checks),Collections.unmodifiableMap(pairs),List.copyOf(acceptanceReports));
        }
        if(ledger.currentBytes()!=0)throw new IllegalStateException("Scene memory ownership did not close");
        return report;
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
    private static PairReport pairs(List<RequiredPair> required,Map<String,BIdentificationPairCache.Index> cache,
            BIdentifiedObservationKernel.Parameters parameter,Writer writer,Controls controls,CancellationProbe cancellation) throws Exception {
        var totals=new CheckTotals();var files=new ArrayList<String>();long peak=0,work=0;int pairCount=0;
        for(var pair:required) {
            cancellation.checkpoint();if(++work>8_000_000)throw new RunLimitException("PAIR_INVENTORY_WORK");
            var a=pair.first();var b=pair.second();
            Checks checks;var first=cache.get(a.key().id());var second=cache.get(b.key().id());
            var ledger=new AttemptMemoryLedger(controls.memoryBytes()-Writer.METADATA_BYTES);
            try(var owner=ledger.rootOwner()) {
                var binding=first==null||second==null?Optional.<PairBinding>empty():bindPair(a,first.source(),b,second.source());
                if(binding.isEmpty())checks=new Checks(List.of(new Check(Code.SOURCE_IDENTITY,Verdict.FAIL,1)));
                else {
                    var ah=BIdentificationPairCache.readIndex(writer.directory,first,cancellation,owner);
                    var bh=BIdentificationPairCache.readIndex(writer.directory,second,cancellation,owner);
                    var left=BIdentificationPairCache.read(writer.directory,ah,cancellation,owner);
                    var right=BIdentificationPairCache.read(writer.directory,bh,cancellation,owner);
                    checks=comparePair(binding.orElseThrow(),left,right,cancellation);
                }
                peak=Math.max(peak,ledger.peakBytes()+Writer.METADATA_BYTES);
            }
            if(ledger.currentBytes()!=0)throw new IllegalStateException("Pair-cache ownership did not close");
            totals.add(checks);
            String file="pair-check-%s-%03d.json".formatted(parameterId(parameter),pairCount++);
            var fields=new LinkedHashMap<String,Object>();fields.put("first",a.key().id());fields.put("second",b.key().id());
            fields.put("kind",pair.kind().name());fields.put("parameters",parameterFields(parameter));
            fields.put("coverage",first==null||second==null?"MISSING_REQUIRED_SCENE":"BOTH_REQUIRED_SCENES_EVALUATED");
            fields.put("checks",checkFields(checks));fields.put("peakPairBytes",peak);
            if(first!=null){fields.put("firstSource",sourceFields(first.source()));fields.put("firstFactsHash",first.factsHash());fields.put("modelHash",first.modelHash());}
            if(second!=null){fields.put("secondSource",sourceFields(second.source()));fields.put("secondFactsHash",second.factsHash());}
            writer.publish(file,fields);files.add(file);
        }
        if(pairCount==0)totals.add(new Checks(List.of(new Check(Code.SOURCE_IDENTITY,Verdict.UNAVAILABLE,0))));
        return new PairReport(totals.finish(),List.copyOf(files),peak);
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
    private static final class Aggregate {
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
    private static final class Writer implements AutoCloseable {
        static final long METADATA_BYTES=1024L*1024;
        final Path directory;long bytes;String chain="0".repeat(64);
        final AttemptMemoryLedger.Owner metadata;
        Writer(Path directory,long limit) {
            this.directory=directory;metadata=new AttemptMemoryLedger(limit).rootOwner();
            try{metadata.reserve(METADATA_BYTES);}catch(RuntimeException failure){metadata.close();throw failure;}
            // Current sealed catalog's indices, definitions, score/control totals and receipt-name lists.
            // Scene/pair ledgers subtract this live reservation from the same hard64MiB envelope.
        }
        String receiptSetHash(){return chain;}
        void publish(String file,Map<String,Object> fields) throws Exception {
            byte[] encoded=(BIdentificationReceipt.canonical(fields)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long length=encoded.length;
            if(length>MAX_DISK_BYTES-bytes)throw new RunLimitException("DISK_LIMIT");
            BIdentificationReceipt.publish(directory.resolve(file),fields);bytes+=length;
            chain=BIdentificationReceipt.hash((chain+"\n"+file+"\n"+BIdentificationReceipt.hash(encoded)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

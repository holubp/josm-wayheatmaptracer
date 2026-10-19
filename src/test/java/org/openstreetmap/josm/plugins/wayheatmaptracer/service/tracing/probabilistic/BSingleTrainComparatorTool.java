package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.file.*;
import java.util.function.LongSupplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.*;

/** Pending single B0 truth-conditioned comparator; no production, fitting or selection authority. */
public final class BSingleTrainComparatorTool {
    enum Phase {ACQUISITION,ADAPT,PROOF,SOLVE,ACCOUNT}
    static List<String> outputFiles(){var names=new ArrayList<>(List.of("binding.json","status.json","proof.json","solver.json","summary.json"));
        for(String path:List.of("archived","comparator"))for(int i=0;i<11;i++)names.add("detail-%s-%02d.json".formatted(path,i));
        for(int i=0;i<5;i++)names.add("progress-%02d.json".formatted(i));return List.copyOf(names);}
    static final class Progress {
        final long[][] counts=new long[5][4];final BObservationIdentificationTool.Writer writer;
        Progress(BObservationIdentificationTool.Writer writer){this.writer=writer;}
        void begin(Phase p) throws Exception{var c=counts[p.ordinal()];if(c[0]!=0)throw new IllegalStateException("No repeated phase");c[0]++;c[3]++;publish(writer,"progress-%02d.json".formatted(p.ordinal()),fields());}
        void succeeded(Phase p){var c=counts[p.ordinal()];if(c[3]!=1)throw new IllegalStateException("Active phase required");c[1]++;c[3]--;}
        void failed(){for(var c:counts)if(c[3]>0){c[2]+=c[3];c[3]=0;}}
        Map<String,Object> fields(){var phases=new TreeMap<String,Object>();for(var p:Phase.values()){var c=counts[p.ordinal()];phases.put(p.name(),Map.of("attempted",c[0],"succeeded",c[1],"failed",c[2],"active",c[3]));}return Map.of("schema",1,"phases",phases);}
    }
    static void publish(BObservationIdentificationTool.Writer writer,String file,Map<String,Object> fields) throws Exception {
        if(!outputFiles().contains(file))throw new SecurityException("Fixed comparator output leaf required");writer.publish(file,fields);
    }
    static void publishFailure(BObservationIdentificationTool.Writer writer,boolean proofAttempted,Map<String,Object> proof,
            java.util.function.Supplier<Map<String,Object>> terminal,Throwable failure) {
        if(!proofAttempted)try{publish(writer,"proof.json",proof);}catch(Exception|Error publication){failure.addSuppressed(publication);}
        try{publish(writer,"status.json",terminal.get());}catch(Exception|Error publication){failure.addSuppressed(publication);}
    }
    static Map<String,Object> solverFields(ProbabilisticInferenceResult result) {
        var f=new TreeMap<String,Object>();f.put("schema",1);f.put("status",result.status().name());f.put("route","ACTUAL_PUBLIC_FULL_FB_KBEST_MATERIALIZATION_DIAGNOSTICS");
        f.put("evaluatedPairVisits",result.evaluatedPairVisits());f.put("evaluatedTransitions",result.evaluatedTransitions());f.put("posteriorUsable",result.posteriorUsable());
        f.put("logPartition",BSingleTrainObjectiveAccounting.number(result.logPartition()));f.put("alternativeSearchTruncated",result.alternativeSearchTruncated());
        f.put("mapEnergy",result.mapPath().<Object>map(p->p.energy()).orElse("UNAVAILABLE"));
        f.put("mapStateIndices",result.mapPath().<Object>map(p->java.util.Arrays.stream(p.stateIndices()).boxed().toList()).orElse("UNAVAILABLE"));
        f.put("rawPathCount",result.rawPaths().size());f.put("distinctPathCount",result.distinctPaths().size());
        f.put("workAccounting",result.workAccounting().<Object>map(w->Map.of("executionPolicy",w.executionPolicy(),"forwardBackwardScores",w.forwardBackwardScores(),"kBestScores",w.kBestScores(),
                "logicalKBestExtensions",w.logicalKBestExtensions(),"refusedScoreAttempts",w.refusedScoreAttempts())).orElse("UNAVAILABLE"));
        f.put("completion",result.completion().<Object>map(c->Map.of("effectiveRawLimit",c.effectiveRawLimit(),"effectiveDistinctLimit",c.effectiveDistinctLimit(),
                "completePathsAtSaturation",c.completePathsAtSaturation(),"terminalCountSaturated",c.terminalCountSaturated(),"rawEnumerationCapped",c.rawEnumerationCapped(),"requestedDiversityReached",c.requestedDiversityReached())).orElse("UNAVAILABLE"));
        f.put("alternativeAuthority","CAP1_MAP_ONLY_NOT_ALL_ALTERNATIVES_COMPLETE");return f;
    }
    static ProbabilisticPath requireUsable(ProbabilisticInferenceResult result) {
        if(!Set.of(ProbabilisticInferenceResult.Status.COMPLETE,ProbabilisticInferenceResult.Status.AMBIGUOUS,ProbabilisticInferenceResult.Status.REVIEW_REQUIRED).contains(result.status())
                ||!Double.isFinite(result.logPartition())||!result.posteriorUsable()||result.workAccounting().isEmpty()||result.completion().isEmpty()||result.mapPath().isEmpty())
            throw new IllegalStateException("Comparator unavailable; no family-rejection authority");return result.mapPath().orElseThrow();
    }
    static void requireBits(double expected,double actual){if(!Double.isFinite(expected)||Double.doubleToRawLongBits(expected)!=Double.doubleToRawLongBits(actual))throw new SecurityException("Exact grouped objective identity changed");}
    private static Map<String,Object> terminal(String state,Progress progress,BSingleTrainComparatorRegion.Work proof,
            BSingleTrainObjectiveAccounting.Counts counts,int solves,EvaluationAccess access,BObservationIdentificationTool.Writer writer,long peak,long closed,long elapsed) {
        var f=new TreeMap<String,Object>(progress.fields());f.put("purpose",BSingleTrainComparatorManifest.PURPOSE);f.put("status",state);f.put("counts",counts.fields());
        f.put("completedPathAccounts",counts.completedAccounts);f.put("actualSolverEntries",solves);f.put("proofComplete",proof.complete);f.put("proofPairs",proof.pairs);
        f.put("rasterReads",access.rasterReads());f.put("labelReads",access.labelReads());f.put("selectReads",0);f.put("holdoutReads",0);f.put("selectionAuthority","NONE");
        f.put("peakAccountedBytes",peak<0?"UNAVAILABLE":peak);f.put("ownerBytesAfterClose",closed<0?"UNAVAILABLE":closed);f.put("memoryAccounting","CONSERVATIVE_OWNED_PAYLOAD_ENVELOPES_NOT_EXACT_HEAP");
        f.put("elapsedNanos",elapsed);f.put("diskChargeBeforeStatusUpperBound",writer.bytes);f.put("uncertainOrdinaryReservationBytes",writer.retainedOrdinaryReservationBytes);return f;
    }
    private static void directory(Path output) throws Exception {Path absolute=output.toAbsolutePath().normalize();for(Path p=absolute;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new SecurityException("Output symlinks forbidden");Files.createDirectory(absolute);}
    @SuppressWarnings("unchecked") static void run(Path output,String digest) throws Exception {
        LongSupplier clock=System::nanoTime;long started=clock.getAsLong();var cancellation=BSingleTrainGeometryDiagnosticTool.deadline(clock,started);cancellation.checkpoint();
        var seal=BSingleTrainComparatorManifest.register(digest);seal.requireExecution(System.getenv());cancellation.checkpoint();
        var definition=trainDiagnosticCatalog().definitions().stream().filter(d->d.key().id().equals(BSingleTrainDiagnosticManifest.CASE)).findFirst().orElseThrow();
        var controls=new BObservationIdentificationTool.Controls(List.of(BSingleTrainDiagnosticManifest.CASE),67_108_864L,300_000_000_000L,clock,cancellation,()->{});
        var access=EvaluationAccess.preselection(BIdentificationCatalog.Phase.FIT_SELECT);directory(output);
        var counts=new BSingleTrainObjectiveAccounting.Counts(162,160,158,962);var proof=new BSingleTrainComparatorRegion.Work(101_160,1_000_000_000L);
        int[] solves={0};long peak=-1,closed=-1;AttemptMemoryLedger ledger=null;boolean proofPublicationAttempted=false;
        try(var writer=new BObservationIdentificationTool.Writer(output,67_108_864L)) {
            var progress=new Progress(writer);
            try {
                publish(writer,"binding.json",Map.of("schema",1,"purpose",BSingleTrainComparatorManifest.PURPOSE,"freezeSha256",digest,"sourceProofOnly",BSingleTrainDiagnosticManifest.OLD_SOURCE,
                        "case",BSingleTrainDiagnosticManifest.CASE,"parameter","G1-L1-B0","launchContext",BSingleTrainComparatorManifest.launch(System.getenv()),"runtimeClassSha256",BSingleTrainComparatorManifest.runtimeClasses(),"selectionAuthority","NONE"));
                progress.begin(Phase.ACQUISITION);var loaded=BSingleTrainDiagnosticBridge.open(definition,BSingleTrainDiagnosticManifest.OLD_SOURCE,BSingleTrainDiagnosticManifest.RENDERER,
                        BSingleTrainDiagnosticManifest.CATALOG,seal::verify,d->BIdentificationScenes.render(d.key(),access),controls);ledger=loaded.ledger;
                try(loaded;var owner=loaded.owner.child("single-comparator")) {
                    owner.reserve(4L*1024*1024);
                    if(loaded.profiles.size()!=81||!loaded.admitted.coarsened()||loaded.admitted.maximumStates()!=36||loaded.admitted.pairBound()!=101_160)throw new SecurityException("Original admitted inventory changed");
                    var input=BSingleTrainDiagnosticBridge.inputFields(loaded.input);var rows=loaded.coordinates(cancellation);byte[] bytes=BSingleTrainGeometryDiagnosticTool.archived("G1-L1-B0");
                    BSingleTrainGeometryDiagnosticTool.decode("G1-L1-B0",bytes,input,rows);BSingleTrainCostDiagnosticTool.decodeWitness(BSingleTrainCostDiagnosticTool.witness(),rows);
                    var archive=BIdentificationReceipt.parse(bytes);var raw=(Map<String,Object>)archive.get("rawGeometry");int[] saved=((List<Number>)raw.get("mapStateIndices")).stream().mapToInt(Number::intValue).toArray();
                    requireBits(141.19791473890345,((Number)raw.get("mapEnergy")).doubleValue());progress.succeeded(Phase.ACQUISITION);seal.verify();cancellation.checkpoint();
                    progress.begin(Phase.ADAPT);var kernel=new BIdentifiedObservationKernel(new BIdentifiedObservationKernel.Parameters(1,1,0));if(!kernel.identityHash().equals(raw.get("modelHash")))throw new SecurityException("Original B0 identity changed");
                    var mutable=new ArrayList<InferenceProfile>();for(int i=0;i<81;i++){cancellation.checkpoint();if(counts.adapters>=81)throw new BObservationIdentificationTool.RunLimitException("ADAPTER_WORK");counts.adapters++;
                        mutable.add(BObservationAdapter.evaluate(loaded.facts.get(i),loaded.admitted.lattices().get(i),EvidenceModelParameters.defaults(),true,kernel,cancellation,owner));}
                    var profiles=List.copyOf(mutable);BSingleTrainComparatorRegion.requireFixedGraph(profiles,rows);
                    var savedAccount=BSingleTrainObjectiveAccounting.account(profiles,saved,EvidenceModelParameters.defaults(),cancellation,counts);requireBits(141.19791473890345,savedAccount.energy());
                    progress.succeeded(Phase.ADAPT);seal.verify();cancellation.checkpoint();progress.begin(Phase.PROOF);
                    double pitch=loaded.scene.evidence().resolution().nativePitchMeters().orElseThrow();if(pitch!=.5)throw new SecurityException("Original oracle tolerance changed");
                    var curves=loaded.scene.truth().regions().stream().flatMap(r->r.acceptableCurves().stream()).toList();if(curves.size()!=2)throw new SecurityException("Original union changed");
                    var oracle=new BSingleTrainGeometryReachability.Oracle(curves,pitch,owner,cancellation);var candidate=BSingleTrainComparatorRegion.candidate(rows,oracle.polygons,owner);
                    var gate=BSingleTrainComparatorRegion.certify(rows,loaded.scene.evidence().decisionRegion(),candidate,oracle.union,proof,owner,cancellation);
                    if(!proof.complete||proof.pairs!=101_160)throw new SecurityException("Complete fixed proof required");proofPublicationAttempted=true;publish(writer,"proof.json",proof.fields());progress.succeeded(Phase.PROOF);
                    seal.verify();cancellation.checkpoint();BSingleTrainComparatorRegion.requireFixedGraph(profiles,rows);progress.begin(Phase.SOLVE);
                    var result=gate.solveOnce(region->{seal.verify();cancellation.checkpoint();solves[0]++;return new ProbabilisticInference().solve(profiles,EvidenceModelParameters.defaults(),
                            new TraceBudgets(96,8_000_000,128_000_000,1,1),region,cancellation,owner,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);});
                    publish(writer,"solver.json",solverFields(result));var path=requireUsable(result);progress.succeeded(Phase.SOLVE);seal.verify();cancellation.checkpoint();progress.begin(Phase.ACCOUNT);
                    if(path.points().size()!=81)throw new SecurityException("Complete original path required");int[] states=path.stateIndices();
                    for(int i=0;i<81;i++)if(!path.points().get(i).equals(rows.get(i).get(states[i])))throw new SecurityException("Solver state/point identity changed");
                    var accounted=BSingleTrainObjectiveAccounting.account(profiles,states,EvidenceModelParameters.defaults(),cancellation,counts);requireBits(path.energy(),accounted.energy());
                    if(!BIdentificationAcceptance.exactAnchors(loaded.profiles,path.points(),cancellation).passed())throw new SecurityException("Original anchors failed");
                    for(int i=1;i<81;i++){cancellation.checkpoint();var a=path.points().get(i-1);var b=path.points().get(i);var original=loaded.scene.evidence().decisionRegion();
                        if(!original.contains(a)||!original.contains(b)||!original.containsSegment(a,b,cancellation,ignored->{},owner))throw new SecurityException("Original hard route failed");}
                    var geometry=BIdentificationAcceptance.geometry(BIdentificationAcceptance.GeometrySelector.PARALLEL_SEGMENTS_X40_80,loaded.scene,path.points(),pitch,cancellation,owner);
                    if(!geometry.passed())throw new SecurityException("Actual original whole geometry failed");
                    for(int p=0;p<2;p++)for(int chunk=0;chunk<11;chunk++) {
                        var selected=p==0?savedAccount:accounted;var details=new ArrayList<Map<String,Object>>();
                        for(int i=8*chunk;i<Math.min(81,8*(chunk+1));i++) {var row=new LinkedHashMap<String,Object>(selected.rows().get(i));int state=p==0?saved[i]:states[i];var responsibilities=new ArrayList<Double>();
                            for(int c=0;c<profiles.get(i).componentCount();c++)responsibilities.add(profiles.get(i).componentResponsibility(state,c));row.put("responsibilities",responsibilities);row.put("componentDensities","UNAVAILABLE_NO_SECOND_KERNEL_PASS");details.add(row);}
                        cancellation.checkpoint();publish(writer,"detail-%s-%02d.json".formatted(p==0?"archived":"comparator",chunk),Map.of("schema",1,"path",p==0?"ARCHIVED_B0":"TRUTH_CONDITIONED_COMPARATOR_B0","profiles",details));
                    }
                    var summary=new TreeMap<String,Object>();summary.put("schema",1);summary.put("purpose",BSingleTrainComparatorManifest.PURPOSE);summary.put("freezeSha256",digest);summary.put("input",input);summary.put("parameter","G1-L1-B0");summary.put("modelHash",kernel.identityHash());
                    summary.put("archivedReceiptSha256",BSingleTrainDiagnosticManifest.RECEIPTS.get("G1-L1-B0"));summary.put("archivedEnergy",savedAccount.energy());summary.put("comparatorEnergy",accounted.energy());summary.put("gapComparatorMinusArchived",accounted.energy()-savedAccount.energy());
                    summary.put("energyBitEquality",true);summary.put("actualOriginalWholeOraclePassed",true);summary.put("originalHardRoutePassed",true);summary.put("exactAnchorsPassed",true);
                    summary.put("checks",geometry.checks().stream().map(c->Map.of("code",c.code().name(),"verdict",c.verdict().name(),"count",c.count())).toList());
                    summary.put("selectionAuthority","NONE");summary.put("scope","FIXED_TRAIN_TRUTH_CONDITIONED_GRAPH_NOT_PRODUCTION_OR_MODEL_QUALIFICATION");
                    summary.put("comparisonAuthority","BEST_UNDER_ORIGINAL_SELECTOR_X40_80_ONLY");summary.put("exteriorImageSupport","UNAVAILABLE_NOT_ENFORCED_BY_SELECTOR");
                    summary.put("rounding","GROUPED_ENERGIES_EXACT_SEPARATE_PIECES_EXPLANATORY_CELL_WIDTH_NOT_MAP_TERM");publish(writer,"summary.json",summary);
                    progress.succeeded(Phase.ACCOUNT);seal.verify();cancellation.checkpoint();peak=loaded.ledger.peakBytes()+BObservationIdentificationTool.Writer.METADATA_BYTES;
                }
                closed=ledger.currentBytes();seal.verify();cancellation.checkpoint();publish(writer,"status.json",terminal("COMPLETE_TRUTH_CONDITIONED_COMPARISON_NOT_QUALIFIED",progress,proof,counts,solves[0],access,writer,peak,closed,clock.getAsLong()-started));
            } catch(Exception|Error failure) {
                progress.failed();if(ledger!=null){peak=ledger.peakBytes()+BObservationIdentificationTool.Writer.METADATA_BYTES;closed=ledger.currentBytes();}
                long finalPeak=peak,finalClosed=closed;
                publishFailure(writer,proofPublicationAttempted,proof.fields(),()->terminal("INCOMPLETE",progress,proof,counts,solves[0],access,writer,finalPeak,finalClosed,clock.getAsLong()-started),failure);throw failure;
            }
        }
    }
    public static void main(String[] args) throws Exception{if(args.length!=4||!"--output".equals(args[0])||!"--freeze-sha256".equals(args[2])||args[1].isBlank())throw new IllegalArgumentException("Use --output NEWDIR --freeze-sha256 EXTERNAL_LITERAL");run(Path.of(args[1]),args[3]);}
    private BSingleTrainComparatorTool() { }
}

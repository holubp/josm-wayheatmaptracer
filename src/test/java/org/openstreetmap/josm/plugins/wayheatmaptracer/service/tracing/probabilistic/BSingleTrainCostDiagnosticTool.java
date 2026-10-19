package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.file.*;
import java.util.function.LongSupplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.*;

/** Fixed-path accounting only: this entrypoint never invokes a solver, search or fit. */
public final class BSingleTrainCostDiagnosticTool {
    enum Phase {ACQUISITION,DECODE,B0_EVALUATION,B0_ACCOUNT,B1_EVALUATION,B1_ACCOUNT}
    static List<String> outputFiles() {
        var files=new ArrayList<>(List.of("binding.json","status.json","summary-B0.json","summary-B1.json"));
        for(int b=0;b<2;b++)for(int c=0;c<11;c++)files.add("detail-B%d-%02d.json".formatted(b,c));
        for(int p=0;p<6;p++)files.add("progress-%02d.json".formatted(p));return List.copyOf(files);
    }
    static final class Progress {
        final long[][] counts=new long[6][4];final BObservationIdentificationTool.Writer writer;
        Progress(BObservationIdentificationTool.Writer writer){this.writer=writer;}
        void begin(Phase p) throws Exception {var c=counts[p.ordinal()];if(c[0]!=0)throw new IllegalStateException("No repeated phases");c[0]++;c[3]++;
            publish(writer,"progress-%02d.json".formatted(p.ordinal()),snapshot());}
        void succeeded(Phase p){var c=counts[p.ordinal()];if(c[3]!=1)throw new IllegalStateException("No active phase");c[1]++;c[3]--;}
        void failed(){for(var c:counts)if(c[3]>0){c[2]+=c[3];c[3]=0;}}
        Map<String,Object> snapshot(){var map=new TreeMap<String,Object>();for(var p:Phase.values()){var c=counts[p.ordinal()];
            map.put(p.name(),Map.of("attempted",c[0],"succeeded",c[1],"failed",c[2],"active",c[3]));}return Map.of("schema",1,"phases",map);}
    }
    static void publish(BObservationIdentificationTool.Writer writer,String file,Map<String,Object> fields) throws Exception {
        if(!outputFiles().contains(file))throw new SecurityException("Fixed output leaf required");writer.publish(file,fields);
    }
    static byte[] witness() throws Exception {
        try(var stream=BSingleTrainCostDiagnosticTool.class.getResourceAsStream("/b-single-train-cost/witness.json")) {
            if(stream==null)throw new SecurityException("Stored witness unavailable");byte[] bytes=stream.readNBytes(65_537);
            if(bytes.length>65_536||!BSingleTrainCostManifest.WITNESS.equals(BIdentificationReceipt.hash(bytes)))throw new SecurityException("Stored witness changed");return bytes;
        }
    }
    @SuppressWarnings("unchecked") static int[] decodeWitness(byte[] bytes,List<List<MetricPoint>> rows) throws Exception {
        if(!BSingleTrainCostManifest.WITNESS.equals(BIdentificationReceipt.hash(bytes)))throw new SecurityException("Stored witness changed");
        var fields=BIdentificationReceipt.parse(bytes);
        if(!fields.keySet().equals(Set.of("schema","states","points","actualWholeOraclePassed"))
                ||!Boolean.TRUE.equals(fields.get("actualWholeOraclePassed"))||!Integer.valueOf(1).equals(((Number)fields.get("schema")).intValue()))
            throw new SecurityException("Stored geometry-only witness required");
        var indices=(List<Number>)fields.get("states");var points=(List<Map<String,Number>>)fields.get("points");
        if(rows.size()!=81||indices.size()!=81||points.size()!=81)throw new SecurityException("Complete original witness required");
        var states=new int[81];
        for(int i=0;i<81;i++) {
            int s=indices.get(i).intValue();if(s!=indices.get(i).doubleValue()||s<0||s>=rows.get(i).size())throw new SecurityException("Witness outside original lattice");
            var p=rows.get(i).get(s);var expected=points.get(i);
            if(!expected.keySet().equals(Set.of("xMeters","yMeters"))||Double.doubleToRawLongBits(p.xMeters())!=Double.doubleToRawLongBits(expected.get("xMeters").doubleValue())
                    ||Double.doubleToRawLongBits(p.yMeters())!=Double.doubleToRawLongBits(expected.get("yMeters").doubleValue()))throw new SecurityException("Witness state/point identity changed");states[i]=s;
        }
        return states;
    }
    record PairAccounts(BSingleTrainObjectiveAccounting.Account archived,BSingleTrainObjectiveAccounting.Account witness) { }
    static PairAccounts accountPair(List<InferenceProfile> profiles,int[] archived,int[] witness,double archivedEnergy,
            CancellationProbe cancellation,BSingleTrainObjectiveAccounting.Counts counts) throws Exception {
        var first=BSingleTrainObjectiveAccounting.account(profiles,archived,EvidenceModelParameters.defaults(),cancellation,counts);
        if(!Double.isFinite(archivedEnergy)||Double.doubleToRawLongBits(first.energy())!=Double.doubleToRawLongBits(archivedEnergy))
            throw new SecurityException("Archived grouped objective mismatch; witness not accounted");
        return new PairAccounts(first,BSingleTrainObjectiveAccounting.account(profiles,witness,EvidenceModelParameters.defaults(),cancellation,counts));
    }
    static CancellationProbe deadline(LongSupplier clock,long started){return BSingleTrainGeometryDiagnosticTool.deadline(clock,started);}
    private static void directory(Path output) throws Exception {
        Path path=output.toAbsolutePath().normalize();for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new SecurityException("Output symlinks forbidden");Files.createDirectory(path);
    }
    private static Map<String,Object> status(String state,Progress progress,BSingleTrainObjectiveAccounting.Counts counts,
            BObservationIdentificationTool.Writer writer,EvaluationAccess access,long peak,long closed,long elapsed) {
        var fields=new TreeMap<String,Object>(progress.snapshot());fields.put("purpose",BSingleTrainCostManifest.PURPOSE);fields.put("status",state);
        fields.put("counts",counts.fields());fields.put("completedPathAccounts",counts.completedAccounts);fields.put("newMapSolves",0);fields.put("newSearches",0);
        fields.put("rasterReads",access.rasterReads());fields.put("labelReads",access.labelReads());fields.put("selectReads",0);fields.put("holdoutReads",0);
        fields.put("accessScope","FIXED_ORIGINAL_TRAIN_ONLY_PRESELECTION_GUARD");fields.put("selectionAuthority","NONE");
        fields.put("peakAccountedBytes",peak<0?"UNAVAILABLE":peak);fields.put("ownerBytesAfterClose",closed<0?"UNAVAILABLE":closed);
        fields.put("memoryAccounting","CONSERVATIVE_OWNED_PAYLOAD_ENVELOPES_NOT_EXACT_HEAP");fields.put("elapsedNanos",elapsed);
        fields.put("diskChargeBeforeStatusUpperBound",writer.bytes);fields.put("uncertainOrdinaryReservationBytes",writer.retainedOrdinaryReservationBytes);return fields;
    }
    @SuppressWarnings("unchecked") static void run(Path output,String digest) throws Exception {
        LongSupplier clock=System::nanoTime;long started=clock.getAsLong();var cancellation=deadline(clock,started);cancellation.checkpoint();
        var seal=BSingleTrainCostManifest.register(digest);seal.requireExecution(System.getenv());cancellation.checkpoint();
        var definition=trainDiagnosticCatalog().definitions().stream().filter(d->d.key().id().equals(BSingleTrainDiagnosticManifest.CASE)).findFirst().orElseThrow();
        var controls=new BObservationIdentificationTool.Controls(List.of(BSingleTrainDiagnosticManifest.CASE),67_108_864L,300_000_000_000L,clock,cancellation,()->{});
        var access=EvaluationAccess.preselection(BIdentificationCatalog.Phase.FIT_SELECT);directory(output);
        var counts=new BSingleTrainObjectiveAccounting.Counts();long peak=-1,closed=-1;AttemptMemoryLedger ledger=null;
        try(var writer=new BObservationIdentificationTool.Writer(output,67_108_864L)) {
            var progress=new Progress(writer);
            try {
                publish(writer,"binding.json",Map.of("schema",1,"purpose",BSingleTrainCostManifest.PURPOSE,"freezeSha256",digest,
                        "sourceProofOnly",BSingleTrainDiagnosticManifest.OLD_SOURCE,"case",BSingleTrainDiagnosticManifest.CASE,
                        "launchContext",BSingleTrainCostManifest.launch(System.getenv()),"runtimeClassSha256",BSingleTrainCostManifest.runtimeClasses(),
                        "witnessMemberSha256",BSingleTrainCostManifest.WITNESS,"selectionAuthority","NONE"));
                progress.begin(Phase.ACQUISITION);
                var loaded=BSingleTrainDiagnosticBridge.open(definition,BSingleTrainDiagnosticManifest.OLD_SOURCE,BSingleTrainDiagnosticManifest.RENDERER,
                        BSingleTrainDiagnosticManifest.CATALOG,seal::verify,d->BIdentificationScenes.render(d.key(),access),controls);ledger=loaded.ledger;
                try(loaded) {
                    if(loaded.profiles.size()!=81||!loaded.admitted.coarsened()||loaded.admitted.maximumStates()!=36||loaded.admitted.pairBound()!=101_160
                            ||loaded.admitted.lattices().stream().mapToInt(l->l.cells().size()).sum()!=2846)throw new SecurityException("Original admission changed");
                    // Whole-run metadata/maps/rows envelope, additional to separately charged adapter and scalar snapshots.
                    loaded.owner.reserve(4L*1024*1024);var input=BSingleTrainDiagnosticBridge.inputFields(loaded.input);progress.succeeded(Phase.ACQUISITION);
                    seal.verify();cancellation.checkpoint();progress.begin(Phase.DECODE);var coordinates=loaded.coordinates(cancellation);
                    byte[] witnessBytes=witness();int[] witnessStates=decodeWitness(witnessBytes,coordinates);
                    var receipts=new ArrayList<Map<String,Object>>();var archivedStates=new ArrayList<int[]>();
                    for(String id:List.of("G1-L1-B0","G1-L1-B1")) {
                        byte[] bytes=BSingleTrainGeometryDiagnosticTool.archived(id);BSingleTrainGeometryDiagnosticTool.decode(id,bytes,input,coordinates);
                        var receipt=BIdentificationReceipt.parse(bytes);receipts.add(receipt);var raw=(Map<String,Object>)receipt.get("rawGeometry");
                        var states=(List<Number>)raw.get("mapStateIndices");archivedStates.add(states.stream().mapToInt(Number::intValue).toArray());
                    }
                    progress.succeeded(Phase.DECODE);
                    for(int b=0;b<2;b++)try(var parameterOwner=loaded.owner.child("fixed-parameter-"+b)) {
                        seal.verify();cancellation.checkpoint();var evaluation=b==0?Phase.B0_EVALUATION:Phase.B1_EVALUATION;var accounting=b==0?Phase.B0_ACCOUNT:Phase.B1_ACCOUNT;
                        progress.begin(evaluation);var kernel=new BIdentifiedObservationKernel(new BIdentifiedObservationKernel.Parameters(1,1,b));
                        var raw=(Map<String,Object>)receipts.get(b).get("rawGeometry");
                        if(!kernel.identityHash().equals(raw.get("modelHash")))throw new SecurityException("Archived parameter identity changed");
                        var profiles=new ArrayList<InferenceProfile>();var snapshots=new ArrayList<List<List<Map<String,Object>>>>();
                        long modeCells=0;
                        for(int i=0;i<81;i++) {
                            cancellation.checkpoint();if(counts.adapters>=162)throw new BObservationIdentificationTool.RunLimitException("ADAPTER_WORK");counts.adapters++;
                            var wrapper=new BSingleTrainObjectiveAccounting.SnapshotKernel(kernel,new int[]{archivedStates.get(b)[i],witnessStates[i]},counts,parameterOwner);
                            var profile=BObservationAdapter.evaluate(loaded.facts.get(i),loaded.admitted.lattices().get(i),EvidenceModelParameters.defaults(),true,wrapper,cancellation,parameterOwner);
                            for(int s:new int[]{archivedStates.get(b)[i],witnessStates[i]})if(!profile.point(s).equals(coordinates.get(i).get(s)))throw new SecurityException("Actual adapter coordinate identity changed");
                            profiles.add(profile);snapshots.add(wrapper.selected);modeCells=Math.addExact(modeCells,(long)profile.cells().size()*profile.componentCount());
                        }
                        progress.succeeded(evaluation);seal.verify();cancellation.checkpoint();progress.begin(accounting);
                        var accounts=accountPair(profiles,archivedStates.get(b),witnessStates,((Number)raw.get("mapEnergy")).doubleValue(),cancellation,counts);
                        for(int chunk=0;chunk<11;chunk++) {
                            var rows=new ArrayList<Map<String,Object>>();
                            for(int i=8*chunk;i<Math.min(81,8*(chunk+1));i++) {
                                var paths=new ArrayList<Map<String,Object>>();
                                for(int path=0;path<2;path++) {
                                    var row=new LinkedHashMap<String,Object>((path==0?accounts.archived():accounts.witness()).rows().get(i));
                                    int state=path==0?archivedStates.get(b)[i]:witnessStates[i];var components=new ArrayList<Map<String,Object>>();
                                    for(int c=0;c<profiles.get(i).componentCount();c++){var component=new LinkedHashMap<>(snapshots.get(i).get(path).get(c));component.put("responsibility",profiles.get(i).componentResponsibility(state,c));components.add(component);}
                                    row.put("components",components);row.put("path",path==0?"ARCHIVED_MAP":"STORED_GEOMETRY_ONLY_WITNESS");paths.add(row);
                                }
                                rows.add(Map.of("profile",i,"paths",paths));
                            }
                            cancellation.checkpoint();publish(writer,"detail-B%d-%02d.json".formatted(b,chunk),Map.of("schema",1,"parameter","G1-L1-B"+b,"profiles",rows));
                        }
                        var summary=new TreeMap<String,Object>();summary.put("schema",1);summary.put("purpose",BSingleTrainCostManifest.PURPOSE);summary.put("input",input);
                        summary.put("parameter","G1-L1-B"+b);summary.put("modelHash",kernel.identityHash());summary.put("archivedReceiptSha256",BSingleTrainDiagnosticManifest.RECEIPTS.get("G1-L1-B"+b));
                        summary.put("archivedEnergy",accounts.archived().energy());summary.put("archivedEnergyBits",Long.toHexString(Double.doubleToRawLongBits(accounts.archived().energy())));
                        summary.put("archivedEnergyBitExact",true);summary.put("witnessEnergy",BSingleTrainObjectiveAccounting.number(accounts.witness().energy()));
                        summary.put("witnessAvailability",Double.isFinite(accounts.witness().energy())?"FINITE_FIXED_CONTROL_NOT_OPTIMAL_OR_PLAUSIBILITY_PROOF":"UNAVAILABLE_NONFINITE_NO_REPLACEMENT");
                        summary.put("witnessMemberSha256",BSingleTrainCostManifest.WITNESS);summary.put("modeCellCount",modeCells);summary.put("freezeSha256",digest);
                        summary.put("grouping","E=(P0+P1)+A0; i>=2 increment=(Pi+A_previous)+Ti; E=E+increment");
                        summary.put("cellWidthMeaning","POSTERIOR_INTEGRATION_METADATA_ONLY_NOT_MAP_COST");summary.put("separatePieces","SEPARATELY_ROUNDED_EXPLANATIONS_NOT_EXACT_ADDITIVE_RECONSTRUCTION");
                        summary.put("selectionAuthority","NONE");publish(writer,"summary-B%d.json".formatted(b),summary);progress.succeeded(accounting);seal.verify();cancellation.checkpoint();
                    }
                    peak=loaded.ledger.peakBytes()+BObservationIdentificationTool.Writer.METADATA_BYTES;
                }
                closed=ledger.currentBytes();seal.verify();cancellation.checkpoint();
                publish(writer,"status.json",status("COMPLETE_NONQUALIFYING_ACCOUNTING",progress,counts,writer,access,peak,closed,clock.getAsLong()-started));
            } catch(Exception|Error failure) {
                progress.failed();if(ledger!=null){peak=ledger.peakBytes()+BObservationIdentificationTool.Writer.METADATA_BYTES;closed=ledger.currentBytes();}
                try{publish(writer,"status.json",status("INCOMPLETE",progress,counts,writer,access,peak,closed,clock.getAsLong()-started));}
                catch(Exception|Error publication){failure.addSuppressed(publication);}throw failure;
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4||!"--output".equals(args[0])||!"--freeze-sha256".equals(args[2])||args[1].isBlank())throw new IllegalArgumentException("Use --output NEWDIR --freeze-sha256 EXTERNAL_LITERAL");
        run(Path.of(args[1]),args[3]);
    }
    private BSingleTrainCostDiagnosticTool() { }
}

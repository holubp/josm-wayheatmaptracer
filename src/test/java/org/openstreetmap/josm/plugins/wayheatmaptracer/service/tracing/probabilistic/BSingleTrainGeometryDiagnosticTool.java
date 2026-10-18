package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.file.*;
import java.util.function.LongSupplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationAcceptance.*;

/** Standalone fixed diagnostic; no model fitting, selection or fresh MAP authority. */
public final class BSingleTrainGeometryDiagnosticTool {
    @SuppressWarnings("unchecked")
    static List<MetricPoint> decode(String id,byte[] bytes,Map<String,Object> input,List<List<MetricPoint>> rows) throws Exception {
        if(!Objects.equals(BSingleTrainDiagnosticManifest.RECEIPTS.get(id),BIdentificationReceipt.hash(bytes)))
            throw new SecurityException("Archived public receipt changed");
        var receipt=BIdentificationReceipt.parse(bytes);
        if(!BSingleTrainDiagnosticManifest.CASE.equals(receipt.get("case"))
                ||!BIdentificationReceipt.canonical(input).equals(BIdentificationReceipt.canonical((Map<String,Object>)receipt.get("input"))))
            throw new SecurityException("Archived original input binding changed");
        var raw=(Map<String,Object>)receipt.get("rawGeometry");var indices=(List<Number>)raw.get("mapStateIndices");
        if(rows.size()!=81||indices.size()!=rows.size())throw new IllegalArgumentException("Exact original rows required");
        var points=new ArrayList<MetricPoint>();
        for(int i=0;i<indices.size();i++) {
            int state=indices.get(i).intValue();
            if(state<0||state>=rows.get(i).size())throw new IllegalArgumentException("Archived state outside admitted row");
            points.add(rows.get(i).get(state));
        }
        return Collections.unmodifiableList(points);
    }
    static byte[] archived(String id) throws Exception {
        if(!BSingleTrainDiagnosticManifest.RECEIPTS.containsKey(id))throw new SecurityException("Fixed archived paths only");
        try(var stream=BSingleTrainGeometryDiagnosticTool.class.getResourceAsStream("/b-single-train-geometry/"+id+".json")) {
            if(stream==null)throw new IllegalArgumentException("Archived receipt missing");
            byte[] bytes=stream.readNBytes(65_537);
            if(bytes.length>65_536||!BSingleTrainDiagnosticManifest.RECEIPTS.get(id).equals(BIdentificationReceipt.hash(bytes)))
                throw new SecurityException("Archived public receipt changed");
            return bytes;
        }
    }
    static CancellationProbe deadline(LongSupplier clock,long started) {
        return ()->{long elapsed=clock.getAsLong()-started;
            if(elapsed<0||elapsed>=300_000_000_000L)throw new BObservationIdentificationTool.RunLimitException("TIME");return false;};
    }
    private static List<Map<String,Object>> points(List<MetricPoint> points) {
        return points.stream().map(p->Map.<String,Object>of("xMeters",p.xMeters(),"yMeters",p.yMeters())).toList();
    }
    enum Phase { ACQUISITION, DECODE, GEOMETRY, SEARCH, EXPORT }
    static final class Progress {
        final long[][] counts=new long[Phase.values().length][4];private final BObservationIdentificationTool.Writer writer;
        Progress(BObservationIdentificationTool.Writer writer){this.writer=writer;}
        void begin(Phase phase) throws Exception {var c=counts[phase.ordinal()];c[0]++;c[3]++;
            writer.publish("progress-%02d.json".formatted(phase.ordinal()),snapshot());}
        void succeeded(Phase phase){var c=counts[phase.ordinal()];c[1]++;c[3]--;}
        void failed(){for(var c:counts)if(c[3]>0){c[2]+=c[3];c[3]=0;}}
        Map<String,Object> snapshot(){var map=new LinkedHashMap<String,Object>();
            for(var phase:Phase.values()){var c=counts[phase.ordinal()];map.put(phase.name(),Map.of("attempted",c[0],"succeeded",c[1],"failed",c[2],"active",c[3]));}
            return Map.of("schema",1,"phases",map);}
    }
    static List<String> outputFiles() {
        var files=new ArrayList<>(List.of("binding.json","paths.json","segments.json","search.json","witness.json","status.json","oracle-00.json","oracle-01.json"));
        for(int i=0;i<11;i++)files.add("states-%02d.json".formatted(i));
        for(int i=0;i<5;i++)files.add("progress-%02d.json".formatted(i));return List.copyOf(files);
    }
    private static void directory(Path output) throws Exception {
        var path=output.toAbsolutePath().normalize();
        for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new SecurityException("Output symlinks forbidden");
        Files.createDirectory(path);
    }
    @SuppressWarnings("unchecked")
    static void run(Path output,String digest) throws Exception {
        LongSupplier clock=System::nanoTime;long started=clock.getAsLong();var cancellation=deadline(clock,started);
        cancellation.checkpoint();var seal=BSingleTrainDiagnosticManifest.register(digest);seal.requireExecution(System.getenv());cancellation.checkpoint();
        var definition=BIdentificationCatalog.trainDiagnosticCatalog().definitions().stream()
                .filter(d->d.key().id().equals(BSingleTrainDiagnosticManifest.CASE)).findFirst().orElseThrow();
        var controls=new BObservationIdentificationTool.Controls(List.of(BSingleTrainDiagnosticManifest.CASE),67_108_864L,
                300_000_000_000L,clock,cancellation,()->{});
        var access=EvaluationAccess.preselection(PhaseForAccess());directory(output);
        try(var writer=new BObservationIdentificationTool.Writer(output,67_108_864L)) {
            var progress=new Progress(writer);long peak=-1,closedBytes=-1;String status="INCOMPLETE_UNKNOWN";
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger acquiredLedger=null;
            writer.publish("binding.json",Map.of("schema",1,"purpose",BSingleTrainDiagnosticManifest.PURPOSE,"freezeSha256",digest,
                    "sourceProofOnly",BSingleTrainDiagnosticManifest.OLD_SOURCE,"case",BSingleTrainDiagnosticManifest.CASE,
                    "launchContext",BSingleTrainDiagnosticManifest.launch(System.getenv()),"selectionAuthority","NONE"));
            try {
                progress.begin(Phase.ACQUISITION);
                var loaded=BSingleTrainDiagnosticBridge.open(definition,BSingleTrainDiagnosticManifest.OLD_SOURCE,
                        BSingleTrainDiagnosticManifest.RENDERER,BSingleTrainDiagnosticManifest.CATALOG,seal::verify,
                        d->BIdentificationScenes.render(d.key(),access),controls);
                acquiredLedger=loaded.ledger;
                try(loaded) {
                    if(loaded.profiles.size()!=81||!loaded.admitted.coarsened()||loaded.admitted.maximumStates()!=36||loaded.admitted.pairBound()!=101_160)
                        throw new SecurityException("Archived admission shape changed");
                    loaded.owner.reserve(4L*1024*1024); // Conservative export/control payload, never called exact live heap.
                    var input=BSingleTrainDiagnosticBridge.inputFields(loaded.input);progress.succeeded(Phase.ACQUISITION);
                    progress.begin(Phase.DECODE);var rows=loaded.coordinates(cancellation);
                    var paths=new LinkedHashMap<String,List<MetricPoint>>();var pathReports=new LinkedHashMap<String,Object>();
                    for(String id:List.of("G1-L1-B0","G1-L1-B1")) {
                        byte[] bytes=archived(id);var path=decode(id,bytes,input,rows);paths.put(id,path);
                        var receipt=BIdentificationReceipt.parse(bytes);pathReports.put(id,Map.of("archivedReceiptSha256",BIdentificationReceipt.hash(bytes),
                                "archivedRawGeometry",receipt.get("rawGeometry"),"points",points(path),"origin","ARCHIVED_MAP_DECODED_NOT_RESOLVED"));
                    }
                    progress.succeeded(Phase.DECODE);seal.verify();cancellation.checkpoint();progress.begin(Phase.GEOMETRY);
                    var curves=loaded.scene.truth().regions().stream().flatMap(r->r.acceptableCurves().stream()).toList();
                    if(curves.size()!=2)throw new SecurityException("Fixed original oracle changed");
                    double pitch=loaded.scene.evidence().resolution().nativePitchMeters().orElseThrow();
                    if(pitch!=.5)throw new SecurityException("Original native tolerance changed");
                    var oracle=new BSingleTrainGeometryReachability.Oracle(curves,pitch,loaded.owner,cancellation);
                    var segments=new ArrayList<Map<String,Object>>();
                    for(var entry:paths.entrySet()) {
                        var checks=geometry(GeometrySelector.PARALLEL_SEGMENTS_X40_80,loaded.scene,entry.getValue(),pitch,cancellation,loaded.owner);
                        if(!exactAnchors(loaded.profiles,entry.getValue(),cancellation).passed()
                                ||checks.checks().stream().noneMatch(c->c.code()==Code.PARALLEL_SEGMENT_UNION&&c.verdict()==Verdict.FAIL&&c.count()==28))
                            throw new SecurityException("Archived geometry verdict changed");
                        boolean cachedPass=true;
                        for(int i=1;i<entry.getValue().size();i++) {
                            cancellation.checkpoint();var a=entry.getValue().get(i-1);var b=entry.getValue().get(i);
                            var clipped=BSingleTrainDiagnosticBridge.clip(a,b);boolean allowed=oracle.allowed(a,b);cachedPass&=allowed;
                            if(clipped.isPresent())segments.add(Map.of("path",entry.getKey(),"endProfile",i,"clipped",points(clipped.get()),
                                    "wholeSegmentContained",allowed,"startContained",oracle.union.contains(clipped.get().get(0)),
                                    "endContained",oracle.union.contains(clipped.get().get(1)),"maximumDistanceMeters","UNAVAILABLE"));
                        }
                        if(cachedPass)throw new SecurityException("Cached segment oracle disagrees with actual failed route");
                    }
                    progress.succeeded(Phase.GEOMETRY);seal.verify();cancellation.checkpoint();progress.begin(Phase.SEARCH);
                    var result=BSingleTrainGeometryReachability.search(rows,loaded.scene.evidence().decisionRegion(),oracle,
                            loaded.owner,cancellation,101_160);var witness=new ArrayList<MetricPoint>();
                    if(result.status()==BSingleTrainGeometryReachability.Status.WITNESS) {
                        for(int i=0;i<rows.size();i++)witness.add(rows.get(i).get(result.states().get(i)));
                        if(!exactAnchors(loaded.profiles,witness,cancellation).passed()
                                ||!geometry(GeometrySelector.PARALLEL_SEGMENTS_X40_80,loaded.scene,witness,pitch,cancellation,loaded.owner).passed())
                            throw new IllegalStateException("Reachability witness failed actual whole oracle");
                        for(int i=1;i<witness.size();i++)if(!loaded.scene.evidence().decisionRegion().containsSegment(witness.get(i-1),witness.get(i),cancellation,ignored->{},loaded.owner))
                            throw new IllegalStateException("Witness failed original hard admission");
                    }
                    progress.succeeded(Phase.SEARCH);seal.verify();cancellation.checkpoint();progress.begin(Phase.EXPORT);
                    for(int chunk=0;chunk<11;chunk++) {
                        var fields=new ArrayList<Map<String,Object>>();
                        for(int i=8*chunk;i<Math.min(rows.size(),8*(chunk+1));i++) {
                            var states=new ArrayList<Map<String,Object>>();
                            for(int s=0;s<rows.get(i).size();s++){var cell=loaded.admitted.lattices().get(i).cells().get(s);var p=rows.get(i).get(s);
                                states.add(Map.of("state",s,"offsetMeters",cell.offsetMeters(),"widthMeters",cell.quadratureWidthMeters(),
                                        "exactAnchor",cell.exactAnchor(),"xMeters",p.xMeters(),"yMeters",p.yMeters()));}
                            fields.add(Map.of("profile",i,"states",states));
                        }
                        writer.publish("states-%02d.json".formatted(chunk),Map.of("schema",1,"profiles",fields));
                    }
                    for(int i=0;i<2;i++)writer.publish("oracle-%02d.json".formatted(i),Map.of("schema",1,"curve",points(curves.get(i)),
                            "tubePolygon",points(oracle.polygons.get(i)),"nativeToleranceMeters",pitch,"selectorXMinMeters",40,"selectorXMaxMeters",80));
                    writer.publish("paths.json",Map.of("schema",1,"input",input,"paths",pathReports,"newMapSolves",0,"newLikelihoodScores",0));
                    writer.publish("segments.json",Map.of("schema",1,"segments",segments,"maximumDistanceAuthority","UNAVAILABLE_NOT_MEASURED"));
                    writer.publish("search.json",Map.of("schema",1,"status",result.status().name(),"reason",result.reason(),"examinedReachablePairs",result.examinedPairs(),
                            "exhaustive",result.status()==BSingleTrainGeometryReachability.Status.EXHAUSTIVE_ABSENCE,"scope","LATTICE_GEOMETRY_ONLY_NOT_MODEL_FEASIBILITY"));
                    writer.publish("witness.json",Map.of("schema",1,"states",result.states(),"points",points(witness),"actualWholeOraclePassed",!witness.isEmpty()));
                    progress.succeeded(Phase.EXPORT);seal.verify();cancellation.checkpoint();status=result.status().name();
                    peak=loaded.ledger.peakBytes()+BObservationIdentificationTool.Writer.METADATA_BYTES;
                }
                closedBytes=loaded.ledger.currentBytes();
                writer.publish("status.json",terminal(status,progress,writer,access,peak,closedBytes,clock.getAsLong()-started));
            }catch(Exception|Error failure) {
                progress.failed();
                if(acquiredLedger!=null){peak=acquiredLedger.peakBytes()+BObservationIdentificationTool.Writer.METADATA_BYTES;closedBytes=acquiredLedger.currentBytes();}
                try{writer.publish("status.json",terminal("INCOMPLETE_UNKNOWN",progress,writer,access,peak,closedBytes,clock.getAsLong()-started));}
                catch(Exception|Error publication){failure.addSuppressed(publication);}throw failure;
            }
        }
    }
    private static BIdentificationCatalog.Phase PhaseForAccess(){return BIdentificationCatalog.Phase.FIT_SELECT;}
    private static Map<String,Object> terminal(String status,Progress progress,BObservationIdentificationTool.Writer writer,
            EvaluationAccess access,long peak,long closed,long elapsed) {
        var map=new LinkedHashMap<String,Object>(progress.snapshot());map.put("purpose",BSingleTrainDiagnosticManifest.PURPOSE);map.put("status",status);
        map.put("rasterReads",access.rasterReads());map.put("labelReads",access.labelReads());map.put("selectReads",0);map.put("holdoutReads",0);
        map.put("peakAccountedBytes",peak<0?"UNAVAILABLE":peak);map.put("memoryAccounting","CONSERVATIVE_OWNED_PAYLOAD_ENVELOPES_NOT_EXACT_HEAP");map.put("ownerBytesAfterClose",closed<0?"UNAVAILABLE":closed);
        map.put("elapsedNanos",elapsed);map.put("diskChargeBeforeStatusUpperBound",writer.bytes);map.put("uncertainOrdinaryReservationBytes",writer.retainedOrdinaryReservationBytes);
        map.put("selectionAuthority","NONE");return map;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Use --output NEWDIR --freeze-sha256 EXTERNAL_LITERAL");
        var options=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2)if(!Set.of("--output","--freeze-sha256").contains(args[i])||args[i+1].isBlank()||options.put(args[i],args[i+1])!=null)
            throw new IllegalArgumentException("Fixed diagnostic arguments required");
        if(!options.keySet().equals(Set.of("--output","--freeze-sha256")))throw new IllegalArgumentException("Complete fixed diagnostic arguments required");
        run(Path.of(options.get("--output")),options.get("--freeze-sha256"));
    }
    private BSingleTrainGeometryDiagnosticTool() { }
}

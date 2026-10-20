package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/**
 * Current-process point-only postprocessing after the historical producer exits.
 * The reviewed launcher supplies the factual wait exit code and authenticates the
 * separate manifest. Supplied digests are provenance, never review authority.
 * No profile construction, engine call, coordinate normalization or route choice.
 */
public final class BNativeHistoricalPointComparison {
 static final String HISTORICAL="117baaf09f4b81a38ad4679eedc8fd1b12ed3d2a";
 static final String SCOPE="HISTORICAL_NATIVE_POINT_COMPARISON_NOT_QUALIFICATION";
 static final long REPORT=2L*1024*1024,RAW=8L*1024*1024;
 static final int LEAF=65536;
 @FunctionalInterface interface LeafOpen {OutputStream open(Path path)throws IOException;}
 record Storage(java.util.function.BiFunction<Path,String,Path> staging,LeafOpen open) { }
 static final Storage FILES=new Storage((parent,prefix)->parent.resolve(prefix+UUID.randomUUID()),
  path->Files.newOutputStream(path,StandardOpenOption.CREATE_NEW));
 /** Independent input pins and a subprocess exit code observed after waiting. */
 public record Controls(String caseId,String archiveSha256,String protocolSha256,String manifestSha256,
   int producerExitCode,CancellationProbe probe) {
  public Controls {
   BNativeNeutralFixtureExporter.requireCase(caseId);
   for(String digest:List.of(archiveSha256,protocolSha256,manifestSha256))BNativeNeutralFixtureExporter.digest(digest);
   if(producerExitCode<0||producerExitCode>255)throw new IllegalArgumentException("Producer exit code");
   Objects.requireNonNull(probe);
  }
 }
 static void require(boolean condition,String reason){if(!condition)throw new SecurityException(reason);}
 static boolean yes(Map<?,?> m,String key){return Boolean.TRUE.equals(m.get(key));}
 static boolean numberIs(Map<?,?> m,String key,long expected){return m.get(key) instanceof Number n&&n.doubleValue()==expected;}
 /** Eligibility for an oracle, never a geometry verdict or model qualification. */
 static boolean completed(Map<String,Object> r,Map<String,Object> p,Map<String,Object> o,int exit,boolean cutoff){
  return exit==0&&!cutoff&&yes(r,"pipelineCompleted")&&yes(r,"engineReturned")
   &&numberIs(r,"nativeInvocations",1)&&numberIs(r,"nativeNormalReturns",1)&&numberIs(r,"engineInvocations",1)
   &&("COMPLETE".equals(r.get("nativeStatus"))||"AMBIGUOUS".equals(r.get("nativeStatus")))
   &&yes(r,"qualityComparisonAvailable")&&yes(r,"routeDataAvailable")
   &&"ALL_RETURNED_ALTERNATIVES_RETAINED".equals(r.get("exportStatus"))
   &&"NATIVE_ATTEMPT_RECORDED".equals(r.get("status"))&&"NATIVE_ATTEMPT_RECORDED".equals(r.get("phase"))
   &&numberIs(r,"sourceBytesAfterClose",0)&&numberIs(r,"publicationBytesLive",REPORT)
   &&"TERMINAL_RECORDED".equals(p.get("phase"))&&"TERMINAL_RECORDED".equals(p.get("status"))
   &&Boolean.FALSE.equals(p.get("nativeCallMayBeInProgress"))&&numberIs(p,"nativeInvocationsAtCheckpoint",1)
   &&numberIs(p,"nativeReturnsAtCheckpoint",1)&&numberIs(o,"remainingNonReportBytes",0)&&numberIs(o,"publicationBytesLive",REPORT);
 }
 @SuppressWarnings("unchecked") static Map<String,Object> object(Object v){
  require(v instanceof Map<?,?>,"Receipt object required");return (Map<String,Object>)v;
 }
 static int count(Object v,int maximum){
  require(v instanceof Number,"Count required");double d=((Number)v).doubleValue();
  require(Double.isFinite(d)&&d>=0&&d<=maximum&&d==(int)d,"Bounded count required");return (int)d;
 }
 static List<MetricPoint> points(Object value,int maximum){
  require(value instanceof List<?>,"Point array");var rows=(List<?>)value;require(rows.size()>=2&&rows.size()<=maximum,"Point count");
  var result=new ArrayList<MetricPoint>(rows.size());for(Object row:rows)result.add(BNativeNeutralFixtureExporter.metric(row));return List.copyOf(result);
 }
 static String hex(double v){return String.format(Locale.ROOT,"%016x",Double.doubleToRawLongBits(v));}
 static List<List<String>> bits(List<MetricPoint> points){return points.stream().map(p->List.of(hex(p.xMeters()),hex(p.yMeters()))).toList();}
 static List<List<Double>> coordinates(List<MetricPoint> points){return points.stream().map(p->List.of(p.xMeters(),p.yMeters())).toList();}
 static void same(Object a,Object b,String reason){require(BIdentificationReceipt.canonical(a).equals(BIdentificationReceipt.canonical(b)),reason);}
 static Map<String,Object> readPinned(Path path,String expected)throws Exception {
  byte[] bytes=BNativeNeutralFixtureExporter.bounded(path,LEAF);
  require(BIdentificationReceipt.hash(bytes).equals(expected),"Raw receipt changed after inventory");
  StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
  var value=BIdentificationReceipt.parse(bytes);nodes(value,0,new int[1]);return value;
 }
 // At most twelve FINAL <=64KiB leaves, <=4096 nodes/leaf, decoded text,
 // parser temporaries and route lists fit the 8MiB RAW envelope. The twelve
 // corresponding partial names are hash-only, read one at a time, never parsed.
 static void nodes(Object value,int depth,int[] count){
  require(depth<=16&&++count[0]<=4096,"Raw receipt object bound");
  if(value instanceof Map<?,?> m)for(var e:m.entrySet()){nodes(e.getKey(),depth+1,count);nodes(e.getValue(),depth+1,count);}
  else if(value instanceof List<?> list)for(Object child:list)nodes(child,depth+1,count);
  else if(value instanceof String s)require(s.length()<=4096,"Raw receipt string bound");
  else if(value instanceof Number n)require(Double.isFinite(n.doubleValue()),"Nonfinite receipt");
 }
 static Map<String,String> inventory(Path raw,CancellationProbe probe)throws Exception {
  require(Files.isDirectory(raw,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(raw),"Raw receipt directory");
  var result=new TreeMap<String,String>();
  try(var files=Files.newDirectoryStream(raw)){
   for(Path file:files){probe.checkpoint();require(result.size()<24,"Raw receipt inventory bound");String name=file.getFileName().toString();
    String base=name.endsWith(".partial")?name.substring(0,name.length()-8):name;
    require(Set.of("binding.json","result.json","ownership.json","phase.json").contains(base)||base.matches("route-[0-7]\\.json"),"Unknown raw receipt leaf");
    result.put(name,BIdentificationReceipt.hash(BNativeNeutralFixtureExporter.bounded(file,LEAF)));
   }
  }return result;
 }
 static List<String> routeNames(Map<String,Object> r,Map<String,String> hashes){
  require(r.get("routeFiles") instanceof List<?>,"Route inventory missing");var names=(List<?>)r.get("routeFiles");
  require(names.size()<=8,"Native alternative bound");var result=new ArrayList<String>();
  for(int i=0;i<names.size();i++){String name="route-"+i+".json";require(name.equals(names.get(i))&&hashes.containsKey(name),"Native route order/inventory");result.add(name);}
  for(String name:hashes.keySet())if(name.startsWith("route-"))require(result.contains(name),"Unlisted native route");
  if(r.get("returnedAlternativeCount")!=null)require(count(r.get("returnedAlternativeCount"),8)>=names.size(),"Returned alternative count");
  else require(names.isEmpty(),"Unreported returned alternative count");return result;
 }
 static BNativeNeutralFixtureExporter.Input prepare(Path archive,Path flat,Controls c,AttemptMemoryLedger.Owner owner)throws Exception {
  try(var cold=owner.reserve(BNativeNeutralFixtureExporter.COLD_BYTES)){
   c.probe().checkpoint();byte[] bytes=BNativeNeutralFixtureExporter.bounded(archive,BNativeNeutralFixtureExporter.MAX_ZIP);
   require(BIdentificationReceipt.hash(bytes).equals(c.archiveSha256()),"External neutral archive pin");
   var entries=BNativeNeutralFixtureExporter.archiveEntries(bytes);
   BNativeNeutralFixtureExporter.write(flat,"index.json",entries.get("index.json"));
   for(String leaf:List.of("scalar.f64","validity.bin","descriptor.json")){c.probe().checkpoint();String name=c.caseId()+"/"+leaf;BNativeNeutralFixtureExporter.write(flat,name,entries.get(name));}
   var input=BNativeNeutralFixtureExporter.decode(c.caseId(),entries.get(c.caseId()+"/scalar.f64"),entries.get(c.caseId()+"/validity.bin"),entries.get(c.caseId()+"/descriptor.json"));
   var original=object(object(BNativeProductionDefaultComparison.contract().get("nativeInputs")).get(c.caseId()));
   require(BIdentificationReceipt.hash(entries.get(c.caseId()+"/scalar.f64")).equals(original.get("rasterSha256"))
    &&BIdentificationReceipt.hash(entries.get(c.caseId()+"/validity.bin")).equals(original.get("validitySha256")),"Frozen native raw source");
   return input;
  }
 }
 static List<MetricPoint> bind(Map<String,Object> b,Map<String,Object> r,Path flat,Controls c,
    BNativeNeutralFixtureExporter.Input input,EvidenceSnapshot evidence)throws Exception {
  require(HISTORICAL.equals(b.get("historicalSource"))&&c.caseId().equals(b.get("case")),"Historical source/case");
  require("HISTORICAL_NATIVE_ENGINE_BOUNDARY_ONLY".equals(b.get("scope")),"Historical engine purpose");
  require(c.archiveSha256().equals(b.get("archiveSha256"))&&c.protocolSha256().equals(b.get("protocolSha256"))
   &&BNativeProductionDefaultComparison.CONTRACT_SHA.equals(b.get("nativeContractSha256")),"Historical external pins");
  require(BIdentificationReceipt.hash(BNativeNeutralFixtureExporter.bounded(flat.resolve("index.json"),LEAF)).equals(b.get("indexSha256")),"Historical index binding");
  same(BNativeNeutralFixtureExporter.captured(evidence),b.get("captured"),"Historical factual snapshot/scalar/masks");
  var frozen=object(object(BNativeProductionDefaultComparison.contract().get("nativeInputs")).get(c.caseId()));
  require(evidence.canonicalHash().equals(frozen.get("snapshotHash")),"Frozen native snapshot");
  var captured=BNativeNeutralFixtureExporter.captured(evidence);
  same(captured.get("width"),frozen.get("outputWidth"),"Frozen output width");same(captured.get("height"),frozen.get("outputHeight"),"Frozen output height");
  var original=input.source();var nativeSource=new ArrayList<MetricPoint>();var geo=new ArrayList<List<Double>>();var geoBits=new ArrayList<List<String>>();
  for(var point:original){c.probe().checkpoint();var g=input.grid().coordinateFrame().toGeographic(point);
   geo.add(List.of(g.latitudeDegrees(),g.longitudeDegrees()));geoBits.add(List.of(hex(g.latitudeDegrees()),hex(g.longitudeDegrees())));
   nativeSource.add(input.grid().coordinateFrame().toMetric(g));}
  same(coordinates(original),b.get("originalMetricSource"),"Original source coordinates");same(bits(original),b.get("originalMetricSourceRawBits"),"Original source bits");
  same(geo,b.get("geographicSource"),"Geographic source coordinates");same(geoBits,b.get("geographicSourceRawBits"),"Geographic source bits");
  same(coordinates(nativeSource),b.get("nativeRoundtripSource"),"Native source coordinates");same(bits(nativeSource),b.get("nativeRoundtripSourceRawBits"),"Native source bits");
  var request=object(b.get("nativeRequest"));require(HISTORICAL.equals(request.get("historicalSource")),"Native request source");
  same(List.of(96,8000000,128000000,32,8),request.get("nativePolicy"),"Original native policy");
  require("DIRECT_LONGITUDINAL_V2".equals(request.get("reliabilityPolicy"))&&"profile-window-centered-v2".equals(request.get("samplerId"))
   &&"4a6c463c009d9dc454741a8146de9f69c1e065b98f5026514a921c9b377a9d77".equals(request.get("parameterHash"))
   &&"DIAGNOSTIC_DEFAULTS_TOSTRING_METADATA_NOT_LIVE_HASH".equals(request.get("parameterHashMeaning"))
   &&evidence.canonicalHash().equals(request.get("evidenceSnapshotHash")),"Historical native request identity");
  if(!r.isEmpty())for(String key:List.of("scope","case","historicalSource","protocolSha256","archiveSha256","indexSha256","nativeContractSha256",
    "captured","originalMetricSource","originalMetricSourceRawBits","geographicSource","geographicSourceRawBits","nativeRoundtripSource","nativeRoundtripSourceRawBits","nativeRequest"))same(b.get(key),r.get(key),"Result/binding "+key);
  return List.copyOf(nativeSource);
 }
 /**
  * Applies unchanged point oracles to every retained alternative after exit.
  * Independent pins/exit status require Controls; they cannot be inferred from
  * three paths. Malformed evidence refuses; native failures remain unavailable.
  */
 public static Map<String,Object> evaluate(Path archive,Path raw,Path output,Controls c)throws Exception {
  return evaluateOwned(archive,raw,output,c,FILES,new AttemptMemoryLedger(64L*1024*1024));
 }
 // Package-private injection is restricted to deterministic I/O/ownership tests;
 // the public CLI/API always uses CREATE_NEW storage and a fresh 64MiB root.
 static Map<String,Object> evaluateOwned(Path archive,Path raw,Path output,Controls c,Storage storage,AttemptMemoryLedger ledger)throws Exception {
  Objects.requireNonNull(c);Objects.requireNonNull(storage);require(ledger.limitBytes()==64L*1024*1024,"Checker ledger limit");
  Path flat=null;var summary=new LinkedHashMap<String,Object>();var leaves=new LinkedHashMap<String,byte[]>();
  try(var root=ledger.rootOwner();var report=root.reserve(REPORT);var rawOwner=root.reserve(RAW)){
   c.probe().checkpoint();require(!Files.exists(output,LinkOption.NOFOLLOW_LINKS),"Fresh comparison directory required");
   Path parent=output.toAbsolutePath().getParent();require(Files.isDirectory(parent),"Existing output parent required");
   Path candidate=storage.staging().apply(parent,"."+output.getFileName()+"-neutral-");
   Files.createDirectory(candidate); // Claim must succeed BEFORE cleanup owns it.
   flat=candidate;
   var input=prepare(archive,flat,c,root);var hashes=inventory(raw,c.probe());
   var result=hashes.containsKey("result.json")?readPinned(raw.resolve("result.json"),hashes.get("result.json")):new LinkedHashMap<String,Object>();
   var phase=hashes.containsKey("phase.json")?readPinned(raw.resolve("phase.json"),hashes.get("phase.json")):new LinkedHashMap<String,Object>();
   var ownership=hashes.containsKey("ownership.json")?readPinned(raw.resolve("ownership.json"),hashes.get("ownership.json")):new LinkedHashMap<String,Object>();
   Path cutoff=raw.resolveSibling(c.caseId()+"-external-cutoff.json");boolean cut=Files.exists(cutoff,LinkOption.NOFOLLOW_LINKS);
   String cutoffHash=cut?BIdentificationReceipt.hash(BNativeNeutralFixtureExporter.bounded(cutoff,LEAF)):null;
   summary.put("scope",SCOPE);summary.put("case",c.caseId());summary.put("manifestSha256",c.manifestSha256());summary.put("archiveSha256",c.archiveSha256());
   summary.put("protocolSha256",c.protocolSha256());summary.put("producerExitCode",c.producerExitCode());summary.put("exitCodeAuthority","EXTERNAL_LAUNCHER_WAIT_REQUIRED");
   summary.put("rawLeafSha256",hashes);summary.put("externalCutoffSha256",cutoffHash);summary.put("rawNativeStatus",result.get("nativeStatus"));
   summary.put("rawNativeInvocations",result.get("nativeInvocations"));summary.put("rawNativeNormalReturns",result.get("nativeNormalReturns"));
   var nativeFacts=new LinkedHashMap<String,Object>();for(String key:List.of("status","phase","cause","nativeExplanation","engineReturned","engineInvocations",
    "nativeInvocations","nativeNormalReturns","pipelineCompleted","exportStatus","returnedAlternativeCount","rawAlternatives","distinctAlternatives","alternativesTruncated",
    "evaluatedStates","pairVisits","transitions","workAccounting","ledgerPeakBytes","ledgerLimitBytes","sourceBytesAfterClose","observedControlPlaneOutcome","controlPlaneMemoryAbort"))
     if(result.containsKey(key))nativeFacts.put(key,result.get(key));summary.put("rawNativeFacts",nativeFacts);
   summary.put("rawPhase",phase);summary.put("rawOwnership",ownership);summary.put("rawFinalRootBytes","NOT_INFERRED_FROM_EXIT_OR_PUBLICATION");
   summary.put("modelQualified",false);summary.put("finalPreviewOrApplyQualification",false);summary.put("selectedAlternative",null);
   summary.put("available",false);summary.put("status","UNAVAILABLE_NO_COMPLETE_NATIVE_EXECUTION");summary.put("routeFiles",List.of());
   boolean partial=hashes.keySet().stream().anyMatch(n->n.endsWith(".partial"));boolean finalResult=hashes.containsKey("result.json");
   summary.put("partialPublicationPresent",partial);summary.put("sourceBindingAvailable",false);
   summary.put("rawFinalRouteLeaves",hashes.keySet().stream().filter(n->n.matches("route-[0-7]\\.json")).toList());
   summary.put("rawRouteOrderCompleteness","NOT_INFERRED_FROM_FILE_PREFIX");
   if(!finalResult)summary.put("status","UNAVAILABLE_MISSING_FINAL_RESULT");
   if(partial)summary.put("status","UNAVAILABLE_PARTIAL_RAW_PUBLICATION");
   if(hashes.containsKey("binding.json")){
    try(var capture=BNativeNeutralFixtureExporter.recapture(flat,c.caseId(),c.probe(),root)){
     var binding=readPinned(raw.resolve("binding.json"),hashes.get("binding.json"));var nativeSource=bind(binding,result,flat,c,input,capture.snapshot());
     summary.put("sourceBindingAvailable",true);
     // Without a final result, or with ANY staged partial, final route prefixes
     // stay hash-only evidence. Never parse staged JSON or invent a route count.
     var names=finalResult&&!partial?routeNames(result,hashes):List.<String>of();
     boolean eligible=finalResult&&!partial&&completed(result,phase,ownership,c.producerExitCode(),cut);var outputNames=new ArrayList<String>();
     for(int i=0;i<names.size();i++){
      c.probe().checkpoint();String name=names.get(i);var route=readPinned(raw.resolve(name),hashes.get(name));require(count(route.get("index"),7)==i,"Native alternative index");
      var path=points(route.get("points"),81);same(bits(path),route.get("pointRawBits"),"Returned raw coordinate bits");
      var row=new LinkedHashMap<String,Object>();row.put("scope",SCOPE);row.put("case",c.caseId());row.put("index",i);row.put("rawRouteSha256",hashes.get(name));
      row.put("neutralSnapshotHash",capture.snapshot().canonicalHash());row.put("manifestSha256",c.manifestSha256());row.put("rawRouteId",route.get("id"));
      row.put("points",route.get("points"));row.put("pointRawBits",route.get("pointRawBits"));row.put("rawNativeStatus",result.get("nativeStatus"));
      row.put("modelQualified",false);row.put("available",false);row.put("passed",null);row.put("availability","UNAVAILABLE_NO_COMPLETE_NATIVE_EXECUTION");
      if(eligible){require("COMPLETE_NATIVE_ALTERNATIVE_ENGINE_BOUNDARY_ONLY".equals(route.get("quality")),"Partial route contradicts completed result");
       var verdict=BNativePointAcceptance.evaluate(c.caseId(),capture.snapshot(),input.source(),nativeSource,path,c.probe(),root);row.putAll(verdict);
       if(!verdict.containsKey("availability"))row.put("availability",Boolean.TRUE.equals(verdict.get("available"))?
        "AVAILABLE_POINT_CHECK_RECORDED_NOT_QUALIFIED":"UNAVAILABLE_POINT_ORACLE");}
      leaves.put(name,json(row));outputNames.add(name);
     }
     if(eligible){require(names.size()>0&&count(result.get("returnedAlternativeCount"),8)==names.size(),"All returned alternatives required");
      summary.put("available",true);summary.put("status","POINT_CHECKS_RECORDED_NOT_QUALIFIED");}
     summary.put("routeFiles",outputNames);summary.put("neutralSnapshotHash",capture.snapshot().canonicalHash());
    }
   }else if(finalResult&&!partial)summary.put("status","UNAVAILABLE_NO_FINAL_SOURCE_BINDING");
   summary.put("checkerLedgerLimitBytes",ledger.limitBytes());summary.put("checkerLedgerPeakBytes",ledger.peakBytes());
   leaves.put("result.json",json(summary));leaves.put("ownership.json",json(Map.of("checkerNonReportBytesAtPublication",ledger.currentBytes()-REPORT,"publicationBytesLive",REPORT,
    "rawProducerOwnership",ownership,"checkerFinalRootBytes","UNAVAILABLE_UNTIL_RETURN")));
   publish(output,leaves,c.probe(),storage.open());
  }finally{
   if(flat!=null&&Files.exists(flat)){for(String leaf:List.of("scalar.f64","validity.bin","descriptor.json"))Files.deleteIfExists(flat.resolve(c.caseId()).resolve(leaf));
    Files.deleteIfExists(flat.resolve(c.caseId()));Files.deleteIfExists(flat.resolve("index.json"));Files.deleteIfExists(flat);}
   if(ledger.currentBytes()!=0)throw new IllegalStateException("Point checker owner leak");
  }
  return Collections.unmodifiableMap(summary);
 }
 static byte[] json(Map<String,Object> map){byte[] b=BNativeNeutralFixtureExporter.json(map);require(b.length<=LEAF,"Point comparison export overflow");return b;}
 static void publish(Path output,Map<String,byte[]> leaves,CancellationProbe probe,LeafOpen open)throws Exception {
  require(leaves.size()<=10&&leaves.values().stream().mapToLong(b->b.length).sum()<=10L*LEAF,"Point comparison export bound");
  Files.createDirectory(output);var staged=new ArrayList<Path>();var committed=new ArrayList<Path>();
  try{
   for(var e:leaves.entrySet()){Path temporary=output.resolve(e.getKey()+".partial");try(var stream=open.open(temporary)){
    staged.add(temporary); // Successful CREATE_NEW open establishes ownership.
    stream.write(e.getValue());}}
   probe.checkpoint(); // ALL staging complete; admission immediately before FIRST move.
   for(var e:leaves.entrySet()){Path target=output.resolve(e.getKey());Files.move(output.resolve(e.getKey()+".partial"),target,StandardCopyOption.ATOMIC_MOVE);committed.add(target);}
  }catch(Exception|Error failure){for(Path p:staged)removeOwned(p,failure);for(Path p:committed)removeOwned(p,failure);removeOwned(output,failure);throw failure;}
 }
 static void removeOwned(Path path,Throwable primary){try{Files.deleteIfExists(path);}catch(Exception cleanup){primary.addSuppressed(cleanup);}}
 /** Launch from current test runtime, only after the old producer has exited. */
 public static void main(String[] args)throws Exception {
  require(args.length==16,"Eight explicit point checker arguments required");var a=new HashMap<String,String>();
  for(int i=0;i<args.length;i+=2)require(a.put(args[i],args[i+1])==null,"Duplicate CLI argument");
  require(a.keySet().equals(Set.of("--case","--neutral-archive","--neutral-sha256","--protocol-sha256","--historical-receipts","--output","--manifest-sha256","--producer-exit-code")),"Point checker CLI arguments");
  evaluate(Path.of(a.get("--neutral-archive")),Path.of(a.get("--historical-receipts")),Path.of(a.get("--output")),
   new Controls(a.get("--case"),a.get("--neutral-sha256"),a.get("--protocol-sha256"),a.get("--manifest-sha256"),Integer.parseInt(a.get("--producer-exit-code")),CancellationProbe.NONE));
 }
 private BNativeHistoricalPointComparison() { }
}

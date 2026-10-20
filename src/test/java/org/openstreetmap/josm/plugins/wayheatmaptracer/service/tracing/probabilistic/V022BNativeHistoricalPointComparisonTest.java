package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Controlled producer-shaped metadata only: these checks are not historical numerical runs. */
class V022BNativeHistoricalPointComparisonTest {
 @TempDir static Path temporary;
 static Path exported,archive;static String archiveSha;
 static final String PROTOCOL="1".repeat(64),MANIFEST="2".repeat(64);
 static final Set<String> forbidden=Set.of("ProbabilisticProfileFactory","BIdentificationFactsExtractor","ProbabilisticObservationModel","BGapField","BGapPairTable","ProbabilisticTraceEngine","ProbabilisticInference").stream().map(n->"org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic."+n).collect(java.util.stream.Collectors.toUnmodifiableSet());
 static final CancellationProbe noEngine=()->{boolean entered=StackWalker.getInstance().walk(s->s.anyMatch(f->forbidden.contains(f.getClassName())));assertFalse(entered,"Point checker entered numerical engine");return false;};
 @BeforeAll static void neutralOnly()throws Exception {
  // Exporter's zero-numerics contract has its own retained suite; the expensive
  // StackWalker sentinel applies to THIS checker's selected capture/oracle path.
  exported=temporary.resolve("neutral");BNativeNeutralFixtureExporter.export(exported,CancellationProbe.NONE);archive=Path.of(exported+".zip");archiveSha=BIdentificationReceipt.hash(Files.readAllBytes(archive));
 }
 static BNativeHistoricalPointComparison.Controls controls(String id,int exit,CancellationProbe probe){return new BNativeHistoricalPointComparison.Controls(id,archiveSha,PROTOCOL,MANIFEST,exit,probe);}
 static BNativeNeutralFixtureExporter.Input input(String id)throws Exception {return BNativeNeutralFixtureExporter.decode(id,Files.readAllBytes(exported.resolve(id+"/scalar.f64")),Files.readAllBytes(exported.resolve(id+"/validity.bin")),Files.readAllBytes(exported.resolve(id+"/descriptor.json")));}
 static List<MetricPoint> nativeSource(BNativeNeutralFixtureExporter.Input input){return input.source().stream().map(p->input.grid().coordinateFrame().toMetric(input.grid().coordinateFrame().toGeographic(p))).toList();}
 static void write(Path dir,String name,Map<String,Object> m)throws Exception {Files.write(dir.resolve(name),BNativeNeutralFixtureExporter.json(m));}
 static Map<String,Object> binding(String id)throws Exception {
  var in=input(id);var nativePoints=nativeSource(in);var geo=in.source().stream().map(in.grid().coordinateFrame()::toGeographic).toList();
  var b=new LinkedHashMap<String,Object>();b.put("testEvidence","CONTROLLED_METADATA_NOT_NATIVE_NUMERICAL_EVIDENCE");b.put("scope","HISTORICAL_NATIVE_ENGINE_BOUNDARY_ONLY");b.put("case",id);
  b.put("historicalSource","117baaf09f4b81a38ad4679eedc8fd1b12ed3d2a");b.put("archiveSha256",archiveSha);b.put("protocolSha256",PROTOCOL);
  b.put("indexSha256",BIdentificationReceipt.hash(Files.readAllBytes(exported.resolve("index.json"))));b.put("nativeContractSha256",BNativeProductionDefaultComparison.CONTRACT_SHA);
  var cap=(Map<?,?>)((Map<?,?>)read(exported.resolve("index.json")).get("captures")).get(id);b.put("captured",cap);
  b.put("originalMetricSource",BNativeHistoricalPointComparison.coordinates(in.source()));b.put("originalMetricSourceRawBits",BNativeHistoricalPointComparison.bits(in.source()));
  b.put("nativeRoundtripSource",BNativeHistoricalPointComparison.coordinates(nativePoints));b.put("nativeRoundtripSourceRawBits",BNativeHistoricalPointComparison.bits(nativePoints));
  b.put("geographicSource",geo.stream().map(g->List.of(g.latitudeDegrees(),g.longitudeDegrees())).toList());
  b.put("geographicSourceRawBits",geo.stream().map(g->List.of(BNativeHistoricalPointComparison.hex(g.latitudeDegrees()),BNativeHistoricalPointComparison.hex(g.longitudeDegrees()))).toList());
  b.put("nativeRequest",Map.of("historicalSource",b.get("historicalSource"),"nativePolicy",List.of(96,8000000,128000000,32,8),"reliabilityPolicy","DIRECT_LONGITUDINAL_V2","samplerId","profile-window-centered-v2",
   "parameterHash","4a6c463c009d9dc454741a8146de9f69c1e065b98f5026514a921c9b377a9d77","parameterHashMeaning","DIAGNOSTIC_DEFAULTS_TOSTRING_METADATA_NOT_LIVE_HASH","evidenceSnapshotHash",cap.get("snapshotHash")));
  return b;
 }
 static Path raw(String id,List<List<MetricPoint>> paths)throws Exception {
  Path dir=Files.createTempDirectory(temporary,"raw-");var b=binding(id);write(dir,"binding.json",b);var r=new LinkedHashMap<>(b);r.putAll(result());
  var names=new ArrayList<String>();for(int i=0;i<paths.size();i++){
   String name="route-"+i+".json";names.add(name);write(dir,name,Map.of("index",i,"id","unit-"+i,"points",BNativeHistoricalPointComparison.coordinates(paths.get(i)),"pointRawBits",BNativeHistoricalPointComparison.bits(paths.get(i)),"quality","COMPLETE_NATIVE_ALTERNATIVE_ENGINE_BOUNDARY_ONLY"));
  }r.put("routeFiles",names);r.put("returnedAlternativeCount",paths.size());write(dir,"result.json",r);write(dir,"phase.json",phase());write(dir,"ownership.json",ownership());return dir;
 }
 static Path output(){return temporary.resolve("out-"+UUID.randomUUID());}
 static Map<String,Object> read(Path file)throws Exception{return BIdentificationReceipt.read(file);}
 static Map<String,Object> result(){return new LinkedHashMap<>(Map.ofEntries(
  Map.entry("pipelineCompleted",true),Map.entry("nativeStatus","COMPLETE"),Map.entry("engineReturned",true),
  Map.entry("nativeInvocations",1),Map.entry("nativeNormalReturns",1),Map.entry("engineInvocations",1),
  Map.entry("qualityComparisonAvailable",true),Map.entry("routeDataAvailable",true),
  Map.entry("exportStatus","ALL_RETURNED_ALTERNATIVES_RETAINED"),Map.entry("routeFiles",List.of("route-0.json")),
  Map.entry("returnedAlternativeCount",1),Map.entry("sourceBytesAfterClose",0),Map.entry("publicationBytesLive",2097152),
  Map.entry("status","NATIVE_ATTEMPT_RECORDED"),Map.entry("phase","NATIVE_ATTEMPT_RECORDED")));
 }
 static Map<String,Object> phase(){return Map.of("phase","TERMINAL_RECORDED","status","TERMINAL_RECORDED","nativeCallMayBeInProgress",false,"nativeInvocationsAtCheckpoint",1,"nativeReturnsAtCheckpoint",1);}
 static Map<String,Object> ownership(){return Map.of("remainingNonReportBytes",0,"publicationBytesLive",2097152,"finalRootBytes","UNAVAILABLE_UNTIL_ROOT_CLOSE_LOG");}
 @Test void completedNativeReturnIsEligibleForPointCheckingWithoutClaimingQualification(){
  assertTrue(BNativeHistoricalPointComparison.completed(result(),phase(),ownership(),0,false));
 }
 @Test void ambiguousCompleteAlternativesCanBeCheckedWithoutChoosingOne(){
  var r=result();r.put("nativeStatus","AMBIGUOUS");assertTrue(BNativeHistoricalPointComparison.completed(r,phase(),ownership(),0,false));
 }
 @Test void cutoffOrContradictoryExitAndPublicationCannotConferQuality(){
  assertFalse(BNativeHistoricalPointComparison.completed(result(),phase(),ownership(),1,false));
  assertFalse(BNativeHistoricalPointComparison.completed(result(),phase(),ownership(),0,true));
  var r=result();r.put("pipelineCompleted",false);assertFalse(BNativeHistoricalPointComparison.completed(r,phase(),ownership(),0,false));
  var o=new LinkedHashMap<>(ownership());o.put("remainingNonReportBytes",1);assertFalse(BNativeHistoricalPointComparison.completed(result(),phase(),o,0,false));
 }
 @Test void everyReturnedFailureStatusRemainsUnavailableEvenWithPartialRoutes(){
  for(String status:List.of("RESOURCE_LIMIT","CANCELLED","NUMERIC_FAILURE","NO_ROUTE")){
   var r=result();r.put("nativeStatus",status);assertFalse(BNativeHistoricalPointComparison.completed(r,phase(),ownership(),0,false));
  }
  var thrown=result();thrown.put("nativeStatus",null);thrown.put("nativeNormalReturns",0);thrown.put("engineReturned",false);
  assertFalse(BNativeHistoricalPointComparison.completed(thrown,phase(),ownership(),0,false));
 }
 @Test void bindsEveryAlternativeToExactRawBytesAndSnapshotWithoutTruthSelection()throws Exception {
  String id="connected-straight";var source=nativeSource(input(id));var escape=List.of(source.get(0),new MetricPoint(20,40),source.get(1));var raw=raw(id,List.of(source,escape));Path out=output();
  byte[] original=Files.readAllBytes(raw.resolve("route-0.json"));var summary=BNativeHistoricalPointComparison.evaluate(archive,raw,out,controls(id,0,noEngine));
  assertEquals(true,summary.get("available"));assertEquals(List.of("route-0.json","route-1.json"),summary.get("routeFiles"));assertNull(summary.get("selectedAlternative"));assertEquals(false,summary.get("modelQualified"));
  assertEquals(true,read(out.resolve("route-0.json")).get("passed"));assertEquals(false,read(out.resolve("route-1.json")).get("passed"));
  assertEquals(BIdentificationReceipt.hash(original),read(out.resolve("route-0.json")).get("rawRouteSha256"));assertArrayEquals(original,Files.readAllBytes(raw.resolve("route-0.json")));
  assertEquals(((Map<?,?>)binding(id).get("captured")).get("snapshotHash"),read(out.resolve("route-0.json")).get("neutralSnapshotHash"));
 }
 @Test void nativeFailuresAndNonzeroExitRetainRoutesWithoutGeometryPass()throws Exception {
  String id="connected-straight";for(String status:List.of("RESOURCE_LIMIT","NO_ROUTE","NUMERIC_FAILURE","CANCELLED","COMPLETE")){
   var raw=raw(id,List.of(nativeSource(input(id))));var r=read(raw.resolve("result.json"));r.put("nativeStatus",status);write(raw,"result.json",r);Path out=output();
   var summary=BNativeHistoricalPointComparison.evaluate(archive,raw,out,controls(id,status.equals("COMPLETE")?1:0,noEngine));assertEquals(false,summary.get("available"));
   var row=read(out.resolve("route-0.json"));assertEquals(false,row.get("available"));assertNull(row.get("passed"));assertEquals(status,row.get("rawNativeStatus"));
  }
 }
 @Test void snapshotBitsSourceAndPolicyMutationsRefuseRatherThanCreatingAuthority()throws Exception {
  String id="connected-straight";
  for(String key:List.of("historicalSource","protocolSha256","captured","originalMetricSourceRawBits","nativeRequest")){
   var raw=raw(id,List.of(nativeSource(input(id))));var b=read(raw.resolve("binding.json"));b.put(key,key.equals("captured")||key.equals("nativeRequest")?Map.of():"0".repeat(64));write(raw,"binding.json",b);Path out=output();
   assertThrows(SecurityException.class,()->BNativeHistoricalPointComparison.evaluate(archive,raw,out,controls(id,0,noEngine)),key);assertFalse(Files.exists(out));
  }
  var raw=raw(id,List.of(nativeSource(input(id))));var row=read(raw.resolve("route-0.json"));row.put("pointRawBits",List.of(List.of("0000000000000000","0000000000000000")));write(raw,"route-0.json",row);
  assertThrows(SecurityException.class,()->BNativeHistoricalPointComparison.evaluate(archive,raw,output(),controls(id,0,noEngine)));
 }
 @Test void cutoffAndEarlyFailureNeverInventNativeReturnOrFinalOwnership()throws Exception {
  String id="connected-straight";var raw=raw(id,List.of(nativeSource(input(id))));Path cutoff=raw.resolveSibling(id+"-external-cutoff.json");Files.writeString(cutoff,"{\"phase\":\"EXTERNAL_CUTOFF\"}");
  try{Path out=output();assertEquals(false,BNativeHistoricalPointComparison.evaluate(archive,raw,out,controls(id,0,noEngine)).get("available"));assertNull(read(out.resolve("route-0.json")).get("passed"));}finally{Files.delete(cutoff);}
  Path early=Files.createTempDirectory(temporary,"early-");write(early,"phase.json",Map.of("phase","NATIVE_ENGINE_CALL_PENDING"));Path out=output();
  var r=BNativeHistoricalPointComparison.evaluate(archive,early,out,controls(id,137,noEngine));assertEquals(false,r.get("available"));assertNull(r.get("rawNativeInvocations"));assertEquals("NOT_INFERRED_FROM_EXIT_OR_PUBLICATION",r.get("rawFinalRootBytes"));
 }
 @Test void thrownNativeCallHasUnknownReturnedCountAndRemainsUnavailable()throws Exception {
  String id="connected-straight";var raw=raw(id,List.of());var r=read(raw.resolve("result.json"));
  r.remove("returnedAlternativeCount");r.put("nativeStatus",null);r.put("nativeNormalReturns",0);r.put("engineReturned",false);
  r.put("pipelineCompleted",false);r.put("qualityComparisonAvailable",false);r.put("cause","RESOURCE_LIMIT");
  r.put("workAccounting",Map.of("actualAvailableTransitions",17));write(raw,"result.json",r);Path out=output();
  var summary=BNativeHistoricalPointComparison.evaluate(archive,raw,out,controls(id,1,CancellationProbe.NONE));
  assertEquals(false,summary.get("available"));assertNull(summary.get("rawNativeStatus"));
  var facts=(Map<?,?>)summary.get("rawNativeFacts");assertEquals("RESOURCE_LIMIT",facts.get("cause"));assertEquals(17,((Number)((Map<?,?>)facts.get("workAccounting")).get("actualAvailableTransitions")).intValue());
  assertEquals(8L*1024*1024,((Number)read(out.resolve("ownership.json")).get("checkerNonReportBytesAtPublication")).longValue());
 }
 @Test void partialStagingAndMissingFinalResultAreHashOnlyUnavailableEvenWithZeroExit()throws Exception {
  String id="connected-straight";var raw=raw(id,List.of(nativeSource(input(id))));Files.delete(raw.resolve("result.json"));Files.delete(raw.resolve("ownership.json"));
  Files.writeString(raw.resolve("result.json.partial"),"{not finished");Files.writeString(raw.resolve("route-1.json.partial"),"not JSON");
  Path out=output();var summary=BNativeHistoricalPointComparison.evaluate(archive,raw,out,controls(id,0,CancellationProbe.NONE));
  assertEquals(false,summary.get("available"));assertEquals("UNAVAILABLE_PARTIAL_RAW_PUBLICATION",summary.get("status"));assertEquals(List.of(),summary.get("routeFiles"));
  assertNull(summary.get("rawNativeStatus"));assertNull(summary.get("rawNativeInvocations"));assertFalse(Files.exists(out.resolve("route-0.json")));
  var hashes=(Map<?,?>)summary.get("rawLeafSha256");assertEquals(BIdentificationReceipt.hash(Files.readAllBytes(raw.resolve("route-1.json.partial"))),hashes.get("route-1.json.partial"));
  assertEquals(true,summary.get("sourceBindingAvailable"));
 }
 @Test void graphAndCensoredAvailabilityRemainTypedAndEndpointUlpIsNeverNormalized()throws Exception {
  for(String id:List.of("positive-twenty-five-representation","censored-flank")){
   var raw=raw(id,List.of(nativeSource(input(id))));Path out=output();BNativeHistoricalPointComparison.evaluate(archive,raw,out,controls(id,0,noEngine));
   var row=read(out.resolve("route-0.json"));assertEquals(false,row.get("available"));assertNull(row.get("passed"));
   assertEquals(id.equals("censored-flank")?"UNAVAILABLE_CENSORED_POSITION":"UNAVAILABLE_NATIVE_GRAPH_NOT_EXPOSED",row.get("availability"));
  }
  String id="reverse-original-mesh";var source=new ArrayList<>(nativeSource(input(id)));source.set(source.size()-1,new MetricPoint(8.0,37));var raw=raw(id,List.of(source));Path out=output();
  BNativeHistoricalPointComparison.evaluate(archive,raw,out,controls(id,0,noEngine));assertEquals(false,read(out.resolve("route-0.json")).get("nativeExactAnchors"));assertEquals(false,read(out.resolve("route-0.json")).get("passed"));
 }
 @Test void completedOriginalAndPreservationVerdictsHaveAvailableReasonsForBothPassAndFail()throws Exception {
  for(String id:List.of("connected-straight","same-trail-three-strong")){
   var source=nativeSource(input(id));var escape=new ArrayList<>(source);
   if(escape.size()==2)escape.add(1,new MetricPoint(1000,1000));else escape.set(1,new MetricPoint(1000,1000));
   var raw=raw(id,List.of(source,escape));Path out=output();BNativeHistoricalPointComparison.evaluate(archive,raw,out,controls(id,0,CancellationProbe.NONE));
   for(int i=0;i<2;i++){var row=read(out.resolve("route-"+i+".json"));assertEquals(true,row.get("available"));assertEquals(i==0,row.get("passed"));
    assertEquals("AVAILABLE_POINT_CHECK_RECORDED_NOT_QUALIFIED",row.get("availability"),id+" route"+i);}
  }
 }
 void ioFailure(boolean duringWrite)throws Exception {
  String id="connected-straight";var raw=raw(id,List.of(nativeSource(input(id))));Path out=output();var ledger=new AttemptMemoryLedger(64L*1024*1024);
  var primary=new java.io.IOException(duringWrite?"controlled write":"controlled close");var secondary=new java.io.IOException("controlled secondary close");
  BNativeHistoricalPointComparison.LeafOpen open=path->new java.io.FilterOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)){
   @Override public void write(byte[] bytes,int offset,int length)throws java.io.IOException {if(duringWrite){out.write(bytes,offset,1);throw primary;}out.write(bytes,offset,length);}
   @Override public void close()throws java.io.IOException {out.close();throw duringWrite?secondary:primary;}
  };
  var storage=new BNativeHistoricalPointComparison.Storage((parent,prefix)->parent.resolve(prefix+UUID.randomUUID()),open);
  Exception actual=assertThrows(java.io.IOException.class,()->BNativeHistoricalPointComparison.evaluateOwned(archive,raw,out,controls(id,0,CancellationProbe.NONE),storage,ledger));
  assertSame(primary,actual,"cleanup must preserve the primary I/O failure");if(duringWrite)assertTrue(Arrays.asList(actual.getSuppressed()).contains(secondary));
  assertEquals(0,ledger.currentBytes());assertFalse(Files.exists(out),"no published/partial directory remains");
  try(var files=Files.list(temporary)){assertFalse(files.anyMatch(p->p.getFileName().toString().contains("-neutral-")));}
 }
 @Test void partialWriteAndCloseFailureRollBackOwnedFileAndPreservePrimary()throws Exception {ioFailure(true);}
 @Test void closeFailureRollsBackOwnedFileAndPreservesPrimary()throws Exception {ioFailure(false);}
 @Test void failedStagingClaimPreservesEveryPreexistingFileAndOwner()throws Exception {
  String id="connected-straight";Path existing=Files.createDirectory(temporary.resolve("preexisting-staging"));Files.createDirectory(existing.resolve(id));
  var sentinels=new LinkedHashMap<String,byte[]>();for(String name:List.of("index.json",id+"/scalar.f64",id+"/validity.bin",id+"/descriptor.json")){
   byte[] bytes=("PREEXISTING-"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(existing.resolve(name),bytes);sentinels.put(name,bytes);
  }
  var storage=new BNativeHistoricalPointComparison.Storage((parent,prefix)->existing,BNativeHistoricalPointComparison.FILES.open());
  var ledger=new AttemptMemoryLedger(64L*1024*1024);Path raw=Files.createTempDirectory(temporary,"unused-raw-");Path out=output();
  assertThrows(FileAlreadyExistsException.class,()->BNativeHistoricalPointComparison.evaluateOwned(archive,raw,out,controls(id,0,CancellationProbe.NONE),storage,ledger));
  for(var e:sentinels.entrySet()){assertTrue(Files.exists(existing.resolve(e.getKey())),e.getKey());assertArrayEquals(e.getValue(),Files.readAllBytes(existing.resolve(e.getKey())),e.getKey());}
  assertEquals(0,ledger.currentBytes());assertFalse(Files.exists(out));
 }
 @Test void boundedInventoryPinsAndCommitBoundaryCancellationLeaveNoPublishedPrefix()throws Exception {
  String id="connected-straight";var raw=raw(id,List.of(nativeSource(input(id))));var bad=new BNativeHistoricalPointComparison.Controls(id,"0".repeat(64),PROTOCOL,MANIFEST,0,noEngine);
  assertThrows(SecurityException.class,()->BNativeHistoricalPointComparison.evaluate(archive,raw,output(),bad));
  Files.writeString(raw.resolve("unknown.json"),"{}");assertThrows(SecurityException.class,()->BNativeHistoricalPointComparison.evaluate(archive,raw,output(),controls(id,0,noEngine)));Files.delete(raw.resolve("unknown.json"));
  Path out=output();CancellationProbe afterStaging=()->Files.exists(out.resolve("route-0.json.partial"))&&Files.exists(out.resolve("result.json.partial"))&&Files.exists(out.resolve("ownership.json.partial"));
  assertThrows(java.util.concurrent.CancellationException.class,()->BNativeHistoricalPointComparison.evaluate(archive,raw,out,controls(id,0,afterStaging)));assertFalse(Files.exists(out));
  try(var files=Files.list(temporary)){assertFalse(files.anyMatch(p->p.getFileName().toString().contains("-neutral-")));}
 }
 @Test void explicitCliRunsInCurrentClasspathWithoutOldProducerOrGradlePerCase()throws Exception {
  String id="connected-straight";var raw=raw(id,List.of(nativeSource(input(id))));Path out=output();
  String cp=V022BNativeHistoricalClasspathTest.currentClasspath();
  assertTrue(cp.contains("build/classes/java/test"));assertFalse(cp.contains("josm-historical-native-capture"));
  var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",cp,BNativeHistoricalPointComparison.class.getName(),
   "--case",id,"--neutral-archive",archive.toString(),"--neutral-sha256",archiveSha,"--protocol-sha256",PROTOCOL,"--historical-receipts",raw.toString(),"--output",out.toString(),"--manifest-sha256",MANIFEST,"--producer-exit-code","0")
   .redirectErrorStream(true).redirectOutput(temporary.resolve("cli.log").toFile()).start();
  assertTrue(process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(temporary.resolve("cli.log")));
  assertEquals("POINT_CHECKS_RECORDED_NOT_QUALIFIED",read(out.resolve("result.json")).get("status"));
 }
}

/** Cheap per-job runtime emission: no fixture lifecycle, capture or oracle. */
class V022BNativeHistoricalClasspathTest {
 static String currentClasspath()throws Exception {
  String cp=System.getProperty("java.class.path");assertNotNull(cp);assertTrue(cp.length()<=65536);
  var entries=Arrays.stream(cp.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))).map(s->Path.of(s).toAbsolutePath().normalize()).toList();
  assertTrue(entries.size()<=128);var origins=new TreeMap<String,Object>();
  for(Class<?> type:List.of(BNativeHistoricalPointComparison.class,BNativePointAcceptance.class,BNativeNeutralFixtureExporter.class,ProbabilisticTraceEngine.class)){
   Path origin=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();assertTrue(entries.contains(origin),type.getName());origins.put(type.getName(),origin.toString());
   assertEquals(BNativeHistoricalPointComparison.class.getClassLoader(),type.getClassLoader());
  }
  Path test=Path.of(BNativeHistoricalPointComparison.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
  Path main=Path.of(ProbabilisticTraceEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
  assertEquals(test.getParent(),main.getParent());assertEquals("test",test.getFileName().toString());assertEquals("main",main.getFileName().toString());
  assertFalse(cp.contains("josm-historical-native-capture"));Files.writeString(Path.of("build/historical-point-current-classpath.txt"),cp+"\n");
  Files.write(Path.of("build/historical-point-current-code-sources.json"),BNativeNeutralFixtureExporter.json(origins));return cp;
 }
 @Test void emitsActualCurrentClasspathWithoutFixtureAcquisition()throws Exception {assertFalse(currentClasspath().isBlank());}
 @Test void pinnedRawParserRejectsChangedBytesBeforeUsingTheirCoordinates(@TempDir Path dir)throws Exception {
  Path file=dir.resolve("route-0.json");byte[] original="{\"points\":[[8,37],[32,37]]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  Files.write(file,original);String pin=BIdentificationReceipt.hash(original);Files.writeString(file,"{\"points\":[[8,37],[32,40]]}");
  assertThrows(SecurityException.class,()->BNativeHistoricalPointComparison.readPinned(file,pin));
 }
}

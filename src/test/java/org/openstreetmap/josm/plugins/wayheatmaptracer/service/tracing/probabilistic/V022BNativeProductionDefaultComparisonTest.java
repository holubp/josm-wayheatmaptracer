package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
class V022BNativeProductionDefaultComparisonTest {
 @Test void fixedInventoryIncludesOriginalTwentyEarlierSevenAndNewFive(){
  assertEquals(32,BNativeProductionDefaultComparison.ids().size());
  assertEquals(32,new HashSet<>(BNativeProductionDefaultComparison.ids()).size());
  assertTrue(BNativeProductionDefaultComparison.ids().contains("connected-sharp-apex"));
  assertEquals(5,BNativeProductionComparison.IDS.size());
 }
 @Test void ordinaryLedgerUsesActualProductionDefaultWhileOldComparisonRemains64MiB(){
  var l=BNativeProductionDefaultComparison.ledger();assertEquals(256L*1024*1024,l.limitBytes());
  try(var o=l.rootOwner();var r=o.reserve(64L*1024*1024+1)){assertTrue(l.currentBytes()>BNativeProductionComparison.BYTES);}
  assertEquals(0,l.currentBytes());assertEquals(64L*1024*1024,BNativeProductionComparison.BYTES);
  assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->{var old=new AttemptMemoryLedger(BNativeProductionComparison.BYTES);try(var o=old.rootOwner();var r=o.reserve(64L*1024*1024+1)){}});
 }
 @Test void captureCensusIncludesScalarCopyRetainedMetadataAndAllAliasOutput(){
  assertEquals(18_136_362L,BNativeProductionDefaultComparison.captureUpperBytes(25921,2));
  assertTrue(BNativeProductionDefaultComparison.captureUpperBytes(8991,12)<32L*1024*1024);
  assertThrows(IllegalArgumentException.class,()->BNativeProductionDefaultComparison.captureUpperBytes(25922,2));
 }
 @Test void terminalPhaseStatusDoesNotPretendAnAttemptRemainsInProgress(@TempDir Path dir)throws Exception{
  var r=BNativeProductionComparison.execute(BNativeProductionComparison.IDS.get(0),dir,()->0,()->true);
  assertEquals("CANCELLED",r.get("safeCause"));
  assertEquals("TERMINAL_BOUNDED_ATTEMPT_RECORDED",BIdentificationReceipt.read(dir.resolve("phase.json")).get("status"));
 }
 @Test void frozenSourceCapturesMatchLegacyBytesAndProduceReviewInventory()throws Exception{
  var nativeInputs=new LinkedHashMap<String,Object>();
  for(String id:BNativeProductionDefaultComparison.ids()){
   var ledger=BNativeProductionDefaultComparison.ledger();
   try(var owner=ledger.rootOwner();var render=owner.reserve(32L*1024*1024);var metadata=owner.reserve(BNativeProductionComparison.REPORT_BYTES)){
    var binding=new LinkedHashMap<String,Object>();
    var actual=V022BGapMatchedControlsTest.captureNativeDefaultSource(id,binding,CancellationProbe.NONE);
    var legacy=V022BGapMatchedControlsTest.nativeDefaultLegacyCapture(id);
    assertEquals(legacy.canonicalHash(),actual.canonicalHash(),id);
    assertArrayEquals(legacy.fields().get("scalar").copiedValues(),actual.fields().get("scalar").copiedValues(),id);
    assertArrayEquals(legacy.fields().get("scalar").copiedValidity(),actual.fields().get("scalar").copiedValidity(),id);
    assertArrayEquals(legacy.fields().get("scalar").copiedInterpolationValidity(),actual.fields().get("scalar").copiedInterpolationValidity(),id);
    var b=BNativeProductionComparison.bind(actual,V022BGapMatchedControlsTest.nativeDefaultSource(id));
    assertTrue(b.request().profileChainage().cumulativeGroundMeters().size()<=81,id);
    binding.put("nativeRequestedProfiles",b.request().profileChainage().cumulativeGroundMeters().size());
    binding.put("nativeSource",b.binding().get("nativeMetricSource"));
    binding.put("frame",actual.coordinateFrame().toString());binding.put("transform",actual.transform().toString());
    binding.put("resolution",actual.resolution().toString());binding.put("resampling",actual.resampling().toString());
    Files.createDirectories(Path.of("build/native-default-source-review"));
    Files.writeString(Path.of("build/native-default-source-review",id+".json"),BIdentificationReceipt.canonical(binding));
    var compact=new LinkedHashMap<String,Object>();
    for(String key:List.of("rasterSha256","validitySha256","snapshotHash","width","height","origin","pitchMeters","outputWidth","outputHeight","captureUpperBytes","nativeRequestedProfiles"))compact.put(key,binding.get(key));
    nativeInputs.put(id,compact);
    assertEquals(((Map<?,?>)BNativeProductionDefaultComparison.contract().get("nativeInputs")).get(id),BIdentificationReceipt.parse(BIdentificationReceipt.canonical(compact).getBytes(java.nio.charset.StandardCharsets.UTF_8)),id);
   }finally{assertEquals(0,ledger.currentBytes());}
  }
  Files.createDirectories(Path.of("build/native-default-source-review"));
  Files.writeString(Path.of("build/native-default-source-review/native-inputs.json"),BIdentificationReceipt.canonical(nativeInputs));
 }
 @Test void invalidIdsAndCancellationRefuseBeforeCapture(){
  assertThrows(IllegalArgumentException.class,()->V022BGapMatchedControlsTest.captureNativeDefaultSource("private",new HashMap<>(),CancellationProbe.NONE));
  var b=new HashMap<String,Object>();
  assertThrows(java.util.concurrent.CancellationException.class,()->V022BGapMatchedControlsTest.captureNativeDefaultSource("connected-straight",b,()->true));assertTrue(b.isEmpty());
 }
 @Test void frozenProtocolBindsAllThirtyTwoWithoutRebindingOldFive()throws Exception{
  var c=BNativeProductionDefaultComparison.contract();assertEquals(BNativeProductionDefaultComparison.ids(),c.get("cases"));
  assertEquals(268435456L,((Number)c.get("attemptBytes")).longValue());
  assertEquals(67108864L,((Number)BNativeProductionComparison.contract().get("attemptBytes")).longValue());
  assertNotEquals(BNativeProductionComparison.CONTRACT_SHA,BNativeProductionDefaultComparison.CONTRACT_SHA);
 }
 @Test void defaultCancellationPublishesActualPolicyButNoCaptureOrNativeCall(@TempDir Path dir)throws Exception{
  var r=BNativeProductionDefaultComparison.execute("connected-straight",dir,()->0,()->true);
  assertEquals(0,r.get("nativeInvocations"));assertEquals(0,r.get("nativeReturns"));assertEquals("CANCELLED",r.get("safeCause"));
  assertEquals(BNativeProductionDefaultComparison.IDENTITY,r.get("identity"));assertFalse(Files.exists(dir.resolve("binding.json")));
  var o=BIdentificationReceipt.read(dir.resolve("ownership.json"));assertEquals(0L,((Number)o.get("remainingNonReportBytes")).longValue());
  assertEquals(BNativeProductionDefaultComparison.CONTRACT_SHA,BIdentificationReceipt.read(dir.resolve("phase.json")).get("comparisonContractSha256"));
  assertThrows(IllegalStateException.class,()->BNativeProductionDefaultComparison.execute("connected-straight",dir,()->0,()->true));
 }
 /** Only the fixed separate CI job opts in; no local32 native executions. */
 @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="V022_RUN_NATIVE_B_DEFAULT_COMPARISON",matches="true")
 @Test void fixedOrdinaryNativeDefaultComparison()throws Exception{
  assertEquals("true",System.getenv("GITHUB_ACTIONS"));assertTrue(System.getenv("GITHUB_SHA")!=null&&System.getenv("GITHUB_SHA").matches("[0-9a-f]{40}"));
  String id=System.getenv("V022_NATIVE_B_DEFAULT_CASE");BNativeProductionDefaultComparison.requireCase(id);
  var r=BNativeProductionDefaultComparison.execute(id,Path.of("build/b-native-production-default-comparison",id),System::nanoTime,CancellationProbe.NONE);
  assertEquals(1,r.get("nativeInvocations"),"Factual source integration must reach native engine once");
  assertTrue(((Number)r.get("nativeReturns")).intValue()==1||List.of("RESOURCE_LIMIT","CANCELLED","NUMERIC_FAILURE").contains(r.get("safeCause")));
  assertEquals(false,r.get("modelQualification"));
 }
}

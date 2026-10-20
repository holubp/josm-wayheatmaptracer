package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022BNativeNeutralFixtureTest {
 @TempDir static Path temporary;
 static Path exported;
 static final AtomicInteger checkpoints=new AtomicInteger();
 static final StackWalker walker=StackWalker.getInstance();
 static final Set<String> forbiddenClasses=Set.of("ProbabilisticProfileFactory","BIdentificationFactsExtractor","ProbabilisticObservationModel","BGapField","BGapPairTable","ProbabilisticTraceEngine","ProbabilisticInference").stream()
  .map(n->"org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic."+n).collect(java.util.stream.Collectors.toUnmodifiableSet());
 static final CancellationProbe noNumerics=()->{
  checkpoints.incrementAndGet();
  boolean entered=walker.walk(frames->frames.anyMatch(frame->forbiddenClasses.contains(frame.getClassName())));
  assertFalse(entered,"Neutral capture entered a numerical stage");return false;
 };
 @BeforeAll static void exportOnce()throws Exception {exported=temporary.resolve("neutral");BNativeNeutralFixtureExporter.export(exported,noNumerics);}
 static Map<String,Object> index()throws Exception {
  assertTrue(Files.isRegularFile(exported.resolve("index.json")),"neutral export must publish its complete bounded manifest");
  return BIdentificationReceipt.read(exported.resolve("index.json"));
 }
 @Test void all32RawInputsMatchExistingFrozenHashes()throws Exception {
  var expected=BNativeProductionDefaultComparison.contract();var manifest=index();
  assertEquals(expected.get("cases"),manifest.get("cases"));
  var inputs=(Map<?,?>)expected.get("nativeInputs");
  for(String id:BNativeProductionDefaultComparison.ids()) {
   var facts=(Map<?,?>)inputs.get(id);var scalar=Files.readAllBytes(exported.resolve(id+"/scalar.f64"));var mask=Files.readAllBytes(exported.resolve(id+"/validity.bin"));
   assertEquals(52488,scalar.length);assertEquals(6561,mask.length);
   assertEquals(facts.get("rasterSha256"),BIdentificationReceipt.hash(scalar),id);assertEquals(facts.get("validitySha256"),BIdentificationReceipt.hash(mask),id);
  }
 }
 @Test void all32RecapturesMatchSnapshotScalarAndBothMasks()throws Exception {
  var index=index();assertEquals(32,((List<?>)index.get("cases")).size());
  var expected=(Map<?,?>)BNativeProductionDefaultComparison.contract().get("nativeInputs");
  for(String id:BNativeProductionDefaultComparison.ids()){
   var ledger=new AttemptMemoryLedger(64L*1024*1024);
   try(var owner=ledger.rootOwner();var metadata=owner.reserve(BNativeNeutralFixtureExporter.REPORT_BYTES);
       var witness=owner.reserve(BNativeNeutralFixtureExporter.CAPTURE_BYTES);
       var captured=BNativeNeutralFixtureExporter.recapture(exported,id,noNumerics,owner)){
    var actual=captured.snapshot();var frozen=(Map<?,?>)expected.get(id);var facts=BNativeNeutralFixtureExporter.captured(actual);
    assertEquals(frozen.get("snapshotHash"),actual.canonicalHash(),id);
    assertEquals(frozen.get("outputWidth"),((Map<?,?>)((Map<?,?>)index.get("captures")).get(id)).get("width"));
    var original=V022BGapMatchedControlsTest.nativeDefaultLegacyCapture(id);
    assertEquals(BIdentificationReceipt.canonical(BNativeNeutralFixtureExporter.captured(original)),BIdentificationReceipt.canonical(facts),id);
    assertTrue(ledger.currentBytes()<=64L*1024*1024);
   }
   assertEquals(0,ledger.currentBytes(),id);
  }
  assertTrue(checkpoints.get()>32,"real capture must execute the no-numerics sentinel");
 }
 @Test void quarterPhaseIsNotReplacedByIntegerGrid()throws Exception {
  index();var d=BIdentificationReceipt.read(exported.resolve("quarter-phase-alias/descriptor.json"));
  var grid=(Map<?,?>)d.get("outputGrid");assertEquals(List.of(-19.75,.25),grid.get("origin"));assertEquals(161,((Number)grid.get("width")).intValue());
  var original=(List<?>)d.get("inputRasterOrigin");assertEquals(0L,Double.doubleToRawLongBits(((Number)original.get(1)).doubleValue()));
  var thin=BIdentificationReceipt.read(exported.resolve("thin-three-strands/descriptor.json"));var nativeOrigin=(List<?>)thin.get("inputRasterOrigin");
  assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits(((Number)nativeOrigin.get(1)).doubleValue()),"original affine inverse provenance has a factual signed-zero distinction");
 }
 @Test void neutralArchiveContainsNoTruthOrRecordingPayload()throws Exception {
  var m=index();assertFalse(m.containsKey("truth"));
  for(String id:BNativeProductionDefaultComparison.ids()){
   var d=BIdentificationReceipt.read(exported.resolve(id+"/descriptor.json"));assertEquals(BNativeNeutralFixtureExporter.DESCRIPTOR_KEYS,d.keySet());
   for(String forbidden:List.of("truth","reference","expected","traces","recordings","states","observations","candidate","eta","oracle"))assertFalse(d.containsKey(forbidden));
   assertTrue(Files.size(exported.resolve(id+"/descriptor.json"))<=8192);
  }
  try(var paths=Files.walk(exported)){assertEquals(97,paths.filter(Files::isRegularFile).count());}
  var preparation=(Map<?,?>)m.get("preparation");assertEquals(0,((Number)preparation.get("engineInvocations")).intValue());assertEquals(0,((Number)preparation.get("remainingCaseOwnedBytes")).intValue());
  assertEquals(2L*1024*1024,((Number)preparation.get("publicationReservationBytes")).longValue());
  assertEquals(34L*1024*1024,((Number)preparation.get("ledgerPeakBytes")).longValue());
  assertEquals(18_139_322,BNativeProductionDefaultComparison.captureUpperBytes(25921,12));
  assertTrue(12L*1024*1024+18_139_322<32L*1024*1024);
  Path zip=exported.resolveSibling("neutral.zip");assertTrue(Files.size(zip)<=4L*1024*1024);
  BNativeNeutralFixtureExporter.validateArchive(Files.readAllBytes(zip));
  var bad=new LinkedHashMap<String,Object>(m);var wrongPreparation=new LinkedHashMap<String,Object>((Map<String,Object>)m.get("preparation"));wrongPreparation.put("truth",List.of(1,2));bad.put("preparation",wrongPreparation);
  assertThrows(IllegalArgumentException.class,()->BNativeNeutralFixtureExporter.validateIndex(bad));
 }
 static byte[] zip(String name,byte[] content)throws Exception {
  var bytes=new ByteArrayOutputStream();try(var z=new ZipOutputStream(bytes)){stored(z,name,content);}return bytes.toByteArray();
 }
 static void stored(ZipOutputStream z,String name,byte[] content)throws Exception{
  var e=new ZipEntry(name);e.setMethod(ZipEntry.STORED);e.setSize(content.length);var crc=new CRC32();crc.update(content);e.setCrc(crc.getValue());z.putNextEntry(e);z.write(content);z.closeEntry();
 }
 @Test void unknownDuplicateTraversalAndOversizedMembersRefuse()throws Exception {
  for(String name:List.of("../escape","unknown","/absolute"))assertEquals(name.equals("unknown")?"Neutral archive member":"Neutral archive path",assertThrows(IllegalArgumentException.class,()->BNativeNeutralFixtureExporter.validateArchive(zip(name,new byte[]{1})),name).getMessage());
  assertEquals("Neutral archive member size",assertThrows(IllegalArgumentException.class,()->BNativeNeutralFixtureExporter.validateArchive(zip("index.json",new byte[65537]))).getMessage());
  var bytes=new ByteArrayOutputStream();try(var z=new ZipOutputStream(bytes)){stored(z,"index.json",new byte[]{1});stored(z,"jndex.json",new byte[]{1});}
  byte[] duplicate=bytes.toByteArray(),before="jndex.json".getBytes(java.nio.charset.StandardCharsets.US_ASCII),after="index.json".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  for(int i=0;i<=duplicate.length-before.length;i++){boolean match=true;for(int j=0;j<before.length;j++)match&=duplicate[i+j]==before[j];if(match)System.arraycopy(after,0,duplicate,i,after.length);}
  assertEquals("Neutral archive duplicate",assertThrows(IllegalArgumentException.class,()->BNativeNeutralFixtureExporter.validateArchive(duplicate)).getMessage());
  assertThrows(IllegalArgumentException.class,()->BNativeNeutralFixtureExporter.validateArchive(new byte[4*1024*1024+1]));
  assertThrows(IllegalArgumentException.class,()->BNativeNeutralFixtureExporter.parse("[".repeat(9).getBytes(),8192,256));
  assertThrows(IllegalArgumentException.class,()->BNativeNeutralFixtureExporter.parse(" ".repeat(8193).getBytes(),8192,256));
 }
 @Test void invalidMaskOrNonfiniteScalarRefusesBeforeEngine() {
  var values=new byte[52488];var mask=new byte[6561];Arrays.fill(mask,(byte)1);mask[5]=2;
  assertThrows(IllegalArgumentException.class,()->BNativeNeutralFixtureExporter.validateRaw(values,mask));
  mask[5]=1;mask[0]=0;ByteBuffer.wrap(values).putDouble(Double.NaN);
  assertThrows(IllegalArgumentException.class,()->BNativeNeutralFixtureExporter.validateRaw(values,mask));
  ByteBuffer.wrap(values).putDouble(Double.POSITIVE_INFINITY);
  assertThrows(IllegalArgumentException.class,()->BNativeNeutralFixtureExporter.validateRaw(values,mask));
 }
 @Test void cancellationAndInsufficientFundingCloseBeforeCapture()throws Exception {
  var ledger=new AttemptMemoryLedger(32L*1024*1024-1);
  try(var owner=ledger.rootOwner()){
   assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->BNativeNeutralFixtureExporter.recapture(exported,BNativeProductionDefaultComparison.ids().get(0),noNumerics,owner));assertEquals(0,ledger.currentBytes());
  }
  var funded=new AttemptMemoryLedger(64L*1024*1024);
  try(var owner=funded.rootOwner()){
   assertThrows(java.util.concurrent.CancellationException.class,()->BNativeNeutralFixtureExporter.recapture(exported,BNativeProductionDefaultComparison.ids().get(0),()->true,owner));assertEquals(0,funded.currentBytes());
  }
 }
 @AfterAll static void retainBoundedReviewArchive()throws Exception {
  Path zip=exported.resolveSibling("neutral.zip");if(!Files.isRegularFile(zip))return;
  byte[] bytes=Files.readAllBytes(zip);BNativeNeutralFixtureExporter.validateArchive(bytes);
  Path review=Files.createTempDirectory(Path.of("build"),"native-neutral-review-");Path retained=review.resolve("neutral.zip");Files.write(retained,bytes);
  BNativeNeutralFixtureExporter.prepare(retained,BIdentificationReceipt.hash(bytes),review.resolve("staged"));
  System.out.println("NEUTRAL_REVIEW_DIRECTORY="+review+" ARCHIVE_SHA256="+BIdentificationReceipt.hash(bytes)+" CAPTURE_ONLY_CHECKPOINTS="+checkpoints.get());
 }
}

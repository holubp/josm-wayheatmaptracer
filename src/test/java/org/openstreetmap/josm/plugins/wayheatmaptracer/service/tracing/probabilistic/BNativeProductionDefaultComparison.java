package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Separate ordinary-default diagnostic authority, never experiment or qualification authority. */
final class BNativeProductionDefaultComparison {
 static final String IDENTITY="CURRENT_NATIVE_PRODUCTION_DEFAULT_256M_PUBLIC32_ENGINE_BOUNDARY_ONLY";
 static final String CONTRACT_SHA="34c0c300332f3d1eb5701eecf9b980a64c94940188d8fc5e6c221b5e4609639c";
 static List<String> ids(){return V022BGapMatchedControlsTest.nativeDefaultCases();}
 static void requireCase(String id){if(!ids().contains(id))throw new IllegalArgumentException("Fixed public32 case required");}
 static AttemptMemoryLedger ledger(){return AttemptMemoryLedger.production();}
 /** Source-census bound: one direct scalar input81²; output<=161²; <=12 source points.
  * Eight retained string occurrences <=4096 chars, <=2 lineage operations, one pitch sample.
  * Includes scalar copy and conservative metadata rather than relying on the capture's512MiB metadata guard. */
 static long captureUpperBytes(int outputPixels,int sourcePoints){
  if(outputPixels<4||outputPixels>25921||sourcePoints<2||sourcePoints>12)throw new IllegalArgumentException("Fixed capture census exceeded");
  long logical=Math.addExact(16L*6561,36L*outputPixels);
  long metadata=138760L+296L*(sourcePoints-2);
  return Math.addExact(Math.addExact(logical,logical/8),16L*1024*1024+8L*6561+metadata);
 }
 static void checkCaptureMetadata(String id,EvidenceSnapshot evidence,List<MetricPoint> source){
  requireCase(id);var field=evidence.fields().get("scalar");
  if(evidence.fields().size()!=1||field==null||source.size()>12||evidence.resolution().spatialPitchSamples().size()>1
    ||field.lineage().scalarOperations().size()>2)throw new SecurityException("Fixed capture metadata inventory changed");
  var strings=new ArrayList<String>(List.of(evidence.snapshotId(),evidence.sourceIdentity(),evidence.resampling().sourceTransformKind(),
    evidence.resampling().sourceTransformIdentity(),"scalar",field.lineage().sourcePalette()));
  strings.addAll(field.lineage().scalarOperations());
  if(strings.size()>8||strings.stream().anyMatch(s->s.length()>4096))throw new SecurityException("Fixed capture metadata strings exceeded");
  if(captureUpperBytes(Math.multiplyExact(field.width(),field.height()),source.size())>32L*1024*1024)throw new SecurityException("Fixed capture reserve exceeded");
 }
 static Map<String,Object> policy(){
  var p=new LinkedHashMap<>(BNativeProductionComparison.policy());p.put("identity",IDENTITY);
  p.put("attemptMemoryBytes",256L*1024*1024);p.put("memoryContext","ORDINARY_PRODUCTION_LEDGER_WITH_CAPTURE_AND_METADATA_FUNDED_INSIDE_SAME_ROOT");
  p.put("experimentQualificationMemoryUnchangedBytes",64L*1024*1024);return p;
 }
 static Map<String,Object> contract()throws Exception{
  try(var in=BNativeProductionDefaultComparison.class.getResourceAsStream("/b-observed-gap/native-production-default-comparison.json")){
   if(in==null)throw new SecurityException("Default comparison contract absent");var bytes=in.readNBytes(65537);
   if(bytes.length>65536||!CONTRACT_SHA.equals(BIdentificationReceipt.hash(bytes)))throw new SecurityException("Default comparison contract changed");
   var c=BIdentificationReceipt.parse(bytes);if(!ids().equals(c.get("cases")))throw new SecurityException("Default comparison inventory changed");return c;
  }
 }
 static Map<String,Object> execute(String id,Path directory,LongSupplier clock,CancellationProbe probe)throws Exception{
  requireCase(id);return BNativeProductionComparison.executeProductionDefaults(id,directory,clock,probe);
 }
 private BNativeProductionDefaultComparison(){}
}

package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.nio.file.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import java.time.LocalDateTime;
import java.awt.image.BufferedImage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Neutral public source handoff only; no profile or numerical execution authority. */
final class BNativeNeutralFixtureExporter {
 static final long COLD_BYTES=12L*1024*1024,CAPTURE_BYTES=20L*1024*1024,REPORT_BYTES=2L*1024*1024;
 static final int MAX_ZIP=4*1024*1024;
 static final String SCHEMA="neutral-native-public32-v1";
 static final Set<String> DESCRIPTOR_KEYS=Set.of("schema","id","width","height","origin","pitchMeters","source","frame","outputGrid","capture","inputRasterOrigin");
 /** Immutable scalar-only handoff. No observations, fixture truth or recording polylines. */
 record Input(double[] scalar,boolean[] validity,MetricPoint origin,double pitch,List<MetricPoint> source,MetricRasterGrid grid,RasterPoint inputRasterOrigin) {
  Input {
   if(scalar==null||validity==null||scalar.length!=6561||validity.length!=6561||origin==null||pitch!=1||source==null||source.size()<2||source.size()>12||grid==null
    ||inputRasterOrigin==null||(long)grid.width()*grid.height()>25921)throw new IllegalArgumentException("Neutral source dimensions");
   scalar=scalar.clone();validity=validity.clone();source=List.copyOf(source);
  }
  @Override public double[] scalar(){return scalar.clone();}
  @Override public boolean[] validity(){return validity.clone();}
 }
 static List<String> ids(){return BNativeProductionDefaultComparison.ids();}
 static void requireCase(String id){BNativeProductionDefaultComparison.requireCase(id);}
 static byte[] scalarBytes(double[] values){var b=ByteBuffer.allocate(Math.multiplyExact(values.length,8)).order(ByteOrder.BIG_ENDIAN);for(double v:values)b.putDouble(v);return b.array();}
 static byte[] maskBytes(boolean[] valid){byte[] b=new byte[valid.length];for(int i=0;i<b.length;i++)b[i]=(byte)(valid[i]?1:0);return b;}
 static List<Double> point(MetricPoint p){return List.of(p.xMeters(),p.yMeters());}
 static List<Double> geographic(GeographicPoint p){return List.of(p.latitudeDegrees(),p.longitudeDegrees());}
 static Map<String,Object> captureFields(String id){return Map.of("snapshotId","matched-"+id,"sourceIdentity","matched-public-declared-"+id,
  "selectedField","scalar","palette","matched-declared-scalar","acquisition","SYNTHETIC","derivation","DIRECT_INTENSITY",
  "correlationGroup","SYNTHETIC_TRUTH","nativePitchMeters",1,"renderedPitchMeters",1,"searchHalfWidthMeters",25);}
 static Map<String,Object> descriptor(String id,Input input){
  requireCase(id);var grid=input.grid();var frame=grid.coordinateFrame();var c=frame.distortionCertificate();
  var f=Map.<String,Object>of("projectionId",frame.projectionId(),"method",c.method(),"origin",geographic(frame.origin()),
   "southWest",geographic(c.southWest()),"northEast",geographic(c.northEast()),"bound",c.maximumRelativeDistanceError(),
   "eastMetersPerRadian",c.eastMetersPerRadian(),"northMetersPerRadian",c.northMetersPerRadian());
  var g=Map.<String,Object>of("origin",point(grid.firstPixelCenter()),"axes",List.of(grid.xAxisEastUnit(),grid.xAxisNorthUnit(),grid.yAxisEastUnit(),grid.yAxisNorthUnit()),
   "pitchMeters",grid.pitchMeters(),"width",grid.width(),"height",grid.height());
  var result=new TreeMap<String,Object>(Map.of("schema",SCHEMA,"id",id,"width",81,"height",81,"origin",point(input.origin()),"pitchMeters",input.pitch(),
   "source",input.source().stream().map(BNativeNeutralFixtureExporter::point).toList(),"frame",f,"outputGrid",g,"capture",captureFields(id)));
  result.put("inputRasterOrigin",List.of(input.inputRasterOrigin().x(),input.inputRasterOrigin().y()));return result;
 }
 static void validateRaw(byte[] scalar,byte[] validity) {
  if(scalar.length!=52488||validity.length!=6561)throw new IllegalArgumentException("Neutral raw length");
  var b=ByteBuffer.wrap(scalar).order(ByteOrder.BIG_ENDIAN);
  for(int i=0;i<validity.length;i++){double v=b.getDouble();if((validity[i]!=0&&validity[i]!=1)||!Double.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Neutral raw value");}
 }
 static byte[] json(Map<String,Object> value){return (BIdentificationReceipt.canonical(value)+"\n").getBytes(StandardCharsets.UTF_8);}
 /** Reject expansion before the general metadata parser allocates a tree. Numeric tokens<=64, strings<=256. */
 static Map<String,Object> parse(byte[] bytes,int limit,int nodes)throws Exception {
  if(bytes.length==0||bytes.length>limit)throw new IllegalArgumentException("Neutral JSON size");
  String text=new String(bytes,StandardCharsets.UTF_8);if(!Arrays.equals(bytes,text.getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException("Neutral UTF8");
  int depth=0,count=0;
  for(int i=0;i<text.length();i++){
   char c=text.charAt(i);
   if(c=='"'){if(++count>nodes)throw new IllegalArgumentException("Neutral JSON nodes");int length=0;boolean end=false;
    while(++i<text.length()){c=text.charAt(i);if(c=='"'){end=true;break;}if(c=='\\'){if(++i>=text.length())break;}if(++length>256)throw new IllegalArgumentException("Neutral JSON string");}
    if(!end)throw new IllegalArgumentException("Neutral JSON string");
   }else if(c=='{'||c=='['){if(++depth>8||++count>nodes)throw new IllegalArgumentException("Neutral JSON nesting");}
   else if(c=='}'||c==']'){if(--depth<0)throw new IllegalArgumentException("Neutral JSON nesting");}
   else if(c=='-'||Character.isDigit(c)||c=='t'||c=='f'||c=='n'){
    if(++count>nodes)throw new IllegalArgumentException("Neutral JSON nodes");int start=i;
    while(i+1<text.length()&&",]} \t\r\n".indexOf(text.charAt(i+1))<0)i++;
    if(i-start+1>64)throw new IllegalArgumentException("Neutral JSON token");
   }
  }
  if(depth!=0)throw new IllegalArgumentException("Neutral JSON nesting");return BIdentificationReceipt.parse(bytes);
 }
 static void keys(Map<?,?> map,Set<String> expected){if(!map.keySet().equals(expected))throw new IllegalArgumentException("Neutral descriptor schema");}
 static double number(Object v){if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Neutral number");return n.doubleValue();}
 static List<?> list(Object v,int size){if(!(v instanceof List<?> a)||a.size()!=size)throw new IllegalArgumentException("Neutral list");return a;}
 static MetricPoint metric(Object value){var p=list(value,2);return new MetricPoint(number(p.get(0)),number(p.get(1)));}
 static GeographicPoint geo(Object value){var p=list(value,2);return new GeographicPoint(number(p.get(0)),number(p.get(1)));}
 static Map<?,?> map(Object value){if(!(value instanceof Map<?,?> m))throw new IllegalArgumentException("Neutral object");return m;}
 static Input decode(String id,byte[] scalar,byte[] mask,byte[] descriptor)throws Exception {
  requireCase(id);validateRaw(scalar,mask);var d=parse(descriptor,8192,256);keys(d,DESCRIPTOR_KEYS);
  if(!SCHEMA.equals(d.get("schema"))||!id.equals(d.get("id"))||number(d.get("width"))!=81||number(d.get("height"))!=81||number(d.get("pitchMeters"))!=1
    ||!BIdentificationReceipt.canonical(captureFields(id)).equals(BIdentificationReceipt.canonical(d.get("capture"))))throw new IllegalArgumentException("Neutral descriptor identity");
  var f=map(d.get("frame"));keys(f,Set.of("projectionId","method","origin","southWest","northEast","bound","eastMetersPerRadian","northMetersPerRadian"));
  var frame=LocalMetricFrame.restoreCertified((String)f.get("projectionId"),(String)f.get("method"),geo(f.get("origin")),geo(f.get("southWest")),geo(f.get("northEast")),
   number(f.get("bound")),number(f.get("eastMetersPerRadian")),number(f.get("northMetersPerRadian")));
  var g=map(d.get("outputGrid"));keys(g,Set.of("origin","axes","pitchMeters","width","height"));var a=list(g.get("axes"),4);
  int width=integer(g.get("width")),height=integer(g.get("height"));
  var grid=new MetricRasterGrid(frame,metric(g.get("origin")),number(a.get(0)),number(a.get(1)),number(a.get(2)),number(a.get(3)),number(g.get("pitchMeters")),width,height);
  if(!(d.get("source") instanceof List<?> points)||points.size()<2||points.size()>12)throw new IllegalArgumentException("Neutral source count");
  var source=new ArrayList<MetricPoint>(points.size());for(var p:points)source.add(metric(p));
  double[] values=new double[6561];boolean[] valid=new boolean[6561];var b=ByteBuffer.wrap(scalar).order(ByteOrder.BIG_ENDIAN);
  for(int i=0;i<values.length;i++){values[i]=b.getDouble();valid[i]=mask[i]==1;}
  var raster=list(d.get("inputRasterOrigin"),2);
  return new Input(values,valid,metric(d.get("origin")),number(d.get("pitchMeters")),source,grid,new RasterPoint(number(raster.get(0)),number(raster.get(1))));
 }
 static int integer(Object v){double d=number(v);if(d<2||d>25921||d!=(int)d)throw new IllegalArgumentException("Neutral dimension");return (int)d;}
 /** Only production scalar capture; never constructs a profile, observation, G field or solver. */
 static EvidenceSnapshot capture(String id,Input input,CancellationProbe probe){
  probe.checkpoint();var grid=input.grid();var frame=grid.coordinateFrame();var origin=input.origin();double p=input.pitch();
  var inverse=SupportedInputRasterTransform.localMetricAffine(frame,input.inputRasterOrigin(),1/p,0,0,1/p);
  var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
   "matched-declared-scalar",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
  return new RasterEvidenceCapture(CAPTURE_BYTES).captureWithMetricScalarSource("matched-"+id,new BufferedImage(81,81,BufferedImage.TYPE_INT_ARGB),input.validity(),input.scalar(),
   input.source().stream().map(frame::toGeographic).toList(),input.source(),inverse,grid,EvidenceResolution.nativeSource(p,p),25,"matched-public-declared-"+id,
   EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,"scalar",lineage,probe);
 }
 static Map<String,Object> captured(EvidenceSnapshot snapshot){var field=snapshot.fields().get("scalar");return Map.of("snapshotHash",snapshot.canonicalHash(),
  "scalarSha256",BIdentificationReceipt.hash(scalarBytes(field.copiedValues())),"vertexMaskSha256",BIdentificationReceipt.hash(maskBytes(field.copiedValidity())),
  "interpolationMaskSha256",BIdentificationReceipt.hash(maskBytes(field.copiedInterpolationValidity())),"width",field.width(),"height",field.height());}
 static Set<String> memberNames(){var names=new TreeSet<String>();names.add("index.json");for(String id:ids())for(String leaf:List.of("scalar.f64","validity.bin","descriptor.json"))names.add(id+"/"+leaf);return names;}
 static int cap(String name){return name.equals("index.json")?65536:name.endsWith("scalar.f64")?52488:name.endsWith("validity.bin")?6561:8192;}
 static void write(Path directory,String name,byte[] bytes)throws Exception {
  if(!memberNames().contains(name)||bytes.length==0||bytes.length>cap(name))throw new IllegalArgumentException("Neutral member size");
  Path path=directory.resolve(name);Files.createDirectories(path.getParent());Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
 }
 static void export(Path directory)throws Exception {export(directory,CancellationProbe.NONE);}
 static void export(Path directory,CancellationProbe probe)throws Exception {
  var ledger=new AttemptMemoryLedger(64L*1024*1024);
  try(var root=ledger.rootOwner();var report=root.reserve(REPORT_BYTES)){
  Files.createDirectory(directory);var contract=map(BNativeProductionDefaultComparison.contract().get("nativeInputs"));
  var members=new TreeMap<String,Object>();var captures=new TreeMap<String,Object>();long peak=0;
  for(String id:ids()){
   try(var owner=root.child("neutral-case");var cold=owner.reserve(COLD_BYTES);var capture=owner.reserve(CAPTURE_BYTES)){
    probe.checkpoint();var input=V022BGapMatchedControlsTest.neutralNativeInput(id,probe);
    byte[] scalar=scalarBytes(input.scalar()),mask=maskBytes(input.validity()),d=json(descriptor(id,input));
    var restored=decode(id,scalar,mask,d);var expected=map(contract.get(id));
    if(!BIdentificationReceipt.hash(scalar).equals(expected.get("rasterSha256"))||!BIdentificationReceipt.hash(mask).equals(expected.get("validitySha256")))throw new SecurityException("Frozen neutral raw source changed");
    var actual=captured(capture(id,restored,probe));
    if(!actual.get("snapshotHash").equals(expected.get("snapshotHash"))||number(actual.get("width"))!=number(expected.get("outputWidth"))||number(actual.get("height"))!=number(expected.get("outputHeight")))throw new SecurityException("Frozen neutral capture changed: "+id);
    captures.put(id,actual);
    byte[][] data={scalar,mask,d};String[] leaves={"scalar.f64","validity.bin","descriptor.json"};
    for(int i=0;i<leaves.length;i++){String name=id+"/"+leaves[i];write(directory,name,data[i]);members.put(name,Map.of("bytes",data[i].length,"sha256",BIdentificationReceipt.hash(data[i])));}
    peak=Math.max(peak,ledger.peakBytes());
   }
   if(ledger.currentBytes()!=REPORT_BYTES)throw new IllegalStateException("Neutral case owners remained live");
  }
  write(directory,"index.json",json(Map.of("schema",SCHEMA,"cases",ids(),"members",members,"captures",captures,
   "preparation",Map.of("captureReturns",captures.size(),"engineInvocations",0,"ledgerPeakBytes",peak,
    "remainingCaseOwnedBytes",ledger.currentBytes()-REPORT_BYTES,"publicationReservationBytes",ledger.currentBytes(),"coldBytes",COLD_BYTES,"captureBytes",CAPTURE_BYTES))));
  Path archive=directory.resolveSibling(directory.getFileName()+".zip");
  try(var preparation=root.child("neutral-archive-preparation");var cold=preparation.reserve(COLD_BYTES)){
  try(var z=new ZipOutputStream(Files.newOutputStream(archive,StandardOpenOption.CREATE_NEW))){
   for(String name:memberNames()){byte[] data=bounded(directory.resolve(name),cap(name));var entry=new ZipEntry(name);entry.setTimeLocal(LocalDateTime.of(1980,1,1,0,0));entry.setMethod(ZipEntry.STORED);entry.setSize(data.length);entry.setCompressedSize(data.length);var crc=new CRC32();crc.update(data);entry.setCrc(crc.getValue());z.putNextEntry(entry);z.write(data);z.closeEntry();}
  }
  if(Files.size(archive)>MAX_ZIP)throw new IllegalArgumentException("Neutral archive cap");
  validateArchive(bounded(archive,MAX_ZIP));
  }
  }finally{if(ledger.currentBytes()!=0)throw new IllegalStateException("Neutral export owner leak");}
 }
 static byte[] bounded(Path path,int limit)throws Exception {
  if(Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.size(path)>limit)throw new IllegalArgumentException("Neutral input file");
  try(var in=Files.newInputStream(path)){byte[] b=in.readNBytes(limit+1);if(b.length==0||b.length>limit)throw new IllegalArgumentException("Neutral input file");return b;}
 }
 /** Preparation-only ZIP authentication. The future measured historical producer is ZIP-free. */
 static Map<String,byte[]> archiveEntries(byte[] bytes)throws Exception {
  if(bytes.length>MAX_ZIP)throw new IllegalArgumentException("Neutral archive cap");var result=new TreeMap<String,byte[]>();var allowed=memberNames();
  try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;
   while((entry=zip.getNextEntry())!=null){String name=entry.getName();
    if(entry.isDirectory()||name.startsWith("/")||name.contains("\\")||name.contains(":")||Arrays.asList(name.split("/",-1)).contains(".."))throw new IllegalArgumentException("Neutral archive path");
    if(!allowed.contains(name))throw new IllegalArgumentException("Neutral archive member");if(result.containsKey(name))throw new IllegalArgumentException("Neutral archive duplicate");
    if(entry.getMethod()!=ZipEntry.STORED||entry.getSize()<=0||entry.getSize()>cap(name))throw new IllegalArgumentException("Neutral archive member size");
    byte[] b=zip.readNBytes(cap(name)+1);if(b.length!=entry.getSize()||b.length>cap(name))throw new IllegalArgumentException("Neutral archive member size");result.put(name,b);
   }
  }
  if(!result.keySet().equals(allowed))throw new IllegalArgumentException("Neutral archive inventory");validateEntries(result);return result;
 }
 static void validateArchive(byte[] bytes)throws Exception {archiveEntries(bytes);}
 static void validateIndex(Map<String,Object> index){
  keys(index,Set.of("schema","cases","members","captures","preparation"));
  if(!SCHEMA.equals(index.get("schema"))||!ids().equals(index.get("cases")))throw new IllegalArgumentException("Neutral index identity");
  var expected=new TreeSet<>(memberNames());expected.remove("index.json");var members=map(index.get("members"));if(!members.keySet().equals(expected))throw new IllegalArgumentException("Neutral index inventory");
  for(String name:expected){var field=map(members.get(name));keys(field,Set.of("bytes","sha256"));double size=number(field.get("bytes"));if(size<1||size>cap(name)||size!=(int)size)throw new IllegalArgumentException("Neutral index size");digest(field.get("sha256"));}
  var captures=map(index.get("captures"));if(!captures.keySet().equals(new HashSet<>(ids())))throw new IllegalArgumentException("Neutral capture inventory");
  for(String id:ids()){var field=map(captures.get(id));keys(field,Set.of("snapshotHash","scalarSha256","vertexMaskSha256","interpolationMaskSha256","width","height"));
   for(String key:List.of("snapshotHash","scalarSha256","vertexMaskSha256","interpolationMaskSha256"))digest(field.get(key));
   if((long)integer(field.get("width"))*integer(field.get("height"))>25921)throw new IllegalArgumentException("Neutral capture dimension");}
  var preparation=map(index.get("preparation"));keys(preparation,Set.of("captureReturns","engineInvocations","ledgerPeakBytes","remainingCaseOwnedBytes","publicationReservationBytes","coldBytes","captureBytes"));
  if(number(preparation.get("captureReturns"))!=32||number(preparation.get("engineInvocations"))!=0||number(preparation.get("remainingCaseOwnedBytes"))!=0||number(preparation.get("publicationReservationBytes"))!=REPORT_BYTES
   ||number(preparation.get("ledgerPeakBytes"))!=COLD_BYTES+CAPTURE_BYTES+REPORT_BYTES||number(preparation.get("coldBytes"))!=COLD_BYTES||number(preparation.get("captureBytes"))!=CAPTURE_BYTES)
   throw new IllegalArgumentException("Neutral preparation metadata");
 }
 static void digest(Object value){if(!(value instanceof String s)||!s.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Neutral digest");}
 static Map<String,Object> validateEntries(Map<String,byte[]> entries)throws Exception {
  var index=parse(entries.get("index.json"),65536,4096);validateIndex(index);
  var members=map(index.get("members"));var expected=new TreeSet<>(memberNames());expected.remove("index.json");if(!members.keySet().equals(expected))throw new IllegalArgumentException("Neutral index inventory");
  for(String name:expected){var field=map(members.get(name));keys(field,Set.of("bytes","sha256"));var b=entries.get(name);if(number(field.get("bytes"))!=b.length||!BIdentificationReceipt.hash(b).equals(field.get("sha256")))throw new IllegalArgumentException("Neutral member digest");}
  for(String id:ids())decode(id,entries.get(id+"/scalar.f64"),entries.get(id+"/validity.bin"),entries.get(id+"/descriptor.json"));
  return index;
 }
 static void prepare(Path archive,String expectedHash,Path directory)throws Exception {
  var ledger=new AttemptMemoryLedger(64L*1024*1024);
  try(var owner=ledger.rootOwner();var cold=owner.reserve(COLD_BYTES);var metadata=owner.reserve(REPORT_BYTES)){
  byte[] bytes=bounded(archive,MAX_ZIP);if(!BIdentificationReceipt.hash(bytes).equals(expectedHash))throw new SecurityException("Neutral external archive digest");
  var entries=archiveEntries(bytes);Files.createDirectory(directory);for(var e:entries.entrySet())write(directory,e.getKey(),e.getValue());
  }finally{if(ledger.currentBytes()!=0)throw new IllegalStateException("Neutral preparation owner leak");}
 }
 record OwnedCapture(EvidenceSnapshot snapshot,AttemptMemoryLedger.Owner owner) implements AutoCloseable {
  @Override public void close(){owner.close();}
 }
 static OwnedCapture recapture(Path directory,String id,CancellationProbe probe,AttemptMemoryLedger.Owner parent)throws Exception {
  var owner=parent.child("neutral-selected-input-and-capture");
  try{owner.reserve(COLD_BYTES);owner.reserve(CAPTURE_BYTES);return new OwnedCapture(recaptureFunded(directory,id,probe),owner);}
  catch(Exception|Error failure){owner.close();throw failure;}
 }
 private static EvidenceSnapshot recaptureFunded(Path directory,String id,CancellationProbe probe)throws Exception {
  requireCase(id);probe.checkpoint();if(Files.isSymbolicLink(directory)||Files.isSymbolicLink(directory.resolve(id)))throw new IllegalArgumentException("Neutral input file");
  var index=parse(bounded(directory.resolve("index.json"),65536),65536,4096);validateIndex(index);var members=map(index.get("members"));
  byte[][] bytes=new byte[3][];String[] leaves={"scalar.f64","validity.bin","descriptor.json"};
  for(int i=0;i<3;i++){String name=id+"/"+leaves[i];bytes[i]=bounded(directory.resolve(name),cap(name));var m=map(members.get(name));
   if(number(m.get("bytes"))!=bytes[i].length||!BIdentificationReceipt.hash(bytes[i]).equals(m.get("sha256")))throw new SecurityException("Neutral selected member changed");}
  var input=decode(id,bytes[0],bytes[1],bytes[2]);var result=capture(id,input,probe);var fields=captured(result);
  if(!BIdentificationReceipt.canonical(fields).equals(BIdentificationReceipt.canonical(map(index.get("captures")).get(id))))throw new SecurityException("Neutral selected capture changed");
  return result;
 }
 private BNativeNeutralFixtureExporter() { }
}

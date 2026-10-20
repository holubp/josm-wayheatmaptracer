package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.InvocationTargetException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** PUBLIC HISTORICAL METADATA ONLY. Source text is never compiled or acquired from here.
 * The current checkout remains ineligible for every old empirical authority. */
final class HistoricalObservationMetadataFixture {
 static final String REVISION="bde65473d9de4df815606df57ac21339eee2c17e";
 static final String FREEZE_SHA="5aad59b456a8c3970074e93f876febda698e6285a0cce0bcf6e7b5ed60c38ed7";
 static final String ARCHIVE_SHA="e66124aef50c10c1be483f339e586642d7a6618089700829011f6b82cd3e104e";
 private static final String FREEZE="superpowers/docs/plans/2026-10-19-b-single-train-comparator-freeze.json";
 private static final String INDEX="src/test/resources/b-observation-identification/holdout-index.json";
 private static final Map<String,String> EXTRA=Map.of(
  "superpowers/docs/plans/2026-10-18-b-observation-feasibility-manifest.json","8fb47902e402bded101cd239d52b1f67655616f12ba6d0d7a35918b203a0aa77",
  "superpowers/docs/plans/2026-10-18-b-observation-reviewed-source.json","61246d9c65582c990ad52ed581a8de2c13b3a4671c4a3e70a0b1fd38289e17a6",
  "superpowers/docs/plans/2026-10-18-b-observation-runtime-diagnostic-freeze.json","3c9e872713736d1f475e3c9983bdf5be01e71dc88e89f24133a8cff241fd171d",
  "superpowers/docs/plans/2026-10-18-b-observation-prospective-fit-policy.json","53a9be63e27bd25476954f507e86e7d554c9f0953b9ea9de27851a0ef0fd3d93",
  "superpowers/docs/plans/2026-10-18-b-observation-optimized-runtime-policy.json","f298376bb5da000d71fa9fbb12b5d2c3635309d07e5cf758218a06feee4279c5",
  INDEX,"97b134e7c983b698679258f244ee8431696bb34257615ce27675e19c21f6b092");
 static byte[] archiveBytes()throws Exception{
  try(var in=HistoricalObservationMetadataFixture.class.getResourceAsStream("/b-observation-authority-fixtures/public-bde6547-metadata.zip.base64")){
   require(in!=null,"Historical archive absent");var encoded=in.readNBytes(4_194_305);require(encoded.length<=4_194_304,"Encoded archive cap");
   byte[] bytes=Base64.getMimeDecoder().decode(encoded);authenticateArchive(bytes);return bytes;
  }
 }
 static void authenticateArchive(byte[] bytes){require(bytes.length<=2_097_152&&ARCHIVE_SHA.equals(BIdentificationReceipt.hash(bytes)),"Historical archive digest changed");}
 /** Parser only, not authority: install always authenticates the independent archive digest first. */
 static Map<String,byte[]> readEntries(byte[] bytes,Set<String> allowed)throws Exception{
  if(bytes.length>2_097_152||allowed==null||allowed.isEmpty()||allowed.size()>87)throw new IllegalArgumentException("Historical archive bounds");
  var entries=new LinkedHashMap<String,byte[]>();long expanded=0;
  try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))){
   java.util.zip.ZipEntry e;while((e=zip.getNextEntry())!=null){
    String name=e.getName();Path path=Path.of(name);
    if(e.isDirectory()||path.isAbsolute()||name.indexOf('\\')>=0||name.indexOf(':')>=0||!path.normalize().equals(path)
      ||java.util.Arrays.asList(name.split("/",-1)).contains(".."))throw new IllegalArgumentException("Historical archive path");
    if(!allowed.contains(name))throw new IllegalArgumentException("Historical archive unknown member");
    if(entries.containsKey(name))throw new IllegalArgumentException("Historical archive duplicate member");
    if(entries.size()>=87)throw new IllegalArgumentException("Historical archive bounds");
    byte[] value=zip.readNBytes(1_048_577);
    if(value.length>1_048_576)throw new IllegalArgumentException("Historical archive member cap");
    expanded=Math.addExact(expanded,value.length);if(expanded>2_097_152)throw new IllegalArgumentException("Historical archive aggregate cap");
    entries.put(name,value);
   }
  }
  if(!entries.keySet().equals(allowed))throw new IllegalArgumentException("Historical archive incomplete inventory");
  return entries;
 }
 static Path install(Path root)throws Exception{
  var entries=readEntries(archiveBytes(),expectedPaths());
  byte[] freeze=entries.get(FREEZE);require(freeze!=null&&FREEZE_SHA.equals(BIdentificationReceipt.hash(freeze)),"Historical freeze changed");
  var members=(Map<?,?>)BIdentificationReceipt.parse(freeze).get("members");
  require(entries.keySet().equals(expectedPaths()),"Historical archive inventory changed");
  for(var item:members.entrySet())require(item.getValue().equals(BIdentificationReceipt.hash(entries.get(item.getKey()))),"Historical member changed: "+item.getKey());
  for(var item:EXTRA.entrySet())require(item.getValue().equals(BIdentificationReceipt.hash(entries.get(item.getKey()))),"Historical metadata changed: "+item.getKey());
  for(var item:entries.entrySet()){
   String name=item.getKey();Path target=root.resolve(name).normalize();require(target.startsWith(root.normalize()),"Unsafe historical path");
   Files.createDirectories(target.getParent());Files.write(target,item.getValue());
  }
  Path index=root.resolve("resources/b-observation-identification/holdout-index.json");Files.createDirectories(index.getParent());Files.write(index,entries.get(INDEX));
  return root;
 }
 static Set<String> expectedPaths()throws Exception{
  // This immutable public metadata file is independently pinned; no CURRENT Java source is read.
  byte[] bytes=Files.readAllBytes(Path.of(FREEZE));require(FREEZE_SHA.equals(BIdentificationReceipt.hash(bytes)),"Historical external tuple changed");
  var members=(Map<?,?>)BIdentificationReceipt.parse(bytes).get("members");var result=new HashSet<String>();
  for(Object key:members.keySet())result.add((String)key);result.addAll(EXTRA.keySet());result.add(FREEZE);return Set.copyOf(result);
 }
 static void probe(Path root,String action,Path output)throws Exception{
  if(!List.of("purposes","diagnostic","cost","comparator","catalog","binding","old-fit").contains(action))throw new IllegalArgumentException("Metadata-only action required");
  String classes=Path.of(HistoricalObservationMetadataFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  String main=Path.of(CancellationProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  String josm=Path.of(org.openstreetmap.josm.data.coor.LatLon.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
   String.join(java.io.File.pathSeparator,root.resolve("resources").toString(),classes,main,josm),Probe.class.getName(),action,
   ProspectiveTestInputs.expectedPins(),output.toAbsolutePath().toString()).directory(root.toFile()).redirectErrorStream(true);
  builder.environment().remove("GITHUB_ACTIONS");var process=builder.start();boolean done=process.waitFor(30,TimeUnit.SECONDS);
  if(!done)process.destroyForcibly();require(done,"Historical metadata parser timed out");
  String text=new String(process.getInputStream().readNBytes(8192),StandardCharsets.UTF_8);
  require(process.exitValue()==0&&text.equals("HISTORICAL_METADATA_ONLY_NO_EXECUTABLE_AUTHORITY\n"),text);
 }
 static void run(String action,Path output)throws Exception{
  Path root=Files.createTempDirectory(output.toAbsolutePath().getParent(),"historical-metadata-");
  try{probe(install(root),action,output);}finally{
   try(var walk=Files.walk(root)){for(Path path:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
  }
 }
 static Object publicPlan(BIdentificationCatalog.Phase phase,List<String> ids)throws Exception{
  var method=BObservationIdentificationTool.class.getDeclaredMethod("definitions",BIdentificationCatalog.class,BIdentificationCatalog.Phase.class,List.class);
  method.setAccessible(true);
  try{return method.invoke(null,BIdentificationCatalog.publicCatalog(),phase,ids);}
  catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException failure)throw failure;throw e;}
 }
 static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 public static final class Probe {
  private static void requirePurpose(Map<String,Object> provenance,String expected){
   require(expected.equals(provenance.get("protocolPurpose")),"Historical protocolPurpose changed: "+expected);
  }
  public static void main(String[] args)throws Exception{
   switch(args[0]){
    case "catalog", "binding" -> {
     var calls=new java.util.concurrent.atomic.AtomicInteger();
     var controls=new BObservationIdentificationTool.Controls(null,64L*1024*1024,3_600_000_000_000L,System::nanoTime,CancellationProbe.NONE,
      ()->{calls.incrementAndGet();throw new AssertionError("Historical metadata cannot acquire");});
     var result=BObservationIdentificationTool.runProspective(BIdentificationCatalog.Phase.CATALOG,Path.of(args[2]),null,controls,args[1]);
     require(result.status().equals("CATALOG_REGISTERED")&&calls.get()==0,"Historical metadata control acquired");
     if(args[0].equals("binding")) {
      var seal=BProspectiveIdentificationManifest.registerMetadata(BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT,args[1]);
      Class<?> guard=Class.forName(BObservationIdentificationTool.class.getName()+"$SourceGuard");
      Object forbidden=java.lang.reflect.Proxy.newProxyInstance(guard.getClassLoader(),new Class<?>[]{guard},
       (proxy,method,arguments)->{throw new AssertionError("Metadata binding check cannot enter an execution guard");});
      Class<?> binding=Class.forName(BObservationIdentificationTool.class.getName()+"$RunBinding");
      var constructor=binding.getDeclaredConstructor(BIdentificationManifest.class,String.class,String.class,String.class,
       guard,boolean.class,String.class,String.class,guard,Map.class);constructor.setAccessible(true);
      Object registered=constructor.newInstance(seal.family(),seal.manifestHash(),seal.sourceHash(),seal.catalogHash(),
       forbidden,true,seal.registryHash(),seal.pinsHash(),forbidden,seal.provenance());
      var verify=BObservationIdentificationTool.class.getDeclaredMethod("verifyBinding",Path.class,binding);verify.setAccessible(true);
      Path original=Path.of(args[2]).resolve("binding.json");verify.invoke(null,original,registered);
      var fields=new LinkedHashMap<String,Object>(BIdentificationReceipt.read(original));fields.put("sourceHash","0".repeat(64));
      Path changed=Path.of(args[2]).resolve("mutated-source-binding.json");BIdentificationReceipt.publish(changed,fields);
      try{verify.invoke(null,changed,registered);throw new AssertionError("Changed actual CATALOG binding accepted");}
      catch(InvocationTargetException expected){require(expected.getCause() instanceof IllegalArgumentException
       &&expected.getCause().getMessage().equals("CATALOG source/catalog/manifest binding changed"),"Wrong actual binding rejection: "+expected.getCause());}
      require(calls.get()==0,"Binding mutation acquired historical inputs");
     }
    }
    case "purposes" -> {
     var d=BProspectiveIdentificationManifest.registerMetadata(BProspectiveIdentificationManifest.Purpose.OPTIMIZED_TRAIN_DIAGNOSTIC,args[1]);
     var f=BProspectiveIdentificationManifest.registerMetadata(BProspectiveIdentificationManifest.Purpose.PROSPECTIVE_FIT,args[1]);
     d.verifyUnchanged();f.verifyUnchanged();
     require(d.sourceHash().equals(f.sourceHash())&&d.registryHash().equals(f.registryHash()),"Historical source tuple differs");
     require(!d.manifestHash().equals(f.manifestHash())&&!d.catalogHash().equals(f.catalogHash()),"Purpose distinction lost");
     require(BIdentificationCatalog.trainDiagnosticCatalog().canonicalHash().equals(d.catalogHash()),"Diagnostic catalog changed");
     require(BIdentificationCatalog.publicCatalog().canonicalHash().equals(f.catalogHash()),"FIT metadata catalog changed");
     requirePurpose(d.provenance(),"OPTIMIZED_TRAIN_DIAGNOSTIC");
     requirePurpose(f.provenance(),"PROSPECTIVE_FIT");
     // Controlled metadata projection only: a wrong emitted purpose must trip this same assertion.
     var wrong=new LinkedHashMap<String,Object>(d.provenance());wrong.put("protocolPurpose","PROSPECTIVE_FIT");
     boolean rejected=false;try{requirePurpose(wrong,"OPTIMIZED_TRAIN_DIAGNOSTIC");}catch(AssertionError expected){
      require(expected.getMessage().equals("Historical protocolPurpose changed: OPTIMIZED_TRAIN_DIAGNOSTIC"),"Wrong purpose assertion reason");rejected=true;
     }
     require(rejected,"Wrong purpose projection bypassed the historical assertion");
    }
    case "diagnostic" -> {
     var b=Files.readAllBytes(BSingleTrainDiagnosticManifest.FREEZE);BSingleTrainDiagnosticManifest.register(BIdentificationReceipt.hash(b)).verify();
     require(BSingleTrainDiagnosticManifest.memberPaths().equals(BSingleTrainDiagnosticManifest.currentMembers().keySet()),"Historical diagnostic inventory changed");
    }
    case "cost" -> {
     var b=Files.readAllBytes(BSingleTrainCostManifest.FREEZE);BSingleTrainCostManifest.register(BIdentificationReceipt.hash(b)).verify();
     require(BSingleTrainCostManifest.memberPaths().containsAll(BSingleTrainDiagnosticManifest.memberPaths()),"Historical cost omitted diagnostic inventory");
    }
    case "comparator" -> {
     BSingleTrainComparatorManifest.register(FREEZE_SHA).verify();
     require(BSingleTrainComparatorManifest.memberPaths().containsAll(BSingleTrainCostManifest.memberPaths()),"Historical comparator omitted cost inventory");
    }
    case "old-fit" -> {
     try{BIdentificationManifest.register(BObservationTrainRuntimeDiagnosticTool.MANIFEST,BIdentificationReceipt.sourceHash());throw new AssertionError("Old FIT accepted changed Tool");}
     catch(IllegalArgumentException expected){require(expected.getMessage().equals("Reviewed source member changed: "+BObservationTrainRuntimeDiagnosticTool.TOOL),"Tool-specific guard not reached: "+expected.getMessage());}
    }
    default -> throw new AssertionError("No empirical action exists");
   }
   System.out.println("HISTORICAL_METADATA_ONLY_NO_EXECUTABLE_AUTHORITY");
  }
 }
 private HistoricalObservationMetadataFixture(){}
}

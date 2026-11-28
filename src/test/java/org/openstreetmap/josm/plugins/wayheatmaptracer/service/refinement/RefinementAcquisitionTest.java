package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.VisibleRefinementCaptureTest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
class RefinementAcquisitionTest {
 static ParentTraceReference withSelectedSource(ParentTraceReference parent,String palette,String mapping,boolean aggregate) {
  var selected=new RefinementSourcePlanner.SourceRequest(palette,mapping,aggregate,"");
  return new ParentTraceReference(parent.root(),parent.parentStage(),parent.parentChoice(),parent.nativeCandidateId(),
    selected,parent.parentIdentity(),parent.points(),parent.correspondence());
 }
 static RefinementSourcePlanner.Plan selectedPlan(RefinementSourcePlanner.Plan template,String palette,String mapping,boolean aggregate) {
  var parent=withSelectedSource(template.parent(),palette,mapping,aggregate);
  return RefinementSourcePlanner.plan(template.ticket(),template.settings(),parent);
 }
 @Test void frozenVisiblePlanConvertsSameEpochPixelsAndRejectsShortFrameBeforeScalarWork() {
  var source=selectedPlan(VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE),"blue","blue",false);var epoch=new org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch();
  var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var old=VisibleEvidencePlanTest.original(source,epoch,1);
   var original=new LegacyVisibleSource(old.minimumEast(),old.minimumNorth(),old.maximumEast(),old.maximumNorth(),10,6,OptionalDouble.of(1),old.projectionCode(),old.sourceReceipt(),old.decisionRadiusMeters(),old.sampleStepMeters());
   var plan=VisibleEvidencePlan.create(source,VisibleEvidencePlanTest.factual(original),work);var raster=VisibleRefinementCaptureTest.raster(plan);
   var captured=RefinementAcquisition.captureVisible(source,plan,raster,"blue",work,Optional.empty());var evidence=captured.value().evidence();
   assertTrue(work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.HASH)>0,"Generic/fresh visible inputs retain their actual metered original ARGB hash");
   assertEquals(plan.grid().transform(),evidence.transform());assertEquals(plan.grid().width(),evidence.resampling().outputWidth());assertEquals(1,evidence.resolution().outputRasterPitchMeters());
   assertTrue(work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.MAP)>0);
   var descriptor=captured.value().retentionDescriptor(source.requests().stream().filter(r->r.palette().equals("blue")&&r.mapping().equals("blue")).findFirst().orElseThrow(),evidence);
   assertEquals(original.sourceReceipt(),descriptor.visibleReceipt().orElseThrow());assertFalse(descriptor.inputByteHash().isEmpty());
   long mapped=work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.MAP);
   var tiny=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.VisibleRaster(6,6,new int[36],original.minimumEast(),original.minimumNorth(),original.minimumEast()+10,original.minimumNorth()+10,10,6,OptionalDouble.of(1),raster.sourceIdentity(),raster.projectionCode(),original.sourceReceipt());
   assertThrows(IllegalStateException.class,()->RefinementAcquisition.captureVisible(source,plan,tiny,"blue",work));assertEquals(mapped,work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.MAP));
   var coarser=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.VisibleRaster(raster.width(),raster.height(),raster.argb(),raster.minimumEast(),raster.minimumNorth(),raster.maximumEast(),raster.maximumNorth(),10,6,OptionalDouble.of(2),raster.sourceIdentity(),raster.projectionCode(),original.sourceReceipt());
   assertThrows(UnsupportedOperationException.class,()->RefinementAcquisition.captureVisible(source,plan,coarser,"blue",work));assertEquals(mapped,work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.MAP));
   epoch.sourceChanged();assertThrows(VisibleEvidencePlan.StaleSource.class,descriptor::requireCurrent);assertThrows(VisibleEvidencePlan.StaleSource.class,captured.value()::evidence);captured.owner().close();
  }assertEquals(0,ledger.currentBytes());
 }
 static final class EventCancellation implements CancellationProbe {
  private final java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners=new java.util.concurrent.CopyOnWriteArrayList<>();
  public boolean cancelled(){return cancelled.get();}
  public Registration onCancellation(Runnable listener){listeners.add(listener);if(cancelled())listener.run();return ()->listeners.remove(listener);}
  void cancel(){cancelled.set(true);listeners.forEach(Runnable::run);}
 }
 @TempDir java.nio.file.Path temp;
 @Test void captureEpochCannotResurrectBeforeTheFirstMapping() throws Exception {
  var parent=nearBoundaryHaloParent();var plan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(parent),RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.DIRECT_VALUE,false),parent);
  var bytes=png();var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((r,c)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,""),new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())) {
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()) {
    var capture=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner).value();
    coordinator.updateActiveGeneration(new ManagedTileGeneration(10));coordinator.updateActiveGeneration(new ManagedTileGeneration(9));
    long before=ledger.currentBytes();assertFalse(capture.isCurrent(coordinator));assertThrows(IllegalStateException.class,()->capture.evidence("blue",CancellationProbe.NONE));assertEquals(before,ledger.currentBytes());
   }assertEquals(0,ledger.currentBytes());
  }
 }
 @Test void retainedSourceSurvivesWorkerSettlementAndStillChecksExternalGeneration() throws Exception {
  var parent=nearBoundaryHaloParent();
  var plan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(parent),RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.DIRECT_VALUE,false),parent);
  var bytes=png();var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((r,c)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,""),new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())) {
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var ledger=AttemptMemoryLedger.production();
   try(var root=ledger.rootOwner()) {
    var worker=root.child("worker");var publication=root.child("publication");
    var capture=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,worker).value();
    var request=plan.requests().stream().filter(s->s.palette().equals("blue")&&s.mapping().equals("blue")).findFirst().orElseThrow();
    var evidence=capture.evidence("blue",CancellationProbe.NONE);
    var retained=capture.retentionDescriptor(request,evidence);
    long withRaw=ledger.currentBytes();
    capture.releaseRasterInputs(evidence);
    assertTrue(ledger.currentBytes()<withRaw,"Frozen scalar evidence no longer pins the acquired mosaics");
    assertTrue(capture.isCurrent(coordinator));
    assertSame(retained,capture.retentionDescriptor(request,evidence));
    assertFalse(capture.sourceReceipts().isEmpty(),"Raw-pixel settlement preserves factual receipts");
    assertTrue(retained.inputByteHash().isEmpty());assertTrue(retained.inputHashProtocol().isEmpty(),"Old source API keeps honest ineligible absence and performs no optional input hashing");
    assertSame(evidence,retained.evidence());assertEquals(IntensitySamplingMode.DIRECT_VALUE,retained.intensityMode());
    assertEquals(9,retained.generation().orElseThrow());assertTrue(retained.visibleReceipt().isEmpty());
    assertEquals("blue",retained.source().palette());assertEquals("blue",retained.source().mapping());
    assertEquals(evidence.resampling().inputWidth(),retained.referenceWidth());
    assertEquals(evidence.resampling().sourceTransformIdentity(),retained.referenceTransform().parameterIdentity());
    long charged=ledger.currentBytes();retained.retainInto(publication);retained.retainInto(publication);
    assertEquals(charged,ledger.currentBytes(),"co-ownership must not double-charge immutable arrays or descriptors");
    worker.close();retained.requireCurrent();assertTrue(ledger.currentBytes()>=retained.backingBytes());
    assertSame(evidence.fields().get("native"),retained.evidence().fields().get(retained.fieldName()));
    coordinator.updateActiveGeneration(new ManagedTileGeneration(10));assertThrows(IllegalStateException.class,retained::requireCurrent);
    coordinator.updateActiveGeneration(new ManagedTileGeneration(9));assertThrows(IllegalStateException.class,retained::requireCurrent,"A recycled numeric generation is not the original lifetime");
    publication.close();assertEquals(0,ledger.currentBytes());
   }
  }
 }
 @Test void sameImageMappingsHaveDistinctChargedEvidenceGroupsAfterSettlement() throws Exception {
  var parent=withSelectedSource(RefinementSourcePlannerTest.parent(),"blue","blue",false);var plan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(parent),RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.COLOR_MAPPING,false),parent);
  var alternativeParent=withSelectedSource(parent,"blue","hot-corridor",false);var alternativePlan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(alternativeParent),RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.COLOR_MAPPING,false),alternativeParent);
  var bytes=png();var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((r,c)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,""),new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())) {
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var ledger=AttemptMemoryLedger.production();try(var root=ledger.rootOwner()) {
    var worker=root.child("mapping-worker");var publication=root.child("mapping-publication");
    var acquisition=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator);
    var capture=acquisition.acquire(plan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,worker).value();
    var alternativeCapture=acquisition.acquire(alternativePlan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,worker).value();
    var hashWork=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,System.nanoTime()+300_000_000_000L,CancellationProbe.NONE,worker);
    var base=capture.evidence("blue",CancellationProbe.NONE,hashWork);
    var alternative=alternativeCapture.mappingEvidence("hot-corridor",CancellationProbe.NONE,hashWork);
    assertTrue(hashWork.visits()>0,"Each separately selected operation hashes its actual acquired image");
    var first=capture.retentionDescriptor(plan.requests().stream().filter(s->s.palette().equals("blue")&&s.mapping().equals("blue")).findFirst().orElseThrow(),base);
    var second=alternativeCapture.retentionDescriptor(alternativePlan.requests().get(0),alternative);
    assertNotSame(base,alternative);assertNotSame(base.fields().get("native"),alternative.fields().get("native"));
    assertNotEquals(first.imageOwnershipIdentity(),second.imageOwnershipIdentity());assertNotEquals(first.source(),second.source());
    var sourceField=RefinementAcquisition.Capture.class.getDeclaredField("sources");sourceField.setAccessible(true);
    var actualInputs=(ManagedModernPreviewSource.RefinementSources)sourceField.get(capture);var inputImage=actualInputs.raster("blue",coordinator).image();
    var independent=java.security.MessageDigest.getInstance("SHA-256");for(int y=0;y<inputImage.getHeight();y++)for(int x=0;x<inputImage.getWidth();x++){int pixel=inputImage.getRGB(x,y);independent.update((byte)(pixel>>>24));independent.update((byte)(pixel>>>16));independent.update((byte)(pixel>>>8));independent.update((byte)pixel);}
    assertEquals(java.util.HexFormat.of().formatHex(independent.digest()),first.inputByteHash());assertEquals(first.inputByteHash(),second.inputByteHash());
    assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL,first.inputHashProtocol());
    long before=ledger.currentBytes();first.retainInto(publication);second.retainInto(publication);first.retainInto(publication);assertEquals(before,ledger.currentBytes());
    worker.close();first.requireCurrent();second.requireCurrent();
    assertEquals(first.backingBytes()+first.descriptorBytes()+second.backingBytes()+second.descriptorBytes(),ledger.currentBytes());
    assertNotEquals(base.canonicalHash(),alternative.canonicalHash(),"Mapping lineage is part of scalar evidence identity");
    publication.close();assertEquals(0,ledger.currentBytes());
   }
  }
 }
 @Test void admittedOriginalHashRefusesBeforeCopiesAndRejectsStaleGenerationDuringHashing() throws Exception {
  var parent=withSelectedSource(RefinementSourcePlannerTest.parent(),"blue","blue",false);var plan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(parent),RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.COLOR_MAPPING,false),parent);var bytes=png();var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((r,c)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,""),new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())) {
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()) {
    var capture=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner).value();long before=ledger.currentBytes();
    var refused=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(0,AttemptMemoryLedger.MAX_BYTES,System.nanoTime()+60_000_000_000L,CancellationProbe.NONE,owner);
    assertThrows(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.LimitReached.class,()->capture.evidence("blue",CancellationProbe.NONE,refused));assertEquals(before,ledger.currentBytes());
    var changed=new java.util.concurrent.atomic.AtomicBoolean();var workRef=new java.util.concurrent.atomic.AtomicReference<org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext>();
    CancellationProbe epochDuringHash=()->{var actual=workRef.get();if(actual!=null&&actual.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.HASH)>256&&changed.compareAndSet(false,true))coordinator.updateActiveGeneration(new ManagedTileGeneration(10));return false;};
    var work=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,System.nanoTime()+60_000_000_000L,epochDuringHash,owner);workRef.set(work);
    assertThrows(IllegalStateException.class,()->capture.evidence("blue",CancellationProbe.NONE,work));assertTrue(changed.get());assertFalse(capture.isCurrent(coordinator));
    assertDoesNotThrow(parent::requireComplete,"Parent reference remains available after source refusal");
   }assertEquals(0,ledger.currentBytes());
  }
 }
 @Test void managedNearTileBoundaryRidgeRetainsMeasuredNativeAReductionFour() throws Exception {
  var parent=withSelectedSource(nearBoundaryHaloParent(),"blue","blue",false);
  var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,parent.parentStage(),parent.parentChoice(),TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE);
  var plan=RefinementSourcePlanner.plan(ticket,RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.DIRECT_VALUE,false),parent);
  var frame=parent.root().evidence().coordinateFrame();
  var requested=Collections.synchronizedSet(new LinkedHashSet<ManagedTileAddress>());
  var images=new HashMap<Integer,byte[]>();
  // Both possible rows are real generated imagery; the current crop requests only11112.
  for(int tileY:List.of(11112,11113)){
   var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);
   for(int y=0;y<512;y++){
    double worldY=(tileY*512.0+y+.5)/(512.0*(1<<15));
    double latitude=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*worldY))));
    double groundY=frame.toMetric(new GeographicPoint(latitude,frame.origin().longitudeDegrees())).yMeters();
    int red=(int)Math.round(245*Math.exp(-.5*Math.pow(groundY/2.0,2)));
    for(int x=0;x<512;x++){
     int green=Math.min(red,(x/8+3*(y/8))%4);
     image.setRGB(x,y,(red==0?0:0xff000000)|(red<<16)|(green<<8));
    }
   }
   var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);images.put(tileY,bytes.toByteArray());
  }
  var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((request,credentials)->{
   requested.add(request.address());
   assertEquals(17658,request.address().x());assertEquals(15,request.address().zoom());
   var bytes=images.get(request.address().y());assertNotNull(bytes,"unexpected tile beyond analytic fixture");
   return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,"");
  },new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));
   var ledger=AttemptMemoryLedger.production();
   try(var owner=ledger.rootOwner()){
    var acquired=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner);
    assertEquals(Set.of("blue"),acquired.value().palettes(),"only the selected detector source is acquired for refinement");
    assertEquals(Set.of("blue"),requested.stream().map(ManagedTileAddress::color).collect(java.util.stream.Collectors.toSet()));
    assertEquals(requested.size(),acquired.value().workReceipt().logicalTileRequests());
    var evidence=acquired.value().evidence("blue",CancellationProbe.NONE);
    var field=evidence.fields().get("native");
    for(boolean valid:field.copiedValidity())assertTrue(valid,"raw acquisition is observed; this is not nodata");
    assertSame(frame,evidence.coordinateFrame());
    var scoped=parent.forOperation(plan.operationIdentity());scoped.reserveBindingWorkspace(evidence,acquired.owner());
    var derived=scoped.request(TrackerMode.CORRIDOR_AWARE,evidence,parent.root().request().budgets(),evidence.resolution().outputRasterPitchMeters());
    assertEquals(8.0,derived.permissions().ordinaryRadiusMeters());
    assertEquals(parent.root().request().permissions(),derived.permissions());
    assertEquals(parent.root().request().selectedRange(),derived.selectedRange());
    assertEquals(parent.root().network().canonicalHash(),derived.networkContentHash());
    var sampled=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedScalarProfileSampler().sampleRadiusThree(
      evidence,"native",derived.corridorInput().orElseThrow().profileLocations(),8.0,
      derived.corridorInput().orElseThrow().lateralStepMeters(),CancellationProbe.NONE,
      derived.profileChainage().sourceOriginGroundMeters(),acquired.owner());
    assertEquals(List.of(1,2,4),sampled.profiles().levels().stream().map(level->level.reduction()).toList());
    var coarse=sampled.profiles().levels().stream().filter(level->level.reduction()==4).findFirst().orElseThrow();
    long measured=coarse.profiles().stream().filter(profile->profile.anchorWithinRaster()
      &&profile.intensitySamples().stream().anyMatch(sample->sample.insideRaster())).count();
    System.out.println("halo nativeA grid="+field.width()+"x"+field.height()+" measuredR4="+measured+"/"+coarse.profiles().size()+" actualTiles="+requested);
    assertTrue(measured>0,"native A R4 must retain measured ridge rows; fully observed imagery must not become crop-induced nodata");
    assertEquals(115,field.width());assertEquals(37,field.height());
    assertTrue(requested.stream().anyMatch(address->address.y()==11113),"native context requires the south tile without changing r=8m");
    assertEquals(2,requested.size(),"the selected palette acquires both actual tile rows");
   }
   assertEquals(0,ledger.currentBytes());
  }
 }

 @Test void observedZeroHaloStaysMeasuredWhileMissingTileStaysUnavailable() throws Exception {
  var parent=withSelectedSource(nearBoundaryHaloParent(),"blue","blue",false);
  var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,parent.parentStage(),parent.parentChoice(),TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE);
  var plan=RefinementSourcePlanner.plan(ticket,RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.DIRECT_VALUE,false),parent);
  var grayParent=withSelectedSource(parent,"gray","gray",false);var grayPlan=RefinementSourcePlanner.plan(ticket,RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.DIRECT_VALUE,false),grayParent);
  var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB),"png",bytes);
  var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((request,credentials)->request.address().color().equals("gray")&&request.address().y()==11113
    ?new TransportResponse(TileFetchStatus.NO_TILE,404,"",null,null,Duration.ZERO,"")
    :new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes.toByteArray(),null,Duration.ZERO,""),new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var ledger=AttemptMemoryLedger.production();
   try(var owner=ledger.rootOwner()){
    var acquisition=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator);
    var acquired=acquisition.acquire(plan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner);
    var grayAcquired=acquisition.acquire(grayPlan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner);
    assertEquals(Set.of("blue"),acquired.value().palettes());
    assertEquals(ManagedModernPreviewSource.RefinementStatus.UNAVAILABLE,grayAcquired.value().sourceOutcomes().get("gray"));
    assertEquals(2,acquired.value().workReceipt().logicalTileRequests());assertEquals(2,grayAcquired.value().workReceipt().logicalTileRequests());
    var evidence=acquired.value().evidence("blue",CancellationProbe.NONE);var field=evidence.fields().get("native");
    for(boolean valid:field.copiedValidity())assertTrue(valid,"transparent observed pixels remain valid measurements");
    assertTrue(Arrays.stream(field.copiedValues()).allMatch(value->value==0));
    var scoped=parent.forOperation(plan.operationIdentity());scoped.reserveBindingWorkspace(evidence,acquired.owner());
    var request=scoped.request(TrackerMode.CORRIDOR_AWARE,evidence,parent.root().request().budgets(),evidence.resolution().outputRasterPitchMeters());
    assertEquals(parent.root().request().permissions(),request.permissions());assertEquals(parent.root().request().networkContentHash(),request.networkContentHash());
    var sampled=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedScalarProfileSampler().sampleRadiusThree(evidence,"native",request.corridorInput().orElseThrow().profileLocations(),8,
      request.corridorInput().orElseThrow().lateralStepMeters(),CancellationProbe.NONE,request.profileChainage().sourceOriginGroundMeters(),acquired.owner());
    var coarse=sampled.profiles().levels().stream().filter(level->level.reduction()==4).findFirst().orElseThrow();
    assertTrue(coarse.profiles().stream().anyMatch(profile->profile.anchorWithinRaster()));
    assertTrue(coarse.profiles().stream().flatMap(profile->profile.intensitySamples().stream()).allMatch(sample->sample.nativeIntensity()==0));
   }assertEquals(0,ledger.currentBytes());
  }
 }

 @Test void managedBUsesFactualOrientationContextWithoutChangingAuthority() throws Exception {
  var parent=withSelectedSource(nearBoundaryHaloParent(),"blue","blue",false);var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,parent.parentStage(),parent.parentChoice(),TrackerMode.PROBABILISTIC,AlignmentMode.PRECISE_SHAPE);
  var plan=RefinementSourcePlanner.plan(ticket,RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.DIRECT_VALUE,false),parent);
  var calls=new ArrayList<ManagedTileAddress>();var decoder=new TileDecoderClassifier();var bytes=png();
  try(var coordinator=new TileFetchCoordinator((r,c)->{calls.add(r.address());return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,"");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){
    var captured=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner);
    var evidence=captured.value().evidence("blue",CancellationProbe.NONE);var field=evidence.fields().get("native");
    assertEquals(99,field.width());assertEquals(21,field.height());assertEquals(2,calls.size());assertTrue(calls.stream().anyMatch(a->a.y()==11113));
    assertSame(parent.root().evidence().coordinateFrame(),evidence.coordinateFrame());assertEquals(8,parent.root().request().permissions().ordinaryRadiusMeters());
    assertEquals(parent.root().evidence().sourceIdentity(),evidence.sourceIdentity());for(boolean valid:field.copiedValidity())assertTrue(valid);
    var orientation=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.ImageOrientationDescriptor().describe(evidence,field,new MetricPoint(0,-7),evidence.resolution().outputRasterPitchMeters());
    assertTrue(orientation.sampledRayPoints()>0);assertTrue(orientation.responses().stream().allMatch(response->response.forwardValidFraction()==1&&response.backwardValidFraction()==1));
   }assertEquals(0,ledger.currentBytes());
  }
 }
 @Test void originalCertificateRefusesExpandedImageBeforeAnyTransport() throws Exception {
  var broad=nearBoundaryHaloParent().root().evidence().coordinateFrame();
  var frame=LocalMetricFrame.certifiedEquirectangular(broad.origin(),broad.toGeographic(new MetricPoint(-85,-55)),broad.toGeographic(new MetricPoint(85,55)));
  var parent=nearBoundaryHaloParent(frame);var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,parent.parentStage(),parent.parentChoice(),TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE);
  var plan=RefinementSourcePlanner.plan(ticket,RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.DIRECT_VALUE,false),parent);
  var calls=new java.util.concurrent.atomic.AtomicInteger();var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((r,c)->{calls.incrementAndGet();throw new AssertionError("uncertified image must not fetch");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){
    var failure=assertThrows(ParentTraceReference.UnavailableException.class,()->new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner));
    assertEquals(ParentTraceReference.Unavailable.EVIDENCE_COVERAGE,failure.reason());assertEquals(0,calls.get());assertEquals(0,ledger.currentBytes());
   }
  }
 }
 @Test void actualManagedRasterExtentRefusesMismatchedGridWithoutNewReservations() throws Exception {
  var parent=nearBoundaryHaloParent();var plan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(parent),RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.DIRECT_VALUE,false),parent);
  var calls=new java.util.concurrent.atomic.AtomicInteger();var bytes=png();var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((r,c)->{calls.incrementAndGet();return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,"");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){
    var acquired=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner);var actual=acquired.value();
    var type=RefinementAcquisition.Capture.class;var fields=new HashMap<String,Object>();for(var name:List.of("sources","owner","grid","geographic")){var f=type.getDeclaredField(name);f.setAccessible(true);fields.put(name,f.get(actual));}
    // Same certified original frame, real coordinator-owned raster and receipts; only the requested image footprint disagrees.
    var badGrid=new MetricRasterGrid(parent.root().evidence().coordinateFrame(),new MetricPoint(-1000,100),1,0,0,-1,10,201,21);
    var constructor=type.getDeclaredConstructor(RefinementSourcePlanner.Plan.class,ManagedModernPreviewSource.RefinementSources.class,AttemptMemoryLedger.Owner.class,TileFetchCoordinator.class,MetricRasterGrid.class,List.class);constructor.setAccessible(true);
    var mismatched=(RefinementAcquisition.Capture)constructor.newInstance(plan,fields.get("sources"),fields.get("owner"),coordinator,badGrid,fields.get("geographic"));
    long memory=ledger.currentBytes();int attempts=calls.get();var receipt=actual.workReceipt();
    var failure=assertThrows(ParentTraceReference.UnavailableException.class,()->mismatched.evidence("blue",CancellationProbe.NONE));
    assertEquals(ParentTraceReference.Unavailable.EVIDENCE_COVERAGE,failure.reason());assertEquals(memory,ledger.currentBytes());assertEquals(attempts,calls.get());assertEquals(receipt,actual.workReceipt());
   }assertEquals(0,ledger.currentBytes());
  }
 }
 private static ParentTraceReference nearBoundaryHaloParent(){
  return nearBoundaryHaloParent(LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50.00079799385172,14.0020751953125),
    new GeographicPoint(49.980797993851716,13.981238311813435),new GeographicPoint(50.02079799385172,14.022912078811565)));
 }
 private static ParentTraceReference nearBoundaryHaloParent(LocalMetricFrame frame){
  var base=RefinementSourcePlannerTest.parent(false,IntensitySamplingMode.DIRECT_VALUE).root();
  int width=161,height=101;var scalar=new double[width*height];var valid=new boolean[scalar.length];Arrays.fill(valid,true);
  for(int y=0;y<height;y++)for(int x=0;x<width;x++)scalar[y*width+x]=Math.exp(-.5*Math.pow((y-50)/2.0,2));
  var field=new ScalarEvidenceField(width,height,scalar,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
    EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
  var edit=MetricRegion.rectangle(-68,-8,68,8);
  var evidence=new EvidenceSnapshot("halo-root-evidence",frame,RasterMetricTransform.metricGrid(new MetricPoint(-80,-50),1,0,0,1),
    EvidenceResolution.nativeSource(2,1).resampledTo(1),edit,MetricRegion.rectangle(-80.5,-50.5,80.5,50.5),Map.of("native",field),base.sourceIdentity());
  var way=PrimitiveKey.existing(PrimitiveKey.Type.WAY,10);var keys=new ArrayList<PrimitiveKey>();var primitives=new LinkedHashMap<PrimitiveKey,DetachedPrimitive>();
  var positions=List.of(new MetricPoint(-60,0),new MetricPoint(0,0),new MetricPoint(60,0));
  for(int i=0;i<3;i++){var key=PrimitiveKey.existing(PrimitiveKey.Type.NODE,i+1);keys.add(key);primitives.put(key,new DetachedNode(key,frame.toGeographic(positions.get(i)),Map.of(),false,false));}
  primitives.put(way,new DetachedWay(way,keys,Map.of("highway","path"),false,false));var range=new OccurrenceRange(0,2);
  var closure=new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,"public-halo",primitives.keySet(),Set.of(way,keys.get(1)),Set.of(),
    Set.of(keys.get(0),keys.get(2)),Set.of(keys.get(1)),Map.of(way,List.of(range)),List.of(),MetricRegion.rectangle(-75,-20,75,20),edit,true,true,true,true);
  var network=new NetworkSnapshot("halo-root-network",SnapshotRole.CAPTURED_BEFORE,"synthetic",1,closure,primitives,V022SnapshotFixtures.closedWorldReferrerWatches(primitives));
  var chainage=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory().profileChainage(positions,3);
  var request=new TraceRequest(way,range,TrackerMode.LEGACY_V02,AlignmentMode.PRECISE_SHAPE,RecoveryPermissions.disabled(8),TraceBudgets.defaults(),
    evidence.snapshotId(),evidence.canonicalHash(),network.snapshotId(),network.canonicalHash(),"settings","parameters","legacy-root",3,chainage,evidence.resolution());
  var root=new RefinementRootAuthority(network,request,evidence,base.effectiveConfig(),"EPSG:3857",base.sourceIdentity());
  var projection=org.openstreetmap.josm.data.projection.Projections.getProjectionByCode(root.projectionCode());
  var projected=positions.stream().map(frame::toGeographic).map(p->projection.latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(p.latitudeDegrees(),p.longitudeDegrees()))).toList();
  var nativeCandidate=new CenterlineCandidate("halo-native",1,List.of(),List.of(),projected,projected,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
  var result=new AlignmentResult(RefinementParentFixtures.selection(root),null,List.of(nativeCandidate),projected,projected,List.of(),null,null,
    List.of(new DetectorAttempt("blue","blue",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(nativeCandidate.id()),"","")),List.of(nativeCandidate));
  var candidate=new Candidate(new CandidateId(nativeCandidate.id()),result,List.of(new ParentArc(0,0,0),new ParentArc(1,1,0),new ParentArc(2,1,1)),"halo-parent");
  return new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),candidate);
 }
 static byte[] png() throws Exception {var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<512;y++)for(int x=0;x<512;x++)image.setRGB(x,y,0xff000000|((x+y)%200+20)<<16);var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
 @Test void realCoordinatorKeepsIndependentSiblingsAfterPaletteFailure() throws Exception {
  var order=new ArrayList<String>();var bytes=png();var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((request,credentials)->{var color=request.address().color();if(!order.contains(color))order.add(color);return color.equals("blue")?new TransportResponse(TileFetchStatus.NO_TILE,404,"",null,null,Duration.ZERO,""):new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,"");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var parent=withSelectedSource(RefinementSourcePlannerTest.parent(true),"purple","all-colors-combined",true);var plan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(parent),RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.COLOR_MAPPING,true),parent);var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){
    var result=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),()->false,owner);
    assertEquals(List.of("purple","hot","blue","bluered","gray"),order);assertEquals(Set.of("purple","hot","bluered","gray"),result.value().palettes());assertEquals(ManagedModernPreviewSource.RefinementStatus.UNAVAILABLE,result.value().sourceOutcomes().get("blue"));assertTrue(result.value().sourceReceipts().containsKey("blue"));assertFalse(result.value().completeAggregate());assertTrue(result.value().isCurrent(coordinator));assertTrue(ledger.currentBytes()>0);
    assertEquals("all-colors-combined",plan.requests().get(0).mapping());
    coordinator.updateActiveGeneration(new ManagedTileGeneration(10));assertFalse(result.value().isCurrent(coordinator));assertThrows(IllegalStateException.class,()->result.value().aggregateEvidence(()->false));
   }assertEquals(0,ledger.currentBytes());
  }
 }
 @Test void sharedPerTileBudgetStopsBeforeSecondPaletteRatherThanResettingAllowance() throws Exception {
  var bytes=png();var addresses=new ArrayList<ManagedTileAddress>();var decoder=new TileDecoderClassifier();try(var coordinator=new TileFetchCoordinator((r,c)->{addresses.add(r.address());return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,"");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   var generation=new ManagedTileGeneration(9);coordinator.updateActiveGeneration(generation);
   var request=new ManagedModernPreviewSource.Request(List.of(new GeographicPoint(50.001,14.001),new GeographicPoint(50.001,14.0011)),"all","purple",15,generation,8,3,"public-scene",List.of("purple","hot","blue","bluered","gray"));
   var budget=new ManagedModernPreviewSource.RefinementSourceBudget(1,32L*1024*1024,java.time.Instant.now().plusSeconds(30));var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){
    var result=new ManagedModernPreviewSource(coordinator).acquireRefinementSources(request,Set.of(15),false,CredentialSnapshot.fromConfig(null),()->false,owner,budget);
    assertEquals(1,result.value().workReceipt().logicalTileRequests());assertEquals(1,addresses.size());assertEquals("purple",addresses.get(0).color());assertFalse(result.value().completeAggregate());assertEquals(1,budget.tileRequests(),"one operation envelope must count individual tile fetches");
    assertEquals(ManagedModernPreviewSource.RefinementStatus.UNATTEMPTED,result.value().sourceOutcomes().get("hot"));
   }
  }
 }
 @Test void visibleFreshParentEvidenceUsesActualEpochAndRefusesHaloOutsideCapture() throws Exception {
  var p=withSelectedSource(RefinementSourcePlannerTest.parent(),"blue","blue",false);var plan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(p),RefinementSourcePlannerTest.settings(false,IntensitySamplingMode.COLOR_MAPPING,false),p);
  var projection=org.openstreetmap.josm.data.projection.Projections.getProjectionByCode("EPSG:3857");
  var geo=p.root().evidence().coordinateFrame().origin();var center=projection.latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(geo.latitudeDegrees(),geo.longitudeDegrees()));
  var epoch=new org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch();int size=600;var pixels=new int[size*size];Arrays.fill(pixels,0xffff4000);
  var raster=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.VisibleRaster(size,size,pixels,center.east()-100,center.north()-200,center.east()+500,center.north()+400,6,3.86,OptionalDouble.empty(),p.root().sourceIdentity(),"EPSG:3857",epoch.captureStable());
  var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){
   var hashWork=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,System.nanoTime()+300_000_000_000L,CancellationProbe.NONE,owner);
   var hashes=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.OriginalInputHashes(hashWork);
   var result=RefinementAcquisition.captureVisible(plan,raster,"blue",()->false,owner,hashWork,hashes);assertEquals(p.root().evidence().coordinateFrame(),result.value().evidence().coordinateFrame());assertTrue(result.value().evidence().decisionRegion().contains(p.points().get(2)));assertEquals(EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER,result.value().evidence().fields().get("native").lineage().acquisitionKind());
   var descriptor=result.value().retentionDescriptor(plan.requests().get(0),result.value().evidence());
   var expected=java.security.MessageDigest.getInstance("SHA-256");for(int pixel:pixels){expected.update((byte)(pixel>>>24));expected.update((byte)(pixel>>>16));expected.update((byte)(pixel>>>8));expected.update((byte)pixel);}
   assertEquals(java.util.HexFormat.of().formatHex(expected.digest()),descriptor.inputByteHash());
   var alternatePlan=selectedPlan(plan,"blue","hot-corridor",false);long visits=hashWork.visits();var other=RefinementAcquisition.captureVisible(alternatePlan,raster,"hot-corridor",()->false,owner,hashWork,hashes);
   assertEquals(1,hashes.singleImagesHashed());assertTrue(hashWork.visits()-visits<=2);assertNotEquals(other.value().evidence().canonicalHash(),result.value().evidence().canonicalHash());
   var publication=owner.child("visible-publication");descriptor.retainInto(publication);result.owner().close();descriptor.requireCurrent();
   assertSame(raster.sourceReceipt(),descriptor.visibleReceipt().orElseThrow());assertTrue(descriptor.generation().isEmpty());
   assertEquals(600,descriptor.referenceWidth());assertEquals(600,descriptor.referenceHeight());
   epoch.sourceChanged();assertThrows(IllegalStateException.class,()->result.value().evidence());assertThrows(IllegalStateException.class,()->RefinementAcquisition.captureVisible(plan,raster,"blue",()->false,owner));
   assertThrows(IllegalStateException.class,descriptor::requireCurrent);
  }assertEquals(0,ledger.currentBytes());
 }
 @Test void cacheHitsConsumeLogicalTileBudgetButNotNewTransportAttempts() throws Exception {
  var count=new java.util.concurrent.atomic.AtomicInteger();var bytes=png();var decoder=new TileDecoderClassifier();try(var coordinator=new TileFetchCoordinator((r,c)->{count.incrementAndGet();return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,"");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   var generation=new ManagedTileGeneration(9);coordinator.updateActiveGeneration(generation);var source=new ManagedModernPreviewSource(coordinator);
   var request=new ManagedModernPreviewSource.Request(List.of(new GeographicPoint(50.001,14.001),new GeographicPoint(50.001,14.0011)),"all","purple",15,generation,8,3,"public-cache");
   var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){
    source.acquireRefinementSources(request,Set.of(15),false,CredentialSnapshot.fromConfig(null),()->false,owner);
    var budget=new ManagedModernPreviewSource.RefinementSourceBudget(1,coordinator.maximumTransportBodyAllowancePerTile(),java.time.Instant.now().plusSeconds(30));
    var result=source.acquireRefinementSources(request,Set.of(15),false,CredentialSnapshot.fromConfig(null),()->false,owner,budget);
    assertEquals(1,count.get());assertEquals(1,budget.tileRequests());assertEquals(1,budget.cacheHits());assertEquals(0,budget.actualTransportAttempts());assertEquals(0,result.value().workReceipt().observedNetworkBodyBytes().orElseThrow());assertTrue(budget.observedValidatedBytes()>0);assertEquals(coordinator.maximumTransportBodyAllowancePerTile(),budget.reservedBodyAllowanceBytes());assertEquals(Set.of("purple"),result.value().palettes());
   }
  }
 }
 @Test void changedGenerationAndCancellationDuringRealCoordinatorTransportPublishNoSource() throws Exception {
  for(boolean cancel:List.of(false,true)){
   var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var bytes=png();var decoder=new TileDecoderClassifier();var cancelled=new EventCancellation();var seenAddress=new java.util.concurrent.atomic.AtomicReference<ManagedTileAddress>();var cache=new ManagedTileCache(temp.resolve(Boolean.toString(cancel)),decoder);
   try(var coordinator=new TileFetchCoordinator((r,c)->{seenAddress.set(r.address());entered.countDown();try {assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,"");},cache,decoder,TileReliabilityPolicy.defaults())){
    coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var p=withSelectedSource(RefinementSourcePlannerTest.parent(),"blue","blue",false);var plan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(p),RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.COLOR_MAPPING,false),p);var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();var failed=new java.util.concurrent.atomic.AtomicReference<Throwable>();var published=new java.util.concurrent.atomic.AtomicReference<Object>();
    var worker=new Thread(()->{try{published.set(new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),cancelled,owner));}catch(Throwable failure){failed.set(failure);}});worker.start();assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));if(cancel)cancelled.cancel();else coordinator.updateActiveGeneration(new ManagedTileGeneration(10));release.countDown();worker.join(4000);assertFalse(worker.isAlive());assertNull(published.get());assertNotNull(failed.get());if(cancel)assertInstanceOf(java.util.concurrent.CancellationException.class,failed.get());else assertInstanceOf(IllegalStateException.class,failed.get());assertEquals(0,ledger.currentBytes());owner.close();long settle=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(coordinator.stats().inFlight()>0&&System.nanoTime()<settle)Thread.sleep(5);assertEquals(0,coordinator.stats().inFlight());assertFalse(java.nio.file.Files.exists(cache.path(new ManagedTileGeneration(9),seenAddress.get())));
   }
  }
 }
 @Test void visibleOffRasterHaloRefusesBeforeOwnedPixelsAreAllocated() throws Exception {
  var p=withSelectedSource(RefinementSourcePlannerTest.parent(),"blue","blue",false);var plan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(p),RefinementSourcePlannerTest.settings(false,IntensitySamplingMode.COLOR_MAPPING,false),p);var projection=org.openstreetmap.josm.data.projection.Projections.getProjectionByCode("EPSG:3857");var geo=p.root().evidence().coordinateFrame().origin();var center=projection.latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(geo.latitudeDegrees(),geo.longitudeDegrees()));var epoch=new org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch();
  var raster=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.VisibleRaster(100,100,new int[10000],center.east(),center.north(),center.east()+100,center.north()+100,6,3.86,OptionalDouble.empty(),p.root().sourceIdentity(),"EPSG:3857",epoch.captureStable());var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){var failure=assertThrows(IllegalStateException.class,()->RefinementAcquisition.captureVisible(plan,raster,"blue",()->false,owner));assertTrue(failure.getMessage().contains("halo"));assertTrue(ledger.peakBytes()<1024*1024,"only bounded frame planning precedes halo refusal");}
 }
 @Test void completeAggregateRequiresAllFiveCoordinatorPalettesAtBothZooms() throws Exception {
  var bytes=png();var decoder=new TileDecoderClassifier();try(var coordinator=new TileFetchCoordinator((r,c)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,""),new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   var generation=new ManagedTileGeneration(9);coordinator.updateActiveGeneration(generation);var request=new ManagedModernPreviewSource.Request(List.of(new GeographicPoint(50.001,14.001),new GeographicPoint(50.001,14.0011)),"all","purple",15,generation,8,3,"public-aggregate",List.of("purple","hot","blue","bluered","gray"));var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){
    var result=new ManagedModernPreviewSource(coordinator).acquireRefinementSources(request,Set.of(15,14),true,CredentialSnapshot.fromConfig(null),()->false,owner);assertTrue(result.value().completeAggregate());assertEquals(5,result.value().sourceReceipts().size());assertTrue(result.value().sourceReceipts().values().stream().allMatch(r->r.size()==2&&r.values().stream().allMatch(ManagedModernPreviewSource.ZoomReceipt::complete)));assertEquals(10,result.value().workReceipt().logicalTileRequests());assertTrue(Arrays.stream(result.value().aggregateScalars(()->false)).allMatch(Double::isFinite));
   }
  }
 }
 @Test void managedDirectEvidenceUsesActualModeAndPaletteForEveryRaster() throws Exception {
  var bytes=png();var decoder=new TileDecoderClassifier();try(var coordinator=new TileFetchCoordinator((r,c)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,""),new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));for(var mode:List.of(IntensitySamplingMode.DIRECT_ALPHA,IntensitySamplingMode.DIRECT_LUMINANCE,IntensitySamplingMode.DIRECT_VALUE)){
    var p=RefinementSourcePlannerTest.parent(false,mode);var plan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(p),RefinementSourcePlannerTest.settings(true,mode,false),p);var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){
     var result=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),()->false,owner);assertEquals(Set.of("purple"),result.value().palettes());{
      var palette="purple";var field=result.value().evidence(palette,()->false).fields().get("native");assertEquals(EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,field.lineage().derivationKind());assertEquals(palette+"/"+mode.detectorName(),field.lineage().sourcePalette());if(mode==IntensitySamplingMode.DIRECT_ALPHA)assertTrue(Arrays.stream(field.copiedValues()).filter(Double::isFinite).allMatch(v->v==1));
     }
    }assertEquals(0,ledger.currentBytes());
   }
  }
 }
 @Test void sharedBodyAllowanceAndExpiredDeadlineLeaveEverySourceUnattempted() throws Exception {
  var count=new java.util.concurrent.atomic.AtomicInteger();var decoder=new TileDecoderClassifier();try(var coordinator=new TileFetchCoordinator((r,c)->{count.incrementAndGet();return new TransportResponse(TileFetchStatus.NO_TILE,404,"",null,null,Duration.ZERO,"");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   var generation=new ManagedTileGeneration(9);coordinator.updateActiveGeneration(generation);var request=new ManagedModernPreviewSource.Request(List.of(new GeographicPoint(50.001,14.001),new GeographicPoint(50.001,14.0011)),"all","purple",15,generation,8,3,"public-bounds",List.of("purple","hot","blue","bluered","gray"));for(boolean expired:List.of(false,true)){
    var budget=new ManagedModernPreviewSource.RefinementSourceBudget(5,expired?100L*1024*1024:coordinator.maximumTransportBodyAllowancePerTile()-1,expired?java.time.Instant.EPOCH:java.time.Instant.now().plusSeconds(30));var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){
     var result=new ManagedModernPreviewSource(coordinator).acquireRefinementSources(request,Set.of(15),false,CredentialSnapshot.fromConfig(null),()->false,owner,budget);assertTrue(result.value().sourceOutcomes().values().stream().allMatch(v->v==ManagedModernPreviewSource.RefinementStatus.UNATTEMPTED));assertTrue(result.value().workReceipt().budgetStopped());assertEquals(0,result.value().workReceipt().logicalTileRequests());assertEquals(0,count.get());assertTrue(result.value().palettes().isEmpty());assertFalse(result.value().completeAggregate());
    }
   }
  }
 }
 static int markedRed(int worldX,int worldY){int tileX=Math.floorDiv(worldX,512),tileY=Math.floorDiv(worldY,512);return 16+Math.floorMod(worldX,128)+Math.floorMod(worldY,64)+4*Math.floorMod(tileX+3*tileY,8);}
 static double expectedMarkedScalar(GeographicPoint geo,int zoom){double width=512.0*(1<<zoom),lat=Math.toRadians(geo.latitudeDegrees());double x=(geo.longitudeDegrees()+180)/360*width-.5,y=(1-Math.log(Math.tan(lat)+1/Math.cos(lat))/Math.PI)*.5*width-.5;int ix=(int)Math.floor(x),iy=(int)Math.floor(y);double fx=x-ix,fy=y-iy;return ((markedRed(ix,iy)*(1-fx)+markedRed(ix+1,iy)*fx)*(1-fy)+(markedRed(ix,iy+1)*(1-fx)+markedRed(ix+1,iy+1)*fx)*fy)/255.0;}
 @Test void markedWorldPixelsMatchFreshParentEvidenceFarFromCaptureCenter() throws Exception {
  var patterns=new HashMap<Integer,byte[]>();for(int code=0;code<8;code++){var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<512;y++)for(int x=0;x<512;x++)image.setRGB(x,y,0xff000000|((16+x%128+y%64+4*code)<<16)|(((x/8+3*(y/8))%8)<<8));var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);patterns.put(code,out.toByteArray());}
  var decoder=new TileDecoderClassifier();try(var coordinator=new TileFetchCoordinator((r,c)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",patterns.get(Math.floorMod(r.address().x()+3*r.address().y(),8)),null,Duration.ZERO,""),new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var p=RefinementSourcePlannerTest.parent(false,IntensitySamplingMode.DIRECT_VALUE);var plan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(p),RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.DIRECT_VALUE,false),p);var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){
    var capture=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),()->false,owner);for(String palette:List.of("purple")){var evidence=capture.value().evidence(palette,()->false);var field=evidence.fields().get("native");var probes=List.of(new int[]{2,2},new int[]{field.width()/3,field.height()/3},new int[]{field.width()-3,field.height()-3},new int[]{3*field.width()/4,field.height()/2});for(var xy:probes){var geo=evidence.coordinateFrame().toGeographic(evidence.transform().pixelCenterToMetric(xy[0],xy[1]));assertEquals(expectedMarkedScalar(geo,15),field.sample(xy[0],xy[1]).orElseThrow(),1e-9,"global512px center oracle "+palette+" at "+Arrays.toString(xy));}}
   }assertEquals(0,ledger.currentBytes());
  }
 }
 @Test void sourceAuthAbortKeepsFixedFailureAndUnattemptedReceipt() throws Exception {
  var calls=new java.util.concurrent.atomic.AtomicInteger();var decoder=new TileDecoderClassifier();try(var coordinator=new TileFetchCoordinator((r,c)->{calls.incrementAndGet();return new TransportResponse(TileFetchStatus.AUTH_FAILURE,403,"",null,null,Duration.ZERO,"http-auth");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   var generation=new ManagedTileGeneration(9);coordinator.updateActiveGeneration(generation);var request=new ManagedModernPreviewSource.Request(List.of(new GeographicPoint(50.001,14.001),new GeographicPoint(50.001,14.0011)),"all","purple",15,generation,8,3,"public-auth",List.of("purple","hot","blue","bluered","gray"));var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()){
    var failure=assertThrows(ManagedModernPreviewSource.RefinementFailed.class,()->new ManagedModernPreviewSource(coordinator).acquireRefinementSources(request,Set.of(15,14),false,CredentialSnapshot.fromConfig(null),()->false,owner));assertEquals(ManagedModernPreviewSource.RefinementAbortReason.AUTH_FAILURE,failure.receipt().reason());assertEquals(ManagedModernPreviewSource.RefinementStatus.UNATTEMPTED,failure.receipt().outcomes().get("hot"));assertEquals(1,calls.get());assertEquals(1,failure.receipt().work().logicalTileRequests());assertTrue(failure.receipt().work().observedNetworkBodyBytes().isEmpty(),"failed response body size is unavailable");assertEquals(0,ledger.currentBytes());
   }
  }
 }
 @Test void actualSmallABPipelinesPreserveOutputsWithTheLegacyValidationAllowanceControl() throws Exception {
  for(var mode:AlignmentMode.values())for(var engine:List.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC)) {
   var parent=memoryPipelineParent(mode);var root=parent.root();var config=root.effectiveConfig().heatmap();
   var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,parent.parentStage(),parent.parentChoice(),engine,mode);
   var capturedSettings=new java.util.concurrent.atomic.AtomicReference<RefinementSourcePlanner.Settings>();
   javax.swing.SwingUtilities.invokeAndWait(()->capturedSettings.set(RefinementSourcePlanner.Settings.capture(true,config,List.of())));
   var plan=RefinementSourcePlanner.plan(ticket,capturedSettings.get(),parent);
   var images=new HashMap<ManagedTileAddress,byte[]>();var decoder=new TileDecoderClassifier();var signatures=new ArrayList<Map<String,List<Object>>>();
   try(var coordinator=new TileFetchCoordinator((r,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",
       images.computeIfAbsent(r.address(),address->memoryRidge(address,root.evidence().coordinateFrame())),null,Duration.ZERO,""),
       new ManagedTileCache(temp.resolve(mode.name()+engine.name()),decoder),decoder,TileReliabilityPolicy.defaults())) {
    coordinator.updateActiveGeneration(new ManagedTileGeneration(9));long releasedPeak=0;
    for(boolean legacyLifetime:List.of(true,false)) {
     var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()) {
      var acquired=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),CancellationProbe.NONE,owner);
      long released=ledger.peakBytes()-ledger.currentBytes();assertTrue(released>0,"two-zoom acquisition must release completed workspace");
      if(legacyLifetime){releasedPeak=released;owner.reserve(released).adopt(new Object());}else assertEquals(releasedPeak,released);
      var entries=new java.util.concurrent.atomic.AtomicInteger();
      var result=new RefinementPipeline().run(ticket,parent,plan,new RefinementPipeline.Adapters(RefinementPipeline.managedSources(acquired.value(),coordinator),
        RefinementPipeline.productionRunner(t->entries.incrementAndGet()),owner),RefinementPipeline.OperationBudget.production(root.request().budgets()),CancellationProbe.NONE);
      System.out.println("memory-pipeline mode="+mode.name()+" engine="+engine.name()+" legacyLifetime="+legacyLifetime+" outcome="+result.outcome()
        +" assessed="+result.candidates().size()+" rejected="+result.rejected().size()+" actualWork="+result.actualWork()+" currentBytes="+ledger.currentBytes()+" peakBytes="+ledger.peakBytes());
      for(var attempt:result.attempts())System.out.println("memory-pipeline-attempt palette="+attempt.source().palette()+" mapping="+attempt.source().mapping()
        +" kind="+attempt.kind()+" reason="+attempt.reason()+" offered="+attempt.issuedBudgets()+" calls="+attempt.nativeCallAvailability()
        +" work="+attempt.run().flatMap(RefinementEngineAdapter.Run::actualWorkAvailability)
        +" nativeStatus="+attempt.run().flatMap(RefinementEngineAdapter.Run::nativeRun).map(run->run.result().status())
        +" nativeResource="+attempt.run().flatMap(RefinementEngineAdapter.Run::nativeRun).filter(run->run.result().status()==TraceHypothesisSet.Status.RESOURCE_LIMIT)
          .map(run->run.result().explanation()).orElse("UNAVAILABLE"));
      assertTrue(entries.get()>0,mode+"/"+engine+" requires actual native entry");
      assertEquals(List.of("purple"),result.sourceOutcomes().stream().map(o->o.source().mapping()).toList());
      assertTrue(result.sourceOutcomes().stream().allMatch(o->!o.attempts().isEmpty()),"requested sources retain factual attempts or explicit unattempted rows");
      assertFalse(result.candidates().isEmpty(),mode+"/"+engine+" requires actual assessed native geometry: "+result.outcome());
      assertTrue(result.actualWork().orElseThrow().transitions()>0);assertTrue(ledger.peakBytes()<=AttemptMemoryLedger.MAX_BYTES);
      var signature=new LinkedHashMap<String,List<Object>>();
      for(var attempt:result.attempts())for(var run:attempt.run().stream().toList()) {
       var nativeRun=run.nativeRun();
       if(nativeRun.isEmpty()||nativeRun.get().result().status()==TraceHypothesisSet.Status.RESOURCE_LIMIT)continue;
       var values=new ArrayList<Object>();values.add(run.request());values.add(run.outcome());values.add(run.actualWork());
       values.add(nativeRun.get().result().status());values.add(nativeRun.get().result().hypotheses());
       values.add(run.finalized().map(ModernTracePipeline.Result::routes));
       values.add(result.sourceOutcomes().stream().filter(o->o.source().equals(attempt.source())).findFirst().orElseThrow().assessments());
       signature.put(attempt.source().mapping()+"/"+attempt.kind()+"/"+attempt.range(),List.copyOf(values));
      }
      signatures.add(Map.copyOf(signature));
     }assertEquals(0,ledger.currentBytes());
    }
    assertFalse(signatures.get(0).isEmpty(),"control must contain actual completed native output");
    for(var shared:signatures.get(0).entrySet())assertEquals(shared.getValue(),signatures.get(1).get(shared.getKey()),
      "shared completed invocation must retain exact native/final output, parameters, physical geometry, quality and work: "+shared.getKey());
   }
  }
 }
 private static ParentTraceReference memoryPipelineParent(AlignmentMode mode) {
   var original=RefinementParentFixtures.authority(mode);var c=original.effectiveConfig().heatmap();
   var config=new ManagedHeatmapConfig("","","","","all","blue",c.manualLayerName(),c.layerRegex(),mode,TrackerMode.LEGACY_V02,
     c.verbose(),c.debug(),false,true,c.showAggregateIntensityLayer(),c.candidateRatingEnabled(),c.parallelWayAwareness(),c.allowUndownloadedAlignment(),
     c.adjustJunctionNodes(),c.simplifyEnabled(),c.crossSectionHalfWidthPx(),c.crossSectionStepPx(),c.simplifyTolerancePx(),InferenceMode.RAW_HIGH_RESOLUTION,
     15,14,8,3,IntensitySamplingMode.COLOR_MAPPING,9);
   var root=new RefinementRootAuthority(original.network(),original.request(),original.evidence(),new AlignmentConfig(config,original.effectiveConfig().cleanup()),original.projectionCode(),original.sourceIdentity());
   return new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"purple"));
 }
 private static byte[] memoryRidge(ManagedTileAddress address,LocalMetricFrame frame) {
  try {
   var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);
   int center=switch(address.color()){case "blue"->0xff4ca9ff;case "bluered"->0xffff24b2;case "purple"->0xffbd9cff;case "gray"->0xffe84bea;default->0xfffdfdfd;};
   for(int y=0;y<512;y++) {
    double worldY=(address.y()*512.0+y+.5)/(512.0*(1<<address.zoom()));double latitude=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*worldY))));
    double ground=frame.toMetric(new GeographicPoint(latitude,frame.origin().longitudeDegrees())).yMeters();double strength=Math.exp(-.5*Math.pow((ground-50)/2,2));
    for(int x=0;x<512;x++){int background=3+(x*7+y*3)%29;int red=(int)Math.round(background+strength*(((center>>>16)&255)-background));
     int green=(int)Math.round(background+strength*(((center>>>8)&255)-background));int blue=(int)Math.round(background+strength*((center&255)-background));image.setRGB(x,y,0xff000000|red<<16|green<<8|blue);}
   }
   var bytes=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",bytes))throw new IllegalStateException("PNG writer unavailable");return bytes.toByteArray();
  }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
 }
 @Test void capRefusalPrecedesTransportAndReleasesOwnership() throws Exception {
  var count=new java.util.concurrent.atomic.AtomicInteger();var decoder=new TileDecoderClassifier();try(var coordinator=new TileFetchCoordinator((r,c)->{count.incrementAndGet();return new TransportResponse(TileFetchStatus.NO_TILE,404,"",null,null,Duration.ZERO,"");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())){
   coordinator.updateActiveGeneration(new ManagedTileGeneration(9));var p=RefinementSourcePlannerTest.parent();var plan=RefinementSourcePlanner.plan(RefinementSourcePlannerTest.ticket(p),RefinementSourcePlannerTest.settings(true,IntensitySamplingMode.COLOR_MAPPING,false),p);var ledger=new AttemptMemoryLedger(1024);try(var owner=ledger.rootOwner()){assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,CredentialSnapshot.fromConfig(null),()->false,owner));assertEquals(0,count.get());}assertEquals(0,ledger.currentBytes());
  }
 }
}

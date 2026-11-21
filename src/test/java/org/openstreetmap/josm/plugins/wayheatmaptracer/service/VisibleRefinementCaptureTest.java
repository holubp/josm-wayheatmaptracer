package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.VisibleRefinementCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.VisibleRefinementCapture.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;

public class VisibleRefinementCaptureTest {
 static final class Queue implements Executor {
  final ArrayDeque<Runnable> tasks=new ArrayDeque<>();boolean reject;public void execute(Runnable task){if(reject)throw new RejectedExecutionException();tasks.add(task);}
  void next(){tasks.remove().run();}void drain(){while(!tasks.isEmpty())next();}
 }
 static class Renderer implements VisibleRefinementCapture.Renderer {
  Viewport user=new Viewport(100,200,3,800,600);int paints,restores;boolean failRestore,heterogeneous;Runnable afterPaint=()->{};
  public void requireCurrent(Request r){r.evidencePlan().requireCurrent();}
  public Viewport snapshot(){return user;}
  public RenderedChunk render(Request r,Chunk c){paints++;user=new Viewport(0,0,1,c.viewWidth(),c.viewHeight());var image=new BufferedImage(c.viewWidth()*6,c.viewHeight()*6,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xff123456);afterPaint.run();return new RenderedChunk(image,c.bounds(),heterogeneous&&paints%2==0?14:15,OptionalDouble.of(heterogeneous&&paints%2==0?2:1),r.evidencePlan().originalFrame().groundMetersPerViewPixel());}
  public void restore(Viewport saved){restores++;user=saved;if(failRestore)throw new IllegalStateException("restore failed");}
 }
 static final class KnownLayer extends org.openstreetmap.josm.gui.layer.ImageryLayer {
  KnownLayer(){super(new org.openstreetmap.josm.data.imagery.ImageryInfo("Synthetic marked pixels"));}
  @Override protected javax.swing.Action getAdjustAction(){return null;}
  @Override protected List<OffsetMenuEntry> getOffsetMenuEntries(){return List.of();}
  @Override public String getToolTipText(){return "Synthetic pixels";}
  @Override public void visitBoundingBox(org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor visitor){ }
  @Override public javax.swing.Action[] getMenuEntries(){return new javax.swing.Action[0];}
  @Override public void paint(java.awt.Graphics2D graphics,org.openstreetmap.josm.gui.MapView view,org.openstreetmap.josm.data.Bounds ignored){graphics.setColor(java.awt.Color.BLACK);graphics.fillRect(0,0,view.getWidth(),view.getHeight());graphics.setColor(java.awt.Color.RED);graphics.fillRect(3,4,1,1);}
 }
 static void pump(Queue edt,Queue worker,CaptureOperation op){for(int n=0;n<1000&&!op.settled().toCompletableFuture().isDone();n++){worker.drain();edt.drain();}assertTrue(op.settled().toCompletableFuture().isDone());}
 public static org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.VisibleRaster raster(VisibleEvidencePlan p) {
  var b=p.roundedBounds();int w=p.viewWidth()*6,h=p.viewHeight()*6;
  return new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.VisibleRaster(w,h,new int[w*h],b.minimumEast(),b.minimumNorth(),b.maximumEast(),b.maximumNorth(),p.projectedScale(),p.original().groundMetersPerViewPixel(),p.original().nativePitchMeters(),p.sourcePlan().parent().root().sourceIdentity(),p.original().projectionCode(),p.original().sourceReceipt());
 }
 static Request request(VisibleEvidencePlan p,AttemptMemoryLedger.Owner owner) {
  var stage=VisibleEvidencePlanTest.publishedStage(p);
  owner.reserve(1024).adopt(stage);
  return new Request(p,stage,List.of());
 }
 @Test void checkedSixfoldAdmissionRefusesBeforePaint() {
  assertThrows(ArithmeticException.class,()->VisibleRefinementCapture.rasterBytes(Integer.MAX_VALUE,2));
  assertEquals(6L*6*10*20*4,VisibleRefinementCapture.rasterBytes(10,20));
 }
 @Test void recapturePhysicalSettlementAndViewportRestoration() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();var ledger=AttemptMemoryLedger.production();
  try(var owner=ledger.rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);
   var request=request(p,owner);var edt=new Queue();var worker=new Queue();var renderer=new Renderer();
   var op=new VisibleRefinementCapture(renderer,edt,worker,40,40).capture(request,work);
   worker.next();assertEquals(0,renderer.paints);assertFalse(op.settled().toCompletableFuture().isDone());
   var panned=new Viewport(500,600,7,900,700);renderer.user=panned;
   edt.next();assertEquals(panned,renderer.user);assertEquals(1,renderer.restores);
   op.cancel();assertFalse(op.settled().toCompletableFuture().isDone());worker.drain();
   assertTrue(op.settled().toCompletableFuture().isDone());assertTrue(op.result().toCompletableFuture().isCompletedExceptionally());
  }
  assertEquals(0,ledger.currentBytes());
 }
 @Test void originalEpochRemainsRequiredBeforeAndAfterRender() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);
   var edt=new Queue();var worker=new Queue();var renderer=new Renderer();
   var op=new VisibleRefinementCapture(renderer,edt,worker,5000,5000).capture(request(p,owner),work);
   worker.next();epoch.sourceChanged();edt.next();worker.drain();
   assertEquals(0,renderer.paints);assertTrue(op.result().toCompletableFuture().isCompletedExceptionally());assertTrue(op.settled().toCompletableFuture().isDone());
  }
 }
 @Test void outsideRootRasterRequestsExactParentFootprintOnceAndKnownPixelsSurvive() throws Exception {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();var ledger=AttemptMemoryLedger.production();
  try(var owner=ledger.rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);
   var edt=new Queue();var worker=new Queue();var renderer=new Renderer();var request=request(p,owner);
   var op=new VisibleRefinementCapture(renderer,edt,worker,40,40).capture(request,work);pump(edt,worker,op);
   var output=op.result().toCompletableFuture().join();var frame=output.value();assertSame(p,frame.evidencePlan());assertSame(s,p.sourcePlan());assertSame(p.projectedParent(),request.projectedParent());
   assertEquals((long)frame.width()*frame.height(),work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.COPY),"Port assembly is exactly one copy pass; no automatic frozen clone");
   assertEquals((long)frame.width()*frame.height(),work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.HASH));
   assertEquals((p.viewWidth()+39)/40*((p.viewHeight()+39)/40),renderer.paints);assertEquals(renderer.paints,renderer.restores);
   var copied=frame.copyRaster(work);var actualRaster=copied.value();int[] pixels=actualRaster.argb();var expected=new int[pixels.length];
   for(var c:frame.chunks()){expected[(c.chunk().viewY()*6)*frame.width()+c.chunk().viewX()*6]=0xff123456;assertEquals(15,c.sourceZoom());}
   assertArrayEquals(expected,pixels,"Only the independently located chunk-origin marks may be nonzero");
   var hash=java.security.MessageDigest.getInstance("SHA-256");for(int v:expected){hash.update((byte)(v>>>24));hash.update((byte)(v>>>16));hash.update((byte)(v>>>8));hash.update((byte)v);}
   assertEquals(HexFormat.of().formatHex(hash.digest()),frame.inputHash());
   pixels[0]=0;assertEquals(0xff123456,frame.argbAt(0,0,work),"A public copied raster cannot mutate the privately leased mosaic");
   assertEquals(p.original().sourceReceipt(),frame.sourceReceipt());assertEquals(VisibleEvidencePlan.Support.COVERED,p.support(actualRaster));copied.owner().close();output.owner().close();
  }assertEquals(0,ledger.currentBytes());
 }
 @Test void coveredRasterNeverInvokesRendererAndConsumerCancellationCannotSettleProducer() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();var ledger=AttemptMemoryLedger.production();
  try(var owner=ledger.rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var old=VisibleEvidencePlanTest.original(s,epoch,1);var first=VisibleEvidencePlan.create(s,old,work);var b=first.roundedBounds();
   var original=new LegacyVisibleSource(b.minimumEast(),b.minimumNorth(),b.maximumEast(),b.maximumNorth(),old.projectionUnitsPerViewPixel(),old.groundMetersPerViewPixel(),old.nativePitchMeters(),old.projectionCode(),old.sourceReceipt(),old.decisionRadiusMeters(),old.sampleStepMeters());
   var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.factual(original),work);var raster=raster(p);
   owner.reserve(4096L+4L*raster.width()*raster.height()).adopt(raster);
   var edt=new Queue();var worker=new Queue();var renderer=new Renderer();var op=new VisibleRefinementCapture(renderer,edt,worker,40,40).capture(request(p,owner),raster,work);
   assertTrue(op.result().toCompletableFuture().cancel(false));assertFalse(op.settled().toCompletableFuture().isDone());
   worker.drain();assertEquals(0,renderer.paints);assertTrue(edt.tasks.isEmpty());
   var output=op.result().toCompletableFuture().join();assertEquals(raster.width(),output.value().width());assertEquals(raster.height(),output.value().height());assertEquals((long)raster.width()*raster.height(),work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.COPY));assertTrue(output.value().chunks().isEmpty());assertTrue(op.settled().toCompletableFuture().isDone());output.owner().close();
  }assertEquals(0,ledger.currentBytes());
 }
 @Test void compatibleAndHeterogeneousChunksKeepFactualResolutionReceipts() {
  for(boolean heterogeneous:List.of(false,true)) {
   var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
   try(var owner=AttemptMemoryLedger.production().rootOwner()) {
    var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);
    var edt=new Queue();var worker=new Queue();var renderer=new Renderer();renderer.heterogeneous=heterogeneous;
    var op=new VisibleRefinementCapture(renderer,edt,worker,40,40).capture(request(p,owner),work);pump(edt,worker,op);
    var output=op.result().toCompletableFuture().join();var frame=output.value();assertTrue(frame.chunks().size()>1);
    assertEquals(!heterogeneous,frame.nativePitchMeters().isPresent());
    assertEquals(heterogeneous?Set.of(14,15):Set.of(15),frame.chunks().stream().map(ChunkResolution::sourceZoom).collect(java.util.stream.Collectors.toSet()));output.owner().close();
   }
  }
 }
 @Test void rasterMemoryAdmissionPrecedesPaintBelowGeometricCeiling() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();var ledger=new AttemptMemoryLedger(65536);
  try(var owner=ledger.rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);
   var edt=new Queue();var worker=new Queue();var renderer=new Renderer();var request=request(p,owner);long before=ledger.currentBytes();
   var op=new VisibleRefinementCapture(renderer,edt,worker,40,40).capture(request,work);worker.drain();
   assertTrue(op.result().toCompletableFuture().isCompletedExceptionally());assertTrue(op.settled().toCompletableFuture().isDone());assertEquals(0,renderer.paints);assertTrue(edt.tasks.isEmpty());assertEquals(before,ledger.currentBytes());
   assertTrue((long)p.viewWidth()*p.viewHeight()<3_300_000);
  }
 }
 @Test void cancellationBeforeFirstCallbackAndRestoreFailureSettleExactlyOnce() {
  for(boolean restoreFailure:List.of(false,true)) {
   var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();var ledger=AttemptMemoryLedger.production();
   try(var owner=ledger.rootOwner()) {
    var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);
    var edt=new Queue();var worker=new Queue();var renderer=new Renderer();renderer.failRestore=restoreFailure;
    var op=new VisibleRefinementCapture(renderer,edt,worker,40,40).capture(request(p,owner),work);worker.next();
    var saved=renderer.user;if(!restoreFailure)op.cancel();assertFalse(op.settled().toCompletableFuture().isDone());edt.next();assertEquals(saved,renderer.user);
    assertFalse(op.settled().toCompletableFuture().isDone());worker.drain();op.cancel();
    assertTrue(op.result().toCompletableFuture().isCompletedExceptionally());assertEquals(restoreFailure?1:0,renderer.paints);assertEquals(restoreFailure?1:0,renderer.restores);
   }assertEquals(0,ledger.currentBytes());
  }
 }
 @Test void epochInvalidatedDuringPaintRestoresAndRefusesOutput() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);
   var edt=new Queue();var worker=new Queue();var renderer=new Renderer();renderer.afterPaint=epoch::beginLoad;
   var op=new VisibleRefinementCapture(renderer,edt,worker,40,40).capture(request(p,owner),work);pump(edt,worker,op);
   assertEquals(1,renderer.paints);assertEquals(1,renderer.restores);
   var failure=assertThrows(CompletionException.class,()->op.result().toCompletableFuture().join());assertInstanceOf(VisibleEvidencePlan.StaleSource.class,failure.getCause());
  }
 }
 @Test void queuedRequestCannotBeMutatedAndExactHistoryPinsSurviveCancellation() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();var ledger=AttemptMemoryLedger.production();
  try(var owner=ledger.rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);long base=ledger.currentBytes();
   var history=owner.child("published-history");var original=request(p,history);var group=new Object();history.reserve(2048).adopt(group);
   var mutable=new ArrayList<Object>(List.of(group,group));var request=new Request(p,original.publishedStage(),mutable);mutable.clear();assertEquals(2,request.inputIdentities().size());
   assertThrows(UnsupportedOperationException.class,()->request.projectedParent().clear());assertEquals(p.requestedBounds(),request.requestedBounds());
   var edt=new Queue();var worker=new Queue();var renderer=new Renderer();var op=new VisibleRefinementCapture(renderer,edt,worker,40,40).capture(request,work);
   long pinned=ledger.currentBytes();history.close();assertEquals(pinned,ledger.currentBytes(),"Exact metadata and shared original group remain charged once");
   var acknowledgements=new java.util.concurrent.atomic.AtomicInteger();op.settled().thenRun(acknowledgements::incrementAndGet);
   op.cancel();op.cancel();worker.drain();assertTrue(edt.tasks.isEmpty());assertEquals(1,acknowledgements.get());assertEquals(base,ledger.currentBytes());
  }assertEquals(0,ledger.currentBytes());
 }
 @Test void roundedActualFrameKeepsVisiblePixelConvention() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),VisibleEvidencePlanTest.work(owner));var b=p.roundedBounds();
   double x=7.25,y=9.75,east=b.minimumEast()+x*p.projectedScale()/6,north=b.maximumNorth()-y*p.projectedScale()/6;
   var geographic=new GeographicPoint(Math.toDegrees(2*Math.atan(Math.exp(north/6378137))-Math.PI/2),Math.toDegrees(east/6378137));
   var transform=org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.visibleWebMercator(b.minimumEast(),b.maximumNorth(),p.projectedScale(),6);
   var actual=transform.toRasterCenter(geographic);assertEquals(x,actual.x(),2e-7);assertEquals(y,actual.y(),2e-7);
   assertNotEquals(x-.5,actual.x(),.01);assertEquals(p.viewWidth()*p.projectedScale(),b.maximumEast()-b.minimumEast(),1e-8);
   assertTrue(b.minimumEast()<=p.requestedBounds().minimumEast());assertTrue(b.maximumNorth()>=p.requestedBounds().maximumNorth());
  }
 }
 @Test void requestMustBindImmutableParentBeyondTheStageAndChoiceLabels() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),VisibleEvidencePlanTest.work(owner));var original=request(p,owner).publishedStage();var c=original.candidates().get(0);
   var wrong=new RefinementModels.Candidate(c.id(),c.payload(),c.correspondence(),"different-published-parent",c.nativeCandidateId(),c.sourcePalette());
   var stage=new RefinementModels.Stage(original.id(),original.parent(),original.parentChoice(),original.kind(),original.engine(),List.of(wrong),original.selected(),original.smoothingInput());
   assertThrows(IllegalArgumentException.class,()->new Request(p,stage,List.of()));
  }
 }
 @Test void simultaneousDistinctOriginalNativeExportBuffersCanRefuseBelowViewCeiling() {
  for(boolean retainOtherBuffers:List.of(false,true)) {
   var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();var ledger=new AttemptMemoryLedger(512*1024);
   try(var owner=ledger.rootOwner()) {
    var work=VisibleEvidencePlanTest.work(owner);var o=VisibleEvidencePlanTest.original(s,epoch,1);
    var original=new LegacyVisibleSource(o.minimumEast(),o.minimumNorth(),o.maximumEast(),o.maximumNorth(),10,6,o.nativePitchMeters(),o.projectionCode(),o.sourceReceipt(),o.decisionRadiusMeters(),o.sampleStepMeters());
    var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.factual(original),work);var request=request(p,owner);
    if(retainOtherBuffers)for(int n=0;n<4;n++){var buffer=new byte[81920];owner.reserve(AttemptMemoryLedger.arrayBytes(buffer.length,1)).adopt(buffer);}
    var edt=new Queue();var worker=new Queue();var renderer=new Renderer();var op=new VisibleRefinementCapture(renderer,edt,worker,5000,5000).capture(request,work);pump(edt,worker,op);
    assertEquals(retainOtherBuffers,op.result().toCompletableFuture().isCompletedExceptionally());
    assertEquals(!retainOtherBuffers,renderer.paints>0);assertTrue((long)p.viewWidth()*p.viewHeight()<3_300_000);
    if(!retainOtherBuffers)op.result().toCompletableFuture().join().owner().close();
   }assertEquals(0,ledger.currentBytes());
  }
 }
 @Test void chunkTransactionsCannotReenterTheSameRenderer() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);
   var edt=new Queue();var worker=new Queue();var renderer=new Renderer();var provider=new VisibleRefinementCapture(renderer,edt,worker,5000,5000);var request=request(p,owner);
   var first=provider.capture(request,work);var second=provider.capture(request,work);worker.drain();
   renderer.afterPaint=()->{renderer.afterPaint=()->{};edt.next();};edt.next();worker.drain();pump(edt,worker,first);
   assertEquals(1,renderer.paints);assertTrue(second.result().toCompletableFuture().isCompletedExceptionally());first.result().toCompletableFuture().join().owner().close();
  }
 }
 @Test void malformedChunkCannotReachAnyRenderer() {
  var bounds=new Bounds(0,0,10,10);
  assertThrows(IllegalArgumentException.class,()->new Chunk(-1,0,1,1,bounds));
  assertThrows(IllegalArgumentException.class,()->new Chunk(0,0,0,1,bounds));
 }
 @Test void originalFrameDisagreementRefusesBeforeDispatchAndActualFrameMustCoverPlan() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);
   var edt=new Queue();var worker=new Queue();var renderer=new Renderer();var provider=new VisibleRefinementCapture(renderer,edt,worker,40,40);
   assertThrows(UnsupportedOperationException.class,()->provider.capture(request(p,owner),raster(p),work));assertTrue(worker.tasks.isEmpty());assertTrue(edt.tasks.isEmpty());assertEquals(0,renderer.paints);
   var o=p.original();var tiny=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.VisibleRaster(6,6,new int[36],o.minimumEast(),o.minimumNorth(),o.minimumEast()+1,o.minimumNorth()+1,1,1,o.nativePitchMeters(),s.parent().root().sourceIdentity(),o.projectionCode(),o.sourceReceipt());
   assertEquals(VisibleEvidencePlan.Support.NEEDS_CAPTURE,p.support(tiny));
   assertEquals(0,work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.MAP));
  }
 }
 @Test void originalOutsideCoverageTriggersOneBoundedSameEpochRecapture() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();var ledger=AttemptMemoryLedger.production();
  try(var owner=ledger.rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var o=VisibleEvidencePlanTest.original(s,epoch,1);var p=VisibleEvidencePlan.create(s,o,work);
   int width=(int)Math.round((o.maximumEast()-o.minimumEast())/o.projectionUnitsPerViewPixel()*6),height=(int)Math.round((o.maximumNorth()-o.minimumNorth())/o.projectionUnitsPerViewPixel()*6);
   var original=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService.VisibleRaster(width,height,new int[width*height],o.minimumEast(),o.minimumNorth(),o.maximumEast(),o.maximumNorth(),o.projectionUnitsPerViewPixel(),o.groundMetersPerViewPixel(),o.nativePitchMeters(),s.parent().root().sourceIdentity(),o.projectionCode(),o.sourceReceipt());
   owner.reserve(4096L+4L*width*height).adopt(original);assertEquals(VisibleEvidencePlan.Support.NEEDS_CAPTURE,p.support(original));
   var edt=new Queue();var worker=new Queue();var renderer=new Renderer();var op=new VisibleRefinementCapture(renderer,edt,worker,5000,5000).capture(request(p,owner),original,work);pump(edt,worker,op);
   var output=op.result().toCompletableFuture().join();assertEquals(1,renderer.paints);assertSame(p,output.value().evidencePlan());assertEquals(o.sourceReceipt(),output.value().sourceReceipt());output.owner().close();
  }assertEquals(0,ledger.currentBytes());
 }
 @Test void everyChunkPreservesTheUserPanMadeSinceThePreviousCallback() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);
   var edt=new Queue();var worker=new Queue();var renderer=new Renderer();var op=new VisibleRefinementCapture(renderer,edt,worker,40,40).capture(request(p,owner),work);
   worker.drain();edt.next();worker.drain();var newPan=new Viewport(900,800,17,1100,900);renderer.user=newPan;edt.next();assertEquals(newPan,renderer.user);assertEquals(2,renderer.restores);
   op.cancel();worker.drain();assertTrue(op.settled().toCompletableFuture().isDone());assertTrue(op.result().toCompletableFuture().isCompletedExceptionally());
  }
 }
 @Test void returnedActualChunkFrameIsCheckedBeforeAnyScalarOrHashWork() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);
   var renderer=new Renderer(){@Override public RenderedChunk render(Request request,Chunk chunk){var result=super.render(request,chunk);var b=chunk.bounds();return new RenderedChunk(result.image(),new Bounds(b.minimumEast()+100,b.minimumNorth(),b.maximumEast()+100,b.maximumNorth()),result.sourceZoom(),result.nativePitchMeters(),result.groundMetersPerViewPixel());}};
   var edt=new Queue();var worker=new Queue();var op=new VisibleRefinementCapture(renderer,edt,worker,40,40).capture(request(p,owner),work);pump(edt,worker,op);
   assertTrue(op.result().toCompletableFuture().isCompletedExceptionally());assertEquals(1,renderer.restores);
   assertEquals(0,work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.HASH));assertEquals(0,work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.MAP));
  }
 }
 @Test void rejectedWorkerDeliverySettlesTheRestoredPaintAndPins() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();var ledger=AttemptMemoryLedger.production();
  try(var owner=ledger.rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);var request=request(p,owner);long baseline=ledger.currentBytes();
   var edt=new Queue();var worker=new Queue();var renderer=new Renderer();var op=new VisibleRefinementCapture(renderer,edt,worker,40,40).capture(request,work);
   worker.next();worker.reject=true;var user=renderer.user;edt.next();assertEquals(user,renderer.user);assertEquals(1,renderer.restores);
   assertTrue(op.result().toCompletableFuture().isCompletedExceptionally());assertTrue(op.settled().toCompletableFuture().isDone());assertEquals(baseline,ledger.currentBytes());
  }assertEquals(0,ledger.currentBytes());
 }
 @Test void realMapViewPanZoomAndSizeAreRestoredAtCallbackTime() throws Exception {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  var previousPreferences=org.openstreetmap.josm.spi.preferences.Config.getPref();var previousProjection=org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection();
  var pane=org.openstreetmap.josm.gui.MainApplication.class.getDeclaredField("contentPanePrivate");pane.setAccessible(true);var previousPane=pane.get(null);
  var projection=org.openstreetmap.josm.data.projection.ProjectionRegistry.class.getDeclaredField("proj");projection.setAccessible(true);
  var reference=new java.util.concurrent.atomic.AtomicReference<org.openstreetmap.josm.gui.MapView>();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   javax.swing.SwingUtilities.invokeAndWait(()->{
    try{pane.set(null,new javax.swing.JPanel());}catch(IllegalAccessException failure){throw new IllegalStateException(failure);}
    org.openstreetmap.josm.spi.preferences.Config.setPreferencesInstance(new org.openstreetmap.josm.spi.preferences.MemoryPreferences());
    org.openstreetmap.josm.data.projection.ProjectionRegistry.setProjection(org.openstreetmap.josm.data.projection.Projections.getProjectionByCode("EPSG:3857"));
    var view=new org.openstreetmap.josm.gui.MapView(new org.openstreetmap.josm.gui.layer.MainLayerManager(),new org.openstreetmap.josm.data.ViewportData(new org.openstreetmap.josm.data.coor.EastNorth(0,0),1.0)) {
     @Override protected boolean isVisibleOnScreen(){return true;}
     @Override public java.awt.Point getLocationOnScreen(){return new java.awt.Point(0,0);}
    };
    view.setSize(800,600);view.prepareToDraw();view.zoomTo(new org.openstreetmap.josm.data.coor.EastNorth(100,200),3,false);reference.set(view);
   });
   var view=reference.get();var work=VisibleEvidencePlanTest.work(owner);var p=VisibleEvidencePlan.create(s,VisibleEvidencePlanTest.original(s,epoch,1),work);var observed=new java.util.concurrent.atomic.AtomicReference<String>();
   var renderer=new Renderer(){
    @Override public Viewport snapshot(){assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());var c=view.getCenter();return new Viewport(c.east(),c.north(),view.getScale(),view.getWidth(),view.getHeight());}
    @Override public RenderedChunk render(Request request,Chunk chunk){
     var b=chunk.bounds();view.setSize(chunk.viewWidth(),chunk.viewHeight());view.prepareToDraw();view.zoomTo(new org.openstreetmap.josm.data.coor.EastNorth((b.minimumEast()+b.maximumEast())/2,(b.minimumNorth()+b.maximumNorth())/2),request.evidencePlan().projectedScale(),false);
     var result=super.render(request,chunk);var actual=view.getProjectionBounds();
     observed.set("requested="+b+", actual="+actual+", stateSize="+view.getState().getViewWidth()+"x"+view.getState().getViewHeight()+", size="+view.getSize()+", scale="+view.getScale());
     var points=request.projectedParent().stream().map(point->new org.openstreetmap.josm.data.coor.EastNorth(point.east(),point.north())).toList();
     double ground=ProjectionGroundScale.measure(points,p.projectedScale()).representativeMetersPerProjectionUnit()*p.projectedScale();
     return new RenderedChunk(result.image(),new Bounds(actual.minEast,actual.minNorth,actual.maxEast,actual.maxNorth),result.sourceZoom(),result.nativePitchMeters(),ground);
    }
    @Override public void restore(Viewport saved){view.setSize(saved.width(),saved.height());view.prepareToDraw();view.zoomTo(new org.openstreetmap.josm.data.coor.EastNorth(saved.east(),saved.north()),saved.scale(),false);super.restore(saved);}
   };
   var edt=new Queue();var worker=new Queue();var op=new VisibleRefinementCapture(renderer,edt,worker,40,40).capture(request(p,owner),work);worker.next();
   var panned=new java.util.concurrent.atomic.AtomicReference<Viewport>();
   javax.swing.SwingUtilities.invokeAndWait(()->{
    view.setSize(910,710);view.prepareToDraw();view.zoomTo(new org.openstreetmap.josm.data.coor.EastNorth(1500,2600),7,false);panned.set(renderer.snapshot());edt.next();var restored=renderer.snapshot();
    assertEquals(panned.get().east(),restored.east(),32*Math.ulp(p.roundedBounds().maximumEast()));assertEquals(panned.get().north(),restored.north(),32*Math.ulp(p.roundedBounds().maximumNorth()));assertEquals(panned.get().scale(),restored.scale());assertEquals(panned.get().width(),restored.width());assertEquals(panned.get().height(),restored.height());
   });
   assertEquals(1,renderer.paints);assertEquals(1,renderer.restores);worker.drain();
   if(op.result().toCompletableFuture().isCompletedExceptionally()){var error=assertThrows(CompletionException.class,()->op.result().toCompletableFuture().join());throw new AssertionError(observed.get(),error.getCause());}
   assertFalse(op.result().toCompletableFuture().isDone(),"Measured real MapView chunk bounds must pass before cancellation");
   op.cancel();javax.swing.SwingUtilities.invokeAndWait(edt::drain);worker.drain();assertTrue(op.settled().toCompletableFuture().isDone());
   // The original Legacy call still paints/projections through its compatibility frame.
   // Only the additive metadata observes its distinct, actually painted clip.
   javax.swing.SwingUtilities.invokeAndWait(()->{
    var layer=new KnownLayer();view.getLayerManager().addLayer(layer);
    try {
     var capture=AlignmentService.class.getDeclaredMethod("captureVisibleHeatmap",org.openstreetmap.josm.gui.layer.ImageryLayer.class,org.openstreetmap.josm.gui.MapView.class,List.class,int.class);capture.setAccessible(true);
     var result=capture.invoke(new AlignmentService(),layer,view,List.of(new org.openstreetmap.josm.data.coor.EastNorth(0,0),new org.openstreetmap.josm.data.coor.EastNorth(20,0)),7);
     var raster=result.getClass().getDeclaredMethod("raster");raster.setAccessible(true);var image=(BufferedImage)raster.invoke(result);
     var compatibility=result.getClass().getDeclaredMethod("bounds");compatibility.setAccessible(true);var bounds=(org.openstreetmap.josm.data.ProjectionBounds)compatibility.invoke(result);
     assertEquals(1014,image.getWidth());assertEquals(702,image.getHeight());assertEquals(-22.8705,bounds.minEast,1e-9);assertEquals(42.8705,bounds.maxEast,1e-9);assertEquals(22.7565,bounds.maxNorth,1e-9);assertEquals(-22.7565,bounds.minNorth,1e-9);
     assertEquals(0xffff0000,image.getRGB(19,28));assertEquals(0xff000000,image.getRGB(24,30));
     var observedFrame=result.getClass().getDeclaredMethod("paintedFrame");observedFrame.setAccessible(true);
     @SuppressWarnings("unchecked") var painted=((Optional<LegacyVisibleSource.PaintedFrame>)observedFrame.invoke(result)).orElseThrow();assertNotEquals(bounds.minEast,painted.minimumEast());
     double east=painted.minimumEast()+3.25*painted.projectionUnitsPerViewPixel(),north=painted.maximumNorth()-4.75*painted.projectionUnitsPerViewPixel();
     var ll=org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection().eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth(east,north));
     var input=org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.visibleWebMercator(painted.minimumEast(),painted.maximumNorth(),painted.projectionUnitsPerViewPixel(),6).toRasterCenter(new GeographicPoint(ll.lat(),ll.lon()));
     assertEquals(19.5,input.x(),1e-7);assertEquals(28.5,input.y(),1e-7);
    }catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}finally{view.getLayerManager().removeLayer(layer);}
   });
  } finally {
   javax.swing.SwingUtilities.invokeAndWait(()->{if(reference.get()!=null)reference.get().destroy();org.openstreetmap.josm.spi.preferences.Config.setPreferencesInstance(previousPreferences);try{pane.set(null,previousPane);if(previousProjection==null)projection.set(null,null);else org.openstreetmap.josm.data.projection.ProjectionRegistry.setProjection(previousProjection);}catch(IllegalAccessException failure){throw new IllegalStateException(failure);}});
  }
 }
 @Test void completedRecaptureFeedsTheSameFrozenScalarGridWithoutChangingRootAuthority() {
  var s=VisibleEvidencePlanTest.source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();var ledger=AttemptMemoryLedger.production();
  try(var owner=ledger.rootOwner()) {
   var work=VisibleEvidencePlanTest.work(owner);var o=VisibleEvidencePlanTest.original(s,epoch,1);
   var metadata=VisibleEvidencePlanTest.factual(new LegacyVisibleSource(o.minimumEast(),o.minimumNorth(),o.maximumEast(),o.maximumNorth(),10,6,o.nativePitchMeters(),o.projectionCode(),o.sourceReceipt(),o.decisionRadiusMeters(),o.sampleStepMeters()));
   var p=VisibleEvidencePlan.create(s,metadata,work);var originalEvidence=s.parent().root().evidence();var originalHash=originalEvidence.canonicalHash();
   var edt=new Queue();var worker=new Queue();var op=new VisibleRefinementCapture(new Renderer(),edt,worker,40,40).capture(request(p,owner),work);pump(edt,worker,op);
   var captured=op.result().toCompletableFuture().join();var frame=captured.value();
   long copiedBefore=work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.COPY);
   var ticket=s.ticket();var otherTicket=new RefinementModels.OperationTicket(ticket.session(),ticket.sequence()+1,ticket.parent(),ticket.choice(),ticket.engine(),ticket.mode());var other=RefinementSourcePlanner.plan(otherTicket,s.settings(),s.parent());
   assertThrows(IllegalArgumentException.class,()->RefinementAcquisition.captureVisible(other,p,frame,"blue",work));assertEquals(copiedBefore,work.counters().get(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.COPY));
   var converted=RefinementAcquisition.captureVisible(s,p,frame,"blue",work);var evidence=converted.value().evidence();
   assertSame(s,converted.value().plan());assertEquals(p.grid().transform(),evidence.transform());
   var source=s.requests().stream().filter(r->r.palette().equals("blue")&&r.mapping().equals("blue")).findFirst().orElseThrow();
   assertEquals(frame.inputHash(),converted.value().retentionDescriptor(source,evidence).inputByteHash());assertSame(originalEvidence,s.parent().root().evidence());assertEquals(originalHash,originalEvidence.canonicalHash());
   converted.owner().close();captured.owner().close();assertThrows(IllegalStateException.class,()->frame.argbAt(0,0,work));
  }assertEquals(0,ledger.currentBytes());
 }
}

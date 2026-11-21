package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.VisibleRefinementCaptureTest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

public class VisibleEvidencePlanTest {
 public static RefinementSourcePlanner.Plan source(TrackerMode engine) {
  var p=RefinementSourcePlannerTest.parent();var t=RefinementSourcePlannerTest.ticket(p);
  t=new RefinementModels.OperationTicket(t.session(),t.sequence(),t.parent(),t.choice(),engine,t.mode());
  return RefinementSourcePlanner.plan(t,RefinementSourcePlannerTest.settings(false,IntensitySamplingMode.COLOR_MAPPING,false),p);
 }
 public static LegacyVisibleSource original(RefinementSourcePlanner.Plan p,VisibleSourceEpoch epoch,double nativePitch) {
  var g=p.parent().root().evidence().coordinateFrame().toGeographic(p.parent().points().get(0));
  double e=6378137*Math.toRadians(g.longitudeDegrees()),n=6378137*Math.log(Math.tan(Math.PI/4+Math.toRadians(g.latitudeDegrees())/2));
  return factual(new LegacyVisibleSource(e-100,n-100,e+100,n+100,1,1,OptionalDouble.of(nativePitch),"EPSG:3857",epoch.captureStable(),OptionalDouble.of(p.parent().root().request().permissions().ordinaryRadiusMeters()),OptionalDouble.of(1)));
 }
 public static LegacyVisibleSource factual(LegacyVisibleSource metadata) {
  int width=(int)Math.round((metadata.maximumEast()-metadata.minimumEast())/metadata.projectionUnitsPerViewPixel()*6),height=(int)Math.round((metadata.maximumNorth()-metadata.minimumNorth())/metadata.projectionUnitsPerViewPixel()*6);
  var chunk=new LegacyVisibleSource.PaintedChunk(0,0,width,height,metadata.minimumEast(),metadata.minimumNorth(),metadata.maximumEast(),metadata.maximumNorth(),metadata.projectionUnitsPerViewPixel(),metadata.groundMetersPerViewPixel(),metadata.nativePitchMeters(),15,metadata.projectionCode());
  return metadata.withPaintedFrame(LegacyVisibleSource.PaintedFrame.common(width,height,List.of(chunk)).orElseThrow());
 }
 public static GeometryWorkContext work(AttemptMemoryLedger.Owner owner){return new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,System.nanoTime()+60_000_000_000L,CancellationProbe.NONE,owner);}
 public static RefinementModels.Stage publishedStage(VisibleEvidencePlan p) {
  var candidate=RefinementParentFixtures.parent(p.sourcePlan().parent().root(),"purple");
  return new RefinementModels.Stage(p.sourcePlan().ticket().parent(),Optional.empty(),Optional.empty(),RefinementModels.StageKind.ROOT_LEGACY,TrackerMode.LEGACY_V02,List.of(candidate),candidate.id(),Optional.empty());
 }
 @Test void engineSpecificPlanIsOneAuthority() {
  var source=source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var work=work(owner);var plan=VisibleEvidencePlan.create(source,original(source,epoch,1.0/6),work);
   assertSame(source,plan.sourcePlan());assertEquals(13,plan.haloPixels());assertEquals(1.0/6,plan.grid().pitchMeters());
   assertEquals(source.parent().root().request().permissions().ordinaryRadiusMeters(),plan.decisionRadiusMeters());
   assertThrows(UnsupportedOperationException.class,()->plan.projectedParent().clear());
   var unequal=VisibleEvidencePlan.create(source,original(source,epoch,1),work);
   assertEquals(16,unequal.maximumReduction());assertEquals(46,unequal.requiredSupportPixels());assertEquals(49,unequal.haloPixels());
   assertTrue(unequal.haloPixels()>13);assertEquals(1,Math.floorMod(unequal.haloPixels(),unequal.maximumReduction()));
   assertEquals(2*(unequal.maximumReduction()-1)+unequal.maximumReduction(),unequal.requiredSupportPixels());
   var b=source(TrackerMode.PROBABILISTIC);var bp=VisibleEvidencePlan.create(b,original(b,epoch,1),work);
   assertNotEquals(unequal.haloPixels(),bp.haloPixels());
   var wideRay=VisibleEvidencePlan.create(b,original(b,epoch,3),work);
   assertEquals(73,wideRay.requiredSupportPixels(),"B's existing 4-source-pitch ray is12m, requiring73 samples at1/6m output pitch");
  }
 }
 @Test void knownInvalidationIsTypedAndTicketBound() {
  var s=source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();var original=original(s,epoch,1);
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var p=VisibleEvidencePlan.create(s,original,work(owner));epoch.beginLoad();
   var failure=assertThrows(VisibleEvidencePlan.StaleSource.class,p::requireCurrent);
   assertEquals(s.ticket(),failure.ticket());assertEquals(s.parent().root().sourceIdentity(),failure.sourceIdentity());assertEquals("visible-original-epoch-changed",failure.getMessage());
  }
 }
 @Test void supportProvesEntireGridWithoutPixelCopies() {
  var s=source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var original=original(s,epoch,1);var p=VisibleEvidencePlan.create(s,original,work(owner));
   var b=p.requestedBounds();int w=p.viewWidth(),h=p.viewHeight();var actual=p.roundedBounds();
   var raster=new LiveBPreviewService.VisibleRaster(w*6,h*6,new int[w*6*h*6],actual.minimumEast(),actual.minimumNorth(),actual.maximumEast(),actual.maximumNorth(),p.projectedScale(),1,OptionalDouble.of(1),s.parent().root().sourceIdentity(),"EPSG:3857",original.sourceReceipt());
   assertEquals(VisibleEvidencePlan.Support.COVERED,RefinementAcquisition.visibleSupport(p,raster));
   assertTrue(b.minimumEast()>=actual.minimumEast());assertTrue(b.maximumNorth()<=actual.maximumNorth());
   var tiny=new LiveBPreviewService.VisibleRaster(6,6,new int[36],original.minimumEast(),original.minimumNorth(),original.minimumEast()+1,original.minimumNorth()+1,1,1,OptionalDouble.of(1),raster.sourceIdentity(),"EPSG:3857",original.sourceReceipt());
   assertEquals(VisibleEvidencePlan.Support.NEEDS_CAPTURE,RefinementAcquisition.visibleSupport(p,tiny));
  }
 }
 @Test void canonicalIdentityBindsRequestedFrameAndOriginalScale() {
  var s=source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var o=original(s,epoch,1);var work=work(owner);var first=VisibleEvidencePlan.create(s,o,work);
   var changed=new LegacyVisibleSource(o.minimumEast(),o.minimumNorth(),o.maximumEast(),o.maximumNorth(),2,o.groundMetersPerViewPixel(),o.nativePitchMeters(),o.projectionCode(),o.sourceReceipt(),o.decisionRadiusMeters(),o.sampleStepMeters());
   var second=VisibleEvidencePlan.create(s,factual(changed),work);
   assertEquals(first.grid(),second.grid());assertNotEquals(first.viewWidth(),second.viewWidth());
   assertNotEquals(first.canonicalIdentity(),second.canonicalIdentity(),"A canonical plan must bind projected scale and actual requested frame");
  }
 }
 @Test void aDifferentWorkContextCannotRestartVisibleConversion() {
  var s=source(TrackerMode.PROBABILISTIC);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var work=work(owner);var p=VisibleEvidencePlan.create(s,original(s,epoch,1),work);var raster=VisibleRefinementCaptureTest.raster(p);var restarted=work(owner);
   assertThrows(IllegalArgumentException.class,()->RefinementAcquisition.captureVisible(s,p,raster,"blue",restarted));
   assertEquals(0,restarted.visits(),"Refusal must precede optional pixel/scalar work");
  }
 }
 @Test void pointsAndDecisionFitDoNotProveCompleteEngineSupport() {
  var a=source(TrackerMode.CORRIDOR_AWARE);var t=a.ticket();
  var bt=new RefinementModels.OperationTicket(t.session(),t.sequence(),t.parent(),t.choice(),TrackerMode.PROBABILISTIC,t.mode());
  var b=RefinementSourcePlanner.plan(bt,a.settings(),a.parent());var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var work=work(owner);var o=original(a,epoch,1);
   o=new LegacyVisibleSource(o.minimumEast(),o.minimumNorth(),o.maximumEast(),o.maximumNorth(),10,6,o.nativePitchMeters(),o.projectionCode(),o.sourceReceipt(),o.decisionRadiusMeters(),o.sampleStepMeters());
   o=factual(o);var ap=VisibleEvidencePlan.create(a,o,work);var bp=VisibleEvidencePlan.create(b,o,work);var raster=VisibleRefinementCaptureTest.raster(bp);
   assertEquals(13,ap.haloPixels());assertEquals(7,bp.haloPixels());assertEquals(VisibleEvidencePlan.Support.COVERED,bp.support(raster));assertEquals(VisibleEvidencePlan.Support.NEEDS_CAPTURE,ap.support(raster));
   var transform=org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.SupportedInputRasterTransform.visibleWebMercator(raster.minimumEast(),raster.maximumNorth(),raster.projectionUnitsPerViewPixel(),6);
   for(var point:a.parent().points()){var pixel=transform.toRasterCenter(ap.grid().coordinateFrame().toGeographic(point));assertTrue(pixel.x()>=0&&pixel.x()<raster.width()-1&&pixel.y()>=0&&pixel.y()<raster.height()-1);}
   var decision=transform.boundsForMetricCell(ap.grid().coordinateFrame(),ap.decisionFootprint().polygons().get(0));assertTrue(decision.minimumX()>=0&&decision.minimumY()>=0&&decision.maximumX()<raster.width()-1&&decision.maximumY()<raster.height()-1);
   assertEquals(a.parent().points(),b.parent().points());assertTrue(a.parent().points().stream().anyMatch(p->Math.abs(p.yMeters()-47)<1e-5),"Fixture parent is bent and moved from original y50");
  }
 }
 @Test void detachedPlanningNeverRunsOnEdt() throws Exception {
  var s=source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var original=original(s,epoch,1);var work=work(owner);
   javax.swing.SwingUtilities.invokeAndWait(()->assertThrows(IllegalStateException.class,()->VisibleEvidencePlan.create(s,original,work)));
   assertEquals(0,work.visits());
  }
 }
 @Test void historicalCompatibilityFrameIsNotFactualPaintAuthority() {
  var s=source(TrackerMode.CORRIDOR_AWARE);var epoch=new VisibleSourceEpoch();var measured=original(s,epoch,1);
  var historical=new LegacyVisibleSource(measured.minimumEast(),measured.minimumNorth(),measured.maximumEast(),measured.maximumNorth(),measured.projectionUnitsPerViewPixel(),measured.groundMetersPerViewPixel(),measured.nativePitchMeters(),measured.projectionCode(),measured.sourceReceipt(),measured.decisionRadiusMeters(),measured.sampleStepMeters());
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var work=work(owner);assertTrue(historical.paintedFrame().isEmpty());assertThrows(UnsupportedOperationException.class,()->VisibleEvidencePlan.create(s,historical,work));
   assertEquals(0,work.counters().get(GeometryWorkContext.Kind.COPY));assertEquals(0,owner.currentBytes());assertEquals(measured.minimumEast(),historical.minimumEast());
  }
 }
 @Test void paintedMosaicNeedsCompleteCommonLatticeAndRetainsHeterogeneousResolution() {
  var first=new LegacyVisibleSource.PaintedChunk(0,0,6,6,0,0,1,1,1,1,OptionalDouble.of(1),15,"EPSG:3857");
  var second=new LegacyVisibleSource.PaintedChunk(6,0,6,6,1,0,2,1,1,1,OptionalDouble.of(1),15,"EPSG:3857");
  var input=new ArrayList<>(List.of(first,second));var frame=LegacyVisibleSource.PaintedFrame.common(12,6,input).orElseThrow();input.clear();assertEquals(2,frame.chunks().size());assertTrue(frame.commonResolution());assertThrows(UnsupportedOperationException.class,()->frame.chunks().clear());
  var shifted=new LegacyVisibleSource.PaintedChunk(6,0,6,6,1.001,0,2.001,1,1,1,OptionalDouble.of(1),15,"EPSG:3857");assertTrue(LegacyVisibleSource.PaintedFrame.common(12,6,List.of(first,shifted)).isEmpty());assertTrue(LegacyVisibleSource.PaintedFrame.common(12,6,List.of(first)).isEmpty());
  var coarse=new LegacyVisibleSource.PaintedChunk(6,0,6,6,1,0,2,1,1,1,OptionalDouble.of(2),14,"EPSG:3857");var heterogeneous=LegacyVisibleSource.PaintedFrame.common(12,6,List.of(first,coarse)).orElseThrow();assertFalse(heterogeneous.commonResolution());assertTrue(heterogeneous.nativePitchMeters().isEmpty());assertEquals(2,heterogeneous.chunks().get(1).nativePitchMeters().orElseThrow());
 }
}

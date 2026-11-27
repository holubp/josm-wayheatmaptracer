package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.PostprocessingValidationWork;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.MetricCorridorRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
class ParentReferenceBinderTest {
 @Test void exactCapturedIntervalPolygonsProveCoverageWithoutRelaxingFallback(){
  // First eligible exterior and one neighboring segment from the public visible interval capture.
  var points=List.of(
      new MetricPoint(-39.39999999972607,-3.973222481332367),
      new MetricPoint(-37.55104772616998,-4.125240458888852),
      new MetricPoint(-35.69999999975167,-4.222712326434083),
      new MetricPoint(-33.848952273333346,-4.125240458888852),
      new MetricPoint(-31.999999999777252,-3.973222481332367),
      new MetricPoint(-27.99999999980517,-3.973222481332367));
  var whole=MetricCorridorRegion.aroundPolyline(points,7.01);
  var interval=MetricCorridorRegion.aroundPolyline(points.subList(0,5),7.01);
  assertTrue(whole.polygons().containsAll(interval.polygons()));
  assertTrue(ParentTraceReference.decisionRegionContains(whole,interval,CancellationProbe.NONE),
      "Every interval polygon is already present in the captured decision corridor");
  var missing=new ArrayList<>(interval.polygons());missing.addAll(MetricRegion.rectangle(100,100,102,102).polygons());
  assertFalse(ParentTraceReference.decisionRegionContains(whole,new MetricRegion(missing),CancellationProbe.NONE));
  var inset=MetricRegion.rectangle(-36,-4.5,-34,-3.5);
  assertFalse(whole.polygons().containsAll(inset.polygons()));
  assertTrue(ParentTraceReference.decisionRegionContains(whole,inset,CancellationProbe.NONE),"Area still proves nonidentical subsets");
  var scans=new java.util.concurrent.atomic.AtomicInteger();
  assertThrows(java.util.concurrent.CancellationException.class,
      ()->ParentTraceReference.decisionRegionContains(whole,interval,()->scans.incrementAndGet()==2));
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var work=new GeometryWorkContext(1,4096,Long.MAX_VALUE,CancellationProbe.NONE,owner);
   try(var scope=PostprocessingValidationWork.open(work)) {
    assertThrows(GeometryWorkContext.LimitReached.class,
        ()->ParentTraceReference.decisionRegionContains(whole,interval,CancellationProbe.NONE));
    assertEquals(1,work.visits());
   }
  }
 }
 @Test void pinsExactNonTopCandidateAndPaletteWithoutDefaultPreviewSubstitution(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var id=new StageId(UUID.randomUUID());var blue=new ParentReferenceBinder().bind(root,id,RefinementParentFixtures.parent(root,"blue"));
  assertEquals("blue",blue.parentPalette());assertEquals("blue-native",blue.nativeCandidateId());assertEquals(53,blue.points().get(2).yMeters(),1e-7);assertSame(root.network(),blue.root().network());
  var purple=new ParentReferenceBinder().bind(root,id,RefinementParentFixtures.parent(root,"purple"));assertNotEquals(blue.canonicalIdentity(),purple.canonicalIdentity());assertEquals(47,purple.points().get(2).yMeters(),1e-7);
 }
 @Test void missingMappingIsTypedAndDoesNotInventNearestOccurrence(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var c=RefinementParentFixtures.parent(root,"blue");var partial=new Candidate(c.id(),c.payload(),List.of(c.correspondence().get(0),c.correspondence().get(4)),c.immutableIdentity());var bound=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),partial);
  assertEquals(List.of(1,2,3),bound.missingOccurrences());assertEquals(ParentTraceReference.Unavailable.MAPPING_UNAVAILABLE,assertThrows(ParentTraceReference.UnavailableException.class,()->bound.requireComplete()).reason());
 }
 @Test void invalidEdgesAndUnboundNativeSelectionFailClosed(){
  var root=RefinementParentFixtures.authority(AlignmentMode.MOVE_EXISTING_NODES);var c=RefinementParentFixtures.parent(root,"blue");var bad=new Candidate(c.id(),c.payload(),List.of(new ParentArc(0,999,0),new ParentArc(4,999,1)),"bad");assertThrows(IllegalArgumentException.class,()->new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),bad));
  var absent=new Candidate(new CandidateId("absent"),c.payload(),c.correspondence(),"bad");assertThrows(IllegalArgumentException.class,()->new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),absent));
 }

 @Test void removedPreciseOccurrenceCutsTheExactParentEdgeAndMissingCutsStayLocal(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var c=RefinementParentFixtures.parent(root,"blue");
  var result=c.result();var nativeBlue=result.candidates().get(1);var reduced=List.of(nativeBlue.finalPreviewPoints().get(0),nativeBlue.finalPreviewPoints().get(2),nativeBlue.finalPreviewPoints().get(4));
  var n=new CenterlineCandidate(nativeBlue.id(),1,List.of(),List.of(),reduced,reduced,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
  result=new AlignmentResult(RefinementParentFixtures.selection(root),null,List.of(n),reduced,reduced,List.of(),null,null,result.detectorAttempts(),List.of(n));
  var arcs=List.of(new ParentArc(0,0,0),new ParentArc(1,0,.5),new ParentArc(2,1,0),new ParentArc(3,1,.5),new ParentArc(4,1,1));
  var bound=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),new Candidate(c.id(),result,arcs,"reduced"));
  var cut=bound.slice(new OccurrenceRange(1,3));assertEquals(List.of(bound.occurrencePosition(1),bound.points().get(1),bound.occurrencePosition(3)),cut.points());assertEquals(List.of(1,2,3),cut.correspondence().stream().map(ParentArc::originalOccurrence).toList());
  var partial=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),new Candidate(c.id(),result,List.of(arcs.get(0),arcs.get(2),arcs.get(3),arcs.get(4)),"partial"));
  assertDoesNotThrow(()->partial.slice(new OccurrenceRange(2,4)).requireComplete());assertThrows(ParentTraceReference.UnavailableException.class,()->partial.slice(new OccurrenceRange(0,2)).requireComplete());
 }
 @Test void missingPaletteStaleAuthorityAndRepeatedOccurrencesOutsideRangeFailClosed(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=RefinementParentFixtures.parent(root,"blue");var result=parent.result();
  var noPalette=new AlignmentResult(result.selection(),result.capturedHeatmap(),result.candidates(),result.sourcePolyline(),result.previewPolyline(),result.nodeMoves(),result.diagnostics(),result.tileMosaics(),List.of(),result.applicableCandidates());
  assertThrows(IllegalArgumentException.class,()->new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),new Candidate(parent.id(),noPalette,parent.correspondence(),"no-palette")));
  var values=new LinkedHashMap<>(root.network().primitives());var way=(DetachedWay)values.get(root.request().selectedWayKey());var repeated=new ArrayList<>(way.nodeKeys());repeated.add(way.nodeKeys().get(0));values.put(way.key(),new DetachedWay(way.key(),repeated,way.tags(),false,false));
  var c=root.network().closure();var changed=new NetworkSnapshot("changed-network",SnapshotRole.CAPTURED_BEFORE,root.network().datasetIdentity(),1,c,values,V022SnapshotFixtures.closedWorldReferrerWatches(values));
  assertThrows(IllegalArgumentException.class,()->new RefinementRootAuthority(changed,root.request(),root.evidence(),root.effectiveConfig(),root.projectionCode(),root.sourceIdentity()),"Stale root digest cannot be relabeled");
  var r=root.request();var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),r.evidenceSnapshotId(),r.evidenceContentHash(),changed.snapshotId(),changed.canonicalHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution());
  assertThrows(IllegalArgumentException.class,()->new RefinementRootAuthority(changed,request,root.evidence(),root.effectiveConfig(),root.projectionCode(),root.sourceIdentity()),"Repeat beyond selected last occurrence still makes original identities ambiguous");
 }
 @Test void taggedAndRelationOwnedOccurrencesCannotGainRemovableAuthority(){
  for(boolean relation:new boolean[]{false,true}) {
   var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var values=new LinkedHashMap<>(root.network().primitives());var way=(DetachedWay)values.get(root.request().selectedWayKey());var key=way.nodeKeys().get(2);var node=(DetachedNode)values.get(key);
   if(relation){var relationKey=PrimitiveKey.existing(PrimitiveKey.Type.RELATION,999);values.put(relationKey,new DetachedRelation(relationKey,List.of(new DetachedRelationMember(key,"via")),Map.of("type","restriction"),false,false));}
   else values.put(key,new DetachedNode(key,node.coordinate(),Map.of("barrier","gate"),false,false));
   var c=root.network().closure();var closure=new ClosureDescriptor(c.scope(),c.queryVersion(),values.keySet(),c.editableExistingKeys(),c.movableExistingNodeKeys(),c.protectedExistingNodeKeys(),c.removableExistingNodeKeys(),c.editableWayOccurrences(),c.externalPorts(),c.collisionEnvelope(),c.editRegion(),c.mayCreateNodes(),true,true,true);
   var network=new NetworkSnapshot("protected-authority",SnapshotRole.CAPTURED_BEFORE,root.network().datasetIdentity(),1,closure,values,V022SnapshotFixtures.closedWorldReferrerWatches(values));var r=root.request();
   var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),r.evidenceSnapshotId(),r.evidenceContentHash(),network.snapshotId(),network.canonicalHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution());
   assertThrows(IllegalArgumentException.class,()->new RefinementRootAuthority(network,request,root.evidence(),root.effectiveConfig(),root.projectionCode(),root.sourceIdentity()),"Captured removal claims contradict factual tags/referrers");
  }
 }
 @Test void arbitrarySelfAssertedMeasuredRowsAndOrdinaryEntryWithoutReferenceRefuse(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  assertThrows(IllegalArgumentException.class,()->parent.withSampling(new ProfileChainage(List.of(0.,1.,2.),root.request().configuredSampleStepMeters())));
  assertThrows(IllegalArgumentException.class,()->parent.withSampling(ProfileChainage.measured(parent.points(),root.request().configuredSampleStepMeters())),"Fine captured source rows cannot be replaced by coarse preview vertices");
  var request=parent.request(TrackerMode.CORRIDOR_AWARE,root.evidence(),root.request().budgets(),1);
  assertThrows(IllegalArgumentException.class,()->new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorEngineAdapter("native").traceWithUsage(request,root.evidence(),root.network(),org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE));
 }

 @Test void explicitAliasSelectsRecordedPaletteWithoutChoosingFirstAttempt(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var c=RefinementParentFixtures.parent(root,"blue");var old=c.result();var selected=old.candidates().get(1);
  var result=new AlignmentResult(RefinementParentFixtures.selection(root),null,List.of(selected),old.sourcePolyline(),old.previewPolyline(),List.of(),null,null,List.of(new DetectorAttempt("purple","purple",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(selected.id()),"",""),new DetectorAttempt("blue","blue",TrackerMode.LEGACY_V02,DetectorAttemptStatus.APPLICABLE,List.of(selected.id()),"","")),List.of(selected));
  var payload=RefinementPayload.capture(result,1_000_000);
  var alias=new Candidate(new CandidateId("chosen-blue-alias"),payload,c.correspondence(),"selected-blue",selected.id(),"blue");
  var bound=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),alias);assertEquals("blue",bound.parentPalette());assertEquals("blue-native",bound.nativeCandidateId());assertEquals("chosen-blue-alias",bound.parentChoice().value());
  var ambiguous=new Candidate(c.id(),payload,c.correspondence(),"ambiguous");
  assertEquals(ParentReferenceBinder.Reason.PALETTE_UNAVAILABLE,assertThrows(ParentReferenceBinder.Refused.class,()->new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),ambiguous)).reason());
  var missing=new Candidate(alias.id(),payload,alias.correspondence(),"missing",selected.id(),"gray");
  assertEquals(ParentReferenceBinder.Reason.PALETTE_UNAVAILABLE,assertThrows(ParentReferenceBinder.Refused.class,()->new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),missing)).reason());
 }

 @Test void zeroEdgeAmbiguityFreezesOnlyDependentReferenceIntervals(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var c=RefinementParentFixtures.parent(root,"blue");var old=c.result();var selected=old.candidates().get(1);var points=new ArrayList<>(selected.finalPreviewPoints());points.set(2,points.get(1));
  var ambiguous=new CenterlineCandidate(selected.id(),1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
  var result=new AlignmentResult(RefinementParentFixtures.selection(root),null,List.of(ambiguous),old.sourcePolyline(),old.previewPolyline(),List.of(),null,null,old.detectorAttempts(),List.of(ambiguous));
  var bound=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),new Candidate(c.id(),result,c.correspondence(),"ambiguous-edge"));
  assertThrows(ParentTraceReference.UnavailableException.class,bound::requireComplete);
  assertDoesNotThrow(()->bound.slice(new OccurrenceRange(2,4)).requireComplete());
 }
 @Test void contradictoryCandidateOwnedAssignmentFailsAgainstExactParentGeometry(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var c=RefinementParentFixtures.parent(root,"blue");var old=c.result();var selected=old.candidates().get(1);
  var bad=new CenterlineCandidate(selected.id(),1,List.of(),List.of(),selected.eastNorthPoints(),selected.finalPreviewPoints(),Map.of(2L,selected.finalPreviewPoints().get(4)),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
  var result=new AlignmentResult(RefinementParentFixtures.selection(root),null,List.of(bad),old.sourcePolyline(),old.previewPolyline(),List.of(),null,null,old.detectorAttempts(),List.of(bad));
  assertThrows(IllegalArgumentException.class,()->new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),new Candidate(c.id(),result,c.correspondence(),"bad-assignment")));
 }
 @Test void moveParentCannotCarryInsertedPrecisePreviewOccurrences(){
  var root=RefinementParentFixtures.authority(AlignmentMode.MOVE_EXISTING_NODES);var c=RefinementParentFixtures.parent(root,"blue");var old=c.result();var selected=old.candidates().get(1);var points=new ArrayList<>(selected.finalPreviewPoints());var a=points.get(1);var b=points.get(2);points.add(2,new org.openstreetmap.josm.data.coor.EastNorth((a.east()+b.east())/2,(a.north()+b.north())/2));
  var dense=new CenterlineCandidate(selected.id(),1,List.of(),List.of(),points,points,Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
  var result=new AlignmentResult(RefinementParentFixtures.selection(root),null,List.of(dense),old.sourcePolyline(),old.previewPolyline(),List.of(),null,null,old.detectorAttempts(),List.of(dense));
  var arcs=List.of(new ParentArc(0,0,0),new ParentArc(1,1,0),new ParentArc(2,3,0),new ParentArc(3,4,0),new ParentArc(4,4,1));
  assertThrows(IllegalArgumentException.class,()->new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),new Candidate(c.id(),result,arcs,"bad-move-count")));
 }
}

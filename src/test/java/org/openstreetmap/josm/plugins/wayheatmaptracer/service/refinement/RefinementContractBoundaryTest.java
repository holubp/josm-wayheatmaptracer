package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;

class RefinementContractBoundaryTest {
 @Test void actualPreparedAUsesItsConsumedNonuniformRowsAndRefusesMissingProof(){
  var fixture=CorridorPolicyFixture.admittedParabola();
  var uncoveredRoot=legacyRoot(fixture);var uncoveredParent=vertexParent(uncoveredRoot);
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var refused=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(uncoveredParent,uncoveredRoot.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
   assertEquals(0,refused.nativeCalls());assertEquals(Optional.of(ParentTraceReference.Unavailable.EVIDENCE_COVERAGE),refused.unavailable());
  }
  var root=legacyRoot(coveredParabola(fixture));var rootRequest=root.request();
  var way=(DetachedWay)root.network().primitives().get(rootRequest.selectedWayKey());
  var parent=vertexParent(root);
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var run=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
   assertEquals(1,run.nativeCalls(),()->run.outcome()+" "+run.unavailable());
   assertTrue(run.nativeRun().isPresent(),()->run.outcome()+" "+run.unavailable());
   var actual=run.nativeRun().orElseThrow();var receipt=actual.corridorReceipt().orElseThrow();
   assertFalse(receipt.candidates().isEmpty(),()->receipt.tracks().toString());
   var route=run.finalized().orElseThrow().routes().stream().filter(r->receipt.candidates().containsKey(r.rawHypothesis().id())).findFirst().orElseThrow();
   var mapping=receipt.candidates().get(route.rawHypothesis().id()).mapping();assertTrue(mapping.consumedCount()>0);
   var rows=run.nativePointReferenceChainages().get(route.rawHypothesis().id());
   for(int i=0;i<rows.size();i++)assertEquals(run.request().profileChainage().cumulativeGroundMeters().get(mapping.originalRow(i)),rows.get(i));
   int removed=mapping.leftOriginalRow()+1;var id=new FinalRoutePointId.ExistingWayNodeOccurrence(rootRequest.selectedWayKey(),way.nodeKeys().get(removed),removed);
   double target=run.reference().occurrenceChainage(removed);int edge=0;while(edge<rows.size()-2&&rows.get(edge+1)<=target)edge++;
   var expected=ParentTraceReference.interpolate(route.rawHypothesis().points().get(edge),route.rawHypothesis().points().get(edge+1),(target-rows.get(edge))/(rows.get(edge+1)-rows.get(edge)));
   assertEquals(expected,route.assignments().get(id),"Retained occurrence uses parent-reference chainage, not raw output length");
   var fake=new TraceHypothesis("missing-prepared-proof",route.rawHypothesis().branchSignature(),run.reference().points(),Collections.nCopies(run.reference().points().size(),ObservationOwnership.INFERRED_GAP),1,OptionalDouble.empty(),Map.of("preparedCorrespondence",1.));
   var fakeSet=new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE,List.of(fake),TraceHypothesisSet.Status.COMPLETE,false,1,1,"corrupt ownership test");
   var fakeRun=new TraceEngineRun(fakeSet,new TraceWorkUsage(1,1,1,1),0,Optional.of(receipt));
   assertThrows(IllegalArgumentException.class,()->ModernTracePipeline.referenceRows(fake,run.request(),fakeRun));
  }
 }
 @Test void cancellationDuringNativeAndFinalizationRetainsFactualWorkAndMemoryOwnership(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  for(boolean finalization:new boolean[]{false,true})try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var calls=new AtomicInteger();CancellationProbe probe=()->StackWalker.getInstance().walk(frames->frames.anyMatch(f->finalization?f.getClassName().endsWith(".FinalGeometryEvaluator"):f.getMethodName().equals("metricEdgeCache")))&&calls.incrementAndGet()>=(finalization?1:2);
   var run=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),probe,owner).value();
   assertEquals(Outcome.CANCELLED,run.outcome());assertEquals(1,run.nativeCalls());assertTrue(run.finalized().isEmpty());assertTrue(run.nativeRun().orElseThrow().result().evaluatedStates()>0);
   assertEquals(run.nativeRun().orElseThrow().usage(),run.actualWork());assertTrue(owner.currentBytes()>0,"Retained native result remains charged until caller settlement");
  }
 }
 @Test void finalizationMemoryIsReservedBeforeNativeEntry(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  try(var owner=new AttemptMemoryLedger(1).rootOwner()) {
   var run=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
   assertEquals(Outcome.RESOURCE_LIMIT,run.outcome());assertEquals(0,run.nativeCalls());assertTrue(run.nativeRun().isEmpty());assertEquals(TraceWorkUsage.none(),run.actualWork());
  }
 }
 @Test void repeatedOperationsScopeGeneratedIdsAndSealedBExecutionRefusesOtherParent(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var adapter=new RefinementEngineAdapter(TrackerMode.PROBABILISTIC,"native");var one=adapter.run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();var two=adapter.run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
   var a=one.finalized().orElseThrow().routes().get(0).pointIds().stream().filter(FinalRoutePointId.GeneratedCandidatePoint.class::isInstance).toList();
   var b=two.finalized().orElseThrow().routes().get(0).pointIds().stream().filter(FinalRoutePointId.GeneratedCandidatePoint.class::isInstance).toList();assertFalse(a.isEmpty());assertTrue(Collections.disjoint(a,b));
   assertThrows(IllegalArgumentException.class,()->new ModernTracePipeline(new CorridorEngineAdapter("native")).finalizeRefined(two.reference(),two.request(),root.evidence(),root.network(),new ModernTracePipeline.Options("native",GeometryCleanupConfig.disabled(),"native",0),one.execution().orElseThrow(),CancellationProbe.NONE));
  }
 }
 @Test void authorizedMovedJunctionStaysPinnedThroughBothNativeEngineOrders(){
  for(var order:List.of(List.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC),List.of(TrackerMode.PROBABILISTIC,TrackerMode.CORRIDOR_AWARE))) {
   var root=movedJunctionRoot();var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
   var points=new ArrayList<MetricPoint>();for(var key:way.nodeKeys())points.add(root.evidence().coordinateFrame().toMetric(((DetachedNode)root.network().primitives().get(key)).coordinate()));
   points.set(0,new MetricPoint(points.get(0).xMeters(),52));for(int i=1;i<4;i++)points.set(i,new MetricPoint(points.get(i).xMeters(),53));
   var arcs=List.of(new ParentArc(0,0,0),new ParentArc(1,1,0),new ParentArc(2,2,0),new ParentArc(3,3,0),new ParentArc(4,3,1));
   var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),candidate(root,"authorized-parent",points,arcs));
   var pinned=parent.frozenAnchors().get(0);String originalHash=root.network().canonicalHash();
   try(var owner=AttemptMemoryLedger.production().rootOwner()) {
    for(var engine:order) {
     var run=new RefinementEngineAdapter(engine,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
     var route=run.finalized().orElseThrow().routes().get(0);assertEquals(pinned,route.hypothesis().points().get(0));
     var plan=new ModernSingleWayEditPlanAdapter().adaptRefined(run,root.evidence(),0);
     assertSame(root.network(),plan.before());assertEquals(originalHash,root.network().canonicalHash());
     var id=PrimitiveKey.existing(PrimitiveKey.Type.WAY,20);assertEquals(((DetachedWay)root.network().primitives().get(id)).nodeKeys(),((DetachedWay)plan.after().primitives().get(id)).nodeKeys());
     var projection=Projections.getProjectionByCode(root.projectionCode());var assignments=new LinkedHashMap<Long,EastNorth>();
     route.existingAssignments().forEach((occurrence,value)->{var geo=root.evidence().coordinateFrame().toGeographic(value);assignments.put(occurrence.nodeKey().id(),projection.latlon2eastNorth(new LatLon(geo.latitudeDegrees(),geo.longitudeDegrees())));});
     var next=candidate(root,route.hypothesis().id(),route.hypothesis().points(),run.finalCorrespondence().get(route.hypothesis().id()),engine,assignments);
     parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),next);
     assertEquals(pinned.xMeters(),parent.frozenAnchors().get(0).xMeters(),1e-7);assertEquals(pinned.yMeters(),parent.frozenAnchors().get(0).yMeters(),1e-7);
    }
   }
   points.set(2,new MetricPoint(points.get(2).xMeters(),75));
   var escaped=candidate(root,"escaped",points,arcs);
   assertThrows(IllegalArgumentException.class,()->new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),escaped));
  }
 }
 @Test void nonuniformParentOccurrenceReconstructionUsesReferenceChainageExactly(){
  var root=RefinementParentFixtures.authority(AlignmentMode.MOVE_EXISTING_NODES);var points=List.of(new MetricPoint(20,50),new MetricPoint(28,53),new MetricPoint(74,55),new MetricPoint(117,52),new MetricPoint(140,50));
  var arcs=List.of(new ParentArc(0,0,0),new ParentArc(1,1,0),new ParentArc(2,2,0),new ParentArc(3,3,0),new ParentArc(4,3,1));
  var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),candidate(root,"nonuniform",points,arcs));
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var run=new RefinementEngineAdapter(TrackerMode.PROBABILISTIC,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();var route=run.finalized().orElseThrow().routes().get(0);
   var rows=run.nativePointReferenceChainages().get(route.rawHypothesis().id());double target=run.reference().occurrenceChainage(1);int edge=0;while(edge<rows.size()-2&&rows.get(edge+1)<target)edge++;
   var expected=ParentTraceReference.interpolate(route.rawHypothesis().points().get(edge),route.rawHypothesis().points().get(edge+1),(target-rows.get(edge))/(rows.get(edge+1)-rows.get(edge)));
   var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());var id=new FinalRoutePointId.ExistingWayNodeOccurrence(way.key(),way.nodeKeys().get(1),1);
   assertEquals(expected,route.assignments().get(id));assertTrue(Math.abs(route.assignments().get(id).xMeters()-50)>10,"Original quarter-length assignment would be around x50");
  }
 }
 private static RefinementRootAuthority movedJunctionRoot(){
  var old=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var request=old.request();var before=old.network();var c=before.closure();
  var values=new LinkedHashMap<>(before.primitives());var way=(DetachedWay)values.get(request.selectedWayKey());var first=way.nodeKeys().get(0);
  var outside=PrimitiveKey.existing(PrimitiveKey.Type.NODE,99);var other=PrimitiveKey.existing(PrimitiveKey.Type.WAY,20);
  values.put(outside,new DetachedNode(outside,old.evidence().coordinateFrame().toGeographic(new MetricPoint(10,45)),Map.of(),false,false));values.put(other,new DetachedWay(other,List.of(outside,first),Map.of("highway","path"),false,false));
  var movable=new HashSet<>(c.movableExistingNodeKeys());movable.add(first);var fixed=new HashSet<>(c.protectedExistingNodeKeys());fixed.remove(first);fixed.add(outside);var editable=new HashSet<>(c.editableExistingKeys());editable.add(first);editable.add(other);
  var authorized=new LinkedHashMap<>(c.editableWayOccurrences());authorized.put(other,List.of(new OccurrenceRange(1,1)));
  var closure=new ClosureDescriptor(c.scope(),c.queryVersion(),values.keySet(),editable,movable,fixed,c.removableExistingNodeKeys(),authorized,c.externalPorts(),c.collisionEnvelope(),c.editRegion(),c.mayCreateNodes(),true,true,true);
  var network=new NetworkSnapshot("moved-junction-root",SnapshotRole.CAPTURED_BEFORE,before.datasetIdentity(),before.sourceGeneration(),closure,values,V022SnapshotFixtures.closedWorldReferrerWatches(values));
  var permissions=new RecoveryPermissions(false,8,8,JunctionPolicy.LEGACY_BOUNDED_MOVE,false);
  var r=new TraceRequest(request.selectedWayKey(),request.selectedRange(),TrackerMode.LEGACY_V02,request.geometryMode(),permissions,request.budgets(),request.evidenceSnapshotId(),request.evidenceContentHash(),network.snapshotId(),network.canonicalHash(),request.settingsHash(),request.parameterHash(),request.samplerId(),request.configuredSampleStepMeters(),request.profileChainage(),request.evidenceResolution());
  var h=old.effectiveConfig().heatmap();var settings=new ManagedHeatmapConfig(h.keyPairId(),h.policy(),h.signature(),h.sessionToken(),h.activity(),h.color(),h.manualLayerName(),h.layerRegex(),h.alignmentMode(),h.trackerMode(),h.verbose(),h.debug(),h.multiColorDetection(),h.aggregateAllColorSchemes(),h.showAggregateIntensityLayer(),h.candidateRatingEnabled(),h.parallelWayAwareness(),h.allowUndownloadedAlignment(),true,h.simplifyEnabled(),h.crossSectionHalfWidthPx(),h.crossSectionStepPx(),h.simplifyTolerancePx(),h.inferenceMode(),h.inferenceZoom(),h.validationZoom(),h.searchHalfWidthMeters(),h.sampleStepMeters(),h.intensitySamplingMode(),h.cacheBuster());
  return new RefinementRootAuthority(network,r,old.evidence(),new AlignmentConfig(settings,old.effectiveConfig().cleanup()),old.projectionCode(),old.sourceIdentity());
 }
 private static RefinementRootAuthority legacyRoot(CorridorPolicyFixture.Fixture fixture) {
  var old=fixture.request();
  var request=new TraceRequest(old.selectedWayKey(),old.selectedRange(),TrackerMode.LEGACY_V02,old.geometryMode(),old.permissions(),old.budgets(),old.evidenceSnapshotId(),old.evidenceContentHash(),old.networkSnapshotId(),old.networkContentHash(),old.settingsHash(),old.parameterHash(),"legacy-analytic",old.configuredSampleStepMeters(),old.profileChainage(),old.evidenceResolution());
  return new RefinementRootAuthority(fixture.network(),request,fixture.evidence(),RefinementSessionTest.config(old.geometryMode()),"EPSG:3857",fixture.evidence().sourceIdentity());
 }
 private static ParentTraceReference vertexParent(RefinementRootAuthority root) {
  var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
  var points=way.nodeKeys().stream().map(k->root.evidence().coordinateFrame().toMetric(((DetachedNode)root.network().primitives().get(k)).coordinate())).toList();
  var arcs=new ArrayList<ParentArc>();for(int i=0;i<points.size();i++)arcs.add(new ParentArc(i,Math.min(i,points.size()-2),i==points.size()-1?1:0));
  var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),candidate(root,"analytic",points,arcs));
  return parent.withSampling(ProfileChainage.measured(parent.points(),root.request().configuredSampleStepMeters()));
 }
 /** Same analytic Gaussian truth/curve, with factual imagery covering full25m square-cap corridor. */
 private static CorridorPolicyFixture.Fixture coveredParabola(CorridorPolicyFixture.Fixture original) {
  int width=160,height=160;var scalar=new double[width*height];var valid=new boolean[scalar.length];Arrays.fill(valid,true);
  for(int y=0;y<height;y++)for(int x=0;x<width;x++)scalar[y*width+x]=Math.exp(-.5*Math.pow((y-64.)/.8,2));
  var field=new ScalarEvidenceField(width,height,scalar,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
  var old=original.evidence();var evidence=new EvidenceSnapshot("covered-analytic",old.coordinateFrame(),old.transform(),old.resolution(),MetricRegion.rectangle(0,0,159,159),MetricRegion.rectangle(-.5,-.5,159.5,159.5),Map.of("native",field),"covered-analytic-source");
  var r=original.request();var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),evidence.snapshotId(),evidence.canonicalHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),evidence.resolution(),r.corridorInput());
  return new CorridorPolicyFixture.Fixture(request,evidence,original.network(),original.options());
 }
 static Candidate candidate(RefinementRootAuthority root,String id,List<MetricPoint> points,List<ParentArc> arcs) {
  return candidate(root,id,points,arcs,TrackerMode.LEGACY_V02,Map.of());
 }
 static Candidate candidate(RefinementRootAuthority root,String id,List<MetricPoint> points,List<ParentArc> arcs,TrackerMode engine,Map<Long,EastNorth> assignments) {
  var projection=Projections.getProjectionByCode(root.projectionCode());var projected=points.stream().map(p->{var geo=root.evidence().coordinateFrame().toGeographic(p);return projection.latlon2eastNorth(new LatLon(geo.latitudeDegrees(),geo.longitudeDegrees()));}).toList();
  var c=new CenterlineCandidate(id,1,List.of(),List.of(),projected,projected,assignments,List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),List.of());
  var result=new AlignmentResult(RefinementParentFixtures.selection(root),null,List.of(c),projected,projected,List.of(),null,null,List.of(new DetectorAttempt("native","native",engine,DetectorAttemptStatus.APPLICABLE,List.of(id),"","")),List.of(c));
  return new Candidate(new CandidateId(id),result,arcs,id);
 }
}

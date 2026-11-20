package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
class RefinementEngineAdapterTest {
 @Test void actualAPreciseRefinesSelectedParentAndFinalizesAgainstOriginalAuthority(){run(TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE);}
 @Test void actualAMoveRefinesSelectedParentAndAssessesExistingNodeOutput(){run(TrackerMode.CORRIDOR_AWARE,AlignmentMode.MOVE_EXISTING_NODES);}
 @Test void actualBPreciseRefinesSelectedParentAndFinalizesAgainstOriginalAuthority(){run(TrackerMode.PROBABILISTIC,AlignmentMode.PRECISE_SHAPE);}
 @Test void actualBMoveRefinesSelectedParentAndAssessesExistingNodeOutput(){run(TrackerMode.PROBABILISTIC,AlignmentMode.MOVE_EXISTING_NODES);}
 private void run(TrackerMode engine,AlignmentMode mode){
  var root=RefinementParentFixtures.authority(mode);var hash=root.network().canonicalHash();
  var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var accounted=new RefinementEngineAdapter(engine,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner);
   try(var resultOwner=accounted.owner()) {
    var run=accounted.value();assertEquals(1,run.nativeCalls());assertTrue(run.unavailable().isEmpty());
    var nativeRun=run.nativeRun().orElseThrow();assertEquals(engine,nativeRun.result().engine());assertFalse(nativeRun.result().hypotheses().isEmpty(),nativeRun.result().explanation());
    assertTrue(nativeRun.usage().transitions()>0);assertEquals(mode,run.request().geometryMode());
    assertEquals(root.request().permissions(),run.request().permissions());assertEquals(hash,run.request().networkContentHash());
    if(engine==TrackerMode.CORRIDOR_AWARE)nativeRun.corridorReceipt().orElseThrow().requireBinding(run.request(),root.evidence(),root.network(),nativeRun.result());
    var result=run.finalized().orElseThrow();assertEquals(nativeRun.result().hypotheses().size(),result.routes().size(),"No implicit smoothing sibling");
    var route=result.routes().get(0);assertTrue(route.rawHypothesis().points().stream().anyMatch(p->p.yMeters()>53.2));
    assertEquals(parent.frozenAnchors().get(0),route.hypothesis().points().get(0));
    assertEquals(parent.frozenAnchors().get(4),route.hypothesis().points().get(route.pointIds().size()-1));
    assertEquals(5,run.finalCorrespondence().get(route.hypothesis().id()).size());
    assertEquals(route.rawHypothesis().points().size(),run.nativePointReferenceChainages().get(route.hypothesis().id()).size());
    if(mode==AlignmentMode.MOVE_EXISTING_NODES){assertEquals(5,route.pointIds().size());assertEquals(List.of(0,1,2,3,4),route.pointIds().stream().map(i->((FinalRoutePointId.ExistingWayNodeOccurrence)i).originalOccurrenceIndex()).toList());assertEquals(5,route.existingAssignments().size());}
    assertEquals(hash,root.network().canonicalHash());assertSame(root.network(),parent.root().network());
   }
  }
 }

 @Test void multiNodePreciseBRemovalHasAnOriginalAuthorityEditPlan(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var run=new RefinementEngineAdapter(TrackerMode.PROBABILISTIC,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
   var plan=new ModernSingleWayEditPlanAdapter().adaptRefined(run,root.evidence(),0);
   assertSame(root.network(),plan.before());var after=(DetachedWay)plan.after().primitives().get(root.request().selectedWayKey());
   var original=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());for(var key:original.nodeKeys().subList(1,4))assertFalse(after.nodeKeys().contains(key));
  }
 }
 @Test void cleanupEnabledRootStillRunsOnlyOneUnsmoothedAStage(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE,true,false,false);assertFalse(root.effectiveConfig().cleanup().isDisabled());
  var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var run=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
   assertEquals(1,run.nativeCalls());assertEquals(run.nativeRun().orElseThrow().result().hypotheses().size(),run.finalized().orElseThrow().routes().size());
   assertTrue(run.finalized().orElseThrow().routes().stream().allMatch(r->r.lineage().variant()==ModernTracePipeline.RouteVariant.BASE));
  }
 }
 @Test void genuinelyUnrepresentableMoveRetainsInspectableBlockedNativeCurve(){
  var root=RefinementParentFixtures.authority(AlignmentMode.MOVE_EXISTING_NODES,false,true,false);var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var run=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
   var route=run.finalized().orElseThrow().routes().get(0);assertTrue(route.rawHypothesis().points().size()>2);assertEquals(2,route.pointIds().size());
   assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Disposition.HARD_BLOCKED,route.quality().disposition());
   assertTrue(route.quality().findings().stream().anyMatch(f->f.code()==org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.FindingCode.PRECISE_SHAPE_REQUIRED));
  }
 }
 @Test void preCancelledOperationRecordsZeroCallsWithoutInventingNativeFailure(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var run=new RefinementEngineAdapter(TrackerMode.PROBABILISTIC,"native").run(parent,root.evidence(),TraceBudgets.defaults(),()->{throw new java.util.concurrent.CancellationException();},owner).value();
   assertEquals(0,run.nativeCalls());assertTrue(run.nativeRun().isEmpty());assertEquals(TraceWorkUsage.none(),run.actualWork());
  }
 }
 @Test void nativeResourceReceiptIsRetainedAndNeverFinalizedAsACompleteStage(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var run=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),new TraceBudgets(root.request().budgets().maximumStatesPerProfile(),1,1,1,1),CancellationProbe.NONE,owner).value();
   assertEquals(1,run.nativeCalls());assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT,run.nativeRun().orElseThrow().result().status());assertTrue(run.finalized().isEmpty());
   assertEquals(run.nativeRun().orElseThrow().usage(),run.actualWork());assertEquals(0,run.nativeRun().orElseThrow().corridorReceipt().orElseThrow().totals().solverStarts());
  }
 }

 @Test void removedPreciseCutBoundariesRemainExactAndNeverBecomeObservedEvidence(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue")).slice(new OccurrenceRange(1,3));
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var run=new RefinementEngineAdapter(TrackerMode.PROBABILISTIC,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
   var route=run.finalized().orElseThrow().routes().get(0);assertInstanceOf(FinalRoutePointId.GeneratedCandidatePoint.class,route.pointIds().get(0));
   assertEquals(run.reference().frozenAnchors().get(1),route.hypothesis().points().get(0));
   assertEquals(run.reference().frozenAnchors().get(3),route.hypothesis().points().get(route.pointIds().size()-1));
   assertEquals(ObservationOwnership.FIXED_TOPOLOGY_ONLY,route.hypothesis().support().get(0));
   assertEquals(ObservationOwnership.FIXED_TOPOLOGY_ONLY,route.hypothesis().support().get(route.pointIds().size()-1));
   assertEquals(ObservationOwnership.FIXED_TOPOLOGY_ONLY,route.sourceOwnership().get(route.pointIds().get(0)));
  }
 }
}

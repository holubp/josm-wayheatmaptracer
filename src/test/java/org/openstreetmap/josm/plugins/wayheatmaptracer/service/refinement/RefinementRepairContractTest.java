package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
class RefinementRepairContractTest {
 @Test void nativeAScratchCannotBypassTheSharedLedgerEvenWhenFinalizationFits(){
  var root=largeRoot();var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  var request=parent.request(TrackerMode.CORRIDOR_AWARE,root.evidence(),TraceBudgets.defaults(),1);
  long cap=(16384L+2048L*(request.profileChainage().cumulativeGroundMeters().size()+request.selectedRange().size()))*request.budgets().maximumDistinctAlternatives();
  var ledger=new AttemptMemoryLedger(cap);
  try(var owner=ledger.rootOwner()) {
   var run=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,owner).value();
   assertEquals(Outcome.RESOURCE_LIMIT,run.outcome(),"Native allocations must participate in the same cap as finalization");
   assertTrue(run.finalized().isEmpty());assertTrue(ledger.peakBytes()<=cap);
   if(run.nativeRun().isPresent())assertTrue(run.nativeRun().orElseThrow().corridorReceipt().orElseThrow().sampling().isEmpty(),"Refuse before scalar materialization");
  }
  assertEquals(0,ledger.currentBytes());
 }
 @Test void foreignLegacySourceCannotAcquireCurrentRootAuthority(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var p=RefinementParentFixtures.parent(root,"blue");var r=p.result();
  var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());var nodes=new ArrayList<Node>();
  for(int i=0;i<way.nodeKeys().size();i++){var c=((DetachedNode)root.network().primitives().get(way.nodeKeys().get(i))).coordinate();var n=new Node(101+i,1);n.setCoor(new LatLon(c.latitudeDegrees(),c.longitudeDegrees()));nodes.add(n);}
  var foreign=new Way(999,1);foreign.setNodes(nodes);var selection=new SelectionContext(foreign,0,4,nodes,Set.of());
  var result=new AlignmentResult(selection,r.capturedHeatmap(),r.candidates(),r.sourcePolyline(),r.previewPolyline(),r.nodeMoves(),r.diagnostics(),r.tileMosaics(),r.detectorAttempts(),r.applicableCandidates());
  var candidate=new Candidate(p.id(),result,p.correspondence(),"foreign");
  assertThrows(ParentReferenceBinder.Refused.class,()->new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),candidate));
 }
 @Test void missingForeignSequenceCoordinateAndRangeProvenanceRefuseButFrozenLegacySurvivesHandleMutation(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var original=RefinementParentFixtures.parent(root,"blue");
  assertTrue(original.payload().nativeGeometry().stream().allMatch(c->c.proposedNodePositions().isEmpty()),"Real Legacy compatibility: no modern assignment map");
  new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),original);
  for(int kind=0;kind<5;kind++) {
   var selection=RefinementParentFixtures.selection(root);var nodes=new ArrayList<>(selection.way().getNodes());
   if(kind==1){var n=new Node(101,1);n.setCoor(nodes.get(1).getCoor());nodes.set(1,n);selection.way().setNodes(nodes);}
   if(kind==2)nodes.get(2).setCoor(new LatLon(nodes.get(2).lat()+.000001,nodes.get(2).lon()));
   SelectionContext altered=kind==0?null:kind==3?new SelectionContext(selection.way(),1,4,List.copyOf(nodes.subList(1,5)),Set.of())
       :kind==4?new SelectionContext(selection.way(),0,4,List.of(nodes.get(0),nodes.get(2),nodes.get(1),nodes.get(3),nodes.get(4)),Set.of())
       :new SelectionContext(selection.way(),0,4,nodes,Set.of());
   var r=original.result();var result=new AlignmentResult(altered,r.capturedHeatmap(),r.candidates(),r.sourcePolyline(),r.previewPolyline(),r.nodeMoves(),r.diagnostics(),r.tileMosaics(),r.detectorAttempts(),r.applicableCandidates());
   var candidate=new Candidate(original.id(),result,original.correspondence(),"mismatch-"+kind);
   var refused=assertThrows(ParentReferenceBinder.Refused.class,()->new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),candidate));
   assertEquals(kind==0?ParentReferenceBinder.Reason.SOURCE_PROVENANCE_UNAVAILABLE:ParentReferenceBinder.Reason.SOURCE_PROVENANCE_MISMATCH,refused.reason());
  }
  // The retained result still has diagnostic live handles; binder authority uses only the earlier frozen bytes.
  var changed=original.result();changed.selection().way().getNode(2).setCoor(new LatLon(0,0));
  changed.selection().way().setNodes(List.of(changed.selection().way().firstNode(),changed.selection().way().lastNode()));
  new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),original);
  var childPayload=RefinementPayload.captureDescendant(changed,original.payload(),1_000_000);
  assertSame(original.payload().sourceSelection().orElseThrow(),childPayload.sourceSelection().orElseThrow());
  var child=new Candidate(original.id(),childPayload,original.correspondence(),"descendant");
  var bound=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),child);
  assertEquals(root.request().selectedRange(),bound.originalRange());
  assertEquals(childPayload.sourceSelection().orElseThrow().wayId(),root.request().selectedWayKey().id());
 }
 @Test void usableLargeNativeAAndCancelledSamplerRetainSharedChargesUntilSettlement() throws Exception {
  var root=largeRoot();var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  for(boolean cancel:new boolean[]{false,true}) {
   var ledger=AttemptMemoryLedger.production();
   try(var owner=ledger.rootOwner()) {
    CancellationProbe probe=cancel?()->StackWalker.getInstance().walk(frames->frames.anyMatch(f->f.getClassName().endsWith(".ScalarIntensityField")&&f.getMethodName().equals("fromEvidence"))):CancellationProbe.NONE;
    var accounted=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),probe,owner);var run=accounted.value();
    assertEquals(cancel?Outcome.CANCELLED:Outcome.COMPLETE,run.outcome());assertEquals(1,run.nativeCalls());
    var actual=run.nativeRun().orElseThrow();var receipt=actual.corridorReceipt().orElseThrow();
    assertTrue(owner.currentBytes()>6_289_409L,"L0 payload and its live reservation cannot disappear before settlement");
    assertEquals(ledger.peakBytes(),actual.peakAdditionalRetainedBytes());
    if(cancel){assertTrue(run.finalized().isEmpty());assertTrue(receipt.sampling().isEmpty());}
    else {
     assertTrue(run.finalized().isPresent());assertTrue(owner.currentBytes()>receipt.sampling().orElseThrow().materializationEstimateBytes(),"Native sampler, strict authority, tracker and optimizer overlap in the same ledger");assertTrue(run.actualWork().transitions()>0);
     var derived=parent.request(TrackerMode.CORRIDOR_AWARE,root.evidence(),TraceBudgets.defaults(),1);
     long finalBytes=(16384L+2048L*(derived.profileChainage().cumulativeGroundMeters().size()+derived.selectedRange().size()))*derived.budgets().maximumDistinctAlternatives();
     long samplerOnlyCap=parent.bindingScratchBytes(root.evidence())+finalBytes+receipt.sampling().orElseThrow().materializationEstimateBytes()-1;
     var limitedLedger=new AttemptMemoryLedger(samplerOnlyCap);
     try(var limitedOwner=limitedLedger.rootOwner()) {
      var limited=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),CancellationProbe.NONE,limitedOwner).value();
      assertEquals(Outcome.RESOURCE_LIMIT,limited.outcome());assertEquals(1,limited.nativeCalls());
      assertTrue(limited.nativeRun().orElseThrow().corridorReceipt().orElseThrow().sampling().isEmpty(),"Shared validation workspace and finalization fit; sampler must refuse before materialization");
      assertTrue(limitedLedger.peakBytes()<=samplerOnlyCap);
     }
     assertEquals(0,limitedLedger.currentBytes());
    }
    javax.swing.SwingUtilities.invokeAndWait(accounted.owner()::close);
    assertEquals(0,ledger.currentBytes(),"EDT settlement releases the operation subtree after the worker relinquishes it");
   }
   assertEquals(0,ledger.currentBytes());
  }
 }
 @Test void competingSharedReservationStopsOptimizerAfterSamplingWithoutLosingActualReceipt(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  long cap=64L*1024*1024;var ledger=new AttemptMemoryLedger(cap);var claimed=new java.util.concurrent.atomic.AtomicBoolean();
  try(var owner=ledger.rootOwner()) {
   CancellationProbe probe=()->{
    if(StackWalker.getInstance().walk(frames->frames.anyMatch(f->f.getMethodName().equals("prepareOriginalDomain")))&&claimed.compareAndSet(false,true))owner.reserve(cap-owner.currentBytes());
    return false;
   };
   var run=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),probe,owner).value();
   assertTrue(claimed.get());assertEquals(Outcome.RESOURCE_LIMIT,run.outcome());assertEquals(1,run.nativeCalls());assertTrue(run.finalized().isEmpty());
   assertTrue(run.nativeRun().orElseThrow().corridorReceipt().orElseThrow().sampling().isPresent(),"Sampling really happened before shared optimizer refusal");
   assertEquals(cap,owner.currentBytes());assertEquals(cap,ledger.peakBytes());
  }
  assertEquals(0,ledger.currentBytes());
 }
 @Test void ownedAndHistoricalNativeAEntriesKeepTheExactObjectiveOutputAndReceiptBinding(){
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  var request=parent.request(TrackerMode.CORRIDOR_AWARE,root.evidence(),TraceBudgets.defaults(),1);var adapter=new CorridorEngineAdapter("native");
  var historical=adapter.traceRefinedWithUsage(parent,request,root.evidence(),root.network(),CancellationProbe.NONE);
  try(var owner=AttemptMemoryLedger.production().rootOwner()) {
   var owned=adapter.traceRefinedWithUsage(parent,request,root.evidence(),root.network(),CancellationProbe.NONE,owner).value();
   assertEquals(historical.result(),owned.result());assertEquals(historical.usage(),owned.usage());
   historical.corridorReceipt().orElseThrow().requireBinding(request,root.evidence(),root.network(),historical.result());
   owned.corridorReceipt().orElseThrow().requireBinding(request,root.evidence(),root.network(),owned.result());
   assertEquals(historical.corridorReceipt().orElseThrow().candidates().keySet(),owned.corridorReceipt().orElseThrow().candidates().keySet());
   assertTrue(owned.peakAdditionalRetainedBytes()>historical.peakAdditionalRetainedBytes());
  }
 }
 @Test void directOwnedEntriesPreflightBeforeBindingAndWorkspaceBorrowingIsOwnerAndThreadBound() throws Exception {
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  for(var engine:List.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC)) {
   var request=parent.request(engine,root.evidence(),TraceBudgets.defaults(),1);var ledger=new AttemptMemoryLedger(1);var probes=new java.util.concurrent.atomic.AtomicInteger();
   try(var owner=ledger.rootOwner()) {
    CancellationProbe probe=()->{probes.incrementAndGet();return false;};
    assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->{
     if(engine==TrackerMode.CORRIDOR_AWARE)new CorridorEngineAdapter("native").traceRefinedWithUsage(parent,request,root.evidence(),root.network(),probe,owner);
     else new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticTraceEngine("native").traceRefinedWithUsage(parent,request,root.evidence(),root.network(),probe,owner);
    });
    assertEquals(0,probes.get(),"Direct entry must claim hash workspace before entering request binding");assertEquals(0,ledger.currentBytes());
   }
  }
  var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();var workspace=parent.reserveBindingWorkspace(root.evidence(),owner);
  var other=owner.child("another-owner");assertThrows(IllegalArgumentException.class,()->workspace.requireCapacity(parent,root.evidence(),other));
  var rejected=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  var thread=new Thread(()->{try{workspace.requireCapacity(parent,root.evidence(),owner);}catch(Throwable failure){rejected.set(failure);}});
  thread.start();thread.join();assertInstanceOf(IllegalArgumentException.class,rejected.get());
  workspace.requireCapacity(parent,root.evidence(),owner);
  javax.swing.SwingUtilities.invokeAndWait(owner::close);assertEquals(0,ledger.currentBytes(),"Borrowing affinity must never prevent cross-thread settlement");
 }
 static RefinementRootAuthority largeRoot(){
  var old=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);int side=1024;double[] values=new double[side*side];boolean[] valid=new boolean[values.length];Arrays.fill(valid,true);
  for(int y=0;y<side;y++)for(int x=0;x<side;x++)values[y*side+x]=.02+.98*Math.exp(-.5*Math.pow((y-54.)/1.5,2));
  var e=old.evidence();var field=new ScalarEvidenceField(side,side,values,valid,e.fields().get("native").lineage());
  var evidence=new EvidenceSnapshot("large-native",e.coordinateFrame(),e.transform(),e.resolution(),e.decisionRegion(),e.evidenceRegion(),Map.of("native",field),e.sourceIdentity());
  var r=old.request();var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),evidence.snapshotId(),evidence.canonicalHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),evidence.resolution());
  return new RefinementRootAuthority(old.network(),request,evidence,old.effectiveConfig(),old.projectionCode(),old.sourceIdentity());
 }
}

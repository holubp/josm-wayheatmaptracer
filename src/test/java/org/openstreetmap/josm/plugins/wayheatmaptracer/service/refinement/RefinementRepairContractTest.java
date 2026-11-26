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
 @Test void nativeOutputAllowanceRequiresItsLiveExactBackingAndWorker() throws Exception {
  var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);
  var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  var request=parent.request(TrackerMode.CORRIDOR_AWARE,root.evidence(),TraceBudgets.defaults(),1);
  long bytes=(16384L+2048L*(request.profileChainage().cumulativeGroundMeters().size()+request.selectedRange().size()))*request.budgets().maximumDistinctAlternatives();
  var ledger=AttemptMemoryLedger.production();
  try(var owner=ledger.rootOwner();var foreign=owner.child("foreign")) {
   var backing=owner.reserve(bytes);
   var allowance=outputAllowance(owner,request,root.evidence(),backing);
   long before=owner.currentBytes();assertTrue(backing.isActiveFor(owner));assertFalse(backing.isActiveFor(foreign));
   allowance.requireCapacity(owner,request,root.evidence());
   assertThrows(IllegalArgumentException.class,()->allowance.requireCapacity(foreign,request,root.evidence()));
   var otherRequest=parent.request(TrackerMode.CORRIDOR_AWARE,root.evidence(),TraceBudgets.defaults(),1);
   assertThrows(IllegalArgumentException.class,()->allowance.requireCapacity(owner,otherRequest,root.evidence()));
   var otherEvidence=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE).evidence();
   assertThrows(IllegalArgumentException.class,()->allowance.requireCapacity(owner,request,otherEvidence));
   var wrongThread=java.util.concurrent.CompletableFuture.runAsync(()->allowance.requireCapacity(owner,request,root.evidence()));
   assertInstanceOf(IllegalArgumentException.class,assertThrows(java.util.concurrent.CompletionException.class,wrongThread::join).getCause());
   assertEquals(before,owner.currentBytes(),"Capacity checks never allocate, adopt or release");
   allowance.close();assertTrue(backing.isActiveFor(owner));
   assertThrows(IllegalArgumentException.class,()->allowance.requireCapacity(owner,request,root.evidence()));
   var workspace=parent.reserveBindingWorkspace(root.evidence(),owner);long checkedBytes=owner.currentBytes();
   var probes=new java.util.concurrent.atomic.AtomicInteger();
   assertThrows(IllegalArgumentException.class,()->new CorridorEngineAdapter("native").traceRefinedWithUsage(parent,request,root.evidence(),root.network(),()->{probes.incrementAndGet();return false;},owner,workspace,allowance));
   assertEquals(0,probes.get());assertEquals(checkedBytes,owner.currentBytes(),"Closed output allowance refuses before binding/native allocation");
   var closedBacking=outputAllowance(owner,request,root.evidence(),backing);backing.close();assertFalse(backing.isActiveFor(owner));
   assertThrows(IllegalArgumentException.class,()->closedBacking.requireCapacity(owner,request,root.evidence()));
   try(var insufficient=owner.reserve(bytes-1)) {
    var small=outputAllowance(owner,request,root.evidence(),insufficient);
    assertThrows(IllegalArgumentException.class,()->small.requireCapacity(owner,request,root.evidence()));
   }
   try(var adopted=owner.reserve(bytes)) {
    var stale=outputAllowance(owner,request,root.evidence(),adopted);adopted.adopt(new Object());
    assertFalse(adopted.isActiveFor(owner));assertThrows(IllegalArgumentException.class,()->stale.requireCapacity(owner,request,root.evidence()));
   }
   var closedOwner=owner.child("closed");var reservation=closedOwner.reserve(bytes);
   var stale=outputAllowance(closedOwner,request,root.evidence(),reservation);closedOwner.close();
   assertFalse(reservation.isActiveFor(closedOwner));assertThrows(IllegalArgumentException.class,()->stale.requireCapacity(closedOwner,request,root.evidence()));
  }
  assertEquals(0,ledger.currentBytes());
 }
 private static RefinementEngineAdapter.NativeOutputAllowance outputAllowance(AttemptMemoryLedger.Owner owner,TraceRequest request,
     EvidenceSnapshot evidence,AttemptMemoryLedger.Reservation reservation) throws Exception {
  var ctor=RefinementEngineAdapter.NativeOutputAllowance.class.getDeclaredConstructor(AttemptMemoryLedger.Owner.class,TraceRequest.class,EvidenceSnapshot.class,AttemptMemoryLedger.Reservation.class);
  ctor.setAccessible(true);return ctor.newInstance(owner,request,evidence,reservation);
 }
 @Test void refinementASettlesOnlySamplerAndCertificateAfterInvocation() {
  var root=largeRoot();var parent=new ParentReferenceBinder().bind(root,new StageId(UUID.randomUUID()),RefinementParentFixtures.parent(root,"blue"));
  var ledger=AttemptMemoryLedger.production();
  try(var owner=ledger.rootOwner();var directOwner=AttemptMemoryLedger.production().rootOwner()) {
   var trackingBytes=new java.util.concurrent.atomic.AtomicLong();
   CancellationProbe probe=()->{
    if(StackWalker.getInstance().walk(frames->frames.anyMatch(f->f.getMethodName().equals("trackDetailed"))))trackingBytes.accumulateAndGet(owner.currentBytes(),Math::max);
    return false;
   };
   var accounted=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),probe,owner);
   var run=accounted.value();assertEquals(Outcome.COMPLETE,run.outcome());
   var actual=run.nativeRun().orElseThrow();var receipt=actual.corridorReceipt().orElseThrow();
   var request=run.request();long finalBytes=(16384L+2048L*(request.profileChainage().cumulativeGroundMeters().size()+request.selectedRange().size()))*request.budgets().maximumDistinctAlternatives();
   var field=root.evidence().fields().get("native");
   long certificate=65536L+2L*(5L*field.width()*field.height()+(long)(field.width()-1)*(field.height()-1));
   long scratch=receipt.sampling().orElseThrow().materializationEstimateBytes()+certificate;
   assertTrue(trackingBytes.get()>=parent.bindingScratchBytes(root.evidence())+finalBytes+scratch,"Full sampler and certificate bounds survive through tracking");
   var direct=new CorridorEngineAdapter("native").traceRefinedWithUsage(run.reference(),request,root.evidence(),root.network(),CancellationProbe.NONE,directOwner).value();
   assertEquals(direct.result(),actual.result());assertEquals(direct.usage(),actual.usage());
   assertEquals(direct.corridorReceipt().orElseThrow().totals(),receipt.totals());
   assertEquals(directOwner.currentBytes()+finalBytes-scratch,owner.currentBytes(),"Only complete sampler/certificate scratch settles; all tracker/domain and output charges remain");
   accounted.owner().close();assertEquals(0,ledger.currentBytes());
  }
 }
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
  long retainedOnSuccess=0;TraceWorkUsage completedWork=null;var samplerEstimate=new java.util.concurrent.atomic.AtomicLong();
  for(int phase:new int[]{0,1,2}) {
   boolean cancel=phase!=0;
   var ledger=AttemptMemoryLedger.production();
   try(var owner=ledger.rootOwner()) {
    var duringSampling=new java.util.concurrent.atomic.AtomicLong();
    CancellationProbe probe=cancel?()->{
     boolean stop=StackWalker.getInstance().walk(frames->frames.anyMatch(f->phase==1
        ?f.getClassName().endsWith(".ScalarIntensityField")&&f.getMethodName().equals("fromEvidence")
        :f.getMethodName().equals("finalizeRefined")));
     if(stop&&phase==1)duringSampling.set(owner.currentBytes());return stop;
    }:CancellationProbe.NONE;
    var accounted=new RefinementEngineAdapter(TrackerMode.CORRIDOR_AWARE,"native").run(parent,root.evidence(),TraceBudgets.defaults(),probe,owner);var run=accounted.value();
    assertEquals(cancel?Outcome.CANCELLED:Outcome.COMPLETE,run.outcome());assertEquals(1,run.nativeCalls());
    var actual=run.nativeRun().orElseThrow();var receipt=actual.corridorReceipt().orElseThrow();
    long finalBytes=(16384L+2048L*(run.request().profileChainage().cumulativeGroundMeters().size()+run.request().selectedRange().size()))*run.request().budgets().maximumDistinctAlternatives();
    long outputBase=parent.bindingScratchBytes(root.evidence())+finalBytes;
    assertTrue(owner.currentBytes()>=outputBase,"Full binding/output allowance survives native scratch settlement");
    assertEquals(ledger.peakBytes(),actual.peakAdditionalRetainedBytes());
    if(cancel){
     assertTrue(run.finalized().isEmpty());
     if(phase==1) {
      assertTrue(receipt.sampling().isEmpty());assertEquals(outputBase,owner.currentBytes());
      assertTrue(duringSampling.get()>=outputBase+samplerEstimate.get(),"Full original sampler bound remains live at cancellation");
     } else {
      assertTrue(receipt.sampling().isPresent());assertEquals(completedWork,run.actualWork());
      assertEquals(retainedOnSuccess,owner.currentBytes(),"Finalization cancellation retains every tracker/domain/output charge");
     }
    }
    else {
     assertTrue(run.finalized().isPresent());assertTrue(run.actualWork().transitions()>0);
     retainedOnSuccess=owner.currentBytes();completedWork=run.actualWork();samplerEstimate.set(receipt.sampling().orElseThrow().materializationEstimateBytes());
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
   var field=root.evidence().fields().get("native");
   long scratch=run.nativeRun().orElseThrow().corridorReceipt().orElseThrow().sampling().orElseThrow().materializationEstimateBytes()
       +65536L+2L*(5L*field.width()*field.height()+(long)(field.width()-1)*(field.height()-1));
   assertEquals(cap-scratch,owner.currentBytes(),"Competing and tracker/domain charges persist; only expired sampler/certificate scratch settles");assertEquals(cap,ledger.peakBytes());
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

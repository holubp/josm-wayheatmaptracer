package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Serializer/registry tests use detached synthetic retained values, not claimed native execution. */
class RuntimeRefinementDiagnosticsTest {
 @TempDir Path directory;
 static RefinementRootAuthority root(){
  var a=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var r=a.request();
  var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),"a".repeat(64),"b".repeat(64),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
  return new RefinementRootAuthority(a.network(),request,a.evidence(),a.effectiveConfig(),a.projectionCode(),a.sourceIdentity());
 }
 @Test void actualRegistryZipKeepsImmutableOriginalSeparateFromSelectedNoninitialLegacy() throws Exception {
  var authority=root();var blue=RefinementParentFixtures.parent(authority,"blue");var purple=RefinementParentFixtures.parent(authority,"purple");
  var stage=Stage.root(List.of(blue,purple),purple.id());var session=new SessionId(UUID.randomUUID());
  var memory=new RefinementDiagnosticsMemory(new AttemptMemoryLedger(4*1024*1024));
  var producer=new DiagnosticsRegistry.RefinementProducer(){public boolean isCurrent(){return true;}public void prepareExport(){}};
  var owner=DiagnosticsRegistry.claimRefinement(producer);
  try(var admission=memory.reserveExport(2*1024*1024)){
   var bundle=RuntimeRefinementDiagnostics.capture(authority,List.of(stage),stage,session,blue.id().value(),Optional.empty(),Optional.empty(),RefinementAttemptDiagnostics.Availability.NONE,"preview-open",Optional.empty(),Optional.empty(),Optional.empty());
   Path encoded=directory.resolve("encoded.zip");Format15BundleWriter.write(bundle,encoded);
   assertTrue(DiagnosticsRegistry.publishRefinement(owner,admission.read(encoded),true));
  }
  memory.closeWorkflow();assertEquals(0,memory.workflowOwnedBytes());assertTrue(memory.registryOwnedBytes()>0);
  Path exported=directory.resolve("last-slide.zip");DiagnosticsRegistry.writeLatest(exported.toFile());
  var archive=Format15ArchiveReader.read(exported);
  var restored=new Format15Bundle(archive.buildIdentity(),archive.sourceIdentityHash(),archive.parameterHash(),archive.artifacts());
  var doc=RefinementLineageCodec.readBound(restored,stage.id(),purple.id()).orElseThrow();
  assertEquals(authority.network().canonicalHash(),doc.originalOsm().immutableIdentity());
  assertEquals(purple.id(),doc.binding().currentChoice());assertEquals(2,doc.stages().get(0).candidates().size());
  assertNotEquals(doc.originalOsm().geometry().sha256(),doc.stages().get(0).candidates().get(1).geometry().sha256());
  assertTrue(new String(restored.artifact("review-state.json").bytes(),java.nio.charset.StandardCharsets.UTF_8).contains("blue-native"));
  assertFalse(restored.artifactNames().stream().anyMatch(n->n.contains("frozen-input")));
  DiagnosticsRegistry.setLastBundle(null);assertEquals(0,memory.totalBytes());
 }
 @Test void capturedSettingsChecksumChangesWithFrozenWidthAndRejectsAnOlderAttemptBinding() {
  var original=root();var baseline=RuntimeRefinementDiagnostics.frozenSettings(original);
  var changed=new RefinementRootAuthority(original.network(),original.request(),original.evidence(),original.effectiveConfig().withSearchHalfWidthMetersOverride(10),original.projectionCode(),original.sourceIdentity());
  assertNotEquals(baseline.sha256(),RuntimeRefinementDiagnostics.frozenSettings(changed).sha256());
  assertEquals("b".repeat(64),original.request().parameterHash(),"Native/root request descriptor must remain untouched");
  var candidate=RefinementParentFixtures.parent(original,"blue");var stage=Stage.root(List.of(candidate),candidate.id());var session=new SessionId(UUID.randomUUID());
  var ticket=new OperationTicket(session,1,stage.id(),stage.selected(),TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE);
  var binding=new RefinementAttemptDiagnostics.Binding(ticket,stage.id(),original.sourceIdentity(),original.network().canonicalHash(),baseline.sha256(),original.frozenGeometryIdentity(),"factual-parent");
  var diagnostic=new RefinementAttemptDiagnostics(binding,Optional.empty(),RefinementAttemptDiagnostics.Publication.FAILED,RefinementAttemptDiagnostics.Phase.PREPARATION,RefinementAttemptDiagnostics.Cause.FAILED,0);
  var key=new RefinementLineageCodec.CandidateKey(stage.id(),stage.selected());
  assertDoesNotThrow(()->RuntimeRefinementDiagnostics.capture(original,List.of(stage),stage,session,candidate.id().value(),Optional.of(diagnostic),Optional.of(key),RefinementAttemptDiagnostics.Availability.AVAILABLE,"preview-open",Optional.empty(),Optional.empty(),Optional.empty()));
  assertThrows(IllegalArgumentException.class,()->RuntimeRefinementDiagnostics.capture(changed,List.of(stage),stage,session,candidate.id().value(),Optional.of(diagnostic),Optional.of(key),RefinementAttemptDiagnostics.Availability.AVAILABLE,"preview-open",Optional.empty(),Optional.empty(),Optional.empty()));
 }

 @Test void noChildResourceNoRouteAndCompletedPresentationFailureKeepForwardReceiptsFactual() throws Exception {
  var authority=root();var parent=RefinementParentFixtures.parent(authority,"blue");var root=Stage.root(List.of(parent),parent.id());var session=new SessionId(UUID.randomUUID());
  var first=new OperationTicket(session,1,root.id(),root.selected(),TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE);
  var a=Stage.child(first,List.of(nativeMetadata(parent,"unit-a",receipt(authority,first,Outcome.COMPLETE))),new CandidateId("unit-a"));
  var second=new OperationTicket(session,2,a.id(),a.selected(),TrackerMode.PROBABILISTIC,AlignmentMode.PRECISE_SHAPE);
  var b=Stage.child(second,List.of(nativeMetadata(parent,"unit-b",receipt(authority,second,Outcome.COMPLETE))),new CandidateId("unit-b"));
  for(var outcome:List.of(Outcome.RESOURCE_LIMIT,Outcome.NO_USABLE_RESULT,Outcome.COMPLETE)) {
   var attempt=new OperationTicket(session,3,root.id(),root.selected(),TrackerMode.PROBABILISTIC,AlignmentMode.PRECISE_SHAPE);var factual=receipt(authority,attempt,outcome);
   var publication=outcome==Outcome.COMPLETE?RefinementAttemptDiagnostics.Publication.FAILED:RefinementAttemptDiagnostics.Publication.NO_CHILD;
   var cause=outcome==Outcome.COMPLETE?RefinementAttemptDiagnostics.Cause.PRESENTATION_FAILED:RefinementAttemptDiagnostics.Cause.PLAN_REFUSED;
   var binding=new RefinementAttemptDiagnostics.Binding(attempt,root.id(),authority.sourceIdentity(),authority.network().canonicalHash(),RuntimeRefinementDiagnostics.frozenSettings(authority).sha256(),authority.frozenGeometryIdentity(),factual.parentIdentity());
   var diagnostic=new RefinementAttemptDiagnostics(binding,Optional.of(factual),publication,outcome==Outcome.COMPLETE?RefinementAttemptDiagnostics.Phase.PUBLICATION:RefinementAttemptDiagnostics.Phase.MATERIALIZATION,cause,2);
   var bundle=RuntimeRefinementDiagnostics.capture(authority,List.of(root,a,b),root,session,parent.id().value(),Optional.of(diagnostic),Optional.of(new RefinementLineageCodec.CandidateKey(root.id(),root.selected())),RefinementAttemptDiagnostics.Availability.AVAILABLE,"preview-open",Optional.empty(),Optional.empty(),Optional.empty());
   Path file=directory.resolve(outcome+".zip");Format15BundleWriter.write(bundle,file);var archive=Format15ArchiveReader.read(file);
   assertTrue(archive.capability().supportedLevels().isEmpty(),"Retained output is not a frozen native input or replay proof");
   var restored=new Format15Bundle(archive.buildIdentity(),archive.sourceIdentityHash(),archive.parameterHash(),archive.artifacts());var doc=RefinementLineageCodec.readBound(restored,root.id(),root.selected()).orElseThrow();
   assertEquals(3,doc.stages().size());assertEquals(factual,doc.latestAttempt().orElseThrow());assertEquals(diagnostic,doc.latestPublication().orElseThrow().diagnostic());
   assertEquals(OptionalInt.of(0),doc.latestAttempt().orElseThrow().attempts().get(1).nativeCalls());assertTrue(doc.latestAttempt().orElseThrow().attempts().get(1).actualWork().isEmpty());
  }
 }
 private static Candidate nativeMetadata(Candidate parent,String id,RefinementOperationReceipt receipt) {
  var payload=RefinementPayload.captureNative(parent.payload().result(),parent.payload(),new RefinementPayload.NativeReview(null,null,Optional.of(receipt),List.of(),OptionalInt.empty()),1000000);
  return new Candidate(new CandidateId(id),payload,List.of(),id,"native-"+id,"blue");
 }
 private static RefinementOperationReceipt receipt(RefinementRootAuthority authority,OperationTicket ticket,Outcome outcome) {
  var attempted=new RefinementOperationReceipt.Attempt("blue","blue",RefinementPipeline.AttemptKind.WHOLE,authority.request().selectedRange(),"native-result",Optional.of(outcome),OptionalInt.of(1),Optional.of(new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage(9,17,2,1)),Optional.of("a".repeat(64)),Optional.of("b".repeat(64)),Optional.of("unit-sampler"),Optional.of(outcome==Outcome.RESOURCE_LIMIT?TraceHypothesisSet.Status.RESOURCE_LIMIT:outcome==Outcome.NO_USABLE_RESULT?TraceHypothesisSet.Status.NO_ROUTE:TraceHypothesisSet.Status.COMPLETE),List.of());
  var unattempted=new RefinementOperationReceipt.Attempt("purple","purple",RefinementPipeline.AttemptKind.UNATTEMPTED,authority.request().selectedRange(),"unattempted-resource-limit",Optional.empty(),OptionalInt.of(0),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),List.of());
  return new RefinementOperationReceipt(ticket,"unit-parent",authority.network().canonicalHash(),outcome,List.of(attempted,unattempted),attempted.actualWork(),true);
 }

 @Test void nativeDiagnosticBindingMustNotAcquireAnExportPrivacyGate() throws Exception {
  var original=root();var config=original.effectiveConfig().heatmap();var components=config.getClass().getRecordComponents();var types=new Class<?>[components.length];var values=new Object[components.length];
  for(int i=0;i<components.length;i++){types[i]=components[i].getType();values[i]=components[i].getName().equals("manualLayerName")?"https://example.invalid/tiles?Signature=synthetic-private":components[i].getAccessor().invoke(config);}
  var changed=(ManagedHeatmapConfig)config.getClass().getDeclaredConstructor(types).newInstance(values);
  var authority=new RefinementRootAuthority(original.network(),original.request(),original.evidence(),new AlignmentConfig(changed,original.effectiveConfig().cleanup()),original.projectionCode(),original.sourceIdentity());
  assertThrows(IllegalArgumentException.class,()->RuntimeRefinementDiagnostics.frozenSettings(authority),"Unsafe export text stays refused");
  var hash=assertDoesNotThrow(()->RuntimeRefinementDiagnostics.frozenSettingsIdentity(authority),"Optional export admission cannot block native workflow binding");assertTrue(hash.matches("[a-f0-9]{64}"));
 }

 @Test void preNativeResourceRefusalKeepsKnownZeroCallsAndUnavailableMeasurements() throws Exception {
  var authority=root();var parent=RefinementParentFixtures.parent(authority,"blue");var root=Stage.root(List.of(parent),parent.id());var session=new SessionId(UUID.randomUUID());
  var ticket=new OperationTicket(session,1,root.id(),root.selected(),TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE);
  var attempts=java.util.stream.Stream.of("hot","blue","bluered","purple","gray","all-colors-combined").map(palette->new RefinementOperationReceipt.Attempt(palette,palette,RefinementPipeline.AttemptKind.WHOLE,authority.request().selectedRange(),"preflight-resource-limit",Optional.of(Outcome.RESOURCE_LIMIT),OptionalInt.of(0),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),List.of())).toList();
  var receipt=new RefinementOperationReceipt(ticket,"unit-parent",authority.network().canonicalHash(),Outcome.RESOURCE_LIMIT,attempts,Optional.empty(),false);
  var binding=new RefinementAttemptDiagnostics.Binding(ticket,root.id(),authority.sourceIdentity(),authority.network().canonicalHash(),RuntimeRefinementDiagnostics.frozenSettingsIdentity(authority),authority.frozenGeometryIdentity(),receipt.parentIdentity());
  var diagnostic=new RefinementAttemptDiagnostics(binding,Optional.of(receipt),RefinementAttemptDiagnostics.Publication.NO_CHILD,RefinementAttemptDiagnostics.Phase.PIPELINE,RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT,0);
  var bundle=RuntimeRefinementDiagnostics.capture(authority,List.of(root),root,session,parent.id().value(),Optional.of(diagnostic),Optional.of(new RefinementLineageCodec.CandidateKey(root.id(),root.selected())),RefinementAttemptDiagnostics.Availability.AVAILABLE,"preview-open",Optional.empty(),Optional.empty(),Optional.empty());
  Path file=directory.resolve("pre-native-resource.zip");Format15BundleWriter.write(bundle,file);var archive=Format15ArchiveReader.read(file);var doc=RefinementLineageCodec.read(archive.artifact(RefinementLineageCodec.ARTIFACT).orElseThrow());
  assertEquals(receipt,doc.latestAttempt().orElseThrow());assertTrue(doc.latestAttempt().orElseThrow().actualWork().isEmpty());assertEquals(6,doc.latestAttempt().orElseThrow().attempts().size());
  assertTrue(doc.latestAttempt().orElseThrow().attempts().stream().allMatch(a->a.nativeCalls().equals(OptionalInt.of(0))&&a.actualWork().isEmpty()&&a.nativeStatus().isEmpty()));
  String original=new String(bundle.artifact("original-osm-geographic.csv").bytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(original.contains("wayNamespace,wayIdentity,occurrence"));assertTrue(original.contains("OSM_UNIQUE,10,0,1,true,"));
 }

}

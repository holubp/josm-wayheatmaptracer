package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

/** Actual production Legacy/bootstrap/controller to Last Slide Debug registry boundary. */
class StepwiseRuntimeDiagnosticsTest {
 @TempDir Path directory;
 @BeforeEach void setup()throws Exception{
  Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
  var pane=MainApplication.class.getDeclaredField("contentPanePrivate");pane.setAccessible(true);pane.set(null,new JPanel());DiagnosticsRegistry.setLastBundle(null);
 }
 @AfterEach void clear(){DiagnosticsRegistry.setLastBundle(null);}
 @Test void cancelledActualBootstrapExportsLiteralAuthorityAndNoInventedNativeWork()throws Exception{
  var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),new ManagedTileCache(directory,decoder),decoder,TileReliabilityPolicy.defaults());
      var owners=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)){
   var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var fixture=new Object[1];var workflow=new LegacyRefinementWorkflow[1];var review=new LegacyReviewOwner[1];var ratings=new HashMap<String,org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateRating>();
   try{
    SwingUtilities.invokeAndWait(()->{
     fixture[0]=fixture(coordinator,owners,work,publications);workflow[0]=value(fixture[0],"workflow",LegacyRefinementWorkflow.class);review[0]=value(fixture[0],"review",LegacyReviewOwner.class);
     var original=review[0].originalResult();var first=review[0].selectedCandidate();
     workflow[0].enableRuntimeDiagnostics(()->LastSlideDebugBundle.fromResult(original,review[0].selectedCandidate(),first,"preview-open","",Map.of()),()->ratings);
     ((JButton)find(workflow[0].controls(),"refineA")).doClick();
    });
    work.remove().run();SwingUtilities.invokeAndWait(publications.remove()); // Factual original root bootstrap only.
    SwingUtilities.invokeAndWait(()->((JButton)find(workflow[0].controls(),"cancel")).doClick());
    work.remove().run();SwingUtilities.invokeAndWait(publications.remove());
    Path export=directory.resolve("runtime.zip");
    SwingUtilities.invokeAndWait(()->{
     assertFalse(workflow[0].nativeSelected());assertFalse(workflow[0].busy());assertEquals(0,workflow[0].actualNativeCalls());
     var diagnostic=workflow[0].latestAttemptDiagnostics().orElseThrow();assertEquals(RefinementAttemptDiagnostics.Publication.CANCELLED,diagnostic.publication());assertTrue(diagnostic.pipeline().isEmpty());
     ratings.put(review[0].selectedCandidate().id(),new org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateRating("good",List.of("off-the-line")));
     try{DiagnosticsRegistry.writeLatest(export.toFile());}catch(Exception failure){throw new RuntimeException(failure);}
    });
    var archive=Format15ArchiveReader.read(export);var bundle=new Format15Bundle(archive.buildIdentity(),archive.sourceIdentityHash(),archive.parameterHash(),archive.artifacts());
    var doc=RefinementLineageCodec.read(bundle.artifact(RefinementLineageCodec.ARTIFACT));
    assertEquals(RefinementAttemptDiagnostics.Publication.CANCELLED,doc.latestPublication().orElseThrow().diagnostic().publication());assertTrue(doc.latestAttempt().isEmpty());
    var settings=bundle.artifact("frozen-root-settings.json");assertEquals(settings.sha256(),bundle.parameterHash());
    String json=new String(settings.bytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(json.contains("original-legacy-authority-v1"));assertTrue(json.contains("7.01"));
    assertTrue(bundle.artifactNames().contains("legacy-root-diagnostics.zip"));
    String calibration=new String(bundle.artifact("candidate-ratings.json").bytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(calibration.contains("good"));assertTrue(calibration.contains("off-the-line"));
    SwingUtilities.invokeAndWait(()->{
     coordinator.updateActiveGeneration(new ManagedTileGeneration(8));coordinator.updateActiveGeneration(new ManagedTileGeneration(7));
     var original=review[0].originalResult();
     assertThrows(IllegalStateException.class,()->AlignWayAction.createLegacyReviewCommand(review[0],new AlignmentService(new TileHeatmapSampler(coordinator)),review[0].dataSet(),original.selection(),original,review[0].selectedCandidate(),review[0].originalConfig().heatmap(),null));
     workflow[0].diagnosticsChanged("apply-failed");workflow[0].close();assertEquals(0,workflow[0].workflowOwnedBytes());
    });
    var failed=export("failed-apply-after-close");String failureStatus=new String(failed.artifact("review-state.json").bytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(failureStatus.contains("\"state\":\"apply-failed\""));
    assertEquals(RefinementAttemptDiagnostics.Publication.CANCELLED,RefinementLineageCodec.read(failed.artifact(RefinementLineageCodec.ARTIFACT)).latestPublication().orElseThrow().diagnostic().publication());

   }finally{SwingUtilities.invokeAndWait(()->{if(workflow[0]!=null)workflow[0].close();if(review[0]!=null)review[0].close();owners.closeAll();});}
  }
 }
 @Test void closingDuringOriginalContextPreparationCannotEraseTheNextOwnersExport() throws Exception {
  var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),new ManagedTileCache(directory,decoder),decoder,TileReliabilityPolicy.defaults());
      var owners=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
   var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var workflow=new LegacyRefinementWorkflow[1];var review=new LegacyReviewOwner[1];
   try {
    SwingUtilities.invokeAndWait(()->{var f=fixture(coordinator,owners,work,publications);workflow[0]=value(f,"workflow",LegacyRefinementWorkflow.class);review[0]=value(f,"review",LegacyReviewOwner.class);
     workflow[0].enableRuntimeDiagnostics(()->{workflow[0].close();DiagnosticsRegistry.setLastModernBundle(new Format15Bundle("new-owner","a".repeat(64),"b".repeat(64),Map.of("owner-marker.txt",Format15Artifact.text("owner-marker.txt","new-owner"))));return null;});click(workflow[0],"refineA");});
    settle(work,publications);assertTrue(work.isEmpty());
    var result=export("reentrant-next-owner");assertEquals("new-owner",result.buildIdentity());
    SwingUtilities.invokeAndWait(()->{assertEquals(0,workflow[0].ownedBytes());assertEquals(0,workflow[0].actualNativeCalls());assertTrue(owners.workGate().settlement().toCompletableFuture().isDone());});
   } finally {SwingUtilities.invokeAndWait(()->{if(workflow[0]!=null)workflow[0].close();if(review[0]!=null)review[0].close();owners.closeAll();});}
  }
 }
 @Test void unfinishedCloseRetainsBindingWithoutClaimingCompletedZeroRefusals() throws Exception {
  var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),new ManagedTileCache(directory,decoder),decoder,TileReliabilityPolicy.defaults());
      var owners=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
   var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var workflow=new LegacyRefinementWorkflow[1];var review=new LegacyReviewOwner[1];
   try {
    SwingUtilities.invokeAndWait(()->{var f=fixture(coordinator,owners,work,publications);workflow[0]=value(f,"workflow",LegacyRefinementWorkflow.class);review[0]=value(f,"review",LegacyReviewOwner.class);workflow[0].enableRuntimeDiagnostics(()->null);click(workflow[0],"refineA");});
    settle(work,publications);
    SwingUtilities.invokeAndWait(()->{assertTrue(workflow[0].busy());workflow[0].close();assertTrue(workflow[0].workflowOwnedBytes()>0);assertFalse(owners.workGate().settlement().toCompletableFuture().isDone());});
    var doc=RefinementLineageCodec.read(export("unfinished-close").artifact(RefinementLineageCodec.ARTIFACT));var diagnostic=doc.latestPublication().orElseThrow().diagnostic();
    assertEquals(RefinementAttemptDiagnostics.Publication.CANCELLED,diagnostic.publication());assertEquals(doc.binding().root(),diagnostic.binding().ticket().parent());assertTrue(diagnostic.pipeline().isEmpty());assertEquals(0,diagnostic.materializationRefusals());assertFalse(diagnostic.materializationComplete());
    work.remove().run();assertFalse(owners.workGate().settlement().toCompletableFuture().isDone());SwingUtilities.invokeAndWait(publications.remove());
    assertTrue(owners.workGate().settlement().toCompletableFuture().isDone());assertEquals(0,workflow[0].workflowOwnedBytes());assertTrue(workflow[0].registryOwnedBytes()>0);assertEquals(0,workflow[0].actualNativeCalls());
    DiagnosticsRegistry.setLastBundle(null);assertEquals(0,workflow[0].ownedBytes());
   }finally{SwingUtilities.invokeAndWait(()->{if(workflow[0]!=null)workflow[0].close();if(review[0]!=null)review[0].close();owners.closeAll();});}
  }
 }
 @Test void budgetRefusedCloseKeepsPhysicalAndQueuedPinsUntilSettlementAndReportsUnavailable() throws Exception {
  var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),new ManagedTileCache(directory,decoder),decoder,TileReliabilityPolicy.defaults());
      var owners=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
   var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var workflow=new LegacyRefinementWorkflow[1];var review=new LegacyReviewOwner[1];
   try {
    SwingUtilities.invokeAndWait(()->{var f=fixture(coordinator,owners,work,publications);workflow[0]=value(f,"workflow",LegacyRefinementWorkflow.class);review[0]=value(f,"review",LegacyReviewOwner.class);var original=review[0].originalResult();var first=review[0].selectedCandidate();workflow[0].enableRuntimeDiagnostics(()->LastSlideDebugBundle.fromResult(original,first,first,"preview-open","",Map.of()));click(workflow[0],"refineA");});
    settle(work,publications); // queued native operation owns its actual reserved inputs/result capacity
    SwingUtilities.invokeAndWait(()->{
     assertTrue(workflow[0].busy());var parent=workflow[0].currentStage();
     try{var field=LegacyRefinementWorkflow.class.getDeclaredField("rootMemory");field.setAccessible(true);var memory=(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner)field.get(workflow[0]);memory.reserve(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.MAX_BYTES-workflow[0].ownedBytes()-1024*1024);}catch(Exception failed){throw new RuntimeException(failed);}
     workflow[0].close();assertEquals(parent,workflow[0].currentStage());assertTrue(workflow[0].workflowOwnedBytes()>0);assertFalse(owners.workGate().settlement().toCompletableFuture().isDone());assertFalse(review[0].dataSet().isModified());
    });
    work.remove().run();assertFalse(owners.workGate().settlement().toCompletableFuture().isDone());SwingUtilities.invokeAndWait(()->assertTrue(workflow[0].workflowOwnedBytes()>0));
    SwingUtilities.invokeAndWait(publications.remove());
    SwingUtilities.invokeAndWait(()->{assertEquals(0,workflow[0].ownedBytes());assertEquals(0,workflow[0].workflowOwnedBytes());assertEquals(0,workflow[0].registryOwnedBytes());assertTrue(owners.workGate().settlement().toCompletableFuture().isDone());assertEquals(0,workflow[0].actualNativeCalls());});
    var refusal=assertThrows(IllegalStateException.class,()->DiagnosticsRegistry.writeLatest(directory.resolve("budget-refused.zip").toFile()));assertEquals("Refinement diagnostics unavailable: memory budget reached",refusal.getMessage());
   } finally {SwingUtilities.invokeAndWait(()->{if(workflow[0]!=null)workflow[0].close();if(review[0]!=null)review[0].close();owners.closeAll();});}
  }
 }
 @Test void reentrantHostAddedOwnerCannotReceiveAnOldLegacyApplyExport() throws Exception {
  var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),new ManagedTileCache(directory,decoder),decoder,TileReliabilityPolicy.defaults());var owners=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
   var workflow=new LegacyRefinementWorkflow[1];var review=new LegacyReviewOwner[1];var action=new AlignWayAction[1];
   SwingUtilities.invokeAndWait(()->{
    var f=fixture(coordinator,owners,new LinkedBlockingQueue<>(),new LinkedBlockingQueue<>());workflow[0]=value(f,"workflow",LegacyRefinementWorkflow.class);review[0]=value(f,"review",LegacyReviewOwner.class);action[0]=new AlignWayAction(owners);
    var original=review[0].originalResult();var candidate=review[0].selectedCandidate();var data=review[0].dataSet();var config=review[0].originalConfig().heatmap();var history=org.openstreetmap.josm.data.UndoRedoHandler.getInstance();var added=new AtomicInteger();
    var listener=new org.openstreetmap.josm.data.UndoRedoHandler.CommandQueuePreciseListener(){
     public void commandAdded(org.openstreetmap.josm.data.UndoRedoHandler.CommandAddedEvent event){added.incrementAndGet();owners.open(()->{});DiagnosticsRegistry.setLastModernBundle(new Format15Bundle("new-owner","a".repeat(64),"b".repeat(64),Map.of("owner-marker.txt",Format15Artifact.text("owner-marker.txt","new-owner"))));}
     public void cleaned(org.openstreetmap.josm.data.UndoRedoHandler.CommandQueueCleanedEvent event){}
     public void commandUndone(org.openstreetmap.josm.data.UndoRedoHandler.CommandUndoneEvent event){}
     public void commandRedone(org.openstreetmap.josm.data.UndoRedoHandler.CommandRedoneEvent event){}
    };
    history.addCommandQueuePreciseListener(listener);
    try {
     var confirmation=AlignmentService.assessCandidate(candidate).reviewRequired()?AlignWayAction.confirmLegacyReview(review[0],new AlignmentService(new TileHeatmapSampler(coordinator)),data,original.selection(),original,candidate,config):null;
     var preview=Class.forName(AlignWayAction.class.getName()+"$PreviewSelection");var constructor=preview.getDeclaredConstructor(org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult.class);constructor.setAccessible(true);var selection=constructor.newInstance(candidate,candidate,original);
     var apply=AlignWayAction.class.getDeclaredMethod("applyPreview",org.openstreetmap.josm.data.osm.DataSet.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext.class,preview,org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig.class,Map.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateReviewConfirmation.class,LegacyReviewOwner.class,LegacyRefinementWorkflow.class);apply.setAccessible(true);apply.invoke(action[0],data,original.selection(),selection,config,Map.of(),confirmation,review[0],workflow[0]);assertEquals(1,added.get());assertFalse(workflow[0].ownsPublication());
    }catch(Exception failure){throw new RuntimeException(failure);}finally{history.removeCommandQueuePreciseListener(listener);history.clean(data);}
   });
   try{assertEquals("new-owner",export("new-owner-after-host-callback").buildIdentity());}
   finally{SwingUtilities.invokeAndWait(()->{action[0].destroy();workflow[0].close();review[0].close();owners.closeAll();});}
  }
 }
 @Test void acceptedNotificationFailureKeepsAppliedEvidence() throws Exception {throwingHostNotification(false);}
 @Test void reentrantThrowingNotificationCannotTouchTheNewOwnersUiLogOrExport() throws Exception {throwingHostNotification(true);}
 private void throwingHostNotification(boolean replaceOwner) throws Exception {
  var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),new ManagedTileCache(directory,decoder),decoder,TileReliabilityPolicy.defaults());var owners=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
   var workflow=new LegacyRefinementWorkflow[1];var review=new LegacyReviewOwner[1];var action=new AlignWayAction[1];
   try {SwingUtilities.invokeAndWait(()->{
    var f=fixture(coordinator,owners,new LinkedBlockingQueue<>(),new LinkedBlockingQueue<>());workflow[0]=value(f,"workflow",LegacyRefinementWorkflow.class);review[0]=value(f,"review",LegacyReviewOwner.class);action[0]=new AlignWayAction(owners);
    var original=review[0].originalResult();var candidate=review[0].selectedCandidate();var data=review[0].dataSet();var config=review[0].originalConfig().heatmap();var history=org.openstreetmap.josm.data.UndoRedoHandler.getInstance();var added=new AtomicInteger();var originalGeometry=original.selection().way().getNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList();
    var originalOwner=new PreviewSessionController.Owner(1);var oldEffects=new AtomicInteger();var overlay=new java.util.concurrent.atomic.AtomicReference<>("new-overlay");var log=new java.util.concurrent.atomic.AtomicReference<>("new-log");var accepted=new java.util.concurrent.atomic.AtomicReference<org.openstreetmap.josm.command.Command>();
    var listener=new org.openstreetmap.josm.data.UndoRedoHandler.CommandQueuePreciseListener(){
     public void commandAdded(org.openstreetmap.josm.data.UndoRedoHandler.CommandAddedEvent event){added.incrementAndGet();accepted.set(event.getCommand());if(replaceOwner){owners.open(()->{});DiagnosticsRegistry.setLastModernBundle(new Format15Bundle("new-owner","a".repeat(64),"b".repeat(64),Map.of("owner-marker.txt",Format15Artifact.text("owner-marker.txt","new-owner"))));}throw new IllegalStateException("Synthetic host notification failure");}
     public void cleaned(org.openstreetmap.josm.data.UndoRedoHandler.CommandQueueCleanedEvent event){}
     public void commandUndone(org.openstreetmap.josm.data.UndoRedoHandler.CommandUndoneEvent event){}
     public void commandRedone(org.openstreetmap.josm.data.UndoRedoHandler.CommandRedoneEvent event){}
    };
    history.addCommandQueuePreciseListener(listener);
    try {
     var confirmation=AlignmentService.assessCandidate(candidate).reviewRequired()?AlignWayAction.confirmLegacyReview(review[0],new AlignmentService(new TileHeatmapSampler(coordinator)),data,original.selection(),original,candidate,config):null;
     var preview=Class.forName(AlignWayAction.class.getName()+"$PreviewSelection");var constructor=preview.getDeclaredConstructor(org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult.class);constructor.setAccessible(true);var selection=constructor.newInstance(candidate,candidate,original);
     var apply=AlignWayAction.class.getDeclaredMethod("applyPreview",org.openstreetmap.josm.data.osm.DataSet.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext.class,preview,org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig.class,Map.class,org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateReviewConfirmation.class,LegacyReviewOwner.class,LegacyRefinementWorkflow.class);apply.setAccessible(true);Throwable notification=null;
     try{apply.invoke(action[0],data,original.selection(),selection,config,Map.of(),confirmation,review[0],workflow[0]);}catch(java.lang.reflect.InvocationTargetException failure){notification=failure.getCause();AlignWayAction.ownedApplyFailure(owners,originalOwner,workflow[0],()->{oldEffects.incrementAndGet();overlay.set("old-hidden");log.set("old-ended");});}
     assertEquals(1,added.get());assertSame(accepted.get(),history.getLastCommand());assertNotEquals(originalGeometry,original.selection().way().getNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());
     if(replaceOwner){AlignWayAction.ownedApplyFailure(owners,originalOwner,workflow[0],()->{oldEffects.incrementAndGet();overlay.set("old-hidden");log.set("old-ended");});assertFalse(workflow[0].ownsPublication());assertEquals(0,oldEffects.get());assertEquals("new-overlay",overlay.get());assertEquals("new-log",log.get());}
     assertNull(notification,"Committed command notification failure is not execution rejection");
    }catch(Exception failure){throw new RuntimeException(failure);}finally{history.removeCommandQueuePreciseListener(listener);history.clean(data);}
   });
    if(replaceOwner)assertEquals("new-owner",export("new-owner-after-throwing-host-callback").buildIdentity());
    else {Path file=directory.resolve("accepted-notification-failed.zip");DiagnosticsRegistry.writeLatest(file.toFile());var archive=Format15ArchiveReader.read(file);assertEquals(14,archive.formatVersion());assertTrue(archive.artifact("applied-segment.osm").orElseThrow().bytes().length>0);assertTrue(new String(archive.artifact("verbose-log.txt").orElseThrow().bytes(),java.nio.charset.StandardCharsets.UTF_8).contains("committed-notification-failed"));}
   }
   finally{SwingUtilities.invokeAndWait(()->{action[0].destroy();workflow[0].close();review[0].close();owners.closeAll();});}
  }
 }
 @Test void noninitialLegacyConfirmationBeforeBootstrapSurvivesStrictZipAndNavigation() throws Exception {
  var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",paletteRidge(request.address()),null,Duration.ZERO,""),new ManagedTileCache(directory,decoder),decoder,TileReliabilityPolicy.defaults());var owners=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
   var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var workflow=new LegacyRefinementWorkflow[1];var review=new LegacyReviewOwner[1];var choice=new String[1];
   try {
    SwingUtilities.invokeAndWait(()->{var fixture=paletteFixture(coordinator,owners,work,publications,true);workflow[0]=fixture.workflow();review[0]=fixture.review();var original=review[0].originalResult();var first=review[0].selectedCandidate();workflow[0].enableRuntimeDiagnostics(()->LastSlideDebugBundle.fromResult(original,first,first,"preview-open","",Map.of()));
     var selector=(JComboBox<?>)find(workflow[0].controls(),"candidate");assertTrue(selector.getItemCount()>1);selector.setSelectedIndex(1);choice[0]=workflow[0].selectedChoiceIdentity();var selected=review[0].selectedCandidate();assertTrue(AlignmentService.assessCandidate(selected).reviewRequired());
     var preview=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult(original.selection(),original.capturedHeatmap(),original.candidates(),original.sourcePolyline(),selected.finalPreviewPoints(),original.nodeMoves(),original.diagnostics(),original.tileMosaics(),original.detectorAttempts(),original.applicableCandidates());
     var confirmed=AlignWayAction.confirmLegacyReview(review[0],new AlignmentService(new TileHeatmapSampler(coordinator)),review[0].dataSet(),original.selection(),preview,selected,review[0].originalConfig().heatmap());workflow[0].reviewedLegacy(confirmed);assertEquals(0,workflow[0].actualNativeCalls());assertTrue(work.isEmpty());click(workflow[0],"refineB");
    });
    settle(work,publications);settle(work,publications);var bundle=export("prebootstrap-reviewed-child");var doc=RefinementLineageCodec.read(bundle.artifact(RefinementLineageCodec.ARTIFACT));assertEquals(2,doc.stages().size());assertEquals(choice[0],doc.stages().get(1).parentChoice().orElseThrow().value());
    String marker="\"reviewed\":{\"stage\":\""+doc.binding().root().value()+"\",\"choice\":\""+choice[0]+"\"}";assertTrue(new String(bundle.artifact("review-state.json").bytes(),java.nio.charset.StandardCharsets.UTF_8).contains(marker));
    SwingUtilities.invokeAndWait(()->{assertTrue(workflow[0].actualNativeCalls()>0);click(workflow[0],"undo");((JComboBox<?>)find(workflow[0].controls(),"candidate")).setSelectedIndex(0);});
    assertTrue(new String(export("reviewed-root-after-navigation").artifact("review-state.json").bytes(),java.nio.charset.StandardCharsets.UTF_8).contains(marker));
    SwingUtilities.invokeAndWait(()->{click(workflow[0],"redo");try{var field=LegacyRefinementWorkflow.class.getDeclaredField("confirmation");field.setAccessible(true);assertNull(field.get(workflow[0]),"Historical Legacy review cannot authorize a native child");}catch(ReflectiveOperationException failure){throw new RuntimeException(failure);}});
   }finally{SwingUtilities.invokeAndWait(()->{if(workflow[0]!=null)workflow[0].close();if(review[0]!=null)review[0].close();owners.closeAll();});}
  }
 }
 @Test void actualBothNativeOrdersHistoryCancellationAndAppliedExportStayBoundToTheirExactChoices() throws Exception {
  var decoder=new TileDecoderClassifier();
  try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",paletteRidge(request.address()),null,Duration.ZERO,""),new ManagedTileCache(directory,decoder),decoder,TileReliabilityPolicy.defaults());
      var owners=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
   var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();
   var workflow=new LegacyRefinementWorkflow[1];var review=new LegacyReviewOwner[1];var initial=new String[1];var chosen=new String[1];
   try {
    SwingUtilities.invokeAndWait(()->{
     var f=paletteFixture(coordinator,owners,work,publications);workflow[0]=f.workflow();review[0]=f.review();
     initial[0]=workflow[0].selectedChoiceIdentity();var original=review[0].originalResult();var first=review[0].selectedCandidate();
     workflow[0].enableRuntimeDiagnostics(()->LastSlideDebugBundle.fromResult(original,review[0].selectedCandidate(),first,"preview-open","",Map.of()));
     var choices=(JComboBox<?>)find(workflow[0].controls(),"candidate");assertTrue(choices.getItemCount()>1,"A distinct actual Legacy choice is required");
     int eligible=-1;for(int i=0;i<choices.getItemCount();i++){var choice=(org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls.Choice)choices.getItemAt(i);if(!choice.id().equals(initial[0])&&AlignmentService.assessCandidate(review[0].candidateForChoice(choice.id())).disposition()!=org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateAssessment.Disposition.HARD_BLOCKED){eligible=i;break;}}
     assertTrue(eligible>=0,"Fixture must supply a valid noninitial actual Legacy parent");choices.setSelectedIndex(eligible);chosen[0]=workflow[0].selectedChoiceIdentity();assertNotEquals(initial[0],chosen[0]);click(workflow[0],"refineA");
    });
    settle(work,publications);settle(work,publications); // root bootstrap, then actual A
    var stages=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage[2];
    SwingUtilities.invokeAndWait(()->{assertTrue(workflow[0].nativeSelected(),workflow[0].computationEvidence());stages[0]=workflow[0].currentStage();assertEquals(chosen[0],stages[0].parentChoice().orElseThrow().value());assertTrue(workflow[0].actualNativeCalls()>0);click(workflow[0],"refineB");});
    settle(work,publications);
    SwingUtilities.invokeAndWait(()->{stages[1]=workflow[0].currentStage();assertNotEquals(stages[0].id(),stages[1].id());assertEquals(stages[0].id(),stages[1].parent().orElseThrow());});
    var ab=export("a-b");var abDoc=RefinementLineageCodec.read(ab.artifact(RefinementLineageCodec.ARTIFACT));assertEquals(3,abDoc.stages().size());
    assertEquals(chosen[0],abDoc.stages().get(1).parentChoice().orElseThrow().value());
    SwingUtilities.invokeAndWait(()->{click(workflow[0],"undo");click(workflow[0],"undo");var choices=(JComboBox<?>)find(workflow[0].controls(),"candidate");for(int i=0;i<choices.getItemCount();i++)if(((org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls.Choice)choices.getItemAt(i)).id().equals(initial[0])){choices.setSelectedIndex(i);break;}});
    var changed=RefinementLineageCodec.read(export("changed-root").artifact(RefinementLineageCodec.ARTIFACT));assertEquals(initial[0],changed.binding().currentChoice().value());assertEquals(chosen[0],changed.stages().get(1).parentChoice().orElseThrow().value());assertEquals(3,changed.stages().size());
    SwingUtilities.invokeAndWait(()->{click(workflow[0],"redo");assertEquals(stages[0],workflow[0].currentStage());click(workflow[0],"redo");assertEquals(stages[1],workflow[0].currentStage());click(workflow[0],"undo");click(workflow[0],"undo");click(workflow[0],"refineB");click(workflow[0],"cancel");});
    settle(work,publications);
    var cancelled=RefinementLineageCodec.read(export("cancelled-forward").artifact(RefinementLineageCodec.ARTIFACT));assertEquals(3,cancelled.stages().size());assertEquals(RefinementAttemptDiagnostics.Publication.CANCELLED,cancelled.latestPublication().orElseThrow().diagnostic().publication());assertTrue(cancelled.latestAttempt().isEmpty());
    SwingUtilities.invokeAndWait(()->click(workflow[0],"refineB"));settle(work,publications);
    SwingUtilities.invokeAndWait(()->{assertTrue(workflow[0].nativeSelected(),workflow[0].computationEvidence());click(workflow[0],"refineA");});settle(work,publications);
    var ba=RefinementLineageCodec.read(export("b-a").artifact(RefinementLineageCodec.ARTIFACT));assertEquals(3,ba.stages().size());assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.PROBABILISTIC,ba.stages().get(1).engine());assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.CORRIDOR_AWARE,ba.stages().get(2).engine());
    var handler=org.openstreetmap.josm.data.UndoRedoHandler.getInstance();var before=new ArrayList<org.openstreetmap.josm.data.coor.LatLon>();var after=new ArrayList<org.openstreetmap.josm.data.coor.LatLon>();
    SwingUtilities.invokeAndWait(()->{
     var appliedWay=review[0].originalResult().selection().way();
     handler.clean(review[0].dataSet());before.addAll(review[0].originalResult().selection().way().getNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());
     workflow[0].confirm();assertTrue(workflow[0].canApplyNative());var command=workflow[0].command();AlignWayAction.addReviewedCommand(handler,command);assertSame(command,handler.getLastCommand());
     after.addAll(review[0].originalResult().selection().way().getNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());
     var acceptedPrefix=List.copyOf(handler.getUndoCommands());var redoPrefix=List.copyOf(handler.getRedoCommands());assertThrows(IllegalStateException.class,()->AlignWayAction.addReviewedCommand(handler,command));assertEquals(acceptedPrefix,handler.getUndoCommands());assertEquals(redoPrefix,handler.getRedoCommands());assertEquals(after,appliedWay.getNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());
     workflow[0].appliedDiagnostics();workflow[0].close();review[0].close();assertEquals(0,workflow[0].workflowOwnedBytes());assertTrue(workflow[0].registryOwnedBytes()>0);assertEquals(workflow[0].registryOwnedBytes(),workflow[0].ownedBytes());
     handler.undo();assertEquals(before,appliedWay.getNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());
     var undonePrefix=List.copyOf(handler.getUndoCommands());var pendingRedo=List.copyOf(handler.getRedoCommands());var staleNode=appliedWay.getNode(1);boolean modified=staleNode.isModified();staleNode.put("task6-stale","yes");
     assertThrows(IllegalStateException.class,()->AlignWayAction.addReviewedCommand(handler,command));assertEquals(undonePrefix,handler.getUndoCommands());assertEquals(pendingRedo,handler.getRedoCommands());assertEquals(before,appliedWay.getNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());staleNode.remove("task6-stale");staleNode.setModified(modified);
     handler.redo();assertEquals(after,appliedWay.getNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());
    });
    var applied=export("applied-after-close");String status=new String(applied.artifact("review-state.json").bytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(status.contains("\"state\":\"applied\""));assertTrue(status.contains("observed-full-selected-way"));assertTrue(applied.artifactNames().contains("applied-geographic.csv"));assertEquals(ba.binding().current(),RefinementLineageCodec.read(applied.artifact(RefinementLineageCodec.ARTIFACT)).binding().current());
    DiagnosticsRegistry.setLastBundle(null);assertEquals(0,workflow[0].ownedBytes());
   } finally {SwingUtilities.invokeAndWait(()->{if(workflow[0]!=null)workflow[0].close();if(review[0]!=null){org.openstreetmap.josm.data.UndoRedoHandler.getInstance().clean();review[0].close();}owners.closeAll();});}
  }
 }
 private Format15Bundle export(String name)throws Exception {
  Path file=directory.resolve(name+".zip");SwingUtilities.invokeAndWait(()->{try{DiagnosticsRegistry.writeLatest(file.toFile());}catch(Exception e){throw new RuntimeException(e);}});
  var archive=Format15ArchiveReader.read(file);return new Format15Bundle(archive.buildIdentity(),archive.sourceIdentityHash(),archive.parameterHash(),archive.artifacts());
 }
 private static void settle(LinkedBlockingQueue<Runnable> work,LinkedBlockingQueue<Runnable> publications)throws Exception {assertFalse(work.isEmpty(),"Actual queued worker required");work.remove().run();assertFalse(publications.isEmpty(),"Actual queued publication required");SwingUtilities.invokeAndWait(publications.remove());}
 private static void click(LegacyRefinementWorkflow workflow,String name){var button=(JButton)find(workflow.controls(),name);assertTrue(button.isEnabled(),"Actual control must accept "+name);button.doClick();}
 private record PaletteFixture(LegacyReviewOwner review,LegacyRefinementWorkflow workflow){}
 private static PaletteFixture paletteFixture(TileFetchCoordinator coordinator,PreviewSessionController<LiveBPreviewService.Computed> owners,LinkedBlockingQueue<Runnable> work,LinkedBlockingQueue<Runnable> publications) {
  return paletteFixture(coordinator,owners,work,publications,false);
 }
 private static PaletteFixture paletteFixture(TileFetchCoordinator coordinator,PreviewSessionController<LiveBPreviewService.Computed> owners,LinkedBlockingQueue<Runnable> work,LinkedBlockingQueue<Runnable> publications,boolean requireReview) {
  var data=new org.openstreetmap.josm.data.osm.DataSet();var nodes=new ArrayList<org.openstreetmap.josm.data.osm.Node>();
  for(int i=0;i<5;i++){var n=new org.openstreetmap.josm.data.osm.Node(new org.openstreetmap.josm.data.coor.LatLon(50.000018,14+(i-2)*.00028));data.addPrimitive(n);nodes.add(n);}
  var way=new org.openstreetmap.josm.data.osm.Way();way.put("highway","path");way.setNodes(nodes);data.addPrimitive(way);data.allPrimitives().forEach(p->p.setModified(false));data.setSelected(way);
  var selected=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext(way,0,4,nodes,Set.of(nodes.get(0),nodes.get(4)));
  var config=org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig.withoutCleanup(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig("key","policy","signature","session","all","hot","",".*",org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE,
   org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.LEGACY_V02,false,false,false,true,false,false,false,true,false,false,7,4,3,org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode.RAW_HIGH_RESOLUTION,15,15,7.01,1.56,org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode.COLOR_MAPPING,7));
  var review=LegacyReviewOwner.begin(data,selected,config);var receipt=new TileHeatmapSampler.AcquisitionReceipt[1];
  var result=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selected,null,null,config,new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt actual){receipt[0]=actual;}});
  if(requireReview){
   // Synthetic review-required presentation metadata over factual acquired pixels and unchanged Legacy geometry.
   var choices=result.candidates().stream().map(c->c.withEvidence(c.evidence().withCorridorCoverage(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorCoverage(true,false,4,4,1,0,3,0,0,0,0,0,false,"incomplete-longitudinal-corridor")))).toList();
   result=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult(result.selection(),result.capturedHeatmap(),choices,result.sourcePolyline(),result.previewPolyline(),result.nodeMoves(),result.diagnostics(),result.tileMosaics(),result.detectorAttempts(),List.of());
  }
  review.captured(result,null,receipt[0]);review.select(result.candidates().get(0));
  var owner=owners.open(()->{});var workflow=new LegacyRefinementWorkflow(review,coordinator,review::select,actual->{},status->{},work::add,publications::add);
  owners.replaceWindow(owner,()->{workflow.close();review.close();});workflow.attachOwnership(owners.workGate(),owner,()->{owners.close(owner);review.close();});return new PaletteFixture(review,workflow);
 }
 private static byte[] paletteRidge(ManagedTileAddress address) {
  try {
   var image=new java.awt.image.BufferedImage(512,512,java.awt.image.BufferedImage.TYPE_INT_ARGB);
   int center=switch(address.color()){case "blue"->0xff4ca9ff;case "bluered"->0xffff24b2;case "purple"->0xffbd9cff;case "gray"->0xffe84bea;default->0xfffdfdfd;};
   for(int y=0;y<512;y++){
    double worldY=(address.y()*512.0+y+.5)/(512.0*(1<<address.zoom()));double latitude=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*worldY))));
    double strength=Math.exp(-.5*Math.pow((latitude-50)*111195/2,2));
    for(int x=0;x<512;x++){int background=3+(x*7+y*3)%29;int r=(int)Math.round(background+strength*(((center>>>16)&255)-background));int g=(int)Math.round(background+strength*(((center>>>8)&255)-background));int b=(int)Math.round(background+strength*((center&255)-background));image.setRGB(x,y,0xff000000|r<<16|g<<8|b);}
   }
   var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);return bytes.toByteArray();
  }catch(Exception failed){throw new IllegalStateException(failed);}
 }
 private static Object fixture(TileFetchCoordinator c,PreviewSessionController<LiveBPreviewService.Computed> s,LinkedBlockingQueue<Runnable>w,LinkedBlockingQueue<Runnable>p){try{var m=StepwiseRefinementOwnershipTest.class.getDeclaredMethod("fixture",TileFetchCoordinator.class,PreviewSessionController.class,LinkedBlockingQueue.class,LinkedBlockingQueue.class,AtomicInteger.class);m.setAccessible(true);return m.invoke(null,c,s,w,p,new AtomicInteger());}catch(Exception e){throw new RuntimeException(e);}}
 private static <T>T value(Object record,String name,Class<T>type){try{var m=record.getClass().getDeclaredMethod(name);m.setAccessible(true);return type.cast(m.invoke(record));}catch(Exception e){throw new RuntimeException(e);}}
 private static byte[] ridge(ManagedTileAddress address){try{var m=StepwiseRefinementOwnershipTest.class.getDeclaredMethod("ridge",ManagedTileAddress.class);m.setAccessible(true);return(byte[])m.invoke(null,address);}catch(Exception e){throw new RuntimeException(e);}}
 private static Component find(Container parent,String name){for(var c:parent.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container nested){var found=find(nested,name);if(found!=null)return found;}}return null;}
}

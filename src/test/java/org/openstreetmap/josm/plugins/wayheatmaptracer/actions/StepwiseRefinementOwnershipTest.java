package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

/** Actual shared action-entry/worker glue. GUI window coverage remains a separate host gate. */
class StepwiseRefinementOwnershipTest {
    @TempDir Path temp;
    @BeforeEach void setup() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        var content=MainApplication.class.getDeclaredField("contentPanePrivate");content.setAccessible(true);content.set(null,new JPanel());
    }
    @Test void sameWayAndConfigSupersessionWaitsForActualLateResultDiscard() throws Exception {exercise(false);}
    @Test void ignoredInterruptCannotReleaseNativeInputPinsBeforeWorkerAndQueuedCleanup() throws Exception {exercise(true);}
    @Test void comboChoiceSelectionPinsAdmittedMetadataForTheNextRefinement() throws Exception {selectedInputPin(false);}
    @Test void sameChoiceSelectionPinsAdmittedMetadataForTheNextRefinement() throws Exception {selectedInputPin(true);}
    private void selectedInputPin(boolean sameChoice) throws Exception {
        var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());
            var session=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
            var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var state=new Fixture[1];var nativeCalls=new AtomicInteger();
            try {
                SwingUtilities.invokeAndWait(()->{state[0]=fixture(coordinator,session,work,publications,new AtomicInteger());((JButton)find(state[0].workflow.controls(),"refineA")).doClick();});
                work.remove().run();SwingUtilities.invokeAndWait(publications.remove());work.remove().run();SwingUtilities.invokeAndWait(publications.remove());
                SwingUtilities.invokeAndWait(()->{
                    var f=state[0];assertTrue(f.workflow.nativeSelected());var old=f.workflow.currentStage();nativeCalls.set(f.workflow.actualNativeCalls());
                    if(sameChoice)workflowSession(f.workflow).select(old.selected());
                    else {var combo=(JComboBox<?>)find(f.workflow.controls(),"candidate");combo.setSelectedIndex((combo.getSelectedIndex()+1)%combo.getItemCount());}
                    assertNotSame(old,f.workflow.currentStage(),"Selection creates a new wrapper around the same admitted candidate metadata");assertSourcesLeased(f.workflow,f.workflow.currentStage());
                    ((JButton)find(f.workflow.controls(),"refineB")).doClick();assertTrue(f.workflow.busy(),"The selected input must dispatch using its stable admitted metadata identity");assertEquals(1,work.size());
                    ((JButton)find(f.workflow.controls(),"cancel")).doClick();
                });
                work.remove().run();assertTrue(state[0].workflow.ownedBytes()>0);SwingUtilities.invokeAndWait(publications.remove());
                SwingUtilities.invokeAndWait(()->{var f=state[0];assertFalse(f.workflow.busy());assertTrue(f.workflow.nativeSelected());assertSourcesLeased(f.workflow,f.workflow.currentStage());assertEquals(nativeCalls.get(),f.workflow.actualNativeCalls());assertFalse(f.data.isModified());});
            }finally{
                SwingUtilities.invokeAndWait(()->{if(state[0]!=null)state[0].workflow.close();});while(!work.isEmpty())work.remove().run();while(!publications.isEmpty())SwingUtilities.invokeAndWait(publications.remove());
                SwingUtilities.invokeAndWait(()->{if(state[0]!=null){assertEquals(0,state[0].workflow.ownedBytes());state[0].review.close();}session.closeAll();});
            }
        }
    }
    @Test void returningToLegacyKeepsTheOriginalManagedSourceAuthority() throws Exception {
        var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),
                new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());
            var session=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
            var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var state=new Fixture[1];
            SwingUtilities.invokeAndWait(()->{state[0]=fixture(coordinator,session,work,publications,new AtomicInteger());((JButton)find(state[0].workflow.controls(),"refineA")).doClick();});
            work.remove().run();SwingUtilities.invokeAndWait(publications.remove());work.remove().run();SwingUtilities.invokeAndWait(publications.remove());
            var previous=new RefinementAttemptDiagnostics[1];var child=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage[1];
            SwingUtilities.invokeAndWait(()->{
                var f=state[0];assertTrue(f.workflow.nativeSelected());previous[0]=f.workflow.latestAttemptDiagnostics().orElseThrow();child[0]=f.workflow.currentStage();
                assertEquals(RefinementAttemptDiagnostics.Publication.PUBLISHED,previous[0].publication());
                ((JButton)find(f.workflow.controls(),"undo")).doClick();assertFalse(f.workflow.nativeSelected());
                ((JButton)find(f.workflow.controls(),"refineB")).doClick();assertTrue(f.workflow.latestAttemptDiagnostics().isEmpty());
                ((JButton)find(f.workflow.controls(),"cancel")).doClick();
            });
            work.remove().run();SwingUtilities.invokeAndWait(publications.remove());
            SwingUtilities.invokeAndWait(()->{
                var f=state[0];try {
                    var latest=f.workflow.latestAttemptDiagnostics().orElseThrow();assertEquals(previous[0].binding().ticket().sequence()+1,latest.binding().ticket().sequence());
                    assertEquals(RefinementAttemptDiagnostics.Publication.CANCELLED,latest.publication());assertEquals(RefinementAttemptDiagnostics.Phase.ACQUISITION,latest.phase());
                    assertTrue(latest.pipeline().isEmpty(),"Cancelled before pipeline entry has no native/work receipt");
                    assertEquals(RefinementAttemptDiagnostics.Publication.PUBLISHED,previous[0].publication(),"Previously returned snapshot remains immutable");
                    assertFalse(f.workflow.nativeSelected());((JButton)find(f.workflow.controls(),"redo")).doClick();assertEquals(child[0],f.workflow.currentStage());
                    ((JButton)find(f.workflow.controls(),"undo")).doClick();assertFalse(f.workflow.nativeSelected());
                    var result=f.review.originalResult();var candidate=f.review.selectedCandidate();
                    var service=new AlignmentService(new TileHeatmapSampler(coordinator));
                    CandidateReviewConfirmation confirmation=AlignmentService.assessCandidate(candidate).reviewRequired()
                        ?AlignWayAction.confirmLegacyReview(f.review,service,f.data,result.selection(),result,candidate,f.config.heatmap()):null;
                    assertNotNull(AlignWayAction.createLegacyReviewCommand(f.review,service,f.data,result.selection(),result,candidate,f.config.heatmap(),confirmation));
                    var apply=new JButton("Apply");var confirm=new JButton("Confirm");
                    var refresh=AlignWayAction.createReviewButtonRefresh(f.review,apply,confirm,()->new AlignWayAction.ReviewButtonContext(
                        AlignmentService.assessCandidate(candidate).disposition(),confirmation!=null,false,false,false,true));
                    refresh.run();assertTrue(apply.isEnabled());
                    coordinator.updateActiveGeneration(new ManagedTileGeneration(8));coordinator.updateActiveGeneration(new ManagedTileGeneration(7));
                    assertFalse(f.review.invalidated(),"No dataset or preference invalidation may mask the original receipt check");
                    f.review.requireOriginalAttemptCurrent();refresh.run();assertFalse(apply.isEnabled());assertFalse(confirm.isEnabled());
                    assertThrows(IllegalStateException.class,()->AlignWayAction.createLegacyReviewCommand(f.review,service,f.data,result.selection(),result,candidate,f.config.heatmap(),confirmation));
                    assertFalse(f.data.isModified());
                }finally{f.workflow.close();f.review.close();session.closeAll();}
            });
        }
    }
    @Test void actualNativeEntryCancelWaitsForPhysicalAndQueuedResultSettlement() throws Exception {
        var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),
                new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());
            var session=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
            var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var state=new Fixture[1];var displays=new AtomicInteger();
            var cancelled=new CompletableFuture<LegacyRefinementWorkflow.NativeEntry>();
            try {
                SwingUtilities.invokeAndWait(()->{
                    state[0]=fixture(coordinator,session,work,publications,displays);var workflow=state[0].workflow;
                    workflow.firstNativeEntry().whenComplete((entry,failure)->{
                        try {
                            assertNull(failure);assertTrue(SwingUtilities.isEventDispatchThread());assertTrue(entry.entered());assertTrue(workflow.ownsNativeEntry(entry));
                            assertEquals(AlignmentMode.PRECISE_SHAPE,entry.ticket().mode());assertTrue(workflow.ownedBytes()>0);assertFalse(entry.settlement().toCompletableFuture().isDone());
                            var cancel=(JButton)find(workflow.controls(),"cancel");assertTrue(cancel.isEnabled());cancel.doClick();assertTrue(entry.cancellationRequested());cancelled.complete(entry);
                        }catch(Throwable failed){cancelled.completeExceptionally(failed);}
                    });
                    ((JButton)find(workflow.controls(),"refineA")).doClick();
                });
                work.remove().run();SwingUtilities.invokeAndWait(publications.remove());
                work.remove().run(); // real production native call; entry queues the real Cancel on EDT
                var entry=cancelled.get(5,TimeUnit.SECONDS);
                assertTrue(entry.workerReturned());assertFalse(entry.settlement().toCompletableFuture().isDone());
                assertFalse(session.workGate().settlement().toCompletableFuture().isDone());assertTrue(state[0].workflow.ownedBytes()>0);assertEquals(0,displays.get());
                SwingUtilities.invokeAndWait(publications.remove());
                assertTrue(entry.cancellationObserved());assertTrue(entry.settlement().toCompletableFuture().isDone());assertTrue(session.workGate().settlement().toCompletableFuture().isDone());
                SwingUtilities.invokeAndWait(()->{assertFalse(state[0].workflow.busy());assertFalse(state[0].workflow.nativeSelected());assertEquals(entry.ticket().parent(),state[0].workflow.currentStage().id());assertFalse(state[0].data.isModified());assertEquals(0,displays.get());var diagnostic=attemptDiagnostics(state[0].workflow).orElseThrow();
                    assertEquals(entry.ticket(),diagnostic.binding().ticket());assertEquals(entry.ticket().parent(),diagnostic.binding().rootStage());
                    assertEquals(RefinementAttemptDiagnostics.Publication.CANCELLED,diagnostic.publication());assertEquals(RefinementAttemptDiagnostics.Cause.CANCELLED,diagnostic.cause());
                    var receipt=diagnostic.pipeline().orElseThrow();
                    boolean knownEntry=receipt.attempts().stream().anyMatch(a->a.nativeCalls().orElse(0)>0);
                    boolean unavailableEntry=receipt.attempts().stream().anyMatch(a->(a.kind()==RefinementPipeline.AttemptKind.WHOLE||a.kind()==RefinementPipeline.AttemptKind.INTERVAL)&&a.nativeCalls().isEmpty());
                    assertTrue(knownEntry||unavailableEntry,"Native entry must preserve its available receipt or explicit receipt unavailability: "+receipt.attempts().stream().map(a->a.kind()+":"+a.nativeCalls()+":"+a.reason()).toList());
                    if(unavailableEntry)assertTrue(receipt.actualWork().isEmpty(),"Missing native receipt cannot become measured zero work");
                    if(receipt.actualWork().isEmpty())assertTrue(receipt.attempts().stream().anyMatch(a->a.actualWork().isEmpty()&&(a.nativeCalls().isEmpty()||a.nativeCalls().orElse(0)>0)),"Unavailable native work must remain explicitly unavailable");
                    assertEquals(RefinementAttemptDiagnostics.Availability.AVAILABLE,state[0].workflow.attemptDiagnosticsAvailability());});
            } finally {SwingUtilities.invokeAndWait(()->{if(state[0]!=null){state[0].workflow.close();state[0].review.close();}session.closeAll();});}
        }
    }
    @Test void actualNoSignalResourceLimitRetainsItsNoChildReceiptAndParent() throws Exception {
        var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",emptyRaster(),null,Duration.ZERO,""),
                new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());
            var session=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
            var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var state=new Fixture[1];var displays=new AtomicInteger();
            try {
                SwingUtilities.invokeAndWait(()->{state[0]=fixture(coordinator,session,work,publications,displays);((JButton)find(state[0].workflow.controls(),"refineB")).doClick();});
                work.remove().run();SwingUtilities.invokeAndWait(publications.remove());
                var root=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage[1];SwingUtilities.invokeAndWait(()->root[0]=state[0].workflow.currentStage());
                work.remove().run();SwingUtilities.invokeAndWait(publications.remove());
                SwingUtilities.invokeAndWait(()->{
                    assertFalse(state[0].workflow.nativeSelected());assertEquals(root[0],state[0].workflow.currentStage());assertFalse(state[0].data.isModified());assertEquals(0,displays.get());
                    var diagnostic=attemptDiagnostics(state[0].workflow).orElseThrow();var receipt=diagnostic.pipeline().orElseThrow();
                    assertEquals(root[0].id(),diagnostic.binding().rootStage());assertEquals(root[0].id(),diagnostic.binding().ticket().parent());assertEquals(root[0].selected(),diagnostic.binding().ticket().choice());
                    assertEquals(RefinementAttemptDiagnostics.Publication.NO_CHILD,diagnostic.publication());
                    assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Outcome.RESOURCE_LIMIT,receipt.outcome());
                    assertEquals(1,receipt.attempts().stream().map(RefinementOperationReceipt.Attempt::palette).distinct().count());
                    assertTrue(receipt.truncated());
                    for(var attempt:receipt.attempts())if(attempt.kind()==RefinementPipeline.AttemptKind.UNATTEMPTED){assertEquals(0,attempt.nativeCalls().orElseThrow());assertTrue(attempt.nativeStatus().isEmpty());}
                    assertTrue(receipt.actualWork().isPresent());assertTrue(state[0].workflow.ownedBytes()>0);
                });
            } finally {SwingUtilities.invokeAndWait(()->{if(state[0]!=null){state[0].workflow.close();state[0].review.close();}session.closeAll();});}
        }
    }
    private static Optional<RefinementAttemptDiagnostics> attemptDiagnostics(LegacyRefinementWorkflow workflow) {return workflow.latestAttemptDiagnostics();}
    private static byte[] emptyRaster() {
        try {var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB),"png",bytes);return bytes.toByteArray();}
        catch(Exception failure){throw new IllegalStateException(failure);}
    }
    @Test void heldReplacementOriginalEpochAbaIsStaleWithoutRewritingPipelineFacts() throws Exception {failedReplacement(true);}
    @Test void throwingReplacementDisplayKeepsParentForwardAndOnlyPresentedGeometryApplicable() throws Exception {failedReplacement(false);}
    @Test void staleOriginalEpochCannotBeMaskedByManualCancellation() throws Exception {failedReplacement(true,true,false);}
    @Test void failedRootRestorationBlocksUntilExactForwardChildIsPresented() throws Exception {failedReplacement(false,false,true);}
    private void failedReplacement(boolean sourceStale) throws Exception {failedReplacement(sourceStale,false,false);}
    private void failedReplacement(boolean sourceStale,boolean manualCancel,boolean restoreFails) throws Exception {
        var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),
                new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());
            var session=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
            var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var state=new Fixture[1];
            var throwDisplay=new java.util.concurrent.atomic.AtomicBoolean();var throwRoot=new java.util.concurrent.atomic.AtomicBoolean();
            var shown=new java.util.concurrent.atomic.AtomicReference<List<org.openstreetmap.josm.data.coor.EastNorth>>();
            var before=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage[2];var bytes=new long[1];
            try {
                SwingUtilities.invokeAndWait(()->{
                    state[0]=fixture(coordinator,session,work,publications,actual->{if(throwDisplay.getAndSet(false))throw new IllegalStateException("synthetic display refusal");shown.set(actual.candidate().finalPreviewPoints());},root->{if(throwRoot.get())throw new IllegalStateException("synthetic root restoration refusal");shown.set(root.finalPreviewPoints());});
                    ((JButton)find(state[0].workflow.controls(),"refineA")).doClick();
                });
                work.remove().run();SwingUtilities.invokeAndWait(publications.remove());work.remove().run();SwingUtilities.invokeAndWait(publications.remove());
                SwingUtilities.invokeAndWait(()->{
                    var f=state[0];assertTrue(f.workflow.nativeSelected());before[1]=f.workflow.currentStage();assertSourcesLeased(f.workflow,before[1]);
                    ((JButton)find(f.workflow.controls(),"undo")).doClick();before[0]=f.workflow.currentStage();bytes[0]=f.workflow.ownedBytes();
                    assertEquals(f.workflow.selectedGeometry(),shown.get());((JButton)find(f.workflow.controls(),"refineB")).doClick();
                });
                work.remove().run(); // exact successful replacement result is held before publication
                assertFalse(session.workGate().settlement().toCompletableFuture().isDone());
                SwingUtilities.invokeAndWait(()->{
                    assertSourcesLeased(state[0].workflow,before[1]);
                    assertFalse(state[0].review.invalidated());assertFalse(state[0].data.isModified());
                    if(sourceStale){coordinator.updateActiveGeneration(new ManagedTileGeneration(8));coordinator.updateActiveGeneration(new ManagedTileGeneration(7));}
                    else {throwDisplay.set(true);throwRoot.set(restoreFails);}
                    if(manualCancel)((JButton)find(state[0].workflow.controls(),"cancel")).doClick();
                    assertFalse(state[0].review.invalidated(),"Dataset/preferences must not mask source epoch or presentation failure");
                });
                SwingUtilities.invokeAndWait(publications.remove());
                SwingUtilities.invokeAndWait(()->{
                    var f=state[0];var diagnostic=f.workflow.latestAttemptDiagnostics().orElseThrow();
                    assertEquals(before[0],f.workflow.currentStage(),"Failed replacement must preserve exact parent");
                    assertEquals(List.of(before[0],before[1]),workflowSession(f.workflow).history(),"Failed replacement must preserve old forward history");
                    assertSourcesLeased(f.workflow,before[1]);
                    var retained=before[1].candidates().stream().flatMap(c->c.payload().nativeReview().orElseThrow().retainedSources().values().stream()).distinct().toList();
                    for(var source:retained)if(sourceStale)assertThrows(IllegalStateException.class,source::requireCurrent);else source.requireCurrent();
                    assertEquals(f.workflow.selectedGeometry(),shown.get());assertEquals(!restoreFails,f.workflow.hasCurrentPresentation());assertFalse(f.workflow.canApplyNative());
                    assertThrows(IllegalStateException.class,f.workflow::confirm);assertThrows(IllegalStateException.class,f.workflow::command);
                    var apply=new JButton("Apply");var confirm=new JButton("Confirm");
                    var refresh=AlignWayAction.createReviewButtonRefresh(f.review,apply,confirm,()->new AlignWayAction.ReviewButtonContext(
                        AlignmentService.assessCandidate(f.review.selectedCandidate()).disposition(),true,false,false,false,f.workflow.hasCurrentPresentation()));
                    refresh.run();if(restoreFails){assertFalse(apply.isEnabled());assertFalse(confirm.isEnabled());}
                    assertEquals(sourceStale?RefinementAttemptDiagnostics.Publication.STALE:RefinementAttemptDiagnostics.Publication.FAILED,diagnostic.publication());
                    assertEquals(RefinementAttemptDiagnostics.Phase.PUBLICATION,diagnostic.phase());
                    assertEquals(sourceStale?RefinementAttemptDiagnostics.Cause.STALE:RefinementAttemptDiagnostics.Cause.PRESENTATION_FAILED,diagnostic.cause());
                    assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Outcome.COMPLETE,diagnostic.pipeline().orElseThrow().outcome(),"Later failure must not rewrite actual completed pipeline outcome");
                    assertFalse(f.workflow.busy());assertEquals(bytes[0],f.workflow.ownedBytes(),"Only retained history and one diagnostic slot may remain charged");
                    assertTrue(session.workGate().settlement().toCompletableFuture().isDone());assertFalse(f.data.isModified());
                    throwRoot.set(false);((JButton)find(f.workflow.controls(),"redo")).doClick();assertEquals(before[1],f.workflow.currentStage());assertEquals(f.workflow.selectedGeometry(),shown.get());
                    if(sourceStale){assertFalse(f.workflow.canApplyNative());assertThrows(IllegalStateException.class,f.workflow::confirm);}
                    else {f.workflow.confirm();assertTrue(f.workflow.canApplyNative(),"Safely restored, successfully presented forward child must remain usable");}
                    assertEquals(diagnostic,f.workflow.latestAttemptDiagnostics().orElseThrow());
                });
            } finally {SwingUtilities.invokeAndWait(()->{if(state[0]!=null){state[0].workflow.close();state[0].review.close();assertEquals(0,state[0].workflow.ownedBytes());}session.closeAll();});}
        }
    }
    @Test void closeDuringChildPreparationCannotRedisplayTheOldNativeParent() throws Exception {invalidatedPreparation(0);}
    @Test void supersessionDuringChildPreparationCannotRedisplayIntoTheNextOwner() throws Exception {invalidatedPreparation(1);}
    @Test void liveOwnerCancellationDuringChildPreparationStillRestoresItsParent() throws Exception {invalidatedPreparation(2);}
    private void invalidatedPreparation(int mode) throws Exception {
        var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),
                new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());
            var session=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
            var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var state=new Fixture[1];
            var invalidateNext=new java.util.concurrent.atomic.AtomicBoolean();var invalidated=new java.util.concurrent.atomic.AtomicBoolean();var lateDisplays=new AtomicInteger();
            var shown=new java.util.concurrent.atomic.AtomicReference<List<org.openstreetmap.josm.data.coor.EastNorth>>();
            var marker=List.of(new org.openstreetmap.josm.data.coor.EastNorth(123,456));var nextStarted=new CountDownLatch(1);var nextAction=new AlignWayAction[1];
            var parent=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage[1];var history=new java.util.concurrent.atomic.AtomicReference<List<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage>>();
            var retainedSession=new RefinementSession[1];var parentGeometry=new java.util.concurrent.atomic.AtomicReference<List<org.openstreetmap.josm.data.coor.EastNorth>>();var retainedBytes=new long[1];
            try {
                SwingUtilities.invokeAndWait(()->{
                    state[0]=fixture(coordinator,session,work,publications,actual->{
                        if(invalidated.get())lateDisplays.incrementAndGet();
                        shown.set(actual.candidate().finalPreviewPoints());
                        if(invalidateNext.getAndSet(false)) {
                            assertTrue(state[0].workflow.busy());assertTrue(state[0].workflow.ownedBytes()>0);assertFalse(session.workGate().settlement().toCompletableFuture().isDone());
                            if(mode==0)state[0].workflow.close();
                            else if(mode==1) {
                                nextAction[0]=new AlignWayAction(session);
                                assertFalse(nextAction[0].requestPreparedInvocation(new Object(),()->{session.workGate().adopt(new Object(),()->{});shown.set(marker);nextStarted.countDown();},()->{}));
                            } else ((JButton)find(state[0].workflow.controls(),"cancel")).doClick();
                            if(mode!=2)invalidated.set(true);
                            shown.set(marker);
                        }
                    },root->shown.set(root.finalPreviewPoints()));
                    ((JButton)find(state[0].workflow.controls(),"refineA")).doClick();
                });
                work.remove().run();SwingUtilities.invokeAndWait(publications.remove());work.remove().run();SwingUtilities.invokeAndWait(publications.remove());
                SwingUtilities.invokeAndWait(()->{
                    var f=state[0];assertTrue(f.workflow.nativeSelected());parent[0]=f.workflow.currentStage();retainedSession[0]=workflowSession(f.workflow);history.set(retainedSession[0].history());
                    parentGeometry.set(f.workflow.selectedGeometry());retainedBytes[0]=f.workflow.ownedBytes();invalidateNext.set(true);((JButton)find(f.workflow.controls(),"refineB")).doClick();
                });
                work.remove().run();assertFalse(session.workGate().settlement().toCompletableFuture().isDone());SwingUtilities.invokeAndWait(publications.remove());
                if(mode==1)assertTrue(nextStarted.await(5,TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(()->{
                    var f=state[0];assertEquals(0,lateDisplays.get(),"Lost owner must never invoke its old display consumer during recovery");
                    assertEquals(history.get(),retainedSession[0].history());assertEquals(parent[0],retainedSession[0].current());
                    assertFalse(f.workflow.busy());assertTrue(session.workGate().settlement().toCompletableFuture().isDone());assertFalse(f.data.isModified());
                    if(mode==2) {
                        assertEquals(parentGeometry.get(),shown.get());assertTrue(f.workflow.hasCurrentPresentation());
                        assertEquals(RefinementAttemptDiagnostics.Publication.CANCELLED,f.workflow.latestAttemptDiagnostics().orElseThrow().publication());
                        assertEquals(retainedBytes[0],f.workflow.ownedBytes());f.workflow.confirm();assertTrue(f.workflow.canApplyNative());
                    } else {
                        assertEquals(marker,shown.get(),"Old recovery must not overwrite or hide newer/closed view state");assertEquals(0,f.workflow.ownedBytes());
                        assertTrue(f.workflow.latestAttemptDiagnostics().isEmpty());assertEquals(RefinementAttemptDiagnostics.Availability.NONE,f.workflow.attemptDiagnosticsAvailability());
                        assertFalse(f.workflow.hasCurrentPresentation());assertFalse(f.workflow.canApplyNative());
                    }
                });
            } finally {SwingUtilities.invokeAndWait(()->{if(state[0]!=null){state[0].workflow.close();state[0].review.close();}if(nextAction[0]!=null)nextAction[0].destroy();session.closeAll();});}
        }
    }
    private static RefinementSession workflowSession(LegacyRefinementWorkflow workflow) {
        try {var field=LegacyRefinementWorkflow.class.getDeclaredField("session");field.setAccessible(true);return (RefinementSession)field.get(workflow);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static void assertSourcesLeased(LegacyRefinementWorkflow workflow,org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage stage) {
        try {
            var field=LegacyRefinementWorkflow.class.getDeclaredField("rootMemory");field.setAccessible(true);
            var memory=(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner)field.get(workflow);
            long before=memory.currentBytes();var sources=stage.candidates().stream().flatMap(c->c.payload().nativeReview().orElseThrow().retainedSources().values().stream()).distinct().toList();
            assertFalse(sources.isEmpty());try(var probe=memory.child("source-lease-check")){sources.forEach(s->s.retainInto(probe));assertEquals(before,memory.currentBytes(),"Actual source groups remain charged once across old forward/queued replacement pins");}
            assertEquals(before,memory.currentBytes());
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private void exercise(boolean holdWorker) throws Exception {
        var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),
                new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());
            var session=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
            var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var fixture=new Fixture[1];
            var normal=new AlignWayAction[1];var precise=new AlignWayAction[1];var move=new AlignWayAction[1];
            var display=new AtomicInteger();var nextPrecise=new AtomicInteger();var nextMove=new AtomicInteger();var resumed=new CountDownLatch(1);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var ignored=new CountDownLatch(1);Thread running=null;
            try {
                SwingUtilities.invokeAndWait(()->{
                    normal[0]=new AlignWayAction(session);precise[0]=new AlignWayAction(AlignmentMode.PRECISE_SHAPE,session);move[0]=new AlignWayAction(AlignmentMode.MOVE_EXISTING_NODES,session);
                    assertTrue(normal[0].requestPreparedInvocation(new Object(),()->fail("Idle start is immediate"),()->{}));
                    fixture[0]=fixture(coordinator,session,work,publications,display);
                    assertEquals(AlignmentMode.PRECISE_SHAPE,normal[0].effectiveConfig(fixture[0].config.heatmap()).alignmentMode());
                    assertEquals(AlignmentMode.PRECISE_SHAPE,precise[0].effectiveConfig(fixture[0].config.heatmap()).alignmentMode());
                    ((JButton)find(fixture[0].workflow.controls(),"refineA")).doClick();
                });
                work.remove().run();SwingUtilities.invokeAndWait(publications.remove()); // actual bootstrap -> queued native operation
                assertEquals(1,work.size());
                if(holdWorker) {
                    Runnable actual=work.remove();running=new Thread(()->{
                        entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException interrupted){ignored.countDown();}
                        actual.run();
                    });running.start();assertTrue(entered.await(3,TimeUnit.SECONDS));
                } else {
                    work.remove().run(); // actual Task4 result is complete, publication intentionally held
                    assertFalse(publications.isEmpty());assertTrue(fixture[0].workflow.actualNativeCalls()>0);
                }
                SwingUtilities.invokeAndWait(()->{
                    assertTrue(fixture[0].workflow.busy());assertTrue(fixture[0].workflow.ownedBytes()>0);
                    assertFalse(fixture[0].review.invalidated(),"No epoch change may mask missing global ownership");
                    assertFalse(precise[0].requestPreparedInvocation(new Object(),nextPrecise::incrementAndGet,()->{}));
                    assertFalse(move[0].requestPreparedInvocation(new Object(),()->{nextMove.incrementAndGet();resumed.countDown();},()->{}));
                    assertFalse(fixture[0].review.invalidated());assertFalse(fixture[0].data.isModified());
                    assertFalse(session.workGate().settlement().toCompletableFuture().isDone());
                    assertTrue(fixture[0].workflow.ownedBytes()>0,"Cancelled/closed UI still owns input and queued-result reservations");
                    assertEquals(0,nextPrecise.get());assertEquals(0,nextMove.get());assertEquals(0,display.get());
                });
                if(holdWorker) {
                    running.interrupt();assertTrue(ignored.await(3,TimeUnit.SECONDS));
                    assertFalse(session.workGate().settlement().toCompletableFuture().isDone());
                    release.countDown();running.join(5000);assertFalse(running.isAlive());
                }
                assertTrue(fixture[0].workflow.ownedBytes()>0,"Physical return alone cannot release queued publication input");
                SwingUtilities.invokeAndWait(publications.remove());
                assertTrue(resumed.await(3,TimeUnit.SECONDS));
                assertEquals(0,nextPrecise.get(),"Superseded queued intent never starts");assertEquals(1,nextMove.get());assertEquals(0,display.get(),"Late old output cannot overwrite overlay/publication");
                assertEquals(0,fixture[0].workflow.ownedBytes());assertTrue(session.workGate().settlement().toCompletableFuture().isDone());assertTrue(attemptDiagnostics(fixture[0].workflow).isEmpty(),"Closed/superseded owner cannot retain/publish late attempt diagnostics");
            } finally {
                release.countDown();if(running!=null)running.join(5000);
                SwingUtilities.invokeAndWait(()->{if(fixture[0]!=null){fixture[0].workflow.close();fixture[0].review.close();}if(normal[0]!=null)normal[0].destroy();if(precise[0]!=null)precise[0].destroy();if(move[0]!=null)move[0].destroy();});
            }
        }
    }
    private record Fixture(DataSet data,AlignmentConfig config,LegacyReviewOwner review,LegacyRefinementWorkflow workflow){ }
    private static Fixture fixture(TileFetchCoordinator coordinator,PreviewSessionController<LiveBPreviewService.Computed> session,
            LinkedBlockingQueue<Runnable> work,LinkedBlockingQueue<Runnable> publications,AtomicInteger displayed) {
        return fixture(coordinator,session,work,publications,actual->displayed.incrementAndGet(),root->{});
    }
    private static Fixture fixture(TileFetchCoordinator coordinator,PreviewSessionController<LiveBPreviewService.Computed> session,
            LinkedBlockingQueue<Runnable> work,LinkedBlockingQueue<Runnable> publications,
            java.util.function.Consumer<LegacyRefinementWorkflow.Display> displayed,java.util.function.Consumer<CenterlineCandidate> rootDisplay) {
        var data=new DataSet();var nodes=new ArrayList<Node>();for(int i=0;i<5;i++){var n=new Node(new LatLon(50.000018,14+(i-2)*.00028));data.addPrimitive(n);nodes.add(n);}
        var way=new Way();way.put("highway","path");way.setNodes(nodes);data.addPrimitive(way);data.allPrimitives().forEach(p->p.setModified(false));data.setSelected(way);
        var selected=new SelectionContext(way,0,4,nodes,Set.of(nodes.get(0),nodes.get(4)));
        var config=AlignmentConfig.withoutCleanup(new ManagedHeatmapConfig("key","policy","signature","session","all","hot","",".*",AlignmentMode.PRECISE_SHAPE,
            TrackerMode.LEGACY_V02,false,false,false,false,false,false,false,true,false,false,7,4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,7.01,1.56,IntensitySamplingMode.DIRECT_VALUE,7));
        var review=LegacyReviewOwner.begin(data,selected,config);var receipt=new TileHeatmapSampler.AcquisitionReceipt[1];
        var result=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selected,null,null,config,new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt actual){receipt[0]=actual;}});
        review.captured(result,null,receipt[0]);review.select(result.candidates().get(0));
        var owner=session.open(()->{});
        rootDisplay.accept(review.selectedCandidate());
        var workflow=new LegacyRefinementWorkflow(review,coordinator,candidate->{review.select(candidate);rootDisplay.accept(candidate);},displayed,status->{},work::add,publications::add);
        session.replaceWindow(owner,()->{workflow.close();review.close();});
        workflow.attachOwnership(session.workGate(),owner,()->{session.close(owner);review.close();});
        return new Fixture(data,config,review,workflow);
    }
    private static Component find(Container parent,String name){for(var c:parent.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container nested){var found=find(nested,name);if(found!=null)return found;}}return null;}
    private static byte[] ridge(ManagedTileAddress address) {
        try {var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<512;y++){
            double world=(address.y()*512.0+y+.5)/(512.0*(1<<address.zoom()));double latitude=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*world))));
            int red=(int)Math.round(245*Math.exp(-.5*Math.pow((latitude-50)*111195/2,2)));
            for(int x=0;x<512;x++)image.setRGB(x,y,(red==0?0:0xff000000)|(red<<16)|Math.min(red,(x/8+y/8)%4)<<8);
        }var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();}catch(Exception failure){throw new IllegalStateException(failure);}
    }
}
